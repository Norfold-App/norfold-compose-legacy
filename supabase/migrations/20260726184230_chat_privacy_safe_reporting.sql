-- Privacy-safe Chat reporting. Reports reference an authenticated message and
-- retain only server-derived routing metadata. E2EE plaintext is never copied
-- into moderation storage; the reporter may add a short, explicit note.

create table public.chat_abuse_reports (
  id uuid primary key default gen_random_uuid(),
  reporter_id uuid not null references public.profiles(id) on delete cascade,
  reported_user_id uuid not null references public.profiles(id) on delete cascade,
  security_epoch_id uuid not null references public.chat_security_epochs(id) on delete restrict,
  account_conversation_id uuid references public.direct_conversations(id) on delete set null,
  workspace_id uuid references public.workspaces(id) on delete set null,
  channel_id uuid,
  message_sync_id uuid not null,
  transport_mode text not null check (transport_mode in ('e2ee', 'managed')),
  category text not null check (category in (
    'spam', 'harassment', 'hate', 'sexual_content', 'violence',
    'impersonation', 'scam', 'self_harm', 'other'
  )),
  reporter_note text not null default '' check (char_length(reporter_note) <= 1000),
  evidence_mode text not null default 'metadata_only'
    check (evidence_mode = 'metadata_only'),
  evidence_digest text,
  status text not null default 'submitted'
    check (status in ('submitted', 'triaged', 'actioned', 'dismissed')),
  blocked_at_submission boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  expires_at timestamptz not null default (now() + interval '90 days'),
  reviewed_at timestamptz,
  reviewed_by uuid references public.profiles(id) on delete set null,
  check (
    (account_conversation_id is not null and workspace_id is null)
    or (workspace_id is not null and account_conversation_id is null)
  ),
  unique (reporter_id, security_epoch_id, message_sync_id)
);

create index chat_abuse_reports_queue_idx
  on public.chat_abuse_reports(status, created_at)
  where status in ('submitted', 'triaged');
create index chat_abuse_reports_reporter_idx
  on public.chat_abuse_reports(reporter_id, created_at desc);
create index chat_abuse_reports_target_idx
  on public.chat_abuse_reports(reported_user_id, created_at desc);
create index chat_abuse_reports_expiry_idx
  on public.chat_abuse_reports(expires_at)
  where status in ('actioned', 'dismissed');

alter table public.chat_abuse_reports enable row level security;
revoke all on public.chat_abuse_reports from public, anon, authenticated;

create or replace function private.touch_chat_abuse_report()
returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

revoke all on function private.touch_chat_abuse_report()
  from public, anon, authenticated;

create trigger touch_chat_abuse_report
before update on public.chat_abuse_reports
for each row execute function private.touch_chat_abuse_report();

create or replace function public.submit_chat_abuse_report(
  p_message_sync_id uuid,
  p_security_epoch uuid,
  p_category text,
  p_reporter_note text default '',
  p_also_block boolean default false
) returns uuid
language plpgsql security definer set search_path = '' as $$
declare
  v_epoch public.chat_security_epochs%rowtype;
  v_author uuid;
  v_mode text;
  v_digest text;
  v_report uuid;
  v_note text := btrim(coalesce(p_reporter_note, ''));
  v_recent_count integer;
begin
  if auth.uid() is null then
    raise exception using errcode = '42501', message = 'authentication_required';
  end if;
  if p_category not in (
    'spam', 'harassment', 'hate', 'sexual_content', 'violence',
    'impersonation', 'scam', 'self_harm', 'other'
  ) then
    raise exception using errcode = '22023', message = 'invalid_report_category';
  end if;
  if char_length(v_note) > 1000 then
    raise exception using errcode = '22001', message = 'report_note_too_long';
  end if;

  select * into v_epoch
  from public.chat_security_epochs e
  where e.id = p_security_epoch;
  if v_epoch.id is null
     or not private.can_access_security_epoch(v_epoch.id, auth.uid()) then
    raise exception using errcode = '42501', message = 'message_access_required';
  end if;

  -- E2EE reports prove the referenced relay existed while preserving only a
  -- digest of its opaque envelope. No ciphertext or decrypted text is copied.
  select m.sender_id, 'e2ee',
         encode(extensions.digest(m.envelope::text, 'sha256'), 'hex')
    into v_author, v_mode, v_digest
  from public.chat_e2ee_relay_messages m
  where m.security_epoch_id = p_security_epoch
    and m.client_sync_id = p_message_sync_id
  limit 1;

  if v_author is null then
    -- A destructive tombstone remains reportable after content expiry/unsend.
    select t.created_by, v_epoch.mode::text,
           encode(extensions.digest(
             concat_ws(':', t.object_id::text, t.security_epoch_id::text, t.reason),
             'sha256'
           ), 'hex')
      into v_author, v_mode, v_digest
    from public.chat_object_tombstones t
    where t.security_epoch_id = p_security_epoch
      and t.object_id = p_message_sync_id
    limit 1;
  end if;

  if v_author is null and v_epoch.scope = 'workspace' and v_epoch.mode = 'managed' then
    select o.created_by, 'managed',
           encode(extensions.digest(
             concat_ws(':', o.id::text, o.client_sync_id::text, o.server_version::text),
             'sha256'
           ), 'hex')
      into v_author, v_mode, v_digest
    from public.workspace_objects o
    where o.workspace_id = v_epoch.workspace_id
      and o.client_sync_id = p_message_sync_id
      and o.object_type = 'chat_message'
    limit 1;
  end if;

  if v_author is null then
    raise exception using errcode = 'P0002', message = 'reportable_message_not_found';
  end if;
  if v_author = auth.uid() then
    raise exception using errcode = '22023', message = 'cannot_report_own_message';
  end if;

  select count(*) into v_recent_count
  from public.chat_abuse_reports r
  where r.reporter_id = auth.uid()
    and r.created_at >= now() - interval '24 hours';
  if v_recent_count >= 10 then
    raise exception using errcode = '54000', message = 'report_rate_limit';
  end if;

  insert into public.chat_abuse_reports (
    reporter_id, reported_user_id, security_epoch_id,
    account_conversation_id, workspace_id, channel_id,
    message_sync_id, transport_mode, category, reporter_note,
    evidence_digest, blocked_at_submission
  ) values (
    auth.uid(), v_author, v_epoch.id,
    v_epoch.account_conversation_id, v_epoch.workspace_id, v_epoch.channel_id,
    p_message_sync_id, v_mode, p_category, v_note,
    v_digest, p_also_block
  )
  on conflict (reporter_id, security_epoch_id, message_sync_id) do update
  set category = excluded.category,
      reporter_note = excluded.reporter_note,
      blocked_at_submission = excluded.blocked_at_submission,
      status = case
        when public.chat_abuse_reports.status = 'dismissed' then 'submitted'
        else public.chat_abuse_reports.status
      end,
      expires_at = now() + interval '90 days'
  returning id into v_report;

  if p_also_block then
    insert into public.blocked_users (owner_id, blocked_id)
    values (auth.uid(), v_author)
    on conflict (owner_id, blocked_id) do nothing;
  end if;

  return v_report;
end;
$$;

revoke all on function public.submit_chat_abuse_report(uuid, uuid, text, text, boolean)
  from public, anon;
grant execute on function public.submit_chat_abuse_report(uuid, uuid, text, text, boolean)
  to authenticated;

create or replace function public.my_chat_abuse_report_receipts(
  p_limit integer default 50
) returns table (
  report_id uuid,
  message_sync_id uuid,
  reported_user_id uuid,
  category text,
  status text,
  blocked_at_submission boolean,
  created_at timestamptz,
  updated_at timestamptz
)
language sql stable security definer set search_path = '' as $$
  select r.id, r.message_sync_id, r.reported_user_id, r.category, r.status,
         r.blocked_at_submission, r.created_at, r.updated_at
  from public.chat_abuse_reports r
  where r.reporter_id = auth.uid()
  order by r.created_at desc
  limit greatest(1, least(p_limit, 200));
$$;

revoke all on function public.my_chat_abuse_report_receipts(integer)
  from public, anon;
grant execute on function public.my_chat_abuse_report_receipts(integer)
  to authenticated;

create or replace function private.purge_expired_chat_abuse_reports()
returns integer
language plpgsql security definer set search_path = '' as $$
declare
  v_count integer;
begin
  with removed as (
    delete from public.chat_abuse_reports r
    where r.expires_at <= now()
      and r.status in ('actioned', 'dismissed')
    returning 1
  ) select count(*) into v_count from removed;
  return v_count;
end;
$$;

revoke all on function private.purge_expired_chat_abuse_reports()
  from public, anon, authenticated;

do $$
begin
  if exists (select 1 from pg_extension where extname = 'pg_cron') then
    if not exists (select 1 from cron.job where jobname = 'purge-expired-chat-abuse-reports') then
      perform cron.schedule(
        'purge-expired-chat-abuse-reports',
        '17 4 * * *',
        'select private.purge_expired_chat_abuse_reports()'
      );
    end if;
  end if;
end
$$;
