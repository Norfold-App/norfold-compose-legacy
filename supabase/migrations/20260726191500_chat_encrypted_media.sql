-- Opaque encrypted Chat media. Storage sees ciphertext and routing metadata;
-- filenames, MIME types, and media keys live only inside the message payload.

insert into storage.buckets (id, name, public, file_size_limit)
values ('chat-media', 'chat-media', false, 27262976)
on conflict (id) do update
set public = false, file_size_limit = 27262976;

create table public.chat_media_objects (
  id uuid primary key default gen_random_uuid(),
  uploader_id uuid not null references public.profiles(id) on delete cascade,
  security_epoch_id uuid not null references public.chat_security_epochs(id) on delete cascade,
  message_sync_id uuid not null,
  storage_path text not null unique,
  media_category text not null check (media_category in ('image', 'video', 'audio', 'document', 'other')),
  plaintext_bytes bigint not null check (plaintext_bytes > 0),
  ciphertext_bytes bigint not null check (ciphertext_bytes > plaintext_bytes),
  ciphertext_sha256 text check (ciphertext_sha256 ~ '^[0-9a-f]{64}$'),
  state text not null default 'reserved' check (state in ('reserved', 'ready', 'deleted')),
  created_at timestamptz not null default now(),
  ready_at timestamptz,
  content_expires_at timestamptz,
  deleted_at timestamptz,
  unique (security_epoch_id, message_sync_id)
);

create index chat_media_uploader_window_idx
  on public.chat_media_objects(uploader_id, created_at desc)
  where state <> 'deleted';
create index chat_media_epoch_idx
  on public.chat_media_objects(security_epoch_id, created_at desc)
  where state = 'ready';
create index chat_media_expiry_idx
  on public.chat_media_objects(content_expires_at)
  where state = 'ready' and content_expires_at is not null;

alter table public.chat_media_objects enable row level security;
create policy chat_media_participant_read
  on public.chat_media_objects for select to authenticated
  using (private.can_access_security_epoch(security_epoch_id, (select auth.uid())));
revoke all on public.chat_media_objects from public, anon, authenticated;
grant select on public.chat_media_objects to authenticated;

create or replace function public.reserve_chat_media_upload(
  p_security_epoch uuid,
  p_message_sync_id uuid,
  p_plaintext_bytes bigint,
  p_ciphertext_bytes bigint,
  p_media_category text,
  p_content_expires_at timestamptz default null
) returns table (media_id uuid, storage_path text)
language plpgsql security definer set search_path = '' as $$
declare
  v_cap public.chat_service_capabilities%rowtype;
  v_existing public.chat_media_objects%rowtype;
  v_id uuid := gen_random_uuid();
  v_count integer;
  v_bytes bigint;
begin
  if auth.uid() is null then
    raise exception using errcode = '42501', message = 'authentication_required';
  end if;
  if not private.can_access_security_epoch(p_security_epoch, auth.uid()) then
    raise exception using errcode = '42501', message = 'security_epoch_access_required';
  end if;
  if p_media_category not in ('image', 'video', 'audio', 'document', 'other')
     or p_plaintext_bytes <= 0 or p_ciphertext_bytes <= p_plaintext_bytes then
    raise exception using errcode = '22023', message = 'invalid_media_reservation';
  end if;

  select * into v_existing
  from public.chat_media_objects m
  where m.security_epoch_id = p_security_epoch
    and m.message_sync_id = p_message_sync_id;
  if v_existing.id is not null then
    if v_existing.uploader_id <> auth.uid()
       or v_existing.plaintext_bytes <> p_plaintext_bytes
       or v_existing.ciphertext_bytes <> p_ciphertext_bytes then
      raise exception using errcode = '23505', message = 'media_reservation_conflict';
    end if;
    return query select v_existing.id, v_existing.storage_path;
    return;
  end if;

  select * into v_cap from public.chat_service_capabilities where singleton;
  if v_cap.service_mode = 'degraded' then
    raise exception using errcode = '55000', message = 'chat_media_temporarily_unavailable';
  end if;
  if p_plaintext_bytes > v_cap.max_single_file_bytes then
    raise exception using errcode = '54000', message = 'chat_file_too_large';
  end if;
  if p_ciphertext_bytes > v_cap.max_single_file_bytes + 1048576 then
    raise exception using errcode = '54000', message = 'chat_ciphertext_too_large';
  end if;
  if p_content_expires_at is not null and p_content_expires_at <= now() + interval '1 minute' then
    raise exception using errcode = '22023', message = 'media_expiry_too_soon';
  end if;

  select count(*), coalesce(sum(m.plaintext_bytes), 0)
    into v_count, v_bytes
  from public.chat_media_objects m
  where m.uploader_id = auth.uid()
    and m.created_at >= now() - interval '24 hours'
    and m.state <> 'deleted';
  if v_count >= v_cap.files_per_rolling_24h then
    raise exception using errcode = '54000', message = 'chat_daily_file_count_limit';
  end if;
  if v_bytes + p_plaintext_bytes > v_cap.upload_bytes_per_rolling_24h then
    raise exception using errcode = '54000', message = 'chat_daily_upload_bytes_limit';
  end if;

  insert into public.chat_media_objects (
    id, uploader_id, security_epoch_id, message_sync_id, storage_path,
    media_category, plaintext_bytes, ciphertext_bytes, content_expires_at
  ) values (
    v_id, auth.uid(), p_security_epoch, p_message_sync_id,
    p_security_epoch::text || '/' || p_message_sync_id::text || '/' || v_id::text || '.nfmedia',
    p_media_category, p_plaintext_bytes, p_ciphertext_bytes, p_content_expires_at
  ) returning chat_media_objects.id, chat_media_objects.storage_path
    into media_id, storage_path;
  return next;
end;
$$;

revoke all on function public.reserve_chat_media_upload(uuid, uuid, bigint, bigint, text, timestamptz)
  from public, anon;
grant execute on function public.reserve_chat_media_upload(uuid, uuid, bigint, bigint, text, timestamptz)
  to authenticated;

create or replace function public.finalize_chat_media_upload(
  p_media uuid,
  p_ciphertext_sha256 text
) returns void
language plpgsql security definer set search_path = '' as $$
declare
  v_media public.chat_media_objects%rowtype;
begin
  select * into v_media from public.chat_media_objects where id = p_media for update;
  if v_media.id is null or v_media.uploader_id <> auth.uid() then
    raise exception using errcode = '42501', message = 'media_owner_required';
  end if;
  if p_ciphertext_sha256 !~ '^[0-9a-f]{64}$' then
    raise exception using errcode = '22023', message = 'invalid_ciphertext_digest';
  end if;
  if not exists (
    select 1 from storage.objects o
    where o.bucket_id = 'chat-media' and o.name = v_media.storage_path
  ) then
    raise exception using errcode = 'P0002', message = 'media_ciphertext_not_uploaded';
  end if;
  update public.chat_media_objects
  set state = 'ready', ciphertext_sha256 = p_ciphertext_sha256, ready_at = now()
  where id = p_media.id;
end;
$$;

revoke all on function public.finalize_chat_media_upload(uuid, text) from public, anon;
grant execute on function public.finalize_chat_media_upload(uuid, text) to authenticated;

create or replace function public.chat_media_download_path(p_media uuid)
returns text
language plpgsql stable security definer set search_path = '' as $$
declare
  v_path text;
begin
  select m.storage_path into v_path
  from public.chat_media_objects m
  where m.id = p_media
    and m.state = 'ready'
    and (m.content_expires_at is null or m.content_expires_at > now())
    and private.can_access_security_epoch(m.security_epoch_id, auth.uid());
  if v_path is null then
    raise exception using errcode = '42501', message = 'media_access_required';
  end if;
  return v_path;
end;
$$;

revoke all on function public.chat_media_download_path(uuid) from public, anon;
grant execute on function public.chat_media_download_path(uuid) to authenticated;

create policy chat_media_ciphertext_insert
  on storage.objects for insert to authenticated
  with check (
    bucket_id = 'chat-media'
    and exists (
      select 1 from public.chat_media_objects m
      where m.storage_path = name
        and m.uploader_id = (select auth.uid())
        and m.state = 'reserved'
    )
  );
create policy chat_media_ciphertext_update
  on storage.objects for update to authenticated
  using (
    bucket_id = 'chat-media'
    and exists (
      select 1 from public.chat_media_objects m
      where m.storage_path = name and m.uploader_id = (select auth.uid())
    )
  ) with check (bucket_id = 'chat-media');
create policy chat_media_ciphertext_read
  on storage.objects for select to authenticated
  using (
    bucket_id = 'chat-media'
    and exists (
      select 1 from public.chat_media_objects m
      where m.storage_path = name
        and m.state = 'ready'
        and (m.content_expires_at is null or m.content_expires_at > now())
        and private.can_access_security_epoch(m.security_epoch_id, (select auth.uid()))
    )
  );

create or replace function private.expire_chat_media_objects()
returns integer
language plpgsql security definer set search_path = '' as $$
declare
  v_count integer;
begin
  with expired as (
    update public.chat_media_objects m
    set state = 'deleted', deleted_at = now()
    where m.state <> 'deleted'
      and (
        (m.content_expires_at is not null and m.content_expires_at <= now())
        or (m.state = 'reserved' and m.created_at <= now() - interval '1 hour')
      )
    returning 1
  ) select count(*) into v_count from expired;
  return v_count;
end;
$$;

revoke all on function private.expire_chat_media_objects()
  from public, anon, authenticated;

-- Media messages carry the same immutable sender lifespan as text messages.
create or replace function private.validate_chat_relay_lifespan()
returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  v_raw text := new.envelope #>> '{metadata,lifespanJson}';
  v_expiry timestamptz;
begin
  new.content_expires_at := null;
  if new.envelope #>> '{metadata,contentType}' not in ('text', 'media') or v_raw is null then
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

create or replace function private.retire_chat_media_for_tombstone()
returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  update public.chat_media_objects
  set state = 'deleted', deleted_at = coalesce(new.destructive_at, now())
  where security_epoch_id = new.security_epoch_id
    and message_sync_id = new.object_id
    and state <> 'deleted';
  return new;
end;
$$;

revoke all on function private.retire_chat_media_for_tombstone()
  from public, anon, authenticated;
drop trigger if exists retire_chat_media_for_tombstone
  on public.chat_object_tombstones;
create trigger retire_chat_media_for_tombstone
after insert or update of destructive_at on public.chat_object_tombstones
for each row execute function private.retire_chat_media_for_tombstone();
