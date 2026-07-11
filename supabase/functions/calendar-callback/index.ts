import { createClient } from "npm:@supabase/supabase-js@2";
import { encryptSecret, verifySignedState } from "../_shared/crypto.ts";
import { requiredEnv } from "../_shared/http.ts";

type Provider = "google_calendar" | "outlook_calendar";
type CallbackState = { userId: string; provider: Provider; redirectUri: string; expiresAt: number };

type TokenResponse = {
  access_token: string;
  refresh_token?: string;
  expires_in?: number;
  scope?: string;
  id_token?: string;
};

async function exchangeCode(provider: Provider, code: string, redirectUri: string): Promise<TokenResponse> {
  const google = provider === "google_calendar";
  const tokenUrl = google
    ? "https://oauth2.googleapis.com/token"
    : "https://login.microsoftonline.com/common/oauth2/v2.0/token";
  const body = new URLSearchParams({
    grant_type: "authorization_code",
    code,
    redirect_uri: redirectUri,
    client_id: requiredEnv(google ? "GOOGLE_CALENDAR_CLIENT_ID" : "MICROSOFT_CALENDAR_CLIENT_ID"),
    client_secret: requiredEnv(google ? "GOOGLE_CALENDAR_CLIENT_SECRET" : "MICROSOFT_CALENDAR_CLIENT_SECRET"),
  });
  const response = await fetch(tokenUrl, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body,
  });
  const result = await response.json();
  if (!response.ok || typeof result.access_token !== "string") {
    throw new Error(`Provider token exchange failed: ${result.error_description ?? result.error ?? response.status}`);
  }
  return result as TokenResponse;
}

function jwtSubject(idToken?: string): string {
  if (!idToken) return "calendar-account";
  const payload = idToken.split(".")[1];
  if (!payload) return "calendar-account";
  const decoded = payload.replaceAll("-", "+").replaceAll("_", "/").padEnd(Math.ceil(payload.length / 4) * 4, "=");
  return String(JSON.parse(atob(decoded)).sub ?? "calendar-account");
}

Deno.serve(async (request) => {
  try {
    const url = new URL(request.url);
    const error = url.searchParams.get("error");
    if (error) throw new Error(`Provider authorization failed: ${error}`);
    const code = url.searchParams.get("code");
    const signedState = url.searchParams.get("state");
    if (!code || !signedState) throw new Error("Missing authorization callback parameters");

    const state = await verifySignedState<CallbackState>(signedState, requiredEnv("CALENDAR_STATE_SECRET"));
    const tokens = await exchangeCode(state.provider, code, state.redirectUri);
    if (!tokens.refresh_token) throw new Error("Provider did not issue an offline refresh token");
    const encrypted = await encryptSecret(tokens.refresh_token, requiredEnv("INTEGRATION_TOKEN_KEY"));
    const supabase = createClient(requiredEnv("SUPABASE_URL"), requiredEnv("SUPABASE_SERVICE_ROLE_KEY"), {
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const { error: databaseError } = await supabase.from("integration_accounts").upsert({
      user_id: state.userId,
      provider: state.provider,
      provider_subject: jwtSubject(tokens.id_token),
      encrypted_refresh_token: `\\x${Array.from(Uint8Array.from(atob(encrypted.ciphertext), (c) => c.charCodeAt(0))).map((b) => b.toString(16).padStart(2, "0")).join("")}`,
      token_nonce: `\\x${Array.from(Uint8Array.from(atob(encrypted.nonce), (c) => c.charCodeAt(0))).map((b) => b.toString(16).padStart(2, "0")).join("")}`,
      scopes: tokens.scope?.split(" ").filter(Boolean) ?? [],
      revoked_at: null,
      updated_at: new Date().toISOString(),
    }, { onConflict: "user_id,provider,provider_subject" });
    if (databaseError) throw databaseError;
    return Response.redirect(`norfold://integrations/calendar?provider=${encodeURIComponent(state.provider)}&status=connected`, 302);
  } catch (error) {
    const message = error instanceof Error ? error.message : "Calendar connection failed";
    return Response.redirect(`norfold://integrations/calendar?status=error&message=${encodeURIComponent(message)}`, 302);
  }
});
