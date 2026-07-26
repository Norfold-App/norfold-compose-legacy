-- Stable cloud-backed chat channels and deterministic direct conversations.
-- PRE_BETA: deployed with a coordinated test-data reset.

-- Both participants must already belong to the workspace. The deterministic UUID makes opening
-- the same DM concurrently on two devices converge on one channel instead of creating twins.
create or replace function public.ensure_direct_chat_channel(
  p_workspace uuid,
  p_other_user uuid
) returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  me uuid := auth.uid();
  channel_id uuid;
begin
  if me is null then
    raise exception using errcode = '42501', message = 'authentication_required';
  end if;
  if me = p_other_user then
    raise exception using errcode = '22023', message = 'cannot_message_self';
  end if;
  if not public.is_workspace_member(p_workspace) or not exists (
    select 1 from public.workspace_memberships membership
    where membership.workspace_id = p_workspace
      and membership.user_id = p_other_user
      and membership.removed_at is null
  ) then
    raise exception using errcode = '42501', message = 'workspace_member_required';
  end if;

  channel_id := md5(
    p_workspace::text || ':' || least(me::text, p_other_user::text) || ':' || greatest(me::text, p_other_user::text)
  )::uuid;

  insert into public.chat_channel_members (workspace_id, channel_sync_id, user_id, role, added_by)
  values
    (p_workspace, channel_id, me, 'owner', me),
    (p_workspace, channel_id, p_other_user, 'owner', me)
  on conflict (workspace_id, channel_sync_id, user_id) do nothing;

  return channel_id;
end;
$$;

revoke all on function public.ensure_direct_chat_channel(uuid, uuid) from public;
grant execute on function public.ensure_direct_chat_channel(uuid, uuid) to authenticated;

-- Channel deletion is owner-only for private channels and workspace-owner/admin-only for public
-- channels. Messages are tombstoned with the channel so they cannot reappear after reinstall.
create or replace function public.delete_chat_channel(
  p_workspace uuid,
  p_channel uuid
) returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  me uuid := auth.uid();
  allowed boolean;
begin
  if me is null then
    raise exception using errcode = '42501', message = 'authentication_required';
  end if;

  if public.channel_is_private(p_workspace, p_channel) then
    allowed := exists (
      select 1 from public.chat_channel_members member
      where member.workspace_id = p_workspace
        and member.channel_sync_id = p_channel
        and member.user_id = me
        and member.role = 'owner'
    );
  else
    allowed := exists (
      select 1 from public.workspace_memberships membership
      where membership.workspace_id = p_workspace
        and membership.user_id = me
        and membership.removed_at is null
        and membership.role in ('owner', 'admin')
    );
  end if;

  if not allowed then
    raise exception using errcode = '42501', message = 'channel_delete_denied';
  end if;

  update public.workspace_objects
  set deleted_at = now(), updated_at = now(), updated_by = me, server_version = server_version + 1
  where workspace_id = p_workspace
    and deleted_at is null
    and (
      (object_type = 'chat_channel' and client_sync_id = p_channel)
      or (object_type = 'chat_message' and content ->> 'channelSyncId' = p_channel::text)
    );

  delete from public.chat_channel_members
  where workspace_id = p_workspace and channel_sync_id = p_channel;
end;
$$;

revoke all on function public.delete_chat_channel(uuid, uuid) from public;
grant execute on function public.delete_chat_channel(uuid, uuid) to authenticated;

-- A channel definition is itself a collaboration event. Message notifications already filter
-- recipients through chat_channel_members, so DMs stay private while topics/groups reach the
-- workspace members who can read them.
create or replace function public.enqueue_workspace_object_notification()
returns trigger language plpgsql security definer set search_path = '' as $$
declare
  event_kind text;
  event_title text;
  event_body text;
  event_destination text;
  channel_sync uuid;
  private_channel boolean := false;
begin
  if new.deleted_at is not null then return new; end if;

  event_kind := case new.object_type
    when 'chat_message' then 'chat_message'
    when 'task' then 'task_changed'
    when 'shared_document' then 'document_changed'
    when 'object_comment' then 'document_comment'
    else null
  end;
  if event_kind is null then return new; end if;

  if new.object_type = 'chat_message' then
    channel_sync := nullif(new.content ->> 'channelSyncId', '')::uuid;
    private_channel := channel_sync is not null and public.channel_is_private(new.workspace_id, channel_sync);
    event_kind := case when private_channel then 'chat_dm' else 'chat_message' end;
    event_title := coalesce(nullif(new.content ->> 'authorDisplayName', ''), 'New message');
    event_body := left(coalesce(nullif(new.content ->> 'body', ''), new.content ->> 'attachmentName', 'New attachment'), 180);
    event_destination := 'Chat';
  elsif new.object_type = 'task' then
    event_title := 'Task updated';
    event_body := left(coalesce(new.content ->> 'title', 'Open Tasks to review the change'), 180);
    event_destination := 'Tasks';
  else
    event_title := case when new.object_type = 'object_comment' then 'New comment' else 'Document updated' end;
    event_body := left(coalesce(new.content ->> 'title', new.content ->> 'body', 'Open Norfold to review the change'), 180);
    event_destination := 'ObjectDetail';
  end if;

  insert into public.notification_deliveries (
    user_id, workspace_id, channel, title, body, payload, scheduled_at, event_key
  )
  select
    membership.user_id,
    new.workspace_id,
    'push'::public.delivery_channel,
    event_title,
    event_body,
    jsonb_build_object(
      'kind', event_kind,
      'destination', event_destination,
      'workspaceId', new.workspace_id::text,
      'objectId', new.id::text,
      'messageId', case when new.object_type = 'chat_message' then new.client_sync_id::text else '' end,
      'channelId', coalesce(channel_sync::text, ''),
      'sender', coalesce(new.content ->> 'authorUsername', '')
    ),
    now(),
    new.id::text || ':' || new.server_version::text
  from public.workspace_memberships membership
  where membership.workspace_id = new.workspace_id
    and membership.removed_at is null
    and membership.user_id <> new.updated_by
    and (
      new.object_type <> 'chat_message'
      or channel_sync is null
      or not private_channel
      or exists (
        select 1 from public.chat_channel_members member
        where member.workspace_id = new.workspace_id
          and member.channel_sync_id = channel_sync
          and member.user_id = membership.user_id
      )
    )
  on conflict do nothing;
  return new;
end;
$$;
