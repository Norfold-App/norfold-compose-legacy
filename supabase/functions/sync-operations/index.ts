import { corsHeaders, json, requiredEnv } from "../_shared/http.ts";
import { authenticatedClient, requireUser } from "../_shared/supabase.ts";

type SyncOperation = {
  workspaceId: string;
  syncId: string;
  objectType: string;
  operation: "upsert" | "delete";
  idempotencyKey: string;
  deviceId: string;
  baseVersion?: number | null;
  content?: Record<string, unknown> | null;
  ciphertext?: string | null;
  nonce?: string | null;
  keyVersion?: number | null;
  contentHash: string;
};

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (request.method !== "POST") return json({ error: "Method not allowed" }, 405);
  try {
    await requireUser(request);
    const payload = await request.json() as { operations?: SyncOperation[] };
    const operations = payload.operations ?? [];
    if (operations.length === 0 || operations.length > 100) return json({ error: "Provide 1 to 100 operations" }, 400);
    const client = authenticatedClient(request);
    const results = [];
    for (const operation of operations) {
      const { data, error } = await client.rpc("apply_workspace_object", {
        target_workspace: operation.workspaceId,
        target_sync_id: operation.syncId,
        target_type: operation.objectType,
        op: operation.operation,
        operation_id: operation.idempotencyKey,
        source_device: operation.deviceId,
        expected_version: operation.baseVersion ?? null,
        body: operation.content ?? null,
        encrypted_body: operation.ciphertext ?? null,
        encrypted_nonce: operation.nonce ?? null,
        encryption_key_version: operation.keyVersion ?? null,
        body_hash: operation.contentHash,
      });
      if (error) throw error;
      results.push(data);
    }
    // Object triggers have now committed durable delivery rows. Dispatch is best-effort here:
    // a temporary FCM failure stays queued and the scheduled/manual dispatcher can retry it.
    const dispatchResponse = await fetch(`${requiredEnv("SUPABASE_URL")}/functions/v1/notification-dispatch`, {
      method: "POST",
      headers: { authorization: `Bearer ${requiredEnv("SUPABASE_SERVICE_ROLE_KEY")}` },
    });
    if (!dispatchResponse.ok) console.error("notification dispatch deferred:", dispatchResponse.status);
    return json({ results });
  } catch (error) {
    if (error instanceof SyntaxError) return json({ error: "Invalid JSON payload" }, 400);
    const message = error instanceof Error ? error.message : "Sync failed";
    // Conflict and channel-privacy markers are part of the client contract and stay verbatim;
    // anything else is an internal failure whose detail belongs in the function log, not the
    // response. The old blanket 401 also mislabeled every failure as an auth problem.
    if (message.startsWith("VERSION_CONFLICT")) return json({ error: message }, 409);
    if (message.includes("CHANNEL_ACCESS_DENIED")) return json({ error: "CHANNEL_ACCESS_DENIED" }, 403);
    if (message === "Authentication required" || message === "Invalid authentication token") {
      return json({ error: message }, 401);
    }
    console.error("sync-operations failed:", message);
    return json({ error: "Sync failed" }, 500);
  }
});
