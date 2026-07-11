import { corsHeaders, json } from "../_shared/http.ts";
import { bytesToPostgresHex, requireUser, serviceClient } from "../_shared/supabase.ts";

const encoder = new TextEncoder();
async function tokenHash(token: string): Promise<string> {
  return bytesToPostgresHex(new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(token))));
}

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (request.method !== "POST") return json({ error: "Method not allowed" }, 405);
  try {
    const user = await requireUser(request);
    const body = await request.json();
    const client = serviceClient();
    if (body.action === "create") {
      if (!body.workspaceId || !body.email) return json({ error: "workspaceId and email are required" }, 400);
      const { data: membership } = await client.from("workspace_memberships").select("role").eq("workspace_id", body.workspaceId).eq("user_id", user.id).is("removed_at", null).maybeSingle();
      if (!membership || !["owner", "admin"].includes(membership.role)) return json({ error: "Workspace administrator access required" }, 403);
      const raw = crypto.getRandomValues(new Uint8Array(32));
      const token = btoa(String.fromCharCode(...raw)).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
      const { data, error } = await client.from("workspace_invitations").insert({
        workspace_id: body.workspaceId,
        email: String(body.email).trim().toLowerCase(),
        role: body.role === "viewer" ? "viewer" : "member",
        token_hash: await tokenHash(token),
        invited_by: user.id,
        expires_at: new Date(Date.now() + 7 * 86_400_000).toISOString(),
      }).select("id,expires_at").single();
      if (error) throw error;
      return json({ invitation: data, token });
    }
    if (body.action === "accept") {
      if (!body.token) return json({ error: "Invitation token is required" }, 400);
      const hash = await tokenHash(String(body.token));
      const { data: invitation, error } = await client.from("workspace_invitations").select("*").eq("token_hash", hash).is("accepted_at", null).gt("expires_at", new Date().toISOString()).single();
      if (error || !invitation) return json({ error: "Invitation is invalid or expired" }, 404);
      if (user.email?.toLowerCase() !== invitation.email.toLowerCase()) return json({ error: "Invitation belongs to another account" }, 403);
      const { error: membershipError } = await client.from("workspace_memberships").upsert({ workspace_id: invitation.workspace_id, user_id: user.id, role: invitation.role, removed_at: null });
      if (membershipError) throw membershipError;
      await client.from("workspace_invitations").update({ accepted_at: new Date().toISOString() }).eq("id", invitation.id);
      return json({ workspaceId: invitation.workspace_id, accepted: true });
    }
    return json({ error: "Unsupported invitation action" }, 400);
  } catch (error) {
    return json({ error: error instanceof Error ? error.message : "Invitation failed" }, 401);
  }
});
