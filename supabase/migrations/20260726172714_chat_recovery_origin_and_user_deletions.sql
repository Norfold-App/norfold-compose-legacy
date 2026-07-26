-- Origin-authenticated relay recovery and account-owned "delete from my view" state.
-- Plaintext and origin proofs remain inside ciphertext; Postgres only binds recovery routing
-- to immutable relay/device metadata and prevents hidden or destructive objects resurfacing.

alter table public.chat_e2ee_relay_messages
  add column if not exists origin_relay_id uuid
    references public.chat_e2ee_relay_messages(id),
  add column if not exists recovery_request_id uuid
    references public.chat_recovery_requests(id);

create unique index if not exists chat_e2ee_relay_recovery_request_idx
  on public.chat_e2ee_relay_messages(recovery_request_id)
  where recovery_request_id is not null;

create unique index if not exists chat_recovery_request_device_pending_idx
  on public.chat_recovery_requests(requester_id, requester_device_id, object_id)
  where status = 'pending';

create table if not exists public.chat_user_hidden_objects (
  user_id uuid not null references public.profiles(id) on delete cascade,
  object_id uuid not null,
  security_epoch_id uuid not null references public.chat_security_epochs(id) on delete cascade,
  hidden_at timestamptz not null default now(),
  reason text not null default 'delete_for_me'
    check (reason in ('delete_for_me', 'expired_for_me')),
  created_at timestamptz not null default now(),
  primary key (user_id, object_id)
);

create index if not exists chat_user_hidden_epoch_idx
  on public.chat_user_hidden_objects(user_id, security_epoch_id, hidden_at desc);

alter table public.chat_user_hidden_objects enable row level security;
drop policy if exists chat_user_hidden_owner_read on public.chat_user_hidden_objects;
create policy chat_user_hidden_owner_read
  on public.chat_user_hidden_objects for select to authenticated
  using (user_id = (select auth.uid()));

revoke all on public.chat_user_hidden_objects from public, anon, authenticated;
grant select on public.chat_user_hidden_objects to authenticated;

create or replace function public.hide_chat_object_for_me(
  p_object uuid,
  p_security_epoch uuid,
  p_hidden_at_millis bigint default null
) returns void
language plpgsql security definer set search_path = '' as $$
declare
  v_hidden_at timestamptz := case
    when p_hidden_at_millis is null then now()
    else to_timestamp(p_hidden_at_millis::double precision / 1000.0)
  end;
begin
  if auth.uid() is null
     or not private.can_access_security_epoch(p_security_epoch, auth.uid())
     or not exists (
       select 1 from public.chat_e2ee_relay_messages m
       where m.client_sync_id = p_object
         and m.security_epoch_id = p_security_epoch
     ) then
    raise exception using errcode = '42501', message = 'chat_object_hide_not_authorized';
  end if;
  if v_hidden_at > now() + interval '5 minutes' then
    raise exception using errcode = '22023', message = 'invalid_hidden_timestamp';
  end if;
  insert into public.chat_user_hidden_objects (
    user_id, object_id, security_epoch_id, hidden_at, reason
  ) values (
    auth.uid(), p_object, p_security_epoch, v_hidden_at, 'delete_for_me'
  )
  on conflict (user_id, object_id) do update
  set security_epoch_id = excluded.security_epoch_id,
      hidden_at = greatest(public.chat_user_hidden_objects.hidden_at, excluded.hidden_at),
      reason = excluded.reason;
end;
$$;

revoke all on function public.hide_chat_object_for_me(uuid, uuid, bigint)
  from public, anon;
grant execute on function public.hide_chat_object_for_me(uuid, uuid, bigint)
  to authenticated;

create or replace function public.unhide_chat_object_for_me(p_object uuid)
returns void
language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then
    raise exception using errcode = '42501', message = 'authentication_required';
  end if;
  delete from public.chat_user_hidden_objects h
  where h.user_id = auth.uid() and h.object_id = p_object;
end;
$$;

revoke all on function public.unhide_chat_object_for_me(uuid) from public, anon;
grant execute on function public.unhide_chat_object_for_me(uuid) to authenticated;

create or replace function public.hidden_chat_objects_for_me()
returns table (
  object_id uuid,
  security_epoch_id uuid,
  conversation_id text,
  hidden_at timestamptz,
  reason text
)
language sql stable security definer set search_path = '' as $$
  select h.object_id, h.security_epoch_id,
         coalesce(e.account_conversation_id, e.channel_id, e.workspace_id)::text,
         h.hidden_at, h.reason
  from public.chat_user_hidden_objects h
  join public.chat_security_epochs e on e.id = h.security_epoch_id
  where h.user_id = auth.uid()
  order by h.hidden_at, h.object_id;
$$;

revoke all on function public.hidden_chat_objects_for_me() from public, anon;
grant execute on function public.hidden_chat_objects_for_me() to authenticated;

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
     )
     or not exists (
       select 1 from public.chat_e2ee_relay_messages m
       where m.client_sync_id = p_object
         and m.security_epoch_id = p_security_epoch
         and m.state in ('purged', 'expired')
     ) then
    raise exception using errcode = '42501', message = 'recovery_not_authorized';
  end if;
  if exists (
    select 1 from public.chat_object_tombstones t where t.object_id = p_object
  ) or exists (
    select 1 from public.chat_user_hidden_objects h
    where h.user_id = auth.uid() and h.object_id = p_object
  ) then
    raise exception using errcode = '42501', message = 'content_no_longer_recoverable';
  end if;

  select r.id into v_request
  from public.chat_recovery_requests r
  where r.requester_id = auth.uid()
    and r.requester_device_id = p_requester_device
    and r.object_id = p_object
    and r.status = 'pending'
    and r.expires_at > now()
  order by r.created_at desc limit 1;
  if v_request is not null then return v_request; end if;

  update public.chat_recovery_requests
  set status = 'unavailable', resolved_at = now()
  where requester_id = auth.uid()
    and requester_device_id = p_requester_device
    and object_id = p_object
    and status = 'pending';

  insert into public.chat_recovery_requests (
    object_id, security_epoch_id, requester_id, requester_device_id
  ) values (p_object, p_security_epoch, auth.uid(), p_requester_device)
  returning id into v_request;
  return v_request;
end;
$$;

revoke all on function public.request_encrypted_recovery(uuid, uuid, uuid)
  from public, anon;
grant execute on function public.request_encrypted_recovery(uuid, uuid, uuid)
  to authenticated;

create or replace function public.pending_encrypted_recovery_requests(
  p_device uuid,
  p_limit integer default 100
) returns table (
  request_id uuid,
  object_id uuid,
  security_epoch_id uuid,
  requester_id uuid,
  requester_device_id uuid,
  requester_crypto_generation integer,
  requester_hpke_public_keyset text,
  created_at timestamptz,
  expires_at timestamptz
)
language plpgsql stable security definer set search_path = '' as $$
begin
  if auth.uid() is null or not exists (
    select 1 from public.chat_device_credentials holder
    where holder.device_id = p_device
      and holder.user_id = auth.uid()
      and holder.revoked_at is null
      and holder.archive_capable
  ) then
    raise exception using errcode = '42501', message = 'archive_capable_device_required';
  end if;
  return query
  select r.id, r.object_id, r.security_epoch_id, r.requester_id,
         r.requester_device_id, requester.crypto_generation,
         requester.hpke_public_keyset, r.created_at, r.expires_at
  from public.chat_recovery_requests r
  join public.chat_device_credentials requester
    on requester.device_id = r.requester_device_id
   and requester.user_id = r.requester_id
   and requester.revoked_at is null
  where r.status = 'pending'
    and r.expires_at > now()
    and r.requester_device_id <> p_device
    and private.can_access_security_epoch(r.security_epoch_id, auth.uid())
    and not exists (
      select 1 from public.chat_object_tombstones t where t.object_id = r.object_id
    )
  order by r.created_at, r.id
  limit greatest(1, least(p_limit, 250));
end;
$$;

revoke all on function public.pending_encrypted_recovery_requests(uuid, integer)
  from public, anon;
grant execute on function public.pending_encrypted_recovery_requests(uuid, integer)
  to authenticated;

create or replace function public.fulfill_encrypted_recovery(
  p_request uuid,
  p_sender_device uuid,
  p_client_sync_id uuid,
  p_envelope jsonb
) returns uuid
language plpgsql security definer set search_path = '' as $$
declare
  v_request public.chat_recovery_requests%rowtype;
  v_original public.chat_e2ee_relay_messages%rowtype;
  v_relay uuid;
begin
  if auth.uid() is null or not exists (
    select 1 from public.chat_device_credentials c
    where c.device_id = p_sender_device
      and c.user_id = auth.uid()
      and c.revoked_at is null
      and c.archive_capable
  ) then
    raise exception using errcode = '42501', message = 'archive_capable_device_required';
  end if;
  select * into v_request from public.chat_recovery_requests r
  where r.id = p_request and r.status = 'pending' and r.expires_at > now()
  for update;
  if v_request.id is null
     or v_request.requester_device_id = p_sender_device
     or not private.can_access_security_epoch(v_request.security_epoch_id, auth.uid())
     or exists (
       select 1 from public.chat_object_tombstones t where t.object_id = v_request.object_id
     ) then
    raise exception using errcode = '42501', message = 'recovery_not_authorized';
  end if;
  select * into v_original from public.chat_e2ee_relay_messages m
  where m.client_sync_id = v_request.object_id
    and m.security_epoch_id = v_request.security_epoch_id
  order by m.created_at limit 1;
  if v_original.id is null
     or p_client_sync_id = v_request.object_id
     or p_envelope #>> '{metadata,contentType}' is distinct from 'recovery'
     or p_envelope #>> '{metadata,targetMessageId}' is distinct from v_request.object_id::text
     or p_envelope #>> '{metadata,securityEpochId}' is distinct from v_request.security_epoch_id::text
  then
    raise exception using errcode = '22023', message = 'recovery_envelope_metadata_mismatch';
  end if;

  v_relay := public.send_e2ee_relay_message(
    p_client_sync_id,
    v_request.security_epoch_id,
    p_sender_device,
    1,
    p_envelope
  );
  update public.chat_e2ee_relay_messages
  set origin_relay_id = v_original.id,
      recovery_request_id = v_request.id
  where id = v_relay;
  update public.chat_recovery_requests
  set status = 'fulfilled', fulfilled_relay_id = v_relay, resolved_at = now()
  where id = v_request.id;
  return v_relay;
end;
$$;

revoke all on function public.fulfill_encrypted_recovery(uuid, uuid, uuid, jsonb)
  from public, anon;
grant execute on function public.fulfill_encrypted_recovery(uuid, uuid, uuid, jsonb)
  to authenticated;

drop function if exists public.pending_e2ee_relay_messages(uuid, integer, timestamptz, integer);
create function public.pending_e2ee_relay_messages(
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
  origin_client_sync_id uuid,
  origin_sender_id uuid,
  origin_sender_device_id uuid,
  origin_sender_crypto_generation integer,
  origin_sender_hpke_public_keyset text,
  origin_sender_signing_public_keyset text,
  recovery_request_id uuid,
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
         outer_key.hpke_public_keyset, outer_key.signing_public_keyset,
         origin.client_sync_id, origin.sender_id, origin.sender_device_id,
         origin.sender_crypto_generation, origin_key.hpke_public_keyset,
         origin_key.signing_public_keyset, m.recovery_request_id,
         m.envelope, m.created_at
  from public.chat_e2ee_relay_recipients r
  join public.chat_e2ee_relay_messages m on m.id = r.relay_id
  join public.chat_device_key_history outer_key
    on outer_key.device_id = m.sender_device_id
   and outer_key.crypto_generation = m.sender_crypto_generation
  join public.chat_e2ee_relay_messages origin
    on origin.id = coalesce(m.origin_relay_id, m.id)
  join public.chat_device_key_history origin_key
    on origin_key.device_id = origin.sender_device_id
   and origin_key.crypto_generation = origin.sender_crypto_generation
  where r.recipient_id = auth.uid()
    and r.recipient_device_id = p_device
    and r.recipient_crypto_generation = p_crypto_generation
    and r.persisted_at is null
    and m.state = 'active'
    and m.envelope is not null
    and (p_after is null or m.created_at >= p_after)
    and not exists (
      select 1 from public.chat_object_tombstones t
      where t.object_id = origin.client_sync_id
    )
    and not exists (
      select 1 from public.chat_user_hidden_objects hidden
      where hidden.user_id = auth.uid()
        and hidden.object_id = origin.client_sync_id
    )
  order by m.created_at, m.id
  limit greatest(1, least(p_limit, 500));
end;
$$;

revoke all on function public.pending_e2ee_relay_messages(uuid, integer, timestamptz, integer)
  from public, anon;
grant execute on function public.pending_e2ee_relay_messages(uuid, integer, timestamptz, integer)
  to authenticated;

create or replace function public.recoverable_chat_objects(p_limit integer default 250)
returns table (
  object_id uuid,
  security_epoch_id uuid,
  created_at timestamptz
)
language sql stable security definer set search_path = '' as $$
  select m.client_sync_id, m.security_epoch_id, m.created_at
  from public.chat_e2ee_relay_messages m
  where auth.uid() is not null
    and m.origin_relay_id is null
    and m.state in ('purged', 'expired')
    and private.can_access_security_epoch(m.security_epoch_id, auth.uid())
    and not exists (
      select 1 from public.chat_object_tombstones t where t.object_id = m.client_sync_id
    )
    and not exists (
      select 1 from public.chat_user_hidden_objects h
      where h.user_id = auth.uid() and h.object_id = m.client_sync_id
    )
  order by m.created_at desc, m.id desc
  limit greatest(1, least(p_limit, 500));
$$;

revoke all on function public.recoverable_chat_objects(integer) from public, anon;
grant execute on function public.recoverable_chat_objects(integer) to authenticated;

do $$
begin
  alter publication supabase_realtime add table public.chat_user_hidden_objects;
exception when duplicate_object then null;
end;
$$;

notify pgrst, 'reload schema';
