-- The Android surface supports one active account per calendar provider. Older
-- rows remain as revoked history, but a reconnect must atomically make exactly
-- one row active so calendar-sync never selects an arbitrary account.

with ranked as (
  select
    id,
    row_number() over (
      partition by user_id, provider
      order by updated_at desc, id desc
    ) as active_rank
  from public.integration_accounts
  where revoked_at is null
)
update public.integration_accounts as account
set
  revoked_at = now(),
  cursor = null,
  updated_at = now()
from ranked
where account.id = ranked.id
  and ranked.active_rank > 1;

create unique index integration_accounts_one_active_provider_idx
  on public.integration_accounts (user_id, provider)
  where revoked_at is null;

create or replace function public.replace_calendar_integration(
  p_user_id uuid,
  p_provider public.integration_provider,
  p_provider_subject text,
  p_encrypted_refresh_token bytea,
  p_token_nonce bytea,
  p_scopes text[]
)
returns uuid
language plpgsql
set search_path = ''
as $$
declare
  v_integration_id uuid;
  v_now timestamptz := now();
begin
  -- Serialize every connect/reconnect for one user and provider. The unique
  -- partial index is the final invariant; this lock also makes last callback
  -- completion deterministically win instead of two callbacks revoking each
  -- other in opposite order.
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(p_user_id::text || ':' || p_provider::text, 0)
  );

  update public.integration_accounts
  set
    revoked_at = v_now,
    cursor = null,
    updated_at = v_now
  where user_id = p_user_id
    and provider = p_provider
    and provider_subject <> p_provider_subject
    and revoked_at is null;

  insert into public.integration_accounts (
    user_id,
    provider,
    provider_subject,
    encrypted_refresh_token,
    token_nonce,
    scopes,
    cursor,
    revoked_at,
    updated_at
  ) values (
    p_user_id,
    p_provider,
    p_provider_subject,
    p_encrypted_refresh_token,
    p_token_nonce,
    coalesce(p_scopes, '{}'::text[]),
    null,
    null,
    v_now
  )
  on conflict (user_id, provider, provider_subject) do update
  set
    encrypted_refresh_token = excluded.encrypted_refresh_token,
    token_nonce = excluded.token_nonce,
    scopes = excluded.scopes,
    cursor = null,
    revoked_at = null,
    updated_at = excluded.updated_at
  returning id into v_integration_id;

  return v_integration_id;
end;
$$;

revoke all on function public.replace_calendar_integration(
  uuid,
  public.integration_provider,
  text,
  bytea,
  bytea,
  text[]
) from public, anon, authenticated;

grant execute on function public.replace_calendar_integration(
  uuid,
  public.integration_provider,
  text,
  bytea,
  bytea,
  text[]
) to service_role;
