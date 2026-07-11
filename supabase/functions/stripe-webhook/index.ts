import Stripe from "npm:stripe@18";
import { createClient } from "npm:@supabase/supabase-js@2";
import { json, requiredEnv } from "../_shared/http.ts";

const stripe = new Stripe(requiredEnv("STRIPE_SECRET_KEY"), { httpClient: Stripe.createFetchHttpClient() });
const supabase = createClient(requiredEnv("SUPABASE_URL"), requiredEnv("SUPABASE_SERVICE_ROLE_KEY"), {
  auth: { persistSession: false, autoRefreshToken: false },
});

function tierForPrice(priceId?: string): "free" | "pro" | "team" {
  if (priceId && priceId === Deno.env.get("STRIPE_TEAM_PRICE_ID")) return "team";
  if (priceId && priceId === Deno.env.get("STRIPE_PRO_PRICE_ID")) return "pro";
  return "free";
}

async function persistSubscription(subscription: Stripe.Subscription): Promise<void> {
  const userId = subscription.metadata.norfold_user_id;
  if (!userId) throw new Error("Stripe subscription is missing norfold_user_id metadata");
  const active = ["active", "trialing", "past_due"].includes(subscription.status);
  const priceId = subscription.items.data[0]?.price.id;
  const tier = active ? tierForPrice(priceId) : "free";
  const periodEnd = subscription.items.data[0]?.current_period_end;
  const { error } = await supabase.from("subscriptions").upsert({
    user_id: userId,
    tier,
    source: "stripe",
    external_customer_id: String(subscription.customer),
    external_subscription_id: subscription.id,
    current_period_end: periodEnd ? new Date(periodEnd * 1000).toISOString() : null,
    cancel_at_period_end: subscription.cancel_at_period_end,
    updated_at: new Date().toISOString(),
  });
  if (error) throw error;
  const entitlements = [
    { entitlement: "personal_cloud", enabled: tier === "pro" || tier === "team" },
    { entitlement: "calendar_integrations", enabled: tier === "pro" || tier === "team" },
    { entitlement: "team_collaboration", enabled: tier === "team" },
  ].map((entry) => ({ user_id: userId, source: "stripe", updated_at: new Date().toISOString(), ...entry }));
  const { error: entitlementError } = await supabase.from("entitlements").upsert(entitlements, { onConflict: "user_id,entitlement" });
  if (entitlementError) throw entitlementError;
}

Deno.serve(async (request) => {
  if (request.method !== "POST") return json({ error: "Method not allowed" }, 405);
  try {
    const signature = request.headers.get("stripe-signature");
    if (!signature) return json({ error: "Missing Stripe signature" }, 400);
    const event = await stripe.webhooks.constructEventAsync(
      await request.text(),
      signature,
      requiredEnv("STRIPE_WEBHOOK_SECRET"),
    );
    if (["customer.subscription.created", "customer.subscription.updated", "customer.subscription.deleted"].includes(event.type)) {
      await persistSubscription(event.data.object as Stripe.Subscription);
    }
    return json({ received: true });
  } catch (error) {
    const message = error instanceof Error ? error.message : "Webhook processing failed";
    return json({ error: message }, 400);
  }
});
