-- Pre-Beta Stage 1: device trust, security epochs, opaque E2EE relay,
-- delivery/purge semantics, and best-effort encrypted recovery coordination.

create table public.chat_service_capabilities (
  singleton boolean primary key default true check (singleton),
  service_mode text not null default 'normal'
    check (service_mode in ('normal', 'conserve', 'degraded')),
  workspace_members_max integer not null default 4 check (workspace_members_max >= 1),
  personal_group_members_max integer not null default 8 check (personal_group_members_max >= 2),
  authorized_devices_max integer not null default 5 check (authorized_devices_max >= 1),
  concurrent_chat_devices_max integer not null default 3 check (concurrent_chat_devices_max >= 1),
  files_per_rolling_24h integer not null default 5 check (files_per_rolling_24h >= 0),
  max_single_file_bytes bigint not null default 26214400 check (max_single_file_bytes >= 0),
  upload_bytes_per_rolling_24h bigint not null default 52428800 check (upload_bytes_per_rolling_24h >= 0),
  dm_outstanding_relay_bytes bigint not null default 52428800 check (dm_outstanding_relay_bytes >= 0),
  group_outstanding_relay_bytes bigint not null default 104857600 check (group_outstanding_relay_bytes >= 0),
  channel_outstanding_relay_bytes bigint not null default 104857600 check (channel_outstanding_relay_bytes >= 0),
  workspace_outstanding_relay_bytes bigint not null default 104857600 check (workspace_outstanding_relay_bytes >= 0),
  delivered_relay_grace interval not null default interval '24 hours',
  undelivered_relay_max interval not null default interval '30 days',
  account_deletion_max_grace_days integer not null default 30
    check (account_deletion_max_grace_days between 7 and 365),
  updated_at timestamptz not null default now()
);

insert into public.chat_service_capabilities (singleton) values (true)
on conflict (singleton) do nothing;

alter table public.chat_service_capabilities enable row level security;
create policy chat_capabilities_authenticated_read
  on public.chat_service_capabilities for select to authenticated using (true);
grant select on public.chat_service_capabilities to authenticated;

create table public.chat_device_credentials (
  device_id uuid primary key references public.devices(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  crypto_generation integer not null check (crypto_generation > 0),
  hpke_public_keyset text not null,
  signing_public_keyset text not null,
  credential_fingerprint text not null,
  friendly_name text not null check (char_length(friendly_name) between 1 and 80),
  authorized_at timestamptz not null default now(),
  authorized_by_device uuid references public.devices(id) on delete set null,
  last_chat_activity_at timestamptz,
  active_lease_until timestamptz,
  archive_capable boolean not null default false,
  revoked_at timestamptz,
  unique (user_id, credential_fingerprint)
);

create index chat_device_credentials_user_active_idx
  on public.chat_device_credentials(user_id, active_lease_until desc)
  where revoked_at is null;

alter table public.chat_device_credentials enable row level security;
create policy chat_device_credentials_owner_read
  on public.chat_device_credentials for select to authenticated
  using (user_id = (select auth.uid()));
revoke all on public.chat_device_credentials from anon, authenticated;
grant select on public.chat_device_credentials to authenticated;

create table public.chat_security_events (
  id uuid primary key default gen_random_uuid(),
  account_id uuid not null references public.profiles(id) on delete cascade,
  device_id uuid references public.devices(id) on delete set null,
  event_type text not null check (event_type in (
    'device_authorized', 'device_revoked', 'identity_reset',
    'security_epoch_started', 'security_mode_changed'
  )),
  crypto_generation integer,
  detail jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create index chat_security_events_account_idx
  on public.chat_security_events(account_id, created_at desc);
alter table public.chat_security_events enable row level security;
create policy chat_security_events_owner_read
  on public.chat_security_events for select to authenticated
  using (account_id = (select auth.uid()));
revoke all on public.chat_security_events from anon, authenticated;
grant select on public.chat_security_events to authenticated;

-- Public credentials are immutable by generation so delayed messages remain
-- verifiable after a device rotates its private material.
create table public.chat_device_key_history (
  device_id uuid not null references public.devices(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  crypto_generation integer not null check (crypto_generation > 0),
  hpke_public_keyset text not null,
  signing_public_keyset text not null,
  credential_fingerprint text not null,
  registered_at timestamptz not null default now(),
  revoked_at timestamptz,
  primary key (device_id, crypto_generation)
);

alter table public.chat_device_key_history enable row level security;
create policy chat_device_key_history_owner_read
  on public.chat_device_key_history for select to authenticated
  using (user_id = (select auth.uid()));
revoke all on public.chat_device_key_history from anon, authenticated;
grant select on public.chat_device_key_history to authenticated;

create or replace function public.register_chat_device(
  p_device uuid,
  p_friendly_name text,
  p_crypto_generation integer,
  p_hpke_public_keyset text,
  p_signing_public_keyset text,
  p_credential_fingerprint text,
  p_archive_capable boolean default false
) returns void
language plpgsql security definer set search_path = '' as $$
declare
  v_limit integer;
  v_count integer;
  v_was_current boolean;
begin
  if auth.uid() is null then
    raise exception using errcode = '42501', message = 'authentication_required';
  end if;
  if p_crypto_generation <= 0
     or btrim(coalesce(p_hpke_public_keyset, '')) = ''
     or btrim(coalesce(p_signing_public_keyset, '')) = ''
     or btrim(coalesce(p_credential_fingerprint, '')) = '' then
    raise exception using errcode = '22023', message = 'invalid_device_credential';
  end if;

  select exists (
    select 1 from public.chat_device_credentials c
    where c.device_id = p_device
      and c.user_id = auth.uid()
      and c.crypto_generation = p_crypto_generation
      and c.credential_fingerprint = p_credential_fingerprint
      and c.revoked_at is null
  ) into v_was_current;

  select authorized_devices_max into v_limit
  from public.chat_service_capabilities where singleton;
  select count(*) into v_count
  from public.chat_device_credentials c
  where c.user_id = auth.uid() and c.revoked_at is null and c.device_id <> p_device;
  if v_count >= v_limit then
    raise exception using errcode = '54000', message = 'authorized_device_limit';
  end if;

  insert into public.devices (id, user_id, name, platform, last_seen_at)
  values (p_device, auth.uid(), left(btrim(p_friendly_name), 80), 'android', now())
  on conflict (id) do update
  set name = excluded.name, last_seen_at = excluded.last_seen_at
  where public.devices.user_id = auth.uid();

  if not exists (
    select 1 from public.devices d where d.id = p_device and d.user_id = auth.uid()
  ) then
    raise exception using errcode = '42501', message = 'device_owner_mismatch';
  end if;

  insert into public.chat_device_credentials (
    device_id, user_id, crypto_generation, hpke_public_keyset,
    signing_public_keyset, credential_fingerprint, friendly_name,
    archive_capable, revoked_at
  ) values (
    p_device, auth.uid(), p_crypto_generation, p_hpke_public_keyset,
    p_signing_public_keyset, p_credential_fingerprint,
    left(btrim(p_friendly_name), 80), p_archive_capable, null
  )
  on conflict (device_id) do update
  set crypto_generation = excluded.crypto_generation,
      hpke_public_keyset = excluded.hpke_public_keyset,
      signing_public_keyset = excluded.signing_public_keyset,
      credential_fingerprint = excluded.credential_fingerprint,
      friendly_name = excluded.friendly_name,
      archive_capable = excluded.archive_capable,
      revoked_at = null
  where public.chat_device_credentials.user_id = auth.uid();

  insert into public.chat_device_key_history (
    device_id, user_id, crypto_generation, hpke_public_keyset,
    signing_public_keyset, credential_fingerprint, revoked_at
  ) values (
    p_device, auth.uid(), p_crypto_generation, p_hpke_public_keyset,
    p_signing_public_keyset, p_credential_fingerprint, null
  )
  on conflict (device_id, crypto_generation) do nothing;

  if not exists (
    select 1 from public.chat_device_key_history h
    where h.device_id = p_device
      and h.user_id = auth.uid()
      and h.crypto_generation = p_crypto_generation
      and h.hpke_public_keyset = p_hpke_public_keyset
      and h.signing_public_keyset = p_signing_public_keyset
      and h.credential_fingerprint = p_credential_fingerprint
  ) then
    raise exception using errcode = '22023', message = 'credential_generation_conflict';
  end if;

  if not v_was_current then
    insert into public.chat_security_events (
      account_id, device_id, event_type, crypto_generation
    ) values (auth.uid(), p_device, 'device_authorized', p_crypto_generation);
  end if;
end;
$$;

revoke all on function public.register_chat_device(uuid, text, integer, text, text, text, boolean) from public, anon;
grant execute on function public.register_chat_device(uuid, text, integer, text, text, text, boolean) to authenticated;

create or replace function public.renew_chat_device_lease(
  p_device uuid,
  p_lease_seconds integer default 120
) returns timestamptz
language plpgsql security definer set search_path = '' as $$
declare
  v_limit integer;
  v_active integer;
  v_until timestamptz;
begin
  if auth.uid() is null or not exists (
    select 1 from public.chat_device_credentials c
    where c.device_id = p_device and c.user_id = auth.uid() and c.revoked_at is null
  ) then
    raise exception using errcode = '42501', message = 'authorized_device_required';
  end if;

  select concurrent_chat_devices_max into v_limit
  from public.chat_service_capabilities where singleton;
  select count(*) into v_active
  from public.chat_device_credentials c
  where c.user_id = auth.uid()
    and c.device_id <> p_device
    and c.revoked_at is null
    and c.active_lease_until > now();
  if v_active >= v_limit then
    raise exception using errcode = '54000', message = 'active_chat_device_limit';
  end if;

  v_until := now() + make_interval(secs => greatest(30, least(p_lease_seconds, 300)));
  update public.chat_device_credentials
  set active_lease_until = v_until, last_chat_activity_at = now()
  where device_id = p_device and user_id = auth.uid();
  update public.devices set last_seen_at = now()
  where id = p_device and user_id = auth.uid();
  return v_until;
end;
$$;

revoke all on function public.renew_chat_device_lease(uuid, integer) from public, anon;
grant execute on function public.renew_chat_device_lease(uuid, integer) to authenticated;

create or replace function public.revoke_chat_device(p_device uuid)
returns void
language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then
    raise exception using errcode = '42501', message = 'authentication_required';
  end if;
  update public.chat_device_credentials
  set revoked_at = now(), active_lease_until = null
  where device_id = p_device and user_id = auth.uid() and revoked_at is null;
  if not found then
    raise exception using errcode = 'P0002', message = 'device_not_found';
  end if;
  update public.push_tokens set enabled = false
  where device_id = p_device;
  update public.chat_device_key_history set revoked_at = now()
  where device_id = p_device and user_id = auth.uid() and revoked_at is null;
  insert into public.chat_security_events (account_id, device_id, event_type)
  values (auth.uid(), p_device, 'device_revoked');
end;
$$;

revoke all on function public.revoke_chat_device(uuid) from public, anon;
grant execute on function public.revoke_chat_device(uuid) to authenticated;

create type public.chat_security_mode as enum ('e2ee', 'managed');
create type public.chat_relay_state as enum ('active', 'purged', 'expired', 'revoked');

create table public.chat_security_epochs (
  id uuid primary key default gen_random_uuid(),
  scope text not null check (scope in ('account', 'workspace')),
  account_conversation_id uuid references public.direct_conversations(id) on delete cascade,
  workspace_id uuid references public.workspaces(id) on delete cascade,
  channel_id uuid,
  epoch_number integer not null check (epoch_number > 0),
  mode public.chat_security_mode not null,
  protocol text not null,
  started_by uuid not null references public.profiles(id),
  started_at timestamptz not null default now(),
  ended_at timestamptz,
  check (
    (scope = 'account' and account_conversation_id is not null and workspace_id is null)
    or (scope = 'workspace' and workspace_id is not null and account_conversation_id is null)
  )
);

create unique index chat_security_epochs_account_active_idx
  on public.chat_security_epochs(account_conversation_id)
  where scope = 'account' and ended_at is null;
create unique index chat_security_epochs_workspace_active_idx
  on public.chat_security_epochs(workspace_id, coalesce(channel_id, '00000000-0000-0000-0000-000000000000'::uuid))
  where scope = 'workspace' and ended_at is null;

alter table public.chat_security_epochs enable row level security;
create policy chat_security_epochs_account_read
  on public.chat_security_epochs for select to authenticated
  using (
    scope = 'account' and public.is_direct_member(account_conversation_id)
  );
create policy chat_security_epochs_workspace_read
  on public.chat_security_epochs for select to authenticated
  using (
    scope = 'workspace'
    and (
      (channel_id is null and private.is_active_workspace_member(workspace_id, (select auth.uid())))
      or private.can_access_workspace_channel(workspace_id, channel_id, (select auth.uid()))
    )
  );
revoke all on public.chat_security_epochs from anon, authenticated;
grant select on public.chat_security_epochs to authenticated;

create or replace function private.can_access_security_epoch(
  p_epoch uuid,
  p_user uuid
) returns boolean
language sql stable security definer set search_path = '' as $$
  select exists (
    select 1
    from public.chat_security_epochs e
    where e.id = p_epoch
      and (
        (e.scope = 'account' and exists (
          select 1 from public.direct_conversation_members m
          where m.conversation_id = e.account_conversation_id and m.user_id = p_user
        ))
        or (
          e.scope = 'workspace'
          and (
            (e.channel_id is null and private.is_active_workspace_member(e.workspace_id, p_user))
            or private.can_access_workspace_channel(e.workspace_id, e.channel_id, p_user)
          )
        )
      )
  );
$$;

revoke all on function private.can_access_security_epoch(uuid, uuid) from public, anon;
grant execute on function private.can_access_security_epoch(uuid, uuid) to authenticated;

create or replace function public.ensure_account_security_epoch(
  p_conversation uuid,
  p_protocol text default 'norfold-hpke-x25519-aes256gcm-ed25519-v1'
) returns uuid
language plpgsql security definer set search_path = '' as $$
declare
  v_epoch uuid;
begin
  if auth.uid() is null or not public.is_direct_member(p_conversation) then
    raise exception using errcode = '42501', message = 'conversation_membership_required';
  end if;
  select id into v_epoch from public.chat_security_epochs
  where account_conversation_id = p_conversation and ended_at is null
  for update;
  if v_epoch is not null then return v_epoch; end if;

  insert into public.chat_security_epochs (
    scope, account_conversation_id, epoch_number, mode, protocol, started_by
  ) values ('account', p_conversation, 1, 'e2ee', p_protocol, auth.uid())
  returning id into v_epoch;
  return v_epoch;
exception
  when unique_violation then
    select id into v_epoch from public.chat_security_epochs
    where account_conversation_id = p_conversation and ended_at is null;
    return v_epoch;
end;
$$;

revoke all on function public.ensure_account_security_epoch(uuid, text) from public, anon;
grant execute on function public.ensure_account_security_epoch(uuid, text) to authenticated;

create or replace function private.rotate_account_security_epoch_on_membership()
returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  v_conversation uuid := coalesce(new.conversation_id, old.conversation_id);
  v_previous public.chat_security_epochs%rowtype;
  v_next uuid;
  v_actor uuid;
begin
  if not exists (
    select 1 from public.direct_conversations c
    where c.id = v_conversation and c.kind = 'group'
  ) then
    if tg_op = 'DELETE' then return old; else return new; end if;
  end if;

  select * into v_previous
  from public.chat_security_epochs e
  where e.account_conversation_id = v_conversation and e.ended_at is null
  for update;
  if v_previous.id is null then
    if tg_op = 'DELETE' then return old; else return new; end if;
  end if;

  v_actor := coalesce(
    auth.uid(),
    (select m.user_id from public.direct_conversation_members m
     where m.conversation_id = v_conversation and m.role = 'owner' limit 1),
    old.user_id,
    new.user_id
  );
  update public.chat_security_epochs set ended_at = now()
  where id = v_previous.id;
  insert into public.chat_security_epochs (
    scope, account_conversation_id, epoch_number, mode, protocol, started_by
  ) values (
    'account', v_conversation, v_previous.epoch_number + 1,
    v_previous.mode, v_previous.protocol, v_actor
  ) returning id into v_next;

  insert into public.chat_security_events (
    account_id, event_type, detail
  )
  select m.user_id, 'security_epoch_started', jsonb_build_object(
    'conversationId', v_conversation,
    'previousEpochId', v_previous.id,
    'securityEpochId', v_next,
    'reason', case when tg_op = 'INSERT' then 'member_added' else 'member_removed' end
  )
  from public.direct_conversation_members m
  where m.conversation_id = v_conversation;
  if tg_op = 'DELETE' then return old; else return new; end if;
end;
$$;

revoke all on function private.rotate_account_security_epoch_on_membership()
  from public, anon, authenticated;
drop trigger if exists direct_members_rotate_security_epoch
  on public.direct_conversation_members;
create trigger direct_members_rotate_security_epoch
after insert or delete on public.direct_conversation_members
for each row execute function private.rotate_account_security_epoch_on_membership();

create or replace function public.account_conversation_device_credentials(
  p_conversation uuid
) returns table (
  device_id uuid,
  user_id uuid,
  crypto_generation integer,
  hpke_public_keyset text,
  signing_public_keyset text,
  credential_fingerprint text
)
language sql stable security definer set search_path = '' as $$
  select c.device_id, c.user_id, c.crypto_generation,
         c.hpke_public_keyset, c.signing_public_keyset, c.credential_fingerprint
  from public.chat_device_credentials c
  join public.direct_conversation_members m on m.user_id = c.user_id
  where m.conversation_id = p_conversation
    and c.revoked_at is null
    and exists (
      select 1 from public.direct_conversation_members me
      where me.conversation_id = p_conversation and me.user_id = auth.uid()
    );
$$;

revoke all on function public.account_conversation_device_credentials(uuid) from public, anon;
grant execute on function public.account_conversation_device_credentials(uuid) to authenticated;

create table public.chat_e2ee_relay_messages (
  id uuid primary key default gen_random_uuid(),
  client_sync_id uuid not null,
  security_epoch_id uuid not null references public.chat_security_epochs(id),
  sender_id uuid not null references public.profiles(id),
  sender_device_id uuid not null references public.chat_device_credentials(device_id),
  sender_crypto_generation integer not null check (sender_crypto_generation > 0),
  envelope_version integer not null check (envelope_version > 0),
  envelope jsonb,
  state public.chat_relay_state not null default 'active',
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  delivered_grace_until timestamptz,
  purged_at timestamptz,
  check ((state = 'active' and envelope is not null) or state <> 'active')
);

create unique index chat_e2ee_relay_epoch_sync_idx
  on public.chat_e2ee_relay_messages(security_epoch_id, client_sync_id);
create index chat_e2ee_relay_expiry_idx
  on public.chat_e2ee_relay_messages(coalesce(delivered_grace_until, expires_at))
  where state = 'active';

create table public.chat_e2ee_relay_recipients (
  relay_id uuid not null references public.chat_e2ee_relay_messages(id) on delete cascade,
  recipient_id uuid not null references public.profiles(id) on delete cascade,
  recipient_device_id uuid not null references public.chat_device_credentials(device_id) on delete cascade,
  recipient_crypto_generation integer not null check (recipient_crypto_generation > 0),
  delivered_at timestamptz,
  persisted_at timestamptz,
  read_at timestamptz,
  primary key (relay_id, recipient_device_id, recipient_crypto_generation)
);

create index chat_e2ee_relay_recipient_pending_idx
  on public.chat_e2ee_relay_recipients(recipient_device_id, relay_id)
  where persisted_at is null;

alter table public.chat_e2ee_relay_messages enable row level security;
alter table public.chat_e2ee_relay_recipients enable row level security;

create policy chat_e2ee_relay_authorized_read
  on public.chat_e2ee_relay_messages for select to authenticated
  using (private.can_access_security_epoch(security_epoch_id, (select auth.uid())));
create policy chat_e2ee_recipients_participant_read
  on public.chat_e2ee_relay_recipients for select to authenticated
  using (
    recipient_id = (select auth.uid())
    or exists (
      select 1 from public.chat_e2ee_relay_messages m
      where m.id = chat_e2ee_relay_recipients.relay_id
        and m.sender_id = (select auth.uid())
    )
  );

revoke all on public.chat_e2ee_relay_messages from anon, authenticated;
revoke all on public.chat_e2ee_relay_recipients from anon, authenticated;
grant select on public.chat_e2ee_relay_messages to authenticated;
grant select on public.chat_e2ee_relay_recipients to authenticated;

create or replace function public.send_e2ee_relay_message(
  p_client_sync_id uuid,
  p_security_epoch uuid,
  p_sender_device uuid,
  p_envelope_version integer,
  p_envelope jsonb
) returns uuid
language plpgsql security definer set search_path = '' as $$
declare
  v_relay uuid;
  v_expiry interval;
  v_sender_generation integer;
  v_account_conversation uuid;
  v_protocol text;
begin
  if auth.uid() is null
     or not private.can_access_security_epoch(p_security_epoch, auth.uid())
     or not exists (
       select 1 from public.chat_device_credentials c
       where c.device_id = p_sender_device
         and c.user_id = auth.uid()
         and c.revoked_at is null
     ) then
    raise exception using errcode = '42501', message = 'secure_send_not_authorized';
  end if;
  if p_envelope_version <> 1 or p_envelope is null then
    raise exception using errcode = '22023', message = 'invalid_secure_envelope';
  end if;

  select c.crypto_generation into v_sender_generation
  from public.chat_device_credentials c
  where c.device_id = p_sender_device
    and c.user_id = auth.uid()
    and c.revoked_at is null;
  select e.account_conversation_id, e.protocol
    into v_account_conversation, v_protocol
  from public.chat_security_epochs e where e.id = p_security_epoch;

  if jsonb_typeof(p_envelope) <> 'object'
     or p_envelope->>'suite' is distinct from v_protocol
     or nullif(p_envelope->>'ciphertext', '') is null
     or nullif(p_envelope->>'signature', '') is null
     or jsonb_typeof(p_envelope->'recipientKeys') <> 'array'
     or p_envelope #>> '{metadata,securityEpochId}' is distinct from p_security_epoch::text
     or p_envelope #>> '{metadata,messageId}' is distinct from p_client_sync_id::text
     or p_envelope #>> '{metadata,senderAccountId}' is distinct from auth.uid()::text
     or p_envelope #>> '{metadata,senderDeviceId}' is distinct from p_sender_device::text
     or p_envelope #>> '{metadata,senderDeviceGeneration}' is distinct from v_sender_generation::text
     or (v_account_conversation is not null and
         p_envelope #>> '{metadata,conversationId}' is distinct from v_account_conversation::text)
  then
    raise exception using errcode = '22023', message = 'secure_envelope_metadata_mismatch';
  end if;

  if exists (
    select 1 from public.chat_device_credentials c
    where c.revoked_at is null
      and private.can_access_security_epoch(p_security_epoch, c.user_id)
      and not exists (
        select 1 from jsonb_array_elements(p_envelope->'recipientKeys') k
        where k->>'deviceId' = c.device_id::text
          and k->>'generation' = c.crypto_generation::text
          and nullif(k->>'wrappedKey', '') is not null
      )
  ) or exists (
    select 1 from jsonb_array_elements(p_envelope->'recipientKeys') k
    where not exists (
      select 1 from public.chat_device_credentials c
      where c.device_id::text = k->>'deviceId'
        and c.crypto_generation::text = k->>'generation'
        and c.revoked_at is null
        and private.can_access_security_epoch(p_security_epoch, c.user_id)
    )
  ) then
    raise exception using errcode = '22023', message = 'secure_envelope_recipient_set_mismatch';
  end if;

  -- Personal DM request/block policy is still server-authoritative.
  if exists (
    select 1 from public.chat_security_epochs e
    join public.direct_conversations c on c.id = e.account_conversation_id
    where e.id = p_security_epoch and c.kind = 'dm'
  ) and not exists (
    select 1 from public.chat_security_epochs e
    where e.id = p_security_epoch
      and private.can_send_direct_message(e.account_conversation_id, auth.uid())
  ) then
    raise exception using errcode = '42501', message = 'request_policy_denied';
  end if;

  select undelivered_relay_max into v_expiry
  from public.chat_service_capabilities where singleton;

  insert into public.chat_e2ee_relay_messages (
    client_sync_id, security_epoch_id, sender_id, sender_device_id,
    sender_crypto_generation, envelope_version, envelope, expires_at
  ) values (
    p_client_sync_id, p_security_epoch, auth.uid(), p_sender_device,
    v_sender_generation, p_envelope_version, p_envelope, now() + v_expiry
  )
  on conflict (security_epoch_id, client_sync_id) do update
  set client_sync_id = excluded.client_sync_id
  where public.chat_e2ee_relay_messages.sender_id = auth.uid()
  returning id into v_relay;

  if v_relay is null then
    raise exception using errcode = '42501', message = 'idempotency_owner_mismatch';
  end if;

  insert into public.chat_e2ee_relay_recipients (
    relay_id, recipient_id, recipient_device_id, recipient_crypto_generation
  )
  select v_relay, c.user_id, c.device_id, c.crypto_generation
  from public.chat_device_credentials c
  where c.revoked_at is null
    and private.can_access_security_epoch(p_security_epoch, c.user_id)
  on conflict do nothing;

  if p_envelope #>> '{metadata,contentType}' = 'delete_everyone' then
    insert into public.chat_object_tombstones (
      object_id, security_epoch_id, reason, created_by, destructive_at
    )
    select original.client_sync_id, p_security_epoch, 'delete_everyone', auth.uid(), now()
    from public.chat_e2ee_relay_messages original
    where original.security_epoch_id = p_security_epoch
      and original.sender_id = auth.uid()
      and original.client_sync_id::text = p_envelope #>> '{metadata,targetMessageId}'
      and original.created_at >= now() - interval '1 hour'
    on conflict (object_id) do nothing;
    if not found then
      raise exception using errcode = '42501', message = 'unsend_not_allowed';
    end if;
    update public.chat_e2ee_relay_messages original
    set envelope = null, state = 'revoked', purged_at = now()
    where original.security_epoch_id = p_security_epoch
      and original.sender_id = auth.uid()
      and original.client_sync_id::text = p_envelope #>> '{metadata,targetMessageId}';
  end if;

  return v_relay;
end;
$$;

revoke all on function public.send_e2ee_relay_message(uuid, uuid, uuid, integer, jsonb) from public, anon;
grant execute on function public.send_e2ee_relay_message(uuid, uuid, uuid, integer, jsonb) to authenticated;

create or replace function public.ack_e2ee_relay_message(
  p_relay uuid,
  p_device uuid,
  p_crypto_generation integer,
  p_read boolean default false
) returns void
language plpgsql security definer set search_path = '' as $$
declare
  v_grace interval;
begin
  if auth.uid() is null or not exists (
    select 1 from public.chat_device_credentials c
    where c.device_id = p_device and c.user_id = auth.uid() and c.revoked_at is null
  ) then
    raise exception using errcode = '42501', message = 'authorized_device_required';
  end if;

  update public.chat_e2ee_relay_recipients
  set delivered_at = coalesce(delivered_at, now()),
      persisted_at = coalesce(persisted_at, now()),
      read_at = case when p_read then coalesce(read_at, now()) else read_at end
  where relay_id = p_relay
    and recipient_id = auth.uid()
    and recipient_device_id = p_device
    and recipient_crypto_generation = p_crypto_generation;
  if not found then
    raise exception using errcode = 'P0002', message = 'relay_recipient_not_found';
  end if;

  if not exists (
    select 1 from public.chat_e2ee_relay_recipients r
    join public.chat_e2ee_relay_messages m on m.id = r.relay_id
    where r.relay_id = p_relay
      and r.recipient_id <> m.sender_id
      and r.persisted_at is null
  ) then
    select delivered_relay_grace into v_grace
    from public.chat_service_capabilities where singleton;
    update public.chat_e2ee_relay_messages
    set delivered_grace_until = coalesce(delivered_grace_until, now() + v_grace)
    where id = p_relay and state = 'active';
  end if;
end;
$$;

revoke all on function public.ack_e2ee_relay_message(uuid, uuid, integer, boolean) from public, anon;
grant execute on function public.ack_e2ee_relay_message(uuid, uuid, integer, boolean) to authenticated;

create or replace function public.pending_e2ee_relay_messages(
  p_device uuid,
  p_crypto_generation integer,
  p_after timestamptz default null,
  p_limit integer default 250
) returns table (
  relay_id uuid,
  client_sync_id uuid,
  security_epoch_id uuid,
  sender_id uuid,
  sender_device_id uuid,
  sender_crypto_generation integer,
  sender_hpke_public_keyset text,
  sender_signing_public_keyset text,
  envelope jsonb,
  created_at timestamptz
)
language plpgsql stable security definer set search_path = '' as $$
begin
  if auth.uid() is null or not exists (
    select 1 from public.chat_device_credentials c
    where c.device_id = p_device
      and c.user_id = auth.uid()
      and c.crypto_generation = p_crypto_generation
      and c.revoked_at is null
  ) then
    raise exception using errcode = '42501', message = 'authorized_device_required';
  end if;

  return query
  select m.id, m.client_sync_id, m.security_epoch_id, m.sender_id,
         m.sender_device_id, m.sender_crypto_generation,
         h.hpke_public_keyset, h.signing_public_keyset,
         m.envelope, m.created_at
  from public.chat_e2ee_relay_recipients r
  join public.chat_e2ee_relay_messages m on m.id = r.relay_id
  join public.chat_device_key_history h
    on h.device_id = m.sender_device_id
   and h.crypto_generation = m.sender_crypto_generation
  where r.recipient_id = auth.uid()
    and r.recipient_device_id = p_device
    and r.recipient_crypto_generation = p_crypto_generation
    and r.persisted_at is null
    and m.state = 'active'
    and m.envelope is not null
    and (p_after is null or m.created_at >= p_after)
  order by m.created_at, m.id
  limit greatest(1, least(p_limit, 500));
end;
$$;

revoke all on function public.pending_e2ee_relay_messages(uuid, integer, timestamptz, integer)
  from public, anon;
grant execute on function public.pending_e2ee_relay_messages(uuid, integer, timestamptz, integer)
  to authenticated;

create table public.chat_object_tombstones (
  object_id uuid primary key,
  security_epoch_id uuid not null references public.chat_security_epochs(id),
  reason text not null check (reason in ('delete_everyone', 'expired', 'revoked')),
  created_by uuid not null references public.profiles(id),
  destructive_at timestamptz not null,
  created_at timestamptz not null default now()
);

alter table public.chat_object_tombstones enable row level security;
create policy chat_object_tombstones_participant_read
  on public.chat_object_tombstones for select to authenticated
  using (private.can_access_security_epoch(security_epoch_id, (select auth.uid())));
revoke all on public.chat_object_tombstones from anon, authenticated;
grant select on public.chat_object_tombstones to authenticated;

create table public.chat_recovery_requests (
  id uuid primary key default gen_random_uuid(),
  object_id uuid not null,
  security_epoch_id uuid not null references public.chat_security_epochs(id),
  requester_id uuid not null references public.profiles(id),
  requester_device_id uuid not null references public.chat_device_credentials(device_id),
  status text not null default 'pending'
    check (status in ('pending', 'fulfilled', 'unavailable', 'cancelled')),
  fulfilled_relay_id uuid references public.chat_e2ee_relay_messages(id),
  created_at timestamptz not null default now(),
  expires_at timestamptz not null default now() + interval '24 hours',
  resolved_at timestamptz
);

create index chat_recovery_requests_epoch_pending_idx
  on public.chat_recovery_requests(security_epoch_id, created_at)
  where status = 'pending';
alter table public.chat_recovery_requests enable row level security;
create policy chat_recovery_requests_participant_read
  on public.chat_recovery_requests for select to authenticated
  using (private.can_access_security_epoch(security_epoch_id, (select auth.uid())));
revoke all on public.chat_recovery_requests from anon, authenticated;
grant select on public.chat_recovery_requests to authenticated;

create or replace function public.request_encrypted_recovery(
  p_object uuid,
  p_security_epoch uuid,
  p_requester_device uuid
) returns uuid
language plpgsql security definer set search_path = '' as $$
declare
  v_request uuid;
begin
  if auth.uid() is null
     or not private.can_access_security_epoch(p_security_epoch, auth.uid())
     or not exists (
       select 1 from public.chat_device_credentials c
       where c.device_id = p_requester_device
         and c.user_id = auth.uid()
         and c.revoked_at is null
     ) then
    raise exception using errcode = '42501', message = 'recovery_not_authorized';
  end if;
  if exists (
    select 1 from public.chat_object_tombstones t
    where t.object_id = p_object and t.destructive_at <= now()
  ) then
    raise exception using errcode = '42501', message = 'content_no_longer_recoverable';
  end if;

  insert into public.chat_recovery_requests (
    object_id, security_epoch_id, requester_id, requester_device_id
  ) values (p_object, p_security_epoch, auth.uid(), p_requester_device)
  returning id into v_request;
  return v_request;
end;
$$;

revoke all on function public.request_encrypted_recovery(uuid, uuid, uuid) from public, anon;
grant execute on function public.request_encrypted_recovery(uuid, uuid, uuid) to authenticated;

create or replace function public.fulfill_encrypted_recovery(
  p_request uuid,
  p_sender_device uuid,
  p_client_sync_id uuid,
  p_envelope jsonb
) returns uuid
language plpgsql security definer set search_path = '' as $$
declare
  v_request public.chat_recovery_requests%rowtype;
  v_relay uuid;
begin
  if auth.uid() is null or not exists (
    select 1 from public.chat_device_credentials c
    where c.device_id = p_sender_device and c.user_id = auth.uid() and c.revoked_at is null
  ) then
    raise exception using errcode = '42501', message = 'authorized_device_required';
  end if;
  select * into v_request from public.chat_recovery_requests r
  where r.id = p_request and r.status = 'pending' and r.expires_at > now()
  for update;
  if v_request.id is null
     or not private.can_access_security_epoch(v_request.security_epoch_id, auth.uid())
     or exists (
       select 1 from public.chat_object_tombstones t
       where t.object_id = v_request.object_id and t.destructive_at <= now()
     ) then
    raise exception using errcode = '42501', message = 'recovery_not_authorized';
  end if;

  v_relay := public.send_e2ee_relay_message(
    p_client_sync_id,
    v_request.security_epoch_id,
    p_sender_device,
    1,
    p_envelope
  );
  update public.chat_recovery_requests
  set status = 'fulfilled', fulfilled_relay_id = v_relay, resolved_at = now()
  where id = p_request;
  return v_relay;
end;
$$;

revoke all on function public.fulfill_encrypted_recovery(uuid, uuid, uuid, jsonb) from public, anon;
grant execute on function public.fulfill_encrypted_recovery(uuid, uuid, uuid, jsonb) to authenticated;

create or replace function private.purge_expired_chat_relay()
returns integer
language plpgsql security definer set search_path = '' as $$
declare
  v_count integer;
begin
  with purged as (
    update public.chat_e2ee_relay_messages m
    set envelope = null,
        state = case
          when m.delivered_grace_until is null then 'expired'::public.chat_relay_state
          else 'purged'::public.chat_relay_state
        end,
        purged_at = now()
    where m.state = 'active'
      and coalesce(m.delivered_grace_until, m.expires_at) <= now()
    returning 1
  ) select count(*) into v_count from purged;

  update public.chat_recovery_requests
  set status = 'unavailable', resolved_at = now()
  where status = 'pending' and expires_at <= now();
  return v_count;
end;
$$;

revoke all on function private.purge_expired_chat_relay() from public, anon, authenticated;

create extension if not exists pg_cron with schema pg_catalog;
select cron.schedule(
  'norfold-chat-relay-purge',
  '*/15 * * * *',
  'select private.purge_expired_chat_relay()'
);

do $$
begin
  alter publication supabase_realtime add table public.chat_e2ee_relay_messages;
exception when duplicate_object then null;
end;
$$;

do $$
begin
  alter publication supabase_realtime add table public.chat_e2ee_relay_recipients;
exception when duplicate_object then null;
end;
$$;
