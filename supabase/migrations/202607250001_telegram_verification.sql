-- Telegram phone verification (account-level phone identity)
-- 2026-07-25 — verifies a user's phone via @Norfold_auth_bot's request_contact
-- flow. No SMS, no Telegram OIDC. The bot only ever proves "this Telegram
-- account, whose contact card Telegram itself vouches for, owns this number".
--
-- Trust model:
--   * A short-lived session is created by an AUTHENTICATED Norfold user
--     (edge fn telegram-verification-start). The raw /start token is returned
--     to that user once and NEVER stored — only its SHA-256 hash lives here.
--   * The webhook (edge fn telegram-auth-webhook) runs as the service role and
--     is the ONLY writer of verified state. Clients can read their own rows but
--     can never mark themselves verified or set provider_subject/phone.
--   * telegram_complete_verification() is the single atomic commit point:
--     phone uniqueness, provider-subject uniqueness, expiry and idempotency are
--     all enforced inside one transaction with the session row locked.

-- ---------------------------------------------------------------------------
-- Authoritative verified-phone identity (one per account)
-- ---------------------------------------------------------------------------
create table public.account_phone_identities (
  user_id uuid primary key references public.profiles(id) on delete cascade,
  phone_e164 text not null unique,
  verification_provider text not null default 'telegram'
    check (verification_provider in ('telegram')),
  provider_subject text not null,
  verified_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  -- The same Telegram account cannot back two different Norfold accounts.
  unique (verification_provider, provider_subject)
);

alter table public.account_phone_identities enable row level security;

-- You may read only your own verified phone. Writes are service-role only
-- (no INSERT/UPDATE/DELETE policy → PostgREST denies them for anon/authed).
create policy account_phone_self_read on public.account_phone_identities
  for select using (user_id = auth.uid());

-- ---------------------------------------------------------------------------
-- Verification sessions (short-lived, one active per user)
-- ---------------------------------------------------------------------------
create table public.telegram_verification_sessions (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references public.profiles(id) on delete cascade,
  -- SHA-256(raw start token) as lowercase hex. Raw token never stored.
  token_hash text not null unique,
  telegram_user_id bigint,
  telegram_chat_id bigint,
  phone_e164 text,
  status text not null default 'PENDING'
    check (status in ('PENDING', 'STARTED', 'VERIFIED', 'EXPIRED', 'CANCELLED')),
  created_at timestamptz not null default now(),
  expires_at timestamptz not null,
  verified_at timestamptz
);

create index telegram_sessions_user_idx on public.telegram_verification_sessions(user_id);
create index telegram_sessions_tg_user_idx on public.telegram_verification_sessions(telegram_user_id);

alter table public.telegram_verification_sessions enable row level security;

-- The client polls / subscribes to its own session to learn the outcome. It
-- can never see token_hash of others, and can never write (all state moves via
-- the RPCs / service role below).
create policy telegram_sessions_self_read on public.telegram_verification_sessions
  for select using (user_id = auth.uid());

-- ---------------------------------------------------------------------------
-- Webhook idempotency: Telegram retries deliveries, so every update_id is
-- recorded once and replays become no-ops.
-- ---------------------------------------------------------------------------
create table public.telegram_processed_updates (
  update_id bigint primary key,
  processed_at timestamptz not null default now()
);

alter table public.telegram_processed_updates enable row level security;
-- No policies: only the service role (webhook) touches this table.

-- ---------------------------------------------------------------------------
-- RPC: create a session for the authenticated caller.
-- SECURITY DEFINER so it can expire the caller's older sessions atomically and
-- keeps user_id bound to auth.uid() — the client can never forge it.
-- ---------------------------------------------------------------------------
create or replace function public.telegram_start_verification(
  p_token_hash text,
  p_ttl_seconds integer default 600
)
returns public.telegram_verification_sessions
language plpgsql
security definer
set search_path = public
as $$
declare
  v_uid uuid := auth.uid();
  v_row public.telegram_verification_sessions;
begin
  if v_uid is null then
    raise exception 'authentication required' using errcode = '28000';
  end if;
  if p_token_hash is null or length(p_token_hash) <> 64 then
    raise exception 'invalid token hash';
  end if;

  -- Abandon any still-open sessions for this user; only one link is live at a time.
  update public.telegram_verification_sessions
     set status = 'CANCELLED'
   where user_id = v_uid
     and status in ('PENDING', 'STARTED');

  insert into public.telegram_verification_sessions (user_id, token_hash, expires_at)
  values (v_uid, p_token_hash, now() + make_interval(secs => greatest(60, least(p_ttl_seconds, 900))))
  returning * into v_row;

  return v_row;
end;
$$;

revoke all on function public.telegram_start_verification(text, integer) from public;
grant execute on function public.telegram_start_verification(text, integer) to authenticated;

-- ---------------------------------------------------------------------------
-- RPC: atomically complete verification. Called by the webhook (service role).
-- Returns a short status string the webhook maps to a bot reply:
--   'VERIFIED'            — success (or idempotent replay of the same success)
--   'PHONE_ALREADY_LINKED'— phone belongs to a different Norfold account
--   'SUBJECT_CONFLICT'    — this Telegram account already backs another account
--   'EXPIRED'             — session past expiry
--   'NOT_FOUND'           — no matching STARTED session for this token+TG user
-- ---------------------------------------------------------------------------
create or replace function public.telegram_complete_verification(
  p_token_hash text,
  p_telegram_user_id bigint,
  p_phone_e164 text
)
returns text
language plpgsql
security definer
set search_path = public
as $$
declare
  v_session public.telegram_verification_sessions;
  v_phone_owner uuid;
  v_subject_owner uuid;
begin
  -- Lock the session row for the duration of the transaction.
  select * into v_session
    from public.telegram_verification_sessions
   where token_hash = p_token_hash
   for update;

  if not found then
    return 'NOT_FOUND';
  end if;

  -- Idempotent replay: Telegram redelivered the same contact update.
  if v_session.status = 'VERIFIED' then
    if v_session.phone_e164 = p_phone_e164
       and v_session.telegram_user_id = p_telegram_user_id then
      return 'VERIFIED';
    end if;
    return 'NOT_FOUND';
  end if;

  if v_session.status <> 'STARTED'
     or v_session.telegram_user_id is distinct from p_telegram_user_id then
    return 'NOT_FOUND';
  end if;

  if v_session.expires_at <= now() then
    update public.telegram_verification_sessions
       set status = 'EXPIRED'
     where id = v_session.id;
    return 'EXPIRED';
  end if;

  -- Phone must not already belong to a different account.
  select user_id into v_phone_owner
    from public.account_phone_identities
   where phone_e164 = p_phone_e164;
  if v_phone_owner is not null and v_phone_owner <> v_session.user_id then
    return 'PHONE_ALREADY_LINKED';
  end if;

  -- This Telegram identity must not already back a different account.
  select user_id into v_subject_owner
    from public.account_phone_identities
   where verification_provider = 'telegram'
     and provider_subject = p_telegram_user_id::text;
  if v_subject_owner is not null and v_subject_owner <> v_session.user_id then
    return 'SUBJECT_CONFLICT';
  end if;

  insert into public.account_phone_identities
      (user_id, phone_e164, verification_provider, provider_subject, verified_at, updated_at)
  values
      (v_session.user_id, p_phone_e164, 'telegram', p_telegram_user_id::text, now(), now())
  on conflict (user_id) do update
      set phone_e164 = excluded.phone_e164,
          verification_provider = excluded.verification_provider,
          provider_subject = excluded.provider_subject,
          verified_at = now(),
          updated_at = now();

  update public.telegram_verification_sessions
     set status = 'VERIFIED',
         phone_e164 = p_phone_e164,
         verified_at = now()
   where id = v_session.id;

  return 'VERIFIED';
end;
$$;

revoke all on function public.telegram_complete_verification(text, bigint, text) from public;
-- Executable only by the service role (webhook). No grant to anon/authenticated.
