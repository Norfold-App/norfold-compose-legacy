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
      .select("id,user_id,title,body,payload")
      .eq("channel", "push").is("delivered_at", null).is("failure", null)
      .lte("scheduled_at", new Date().toISOString()).limit(100);
    if (error) throw error;
    const projectId = requiredEnv("FIREBASE_PROJECT_ID");
    const accessToken = await serviceAccountAccessToken("FIREBASE_SERVICE_ACCOUNT_JSON", ["https://www.googleapis.com/auth/firebase.messaging"]);
    let delivered = 0;
    for (const delivery of deliveries ?? []) {
      const { data: preference } = await client.from("notification_preferences")
        .select("push,quiet_start,quiet_end").eq("user_id", delivery.user_id).maybeSingle();
      if (!preference?.push || inQuietHours(preference.quiet_start, preference.quiet_end)) continue;
      const { data: tokens } = await client.from("push_tokens").select("encrypted_token,token_nonce,devices!inner(user_id)").eq("devices.user_id", delivery.user_id).eq("enabled", true);
      let successful = false;
      for (const row of tokens ?? []) {
        const token = await decryptSecret(postgresHexToBase64(row.encrypted_token), postgresHexToBase64(row.token_nonce), requiredEnv("INTEGRATION_TOKEN_KEY"));
        const response = await fetch(`https://fcm.googleapis.com/v1/projects/${encodeURIComponent(projectId)}/messages:send`, {
          method: "POST",
          headers: { authorization: `Bearer ${accessToken}`, "content-type": "application/json" },
          body: JSON.stringify({ message: { token, notification: { title: delivery.title, body: delivery.body }, data: delivery.payload ?? {} } }),
        });
        successful = response.ok || successful;
      }
      await client.from("notification_deliveries").update(successful
        ? { delivered_at: new Date().toISOString() }
        : { failure: "No enabled device accepted the notification" }).eq("id", delivery.id);
      if (successful) delivered += 1;
    }
    return json({ processed: deliveries?.length ?? 0, delivered });
  } catch (error) {
    return json({ error: error instanceof Error ? error.message : "Notification dispatch failed" }, 500);
  }
});
