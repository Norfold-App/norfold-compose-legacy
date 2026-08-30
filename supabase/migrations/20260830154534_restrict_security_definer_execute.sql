-- PostgreSQL grants EXECUTE on new functions to PUBLIC by default. These
-- SECURITY DEFINER functions already enforce auth.uid() in their bodies, but
-- the privilege boundary must still deny anonymous entry explicitly.

revoke all on function public.create_friend_invite() from public, anon;
revoke all on function public.redeem_friend_invite(text) from public, anon;
revoke all on function public.add_contact_by_handle(text) from public, anon;
revoke all on function public.open_direct_conversation(uuid) from public, anon;
revoke all on function public.record_message_receipts(uuid, uuid[], text) from public, anon;
revoke all on function public.is_workspace_member(uuid) from public, anon;
revoke all on function public.has_workspace_role(uuid, public.workspace_role[]) from public, anon;

grant execute on function public.create_friend_invite() to authenticated;
grant execute on function public.redeem_friend_invite(text) to authenticated;
grant execute on function public.add_contact_by_handle(text) to authenticated;
grant execute on function public.open_direct_conversation(uuid) to authenticated;
grant execute on function public.record_message_receipts(uuid, uuid[], text) to authenticated;
grant execute on function public.is_workspace_member(uuid) to authenticated;
grant execute on function public.has_workspace_role(uuid, public.workspace_role[]) to authenticated;

-- Trigger functions are invoked by their triggers and have no legitimate
-- direct API caller.
revoke all on function public.handle_new_user() from public, anon, authenticated;
revoke all on function public.record_dm_reply_contact() from public, anon, authenticated;
revoke all on function public.enqueue_workspace_object_notification() from public, anon, authenticated;
