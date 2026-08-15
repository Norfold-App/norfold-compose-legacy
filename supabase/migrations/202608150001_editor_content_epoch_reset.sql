-- Editor content epoch: the server half of a one-time reset of synced editor artifacts.
--
-- The device erases its own editor generation in one local transaction. The server cannot be
-- reached in that transaction, so the remote half is owed rather than done: the client retries
-- reset_workspace_editor_content until it is acknowledged. Everything here is written to be safe
-- to call twice, from any device, in any order relative to the local reset.
--
-- What a reset erases: every shared_document object in the workspace. Deleting those rows cascades
-- to their collaboration operations, snapshots, and revision heads (202608100001) and to their
-- comments (object_comments), which is exactly the set the boundary is defined to erase.
--
-- What it deliberately keeps: the workspace, its memberships and key envelopes, chat channels and
-- messages, activity records, and files. files.object_id and activity_records.object_id are
-- "on delete set null", so a file that was attached to an erased document survives as a standalone
-- file rather than disappearing with it. object_operations.object_id is also "set null", which
-- keeps the idempotency keys alive on purpose: a stale client retrying an operation it already sent
-- gets the recorded result back instead of recreating the object it names.

alter table public.workspaces
  add column if not exists editor_content_epoch integer not null default 0;

comment on column public.workspaces.editor_content_epoch is
  'Generation of editor content this workspace holds. Raised only by reset_workspace_editor_content.';

-- Erases the workspace's editor generation and stamps the new epoch, once.
--
-- Authorization is the caller's own token: security definer runs the deletes, but the gate is
-- has_workspace_role under auth.uid(), so a member or viewer cannot erase a workspace's documents
-- and no privileged key ever reaches a client. Raising 42501 rather than returning a flag is
-- deliberate — the client classifies it as a permanent refusal and stops retrying.
create or replace function public.reset_workspace_editor_content(p_workspace uuid, p_epoch integer)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  current_epoch integer;
  deleted_count integer;
begin
  if p_epoch is null or p_epoch < 1 then
    raise exception 'editor_reset_invalid_epoch' using errcode = '22023';
  end if;
  if not public.has_workspace_role(p_workspace, array['owner', 'admin']::public.workspace_role[]) then
    raise exception 'editor_reset_denied' using errcode = '42501';
  end if;

  select w.editor_content_epoch into current_epoch
  from public.workspaces w
  where w.id = p_workspace and w.deleted_at is null
  for update;

  if not found then
    raise exception 'editor_reset_unknown_workspace' using errcode = '42704';
  end if;

  -- Idempotent by epoch, not by attempt: a second device crossing the same boundary finds the
  -- work already done and is told so, which is the same answer as having done it.
  if current_epoch >= p_epoch then
    return jsonb_build_object('epoch', current_epoch, 'deleted', 0, 'already_applied', true);
  end if;

  delete from public.workspace_objects o
  where o.workspace_id = p_workspace
    and o.object_type = 'shared_document';
  get diagnostics deleted_count = row_count;

  update public.workspaces w
  set editor_content_epoch = p_epoch, updated_at = now()
  where w.id = p_workspace;

  return jsonb_build_object('epoch', p_epoch, 'deleted', deleted_count, 'already_applied', false);
end;
$$;

revoke all on function public.reset_workspace_editor_content(uuid, integer) from public, anon;
grant execute on function public.reset_workspace_editor_content(uuid, integer) to authenticated;

-- Keeps the erased generation erased.
--
-- A device that has not crossed the boundary locally would otherwise re-upload the documents the
-- reset just deleted, and there is no way to tell that write apart from a legitimate one by looking
-- at the row. So a stamped workspace requires the payload to name its epoch: a current client puts
-- it there, a client from before the boundary cannot, and the write is refused. The stamp lives in
-- the payload because that is the only part of a shared document the client fully controls and the
-- server can read.
--
-- Workspaces still at epoch 0 accept unstamped writes, so this is inert until a reset runs.
create or replace function public.enforce_editor_content_epoch()
returns trigger language plpgsql set search_path = '' as $$
declare
  workspace_epoch integer;
  payload_epoch integer;
begin
  if new.object_type <> 'shared_document' then
    return new;
  end if;

  select w.editor_content_epoch into workspace_epoch
  from public.workspaces w
  where w.id = new.workspace_id;

  if coalesce(workspace_epoch, 0) < 1 then
    return new;
  end if;

  begin
    payload_epoch := (new.content ->> 'editorContentEpoch')::integer;
  exception when others then
    payload_epoch := null;
  end;

  if coalesce(payload_epoch, 0) < workspace_epoch then
    raise exception 'EDITOR_EPOCH_STALE:%', workspace_epoch using errcode = '42501';
  end if;

  return new;
end;
$$;

drop trigger if exists workspace_object_editor_epoch on public.workspace_objects;
create trigger workspace_object_editor_epoch
before insert or update of content, ciphertext, object_type
on public.workspace_objects
for each row execute function public.enforce_editor_content_epoch();
