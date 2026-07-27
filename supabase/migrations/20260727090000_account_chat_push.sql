-- Phase 14 — Account/personal chat push completion.
--
-- Workspace-object pushes already exist (see 202607220001/202607220002). This
-- migration adds the missing *account* chat pushes: encrypted DMs, personal
-- groups, and inbound message (contact) requests. It reuses the existing FCM
-- pipeline (notification_deliveries -> notification-dispatch) rather than
-- building a second push system.
--
-- E2EE invariant (spec §148): the server never sees plaintext, so account-chat
-- deliveries carry ROUTING METADATA ONLY — conversation id, kind, a routing
-- destination, and the opaque relay/message ids. Title/body are generic
-- ("New message"); the device performs secure catch-up and refines the visible
-- notification (sender, preview, @mention) locally. Nothing derived from the
-- ciphertext is placed in the payload.
--
-- Suppression applied at enqueue time: block, self-message exclusion, global
-- push preference + quiet hours (dispatch also re-checks), per-conversation
-- notification setting (all/mentions/off/mute-until), and the request gate
-- (a stranger's first message notifies as a Request, not as a chat push).

-- ---------------------------------------------------------------------------
-- Per-conversation notification settings (account-level, synced across devices)
-- ---------------------------------------------------------------------------
-- level:
--   'all'      notify for every message
--   'mentions' device only surfaces @mentions (server still delivers routing
--              push so the device can catch up and decide; see §148)
--   'off'      never notify
-- muted_until: transient mute; when in the future it suppresses like 'off'.
create table if not exists public.conversation_notification_prefs (
  user_id uuid not null references public.profiles(id) on delete cascade,
  conversation_id uuid not null references public.direct_conversations(id) on delete cascade,
  level text not null default 'all' check (level in ('all', 'mentions', 'off')),
  muted_until timestamptz,
  updated_at timestamptz not null default now(),
  primary key (user_id, conversation_id)
);

create index if not exists conversation_notification_prefs_user_idx
  on public.conversation_notification_prefs(user_id);

alter table public.conversation_notification_prefs enable row level security;

-- You read and manage only your own per-conversation settings, and only for a
-- conversation you actually belong to.
drop policy if exists conversation_notification_prefs_self_read on public.conversation_notification_prefs;
create policy conversation_notification_prefs_self_read
  on public.conversation_notification_prefs for select to authenticated
  using (user_id = (select auth.uid()));

drop policy if exists conversation_notification_prefs_self_write on public.conversation_notification_prefs;
create policy conversation_notification_prefs_self_write
  on public.conversation_notification_prefs for insert to authenticated
  with check (
    user_id = (select auth.uid())
    and public.is_direct_member(conversation_id)
  );

drop policy if exists conversation_notification_prefs_self_update on public.conversation_notification_prefs;
create policy conversation_notification_prefs_self_update
  on public.conversation_notification_prefs for update to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()));

drop policy if exists conversation_notification_prefs_self_delete on public.conversation_notification_prefs;
create policy conversation_notification_prefs_self_delete
  on public.conversation_notification_prefs for delete to authenticated
  using (user_id = (select auth.uid()));

-- Narrow RPC so clients set the level/mute atomically and consistently.
create or replace function public.set_conversation_notification_pref(
  p_conversation uuid,
  p_level text default 'all',
  p_muted_until timestamptz default null
) returns void
language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then
    raise exception using errcode = '42501', message = 'authentication_required';
  end if;
  if not public.is_direct_member(p_conversation) then
    raise exception using errcode = '42501', message = 'not_a_conversation_member';
  end if;
  if p_level not in ('all', 'mentions', 'off') then
    raise exception using errcode = '22023', message = 'invalid_level';
  end if;
  insert into public.conversation_notification_prefs (user_id, conversation_id, level, muted_until, updated_at)
  values (auth.uid(), p_conversation, p_level, p_muted_until, now())
  on conflict (user_id, conversation_id) do update
    set level = excluded.level,
        muted_until = excluded.muted_until,
        updated_at = now();
end;
$$;

revoke all on function public.set_conversation_notification_pref(uuid, text, timestamptz) from public, anon;
grant execute on function public.set_conversation_notification_pref(uuid, text, timestamptz) to authenticated;

-- ---------------------------------------------------------------------------
-- Account chat push: one routing-only delivery per recipient user per message
-- ---------------------------------------------------------------------------
-- Fires once per relay message even though the relay fans out to every
-- recipient *device*: we collapse to distinct recipient users and dedupe via
-- notification_deliveries.event_key. The trigger is on the recipients table
-- (which is where fan-out lands) but self-joins back so only the first device
-- row per (user,relay) does the work.
create or replace function public.enqueue_account_chat_push()
returns trigger language plpgsql security definer set search_path = '' as $$
declare
  v_msg public.chat_e2ee_relay_messages%rowtype;
  v_epoch public.chat_security_epochs%rowtype;
  v_conversation uuid;
  v_kind text;
  v_content_type text;
  v_pref public.conversation_notification_prefs%rowtype;
  v_event_kind text;
begin
  -- Only account-scope messages route through personal-chat push. Workspace
  -- scope already has its own enqueue path.
  select * into v_msg from public.chat_e2ee_relay_messages where id = new.relay_id;
  if v_msg.id is null then return new; end if;

  select * into v_epoch from public.chat_security_epochs where id = v_msg.security_epoch_id;
  if v_epoch.scope <> 'account' or v_epoch.account_conversation_id is null then
    return new;
  end if;
  v_conversation := v_epoch.account_conversation_id;

  -- Never notify a user about their own message, on any device.
  if new.recipient_id = v_msg.sender_id then return new; end if;

  -- Only original text/media messages notify; mutations (edits, reactions,
  -- deletes, receipts) are silent. contentType lives in routing metadata, not
  -- the ciphertext, so reading it here does not touch plaintext.
  v_content_type := coalesce(v_msg.envelope #>> '{metadata,contentType}', 'text');
  if v_content_type not in ('text', 'media', 'message') then return new; end if;
  if v_msg.origin_relay_id is not null then
    -- Recovery re-encryptions carry an origin relay; they are not fresh sends.
    return new;
  end if;

  -- Collapse device fan-out: only the lexicographically-first device row for
  -- this (recipient, relay) enqueues, so multi-device users get one push.
  if exists (
    select 1 from public.chat_e2ee_relay_recipients r
    where r.relay_id = new.relay_id
      and r.recipient_id = new.recipient_id
      and r.recipient_device_id < new.recipient_device_id
  ) then
    return new;
  end if;

  -- Hard stop: a blocked pair never notifies.
  if public.pair_blocked(v_msg.sender_id, new.recipient_id) then return new; end if;

  select c.kind into v_kind from public.direct_conversations c where c.id = v_conversation;

  -- Silent-stranger rule: a stranger's message that has not earned a chat push
  -- surfaces only as a Message Request (enqueued separately). Mutual contacts,
  -- accepted requests, and group members pass through.
  if v_kind = 'dm' and not exists (
    select 1 from public.contacts a
    join public.contacts b on b.owner_id = a.contact_id and b.contact_id = a.owner_id
    where a.owner_id = new.recipient_id and a.contact_id = v_msg.sender_id
  ) and not exists (
    select 1 from public.contact_requests req
    where req.conversation_id = v_conversation
      and req.recipient_id = new.recipient_id
      and req.status = 'accepted'
  ) then
    return new;
  end if;

  -- Per-conversation setting. 'off' or an active mute suppresses entirely.
  -- 'mentions' still delivers a routing push (kind marks it) because the server
  -- cannot see mentions; the device suppresses non-mention notifications.
  select * into v_pref from public.conversation_notification_prefs
  where user_id = new.recipient_id and conversation_id = v_conversation;
  if v_pref.user_id is not null then
    if v_pref.level = 'off' then return new; end if;
    if v_pref.muted_until is not null and v_pref.muted_until > now() then return new; end if;
  end if;

  v_event_kind := case
    when v_kind = 'group' then 'chat_group'
    else 'chat_dm'
  end;

  insert into public.notification_deliveries (
    user_id, workspace_id, channel, title, body, payload, scheduled_at, event_key
  ) values (
    new.recipient_id,
    null,
    'push'::public.delivery_channel,
    'New message',
    'You have a new encrypted message.',
    jsonb_build_object(
      'kind', v_event_kind,
      'destination', 'Chat',
      'scope', 'account',
      'conversationId', v_conversation::text,
      'conversationKind', coalesce(v_kind, 'dm'),
      'messageId', v_msg.client_sync_id::text,
      'relayId', v_msg.id::text,
      'notifyLevel', coalesce(v_pref.level, 'all')
    ),
    now(),
    'acct:' || v_msg.id::text || ':' || new.recipient_id::text
  )
  on conflict (user_id, channel, event_key) where event_key is not null do nothing;

  return new;
end;
$$;

revoke all on function public.enqueue_account_chat_push() from public, anon, authenticated;

drop trigger if exists account_chat_push_enqueue on public.chat_e2ee_relay_recipients;
create trigger account_chat_push_enqueue
  after insert on public.chat_e2ee_relay_recipients
  for each row execute function public.enqueue_account_chat_push();

-- ---------------------------------------------------------------------------
-- Message (contact) request push: notify the recipient a stranger reached out
-- ---------------------------------------------------------------------------
create or replace function public.enqueue_contact_request_push()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  if new.status <> 'pending' then return new; end if;
  if public.pair_blocked(new.sender_id, new.recipient_id) then return new; end if;

  insert into public.notification_deliveries (
    user_id, workspace_id, channel, title, body, payload, scheduled_at, event_key
  ) values (
    new.recipient_id,
    null,
    'push'::public.delivery_channel,
    'New message request',
    'Someone wants to start a conversation.',
    jsonb_build_object(
      'kind', 'chat_request',
      'destination', 'Requests',
      'scope', 'account',
      'conversationId', new.conversation_id::text,
      'requestId', new.id::text
    ),
    now(),
    'req:' || new.id::text
  )
  on conflict (user_id, channel, event_key) where event_key is not null do nothing;

  return new;
end;
$$;

revoke all on function public.enqueue_contact_request_push() from public, anon, authenticated;

drop trigger if exists contact_request_push_enqueue on public.contact_requests;
create trigger contact_request_push_enqueue
  after insert on public.contact_requests
  for each row execute function public.enqueue_contact_request_push();
