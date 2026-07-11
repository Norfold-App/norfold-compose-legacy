import { corsHeaders, json } from "../_shared/http.ts";
import { verifyAndPersistPlayPurchase } from "../_shared/play.ts";
import { requireUser } from "../_shared/supabase.ts";

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (request.method !== "POST") return json({ error: "Method not allowed" }, 405);
  try {
    const user = await requireUser(request);
    const { productId, purchaseToken } = await request.json();
    if (!productId || !purchaseToken) return json({ error: "productId and purchaseToken are required" }, 400);
    return json(await verifyAndPersistPlayPurchase(user.id, String(productId), String(purchaseToken)));
  } catch (error) {
    return json({ error: error instanceof Error ? error.message : "Play verification failed" }, 400);
  }
});
