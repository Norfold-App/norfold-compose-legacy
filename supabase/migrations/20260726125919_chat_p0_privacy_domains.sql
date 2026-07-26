-- Pre-Beta Stage 1 / P0: make privacy decisions explicit and fail closed.
--
-- This migration intentionally corrects, rather than edits, the historical
-- roster/social migrations.  Existing clients can continue reading their local
-- cache, but new remote channel writes must carry an explicit visibility value.

create schema if not exists private;
revoke all on schema private from public, anon;

create table public.workspace_chat_channels (
  workspace_id uuid not null references public.workspaces(id) on delete cascade,
  channel_id uuid not null,
  visibility text not null check (visibility in ('public', 'private')),
  kind text not null check (kind in ('general', 'channel')),
  owner_id uuid not null references public.profiles(id),
  created_at timestamptz not null default now(),
  deleted_at timestamptz,
  primary key (workspace_id, channel_id)
);

create index workspace_chat_channels_visible_idx
  on public.workspace_chat_channels(workspace_id, visibility)
  where deleted_at is null;

-- Freeze the old inferred state into explicit metadata exactly once.  From
-- this point onward membership rows answer WHO, never WHETHER a channel is
-- private. Topic/general channels were historically public; the old workspace
-- DM/group kinds were private.
insert into public.workspace_chat_channels (
  workspace_id, channel_id, visibility, kind, owner_id, created_at, deleted_at
)
select
  o.workspace_id,
  o.client_sync_id,
  case
    when lower(coalesce(o.content ->> 'visibility', '')) in ('public', 'private')
      then lower(o.content ->> 'visibility')
    when lower(coalesce(o.content ->> 'kind', '')) in ('dm', 'group')
      then 'private'
    else 'public'
  end,
  case
    when lower(coalesce(o.content ->> 'kind', '')) = 'general' then 'general'
    else 'channel'
  end,
  o.created_by,
  o.created_at,
  o.deleted_at
from public.workspace_objects o
where o.object_type = 'chat_channel'
on conflict (workspace_id, channel_id) do nothing;

create or replace function private.is_active_workspace_member(
  p_workspace uuid,
  p_user uuid
) returns boolean
language sql stable security definer set search_path = '' as $$
  select p_user is not null and exists (
    select 1
    from public.workspace_memberships m
    where m.workspace_id = p_workspace
      and m.user_id = p_user
      and m.removed_at is null
  );
$$;

create or replace function private.is_workspace_admin(
  p_workspace uuid,
  p_user uuid
) returns boolean
language sql stable security definer set search_path = '' as $$
  select p_user is not null and exists (
    select 1
    from public.workspace_memberships m
    where m.workspace_id = p_workspace
      and m.user_id = p_user
      and m.removed_at is null
      and m.role in ('owner', 'admin')
  );
$$;

create or replace function private.is_private_channel_member(
  p_workspace uuid,
  p_channel uuid,
  p_user uuid
) returns boolean
language sql stable security definer set search_path = '' as $$
  select p_user is not null and exists (
    select 1
    from public.chat_channel_members m
    where m.workspace_id = p_workspace
      and m.channel_sync_id = p_channel
      and m.user_id = p_user
  );
$$;

create or replace function private.can_access_workspace_channel(
  p_workspace uuid,
  p_channel uuid,
  p_user uuid
) returns boolean
language sql stable security definer set search_path = '' as $$
  select private.is_active_workspace_member(p_workspace, p_user)
    and exists (
      select 1
      from public.workspace_chat_channels c
      where c.workspace_id = p_workspace
        and c.channel_id = p_channel
        and c.deleted_at is null
        and (
          c.visibility = 'public'
          or (
            c.visibility = 'private'
            and private.is_private_channel_member(p_workspace, p_channel, p_user)
          )
        )
    );
$$;

revoke all on function private.is_active_workspace_member(uuid, uuid) from public, anon;
revoke all on function private.is_workspace_admin(uuid, uuid) from public, anon;
revoke all on function private.is_private_channel_member(uuid, uuid, uuid) from public, anon;
revoke all on function private.can_access_workspace_channel(uuid, uuid, uuid) from public, anon;
grant usage on schema private to authenticated;
grant execute on function private.is_active_workspace_member(uuid, uuid) to authenticated;
grant execute on function private.is_workspace_admin(uuid, uuid) to authenticated;
grant execute on function private.is_private_channel_member(uuid, uuid, uuid) to authenticated;
grant execute on function private.can_access_workspace_channel(uuid, uuid, uuid) to authenticated;

alter table public.workspace_chat_channels enable row level security;

create policy workspace_chat_channels_read
  on public.workspace_chat_channels for select to authenticated
  using (private.can_access_workspace_channel(workspace_id, channel_id, (select auth.uid())));

create policy workspace_chat_channels_insert
  on public.workspace_chat_channels for insert to authenticated
  with check (
    owner_id = (select auth.uid())
    and private.is_active_workspace_member(workspace_id, (select auth.uid()))
    and (
      visibility = 'public'
      or private.is_private_channel_member(workspace_id, channel_id, (select auth.uid()))
    )
  );

create policy workspace_chat_channels_update
  on public.workspace_chat_channels for update to authenticated
  using (
    owner_id = (select auth.uid())
    or private.is_workspace_admin(workspace_id, (select auth.uid()))
  )
  with check (
    owner_id = (select auth.uid())
    or private.is_workspace_admin(workspace_id, (select auth.uid()))
  );

create policy workspace_chat_channels_delete
  on public.workspace_chat_channels for delete to authenticated
  using (
    kind <> 'general'
    and (
      owner_id = (select auth.uid())
      or private.is_workspace_admin(workspace_id, (select auth.uid()))
    )
  );

grant select, insert, update, delete on public.workspace_chat_channels to authenticated;

-- Keep explicit metadata synchronized with the workspace-object envelope. A
-- missing/unknown visibility is denied, not interpreted as public.
create or replace function private.sync_workspace_chat_channel_metadata()
returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  v_visibility text;
  v_kind text;
begin
  if new.object_type <> 'chat_channel' then
    return new;
  end if;

  v_visibility := lower(coalesce(new.content ->> 'visibility', ''));
  if v_visibility not in ('public', 'private') then
    select c.visibility into v_visibility
    from public.workspace_chat_channels c
    where c.workspace_id = new.workspace_id
      and c.channel_id = new.client_sync_id
      and c.deleted_at is null;
    if v_visibility is null then
      raise exception using errcode = '22023', message = 'channel_visibility_required';
    end if;
  end if;

  v_kind := case
    when lower(coalesce(new.content ->> 'kind', '')) = 'general' then 'general'
    else 'channel'
  end;

  insert into public.workspace_chat_channels (
    workspace_id, channel_id, visibility, kind, owner_id, created_at, deleted_at
  ) values (
    new.workspace_id,
    new.client_sync_id,
    v_visibility,
    v_kind,
    new.created_by,
    new.created_at,
    new.deleted_at
  )
  on conflict (workspace_id, channel_id) do update
  set visibility = excluded.visibility,
      deleted_at = excluded.deleted_at;

  return new;
end;
$$;

revoke all on function private.sync_workspace_chat_channel_metadata() from public, anon, authenticated;

drop trigger if exists workspace_chat_channel_metadata on public.workspace_objects;
create trigger workspace_chat_channel_metadata
  before insert or update of content, deleted_at on public.workspace_objects
  for each row execute function private.sync_workspace_chat_channel_metadata();

-- Atomic bootstrap used before the workspace-object envelope is uploaded. It
-- is deliberately narrow: the caller can register only a workspace they
-- currently belong to and becomes the first member of a private channel.
create or replace function public.register_workspace_chat_channel(
  p_workspace uuid,
  p_channel uuid,
  p_visibility text,
  p_kind text default 'channel'
) returns void
language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null
     or not private.is_active_workspace_member(p_workspace, auth.uid()) then
    raise exception using errcode = '42501', message = 'workspace_membership_required';
  end if;
  if p_visibility not in ('public', 'private') then
    raise exception using errcode = '22023', message = 'invalid_channel_visibility';
  end if;
  if p_kind not in ('general', 'channel') then
    raise exception using errcode = '22023', message = 'invalid_channel_kind';
  end if;

  if p_visibility = 'private' then
    insert into public.chat_channel_members (
      workspace_id, channel_sync_id, user_id, role
    ) values (
      p_workspace, p_channel, auth.uid(), 'owner'
    ) on conflict (workspace_id, channel_sync_id, user_id) do nothing;
  end if;

  insert into public.workspace_chat_channels (
    workspace_id, channel_id, visibility, kind, owner_id
  ) values (
    p_workspace, p_channel, p_visibility, p_kind, auth.uid()
  )
  on conflict (workspace_id, channel_id) do update
  set visibility = excluded.visibility
  where public.workspace_chat_channels.owner_id = auth.uid()
     or private.is_workspace_admin(p_workspace, auth.uid());
end;
$$;

revoke all on function public.register_workspace_chat_channel(uuid, uuid, text, text) from public, anon;
grant execute on function public.register_workspace_chat_channel(uuid, uuid, text, text) to authenticated;

-- Replace the old membership-existence inference. Unknown metadata is denied.
create or replace function public.channel_is_private(
  target_workspace uuid,
  target_channel uuid
) returns boolean
language sql stable security invoker set search_path = '' as $$
  select coalesce((
    select c.visibility = 'private'
    from public.workspace_chat_channels c
    where c.workspace_id = target_workspace
      and c.channel_id = target_channel
      and c.deleted_at is null
  ), true);
$$;

create or replace function public.is_channel_member(
  target_workspace uuid,
  target_channel uuid
) returns boolean
language sql stable security invoker set search_path = '' as $$
  select private.is_private_channel_member(
    target_workspace,
    target_channel,
    (select auth.uid())
  );
$$;

revoke all on function public.channel_is_private(uuid, uuid) from public, anon;
revoke all on function public.is_channel_member(uuid, uuid) from public, anon;
grant execute on function public.channel_is_private(uuid, uuid) to authenticated;
grant execute on function public.is_channel_member(uuid, uuid) to authenticated;

create or replace function public.can_read_workspace_object(
  target_workspace uuid,
  obj_sync_id uuid,
  obj_type text,
  obj_content jsonb
) returns boolean
language sql stable security invoker set search_path = '' as $$
  select case
    when obj_type = 'chat_channel' then
      private.can_access_workspace_channel(
        target_workspace,
        obj_sync_id,
        (select auth.uid())
      )
    when obj_type = 'chat_message' then
      case
        when nullif(obj_content ->> 'channelSyncId', '') is null then
          private.is_active_workspace_member(target_workspace, (select auth.uid()))
        else
          private.can_access_workspace_channel(
            target_workspace,
            (obj_content ->> 'channelSyncId')::uuid,
            (select auth.uid())
          )
      end
    else private.is_active_workspace_member(target_workspace, (select auth.uid()))
  end;
$$;

drop policy if exists objects_member_read on public.workspace_objects;
create policy objects_member_read
  on public.workspace_objects for select to authenticated
  using (
    deleted_at is null
    and public.can_read_workspace_object(
      workspace_id,
      client_sync_id,
      object_type,
      content
    )
  );

create or replace function public.enforce_channel_privacy()
returns trigger
language plpgsql security invoker set search_path = '' as $$
declare
  v_channel uuid;
begin
  if new.object_type = 'chat_channel' then
    v_channel := new.client_sync_id;
  elsif new.object_type = 'chat_message' then
    v_channel := nullif(new.content ->> 'channelSyncId', '')::uuid;
  else
    return new;
  end if;

  -- General has no channel id. Every named channel must have authoritative
  -- metadata and must authorize this caller.
  if v_channel is null then
    if private.is_active_workspace_member(new.workspace_id, (select auth.uid())) then
      return new;
    end if;
    raise exception using errcode = '42501', message = 'workspace_membership_required';
  end if;

  if private.can_access_workspace_channel(
    new.workspace_id,
    v_channel,
    (select auth.uid())
  ) then
    return new;
  end if;

  raise exception using errcode = '42501', message = 'channel_access_denied';
end;
$$;

revoke all on function public.enforce_channel_privacy() from public, anon, authenticated;

-- -------------------------------------------------------------------------
-- Account-scope request policy and DM-focused block enforcement.
-- -------------------------------------------------------------------------

-- Do not expose a block oracle for arbitrary third-party pairs. Existing
-- policies/RPCs call this with the authenticated account as one side; any
-- other invocation fails closed without revealing whether that pair blocked.
create or replace function public.pair_blocked(user_a uuid, user_b uuid)
returns boolean
language sql stable security definer set search_path = '' as $$
  select case
    when auth.uid() is null then true
    when auth.uid() <> user_a and auth.uid() <> user_b then true
    else exists (
      select 1
      from public.blocked_users b
      where (b.owner_id = user_a and b.blocked_id = user_b)
         or (b.owner_id = user_b and b.blocked_id = user_a)
    )
  end;
$$;

revoke all on function public.pair_blocked(uuid, uuid) from public, anon;
grant execute on function public.pair_blocked(uuid, uuid) to authenticated;

create table public.message_request_preferences (
  owner_id uuid primary key references public.profiles(id) on delete cascade,
  sender_policy text not null default 'shared_workspace'
    check (sender_policy in ('nobody', 'shared_workspace', 'anyone')),
  pending_behavior text not null default 'first_only'
    check (pending_behavior in ('first_only', 'limited_followups', 'until_decided')),
  request_file_policy text not null default 'none'
    check (request_file_policy in ('none', 'images', 'files')),
  limited_followups integer not null default 2
    check (limited_followups between 0 and 5),
  updated_at timestamptz not null default now()
);

alter table public.message_request_preferences enable row level security;
create policy message_request_preferences_owner
  on public.message_request_preferences for all to authenticated
  using (owner_id = (select auth.uid()))
  with check (owner_id = (select auth.uid()));
grant select, insert, update, delete on public.message_request_preferences to authenticated;

create table public.message_request_extensions (
  request_id uuid primary key references public.contact_requests(id) on delete cascade,
  recipient_id uuid not null references public.profiles(id) on delete cascade,
  extra_messages integer not null default 0 check (extra_messages between 0 and 5),
  allow_until timestamptz,
  allow_until_decided boolean not null default false,
  updated_at timestamptz not null default now()
);

alter table public.message_request_extensions enable row level security;
create policy message_request_extensions_recipient
  on public.message_request_extensions for all to authenticated
  using (recipient_id = (select auth.uid()))
  with check (recipient_id = (select auth.uid()));
grant select, insert, update, delete on public.message_request_extensions to authenticated;

create or replace function private.users_share_active_workspace(
  p_a uuid,
  p_b uuid
) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1
    from public.workspace_memberships a
    join public.workspace_memberships b on b.workspace_id = a.workspace_id
    where a.user_id = p_a
      and b.user_id = p_b
      and a.removed_at is null
      and b.removed_at is null
  );
$$;

create or replace function private.users_share_personal_group(
  p_a uuid,
  p_b uuid
) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1
    from public.direct_conversation_members a
    join public.direct_conversation_members b
      on b.conversation_id = a.conversation_id
    join public.direct_conversations c on c.id = a.conversation_id
    where a.user_id = p_a
      and b.user_id = p_b
      and c.kind = 'group'
  );
$$;

create or replace function private.can_send_direct_message(
  p_conversation uuid,
  p_sender uuid
) returns boolean
language plpgsql stable security definer set search_path = '' as $$
declare
  v_kind text;
  v_recipient uuid;
  v_pref public.message_request_preferences%rowtype;
  v_request public.contact_requests%rowtype;
  v_extension public.message_request_extensions%rowtype;
  v_prior_count integer;
  v_mutual boolean;
begin
  if p_sender is null then return false; end if;

  select c.kind into v_kind
  from public.direct_conversations c
  join public.direct_conversation_members me
    on me.conversation_id = c.id and me.user_id = p_sender
  where c.id = p_conversation;

  if v_kind is null then return false; end if;
  if v_kind = 'group' then return true; end if;
  if v_kind <> 'dm' then return false; end if;

  select m.user_id into v_recipient
  from public.direct_conversation_members m
  where m.conversation_id = p_conversation and m.user_id <> p_sender
  limit 1;

  if v_recipient is null or public.pair_blocked(p_sender, v_recipient) then
    return false;
  end if;

  select exists (
    select 1 from public.contacts a
    join public.contacts b
      on b.owner_id = a.contact_id and b.contact_id = a.owner_id
    where a.owner_id = p_sender and a.contact_id = v_recipient
  ) into v_mutual;
  if v_mutual then return true; end if;

  select * into v_request
  from public.contact_requests r
  where r.conversation_id = p_conversation
    and r.sender_id = p_sender
    and r.recipient_id = v_recipient;

  if v_request.status = 'accepted' then return true; end if;
  if v_request.status = 'declined' then return false; end if;

  select * into v_pref
  from public.message_request_preferences p
  where p.owner_id = v_recipient;

  if not found then
    v_pref.sender_policy := 'shared_workspace';
    v_pref.pending_behavior := 'first_only';
    v_pref.request_file_policy := 'none';
    v_pref.limited_followups := 2;
  end if;

  if v_pref.sender_policy = 'nobody' then return false; end if;
  if v_pref.sender_policy = 'shared_workspace'
     and not private.users_share_active_workspace(p_sender, v_recipient) then
    return false;
  end if;

  select count(*) into v_prior_count
  from public.direct_messages m
  where m.conversation_id = p_conversation and m.sender_id = p_sender;

  -- The very first message creates the request after insertion.
  if v_prior_count = 0 then return true; end if;
  if v_request.id is null then return false; end if;

  select * into v_extension
  from public.message_request_extensions e
  where e.request_id = v_request.id and e.recipient_id = v_recipient;

  if v_extension.allow_until_decided then return true; end if;
  if v_extension.allow_until is not null and v_extension.allow_until > now() then return true; end if;
  if v_prior_count <= coalesce(v_extension.extra_messages, 0) then return true; end if;

  return case v_pref.pending_behavior
    when 'until_decided' then true
    when 'limited_followups' then v_prior_count <= v_pref.limited_followups
    else false
  end;
end;
$$;

revoke all on function private.users_share_active_workspace(uuid, uuid) from public, anon;
revoke all on function private.users_share_personal_group(uuid, uuid) from public, anon;
revoke all on function private.can_send_direct_message(uuid, uuid) from public, anon;
grant execute on function private.users_share_active_workspace(uuid, uuid) to authenticated;
grant execute on function private.users_share_personal_group(uuid, uuid) to authenticated;
grant execute on function private.can_send_direct_message(uuid, uuid) to authenticated;

drop policy if exists direct_messages_member_insert on public.direct_messages;
create policy direct_messages_member_insert
  on public.direct_messages for insert to authenticated
  with check (
    sender_id = (select auth.uid())
    and private.can_send_direct_message(conversation_id, (select auth.uid()))
  );

-- A block suppresses ordinary direct discovery but must not erase authorship
-- inside legitimate shared workspaces or personal groups.
drop policy if exists profiles_member_read on public.profiles;
create policy profiles_member_read
  on public.profiles for select to authenticated
  using (
    id = (select auth.uid())
    or public.shares_workspace(id)
    or private.users_share_personal_group(id, (select auth.uid()))
    or (
      not public.pair_blocked(id, (select auth.uid()))
      and exists (
        select 1
        from public.contacts c
        where (c.owner_id = (select auth.uid()) and c.contact_id = profiles.id)
           or (c.owner_id = profiles.id and c.contact_id = (select auth.uid()))
      )
    )
  );

-- Membership changes for personal groups are not blocked merely because two
-- members blocked one another; block remains a DM/request boundary.
drop policy if exists direct_members_owner_insert on public.direct_conversation_members;
create policy direct_members_owner_insert
  on public.direct_conversation_members for insert to authenticated
  with check (
    exists (
      select 1
      from public.direct_conversation_members o
      join public.direct_conversations c on c.id = o.conversation_id
      where o.conversation_id = direct_conversation_members.conversation_id
        and o.user_id = (select auth.uid())
        and o.role = 'owner'
        and c.kind = 'group'
    )
  );

-- Correct the two SECURITY DEFINER group entry points as well: a direct block
-- must not corrupt legitimate personal-group membership. Capacity remains a
-- server-authoritative beta rule (8 total, including the owner).
create or replace function public.create_personal_group(
  p_name text,
  p_member_ids uuid[]
) returns uuid
language plpgsql security definer set search_path = '' as $$
declare
  v_conversation uuid;
  v_member uuid;
  v_members uuid[];
begin
  if auth.uid() is null then
    raise exception using errcode = '42501', message = 'authentication_required';
  end if;
  if btrim(coalesce(p_name, '')) = '' then
    raise exception using errcode = '22023', message = 'name_required';
  end if;

  select coalesce(array_agg(distinct member_id), '{}'::uuid[])
  into v_members
  from unnest(coalesce(p_member_ids, '{}'::uuid[])) member_id
  where member_id <> auth.uid()
    and exists (
      select 1 from public.contacts c
      where c.owner_id = auth.uid() and c.contact_id = member_id
    );

  if coalesce(array_length(v_members, 1), 0) > 7 then
    raise exception using errcode = '22023', message = 'personal_group_member_limit';
  end if;

  insert into public.direct_conversations (kind, name, created_by)
  values ('group', left(btrim(p_name), 80), auth.uid())
  returning id into v_conversation;

  insert into public.direct_conversation_members (conversation_id, user_id, role)
  values (v_conversation, auth.uid(), 'owner');

  foreach v_member in array v_members loop
    insert into public.direct_conversation_members (conversation_id, user_id, role)
    values (v_conversation, v_member, 'member')
    on conflict do nothing;
  end loop;

  return v_conversation;
end;
$$;

revoke all on function public.create_personal_group(text, uuid[]) from public, anon;
grant execute on function public.create_personal_group(text, uuid[]) to authenticated;

create or replace function public.add_group_member(
  p_conversation uuid,
  p_member uuid
) returns void
language plpgsql security definer set search_path = '' as $$
declare
  v_count integer;
begin
  if auth.uid() is null then
    raise exception using errcode = '42501', message = 'authentication_required';
  end if;
  if not exists (
    select 1
    from public.direct_conversation_members m
    join public.direct_conversations c on c.id = m.conversation_id
    where m.conversation_id = p_conversation
      and c.kind = 'group'
      and m.user_id = auth.uid()
      and m.role in ('owner', 'admin')
  ) then
    raise exception using errcode = '42501', message = 'group_admin_required';
  end if;
  if not exists (
    select 1 from public.contacts c
    where c.owner_id = auth.uid() and c.contact_id = p_member
  ) then
    raise exception using errcode = '42501', message = 'contact_required';
  end if;

  select count(*) into v_count
  from public.direct_conversation_members m
  where m.conversation_id = p_conversation;
  if v_count >= 8 then
    raise exception using errcode = '22023', message = 'personal_group_member_limit';
  end if;

  insert into public.direct_conversation_members (conversation_id, user_id, role)
  values (p_conversation, p_member, 'member')
  on conflict do nothing;
end;
$$;

revoke all on function public.add_group_member(uuid, uuid) from public, anon;
grant execute on function public.add_group_member(uuid, uuid) to authenticated;

-- Future Realtime subscriptions can authorize against the same fail-closed
-- channel predicate. Postgres Changes still obeys the table's RLS policies.
do $$
begin
  alter publication supabase_realtime add table public.workspace_chat_channels;
exception
  when duplicate_object then null;
end;
$$;
