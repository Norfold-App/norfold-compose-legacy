import { serviceClient } from "./supabase.ts";
import { verifyPlaySubscription } from "./google.ts";

const encoder = new TextEncoder();

export async function purchaseTokenHash(token: string): Promise<string> {
  return Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(token))))
    .map((byte) => byte.toString(16).padStart(2, "0")).join("");
}

export async function verifyAndPersistPlayPurchase(userId: string, productId: string, purchaseToken: string) {
  if (!["norfold_pro", "norfold_team"].includes(productId)) throw new Error("Unsupported Norfold subscription product");
  const verified = await verifyPlaySubscription("com.norfold.app", productId, purchaseToken);
  const state = String(verified.body.subscriptionState ?? "");
  const active = ["SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD", "SUBSCRIPTION_STATE_PAUSED"].includes(state);
  const tier = active ? (productId === "norfold_team" ? "team" : "pro") : "free";
  const expiry = verified.lineItem.expiryTime ?? null;
  const sourceId = await purchaseTokenHash(purchaseToken);
  const client = serviceClient();
  const { error } = await client.from("subscriptions").upsert({
    user_id: userId,
    tier,
    source: "google_play",
    external_subscription_id: sourceId,
    current_period_end: expiry,
    cancel_at_period_end: verified.lineItem.autoRenewingPlan?.autoRenewEnabled === false,
    updated_at: new Date().toISOString(),
  });
  if (error) throw error;
  const rows = [
    { entitlement: "personal_cloud", enabled: tier === "pro" || tier === "team" },
    { entitlement: "calendar_integrations", enabled: tier === "pro" || tier === "team" },
    { entitlement: "team_collaboration", enabled: tier === "team" },
  ].map((entry) => ({ user_id: userId, source: "google_play", expires_at: expiry, updated_at: new Date().toISOString(), ...entry }));
  const { error: entitlementError } = await client.from("entitlements").upsert(rows, { onConflict: "user_id,entitlement" });
  if (entitlementError) throw entitlementError;
  return { tier, state, expiresAt: expiry };
}
