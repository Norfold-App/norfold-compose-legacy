-- Message receipts (chat overhaul Phase 3)
-- 2026-07-20 — WRITTEN, NOT DEPLOYED. Deploy with the client build that ships
-- 3-stage receipts.
--
-- Presence (online / away / offline) is Supabase Realtime presence channels —
-- ephemeral, no table. This migration only adds the durable half: per-user
-- delivery/viewed receipts for workspace chat messages.
--
-- Stages: sent (the message row exists) → delivered (a recipient's device
-- imported it) → viewed (the recipient opened the conversation). A receipt row
-- upgrades in place; status can only move forward. Receipts also become the
-- deletion trigger for the Phase-5 relay model: once every recipient has a
-- delivered receipt, the server copy is eligible for purge.

create table public.message_receipts (
  workspace_id uuid not null references public.workspaces(id) on delete cascade,
  message_sync_id uuid not null,
  user_id uuid not null references public.profiles(id) on delete cascade,
  status text not null check (status in ('delivered', 'viewed')),
  delivered_at timestamptz not null default now(),
  viewed_at timestamptz,
  primary key (workspace_id, message_sync_id, user_id)
);

create index message_receipts_message_idx
  on public.message_receipts(workspace_id, message_sync_id);

alter table public.message_receipts enable row level security;

-- Any workspace member can see receipt state (that's the point of ticks).
create policy message_receipts_member_read on public.message_receipts
  for select using (public.is_workspace_member(workspace_id));

-- You write only your own receipts, and only in workspaces you belong to.
create policy message_receipts_self_insert on public.message_receipts
  for insert with check (
    user_id = auth.uid() and public.is_workspace_member(workspace_id)
  );
create policy message_receipts_self_update on public.message_receipts
  for update using (user_id = auth.uid())
  with check (user_id = auth.uid());

alter publication supabase_realtime add table public.message_receipts;

-- Forward-only upsert: delivered can become viewed; viewed never regresses.
-- Batched (arrays) so a catch-up sync marks a whole backlog in one round-trip.
create or replace function public.record_message_receipts(
  p_workspace uuid, p_message_ids uuid[], p_status text
) returns void language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then
    raise exception using errcode = '42501', message = 'authentication_required';
  end if;
  if p_status not in ('delivered', 'viewed') then
    raise exception using errcode = '22023', message = 'invalid_status';
  end if;
  if not public.is_workspace_member(p_workspace) then
    raise exception using errcode = '42501', message = 'not_a_member';
  end if;

  insert into public.message_receipts (workspace_id, message_sync_id, user_id, status, viewed_at)
  select p_workspace, m.id, auth.uid(), p_status,
         case when p_status = 'viewed' then now() end
  from unnest(coalesce(p_message_ids, '{}')) as m(id)
  on conflict (workspace_id, message_sync_id, user_id) do update
    set status = 'viewed',
        viewed_at = coalesce(public.message_receipts.viewed_at, now())
    where excluded.status = 'viewed'
      and public.message_receipts.status = 'delivered';
end;
$$;

grant execute on function public.record_message_receipts(uuid, uuid[], text) to authenticated;

-- Aggregate receipt state for a batch of messages, one row per message:
-- how many recipients delivered / viewed. The sender's client turns this into
-- ticks (all delivered → double tick, all viewed → colored double tick).
create or replace function public.message_receipt_summary(
  p_workspace uuid, p_message_ids uuid[]
) returns table (
  message_sync_id uuid,
  delivered_count bigint,
  viewed_count bigint
) language sql stable security definer set search_path = '' as $$
  select m.id,
         count(r.user_id) filter (where r.user_id is not null),
         count(r.user_id) filter (where r.status = 'viewed')
  from unnest(coalesce(p_message_ids, '{}')) as m(id)
  left join public.message_receipts r
    on r.workspace_id = p_workspace and r.message_sync_id = m.id
  where public.is_workspace_member(p_workspace)
  group by m.id;
$$;

revoke all on function public.message_receipt_summary(uuid, uuid[]) from public;
grant execute on function public.message_receipt_summary(uuid, uuid[]) to authenticated;
