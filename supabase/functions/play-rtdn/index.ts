import { verifyPubSubIdentity } from "../_shared/google_oidc.ts";
import { json, requiredEnv } from "../_shared/http.ts";
import {
  purchaseTokenHash,
  verifyAndPersistPlayPurchase,
} from "../_shared/play.ts";
import { serviceClient } from "../_shared/supabase.ts";

async function verifyPushIdentity(request: Request): Promise<void> {
  await verifyPubSubIdentity(
    request.headers.get("authorization"),
    requiredEnv("PLAY_PUBSUB_AUDIENCE"),
    requiredEnv("PLAY_PUBSUB_SERVICE_ACCOUNT_EMAIL"),
  );
}

Deno.serve(async (request) => {
  if (request.method !== "POST") {
    return json({ error: "Method not allowed" }, 405);
  }
  try {
    await verifyPushIdentity(request);
    const envelope = await request.json();
    const encoded = envelope.message?.data;
    if (!encoded) throw new Error("Pub/Sub message has no data");
    const event = JSON.parse(atob(encoded));
    const notice = event.subscriptionNotification;
    if (!notice?.purchaseToken || !notice?.subscriptionId) {
      return json({ ignored: true });
    }
    const tokenHash = await purchaseTokenHash(notice.purchaseToken);
    const client = serviceClient();
    const { data: subscription, error } = await client.from("subscriptions")
      .select("user_id").eq("source", "google_play").eq(
        "external_subscription_id",
        tokenHash,
      ).single();
    if (error || !subscription) {
      throw new Error("No Norfold user is bound to this Play purchase");
    }
    const result = await verifyAndPersistPlayPurchase(
      subscription.user_id,
      notice.subscriptionId,
      notice.purchaseToken,
    );
    return json({ processed: true, ...result });
  } catch (error) {
    return json({
      error: error instanceof Error ? error.message : "RTDN processing failed",
    }, 401);
  }
});
