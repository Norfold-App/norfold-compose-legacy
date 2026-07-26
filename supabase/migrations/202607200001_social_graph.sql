-- Account-level social graph (chat overhaul Phase 2)
-- 2026-07-20 — WRITTEN, NOT DEPLOYED. Deploy together with the client build that
-- ships contacts + personal DMs.
--
-- Model: invite-only contacts with NO request inbox. A contact edge is
-- one-directional ("I know you"); a friendship is simply both directions
-- existing (mutual). Mutuality is what unlocks notifications client-side
-- (silent stranger-DM rule) — delivery itself is never blocked except by an
-- explicit block. Edges appear three ways:
--   1. add_contact_by_handle: you typed their unique @handle
--   2. redeem_friend_invite: you opened their invite link / QR (creates BOTH
--      edges at once — handing someone your invite is consent)
--   3. reply: sending any personal DM auto-creates your outbound edge, so
--      answering a stranger makes the pair mutual without extra ceremony
--
-- Personal conversations (kind 'dm' | 'group') live OUTSIDE workspaces and
-- follow the HARD privacy pattern from 202607190001: readable only by their
-- members, enforced by RLS with security-definer helpers.

-- ---------------------------------------------------------------------------
-- Contacts: one-directional edges; mutual == friendship
-- ---------------------------------------------------------------------------
create table public.contacts (
  owner_id uuid not null references public.profiles(id) on delete cascade,
  contact_id uuid not null references public.profiles(id) on delete cascade,
  source text not null default 'handle'
    check (source in ('handle', 'invite', 'qr', 'reply')),
  created_at timestamptz not null default now(),
  primary key (owner_id, contact_id),
  check (owner_id <> contact_id)
);

create index contacts_reverse_idx on public.contacts(contact_id, owner_id);

alter table public.contacts enable row level security;

-- You manage only your own edges. Reverse edges (who added you) are never
-- directly readable — mutuality is exposed through my_contacts() so the graph
-- can't be scraped.
create policy contacts_owner_read on public.contacts
  for select using (owner_id = auth.uid());
create policy contacts_owner_delete on public.contacts
  for delete using (owner_id = auth.uid());
-- Inserts go through the RPCs below (block checks live there); no direct policy.

-- ---------------------------------------------------------------------------
-- Blocks: hard stop for delivery and discovery
-- ---------------------------------------------------------------------------
create table public.blocked_users (
  owner_id uuid not null references public.profiles(id) on delete cascade,
  blocked_id uuid not null references public.profiles(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (owner_id, blocked_id),
  check (owner_id <> blocked_id)
);

alter table public.blocked_users enable row level security;

create policy blocked_owner_all on public.blocked_users
  for all using (owner_id = auth.uid()) with check (owner_id = auth.uid());

-- Either direction blocked? (security definer: reads rows neither side owns)
create or replace function public.pair_blocked(user_a uuid, user_b uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.blocked_users b
    where (b.owner_id = user_a and b.blocked_id = user_b)
       or (b.owner_id = user_b and b.blocked_id = user_a)
  );
$$;

revoke all on function public.pair_blocked(uuid, uuid) from public;
grant execute on function public.pair_blocked(uuid, uuid) to authenticated;

-- ---------------------------------------------------------------------------
-- Profile visibility: contacts can see each other (extends co-member rule)
-- ---------------------------------------------------------------------------
drop policy if exists profiles_member_read on public.profiles;
create policy profiles_member_read on public.profiles for select using (
  id = auth.uid()
  or (
    not public.pair_blocked(id, auth.uid())
    and (
      public.shares_workspace(id)
      or exists (
        select 1 from public.contacts c
        where (c.owner_id = auth.uid() and c.contact_id = profiles.id)
           or (c.owner_id = profiles.id and c.contact_id = auth.uid())
      )
    )
  )
);

-- ---------------------------------------------------------------------------
-- Friend invites: single-use tokens for link / QR sharing
-- ---------------------------------------------------------------------------
create table public.friend_invites (
  id uuid primary key default gen_random_uuid(),
  inviter_id uuid not null references public.profiles(id) on delete cascade,
  token text not null unique,
  created_at timestamptz not null default now(),
  expires_at timestamptz not null default now() + interval '7 days',
  redeemed_by uuid references public.profiles(id),
  redeemed_at timestamptz
);

alter table public.friend_invites enable row level security;

create policy friend_invites_owner_read on public.friend_invites
  for select using (inviter_id = auth.uid());
create policy friend_invites_owner_delete on public.friend_invites
  for delete using (inviter_id = auth.uid());
-- Creation and redemption go through the RPCs (token generation server-side).

create or replace function public.create_friend_invite()
returns text language plpgsql security definer set search_path = '' as $$
declare
  invite_token text;
begin
  if auth.uid() is null then
    raise exception using errcode = '42501', message = 'authentication_required';
  end if;
  -- 24 url-safe chars; unguessable, single-use, 7-day expiry.
  invite_token := encode(extensions.gen_random_bytes(18), 'base64');
  invite_token := translate(invite_token, '+/=', '-_');
  insert into public.friend_invites (inviter_id, token)
  values (auth.uid(), invite_token);
  return invite_token;
end;
$$;

grant execute on function public.create_friend_invite() to authenticated;

create or replace function public.redeem_friend_invite(p_token text)
returns uuid language plpgsql security definer set search_path = '' as $$
declare
  invite record;
begin
  if auth.uid() is null then
    raise exception using errcode = '42501', message = 'authentication_required';
  end if;
  select * into invite from public.friend_invites
  where token = btrim(p_token)
    and redeemed_at is null
    and expires_at > now()
  for update;
  if invite is null then
    raise exception using errcode = 'P0002', message = 'invite_invalid';
  end if;
  if invite.inviter_id = auth.uid() then
    raise exception using errcode = '22023', message = 'own_invite';
  end if;
  if public.pair_blocked(invite.inviter_id, auth.uid()) then
    -- Indistinguishable from an expired invite: blocks must not leak.
    raise exception using errcode = 'P0002', message = 'invite_invalid';
  end if;

  update public.friend_invites
  set redeemed_by = auth.uid(), redeemed_at = now()
  where id = invite.id;

  -- Handing out your invite is consent: both edges at once, instantly mutual.
  insert into public.contacts (owner_id, contact_id, source)
  values
    (auth.uid(), invite.inviter_id, 'invite'),
    (invite.inviter_id, auth.uid(), 'invite')
  on conflict (owner_id, contact_id) do nothing;

  return invite.inviter_id;
end;
$$;

grant execute on function public.redeem_friend_invite(text) to authenticated;

create or replace function public.add_contact_by_handle(p_handle text)
returns uuid language plpgsql security definer set search_path = '' as $$
declare
  target uuid;
begin
  if auth.uid() is null then
    raise exception using errcode = '42501', message = 'authentication_required';
  end if;
  select id into target from public.profiles
  where lower(handle) = lower(btrim(p_handle));
  -- Unknown handle and blocked pair answer identically: nothing to leak.
  if target is null or public.pair_blocked(target, auth.uid()) then
    raise exception using errcode = 'P0002', message = 'handle_not_found';
  end if;
  if target = auth.uid() then
    raise exception using errcode = '22023', message = 'own_handle';
  end if;

  insert into public.contacts (owner_id, contact_id, source)
  values (auth.uid(), target, 'handle')
  on conflict (owner_id, contact_id) do nothing;

  return target;
end;
$$;

grant execute on function public.add_contact_by_handle(text) to authenticated;

-- Contact list with the mutual flag resolved server-side (reverse edges are
-- not directly readable under RLS).
create or replace function public.my_contacts()
returns table (
  contact_id uuid,
  handle text,
  display_name text,
  avatar_path text,
  source text,
  mutual boolean,
  created_at timestamptz
) language sql stable security definer set search_path = '' as $$
  select
    c.contact_id,
    p.handle,
    p.display_name,
    p.avatar_path,
    c.source,
    exists (
      select 1 from public.contacts r
      where r.owner_id = c.contact_id and r.contact_id = c.owner_id
    ) as mutual,
    c.created_at
  from public.contacts c
  join public.profiles p on p.id = c.contact_id
  where c.owner_id = auth.uid()
    and not public.pair_blocked(c.contact_id, auth.uid())
  order by p.display_name, p.handle;
$$;

revoke all on function public.my_contacts() from public;
grant execute on function public.my_contacts() to authenticated;

-- ---------------------------------------------------------------------------
-- Personal conversations: account-level DMs and groups
-- ---------------------------------------------------------------------------
create table public.direct_conversations (
  id uuid primary key default gen_random_uuid(),
  kind text not null check (kind in ('dm', 'group')),
  name text not null default '',
  created_by uuid not null references public.profiles(id),
  -- Deduplicates the 1:1 pair: 'low-uuid:high-uuid'. Null for groups.
  dm_key text unique,
  created_at timestamptz not null default now()
);

create table public.direct_conversation_members (
  conversation_id uuid not null references public.direct_conversations(id) on delete cascade,
  user_id uuid not null references public.profiles(id) on delete cascade,
  role text not null default 'member' check (role in ('owner', 'member')),
  joined_at timestamptz not null default now(),
  primary key (conversation_id, user_id)
);

create index direct_conversation_members_user_idx
  on public.direct_conversation_members(user_id);

create table public.direct_messages (
  id uuid primary key default gen_random_uuid(),
  conversation_id uuid not null references public.direct_conversations(id) on delete cascade,
  sender_id uuid not null references public.profiles(id),
  client_sync_id uuid not null,
  content jsonb not null,
  created_at timestamptz not null default now(),
  -- Idempotent sends: a retry with the same client id can't duplicate.
  unique (conversation_id, client_sync_id)
);

create index direct_messages_conversation_idx
  on public.direct_messages(conversation_id, created_at);

alter table public.direct_conversations enable row level security;
alter table public.direct_conversation_members enable row level security;
alter table public.direct_messages enable row level security;

create or replace function public.is_direct_member(target_conversation uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.direct_conversation_members m
    where m.conversation_id = target_conversation and m.user_id = auth.uid()
  );
$$;

revoke all on function public.is_direct_member(uuid) from public;
grant execute on function public.is_direct_member(uuid) to authenticated;

create policy direct_conversations_member_read on public.direct_conversations
  for select using (public.is_direct_member(id));

create policy direct_members_member_read on public.direct_conversation_members
  for select using (public.is_direct_member(conversation_id));

-- Group owners manage the roster; leaving is always allowed.
create policy direct_members_owner_insert on public.direct_conversation_members
  for insert with check (
    exists (
      select 1 from public.direct_conversation_members o
      where o.conversation_id = direct_conversation_members.conversation_id
        and o.user_id = auth.uid() and o.role = 'owner'
    )
    and not public.pair_blocked(user_id, auth.uid())
  );
create policy direct_members_leave_delete on public.direct_conversation_members
  for delete using (
    user_id = auth.uid()
    or exists (
      select 1 from public.direct_conversation_members o
      where o.conversation_id = direct_conversation_members.conversation_id
        and o.user_id = auth.uid() and o.role = 'owner'
    )
  );

create policy direct_messages_member_read on public.direct_messages
  for select using (public.is_direct_member(conversation_id));

-- Sending: you are a member, you are the sender, and (for DMs) the pair is not
-- blocked. Delivery is otherwise never gated — the silent-stranger rule is a
-- client-side notification rule, not a delivery rule.
create policy direct_messages_member_insert on public.direct_messages
  for insert with check (
    sender_id = auth.uid()
    and public.is_direct_member(conversation_id)
    and not exists (
      select 1 from public.direct_conversation_members peer
      where peer.conversation_id = direct_messages.conversation_id
        and peer.user_id <> auth.uid()
        and public.pair_blocked(peer.user_id, auth.uid())
    )
  );

alter publication supabase_realtime add table public.direct_messages;

-- Opens (or returns) the 1:1 conversation with a peer. Anyone reachable —
-- a contact, or any profile you can see — can be messaged; mutuality only
-- controls notifications on the receiving device.
create or replace function public.open_direct_conversation(p_peer uuid)
returns uuid language plpgsql security definer set search_path = '' as $$
declare
  pair_key text;
  conversation uuid;
begin
  if auth.uid() is null then
    raise exception using errcode = '42501', message = 'authentication_required';
  end if;
  if p_peer is null or p_peer = auth.uid() then
    raise exception using errcode = '22023', message = 'invalid_peer';
  end if;
  if not exists (select 1 from public.profiles where id = p_peer)
     or public.pair_blocked(p_peer, auth.uid()) then
    raise exception using errcode = 'P0002', message = 'peer_not_found';
  end if;

  pair_key := least(auth.uid()::text, p_peer::text) || ':' ||
              greatest(auth.uid()::text, p_peer::text);

  select id into conversation from public.direct_conversations where dm_key = pair_key;
  if conversation is not null then
    return conversation;
  end if;

  insert into public.direct_conversations (kind, created_by, dm_key)
  values ('dm', auth.uid(), pair_key)
  returning id into conversation;

  insert into public.direct_conversation_members (conversation_id, user_id, role)
  values (conversation, auth.uid(), 'owner'), (conversation, p_peer, 'member');

  return conversation;
exception
  when unique_violation then
    -- Concurrent open from both sides: the other insert won; return it.
    select id into conversation from public.direct_conversations where dm_key = pair_key;
    return conversation;
end;
$$;

grant execute on function public.open_direct_conversation(uuid) to authenticated;

-- Personal group: creator becomes owner; initial members must be the creator's
-- contacts (invite-only spirit — you can't bulk-add strangers to a group).
create or replace function public.create_personal_group(p_name text, p_member_ids uuid[])
returns uuid language plpgsql security definer set search_path = '' as $$
declare
  conversation uuid;
  member uuid;
begin
  if auth.uid() is null then
    raise exception using errcode = '42501', message = 'authentication_required';
  end if;
  if btrim(coalesce(p_name, '')) = '' then
    raise exception using errcode = '22023', message = 'name_required';
  end if;

  insert into public.direct_conversations (kind, name, created_by)
  values ('group', left(btrim(p_name), 80), auth.uid())
  returning id into conversation;

  insert into public.direct_conversation_members (conversation_id, user_id, role)
  values (conversation, auth.uid(), 'owner');

  foreach member in array coalesce(p_member_ids, '{}') loop
    if member <> auth.uid()
       and exists (
         select 1 from public.contacts c
         where c.owner_id = auth.uid() and c.contact_id = member
       )
       and not public.pair_blocked(member, auth.uid()) then
      insert into public.direct_conversation_members (conversation_id, user_id)
      values (conversation, member)
      on conflict do nothing;
    end if;
  end loop;

  return conversation;
end;
$$;

grant execute on function public.create_personal_group(text, uuid[]) to authenticated;

-- ---------------------------------------------------------------------------
-- Reply == consent: sending a personal DM creates your outbound contact edge,
-- so answering a stranger makes the pair mutual with zero extra steps.
-- ---------------------------------------------------------------------------
create or replace function public.record_dm_reply_contact()
returns trigger language plpgsql security definer set search_path = '' as $$
declare
  peer uuid;
begin
  select m.user_id into peer
  from public.direct_conversation_members m
  join public.direct_conversations c on c.id = m.conversation_id
  where m.conversation_id = new.conversation_id
    and c.kind = 'dm'
    and m.user_id <> new.sender_id
  limit 1;

  if peer is not null then
    insert into public.contacts (owner_id, contact_id, source)
    values (new.sender_id, peer, 'reply')
    on conflict (owner_id, contact_id) do nothing;
  end if;
  return new;
end;
$$;

create trigger direct_message_reply_contact
  after insert on public.direct_messages
  for each row execute function public.record_dm_reply_contact();

-- The conversation list with everything the inbox row needs in one call:
-- peers, last message, and timestamps. Ordered by recency.
create or replace function public.my_direct_conversations()
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
  created_at timestamptz
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
    c.created_at
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
