-- Durable notification enqueueing for shared workspace objects.
-- The dispatcher sends data-only FCM so Android owns routing and catch-up.

alter table public.notification_deliveries
  add column if not exists event_key text;

alter table public.notification_deliveries
  add column if not exists attempt_count integer not null default 0;

create unique index if not exists notification_delivery_event_once
  on public.notification_deliveries(user_id, channel, event_key)
  where event_key is not null;

-- The Android outbox uses deterministic paths with `upsert = true` so retrying an interrupted
-- upload reclaims the same object instead of leaking a second copy. Supabase Storage requires an
-- UPDATE policy for that conflict path in addition to the original INSERT policy.
drop policy if exists workspace_storage_update on storage.objects;
create policy workspace_storage_update on storage.objects for update using (
  bucket_id = 'workspace-files'
  and public.is_workspace_member(((storage.foldername(name))[1])::uuid)
) with check (
  bucket_id = 'workspace-files'
  and public.is_workspace_member(((storage.foldername(name))[1])::uuid)
);

create or replace function public.enqueue_workspace_object_notification()
returns trigger language plpgsql security definer set search_path = '' as $$
declare
  event_kind text;
  event_title text;
  event_body text;
  event_destination text;
  channel_sync uuid;
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
      or not public.channel_is_private(new.workspace_id, channel_sync)
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

drop trigger if exists workspace_object_notification_enqueue on public.workspace_objects;
create trigger workspace_object_notification_enqueue
  after insert or update of content, deleted_at on public.workspace_objects
  for each row execute function public.enqueue_workspace_object_notification();
