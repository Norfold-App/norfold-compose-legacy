-- Sender-authored disappearing messages. Content expiry is distinct from opaque-relay
-- transport retention: expiry creates an irreversible tombstone, while routine relay purge
-- remains recoverable from an authorized encrypted archive.

alter table public.chat_e2ee_relay_messages
  add column if not exists content_expires_at timestamptz;

create index if not exists chat_e2ee_relay_content_expiry_idx
  on public.chat_e2ee_relay_messages(content_expires_at)
  where state = 'active' and content_expires_at is not null;

create or replace function private.validate_chat_relay_lifespan()
returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  v_raw text := new.envelope #>> '{metadata,lifespanJson}';
  v_expiry timestamptz;
begin
  new.content_expires_at := null;
  if new.envelope #>> '{metadata,contentType}' <> 'text' or v_raw is null then
    return new;
  end if;
  if v_raw !~ '^[0-9]{10,13}$' then
    raise exception using errcode = '22023', message = 'invalid_message_lifespan';
  end if;
  v_expiry := to_timestamp(v_raw::double precision / 1000.0);
  if v_expiry < now() + interval '1 minute'
     or v_expiry > now() + interval '365 days' then
    raise exception using errcode = '22023', message = 'message_lifespan_out_of_range';
  end if;
  new.content_expires_at := v_expiry;
  return new;
end;
$$;

revoke all on function private.validate_chat_relay_lifespan()
  from public, anon, authenticated;

drop trigger if exists validate_chat_relay_lifespan
  on public.chat_e2ee_relay_messages;
create trigger validate_chat_relay_lifespan
before insert or update of envelope on public.chat_e2ee_relay_messages
for each row execute function private.validate_chat_relay_lifespan();

create or replace function private.validate_managed_chat_lifespan()
returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  v_raw text;
  v_expiry timestamptz;
begin
  if new.object_type <> 'chat_message' or new.content is null
     or not (new.content ? 'expiresAt') then
    return new;
  end if;
  v_raw := new.content->>'expiresAt';
  if v_raw !~ '^[0-9]{10,13}$' then
    raise exception using errcode = '22023', message = 'invalid_message_lifespan';
  end if;
  v_expiry := to_timestamp(v_raw::double precision / 1000.0);
  if tg_op = 'INSERT' and (
    v_expiry < now() + interval '1 minute'
    or v_expiry > now() + interval '365 days'
  ) then
    raise exception using errcode = '22023', message = 'message_lifespan_out_of_range';
  end if;
  -- Existing messages may be edited, but their original expiry is immutable.
  if tg_op = 'UPDATE'
     and old.content ? 'expiresAt'
     and old.content->>'expiresAt' is distinct from v_raw then
    raise exception using errcode = '22023', message = 'message_lifespan_is_immutable';
  end if;
  return new;
end;
$$;

revoke all on function private.validate_managed_chat_lifespan()
  from public, anon, authenticated;

drop trigger if exists validate_managed_chat_lifespan on public.workspace_objects;
create trigger validate_managed_chat_lifespan
before insert or update of content on public.workspace_objects
for each row execute function private.validate_managed_chat_lifespan();

create or replace function public.chat_tombstones_for_me(p_limit integer default 500)
returns table (
  object_id uuid,
  security_epoch_id uuid,
  conversation_id text,
  destructive_at timestamptz,
  reason text
)
language sql stable security definer set search_path = '' as $$
  select t.object_id, t.security_epoch_id,
         coalesce(e.account_conversation_id, e.channel_id, e.workspace_id)::text,
         t.destructive_at, t.reason
  from public.chat_object_tombstones t
  join public.chat_security_epochs e on e.id = t.security_epoch_id
  where auth.uid() is not null
    and private.can_access_security_epoch(t.security_epoch_id, auth.uid())
  order by t.destructive_at desc, t.object_id
  limit greatest(1, least(p_limit, 2000));
$$;

revoke all on function public.chat_tombstones_for_me(integer) from public, anon;
grant execute on function public.chat_tombstones_for_me(integer) to authenticated;

create or replace function private.purge_expired_chat_relay()
returns integer
language plpgsql security definer set search_path = '' as $$
declare
  v_count integer := 0;
  v_transport_count integer := 0;
  v_managed_count integer := 0;
begin
  insert into public.chat_object_tombstones (
    object_id, security_epoch_id, reason, created_by, destructive_at
  )
  select m.client_sync_id, m.security_epoch_id, 'expired', m.sender_id,
         m.content_expires_at
  from public.chat_e2ee_relay_messages m
  where m.origin_relay_id is null
    and m.state = 'active'
    and m.content_expires_at <= now()
  on conflict (object_id) do nothing;

  with expired as (
    update public.chat_e2ee_relay_messages m
    set envelope = null, state = 'expired', purged_at = now()
    where m.state = 'active' and m.content_expires_at <= now()
    returning 1
  ) select count(*) into v_count from expired;

  -- Managed workspace Chat carries the same absolute expiry in its server-readable content.
  insert into public.chat_object_tombstones (
    object_id, security_epoch_id, reason, created_by, destructive_at
  )
  select o.client_sync_id, (o.content->>'securityEpochId')::uuid,
         'expired', o.created_by,
         to_timestamp((o.content->>'expiresAt')::double precision / 1000.0)
  from public.workspace_objects o
  where o.object_type = 'chat_message'
    and o.deleted_at is null
    and o.content ? 'expiresAt'
    and o.content->>'expiresAt' ~ '^[0-9]{10,13}$'
    and o.content->>'securityEpochId' ~* '^[0-9a-f-]{36}$'
    and to_timestamp((o.content->>'expiresAt')::double precision / 1000.0) <= now()
  on conflict (object_id) do nothing;

  with expired_managed as (
    update public.workspace_objects o
    set deleted_at = now(), updated_at = now(),
        server_version = o.server_version + 1
    where o.object_type = 'chat_message'
      and o.deleted_at is null
      and o.content ? 'expiresAt'
      and o.content->>'expiresAt' ~ '^[0-9]{10,13}$'
      and to_timestamp((o.content->>'expiresAt')::double precision / 1000.0) <= now()
    returning 1
  ) select count(*) into v_managed_count from expired_managed;

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
  ) select count(*) into v_transport_count from purged;

  update public.chat_recovery_requests
  set status = 'unavailable', resolved_at = now()
  where status = 'pending' and expires_at <= now();

  return v_count + v_managed_count + v_transport_count;
end;
$$;

revoke all on function private.purge_expired_chat_relay()
  from public, anon, authenticated;

notify pgrst, 'reload schema';
