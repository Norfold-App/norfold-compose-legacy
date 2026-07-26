-- profiles.handle became NOT NULL in 202607150002, but handle_new_user still inserted
-- profiles without it — every new auth signup failed with 23502. Generate the same
-- placeholder handle the backfill used; users claim a real one via claim_profile_handle.
create or replace function public.handle_new_user()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  insert into public.profiles (id, display_name, handle)
  values (
    new.id,
    coalesce(new.raw_user_meta_data ->> 'full_name', new.email, ''),
    'user_' || substr(replace(new.id::text, '-', ''), 1, 12)
  )
  on conflict (id) do nothing;
  insert into public.subscriptions (user_id) values (new.id) on conflict (user_id) do nothing;
  return new;
end;
$$;
