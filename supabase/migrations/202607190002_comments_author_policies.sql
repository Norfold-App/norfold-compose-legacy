-- Comments were governed by a single FOR ALL policy whose USING clause only checked
-- workspace membership, so any member could delete another member's comment — or update
-- it while setting author_id to themselves (re-authoring), since WITH CHECK only
-- validates the new row. Split into per-command policies: reads stay workspace-wide,
-- writes require being the author.

drop policy if exists comments_member_all on public.object_comments;

create policy comments_member_read on public.object_comments
  for select
  using (public.is_workspace_member(workspace_id));

create policy comments_author_insert on public.object_comments
  for insert
  with check (public.is_workspace_member(workspace_id) and author_id = auth.uid());

create policy comments_author_update on public.object_comments
  for update
  using (public.is_workspace_member(workspace_id) and author_id = auth.uid())
  with check (public.is_workspace_member(workspace_id) and author_id = auth.uid());

create policy comments_author_delete on public.object_comments
  for delete
  using (public.is_workspace_member(workspace_id) and author_id = auth.uid());
