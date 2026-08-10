create table if not exists public.collaboration_artifact_revisions (
  workspace_id uuid not null references public.workspaces(id) on delete cascade,
  artifact_id uuid not null references public.workspace_objects(id) on delete cascade,
  current_revision bigint not null default 0 check (current_revision >= 0),
  revision_hash text not null default repeat('0', 64) check (revision_hash ~ '^[0-9a-f]{64}$'),
  updated_at timestamptz not null default now(),
  primary key (workspace_id, artifact_id)
);

create table if not exists public.collaboration_operations (
  workspace_id uuid not null references public.workspaces(id) on delete cascade,
  artifact_id uuid not null references public.workspace_objects(id) on delete cascade,
  operation_id uuid not null,
  batch_id text check (batch_id is null or char_length(batch_id) between 1 and 256),
  actor_id uuid not null references public.profiles(id),
  device_id uuid not null references public.devices(id),
  actor_sequence bigint not null check (actor_sequence > 0),
  server_revision bigint not null check (server_revision > 0),
  revision_hash text not null check (revision_hash ~ '^[0-9a-f]{64}$'),
  protocol_version integer not null check (protocol_version between 1 and 32),
  semantic_version integer not null check (semantic_version between 1 and 32),
  artifact_schema_version integer not null check (artifact_schema_version between 1 and 1000000),
  key_epoch bigint not null default 0 check (key_epoch >= 0),
  content_type text not null check (char_length(content_type) between 1 and 128),
  payload_hash text not null check (payload_hash ~ '^[0-9a-f]{64}$'),
  payload jsonb not null,
  created_at timestamptz not null default now(),
  primary key (artifact_id, operation_id),
  unique (artifact_id, server_revision),
  check (pg_column_size(payload) <= 65536)
);

create index if not exists collaboration_operations_revision_idx
  on public.collaboration_operations (artifact_id, server_revision);

create table if not exists public.collaboration_snapshots (
  workspace_id uuid not null references public.workspaces(id) on delete cascade,
  artifact_id uuid not null references public.workspace_objects(id) on delete cascade,
  server_revision bigint not null check (server_revision >= 0),
  revision_hash text not null check (revision_hash ~ '^[0-9a-f]{64}$'),
  protocol_version integer not null check (protocol_version between 1 and 32),
  artifact_schema_version integer not null check (artifact_schema_version > 0),
  snapshot jsonb not null check (pg_column_size(snapshot) <= 1048576),
  created_by uuid not null references public.profiles(id),
  created_at timestamptz not null default now(),
  primary key (artifact_id, server_revision)
);

alter table public.collaboration_artifact_revisions enable row level security;
alter table public.collaboration_operations enable row level security;
alter table public.collaboration_snapshots enable row level security;

revoke all on public.collaboration_artifact_revisions, public.collaboration_operations, public.collaboration_snapshots from anon, authenticated;
grant select on public.collaboration_artifact_revisions, public.collaboration_operations, public.collaboration_snapshots to authenticated;

create or replace function public.authorize_collaboration_artifact(
  p_workspace uuid,
  p_artifact uuid,
  p_mutate boolean default false
) returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
    from public.workspace_objects o
    join public.workspace_memberships m
      on m.workspace_id = o.workspace_id
     and m.user_id = (select auth.uid())
     and m.removed_at is null
    where o.id = p_artifact
      and o.workspace_id = p_workspace
      and o.deleted_at is null
      and (not p_mutate or m.role in ('owner', 'admin', 'member'))
  );
$$;

revoke all on function public.authorize_collaboration_artifact(uuid, uuid, boolean) from public;
grant execute on function public.authorize_collaboration_artifact(uuid, uuid, boolean) to authenticated;

create or replace function public.authorize_collaboration_connection(
  p_workspace uuid,
  p_artifact uuid,
  p_device uuid
) returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select public.authorize_collaboration_artifact(p_workspace, p_artifact, true)
    and exists (
      select 1 from public.devices d
      where d.id = p_device and d.user_id = (select auth.uid())
    );
$$;

revoke all on function public.authorize_collaboration_connection(uuid, uuid, uuid) from public;
grant execute on function public.authorize_collaboration_connection(uuid, uuid, uuid) to authenticated;

create policy collaboration_revisions_read on public.collaboration_artifact_revisions
  for select to authenticated
  using ((select public.authorize_collaboration_artifact(workspace_id, artifact_id, false)));

create policy collaboration_operations_read on public.collaboration_operations
  for select to authenticated
  using ((select public.authorize_collaboration_artifact(workspace_id, artifact_id, false)));

create policy collaboration_snapshots_read on public.collaboration_snapshots
  for select to authenticated
  using ((select public.authorize_collaboration_artifact(workspace_id, artifact_id, false)));

create or replace function public.accept_collaboration_operation(
  p_workspace uuid,
  p_artifact uuid,
  p_device uuid,
  p_operation uuid,
  p_batch text,
  p_sequence bigint,
  p_protocol integer,
  p_semantic integer,
  p_schema integer,
  p_key_epoch bigint,
  p_content_type text,
  p_payload_hash text,
  p_payload jsonb
) returns table(operation_id uuid, server_revision bigint, revision_hash text, duplicate boolean)
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_actor uuid := (select auth.uid());
  v_existing public.collaboration_operations%rowtype;
  v_revision bigint;
  v_previous_hash text;
  v_hash text;
begin
  if v_actor is null then raise exception 'authentication_required' using errcode = '42501'; end if;
  if not public.authorize_collaboration_artifact(p_workspace, p_artifact, true) then
    raise exception 'artifact_mutation_denied' using errcode = '42501';
  end if;
  if not exists (select 1 from public.devices d where d.id = p_device and d.user_id = v_actor) then
    raise exception 'device_not_owned' using errcode = '42501';
  end if;
  if p_protocol <> 1 or p_semantic < 1 or p_schema < 1 or p_key_epoch < 0 then
    raise exception 'unsupported_protocol_metadata' using errcode = '22023';
  end if;
  if p_payload is null or p_payload_hash !~ '^[0-9a-f]{64}$' or pg_column_size(p_payload) > 65536 then
    raise exception 'invalid_payload' using errcode = '22023';
  end if;

  insert into public.collaboration_artifact_revisions(workspace_id, artifact_id)
  values (p_workspace, p_artifact)
  on conflict do nothing;

  select r.current_revision, r.revision_hash
    into v_revision, v_previous_hash
  from public.collaboration_artifact_revisions r
  where r.workspace_id = p_workspace and r.artifact_id = p_artifact
  for update;

  select o.* into v_existing
  from public.collaboration_operations o
  where o.artifact_id = p_artifact and o.operation_id = p_operation;
  if found then
    if v_existing.actor_id <> v_actor
      or v_existing.device_id <> p_device
      or v_existing.batch_id is distinct from p_batch
      or v_existing.actor_sequence <> p_sequence
      or v_existing.protocol_version <> p_protocol
      or v_existing.semantic_version <> p_semantic
      or v_existing.artifact_schema_version <> p_schema
      or v_existing.key_epoch <> p_key_epoch
      or v_existing.content_type <> p_content_type
      or v_existing.payload_hash <> p_payload_hash
      or v_existing.payload is distinct from p_payload
    then
      raise exception 'operation_id_collision' using errcode = '23505';
    end if;
    return query select p_operation, v_existing.server_revision, v_existing.revision_hash, true;
    return;
  end if;

  v_revision := v_revision + 1;
  v_hash := encode(extensions.digest(
    convert_to(v_previous_hash || p_payload_hash || p_operation::text || v_revision::text, 'utf8'),
    'sha256'
  ), 'hex');

  insert into public.collaboration_operations(
    workspace_id, artifact_id, operation_id, batch_id, actor_id, device_id,
    actor_sequence, server_revision, revision_hash, protocol_version, semantic_version,
    artifact_schema_version, key_epoch, content_type, payload_hash, payload
  ) values (
    p_workspace, p_artifact, p_operation, p_batch, v_actor, p_device,
    p_sequence, v_revision, v_hash, p_protocol, p_semantic, p_schema, p_key_epoch,
    p_content_type, p_payload_hash, p_payload
  );

  update public.collaboration_artifact_revisions
  set current_revision = v_revision, revision_hash = v_hash, updated_at = now()
  where workspace_id = p_workspace and artifact_id = p_artifact;

  -- The durable commit and its realtime hint share one database transaction. A client
  -- process dying after this RPC cannot leave peers unaware of an accepted operation.
  perform realtime.send(
    jsonb_build_object(
      'room', 'artifact:' || p_workspace::text || ':' || p_artifact::text,
      'actor', v_actor::text,
      'device', p_device::text,
      'op_id', p_operation::text,
      'batch_id', p_batch::text,
      'protocol', p_protocol,
      'semantic', p_semantic,
      'schema', p_schema,
      'key_epoch', p_key_epoch,
      'content_type', p_content_type,
      'payload_hash', p_payload_hash,
      'cls', 'DURABLE',
      'type', 'semantic.batch',
      'target', null,
      'seq', p_sequence,
      'sent_at', extract(epoch from clock_timestamp())::bigint * 1000,
      'server_revision', v_revision,
      'revision_hash', v_hash,
      'payload', p_payload
    ),
    'env',
    'artifact:' || p_workspace::text || ':' || p_artifact::text,
    true
  );

  return query select p_operation, v_revision, v_hash, false;
end;
$$;

revoke all on function public.accept_collaboration_operation(uuid, uuid, uuid, uuid, text, bigint, integer, integer, integer, bigint, text, text, jsonb) from public;
grant execute on function public.accept_collaboration_operation(uuid, uuid, uuid, uuid, text, bigint, integer, integer, integer, bigint, text, text, jsonb) to authenticated;

create or replace function public.send_collaboration_ephemeral(
  p_workspace uuid,
  p_artifact uuid,
  p_device uuid,
  p_event_class text,
  p_event_type text,
  p_target text,
  p_sequence bigint,
  p_payload_hash text,
  p_payload jsonb
) returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare v_actor uuid := (select auth.uid());
begin
  if v_actor is null or not public.authorize_collaboration_artifact(p_workspace, p_artifact, true) then
    raise exception 'ephemeral_send_denied' using errcode = '42501';
  end if;
  if not exists (select 1 from public.devices d where d.id = p_device and d.user_id = v_actor) then
    raise exception 'device_not_owned' using errcode = '42501';
  end if;
  if p_event_class not in ('EPHEMERAL_LATEST', 'EPHEMERAL_EVENT')
    or p_event_type !~ '^[a-z][a-z0-9_.-]{0,63}$'
    or p_event_type = 'semantic.batch'
    or p_sequence <= 0
    or p_payload_hash !~ '^[0-9a-f]{64}$'
    or pg_column_size(p_payload) > 16384
  then
    raise exception 'invalid_ephemeral_payload' using errcode = '22023';
  end if;
  perform realtime.send(
    jsonb_build_object(
      'room', 'artifact:' || p_workspace::text || ':' || p_artifact::text,
      'actor', v_actor::text,
      'device', p_device::text,
      'op_id', v_actor::text || ':' || p_device::text || ':' || p_sequence::text,
      'protocol', 1,
      'semantic', 1,
      'schema', 1,
      'key_epoch', 0,
      'content_type', 'application/vnd.norfold.presence+json',
      'payload_hash', p_payload_hash,
      'cls', p_event_class,
      'type', p_event_type,
      'target', p_target,
      'seq', p_sequence,
      'sent_at', extract(epoch from clock_timestamp())::bigint * 1000,
      'payload', p_payload
    ),
    'env',
    'artifact:' || p_workspace::text || ':' || p_artifact::text,
    true
  );
  return true;
end;
$$;

revoke all on function public.send_collaboration_ephemeral(uuid, uuid, uuid, text, text, text, bigint, text, jsonb) from public;
grant execute on function public.send_collaboration_ephemeral(uuid, uuid, uuid, text, text, text, bigint, text, jsonb) to authenticated;

create or replace function public.read_collaboration_operations(
  p_workspace uuid,
  p_artifact uuid,
  p_after_revision bigint,
  p_limit integer default 256
) returns table(
  operation_id uuid,
  server_revision bigint,
  revision_hash text,
  has_more boolean,
  envelope jsonb,
  snapshot_required boolean
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_current bigint;
  v_hash text;
  v_snapshot boolean;
  v_earliest bigint;
begin
  if not public.authorize_collaboration_artifact(p_workspace, p_artifact, false) then
    raise exception 'artifact_read_denied' using errcode = '42501';
  end if;
  if p_after_revision < 0 or p_limit not between 1 and 256 then
    raise exception 'invalid_recovery_window' using errcode = '22023';
  end if;
  select r.current_revision, r.revision_hash into v_current, v_hash
  from public.collaboration_artifact_revisions r
  where r.workspace_id = p_workspace and r.artifact_id = p_artifact;
  v_current := coalesce(v_current, 0);
  v_hash := coalesce(v_hash, repeat('0', 64));
  select min(o.server_revision) into v_earliest
  from public.collaboration_operations o
  where o.workspace_id = p_workspace and o.artifact_id = p_artifact;
  v_snapshot := (v_current - p_after_revision > 4096 or coalesce(v_earliest, v_current + 1) > p_after_revision + 1)
    and exists (
    select 1 from public.collaboration_snapshots s
    where s.artifact_id = p_artifact and s.server_revision > p_after_revision
  );
  if v_snapshot or v_current = p_after_revision then
    return query select null::uuid, v_current, v_hash, false, null::jsonb, v_snapshot;
    return;
  end if;
  return query
  with windowed as (
    select o.*
    from public.collaboration_operations o
    where o.workspace_id = p_workspace and o.artifact_id = p_artifact
      and o.server_revision > p_after_revision
    order by o.server_revision
    limit p_limit
  )
  select o.operation_id, o.server_revision, o.revision_hash,
    v_current > max(o.server_revision) over (),
    jsonb_build_object(
      'room', 'artifact:' || o.workspace_id::text || ':' || o.artifact_id::text,
      'actor', o.actor_id::text, 'device', o.device_id::text,
      'op_id', o.operation_id::text, 'batch_id', o.batch_id::text,
      'protocol', o.protocol_version, 'semantic', o.semantic_version,
      'schema', o.artifact_schema_version, 'key_epoch', o.key_epoch,
      'content_type', o.content_type, 'payload_hash', o.payload_hash,
      'cls', 'DURABLE', 'type', 'semantic.batch', 'seq', o.actor_sequence,
      'sent_at', extract(epoch from o.created_at)::bigint * 1000,
      'server_revision', o.server_revision, 'revision_hash', o.revision_hash,
      'payload', o.payload
    ), false
  from windowed o
  order by o.server_revision
  ;
end;
$$;

revoke all on function public.read_collaboration_operations(uuid, uuid, bigint, integer) from public;
grant execute on function public.read_collaboration_operations(uuid, uuid, bigint, integer) to authenticated;

create or replace function public.save_collaboration_snapshot(
  p_workspace uuid,
  p_artifact uuid,
  p_server_revision bigint,
  p_revision_hash text,
  p_protocol integer,
  p_schema integer,
  p_snapshot jsonb
) returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare v_actor uuid := (select auth.uid());
begin
  if v_actor is null
    or not public.authorize_collaboration_artifact(p_workspace, p_artifact, true)
    or not public.has_workspace_role(p_workspace, array['owner','admin']::public.workspace_role[])
  then
    raise exception 'snapshot_write_denied' using errcode = '42501';
  end if;
  if p_protocol <> 1 or p_schema < 1 or p_snapshot is null or pg_column_size(p_snapshot) > 1048576 then
    raise exception 'invalid_snapshot' using errcode = '22023';
  end if;
  if not exists (
    select 1 from public.collaboration_artifact_revisions r
    where r.workspace_id = p_workspace and r.artifact_id = p_artifact
      and r.current_revision = p_server_revision and r.revision_hash = p_revision_hash
  ) then raise exception 'snapshot_revision_mismatch' using errcode = '40001'; end if;
  insert into public.collaboration_snapshots(
    workspace_id, artifact_id, server_revision, revision_hash,
    protocol_version, artifact_schema_version, snapshot, created_by
  ) values (
    p_workspace, p_artifact, p_server_revision, p_revision_hash,
    p_protocol, p_schema, p_snapshot, v_actor
  ) on conflict (artifact_id, server_revision) do nothing;
  return true;
end;
$$;

revoke all on function public.save_collaboration_snapshot(uuid, uuid, bigint, text, integer, integer, jsonb) from public;
grant execute on function public.save_collaboration_snapshot(uuid, uuid, bigint, text, integer, integer, jsonb) to authenticated;

create or replace function public.read_collaboration_snapshot(
  p_workspace uuid,
  p_artifact uuid,
  p_minimum_revision bigint
) returns table(
  server_revision bigint,
  revision_hash text,
  protocol_version integer,
  artifact_schema_version integer,
  snapshot jsonb
)
language sql
stable
security definer
set search_path = ''
as $$
  select s.server_revision, s.revision_hash, s.protocol_version, s.artifact_schema_version, s.snapshot
  from public.collaboration_snapshots s
  where s.workspace_id = p_workspace and s.artifact_id = p_artifact
    and s.server_revision >= p_minimum_revision
    and public.authorize_collaboration_artifact(p_workspace, p_artifact, false)
  order by s.server_revision desc
  limit 1;
$$;

revoke all on function public.read_collaboration_snapshot(uuid, uuid, bigint) from public;
grant execute on function public.read_collaboration_snapshot(uuid, uuid, bigint) to authenticated;

create or replace function public.compact_collaboration_log(
  p_workspace uuid,
  p_artifact uuid,
  p_snapshot_revision bigint
) returns bigint
language plpgsql
security definer
set search_path = ''
as $$
declare v_deleted bigint;
begin
  if not public.has_workspace_role(p_workspace, array['owner','admin']::public.workspace_role[]) then
    raise exception 'compaction_denied' using errcode = '42501';
  end if;
  if not exists (
    select 1 from public.collaboration_snapshots s
    join public.collaboration_artifact_revisions r
      on r.workspace_id = s.workspace_id and r.artifact_id = s.artifact_id
    where s.workspace_id = p_workspace and s.artifact_id = p_artifact
      and s.server_revision = p_snapshot_revision
      and s.revision_hash = r.revision_hash
  ) then raise exception 'verified_snapshot_required' using errcode = '22023'; end if;
  delete from public.collaboration_operations o
  where o.workspace_id = p_workspace and o.artifact_id = p_artifact
    and o.server_revision <= greatest(0, p_snapshot_revision - 512);
  get diagnostics v_deleted = row_count;
  return v_deleted;
end;
$$;

revoke all on function public.compact_collaboration_log(uuid, uuid, bigint) from public;
grant execute on function public.compact_collaboration_log(uuid, uuid, bigint) to authenticated;

create or replace function public.authorize_collaboration_topic(
  p_topic text,
  p_mutate boolean default false
) returns boolean
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_parts text[];
begin
  v_parts := string_to_array(p_topic, ':');
  if array_length(v_parts, 1) <> 3 or v_parts[1] <> 'artifact' then return false; end if;
  return public.authorize_collaboration_artifact(v_parts[2]::uuid, v_parts[3]::uuid, p_mutate);
exception when invalid_text_representation then
  return false;
end;
$$;

revoke all on function public.authorize_collaboration_topic(text, boolean) from public;
grant execute on function public.authorize_collaboration_topic(text, boolean) to authenticated;

do $$ begin
  if to_regclass('realtime.messages') is not null then
    execute 'create policy collaboration_realtime_read on realtime.messages for select to authenticated using (
      public.authorize_collaboration_topic((select realtime.topic()), false)
    )';
  end if;
exception when duplicate_object then null;
end $$;
