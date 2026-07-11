import { encryptSecret } from "../_shared/crypto.ts";
import { corsHeaders, json, requiredEnv } from "../_shared/http.ts";
import { bytesToPostgresHex, requireUser, serviceClient } from "../_shared/supabase.ts";

const encoder = new TextEncoder();
async function hash(value: string): Promise<string> {
  return bytesToPostgresHex(new Uint8Array(await crypto.subtle.digest("SHA-256", encoder.encode(value))));
}
function base64Bytes(value: string): string {
  return bytesToPostgresHex(Uint8Array.from(atob(value), (character) => character.charCodeAt(0)));
}

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (request.method !== "POST") return json({ error: "Method not allowed" }, 405);
  try {
    const user = await requireUser(request);
    const { token, deviceId, deviceName, enabled = true } = await request.json();
    if (!token || !deviceId) return json({ error: "token and deviceId are required" }, 400);
    const client = serviceClient();
    const { error: deviceError } = await client.from("devices").upsert({
      id: deviceId,
      user_id: user.id,
      name: String(deviceName || "Android device").slice(0, 120),
      platform: "android",
      last_seen_at: new Date().toISOString(),
    });
    if (deviceError) throw deviceError;
    const encrypted = await encryptSecret(String(token), requiredEnv("INTEGRATION_TOKEN_KEY"));
    const { error } = await client.from("push_tokens").upsert({
      token_hash: await hash(String(token)),
      device_id: deviceId,
      encrypted_token: base64Bytes(encrypted.ciphertext),
      token_nonce: base64Bytes(encrypted.nonce),
      enabled: Boolean(enabled),
      updated_at: new Date().toISOString(),
    });
    if (error) throw error;
    return json({ registered: true });
  } catch (error) {
    return json({ error: error instanceof Error ? error.message : "Push registration failed" }, 401);
  }
});
