-- Server-authoritative group/private-channel governance and participant-visible audit trail.

alter table public.direct_conversation_members
  drop constraint if exists direct_conversation_members_role_check;
alter table public.direct_conversation_members
  add constraint direct_conversation_members_role_check
  check (role in ('owner', 'admin', 'member'));

alter table public.chat_channel_members
  drop constraint if exists chat_channel_members_role_check;
alter table public.chat_channel_members
  add constraint chat_channel_members_role_check
  check (role in ('owner', 'admin', 'member'));

create table if not exists public.chat_governance_events (
  id uuid primary key default gen_random_uuid(),
  scope text not null check (scope in ('account_group', 'workspace_channel')),
  account_conversation_id uuid references public.direct_conversations(id) on delete cascade,
  workspace_id uuid references public.workspaces(id) on delete cascade,
  channel_id uuid,
  event_type text not null check (event_type in (
    'member_added', 'member_removed', 'role_changed', 'ownership_transferred',
    'metadata_changed', 'channel_archived'
  )),
  actor_id uuid references public.profiles(id) on delete set null,
  target_id uuid references public.profiles(id) on delete set null,
  detail jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now(),
  check (
    (scope = 'account_group' and account_conversation_id is not null
      and workspace_id is null and channel_id is null)
    or
    (scope = 'workspace_channel' and account_conversation_id is null
      and workspace_id is not null and channel_id is not null)
  )
);

create index if not exists chat_governance_account_idx
  on public.chat_governance_events(account_conversation_id, created_at desc)
  where scope = 'account_group';
create index if not exists chat_governance_workspace_idx
  on public.chat_governance_events(workspace_id, channel_id, created_at desc)
  where scope = 'workspace_channel';

alter table public.chat_governance_events enable row level security;
drop policy if exists chat_governance_participant_read on public.chat_governance_events;
create policy chat_governance_participant_read
  on public.chat_governance_events for select to authenticated
  using (
    (scope = 'account_group' and public.is_direct_member(account_conversation_id))
    or
    (scope = 'workspace_channel'
      and private.can_access_workspace_channel(workspace_id, channel_id, (select auth.uid())))
  );
revoke all on public.chat_governance_events from public, anon, authenticated;
grant select on public.chat_governance_events to authenticated;

create or replace function public.chat_governance_history(
  p_account_conversation uuid default null,
  p_workspace uuid default null,
  p_channel uuid default null,
  p_limit integer default 50
) returns table (
  event_id uuid,
  event_type text,
  actor_id uuid,
  actor_name text,
  target_id uuid,
  target_name text,
  detail jsonb,
  created_at timestamptz
)
language plpgsql stable security definer set search_path = '' as $$
begin
  if auth.uid() is null or (
    (p_account_conversation is not null and not public.is_direct_member(p_account_conversation))
    or
    (p_account_conversation is null and (
      p_workspace is null or p_channel is null
      or not private.can_access_workspace_channel(p_workspace, p_channel, auth.uid())
    ))
  ) then
    raise exception using errcode = '42501', message = 'governance_history_not_authorized';
  end if;
  return query
  select e.id, e.event_type, e.actor_id,
         coalesce(actor.display_name, actor.handle, 'System'),
         e.target_id, coalesce(target.display_name, target.handle, ''),
         e.detail, e.created_at
  from public.chat_governance_events e
  left join public.profiles actor on actor.id = e.actor_id
  left join public.profiles target on target.id = e.target_id
  where (p_account_conversation is not null
         and e.account_conversation_id = p_account_conversation)
     or (p_account_conversation is null
         and e.workspace_id = p_workspace and e.channel_id = p_channel)
  order by e.created_at desc, e.id desc
  limit greatest(1, least(p_limit, 200));
end;
$$;

revoke all on function public.chat_governance_history(uuid, uuid, uuid, integer)
  from public, anon;
grant execute on function public.chat_governance_history(uuid, uuid, uuid, integer)
  to authenticated;

create or replace function private.audit_direct_group_membership()
returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  v_conversation uuid := coalesce(new.conversation_id, old.conversation_id);
  v_target uuid := coalesce(new.user_id, old.user_id);
  v_type text;
begin
  if not exists (
    select 1 from public.direct_conversations c
    where c.id = v_conversation and c.kind = 'group'
  ) then
    if tg_op = 'DELETE' then return old; else return new; end if;
  end if;
  v_type := case
    when tg_op = 'INSERT' then 'member_added'
    when tg_op = 'DELETE' then 'member_removed'
    when new.role = 'owner' and old.role <> 'owner' then 'ownership_transferred'
    else 'role_changed'
  end;
  insert into public.chat_governance_events (
    scope, account_conversation_id, event_type, actor_id, target_id, detail
  ) values (
    'account_group', v_conversation, v_type, auth.uid(), v_target,
    jsonb_build_object('oldRole', old.role, 'newRole', new.role)
  );
  if tg_op = 'DELETE' then return old; else return new; end if;
end;
$$;

revoke all on function private.audit_direct_group_membership()
  from public, anon, authenticated;
drop trigger if exists audit_direct_group_membership
  on public.direct_conversation_members;
create trigger audit_direct_group_membership
after insert or update of role or delete on public.direct_conversation_members
for each row execute function private.audit_direct_group_membership();

create or replace function private.audit_group_metadata()
returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if new.kind = 'group' and (
    old.name is distinct from new.name
    or old.icon is distinct from new.icon
    or old.cover_path is distinct from new.cover_path
    or old.description is distinct from new.description
    or old.rules is distinct from new.rules
    or old.rules_reminder_days is distinct from new.rules_reminder_days
  ) then
    insert into public.chat_governance_events (
      scope, account_conversation_id, event_type, actor_id, detail
    ) values (
      'account_group', new.id, 'metadata_changed', auth.uid(),
      jsonb_build_object('nameChanged', old.name is distinct from new.name,
                         'rulesChanged', old.rules is distinct from new.rules)
    );
  end if;
  return new;
end;
$$;

revoke all on function private.audit_group_metadata()
  from public, anon, authenticated;
drop trigger if exists audit_group_metadata on public.direct_conversations;
create trigger audit_group_metadata
after update on public.direct_conversations
for each row execute function private.audit_group_metadata();

drop function if exists public.group_members(uuid);
create function public.group_members(p_conversation uuid)
returns table (
  user_id uuid,
  handle text,
  display_name text,
  avatar_path text,
  role text,
  joined_at timestamptz
)
language plpgsql stable security definer set search_path = '' as $$
begin
  if auth.uid() is null or not public.is_direct_member(p_conversation) then
    raise exception using errcode = '42501', message = 'group_membership_required';
  end if;
  return query
  select m.user_id, p.handle, p.display_name, p.avatar_path, m.role, m.joined_at
  from public.direct_conversation_members m
  join public.profiles p on p.id = m.user_id
  join public.direct_conversations c on c.id = m.conversation_id and c.kind = 'group'
  where m.conversation_id = p_conversation
  order by case m.role when 'owner' then 0 when 'admin' then 1 else 2 end,
           m.joined_at, m.user_id;
end;
$$;

revoke all on function public.group_members(uuid) from public, anon;
grant execute on function public.group_members(uuid) to authenticated;

create or replace function public.set_group_member_role(
  p_conversation uuid,
  p_member uuid,
  p_role text
) returns void
language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null or p_role is null or p_role not in ('admin', 'member') then
    raise exception using errcode = '22023', message = 'invalid_group_role';
  end if;
  if not exists (
    select 1 from public.direct_conversation_members me
    join public.direct_conversations c on c.id = me.conversation_id and c.kind = 'group'
    where me.conversation_id = p_conversation
      and me.user_id = auth.uid() and me.role = 'owner'
  ) then
    raise exception using errcode = '42501', message = 'group_owner_required';
  end if;
  if p_member = auth.uid() then
    raise exception using errcode = '22023', message = 'owner_role_requires_transfer';
  end if;
  update public.direct_conversation_members
  set role = p_role
  where conversation_id = p_conversation and user_id = p_member and role <> 'owner';
  if not found then
    raise exception using errcode = 'P0002', message = 'group_member_not_found';
  end if;
end;
$$;

revoke all on function public.set_group_member_role(uuid, uuid, text) from public, anon;
grant execute on function public.set_group_member_role(uuid, uuid, text) to authenticated;

create or replace function public.transfer_group_ownership(
  p_conversation uuid,
  p_new_owner uuid
) returns void
language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null or p_new_owner = auth.uid() then
    raise exception using errcode = '22023', message = 'invalid_new_owner';
  end if;
  perform 1 from public.direct_conversations c
  where c.id = p_conversation and c.kind = 'group' for update;
  if not exists (
    select 1 from public.direct_conversation_members m
    where m.conversation_id = p_conversation
      and m.user_id = auth.uid() and m.role = 'owner'
  ) or not exists (
    select 1 from public.direct_conversation_members m
    where m.conversation_id = p_conversation and m.user_id = p_new_owner
  ) then
    raise exception using errcode = '42501', message = 'ownership_transfer_not_authorized';
  end if;
  update public.direct_conversation_members set role = 'admin'
  where conversation_id = p_conversation and user_id = auth.uid();
  update public.direct_conversation_members set role = 'owner'
  where conversation_id = p_conversation and user_id = p_new_owner;
end;
$$;

revoke all on function public.transfer_group_ownership(uuid, uuid) from public, anon;
grant execute on function public.transfer_group_ownership(uuid, uuid) to authenticated;

create or replace function public.remove_group_member(
  p_conversation uuid,
  p_member uuid
) returns void
language plpgsql security definer set search_path = '' as $$
declare
  v_my_role text;
  v_target_role text;
begin
  select me.role, target.role into v_my_role, v_target_role
  from public.direct_conversation_members me
  join public.direct_conversation_members target
    on target.conversation_id = me.conversation_id and target.user_id = p_member
  join public.direct_conversations c on c.id = me.conversation_id and c.kind = 'group'
  where me.conversation_id = p_conversation and me.user_id = auth.uid();
  if auth.uid() is null or p_member = auth.uid()
     or coalesce(v_my_role, '') not in ('owner', 'admin')
     or coalesce(v_target_role, '') not in ('admin', 'member')
     or (v_target_role = 'admin' and v_my_role <> 'owner') then
    raise exception using errcode = '42501', message = 'member_removal_not_authorized';
  end if;
  delete from public.direct_conversation_members
  where conversation_id = p_conversation and user_id = p_member;
end;
$$;

revoke all on function public.remove_group_member(uuid, uuid) from public, anon;
grant execute on function public.remove_group_member(uuid, uuid) to authenticated;

-- Owners and admins may edit group identity; role checks remain on the server.
create or replace function public.set_group_meta(
  p_conversation uuid,
  p_name text,
  p_icon text,
  p_cover_path text,
  p_description text,
  p_rules text,
  p_rules_reminder_days integer
)
returns void language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null or not exists (
    select 1 from public.direct_conversation_members m
    join public.direct_conversations c on c.id = m.conversation_id and c.kind = 'group'
    where m.conversation_id = p_conversation
      and m.user_id = auth.uid() and m.role in ('owner', 'admin')
  ) then
    raise exception using errcode = '42501', message = 'group_admin_required';
  end if;
  update public.direct_conversations c
  set name = coalesce(left(nullif(btrim(p_name), ''), 80), c.name),
      icon = left(coalesce(p_icon, c.icon), 16),
      cover_path = nullif(btrim(coalesce(p_cover_path, '')), ''),
      description = left(coalesce(p_description, c.description), 500),
      rules = left(coalesce(p_rules, c.rules), 4000),
      rules_reminder_days = greatest(1, least(coalesce(p_rules_reminder_days, c.rules_reminder_days), 365))
  where c.id = p_conversation and c.kind = 'group';
end;
$$;

revoke all on function public.set_group_meta(uuid, text, text, text, text, text, integer)
  from public, anon;
grant execute on function public.set_group_meta(uuid, text, text, text, text, text, integer)
  to authenticated;

create or replace function private.audit_workspace_channel_membership()
returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  v_type text;
begin
  v_type := case
    when tg_op = 'INSERT' then 'member_added'
    when tg_op = 'DELETE' then 'member_removed'
    when new.role = 'owner' and old.role <> 'owner' then 'ownership_transferred'
    else 'role_changed'
  end;
  insert into public.chat_governance_events (
    scope, workspace_id, channel_id, event_type, actor_id, target_id, detail
  ) values (
    'workspace_channel', coalesce(new.workspace_id, old.workspace_id),
    coalesce(new.channel_sync_id, old.channel_sync_id), v_type, auth.uid(),
    coalesce(new.user_id, old.user_id),
    jsonb_build_object('oldRole', old.role, 'newRole', new.role)
  );
  if tg_op = 'DELETE' then return old; else return new; end if;
end;
$$;

revoke all on function private.audit_workspace_channel_membership()
  from public, anon, authenticated;
drop trigger if exists audit_workspace_channel_membership on public.chat_channel_members;
create trigger audit_workspace_channel_membership
after insert or update of role or delete on public.chat_channel_members
for each row execute function private.audit_workspace_channel_membership();

create or replace function public.workspace_channel_members(
  p_workspace uuid,
  p_channel uuid
) returns table (
  user_id uuid,
  handle text,
  display_name text,
  avatar_path text,
  role text,
  joined_at timestamptz
)
language plpgsql stable security definer set search_path = '' as $$
begin
  if auth.uid() is null
     or not private.can_access_workspace_channel(p_workspace, p_channel, auth.uid()) then
    raise exception using errcode = '42501', message = 'channel_access_required';
  end if;
  return query
  select m.user_id, p.handle, p.display_name, p.avatar_path, m.role, m.created_at
  from public.chat_channel_members m
  join public.profiles p on p.id = m.user_id
  where m.workspace_id = p_workspace and m.channel_sync_id = p_channel
  order by case m.role when 'owner' then 0 when 'admin' then 1 else 2 end,
           m.created_at, m.user_id;
end;
$$;

revoke all on function public.workspace_channel_members(uuid, uuid) from public, anon;
grant execute on function public.workspace_channel_members(uuid, uuid) to authenticated;

create or replace function public.add_workspace_channel_member(
  p_workspace uuid,
  p_channel uuid,
  p_member uuid
) returns void
language plpgsql security definer set search_path = '' as $$
declare
  v_actor_role text;
begin
  select role into v_actor_role from public.chat_channel_members
  where workspace_id = p_workspace and channel_sync_id = p_channel and user_id = auth.uid();
  if auth.uid() is null
     or not exists (
       select 1 from public.workspace_chat_channels c
       where c.workspace_id = p_workspace and c.channel_id = p_channel
         and c.visibility = 'private' and c.deleted_at is null
     )
     or (
       not private.is_workspace_admin(p_workspace, auth.uid())
       and coalesce(v_actor_role, '') not in ('owner', 'admin')
     )
     or not private.is_active_workspace_member(p_workspace, p_member) then
    raise exception using errcode = '42501', message = 'channel_member_add_not_authorized';
  end if;
  insert into public.chat_channel_members (
    workspace_id, channel_sync_id, user_id, role, added_by
  ) values (p_workspace, p_channel, p_member, 'member', auth.uid())
  on conflict (workspace_id, channel_sync_id, user_id) do nothing;
end;
$$;

revoke all on function public.add_workspace_channel_member(uuid, uuid, uuid)
  from public, anon;
grant execute on function public.add_workspace_channel_member(uuid, uuid, uuid)
  to authenticated;

create or replace function public.set_workspace_channel_member_role(
  p_workspace uuid,
  p_channel uuid,
  p_member uuid,
  p_role text
) returns void
language plpgsql security definer set search_path = '' as $$
declare
  v_actor_role text;
begin
  if auth.uid() is null or p_role is null or p_role not in ('admin', 'member') then
    raise exception using errcode = '22023', message = 'invalid_channel_role';
  end if;
  select role into v_actor_role from public.chat_channel_members
  where workspace_id = p_workspace and channel_sync_id = p_channel
    and user_id = auth.uid();
  if not private.is_workspace_admin(p_workspace, auth.uid())
     and coalesce(v_actor_role, '') <> 'owner' then
    raise exception using errcode = '42501', message = 'channel_owner_required';
  end if;
  update public.chat_channel_members set role = p_role
  where workspace_id = p_workspace and channel_sync_id = p_channel
    and user_id = p_member and role <> 'owner';
  if not found then
    raise exception using errcode = 'P0002', message = 'channel_member_not_found';
  end if;
end;
$$;

revoke all on function public.set_workspace_channel_member_role(uuid, uuid, uuid, text)
  from public, anon;
grant execute on function public.set_workspace_channel_member_role(uuid, uuid, uuid, text)
  to authenticated;

create or replace function public.transfer_workspace_channel_ownership(
  p_workspace uuid,
  p_channel uuid,
  p_new_owner uuid
) returns void
language plpgsql security definer set search_path = '' as $$
declare
  v_owner uuid;
begin
  select c.owner_id into v_owner from public.workspace_chat_channels c
  where c.workspace_id = p_workspace and c.channel_id = p_channel
    and c.deleted_at is null for update;
  if auth.uid() is null or p_new_owner = v_owner
     or (auth.uid() <> v_owner and not private.is_workspace_admin(p_workspace, auth.uid()))
     or not exists (
       select 1 from public.chat_channel_members m
       where m.workspace_id = p_workspace and m.channel_sync_id = p_channel
         and m.user_id = p_new_owner
     ) then
    raise exception using errcode = '42501', message = 'channel_ownership_transfer_not_authorized';
  end if;
  update public.chat_channel_members set role = 'admin'
  where workspace_id = p_workspace and channel_sync_id = p_channel and role = 'owner';
  update public.chat_channel_members set role = 'owner'
  where workspace_id = p_workspace and channel_sync_id = p_channel and user_id = p_new_owner;
  update public.workspace_chat_channels set owner_id = p_new_owner
  where workspace_id = p_workspace and channel_id = p_channel;
end;
$$;

revoke all on function public.transfer_workspace_channel_ownership(uuid, uuid, uuid)
  from public, anon;
grant execute on function public.transfer_workspace_channel_ownership(uuid, uuid, uuid)
  to authenticated;

create or replace function public.remove_workspace_channel_member(
  p_workspace uuid,
  p_channel uuid,
  p_member uuid
) returns void
language plpgsql security definer set search_path = '' as $$
declare
  v_actor_role text;
  v_target_role text;
begin
  select role into v_actor_role from public.chat_channel_members
  where workspace_id = p_workspace and channel_sync_id = p_channel and user_id = auth.uid();
  select role into v_target_role from public.chat_channel_members
  where workspace_id = p_workspace and channel_sync_id = p_channel and user_id = p_member;
  if auth.uid() is null or p_member = auth.uid()
     or coalesce(v_target_role, '') not in ('admin', 'member')
     or (
       not private.is_workspace_admin(p_workspace, auth.uid())
       and coalesce(v_actor_role, '') not in ('owner', 'admin')
     )
     or (v_target_role = 'admin' and v_actor_role <> 'owner'
         and not private.is_workspace_admin(p_workspace, auth.uid())) then
    raise exception using errcode = '42501', message = 'channel_member_removal_not_authorized';
  end if;
  delete from public.chat_channel_members
  where workspace_id = p_workspace and channel_sync_id = p_channel and user_id = p_member;
end;
$$;

revoke all on function public.remove_workspace_channel_member(uuid, uuid, uuid)
  from public, anon;
grant execute on function public.remove_workspace_channel_member(uuid, uuid, uuid)
  to authenticated;

do $$
begin
  alter publication supabase_realtime add table public.chat_governance_events;
exception when duplicate_object then null;
end;
$$;

notify pgrst, 'reload schema';
