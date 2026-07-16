alter table public.profiles
  add column if not exists handle text;

update public.profiles
set handle = 'user_' || substr(replace(id::text, '-', ''), 1, 12)
where handle is null or btrim(handle) = '';

alter table public.profiles
  alter column handle set not null;

alter table public.profiles
  drop constraint if exists profiles_handle_format;

alter table public.profiles
  add constraint profiles_handle_format
  check (handle = lower(handle) and handle ~ '^[a-z][a-z0-9_]{2,29}$');

create unique index if not exists profiles_handle_unique
  on public.profiles (lower(handle));

create or replace function public.claim_profile_handle(p_handle text, p_display_name text default '')
returns text
language plpgsql
security invoker
set search_path = public
as $$
declare
  normalized_handle text := lower(btrim(p_handle));
  claimed_handle text;
begin
  if auth.uid() is null then
    raise exception using errcode = '42501', message = 'authentication_required';
  end if;
  if normalized_handle !~ '^[a-z][a-z0-9_]{2,29}$' then
    raise exception using errcode = '22023', message = 'invalid_handle';
  end if;

  update public.profiles
  set handle = normalized_handle,
      display_name = left(btrim(p_display_name), 80),
      updated_at = now()
  where id = auth.uid()
  returning handle into claimed_handle;

  if claimed_handle is null then
    raise exception using errcode = 'P0002', message = 'profile_not_found';
  end if;
  return claimed_handle;
exception
  when unique_violation then
    raise exception using errcode = '23505', message = 'handle_taken';
end;
$$;

grant execute on function public.claim_profile_handle(text, text) to authenticated;
