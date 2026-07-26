-- Roster visibility + channel privacy (HARD model)
-- 2026-07-19
--
-- 1. Co-members can read each other's profiles, so the People roster can show
--    real names + avatars for invited people before they ever post.
-- 2. Private chat channels (groups, DMs) are readable ONLY by their members.
--    Topics and the General channel stay open to every workspace member.
--
-- Privacy is enforced at READ time via RLS on workspace_objects, and again on
-- writes through the sync RPC (which is security-definer and bypasses table
-- RLS, so it must gate explicitly). A channel is treated as private iff it has
-- at least one row in chat_channel_members; topics simply have no rows.

-- ---------------------------------------------------------------------------
-- Profile visibility: co-members can see each other
-- ---------------------------------------------------------------------------
create or replace function public.shares_workspace(target_profile uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (
    select 1
    from public.workspace_memberships mine
    join public.workspace_memberships theirs
      on mine.workspace_id = theirs.workspace_id
    where mine.user_id = auth.uid() and mine.removed_at is null
      and theirs.user_id = target_profile and theirs.removed_at is null
  );
$$;

revoke all on function public.shares_workspace(uuid) from public;
grant execute on function public.shares_workspace(uuid) to authenticated;

drop policy if exists profiles_self_read on public.profiles;
create policy profiles_member_read on public.profiles for select using (
  id = auth.uid() or public.shares_workspace(id)
);

-- ---------------------------------------------------------------------------
-- Channel membership: the ACL for private channels (groups, DMs)
-- ---------------------------------------------------------------------------
-- channel_sync_id is the client-generated UUID that also lives in the
-- chat_channel workspace_object's client_sync_id and in each message's
-- content->>'channelSyncId'. We key on it (not the objects PK) so the client
-- can enroll members at channel-creation time without a server round-trip for
-- the object id first.
create table public.chat_channel_members (
  workspace_id uuid not null references public.workspaces(id) on delete cascade,
  channel_sync_id uuid not null,
  user_id uuid not null references public.profiles(id) on delete cascade,
  role text not null default 'member' check (role in ('owner', 'member')),
  added_by uuid not null references public.profiles(id),
  created_at timestamptz not null default now(),
  primary key (workspace_id, channel_sync_id, user_id)
);

create index chat_channel_members_lookup_idx
  on public.chat_channel_members(workspace_id, user_id);

alter table public.chat_channel_members enable row level security;

-- Am I a member of this specific channel?
create or replace function public.is_channel_member(
  target_workspace uuid, target_channel uuid
) returns boolean language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.chat_channel_members m
    where m.workspace_id = target_workspace
      and m.channel_sync_id = target_channel
      and m.user_id = auth.uid()
  );
$$;

-- Is this channel private at all? (private == has any member rows)
create or replace function public.channel_is_private(
  target_workspace uuid, target_channel uuid
) returns boolean language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.chat_channel_members m
    where m.workspace_id = target_workspace
      and m.channel_sync_id = target_channel
  );
$$;

revoke all on function public.is_channel_member(uuid, uuid) from public;
grant execute on function public.is_channel_member(uuid, uuid) to authenticated;
revoke all on function public.channel_is_private(uuid, uuid) from public;
grant execute on function public.channel_is_private(uuid, uuid) to authenticated;

-- You can read the roster of a channel you belong to.
create policy channel_members_read on public.chat_channel_members
  for select using (
    public.is_workspace_member(workspace_id)
    and public.is_channel_member(workspace_id, channel_sync_id)
  );

-- Insert: either bootstrap (creator enrolls SELF as owner while the channel has
-- no members yet — channel_sync_id is a client UUID, so no collision) or an
-- existing owner adding someone. This blocks a member from self-enrolling into
-- an already-private channel they don't belong to.
create policy channel_members_insert on public.chat_channel_members
  for insert with check (
    public.is_workspace_member(workspace_id)
    and added_by = auth.uid()
    and (
      (user_id = auth.uid() and role = 'owner'
        and not public.channel_is_private(workspace_id, channel_sync_id))
      or exists (
        select 1 from public.chat_channel_members o
        where o.workspace_id = chat_channel_members.workspace_id
          and o.channel_sync_id = chat_channel_members.channel_sync_id
          and o.user_id = auth.uid() and o.role = 'owner'
      )
    )
  );

-- Update/delete: owners only.
create policy channel_members_update on public.chat_channel_members
  for update using (
    public.is_workspace_member(workspace_id)
    and exists (
      select 1 from public.chat_channel_members o
      where o.workspace_id = chat_channel_members.workspace_id
        and o.channel_sync_id = chat_channel_members.channel_sync_id
        and o.user_id = auth.uid() and o.role = 'owner'
    )
  ) with check (public.is_workspace_member(workspace_id));
create policy channel_members_delete on public.chat_channel_members
  for delete using (
    public.is_workspace_member(workspace_id)
    and exists (
      select 1 from public.chat_channel_members o
      where o.workspace_id = chat_channel_members.workspace_id
        and o.channel_sync_id = chat_channel_members.channel_sync_id
        and o.user_id = auth.uid() and o.role = 'owner'
    )
  );

alter publication supabase_realtime add table public.chat_channel_members;

-- ---------------------------------------------------------------------------
-- Read privacy: gate private-channel objects to their members
-- ---------------------------------------------------------------------------
-- A chat_channel object's client_sync_id IS the channel id; a chat_message
-- binds to its channel through content->>'channelSyncId'. Everything else
-- (notes, tasks, topics, General) is visible to any workspace member.
create or replace function public.can_read_workspace_object(
  target_workspace uuid, obj_type text, obj_sync_id uuid, obj_content jsonb
) returns boolean language sql stable security definer set search_path = '' as $$
  select case
    when obj_type = 'chat_channel' then
      not public.channel_is_private(target_workspace, obj_sync_id)
      or public.is_channel_member(target_workspace, obj_sync_id)
    when obj_type = 'chat_message'
         and nullif(obj_content ->> 'channelSyncId', '') is not null then
      not public.channel_is_private(target_workspace, (obj_content ->> 'channelSyncId')::uuid)
      or public.is_channel_member(target_workspace, (obj_content ->> 'channelSyncId')::uuid)
    else true
  end;
$$;

revoke all on function public.can_read_workspace_object(uuid, text, uuid, jsonb) from public;
grant execute on function public.can_read_workspace_object(uuid, text, uuid, jsonb) to authenticated;

-- Replace the permissive member-read policy with the channel-aware one.
-- (Adding a second policy would only widen access; RLS SELECT policies OR.)
drop policy if exists objects_member_read on public.workspace_objects;
create policy objects_member_read on public.workspace_objects for select using (
  public.is_workspace_member(workspace_id)
  and public.can_read_workspace_object(workspace_id, object_type, client_sync_id, content)
);

-- ---------------------------------------------------------------------------
-- Write privacy: enforce membership even through the security-definer RPC
-- ---------------------------------------------------------------------------
-- apply_workspace_object runs security-definer, so it bypasses the RLS above.
-- This trigger fires regardless and blocks writes to a private channel by a
-- non-member. Channel creators enroll themselves in chat_channel_members
-- before writing the channel object, so the creator passes as a member.
create or replace function public.enforce_channel_privacy()
returns trigger language plpgsql security definer set search_path = '' as $$
declare channel uuid;
begin
  if new.object_type = 'chat_channel' then
    channel := new.client_sync_id;
  elsif new.object_type = 'chat_message' then
    channel := nullif(new.content ->> 'channelSyncId', '')::uuid;
  else
    return new;
  end if;

  if channel is null then return new; end if;
  if not public.channel_is_private(new.workspace_id, channel) then return new; end if;
  if public.is_channel_member(new.workspace_id, channel) then return new; end if;

  raise exception 'CHANNEL_ACCESS_DENIED';
end;
$$;

create trigger workspace_object_channel_privacy
  before insert or update of object_type, content, client_sync_id
  on public.workspace_objects
  for each row execute function public.enforce_channel_privacy();
