create extension if not exists pgcrypto with schema extensions;
create extension if not exists citext with schema extensions;

create type public.workspace_privacy as enum ('personal_encrypted', 'team_managed');
create type public.workspace_role as enum ('owner', 'admin', 'member', 'viewer');
create type public.operation_kind as enum ('upsert', 'delete');
create type public.delivery_channel as enum ('in_app', 'push', 'email');
create type public.integration_provider as enum ('google_calendar', 'outlook_calendar');
create type public.subscription_tier as enum ('free', 'pro', 'team');

create table public.profiles (
  id uuid primary key references auth.users(id) on delete cascade,
  display_name text not null default '',
  avatar_path text,
  locale text not null default 'en',
  time_zone text not null default 'UTC',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table public.workspaces (
  id uuid primary key default gen_random_uuid(),
  name text not null check (char_length(name) between 1 and 80),
  privacy public.workspace_privacy not null,
  created_by uuid not null references public.profiles(id),
  key_version integer not null default 1 check (key_version > 0),
  server_version bigint not null default 1 check (server_version > 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz
);

create table public.workspace_memberships (
  workspace_id uuid not null references public.workspaces(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  role public.workspace_role not null default 'member',
  joined_at timestamptz not null default now(),
  removed_at timestamptz,
  primary key (workspace_id, user_id)
);

create table public.workspace_invitations (
  id uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspaces(id) on delete cascade,
  email extensions.citext not null,
  role public.workspace_role not null default 'member',
  token_hash bytea not null unique,
  invited_by uuid not null references public.profiles(id),
  expires_at timestamptz not null,
  accepted_at timestamptz,
  created_at timestamptz not null default now()
);

create table public.workspace_key_envelopes (
  workspace_id uuid not null references public.workspaces(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  key_version integer not null,
  wrapped_key bytea not null,
  algorithm text not null check (algorithm in ('x25519-xsalsa20-poly1305', 'rsa-oaep-sha256')),
  created_at timestamptz not null default now(),
  primary key (workspace_id, user_id, key_version)
);

create table public.workspace_objects (
  id uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspaces(id) on delete cascade,
  client_sync_id uuid not null,
  object_type text not null,
  server_version bigint not null default 1,
  key_version integer,
  ciphertext bytea,
  nonce bytea,
  content jsonb,
  content_hash text not null,
  created_by uuid not null references public.profiles(id),
  updated_by uuid not null references public.profiles(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  unique (workspace_id, client_sync_id),
  check ((ciphertext is not null and content is null) or (ciphertext is null and content is not null))
);

create index workspace_objects_changed_idx on public.workspace_objects(workspace_id, server_version);
create index workspace_objects_type_idx on public.workspace_objects(workspace_id, object_type) where deleted_at is null;

create table public.object_operations (
  id uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspaces(id) on delete cascade,
  object_id uuid references public.workspace_objects(id) on delete set null,
  idempotency_key uuid not null,
  device_id uuid not null,
  operation public.operation_kind not null,
  base_version bigint,
  result_version bigint not null,
  actor_id uuid not null references public.profiles(id),
  created_at timestamptz not null default now(),
  unique (workspace_id, idempotency_key)
);

create table public.object_comments (
  id uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspaces(id) on delete cascade,
  object_id uuid not null references public.workspace_objects(id) on delete cascade,
  author_id uuid not null references public.profiles(id),
  body text,
  ciphertext bytea,
  nonce bytea,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  deleted_at timestamptz,
  check ((body is not null and ciphertext is null) or (body is null and ciphertext is not null))
);

create table public.activity_records (
  id bigint generated always as identity primary key,
  workspace_id uuid not null references public.workspaces(id) on delete cascade,
  object_id uuid references public.workspace_objects(id) on delete set null,
  actor_id uuid references public.profiles(id) on delete set null,
  action text not null,
  detail jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);

create table public.files (
  id uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspaces(id) on delete cascade,
  object_id uuid references public.workspace_objects(id) on delete set null,
  storage_path text not null unique,
  display_name text not null,
  mime_type text not null,
  size_bytes bigint not null check (size_bytes >= 0),
  content_hash text not null,
  encrypted boolean not null,
  key_version integer,
  created_by uuid not null references public.profiles(id),
  created_at timestamptz not null default now(),
  deleted_at timestamptz
);

create table public.devices (
  id uuid primary key,
  user_id uuid not null references public.profiles(id) on delete cascade,
  name text not null,
  platform text not null,
  last_seen_at timestamptz not null default now(),
  created_at timestamptz not null default now()
);

create table public.push_tokens (
  token_hash bytea primary key,
  device_id uuid not null references public.devices(id) on delete cascade,
  encrypted_token bytea not null,
  token_nonce bytea not null,
  enabled boolean not null default true,
  updated_at timestamptz not null default now()
);

create table public.notification_preferences (
  user_id uuid primary key references public.profiles(id) on delete cascade,
  in_app boolean not null default true,
  push boolean not null default false,
  email boolean not null default false,
  quiet_start time,
  quiet_end time,
  time_zone text not null default 'UTC',
  updated_at timestamptz not null default now()
);

create table public.notification_deliveries (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete cascade,
  workspace_id uuid references public.workspaces(id) on delete cascade,
  channel public.delivery_channel not null,
  title text not null,
  body text not null,
  payload jsonb not null default '{}'::jsonb,
  scheduled_at timestamptz not null,
  delivered_at timestamptz,
  read_at timestamptz,
  failure text,
  created_at timestamptz not null default now()
);

create table public.integration_accounts (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete cascade,
  provider public.integration_provider not null,
  provider_subject text not null,
  encrypted_refresh_token bytea not null,
  token_nonce bytea not null,
  scopes text[] not null default '{}',
  cursor text,
  revoked_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (user_id, provider, provider_subject)
);

create table public.calendar_event_mappings (
  integration_id uuid not null references public.integration_accounts(id) on delete cascade,
  workspace_id uuid not null references public.workspaces(id) on delete cascade,
  object_id uuid not null references public.workspace_objects(id) on delete cascade,
  provider_calendar_id text not null,
  provider_event_id text not null,
  provider_etag text,
  last_pushed_hash text,
  updated_at timestamptz not null default now(),
  primary key (integration_id, provider_calendar_id, provider_event_id),
  unique (integration_id, object_id)
);

create table public.subscriptions (
  user_id uuid primary key references public.profiles(id) on delete cascade,
  tier public.subscription_tier not null default 'free',
  source text not null default 'none',
  external_customer_id text,
  external_subscription_id text,
  current_period_end timestamptz,
  cancel_at_period_end boolean not null default false,
  updated_at timestamptz not null default now()
);

create table public.entitlements (
  user_id uuid not null references public.profiles(id) on delete cascade,
  entitlement text not null,
  enabled boolean not null,
  source text not null,
  expires_at timestamptz,
  updated_at timestamptz not null default now(),
  primary key (user_id, entitlement)
);

create or replace function public.handle_new_user()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  insert into public.profiles (id, display_name)
  values (new.id, coalesce(new.raw_user_meta_data ->> 'full_name', new.email, ''))
  on conflict (id) do nothing;
  insert into public.subscriptions (user_id) values (new.id) on conflict (user_id) do nothing;
  return new;
end;
$$;

create trigger auth_user_profile after insert on auth.users
for each row execute function public.handle_new_user();

create or replace function public.create_workspace(
  workspace_name text,
  privacy_mode public.workspace_privacy
) returns public.workspaces
language plpgsql security definer set search_path = '' as $$
declare created public.workspaces;
begin
  if auth.uid() is null then raise exception 'Authentication required'; end if;
  if char_length(trim(workspace_name)) not between 1 and 80 then raise exception 'Invalid workspace name'; end if;
  insert into public.workspaces (name, privacy, created_by)
  values (trim(workspace_name), privacy_mode, auth.uid()) returning * into created;
  insert into public.workspace_memberships (workspace_id, user_id, role)
  values (created.id, auth.uid(), 'owner');
  return created;
end;
$$;

create or replace function public.apply_workspace_object(
  target_workspace uuid,
  target_sync_id uuid,
  target_type text,
  op public.operation_kind,
  operation_id uuid,
  source_device uuid,
  expected_version bigint,
  body jsonb default null,
  encrypted_body bytea default null,
  encrypted_nonce bytea default null,
  encryption_key_version integer default null,
  body_hash text default ''
) returns public.workspace_objects
language plpgsql security definer set search_path = '' as $$
declare current public.workspace_objects;
declare prior public.object_operations;
declare next_version bigint;
begin
  if not public.is_workspace_member(target_workspace) then raise exception 'Workspace access denied'; end if;
  select * into prior from public.object_operations
    where workspace_id = target_workspace and idempotency_key = operation_id;
  if prior.id is not null then
    select * into current from public.workspace_objects where id = prior.object_id;
    return current;
  end if;

  select * into current from public.workspace_objects
    where workspace_id = target_workspace and client_sync_id = target_sync_id for update;
  if current.id is not null and expected_version is not null and current.server_version <> expected_version then
    raise exception 'VERSION_CONFLICT:%', current.server_version;
  end if;
  next_version := coalesce(current.server_version, 0) + 1;

  if op = 'delete' then
    if current.id is null then raise exception 'Cannot delete a missing object'; end if;
    update public.workspace_objects set
      server_version = next_version, updated_by = auth.uid(), updated_at = now(), deleted_at = now()
      where id = current.id returning * into current;
  elsif current.id is null then
    insert into public.workspace_objects (
      workspace_id, client_sync_id, object_type, server_version, key_version,
      ciphertext, nonce, content, content_hash, created_by, updated_by
    ) values (
      target_workspace, target_sync_id, target_type, next_version, encryption_key_version,
      encrypted_body, encrypted_nonce, body, body_hash, auth.uid(), auth.uid()
    ) returning * into current;
  else
    update public.workspace_objects set
      object_type = target_type, server_version = next_version, key_version = encryption_key_version,
      ciphertext = encrypted_body, nonce = encrypted_nonce, content = body, content_hash = body_hash,
      updated_by = auth.uid(), updated_at = now(), deleted_at = null
      where id = current.id returning * into current;
  end if;

  insert into public.object_operations (
    workspace_id, object_id, idempotency_key, device_id, operation,
    base_version, result_version, actor_id
  ) values (
    target_workspace, current.id, operation_id, source_device, op,
    expected_version, current.server_version, auth.uid()
  );
  update public.workspaces set server_version = server_version + 1, updated_at = now()
    where id = target_workspace;
  return current;
end;
$$;

revoke all on function public.create_workspace(text, public.workspace_privacy) from public;
grant execute on function public.create_workspace(text, public.workspace_privacy) to authenticated;
revoke all on function public.apply_workspace_object(uuid, uuid, text, public.operation_kind, uuid, uuid, bigint, jsonb, bytea, bytea, integer, text) from public;
grant execute on function public.apply_workspace_object(uuid, uuid, text, public.operation_kind, uuid, uuid, bigint, jsonb, bytea, bytea, integer, text) to authenticated;

create or replace function public.is_workspace_member(target_workspace uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.workspace_memberships m
    where m.workspace_id = target_workspace and m.user_id = auth.uid() and m.removed_at is null
  );
$$;

create or replace function public.has_workspace_role(target_workspace uuid, accepted public.workspace_role[])
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.workspace_memberships m
    where m.workspace_id = target_workspace and m.user_id = auth.uid()
      and m.removed_at is null and m.role = any(accepted)
  );
$$;

create or replace function public.enforce_workspace_privacy()
returns trigger language plpgsql set search_path = '' as $$
declare mode public.workspace_privacy;
begin
  select privacy into mode from public.workspaces where id = new.workspace_id;
  if mode = 'personal_encrypted' and (new.ciphertext is null or new.content is not null) then
    raise exception 'Personal workspace content must be encrypted';
  end if;
  if mode = 'team_managed' and (new.content is null or new.ciphertext is not null) then
    raise exception 'Team workspace content must be server-readable';
  end if;
  return new;
end;
$$;

create trigger workspace_object_privacy before insert or update of workspace_id, ciphertext, content
on public.workspace_objects for each row execute function public.enforce_workspace_privacy();

alter table public.profiles enable row level security;
alter table public.workspaces enable row level security;
alter table public.workspace_memberships enable row level security;
alter table public.workspace_invitations enable row level security;
alter table public.workspace_key_envelopes enable row level security;
alter table public.workspace_objects enable row level security;
alter table public.object_operations enable row level security;
alter table public.object_comments enable row level security;
alter table public.activity_records enable row level security;
alter table public.files enable row level security;
alter table public.devices enable row level security;
alter table public.push_tokens enable row level security;
alter table public.notification_preferences enable row level security;
alter table public.notification_deliveries enable row level security;
alter table public.integration_accounts enable row level security;
alter table public.calendar_event_mappings enable row level security;
alter table public.subscriptions enable row level security;
alter table public.entitlements enable row level security;

create policy profiles_self_read on public.profiles for select using (id = auth.uid());
create policy profiles_self_update on public.profiles for update using (id = auth.uid()) with check (id = auth.uid());
create policy workspaces_member_read on public.workspaces for select using (public.is_workspace_member(id));
create policy workspaces_owner_update on public.workspaces for update using (public.has_workspace_role(id, array['owner','admin']::public.workspace_role[]));
create policy memberships_member_read on public.workspace_memberships for select using (public.is_workspace_member(workspace_id));
create policy memberships_admin_write on public.workspace_memberships for all using (public.has_workspace_role(workspace_id, array['owner','admin']::public.workspace_role[])) with check (public.has_workspace_role(workspace_id, array['owner','admin']::public.workspace_role[]));
create policy invitations_admin_all on public.workspace_invitations for all using (public.has_workspace_role(workspace_id, array['owner','admin']::public.workspace_role[])) with check (public.has_workspace_role(workspace_id, array['owner','admin']::public.workspace_role[]));
create policy envelopes_owner_read on public.workspace_key_envelopes for select using (user_id = auth.uid() and public.is_workspace_member(workspace_id));
create policy envelopes_admin_write on public.workspace_key_envelopes for all using (public.has_workspace_role(workspace_id, array['owner','admin']::public.workspace_role[])) with check (public.has_workspace_role(workspace_id, array['owner','admin']::public.workspace_role[]));

create policy objects_member_read on public.workspace_objects for select using (public.is_workspace_member(workspace_id));
create policy objects_member_insert on public.workspace_objects for insert with check (public.is_workspace_member(workspace_id) and created_by = auth.uid() and updated_by = auth.uid());
create policy objects_member_update on public.workspace_objects for update using (public.is_workspace_member(workspace_id)) with check (public.is_workspace_member(workspace_id) and updated_by = auth.uid());
create policy operations_member_read on public.object_operations for select using (public.is_workspace_member(workspace_id));
create policy operations_member_insert on public.object_operations for insert with check (public.is_workspace_member(workspace_id) and actor_id = auth.uid());
create policy comments_member_all on public.object_comments for all using (public.is_workspace_member(workspace_id)) with check (public.is_workspace_member(workspace_id) and author_id = auth.uid());
create policy activity_member_read on public.activity_records for select using (public.is_workspace_member(workspace_id));
create policy activity_member_insert on public.activity_records for insert with check (public.is_workspace_member(workspace_id) and actor_id = auth.uid());
create policy files_member_all on public.files for all using (public.is_workspace_member(workspace_id)) with check (public.is_workspace_member(workspace_id) and created_by = auth.uid());

create policy devices_self_all on public.devices for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy tokens_self_all on public.push_tokens for all using (exists (select 1 from public.devices d where d.id = device_id and d.user_id = auth.uid())) with check (exists (select 1 from public.devices d where d.id = device_id and d.user_id = auth.uid()));
create policy notification_preferences_self_all on public.notification_preferences for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy notification_deliveries_self_read on public.notification_deliveries for select using (user_id = auth.uid());
create policy integrations_self_all on public.integration_accounts for all using (user_id = auth.uid()) with check (user_id = auth.uid());
create policy calendar_mappings_self_all on public.calendar_event_mappings for all using (exists (select 1 from public.integration_accounts i where i.id = integration_id and i.user_id = auth.uid())) with check (exists (select 1 from public.integration_accounts i where i.id = integration_id and i.user_id = auth.uid()) and public.is_workspace_member(workspace_id));
create policy subscriptions_self_read on public.subscriptions for select using (user_id = auth.uid());
create policy entitlements_self_read on public.entitlements for select using (user_id = auth.uid());

insert into storage.buckets (id, name, public, file_size_limit)
values ('workspace-files', 'workspace-files', false, 104857600)
on conflict (id) do update set public = false, file_size_limit = excluded.file_size_limit;

create policy workspace_storage_read on storage.objects for select using (
  bucket_id = 'workspace-files'
  and public.is_workspace_member(((storage.foldername(name))[1])::uuid)
);
create policy workspace_storage_insert on storage.objects for insert with check (
  bucket_id = 'workspace-files'
  and public.is_workspace_member(((storage.foldername(name))[1])::uuid)
);
create policy workspace_storage_delete on storage.objects for delete using (
  bucket_id = 'workspace-files'
  and public.has_workspace_role(((storage.foldername(name))[1])::uuid, array['owner','admin']::public.workspace_role[])
);

alter publication supabase_realtime add table public.workspace_objects;
alter publication supabase_realtime add table public.object_comments;
alter publication supabase_realtime add table public.activity_records;
