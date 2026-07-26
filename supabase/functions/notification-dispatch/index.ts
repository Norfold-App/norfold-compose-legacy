import { decryptSecret } from "../_shared/crypto.ts";
import { serviceAccountAccessToken } from "../_shared/google.ts";
import { json, requiredEnv } from "../_shared/http.ts";
import { postgresHexToBase64, serviceClient } from "../_shared/supabase.ts";

function inQuietHours(start?: string | null, end?: string | null): boolean {
  if (!start || !end) return false;
  const now = new Date().toISOString().slice(11, 19);
  return start <= end ? now >= start && now < end : now >= start || now < end;
}

Deno.serve(async (request) => {
  if (request.method !== "POST") return json({ error: "Method not allowed" }, 405);
  if (request.headers.get("authorization") !== `Bearer ${requiredEnv("SUPABASE_SERVICE_ROLE_KEY")}`) {
    return json({ error: "Service authorization required" }, 401);
  }
  try {
    const client = serviceClient();
    const { data: deliveries, error } = await client.from("notification_deliveries")
      .select("id,user_id,title,body,payload,attempt_count")
      .eq("channel", "push").is("delivered_at", null).lt("attempt_count", 10)
      .lte("scheduled_at", new Date().toISOString()).limit(100);
    if (error) throw error;
    const projectId = requiredEnv("FIREBASE_PROJECT_ID");
    const accessToken = await serviceAccountAccessToken("FIREBASE_SERVICE_ACCOUNT_JSON", ["https://www.googleapis.com/auth/firebase.messaging"]);
    let delivered = 0;
    for (const delivery of deliveries ?? []) {
      const { data: preference } = await client.from("notification_preferences")
        .select("push,quiet_start,quiet_end").eq("user_id", delivery.user_id).maybeSingle();
      if (!preference?.push || inQuietHours(preference.quiet_start, preference.quiet_end)) continue;
      const { data: tokens } = await client.from("push_tokens").select("token_hash,encrypted_token,token_nonce,devices!inner(user_id)").eq("devices.user_id", delivery.user_id).eq("enabled", true);
      let successful = false;
      for (const row of tokens ?? []) {
        const token = await decryptSecret(postgresHexToBase64(row.encrypted_token), postgresHexToBase64(row.token_nonce), requiredEnv("INTEGRATION_TOKEN_KEY"));
        const data = Object.fromEntries(Object.entries({
          ...(delivery.payload ?? {}),
          title: delivery.title,
          body: delivery.body,
          eventId: delivery.id,
        }).map(([key, value]) => [key, value == null ? "" : String(value)]));
        const response = await fetch(`https://fcm.googleapis.com/v1/projects/${encodeURIComponent(projectId)}/messages:send`, {
          method: "POST",
          headers: { authorization: `Bearer ${accessToken}`, "content-type": "application/json" },
          body: JSON.stringify({ message: { token, data, android: { priority: "HIGH" } } }),
        });
        if (!response.ok) {
          const errorBody = await response.text();
          if (response.status === 404 || errorBody.includes("UNREGISTERED") || errorBody.includes("INVALID_ARGUMENT")) {
            await client.from("push_tokens").update({ enabled: false, updated_at: new Date().toISOString() }).eq("token_hash", row.token_hash);
          }
        }
        successful = response.ok || successful;
      }
      const attempts = Number(delivery.attempt_count ?? 0) + 1;
      const retryAt = new Date(Date.now() + Math.min(60, 2 ** Math.min(attempts, 5)) * 60_000).toISOString();
      await client.from("notification_deliveries").update(successful
        ? { delivered_at: new Date().toISOString(), failure: null, attempt_count: attempts }
        : {
          failure: attempts >= 10 ? "No enabled device accepted the notification" : null,
          attempt_count: attempts,
          scheduled_at: retryAt,
        }).eq("id", delivery.id);
      if (successful) delivered += 1;
    }
    return json({ processed: deliveries?.length ?? 0, delivered });
  } catch (error) {
    return json({ error: error instanceof Error ? error.message : "Notification dispatch failed" }, 500);
  }
});
