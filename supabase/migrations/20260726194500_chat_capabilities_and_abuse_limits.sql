-- Central, server-authoritative Chat beta capabilities and metadata-only abuse controls.
-- The limiter records actor/action/opaque-target counters only; it never inspects E2EE content.

alter table public.chat_service_capabilities
  add column if not exists capability_version integer not null default 1
    check (capability_version > 0),
  add column if not exists stranger_requests_per_hour integer not null default 5
    check (stranger_requests_per_hour >= 0),
  add column if not exists stranger_requests_per_day integer not null default 20
    check (stranger_requests_per_day >= 0),
  add column if not exists message_burst_per_10_seconds integer not null default 20
    check (message_burst_per_10_seconds >= 1),
  add column if not exists message_sustained_per_minute integer not null default 100
    check (message_sustained_per_minute >= 1),
  add column if not exists mutations_per_minute integer not null default 180
    check (mutations_per_minute >= 1),
  add column if not exists groups_per_hour integer not null default 10
    check (groups_per_hour >= 0),
  add column if not exists invites_per_hour integer not null default 20
    check (invites_per_hour >= 0),
  add column if not exists recovery_requests_per_hour integer not null default 20
    check (recovery_requests_per_hour >= 0),
  add column if not exists max_secure_envelope_bytes bigint not null default 1048576
    check (max_secure_envelope_bytes between 4096 and 8388608);

-- Storage keeps a defensive technical ceiling; the lower product limit remains the capability
-- row and is checked atomically before every reservation.
update storage.buckets
set file_size_limit = 105906176
where id = 'chat-media';

-- Internal thresholds are deliberately not exposed through PostgREST. Clients receive only the
-- public product contract from chat_effective_capabilities().
drop policy if exists chat_capabilities_authenticated_read on public.chat_service_capabilities;
revoke all on public.chat_service_capabilities from public, anon, authenticated;

create or replace function public.chat_effective_capabilities()
returns table (
  capability_version integer,
  service_mode text,
  workspace_members_max integer,
  personal_group_members_max integer,
  authorized_devices_max integer,
  concurrent_chat_devices_max integer,
  files_per_rolling_24h integer,
  max_single_file_bytes bigint,
  upload_bytes_per_rolling_24h bigint,
  dm_outstanding_relay_bytes bigint,
  group_outstanding_relay_bytes bigint,
  channel_outstanding_relay_bytes bigint,
  workspace_outstanding_relay_bytes bigint,
  account_deletion_max_grace_days integer,
  presence_refresh_seconds integer,
  background_refresh_seconds integer,
  media_upload_enabled boolean,
  rich_previews_enabled boolean,
  realtime_presence_enabled boolean,
  explanation text,
  updated_at timestamptz
)
language sql stable security definer set search_path = '' as $$
  select
    c.capability_version,
    c.service_mode,
    c.workspace_members_max,
    c.personal_group_members_max,
    c.authorized_devices_max,
    c.concurrent_chat_devices_max,
    c.files_per_rolling_24h,
    c.max_single_file_bytes,
    c.upload_bytes_per_rolling_24h,
    c.dm_outstanding_relay_bytes,
    c.group_outstanding_relay_bytes,
    c.channel_outstanding_relay_bytes,
    c.workspace_outstanding_relay_bytes,
    c.account_deletion_max_grace_days,
    case c.service_mode when 'normal' then 15 when 'conserve' then 45 else 120 end,
    case c.service_mode when 'normal' then 4 when 'conserve' then 12 else 30 end,
    c.service_mode <> 'degraded',
    c.service_mode = 'normal',
    c.service_mode <> 'degraded',
    case c.service_mode
      when 'normal' then 'Chat is operating normally. Pre-beta transfer and membership limits apply.'
      when 'conserve' then 'Chat is conserving service capacity. Messages continue, while presence and previews refresh less often.'
      else 'Chat is in reduced service. Text and security actions continue; new media transfers and nonessential live updates are paused.'
    end,
    c.updated_at
  from public.chat_service_capabilities c
  where c.singleton and auth.uid() is not null;
$$;

revoke all on function public.chat_effective_capabilities() from public, anon;
grant execute on function public.chat_effective_capabilities() to authenticated;

create schema if not exists private;

create table if not exists private.chat_rate_events (
  id bigint generated always as identity primary key,
  actor_id uuid not null,
  action text not null,
  subject_hash bytea not null,
  weight integer not null default 1 check (weight > 0),
  created_at timestamptz not null default now()
);

create index if not exists chat_rate_events_window_idx
  on private.chat_rate_events(actor_id, action, created_at desc);
revoke all on private.chat_rate_events from public, anon, authenticated;

create or replace function private.enforce_chat_rate(
  p_action text,
  p_subject text,
  p_window interval,
  p_limit integer,
  p_weight integer default 1
) returns void
language plpgsql security definer set search_path = '' as $$
declare
  v_actor uuid := auth.uid();
  v_subject_hash bytea;
  v_used bigint;
begin
  if v_actor is null then
    raise exception using errcode = '42501', message = 'authentication_required';
  end if;
  if p_action is null or p_window <= interval '0 seconds' or p_limit < 0 or p_weight <= 0 then
    raise exception using errcode = '22023', message = 'invalid_chat_rate_rule';
  end if;
  v_subject_hash := extensions.digest(coalesce(p_subject, 'global'), 'sha256');
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(v_actor::text || ':' || p_action || ':' || encode(v_subject_hash, 'hex'), 0)
  );
  select coalesce(sum(e.weight), 0) into v_used
  from private.chat_rate_events e
  where e.actor_id = v_actor
    and e.action = p_action
    and e.subject_hash = v_subject_hash
    and e.created_at >= now() - p_window;
  if v_used + p_weight > p_limit then
    raise exception using errcode = '54000', message = 'chat_rate_limit:' || p_action;
  end if;
  insert into private.chat_rate_events(actor_id, action, subject_hash, weight)
  values (v_actor, p_action, v_subject_hash, p_weight);
end;
$$;
revoke all on function private.enforce_chat_rate(text, text, interval, integer, integer)
  from public, anon, authenticated;

create or replace function private.enforce_chat_membership_caps()
returns trigger language plpgsql security definer set search_path = '' as $$
declare
  v_cap public.chat_service_capabilities%rowtype;
  v_count integer;
  v_kind text;
  v_old_user uuid;
begin
  select * into v_cap from public.chat_service_capabilities where singleton;
  if tg_op = 'UPDATE' then
    v_old_user := old.user_id;
  end if;
  if tg_table_name = 'workspace_memberships' then
    if new.removed_at is not null
       or (tg_op = 'UPDATE' and old.removed_at is null) then
      return new;
    end if;
    perform pg_catalog.pg_advisory_xact_lock(
      pg_catalog.hashtextextended('workspace-members:' || new.workspace_id::text, 0)
    );
    select count(*) into v_count from public.workspace_memberships m
    where m.workspace_id = new.workspace_id and m.removed_at is null
      and (v_old_user is null or m.user_id <> v_old_user);
    if v_count >= v_cap.workspace_members_max then
      raise exception using errcode = '54000', message = 'workspace_member_beta_limit';
    end if;
  elsif tg_table_name = 'direct_conversation_members' then
    select c.kind into v_kind from public.direct_conversations c where c.id = new.conversation_id;
    if v_kind = 'group' then
      perform pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended('group-members:' || new.conversation_id::text, 0)
      );
      select count(*) into v_count from public.direct_conversation_members m
      where m.conversation_id = new.conversation_id
        and (v_old_user is null or m.user_id <> v_old_user);
      if v_count >= v_cap.personal_group_members_max then
        raise exception using errcode = '54000', message = 'personal_group_member_beta_limit';
      end if;
    end if;
  elsif tg_table_name = 'chat_channel_members' then
    perform pg_catalog.pg_advisory_xact_lock(
      pg_catalog.hashtextextended(
        'channel-members:' || new.workspace_id::text || ':' || new.channel_sync_id::text,
        0
      )
    );
    select count(*) into v_count from public.chat_channel_members m
    where m.workspace_id = new.workspace_id and m.channel_sync_id = new.channel_sync_id
      and (v_old_user is null or m.user_id <> v_old_user);
    if v_count >= v_cap.workspace_members_max then
      raise exception using errcode = '54000', message = 'workspace_channel_member_beta_limit';
    end if;
  end if;
  return new;
end;
$$;
revoke all on function private.enforce_chat_membership_caps() from public, anon, authenticated;

drop trigger if exists enforce_chat_workspace_member_cap on public.workspace_memberships;
create trigger enforce_chat_workspace_member_cap
before insert or update of removed_at on public.workspace_memberships
for each row execute function private.enforce_chat_membership_caps();

drop trigger if exists enforce_chat_group_member_cap on public.direct_conversation_members;
create trigger enforce_chat_group_member_cap
before insert or update of conversation_id on public.direct_conversation_members
for each row execute function private.enforce_chat_membership_caps();

drop trigger if exists enforce_chat_channel_member_cap on public.chat_channel_members;
create trigger enforce_chat_channel_member_cap
before insert or update of workspace_id, channel_sync_id on public.chat_channel_members
for each row execute function private.enforce_chat_membership_caps();

create or replace function private.guard_new_chat_conversation()
returns trigger language plpgsql security definer set search_path = '' as $$
declare
  v_cap public.chat_service_capabilities%rowtype;
  v_peer uuid;
begin
  select * into v_cap from public.chat_service_capabilities where singleton;
  if new.kind = 'group' then
    perform private.enforce_chat_rate('new_group', 'account', interval '1 hour', v_cap.groups_per_hour);
  elsif new.kind = 'dm' and new.dm_key is not null then
    v_peer := case
      when split_part(new.dm_key, ':', 1) = auth.uid()::text then split_part(new.dm_key, ':', 2)::uuid
      else split_part(new.dm_key, ':', 1)::uuid
    end;
    if not exists (
      select 1 from public.contacts c where c.owner_id = auth.uid() and c.contact_id = v_peer
    ) then
      perform private.enforce_chat_rate('stranger_request_hour', 'account', interval '1 hour', v_cap.stranger_requests_per_hour);
      perform private.enforce_chat_rate('stranger_request_day', 'account', interval '24 hours', v_cap.stranger_requests_per_day);
    end if;
  end if;
  return new;
end;
$$;
revoke all on function private.guard_new_chat_conversation() from public, anon, authenticated;

drop trigger if exists guard_new_chat_conversation on public.direct_conversations;
create trigger guard_new_chat_conversation
before insert on public.direct_conversations
for each row execute function private.guard_new_chat_conversation();

create or replace function private.guard_friend_invite_rate()
returns trigger language plpgsql security definer set search_path = '' as $$
declare v_limit integer;
begin
  select invites_per_hour into v_limit from public.chat_service_capabilities where singleton;
  perform private.enforce_chat_rate('friend_invite', 'account', interval '1 hour', v_limit);
  return new;
end;
$$;
revoke all on function private.guard_friend_invite_rate() from public, anon, authenticated;
drop trigger if exists guard_friend_invite_rate on public.friend_invites;
create trigger guard_friend_invite_rate before insert on public.friend_invites
for each row execute function private.guard_friend_invite_rate();

create or replace function private.guard_chat_recovery_rate()
returns trigger language plpgsql security definer set search_path = '' as $$
declare v_limit integer;
begin
  select recovery_requests_per_hour into v_limit from public.chat_service_capabilities where singleton;
  perform private.enforce_chat_rate('encrypted_recovery', new.security_epoch_id::text, interval '1 hour', v_limit);
  return new;
end;
$$;
revoke all on function private.guard_chat_recovery_rate() from public, anon, authenticated;
drop trigger if exists guard_chat_recovery_rate on public.chat_recovery_requests;
create trigger guard_chat_recovery_rate before insert on public.chat_recovery_requests
for each row execute function private.guard_chat_recovery_rate();

-- The original request policy counted the legacy direct_messages table only. Secure account Chat
-- stores message bodies exclusively in the opaque relay, so count relay metadata as well and
-- create the same request row after the first E2EE message without exposing a preview.
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
  if p_sender is null or auth.uid() is null or p_sender <> auth.uid() then return false; end if;
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
  if v_recipient is null or public.pair_blocked(p_sender, v_recipient) then return false; end if;

  select exists (
    select 1 from public.contacts a
    join public.contacts b on b.owner_id = a.contact_id and b.contact_id = a.owner_id
    where a.owner_id = p_sender and a.contact_id = v_recipient
  ) into v_mutual;
  if v_mutual then return true; end if;

  select * into v_request from public.contact_requests r
  where r.conversation_id = p_conversation
    and r.sender_id = p_sender
    and r.recipient_id = v_recipient;
  if v_request.status = 'accepted' then return true; end if;
  if v_request.status = 'declined' then return false; end if;

  select * into v_pref from public.message_request_preferences p where p.owner_id = v_recipient;
  if not found then
    v_pref.sender_policy := 'shared_workspace';
    v_pref.pending_behavior := 'first_only';
    v_pref.request_file_policy := 'none';
    v_pref.limited_followups := 2;
  end if;
  if v_pref.sender_policy = 'nobody' then return false; end if;
  if v_pref.sender_policy = 'shared_workspace'
     and not private.users_share_active_workspace(p_sender, v_recipient) then return false; end if;

  select
    (select count(*) from public.direct_messages m
     where m.conversation_id = p_conversation and m.sender_id = p_sender)
    +
    (select count(*) from public.chat_e2ee_relay_messages m
     join public.chat_security_epochs e on e.id = m.security_epoch_id
     where e.account_conversation_id = p_conversation
       and m.sender_id = p_sender
       and m.origin_relay_id is null
       and m.envelope #>> '{metadata,contentType}' in ('text', 'media'))
  into v_prior_count;
  if v_prior_count = 0 then return true; end if;
  if v_request.id is null then return false; end if;

  select * into v_extension from public.message_request_extensions e
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
revoke all on function private.can_send_direct_message(uuid, uuid) from public, anon;
grant execute on function private.can_send_direct_message(uuid, uuid) to authenticated;

create or replace function private.queue_contact_request_for_e2ee()
returns trigger language plpgsql security definer set search_path = '' as $$
declare
  v_conversation uuid;
  v_recipient uuid;
begin
  select e.account_conversation_id into v_conversation
  from public.chat_security_epochs e
  join public.direct_conversations c on c.id = e.account_conversation_id and c.kind = 'dm'
  where e.id = new.security_epoch_id;
  if v_conversation is null then return new; end if;
  if new.envelope #>> '{metadata,contentType}' not in ('text', 'media') then return new; end if;
  select m.user_id into v_recipient from public.direct_conversation_members m
  where m.conversation_id = v_conversation and m.user_id <> new.sender_id limit 1;
  if v_recipient is not null
     and not public.pair_blocked(new.sender_id, v_recipient)
     and not exists (
       select 1 from public.contacts a
       join public.contacts b on b.owner_id = a.contact_id and b.contact_id = a.owner_id
       where a.owner_id = new.sender_id and a.contact_id = v_recipient
     ) then
    insert into public.contact_requests(conversation_id, sender_id, recipient_id)
    values (v_conversation, new.sender_id, v_recipient)
    on conflict (conversation_id, recipient_id) do nothing;
  end if;
  return new;
end;
$$;
revoke all on function private.queue_contact_request_for_e2ee() from public, anon, authenticated;
drop trigger if exists queue_contact_request_after_e2ee on public.chat_e2ee_relay_messages;
create trigger queue_contact_request_after_e2ee
after insert on public.chat_e2ee_relay_messages
for each row execute function private.queue_contact_request_for_e2ee();

create or replace function private.guard_chat_relay_write()
returns trigger language plpgsql security definer set search_path = '' as $$
declare
  v_cap public.chat_service_capabilities%rowtype;
  v_type text := coalesce(new.envelope #>> '{metadata,contentType}', 'unknown');
begin
  if exists (
    select 1 from public.chat_e2ee_relay_messages m
    where m.security_epoch_id = new.security_epoch_id
      and m.client_sync_id = new.client_sync_id
      and m.sender_id = new.sender_id
  ) then
    return new;
  end if;
  select * into v_cap from public.chat_service_capabilities where singleton;
  if octet_length(new.envelope::text) > v_cap.max_secure_envelope_bytes then
    raise exception using errcode = '54000', message = 'secure_envelope_too_large';
  end if;
  if v_cap.service_mode = 'degraded' and v_type = 'media' then
    raise exception using errcode = '55000', message = 'chat_media_temporarily_unavailable';
  end if;
  if v_type in ('text', 'media') then
    perform private.enforce_chat_rate('message_burst', new.security_epoch_id::text, interval '10 seconds', v_cap.message_burst_per_10_seconds);
    perform private.enforce_chat_rate('message_sustained', new.security_epoch_id::text, interval '1 minute', v_cap.message_sustained_per_minute);
  else
    perform private.enforce_chat_rate('message_mutation', new.security_epoch_id::text, interval '1 minute', v_cap.mutations_per_minute);
  end if;
  return new;
end;
$$;
revoke all on function private.guard_chat_relay_write() from public, anon, authenticated;
drop trigger if exists guard_chat_relay_write on public.chat_e2ee_relay_messages;
create trigger guard_chat_relay_write before insert on public.chat_e2ee_relay_messages
for each row execute function private.guard_chat_relay_write();

create or replace function private.guard_managed_chat_write()
returns trigger language plpgsql security definer set search_path = '' as $$
declare v_cap public.chat_service_capabilities%rowtype;
begin
  if new.object_type <> 'chat_message' then return new; end if;
  select * into v_cap from public.chat_service_capabilities where singleton;
  if v_cap.service_mode = 'degraded'
     and new.content ? 'secureAttachment' then
    raise exception using errcode = '55000', message = 'chat_media_temporarily_unavailable';
  end if;
  if tg_op = 'INSERT' then
    perform private.enforce_chat_rate('managed_message_burst', new.workspace_id::text, interval '10 seconds', v_cap.message_burst_per_10_seconds);
    perform private.enforce_chat_rate('managed_message_sustained', new.workspace_id::text, interval '1 minute', v_cap.message_sustained_per_minute);
  elsif old.content is distinct from new.content or old.deleted_at is distinct from new.deleted_at then
    perform private.enforce_chat_rate('managed_message_mutation', new.workspace_id::text, interval '1 minute', v_cap.mutations_per_minute);
  end if;
  return new;
end;
$$;
revoke all on function private.guard_managed_chat_write() from public, anon, authenticated;
drop trigger if exists guard_managed_chat_write on public.workspace_objects;
create trigger guard_managed_chat_write
before insert or update of content, deleted_at on public.workspace_objects
for each row execute function private.guard_managed_chat_write();

create or replace function private.guard_chat_media_outstanding_bytes()
returns trigger language plpgsql security definer set search_path = '' as $$
declare
  v_cap public.chat_service_capabilities%rowtype;
  v_epoch public.chat_security_epochs%rowtype;
  v_kind text;
  v_used bigint;
  v_recipient uuid;
  v_file_policy text;
  v_mutual boolean;
  v_daily_count integer;
  v_daily_bytes bigint;
begin
  select * into v_cap from public.chat_service_capabilities where singleton;
  select * into v_epoch from public.chat_security_epochs e where e.id = new.security_epoch_id;
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended('chat-media-uploader:' || new.uploader_id::text, 0)
  );
  if v_cap.service_mode = 'degraded' then
    raise exception using errcode = '55000', message = 'chat_media_temporarily_unavailable';
  end if;
  if new.plaintext_bytes > v_cap.max_single_file_bytes
     or new.ciphertext_bytes > v_cap.max_single_file_bytes + 1048576 then
    raise exception using errcode = '54000', message = 'chat_file_too_large';
  end if;
  select count(*), coalesce(sum(m.plaintext_bytes), 0)
    into v_daily_count, v_daily_bytes
  from public.chat_media_objects m
  where m.uploader_id = new.uploader_id
    and m.created_at >= now() - interval '24 hours'
    and m.state <> 'deleted';
  if v_daily_count >= v_cap.files_per_rolling_24h then
    raise exception using errcode = '54000', message = 'chat_daily_file_count_limit';
  end if;
  if v_daily_bytes + new.plaintext_bytes > v_cap.upload_bytes_per_rolling_24h then
    raise exception using errcode = '54000', message = 'chat_daily_upload_bytes_limit';
  end if;
  if v_epoch.scope = 'account' then
    perform pg_catalog.pg_advisory_xact_lock(
      pg_catalog.hashtextextended('chat-media-account:' || v_epoch.account_conversation_id::text, 0)
    );
    select c.kind into v_kind from public.direct_conversations c where c.id = v_epoch.account_conversation_id;
    if v_kind = 'dm' then
      if not private.can_send_direct_message(v_epoch.account_conversation_id, new.uploader_id) then
        raise exception using errcode = '42501', message = 'request_policy_denied';
      end if;
      select m.user_id into v_recipient from public.direct_conversation_members m
      where m.conversation_id = v_epoch.account_conversation_id
        and m.user_id <> new.uploader_id limit 1;
      select exists (
        select 1 from public.contacts a
        join public.contacts b on b.owner_id = a.contact_id and b.contact_id = a.owner_id
        where a.owner_id = new.uploader_id and a.contact_id = v_recipient
      ) into v_mutual;
      if not coalesce(v_mutual, false) then
        select coalesce(p.request_file_policy, 'none') into v_file_policy
        from public.message_request_preferences p where p.owner_id = v_recipient;
        v_file_policy := coalesce(v_file_policy, 'none');
        if v_file_policy = 'none'
           or (v_file_policy = 'images' and new.media_category <> 'image') then
          raise exception using errcode = '42501', message = 'request_media_policy_denied';
        end if;
      end if;
    end if;
    select coalesce(sum(m.ciphertext_bytes), 0) into v_used
    from public.chat_media_objects m
    join public.chat_security_epochs e on e.id = m.security_epoch_id
    where e.account_conversation_id = v_epoch.account_conversation_id
      and m.state <> 'deleted'
      and (m.content_expires_at is null or m.content_expires_at > now());
    if v_used + new.ciphertext_bytes > case when v_kind = 'dm' then v_cap.dm_outstanding_relay_bytes else v_cap.group_outstanding_relay_bytes end then
      raise exception using errcode = '54000', message = 'chat_conversation_outstanding_bytes_limit';
    end if;
  else
    perform pg_catalog.pg_advisory_xact_lock(
      pg_catalog.hashtextextended('chat-media-workspace:' || v_epoch.workspace_id::text, 0)
    );
    select coalesce(sum(m.ciphertext_bytes), 0) into v_used
    from public.chat_media_objects m
    join public.chat_security_epochs e on e.id = m.security_epoch_id
    where e.workspace_id = v_epoch.workspace_id
      and e.channel_id is not distinct from v_epoch.channel_id
      and m.state <> 'deleted'
      and (m.content_expires_at is null or m.content_expires_at > now());
    if v_used + new.ciphertext_bytes > v_cap.channel_outstanding_relay_bytes then
      raise exception using errcode = '54000', message = 'chat_channel_outstanding_bytes_limit';
    end if;
    select coalesce(sum(m.ciphertext_bytes), 0) into v_used
    from public.chat_media_objects m
    join public.chat_security_epochs e on e.id = m.security_epoch_id
    where e.workspace_id = v_epoch.workspace_id
      and m.state <> 'deleted'
      and (m.content_expires_at is null or m.content_expires_at > now());
    if v_used + new.ciphertext_bytes > v_cap.workspace_outstanding_relay_bytes then
      raise exception using errcode = '54000', message = 'chat_workspace_outstanding_bytes_limit';
    end if;
  end if;
  return new;
end;
$$;
revoke all on function private.guard_chat_media_outstanding_bytes() from public, anon, authenticated;
drop trigger if exists guard_chat_media_outstanding_bytes on public.chat_media_objects;
create trigger guard_chat_media_outstanding_bytes before insert on public.chat_media_objects
for each row execute function private.guard_chat_media_outstanding_bytes();

create or replace function private.purge_chat_rate_events()
returns bigint language plpgsql security definer set search_path = '' as $$
declare v_deleted bigint;
begin
  delete from private.chat_rate_events where created_at < now() - interval '48 hours';
  get diagnostics v_deleted = row_count;
  return v_deleted;
end;
$$;
revoke all on function private.purge_chat_rate_events() from public, anon, authenticated;

do $$
begin
  perform cron.unschedule('norfold-chat-rate-event-purge');
exception when others then null;
end $$;
select cron.schedule(
  'norfold-chat-rate-event-purge',
  '17 * * * *',
  'select private.purge_chat_rate_events()'
);
