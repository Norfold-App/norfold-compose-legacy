-- Pre-Beta Stage 1: truthful workspace Chat security modes, disclosure
-- acknowledgements, non-retroactive epochs, and private-channel boundaries.

create table public.workspace_chat_security_settings (
  workspace_id uuid primary key references public.workspaces(id) on delete cascade,
  mode public.chat_security_mode not null,
  security_version uuid not null default gen_random_uuid(),
  epoch_number integer not null default 1 check (epoch_number > 0),
  protocol text not null,
  changed_by uuid not null references public.profiles(id),
  changed_at timestamptz not null default now()
);

-- Existing workspace Chat history was written through workspace_objects as
-- plaintext JSON. Classify it as Managed instead of retroactively claiming it
-- was E2EE. Only workspaces created after this migration default to E2EE.
insert into public.workspace_chat_security_settings (
  workspace_id, mode, protocol, changed_by, changed_at
)
select w.id, 'managed', 'managed-tls-rls-v1', w.created_by, now()
from public.workspaces w
on conflict (workspace_id) do nothing;

alter table public.workspace_chat_security_settings enable row level security;
create policy workspace_chat_security_member_read
  on public.workspace_chat_security_settings for select to authenticated
  using (private.is_active_workspace_member(workspace_id, (select auth.uid())));
revoke all on public.workspace_chat_security_settings from anon, authenticated;
grant select on public.workspace_chat_security_settings to authenticated;

create table public.workspace_chat_security_acknowledgements (
  workspace_id uuid not null references public.workspaces(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  security_version uuid not null,
  acknowledged_at timestamptz not null default now(),
  primary key (workspace_id, user_id)
);

alter table public.workspace_chat_security_acknowledgements enable row level security;
create policy workspace_chat_security_ack_owner_read
  on public.workspace_chat_security_acknowledgements for select to authenticated
  using (user_id = (select auth.uid()));
revoke all on public.workspace_chat_security_acknowledgements from anon, authenticated;
grant select on public.workspace_chat_security_acknowledgements to authenticated;

create table public.workspace_chat_security_events (
  id uuid primary key default gen_random_uuid(),
  workspace_id uuid not null references public.workspaces(id) on delete cascade,
  security_version uuid not null,
  epoch_number integer not null check (epoch_number > 0),
  previous_mode public.chat_security_mode,
  mode public.chat_security_mode not null,
  reason text not null check (reason in (
    'workspace_created', 'migration_classification', 'mode_changed',
    'workspace_membership_changed', 'channel_membership_changed',
    'channel_created', 'channel_visibility_changed', 'channel_deleted'
  )),
  changed_by uuid not null references public.profiles(id),
  created_at timestamptz not null default now()
);

create index workspace_chat_security_events_workspace_idx
  on public.workspace_chat_security_events(workspace_id, created_at desc);
alter table public.workspace_chat_security_events enable row level security;
create policy workspace_chat_security_events_member_read
  on public.workspace_chat_security_events for select to authenticated
  using (private.is_active_workspace_member(workspace_id, (select auth.uid())));
revoke all on public.workspace_chat_security_events from anon, authenticated;
grant select on public.workspace_chat_security_events to authenticated;

insert into public.workspace_chat_security_events (
  workspace_id, security_version, epoch_number, previous_mode, mode, reason, changed_by
)
select s.workspace_id, s.security_version, s.epoch_number, null, s.mode,
       'migration_classification', s.changed_by
from public.workspace_chat_security_settings s;

create or replace function private.workspace_epoch_user_is_authorized(
  p_workspace uuid,
  p_channel uuid,
  p_user uuid
) returns boolean
language sql stable security definer set search_path = '' as $$
  select private.is_active_workspace_member(p_workspace, p_user)
    and (
      p_channel is null
      or exists (
        select 1
        from public.workspace_chat_channels c
        where c.workspace_id = p_workspace
          and c.channel_id = p_channel
          and c.deleted_at is null
          and (
            c.visibility = 'public'
            or (
              c.visibility = 'private'
              and private.is_private_channel_member(p_workspace, p_channel, p_user)
            )
          )
      )
    );
$$;

revoke all on function private.workspace_epoch_user_is_authorized(uuid, uuid, uuid)
  from public, anon;
grant execute on function private.workspace_epoch_user_is_authorized(uuid, uuid, uuid)
  to authenticated;

-- Freeze the authorized participant set at epoch creation. Dynamic membership
-- alone would let a newly-added member request pre-join ciphertext/recovery.
create table public.chat_security_epoch_members (
  security_epoch_id uuid not null references public.chat_security_epochs(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  added_at timestamptz not null default now(),
  primary key (security_epoch_id, user_id)
);

alter table public.chat_security_epoch_members enable row level security;
revoke all on public.chat_security_epoch_members from anon, authenticated;

create or replace function private.snapshot_chat_security_epoch_members()
returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if new.scope = 'account' then
    insert into public.chat_security_epoch_members (security_epoch_id, user_id)
    select new.id, m.user_id
    from public.direct_conversation_members m
    where m.conversation_id = new.account_conversation_id
    on conflict do nothing;
  else
    insert into public.chat_security_epoch_members (security_epoch_id, user_id)
    select new.id, m.user_id
    from public.workspace_memberships m
    where m.workspace_id = new.workspace_id
      and m.removed_at is null
      and private.workspace_epoch_user_is_authorized(
        new.workspace_id, new.channel_id, m.user_id
      )
    on conflict do nothing;
  end if;
  return new;
end;
$$;

revoke all on function private.snapshot_chat_security_epoch_members()
  from public, anon, authenticated;
drop trigger if exists snapshot_chat_security_epoch_members
  on public.chat_security_epochs;
create trigger snapshot_chat_security_epoch_members
after insert on public.chat_security_epochs
for each row execute function private.snapshot_chat_security_epoch_members();

-- Best-available snapshot for epochs created before this additive migration.
insert into public.chat_security_epoch_members (security_epoch_id, user_id)
select e.id, m.user_id
from public.chat_security_epochs e
join public.direct_conversation_members m
  on e.scope = 'account' and m.conversation_id = e.account_conversation_id
on conflict do nothing;

insert into public.chat_security_epoch_members (security_epoch_id, user_id)
select e.id, m.user_id
from public.chat_security_epochs e
join public.workspace_memberships m
  on e.scope = 'workspace' and m.workspace_id = e.workspace_id
 and m.removed_at is null
where private.workspace_epoch_user_is_authorized(
  e.workspace_id, e.channel_id, m.user_id
)
on conflict do nothing;

create or replace function private.can_access_security_epoch(
  p_epoch uuid,
  p_user uuid
) returns boolean
language sql stable security definer set search_path = '' as $$
  select p_user is not null and exists (
    select 1
    from public.chat_security_epochs e
    join public.chat_security_epoch_members em
      on em.security_epoch_id = e.id and em.user_id = p_user
    where e.id = p_epoch
      and (
        (e.scope = 'account' and exists (
          select 1 from public.direct_conversation_members m
          where m.conversation_id = e.account_conversation_id
            and m.user_id = p_user
        ))
        or (
          e.scope = 'workspace'
          and private.workspace_epoch_user_is_authorized(
            e.workspace_id, e.channel_id, p_user
          )
        )
      )
  );
$$;

revoke all on function private.can_access_security_epoch(uuid, uuid)
  from public, anon;
grant execute on function private.can_access_security_epoch(uuid, uuid)
  to authenticated;

create or replace function private.rotate_workspace_chat_security_epochs(
  p_workspace uuid,
  p_reason text,
  p_actor uuid
) returns void
language plpgsql security definer set search_path = '' as $$
declare
  v_settings public.workspace_chat_security_settings%rowtype;
begin
  select * into v_settings
  from public.workspace_chat_security_settings s
  where s.workspace_id = p_workspace
  for update;
  if v_settings.workspace_id is null then
    return;
  end if;

  -- Membership/channel boundary changes rotate keys without changing the
  -- disclosure version. Mode changes already incremented both values in the
  -- CAS-protected public RPC; workspace creation starts at epoch 1.
  if p_reason not in ('workspace_created', 'mode_changed') then
    update public.workspace_chat_security_settings
    set epoch_number = epoch_number + 1,
        changed_by = p_actor
    where workspace_id = p_workspace
    returning * into v_settings;
  end if;

  update public.chat_security_epochs
  set ended_at = now()
  where scope = 'workspace'
    and workspace_id = p_workspace
    and ended_at is null;

  insert into public.chat_security_epochs (
    scope, workspace_id, channel_id, epoch_number, mode, protocol, started_by
  ) values (
    'workspace', p_workspace, null, v_settings.epoch_number,
    v_settings.mode, v_settings.protocol, p_actor
  );

  insert into public.chat_security_epochs (
    scope, workspace_id, channel_id, epoch_number, mode, protocol, started_by
  )
  select 'workspace', p_workspace, c.channel_id, v_settings.epoch_number,
         v_settings.mode, v_settings.protocol, p_actor
  from public.workspace_chat_channels c
  where c.workspace_id = p_workspace and c.deleted_at is null;

  insert into public.workspace_chat_security_events (
    workspace_id, security_version, epoch_number, previous_mode, mode,
    reason, changed_by
  ) values (
    p_workspace, v_settings.security_version, v_settings.epoch_number,
    v_settings.mode, v_settings.mode, p_reason, p_actor
  );
end;
$$;

revoke all on function private.rotate_workspace_chat_security_epochs(uuid, text, uuid)
  from public, anon, authenticated;

-- Give historical Managed content an immutable initial epoch before transport
-- enforcement turns on. This labels existing semantics; it does not encrypt or
-- otherwise rewrite the message body.
insert into public.chat_security_epochs (
  scope, workspace_id, channel_id, epoch_number, mode, protocol, started_by
)
select 'workspace', s.workspace_id, null, s.epoch_number, s.mode, s.protocol, s.changed_by
from public.workspace_chat_security_settings s
where not exists (
  select 1 from public.chat_security_epochs e
  where e.scope = 'workspace' and e.workspace_id = s.workspace_id
    and e.channel_id is null and e.ended_at is null
);

insert into public.chat_security_epochs (
  scope, workspace_id, channel_id, epoch_number, mode, protocol, started_by
)
select 'workspace', s.workspace_id, c.channel_id, s.epoch_number,
       s.mode, s.protocol, s.changed_by
from public.workspace_chat_security_settings s
join public.workspace_chat_channels c
  on c.workspace_id = s.workspace_id and c.deleted_at is null
where not exists (
  select 1 from public.chat_security_epochs e
  where e.scope = 'workspace' and e.workspace_id = s.workspace_id
    and e.channel_id = c.channel_id and e.ended_at is null
);

-- This backfill mutates existing workspace_objects rows as a maintenance
-- operation, not on behalf of an authenticated caller, so the per-row
-- enforce_channel_privacy() trigger (which requires auth.uid()) must be
-- suspended for the two backfill statements only. It is re-enabled
-- immediately afterward, still inside this migration transaction, so the
-- privacy guard is never absent for real traffic.
alter table public.workspace_objects disable trigger workspace_object_channel_privacy;

update public.workspace_objects o
set content = o.content || jsonb_build_object(
  'securityMode', 'managed',
  'securityEpochId', e.id::text,
  'securityVersion', s.security_version::text
)
from public.workspace_chat_security_settings s
join public.chat_security_epochs e
  on e.workspace_id = s.workspace_id and e.scope = 'workspace' and e.ended_at is null
where o.workspace_id = s.workspace_id
  and o.object_type = 'chat_message'
  and o.deleted_at is null
  and o.content is not null
  and coalesce(e.channel_id::text, '') = coalesce(o.content ->> 'channelSyncId', '');

update public.workspace_objects o
set content = o.content || jsonb_build_object(
  'visibility', c.visibility,
  'securityMode', 'managed',
  'securityEpochId', e.id::text,
  'securityVersion', s.security_version::text
)
from public.workspace_chat_channels c
join public.workspace_chat_security_settings s on s.workspace_id = c.workspace_id
join public.chat_security_epochs e
  on e.workspace_id = c.workspace_id and e.channel_id = c.channel_id
 and e.scope = 'workspace' and e.ended_at is null
where o.workspace_id = c.workspace_id
  and o.client_sync_id = c.channel_id
  and o.object_type = 'chat_channel'
  and o.deleted_at is null;

alter table public.workspace_objects enable trigger workspace_object_channel_privacy;

create or replace function private.initialize_workspace_chat_security()
returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  insert into public.workspace_chat_security_settings (
    workspace_id, mode, protocol, changed_by
  ) values (
    new.id, 'e2ee', 'norfold-hpke-x25519-aes256gcm-ed25519-v1', new.created_by
  );
  perform private.rotate_workspace_chat_security_epochs(
    new.id, 'workspace_created', new.created_by
  );
  return new;
end;
$$;

revoke all on function private.initialize_workspace_chat_security()
  from public, anon, authenticated;
drop trigger if exists initialize_workspace_chat_security on public.workspaces;
create trigger initialize_workspace_chat_security
after insert on public.workspaces
for each row execute function private.initialize_workspace_chat_security();

create or replace function private.rotate_workspace_chat_on_workspace_membership()
returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  v_workspace uuid;
  v_subject uuid;
  v_actor uuid;
begin
  if tg_op = 'DELETE' then
    v_workspace := old.workspace_id;
    v_subject := old.user_id;
  else
    v_workspace := new.workspace_id;
    v_subject := new.user_id;
  end if;
  v_actor := coalesce(
    auth.uid(),
    (select w.created_by from public.workspaces w where w.id = v_workspace),
    v_subject
  );
  perform private.rotate_workspace_chat_security_epochs(
    v_workspace, 'workspace_membership_changed', v_actor
  );
  if tg_op = 'DELETE' then return old; else return new; end if;
end;
$$;

revoke all on function private.rotate_workspace_chat_on_workspace_membership()
  from public, anon, authenticated;
drop trigger if exists workspace_membership_insert_rotates_chat_security
  on public.workspace_memberships;
create trigger workspace_membership_insert_rotates_chat_security
after insert on public.workspace_memberships
for each row execute function private.rotate_workspace_chat_on_workspace_membership();
drop trigger if exists workspace_membership_update_rotates_chat_security
  on public.workspace_memberships;
create trigger workspace_membership_update_rotates_chat_security
after update of removed_at on public.workspace_memberships
for each row
when (old.removed_at is distinct from new.removed_at)
execute function private.rotate_workspace_chat_on_workspace_membership();
drop trigger if exists workspace_membership_delete_rotates_chat_security
  on public.workspace_memberships;
create trigger workspace_membership_delete_rotates_chat_security
after delete on public.workspace_memberships
for each row execute function private.rotate_workspace_chat_on_workspace_membership();

create or replace function private.rotate_workspace_chat_on_channel_membership()
returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  v_workspace uuid;
  v_subject uuid;
  v_actor uuid;
begin
  if tg_op = 'DELETE' then
    v_workspace := old.workspace_id;
    v_subject := old.user_id;
  else
    v_workspace := new.workspace_id;
    v_subject := new.user_id;
  end if;
  v_actor := coalesce(
    auth.uid(),
    (select w.created_by from public.workspaces w where w.id = v_workspace),
    v_subject
  );
  perform private.rotate_workspace_chat_security_epochs(
    v_workspace, 'channel_membership_changed', v_actor
  );
  if tg_op = 'DELETE' then return old; else return new; end if;
end;
$$;

revoke all on function private.rotate_workspace_chat_on_channel_membership()
  from public, anon, authenticated;
drop trigger if exists channel_membership_rotates_chat_security
  on public.chat_channel_members;
create trigger channel_membership_rotates_chat_security
after insert or delete on public.chat_channel_members
for each row execute function private.rotate_workspace_chat_on_channel_membership();

create or replace function private.rotate_workspace_chat_on_channel_metadata()
returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  v_workspace uuid;
  v_reason text;
  v_actor uuid;
begin
  if tg_op = 'DELETE' then
    v_workspace := old.workspace_id;
  else
    v_workspace := new.workspace_id;
  end if;
  v_reason := case
    when tg_op = 'INSERT' then 'channel_created'
    when tg_op = 'DELETE' then 'channel_deleted'
    when new.deleted_at is distinct from old.deleted_at and new.deleted_at is not null
      then 'channel_deleted'
    else 'channel_visibility_changed'
  end;
  v_actor := coalesce(
    auth.uid(),
    case when tg_op = 'DELETE' then old.owner_id else new.owner_id end,
    (select w.created_by from public.workspaces w where w.id = v_workspace)
  );
  perform private.rotate_workspace_chat_security_epochs(v_workspace, v_reason, v_actor);
  if tg_op = 'DELETE' then return old; else return new; end if;
end;
$$;

revoke all on function private.rotate_workspace_chat_on_channel_metadata()
  from public, anon, authenticated;
drop trigger if exists workspace_channel_insert_rotates_chat_security
  on public.workspace_chat_channels;
create trigger workspace_channel_insert_rotates_chat_security
after insert on public.workspace_chat_channels
for each row execute function private.rotate_workspace_chat_on_channel_metadata();
drop trigger if exists workspace_channel_update_rotates_chat_security
  on public.workspace_chat_channels;
create trigger workspace_channel_update_rotates_chat_security
after update of visibility, deleted_at on public.workspace_chat_channels
for each row
when (
  old.visibility is distinct from new.visibility
  or old.deleted_at is distinct from new.deleted_at
)
execute function private.rotate_workspace_chat_on_channel_metadata();
drop trigger if exists workspace_channel_delete_rotates_chat_security
  on public.workspace_chat_channels;
create trigger workspace_channel_delete_rotates_chat_security
after delete on public.workspace_chat_channels
for each row execute function private.rotate_workspace_chat_on_channel_metadata();

create or replace function public.workspace_chat_security_snapshot(
  p_workspace uuid,
  p_channel uuid default null
) returns table (
  workspace_id uuid,
  mode public.chat_security_mode,
  security_version uuid,
  epoch_number integer,
  security_epoch_id uuid,
  acknowledged boolean,
  changed_at timestamptz
)
language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null
     or not private.workspace_epoch_user_is_authorized(
       p_workspace, p_channel, auth.uid()
     ) then
    raise exception using errcode = '42501', message = 'workspace_channel_access_required';
  end if;

  return query
  select s.workspace_id, s.mode, s.security_version, s.epoch_number,
         e.id,
         exists (
           select 1 from public.workspace_chat_security_acknowledgements a
           where a.workspace_id = s.workspace_id
             and a.user_id = auth.uid()
             and a.security_version = s.security_version
         ),
         s.changed_at
  from public.workspace_chat_security_settings s
  left join public.chat_security_epochs e
    on e.workspace_id = s.workspace_id
   and e.scope = 'workspace'
   and e.channel_id is not distinct from p_channel
   and e.ended_at is null
  where s.workspace_id = p_workspace;
end;
$$;

revoke all on function public.workspace_chat_security_snapshot(uuid, uuid)
  from public, anon;
grant execute on function public.workspace_chat_security_snapshot(uuid, uuid)
  to authenticated;

create or replace function public.acknowledge_workspace_chat_security(
  p_workspace uuid,
  p_security_version uuid
) returns void
language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null
     or not private.is_active_workspace_member(p_workspace, auth.uid())
     or not exists (
       select 1 from public.workspace_chat_security_settings s
       where s.workspace_id = p_workspace
         and s.security_version = p_security_version
     ) then
    raise exception using errcode = '42501', message = 'security_acknowledgement_not_authorized';
  end if;
  insert into public.workspace_chat_security_acknowledgements (
    workspace_id, user_id, security_version, acknowledged_at
  ) values (p_workspace, auth.uid(), p_security_version, now())
  on conflict (workspace_id, user_id) do update
  set security_version = excluded.security_version,
      acknowledged_at = excluded.acknowledged_at;
end;
$$;

revoke all on function public.acknowledge_workspace_chat_security(uuid, uuid)
  from public, anon;
grant execute on function public.acknowledge_workspace_chat_security(uuid, uuid)
  to authenticated;

create or replace function public.set_workspace_chat_security_mode(
  p_workspace uuid,
  p_mode public.chat_security_mode,
  p_expected_security_version uuid
) returns table (
  workspace_id uuid,
  mode public.chat_security_mode,
  security_version uuid,
  epoch_number integer,
  changed_at timestamptz
)
language plpgsql security definer set search_path = '' as $$
declare
  v_current public.workspace_chat_security_settings%rowtype;
  v_next_version uuid := gen_random_uuid();
begin
  if auth.uid() is null
     or not private.is_workspace_admin(p_workspace, auth.uid()) then
    raise exception using errcode = '42501', message = 'workspace_security_admin_required';
  end if;

  select * into v_current
  from public.workspace_chat_security_settings s
  where s.workspace_id = p_workspace
  for update;
  if v_current.workspace_id is null then
    raise exception using errcode = 'P0002', message = 'workspace_security_not_found';
  end if;
  if v_current.security_version <> p_expected_security_version then
    raise exception using errcode = '40001', message = 'workspace_security_version_conflict';
  end if;
  if v_current.mode = p_mode then
    return query select v_current.workspace_id, v_current.mode,
      v_current.security_version, v_current.epoch_number, v_current.changed_at;
    return;
  end if;

  if p_mode = 'e2ee' and exists (
    select 1
    from public.workspace_memberships m
    where m.workspace_id = p_workspace
      and m.removed_at is null
      and not exists (
        select 1 from public.chat_device_credentials c
        where c.user_id = m.user_id and c.revoked_at is null
      )
  ) then
    raise exception using errcode = '55000', message = 'workspace_e2ee_devices_incomplete';
  end if;

  update public.workspace_chat_security_settings
  set mode = p_mode,
      security_version = v_next_version,
      epoch_number = epoch_number + 1,
      protocol = case
        when p_mode = 'e2ee' then 'norfold-hpke-x25519-aes256gcm-ed25519-v1'
        else 'managed-tls-rls-v1'
      end,
      changed_by = auth.uid(),
      changed_at = now()
  where workspace_id = p_workspace;

  delete from public.workspace_chat_security_acknowledgements
  where workspace_id = p_workspace;

  perform private.rotate_workspace_chat_security_epochs(
    p_workspace, 'mode_changed', auth.uid()
  );

  update public.workspace_chat_security_events
  set previous_mode = v_current.mode
  where id = (
    select e.id from public.workspace_chat_security_events e
    where e.workspace_id = p_workspace
      and e.security_version = v_next_version
      and e.reason = 'mode_changed'
    order by e.created_at desc limit 1
  );

  return query
  select s.workspace_id, s.mode, s.security_version, s.epoch_number, s.changed_at
  from public.workspace_chat_security_settings s
  where s.workspace_id = p_workspace;
end;
$$;

revoke all on function public.set_workspace_chat_security_mode(uuid, public.chat_security_mode, uuid)
  from public, anon;
grant execute on function public.set_workspace_chat_security_mode(uuid, public.chat_security_mode, uuid)
  to authenticated;

create or replace function public.ensure_workspace_chat_security_epoch(
  p_workspace uuid,
  p_channel uuid default null
) returns table (
  workspace_id uuid,
  security_epoch_id uuid,
  mode public.chat_security_mode,
  security_version uuid,
  epoch_number integer,
  acknowledged boolean,
  changed_at timestamptz
)
language plpgsql security definer set search_path = '' as $$
declare
  v_settings public.workspace_chat_security_settings%rowtype;
  v_epoch uuid;
begin
  if auth.uid() is null
     or not private.workspace_epoch_user_is_authorized(
       p_workspace, p_channel, auth.uid()
     ) then
    raise exception using errcode = '42501', message = 'workspace_channel_access_required';
  end if;

  select * into v_settings
  from public.workspace_chat_security_settings s
  where s.workspace_id = p_workspace
  for update;

  if v_settings.mode = 'e2ee' and exists (
    select 1
    from public.workspace_memberships m
    where m.workspace_id = p_workspace
      and m.removed_at is null
      and private.workspace_epoch_user_is_authorized(
        p_workspace, p_channel, m.user_id
      )
      and not exists (
        select 1 from public.chat_device_credentials c
        where c.user_id = m.user_id and c.revoked_at is null
      )
  ) then
    raise exception using errcode = '55000', message = 'workspace_e2ee_devices_incomplete';
  end if;

  select e.id into v_epoch
  from public.chat_security_epochs e
  where e.scope = 'workspace'
    and e.workspace_id = p_workspace
    and e.channel_id is not distinct from p_channel
    and e.ended_at is null
  for update;

  if v_epoch is null then
    insert into public.chat_security_epochs (
      scope, workspace_id, channel_id, epoch_number, mode, protocol, started_by
    ) values (
      'workspace', p_workspace, p_channel, v_settings.epoch_number,
      v_settings.mode, v_settings.protocol, auth.uid()
    ) returning id into v_epoch;
  end if;

  return query
  select v_settings.workspace_id, v_epoch, v_settings.mode,
    v_settings.security_version, v_settings.epoch_number,
    exists (
      select 1 from public.workspace_chat_security_acknowledgements a
      where a.workspace_id = p_workspace
        and a.user_id = auth.uid()
        and a.security_version = v_settings.security_version
    ), v_settings.changed_at;
end;
$$;

revoke all on function public.ensure_workspace_chat_security_epoch(uuid, uuid)
  from public, anon;
grant execute on function public.ensure_workspace_chat_security_epoch(uuid, uuid)
  to authenticated;

create or replace function public.security_epoch_device_credentials(
  p_security_epoch uuid
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
         c.hpke_public_keyset, c.signing_public_keyset,
         c.credential_fingerprint
  from public.chat_device_credentials c
  where c.revoked_at is null
    and private.can_access_security_epoch(p_security_epoch, c.user_id)
    and private.can_access_security_epoch(p_security_epoch, (select auth.uid()));
$$;

revoke all on function public.security_epoch_device_credentials(uuid)
  from public, anon;
grant execute on function public.security_epoch_device_credentials(uuid)
  to authenticated;

create or replace function private.enforce_active_e2ee_relay_epoch()
returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  v_epoch public.chat_security_epochs%rowtype;
  v_expected_conversation text;
begin
  if new.envelope is null and new.state <> 'active' then
    return new;
  end if;
  select * into v_epoch
  from public.chat_security_epochs e
  where e.id = new.security_epoch_id;
  if v_epoch.id is null
     or v_epoch.mode <> 'e2ee'
     or v_epoch.ended_at is not null then
    raise exception using errcode = '42501', message = 'active_e2ee_epoch_required';
  end if;
  if v_epoch.scope = 'workspace' and not exists (
    select 1 from public.workspace_chat_security_settings s
    where s.workspace_id = v_epoch.workspace_id and s.mode = 'e2ee'
  ) then
    raise exception using errcode = '42501', message = 'workspace_e2ee_mode_required';
  end if;

  v_expected_conversation := case
    when v_epoch.scope = 'account' then v_epoch.account_conversation_id::text
    else coalesce(v_epoch.channel_id, v_epoch.workspace_id)::text
  end;
  if new.envelope #>> '{metadata,conversationId}'
       is distinct from v_expected_conversation then
    raise exception using errcode = '22023', message = 'secure_conversation_metadata_mismatch';
  end if;
  return new;
end;
$$;

revoke all on function private.enforce_active_e2ee_relay_epoch()
  from public, anon, authenticated;
drop trigger if exists enforce_active_e2ee_relay_epoch
  on public.chat_e2ee_relay_messages;
create trigger enforce_active_e2ee_relay_epoch
before insert or update of envelope, security_epoch_id
on public.chat_e2ee_relay_messages
for each row execute function private.enforce_active_e2ee_relay_epoch();

create or replace function private.enforce_workspace_chat_transport_security()
returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  v_settings public.workspace_chat_security_settings%rowtype;
  v_epoch public.chat_security_epochs%rowtype;
begin
  if new.object_type <> 'chat_message' or new.deleted_at is not null then
    return new;
  end if;

  select * into v_settings
  from public.workspace_chat_security_settings s
  where s.workspace_id = new.workspace_id;
  if v_settings.workspace_id is null then
    raise exception using errcode = '42501', message = 'workspace_security_state_required';
  end if;
  if v_settings.mode = 'e2ee' then
    raise exception using errcode = '42501', message = 'workspace_plaintext_fallback_forbidden';
  end if;
  if lower(coalesce(new.content ->> 'securityMode', '')) <> 'managed' then
    raise exception using errcode = '22023', message = 'managed_security_label_required';
  end if;

  select * into v_epoch
  from public.chat_security_epochs e
  where e.id = nullif(new.content ->> 'securityEpochId', '')::uuid
    and e.scope = 'workspace'
    and e.workspace_id = new.workspace_id
    and e.channel_id is not distinct from
      nullif(new.content ->> 'channelSyncId', '')::uuid
    and e.mode = 'managed'
    and e.ended_at is null;
  if v_epoch.id is null then
    raise exception using errcode = '42501', message = 'active_managed_epoch_required';
  end if;
  return new;
end;
$$;

revoke all on function private.enforce_workspace_chat_transport_security()
  from public, anon, authenticated;
drop trigger if exists enforce_workspace_chat_transport_security
  on public.workspace_objects;
create trigger enforce_workspace_chat_transport_security
before insert or update of content, object_type, deleted_at
on public.workspace_objects
for each row execute function private.enforce_workspace_chat_transport_security();

-- Ensure clients can discover the control-plane changes without polling the
-- entire workspace object stream.
do $$
begin
  alter publication supabase_realtime
    add table public.workspace_chat_security_settings;
exception when duplicate_object then null;
end $$;
