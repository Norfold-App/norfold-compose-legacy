// integration_accounts keys a durable row on (user_id, provider, provider_subject), so the
// subject decides which stored refresh token a later reconnect overwrites. Reading it out of an
// unverified id_token payload — and worse, falling back to one shared literal whenever that
// token was absent or unparseable — let two different provider accounts collapse onto a single
// row, where the second connection silently replaced the first account's refresh token.
//
// The subject therefore comes from the provider's own OIDC userinfo endpoint, authenticated with
// the access token the token exchange just returned. Both providers are already asked for the
// `openid` scope in calendar-authorize, so the endpoint is always available on a successful
// exchange. A subject that cannot be established is an error: no durable identity is better than
// a shared one.

const INVALID_SUBJECT = "Provider identity is unavailable";

export type CalendarProvider = "google_calendar" | "outlook_calendar";

const USERINFO_ENDPOINTS: Record<CalendarProvider, string> = {
  google_calendar: "https://openidconnect.googleapis.com/v1/userinfo",
  outlook_calendar: "https://graph.microsoft.com/oidc/userinfo",
};

export type UserinfoFetch = (endpoint: string, accessToken: string) => Promise<Response>;

const fetchUserinfo: UserinfoFetch = (endpoint, accessToken) =>
  fetch(endpoint, {
    headers: { authorization: `Bearer ${accessToken}`, accept: "application/json" },
  });

export function userinfoEndpoint(provider: CalendarProvider): string {
  return USERINFO_ENDPOINTS[provider];
}

export function parseUserinfoSubject(body: unknown): string {
  const subject = (body as { sub?: unknown } | null | undefined)?.sub;
  if (typeof subject !== "string" || subject.trim().length === 0) {
    throw new Error(INVALID_SUBJECT);
  }
  return subject;
}

export async function providerAccountSubject(
  provider: CalendarProvider,
  accessToken: string,
  userinfo: UserinfoFetch = fetchUserinfo,
): Promise<string> {
  if (!accessToken) throw new Error(INVALID_SUBJECT);
  const endpoint = USERINFO_ENDPOINTS[provider];
  if (!endpoint) throw new Error(INVALID_SUBJECT);
  const response = await userinfo(endpoint, accessToken);
  if (!response.ok) throw new Error(INVALID_SUBJECT);
  const body = await response.json().catch(() => null);
  return parseUserinfoSubject(body);
}
