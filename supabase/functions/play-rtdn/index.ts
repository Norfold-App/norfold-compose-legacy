import { json } from "../_shared/http.ts";
import { purchaseTokenHash, verifyAndPersistPlayPurchase } from "../_shared/play.ts";
import { serviceClient } from "../_shared/supabase.ts";

async function verifyPushIdentity(request: Request): Promise<void> {
  const bearer = request.headers.get("authorization")?.replace(/^Bearer\s+/i, "");
  if (!bearer) throw new Error("Missing Pub/Sub identity token");
  const response = await fetch(`https://oauth2.googleapis.com/tokeninfo?id_token=${encodeURIComponent(bearer)}`);
  const claims = await response.json();
  if (!response.ok || !claims.email_verified) throw new Error("Invalid Pub/Sub identity token");
  const expectedEmail = JSON.parse(Deno.env.get("PLAY_SERVICE_ACCOUNT_JSON") ?? "{}").client_email;
  if (!expectedEmail || claims.email !== expectedEmail) throw new Error("Unexpected Pub/Sub service account");
}

Deno.serve(async (request) => {
  if (request.method !== "POST") return json({ error: "Method not allowed" }, 405);
  try {
    await verifyPushIdentity(request);
    const envelope = await request.json();
    const encoded = envelope.message?.data;
    if (!encoded) throw new Error("Pub/Sub message has no data");
    const event = JSON.parse(atob(encoded));
    const notice = event.subscriptionNotification;
    if (!notice?.purchaseToken || !notice?.subscriptionId) return json({ ignored: true });
    const tokenHash = await purchaseTokenHash(notice.purchaseToken);
    const client = serviceClient();
    const { data: subscription, error } = await client.from("subscriptions").select("user_id").eq("source", "google_play").eq("external_subscription_id", tokenHash).single();
    if (error || !subscription) throw new Error("No Norfold user is bound to this Play purchase");
    const result = await verifyAndPersistPlayPurchase(subscription.user_id, notice.subscriptionId, notice.purchaseToken);
    return json({ processed: true, ...result });
  } catch (error) {
    return json({ error: error instanceof Error ? error.message : "RTDN processing failed" }, 401);
  }
});
