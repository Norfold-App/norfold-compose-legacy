// calendar-authorize — the half of the calendar OAuth flow that was missing.
//
// calendar-callback has been deployed and unreachable: it verifies a signed `state`, and nothing
// issued one. This function is that issuer. It authenticates the caller, mints the state the
// callback expects, and returns the provider's authorization URL for the app to open.
//
// verify_jwt stays ON (the default, so there is deliberately no [functions.calendar-authorize]
// entry in supabase/config.toml). The `userId` inside the state is the identity the callback later
// writes the refresh token against, so it must come from a validated JWT and never from the request
// body — otherwise anyone could mint a state naming someone else and attach their own calendar to
// that account.
//
// ## Why the URL is built here and not in the app
//
// Nothing secret is in the authorization URL — the client id is public by design and the client
// secret never leaves the function. The state is the reason. It is an HMAC over CALENDAR_STATE_SECRET,
// which is a function secret; an app that could sign its own state would be an app that could name
// any user id. Building the URL here also keeps the two ends of one contract in one place: the
// redirect_uri signed into the state is the same string sent to the provider and the same one the
// callback replays into the token exchange, and all three have to match exactly or the exchange
// fails with a redirect_uri_mismatch that reads as a configuration problem rather than a drift.
//
// ## access_type=offline is load-bearing
//
// calendar-callback throws when the provider does not return a refresh_token, so the request has to
// ask for offline access or the flow fails on every attempt, for every user, with a generic error.
// Google needs access_type=offline; Microsoft needs the offline_access scope. Google also only
// returns a refresh token on the *first* authorization for a given client/user pair unless it is
// asked to prompt again, so prompt=consent is sent — reconnecting after a revoke is exactly when a
// missing refresh token would otherwise strand the account.
import { signState } from "../_shared/crypto.ts";
import { json, requiredEnv } from "../_shared/http.ts";
import { requireUser } from "../_shared/supabase.ts";

type Provider = "google_calendar" | "outlook_calendar";

// The shape calendar-callback verifies. Kept structurally identical on purpose: the callback
// declares its own copy, and a field renamed on one side only would still sign, still verify, and
// arrive as undefined.
type CallbackState = { userId: string; provider: Provider; redirectUri: string; expiresAt: number };

const StateTtlMs = 10 * 60 * 1000; // Long enough to sign in and grant, short enough to be useless later.

// Read-only. Norfold imports the owner's calendar into its own; it does not write events back, and
// asking for write access it never uses would be asking for trust it does not need.
const GoogleScopes = [
  "https://www.googleapis.com/auth/calendar.readonly",
  "https://www.googleapis.com/auth/calendar.events.readonly",
  "openid",
  "email",
];
// offline_access is what yields the refresh token on the Microsoft identity platform, and openid +
// email give the callback an id_token to derive provider_subject from.
const MicrosoftScopes = [
  "https://graph.microsoft.com/calendars.read",
  "offline_access",
  "openid",
  "email",
];

function providerFrom(value: unknown): Provider {
  if (value === "google_calendar" || value === "outlook_calendar") return value;
  throw new Error(`Unsupported calendar provider: ${String(value)}`);
}

/**
 * The redirect_uri the provider sends the grant to.
 *
 * This is the deployed calendar-callback function, not the app's `norfold://` scheme: the callback
 * needs the authorization code server-side to exchange it with the client secret, and it redirects
 * into the app afterwards. CALENDAR_CALLBACK_URL exists as an override for a project whose
 * functions are served from a custom domain, because the value has to match the provider console's
 * registered redirect exactly.
 */
function callbackUrl(): string {
  const configured = Deno.env.get("CALENDAR_CALLBACK_URL")?.trim();
  if (configured) return configured.replace(/\/+$/, "");
  return `${requiredEnv("SUPABASE_URL").replace(/\/+$/, "")}/functions/v1/calendar-callback`;
}

function authorizationUrl(provider: Provider, state: string, redirectUri: string): string {
  const google = provider === "google_calendar";
  const url = new URL(
    google
      ? "https://accounts.google.com/o/oauth2/v2/auth"
      // The `common` tenant, matching the token endpoint calendar-callback posts to. A tenant
      // mismatch between the two ends is rejected at exchange time, not here.
      : "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
  );
  url.searchParams.set("client_id", requiredEnv(google ? "GOOGLE_CALENDAR_CLIENT_ID" : "MICROSOFT_CALENDAR_CLIENT_ID"));
  url.searchParams.set("response_type", "code");
  url.searchParams.set("redirect_uri", redirectUri);
  url.searchParams.set("scope", (google ? GoogleScopes : MicrosoftScopes).join(" "));
  url.searchParams.set("state", state);
  if (google) {
    url.searchParams.set("access_type", "offline");
    // Google withholds the refresh token on re-authorization unless consent is requested again,
    // and calendar-callback treats a missing one as a hard failure. See the header note.
    url.searchParams.set("prompt", "consent");
    url.searchParams.set("include_granted_scopes", "true");
  } else {
    url.searchParams.set("response_mode", "query");
  }
  return url.toString();
}

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return new Response("ok");
  try {
    if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);

    // Validates the JWT. The user id below is this, never anything the caller sent.
    const user = await requireUser(request);
    const body = await request.json().catch(() => ({}));
    const provider = providerFrom(body?.provider);
    const redirectUri = callbackUrl();
    const state = await signState<CallbackState>(
      { userId: user.id, provider, redirectUri, expiresAt: Date.now() + StateTtlMs },
      requiredEnv("CALENDAR_STATE_SECRET"),
    );
    return json({
      provider,
      authorizationUrl: authorizationUrl(provider, state, redirectUri),
      expiresAt: new Date(Date.now() + StateTtlMs).toISOString(),
    });
  } catch (error) {
    // Same posture as every other function here: the reason goes to the log, the caller gets a
    // generic code. A missing GOOGLE_CALENDAR_CLIENT_ID and an unsupported provider must not be
    // distinguishable from outside.
    console.error("calendar-authorize failed:", error instanceof Error ? error.message : error);
    return json({ error: "authorization_start_failed" }, 400);
  }
});
