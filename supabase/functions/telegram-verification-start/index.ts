// telegram-verification-start — create a short-lived phone-verification session
// for the AUTHENTICATED caller and hand back a t.me deep link.
//
// verify_jwt stays ON (default) for this function: only a logged-in Norfold
// user may start verification, and the user_id is taken from their validated
// JWT — never from the request body.
//
// The raw start token is generated here, returned to the caller exactly once,
// and never persisted. Only its SHA-256 hash is stored (via the
// telegram_start_verification RPC, which binds the row to auth.uid()).
import { authenticatedClient, requireUser } from "../_shared/supabase.ts";
import { json } from "../_shared/http.ts";

const BOT_USERNAME = "Norfold_auth_bot";
const TTL_SECONDS = 600; // 10 minutes

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  bytes.forEach((b) => (binary += String.fromCharCode(b)));
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}

async function sha256Hex(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return new Response("ok");
  try {
    if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);

    // Validates the JWT and gives us a trustworthy user id.
    await requireUser(request);

    // A Telegram /start parameter allows [A-Za-z0-9_-], up to 64 chars.
    // 32 random bytes → 43 base64url chars: unguessable and within the limit.
    const rawToken = base64Url(crypto.getRandomValues(new Uint8Array(32)));
    const tokenHash = await sha256Hex(rawToken);

    // The RPC runs SECURITY DEFINER, binds user_id = auth.uid(), cancels the
    // caller's older open sessions and stores only the hash.
    const supabase = authenticatedClient(request);
    const { data, error } = await supabase.rpc("telegram_start_verification", {
      p_token_hash: tokenHash,
      p_ttl_seconds: TTL_SECONDS,
    });
    if (error) throw error;

    const session = Array.isArray(data) ? data[0] : data;
    return json({
      verificationId: session.id,
      telegramUrl: `https://t.me/${BOT_USERNAME}?start=${rawToken}`,
      expiresAt: session.expires_at,
    });
  } catch (error) {
    console.error("telegram-verification-start failed:", error instanceof Error ? error.message : error);
    return json({ error: "verification_start_failed" }, 400);
  }
});
