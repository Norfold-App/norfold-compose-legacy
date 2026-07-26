-- ===========================================================================
-- Chat overhaul — rich groups (P4), contact requests (P6), DM receipts (P7).
--
-- STAGED, NOT DEPLOYED. The owner runs the two-device test and deploys these
-- together (per-phase deploy gate). Nothing here is applied automatically.
--
-- Depends on: 202607200001_social_graph.sql (direct_conversations, contacts,
-- direct_messages, is_direct_member, pair_blocked, my_direct_conversations).
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- P4: rich group metadata. Icon / cover / description / rules live on the
-- conversation so every member sees the same group identity. rules_reminder_days
-- drives the "review the rules" nudge for members who haven't seen them lately.
-- ---------------------------------------------------------------------------
alter table public.direct_conversations
  add column if not exists icon text not null default '',
  add column if not exists cover_path text,
  add column if not exists description text not null default '',
  add column if not exists rules text not null default '',
  add column if not exists rules_reminder_days integer not null default 7;

alter table public.direct_conversations
  drop constraint if exists direct_conversations_rules_reminder_days_check;
alter table public.direct_conversations
  add constraint direct_conversations_rules_reminder_days_check
  check (rules_reminder_days between 1 and 365);

-- Only the group owner edits group identity. DMs have no editable metadata.
create or replace function public.set_group_meta(
  p_conversation uuid,
  p_name text,
  p_icon text,
  p_cover_path text,
  p_description text,
  p_rules text,
  p_rules_reminder_days integer
)
returns void language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then
    raise exception using errcode = '42501', message = 'authentication_required';
  end if;
  if not exists (
    select 1
    from public.direct_conversation_members m
    join public.direct_conversations c on c.id = m.conversation_id
    where m.conversation_id = p_conversation and c.kind = 'group'
      and m.user_id = auth.uid()
      and m.role = 'owner'
  ) then
    raise exception using errcode = '42501', message = 'owner_only';
  end if;
  update public.direct_conversations c
  set name = coalesce(left(nullif(btrim(p_name), ''), 80), c.name),
      icon = left(coalesce(p_icon, c.icon), 16),
      cover_path = nullif(btrim(coalesce(p_cover_path, '')), ''),
      description = left(coalesce(p_description, c.description), 500),
      rules = left(coalesce(p_rules, c.rules), 4000),
      rules_reminder_days = coalesce(p_rules_reminder_days, c.rules_reminder_days)
  where c.id = p_conversation and c.kind = 'group';
end;
$$;

revoke all on function public.set_group_meta(uuid, text, text, text, text, text, integer) from public;
grant execute on function public.set_group_meta(uuid, text, text, text, text, text, integer) to authenticated;

-- Owner adds a contact to an existing group (same contact + block gates as
-- create_personal_group). Idempotent on (conversation, user).
create or replace function public.add_group_member(p_conversation uuid, p_member uuid)
returns void language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then
    raise exception using errcode = '42501', message = 'authentication_required';
  end if;
  if not exists (
    select 1
    from public.direct_conversation_members m
    join public.direct_conversations c on c.id = m.conversation_id
    where m.conversation_id = p_conversation and c.kind = 'group'
      and m.user_id = auth.uid()
      and m.role = 'owner'
  ) then
    raise exception using errcode = '42501', message = 'owner_only';
  end if;
  if p_member <> auth.uid()
     and exists (
       select 1 from public.contacts c
       where c.owner_id = auth.uid() and c.contact_id = p_member
     )
     and not public.pair_blocked(p_member, auth.uid()) then
    insert into public.direct_conversation_members (conversation_id, user_id)
    values (p_conversation, p_member)
    on conflict do nothing;
  end if;
end;
$$;

revoke all on function public.add_group_member(uuid, uuid) from public;
grant execute on function public.add_group_member(uuid, uuid) to authenticated;

-- Any member leaves a group. The last owner leaving hands ownership to the
-- earliest-joined remaining member so a group is never left ownerless.
create or replace function public.leave_group(p_conversation uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare
  was_owner boolean;
  successor uuid;
begin
  if auth.uid() is null then
    raise exception using errcode = '42501', message = 'authentication_required';
  end if;
  perform 1 from public.direct_conversations c
  where c.id = p_conversation and c.kind = 'group'
  for update;

  select (m.role = 'owner') into was_owner
  from public.direct_conversation_members m
  join public.direct_conversations c on c.id = m.conversation_id
  where m.conversation_id = p_conversation
    and m.user_id = auth.uid()
    and c.kind = 'group';

  if was_owner is null then
    raise exception using errcode = '42501', message = 'group_membership_required';
  end if;

  delete from public.direct_conversation_members
  where conversation_id = p_conversation and user_id = auth.uid();

  if not exists (
    select 1 from public.direct_conversation_members
    where conversation_id = p_conversation
  ) then
    delete from public.direct_conversations where id = p_conversation;
    return;
  end if;

  if coalesce(was_owner, false) then
    if not exists (
      select 1 from public.direct_conversation_members
      where conversation_id = p_conversation and role = 'owner'
    ) then
      select user_id into successor
      from public.direct_conversation_members
      where conversation_id = p_conversation
      order by joined_at asc
      limit 1;
      if successor is not null then
        update public.direct_conversation_members
        set role = 'owner'
        where conversation_id = p_conversation and user_id = successor;
      end if;
    end if;
  end if;
end;
$$;

revoke all on function public.leave_group(uuid) from public;
grant execute on function public.leave_group(uuid) to authenticated;

-- Surface the rich columns to clients. Superset of the original signature; the
-- client maps the extra fields and ignores them for DMs (kind = 'dm').
drop function if exists public.my_direct_conversations();
create function public.my_direct_conversations()
returns table (
  conversation_id uuid,
  kind text,
  name text,
  peer_id uuid,
  peer_handle text,
  peer_display_name text,
  peer_avatar_path text,
  member_count bigint,
  last_message jsonb,
  last_sender uuid,
  last_at timestamptz,
  created_at timestamptz,
  icon text,
  cover_path text,
  description text,
  rules text,
  rules_reminder_days integer,
  my_role text
) language sql stable security definer set search_path = '' as $$
  select
    c.id,
    c.kind,
    c.name,
    peer.user_id,
    p.handle,
    p.display_name,
    p.avatar_path,
    (select count(*) from public.direct_conversation_members mc
      where mc.conversation_id = c.id),
    last.content,
    last.sender_id,
    last.created_at,
    c.created_at,
    c.icon,
    c.cover_path,
    c.description,
    c.rules,
    c.rules_reminder_days,
    me.role
  from public.direct_conversations c
  join public.direct_conversation_members me
    on me.conversation_id = c.id and me.user_id = auth.uid()
  left join lateral (
    select m.user_id from public.direct_conversation_members m
    where m.conversation_id = c.id and m.user_id <> auth.uid() and c.kind = 'dm'
    limit 1
  ) peer on true
  left join public.profiles p on p.id = peer.user_id
  left join lateral (
    select dm.content, dm.sender_id, dm.created_at
    from public.direct_messages dm
    where dm.conversation_id = c.id
    order by dm.created_at desc
    limit 1
  ) last on true
  where c.kind = 'group'
     or peer.user_id is null
     or not public.pair_blocked(peer.user_id, auth.uid())
  order by coalesce(last.created_at, c.created_at) desc;
$$;

revoke all on function public.my_direct_conversations() from public;
grant execute on function public.my_direct_conversations() to authenticated;

-- ---------------------------------------------------------------------------
-- P7: durable 3-stage receipts for account DMs/groups. Clients address
-- optimistic messages by client_sync_id, which both sender and receiver know;
-- the database resolves that stable id to the server message UUID.
-- ---------------------------------------------------------------------------
create table public.direct_message_receipts (
  message_id uuid not null references public.direct_messages(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  delivered_at timestamptz not null default now(),
  viewed_at timestamptz,
  primary key (message_id, user_id)
);

create index direct_message_receipts_user_idx
  on public.direct_message_receipts(user_id, delivered_at desc);

alter table public.direct_message_receipts enable row level security;
create policy direct_receipts_member_read on public.direct_message_receipts
  for select to authenticated
  using (
    (select auth.uid()) = user_id
    or exists (
      select 1 from public.direct_messages dm
      where dm.id = direct_message_receipts.message_id
        and dm.sender_id = (select auth.uid())
    )
  );

-- Receipt writes and aggregates are exposed only through identity-checking RPCs.
revoke all on table public.direct_message_receipts from anon, authenticated;

create or replace function public.record_direct_message_receipts(
  p_message_sync_ids uuid[],
  p_viewed boolean default false
)
returns void language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then
    raise exception using errcode = '42501', message = 'authentication_required';
  end if;
  if coalesce(array_length(p_message_sync_ids, 1), 0) > 500 then
    raise exception using errcode = '22023', message = 'too_many_messages';
  end if;

  insert into public.direct_message_receipts (message_id, user_id, delivered_at, viewed_at)
  select
    dm.id,
    auth.uid(),
    now(),
    case when p_viewed then now() else null end
  from public.direct_messages dm
  where dm.client_sync_id = any(coalesce(p_message_sync_ids, '{}'::uuid[]))
    and dm.sender_id <> auth.uid()
    and public.is_direct_member(dm.conversation_id)
  on conflict (message_id, user_id) do update
  set delivered_at = least(public.direct_message_receipts.delivered_at, excluded.delivered_at),
      viewed_at = case
        when excluded.viewed_at is null then public.direct_message_receipts.viewed_at
        else coalesce(public.direct_message_receipts.viewed_at, excluded.viewed_at)
      end;
end;
$$;

revoke all on function public.record_direct_message_receipts(uuid[], boolean) from public;
grant execute on function public.record_direct_message_receipts(uuid[], boolean) to authenticated;

create or replace function public.direct_message_receipt_summary(p_message_sync_ids uuid[])
returns table (
  message_sync_id uuid,
  delivered_count bigint,
  viewed_count bigint
) language sql stable security definer set search_path = '' as $$
  select
    dm.client_sync_id,
    count(r.user_id),
    count(r.user_id) filter (where r.viewed_at is not null)
  from public.direct_messages dm
  left join public.direct_message_receipts r on r.message_id = dm.id
  where dm.sender_id = auth.uid()
    and dm.client_sync_id = any(coalesce(p_message_sync_ids, '{}'::uuid[]))
  group by dm.id, dm.client_sync_id;
$$;

revoke all on function public.direct_message_receipt_summary(uuid[]) from public;
grant execute on function public.direct_message_receipt_summary(uuid[]) to authenticated;

-- ---------------------------------------------------------------------------
-- P6: message requests. A first DM from someone the recipient has not added
-- creates one durable request. Declining does not silently turn later messages
-- into accepted chat; accepting or replying makes the relationship mutual.
-- ---------------------------------------------------------------------------
alter table public.contacts drop constraint if exists contacts_source_check;
alter table public.contacts
  add constraint contacts_source_check
  check (source in ('handle', 'invite', 'qr', 'reply', 'request'));

create table public.contact_requests (
  id uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references public.direct_conversations(id) on delete cascade,
  sender_id uuid not null references public.profiles(id) on delete cascade,
  recipient_id uuid not null references public.profiles(id) on delete cascade,
  status text not null default 'pending' check (status in ('pending', 'accepted', 'declined')),
  created_at timestamptz not null default now(),
  resolved_at timestamptz,
  unique (conversation_id, recipient_id),
  check (sender_id <> recipient_id)
);

create index contact_requests_recipient_status_idx
  on public.contact_requests(recipient_id, status, created_at desc);
create index contact_requests_sender_idx
  on public.contact_requests(sender_id, created_at desc);

alter table public.contact_requests enable row level security;
create policy contact_requests_participant_read on public.contact_requests
  for select to authenticated
  using ((select auth.uid()) = sender_id or (select auth.uid()) = recipient_id);

-- Requests are consumed through narrow RPCs, not direct table writes.
revoke all on table public.contact_requests from anon, authenticated;

create or replace function public.queue_contact_request_for_dm()
returns trigger language plpgsql security definer set search_path = '' as $$
declare
  recipient uuid;
begin
  select m.user_id into recipient
  from public.direct_conversation_members m
  join public.direct_conversations c on c.id = m.conversation_id
  where m.conversation_id = new.conversation_id
    and c.kind = 'dm'
    and m.user_id <> new.sender_id
  limit 1;

  if recipient is not null
     and not public.pair_blocked(new.sender_id, recipient)
     and not exists (
       select 1 from public.direct_messages earlier
       where earlier.conversation_id = new.conversation_id and earlier.id <> new.id
     )
     and not exists (
       select 1 from public.contacts c
       where c.owner_id = recipient and c.contact_id = new.sender_id
     ) then
    insert into public.contact_requests (conversation_id, sender_id, recipient_id)
    values (new.conversation_id, new.sender_id, recipient)
    on conflict (conversation_id, recipient_id) do nothing;
  end if;
  return new;
end;
$$;

revoke all on function public.queue_contact_request_for_dm() from public, anon, authenticated;

drop trigger if exists queue_contact_request_after_dm on public.direct_messages;
create trigger queue_contact_request_after_dm
  after insert on public.direct_messages
  for each row execute function public.queue_contact_request_for_dm();

create or replace function public.my_contact_requests()
returns table (
  request_id uuid,
  conversation_id uuid,
  sender_id uuid,
  sender_handle text,
  sender_display_name text,
  sender_avatar_path text,
  preview jsonb,
  created_at timestamptz
) language sql stable security definer set search_path = '' as $$
  select
    r.id,
    r.conversation_id,
    r.sender_id,
    p.handle,
    p.display_name,
    p.avatar_path,
    last.content,
    r.created_at
  from public.contact_requests r
  join public.profiles p on p.id = r.sender_id
  left join lateral (
    select dm.content
    from public.direct_messages dm
    where dm.conversation_id = r.conversation_id
    order by dm.created_at desc
    limit 1
  ) last on true
  where r.recipient_id = auth.uid()
    and r.status = 'pending'
    and not public.pair_blocked(r.sender_id, auth.uid())
  order by r.created_at desc;
$$;

revoke all on function public.my_contact_requests() from public;
grant execute on function public.my_contact_requests() to authenticated;

create or replace function public.respond_contact_request(p_request uuid, p_accept boolean)
returns void language plpgsql security definer set search_path = '' as $$
declare
  request public.contact_requests%rowtype;
begin
  if auth.uid() is null then
    raise exception using errcode = '42501', message = 'authentication_required';
  end if;

  select * into request
  from public.contact_requests r
  where r.id = p_request and r.recipient_id = auth.uid() and r.status = 'pending'
  for update;

  if request.id is null then
    raise exception using errcode = 'P0002', message = 'request_not_found';
  end if;

  update public.contact_requests
  set status = case when p_accept then 'accepted' else 'declined' end,
      resolved_at = now()
  where id = request.id;

  if p_accept then
    insert into public.contacts (owner_id, contact_id, source)
    values
      (auth.uid(), request.sender_id, 'request'),
      (request.sender_id, auth.uid(), 'request')
    on conflict (owner_id, contact_id) do nothing;
  end if;
end;
$$;

revoke all on function public.respond_contact_request(uuid, boolean) from public;
grant execute on function public.respond_contact_request(uuid, boolean) to authenticated;

-- Replying is also consent, matching the existing social-graph rule.
create or replace function public.resolve_contact_request_on_reply()
returns trigger language plpgsql security definer set search_path = '' as $$
declare
  pending public.contact_requests%rowtype;
begin
  select * into pending
  from public.contact_requests r
  where r.conversation_id = new.conversation_id
    and r.recipient_id = new.sender_id
    and r.status = 'pending'
  for update;

  if pending.id is not null then
    update public.contact_requests
    set status = 'accepted', resolved_at = now()
    where id = pending.id;
    insert into public.contacts (owner_id, contact_id, source)
    values
      (pending.recipient_id, pending.sender_id, 'reply'),
      (pending.sender_id, pending.recipient_id, 'reply')
    on conflict (owner_id, contact_id) do nothing;
  end if;
  return new;
end;
$$;

revoke all on function public.resolve_contact_request_on_reply() from public, anon, authenticated;

drop trigger if exists resolve_contact_request_after_reply on public.direct_messages;
create trigger resolve_contact_request_after_reply
  after insert on public.direct_messages
  for each row execute function public.resolve_contact_request_on_reply();

-- Re-expose conversations with the inbound request state. Pending/declined
-- conversations remain locally materialized for offline history, but the UI
-- keeps them out of Direct until accepted.
drop function if exists public.my_direct_conversations();
create function public.my_direct_conversations()
returns table (
  conversation_id uuid,
  kind text,
  name text,
  peer_id uuid,
  peer_handle text,
  peer_display_name text,
  peer_avatar_path text,
  member_count bigint,
  last_message jsonb,
  last_sender uuid,
  last_at timestamptz,
  created_at timestamptz,
  icon text,
  cover_path text,
  description text,
  rules text,
  rules_reminder_days integer,
  my_role text,
  inbound_request_status text
) language sql stable security definer set search_path = '' as $$
  select
    c.id,
    c.kind,
    c.name,
    peer.user_id,
    p.handle,
    p.display_name,
    p.avatar_path,
    (select count(*) from public.direct_conversation_members mc
      where mc.conversation_id = c.id),
    last.content,
    last.sender_id,
    last.created_at,
    c.created_at,
    c.icon,
    c.cover_path,
    c.description,
    c.rules,
    c.rules_reminder_days,
    me.role,
    request.status
  from public.direct_conversations c
  join public.direct_conversation_members me
    on me.conversation_id = c.id and me.user_id = auth.uid()
  left join lateral (
    select m.user_id from public.direct_conversation_members m
    where m.conversation_id = c.id and m.user_id <> auth.uid() and c.kind = 'dm'
    limit 1
  ) peer on true
  left join public.profiles p on p.id = peer.user_id
  left join lateral (
    select dm.content, dm.sender_id, dm.created_at
    from public.direct_messages dm
    where dm.conversation_id = c.id
    order by dm.created_at desc
    limit 1
  ) last on true
  left join lateral (
    select r.status
    from public.contact_requests r
    where r.conversation_id = c.id and r.recipient_id = auth.uid()
    limit 1
  ) request on true
  where c.kind = 'group'
     or peer.user_id is null
     or not public.pair_blocked(peer.user_id, auth.uid())
  order by coalesce(last.created_at, c.created_at) desc;
$$;

revoke all on function public.my_direct_conversations() from public;
grant execute on function public.my_direct_conversations() to authenticated;
