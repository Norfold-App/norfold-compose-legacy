import { corsHeaders, json } from "../_shared/http.ts";
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
    return json({ results });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Sync failed";
    return json({ error: message }, message.startsWith("VERSION_CONFLICT") ? 409 : 401);
  }
});
