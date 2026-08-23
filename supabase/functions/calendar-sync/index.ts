// calendar-sync — turns a stored refresh token into calendar events the app can import.
//
// This has to run server-side, and not because of the network calls. The refresh token is encrypted
// with INTEGRATION_TOKEN_KEY, which is a function secret; the device cannot decrypt it, so the
// device cannot talk to the provider. That is the design working as intended — a phone that could
// read the token would be a phone that holds long-lived access to the owner's whole calendar.
//
// So the split is: this function holds the credential and speaks the provider's protocol, and
// returns plain events. The app decides what to store. Nothing about the owner's calendar is
// persisted server-side by this function — it reads, normalises, and hands back.
//
// verify_jwt stays ON (no config.toml entry). The row is selected with the caller's own JWT through
// `integrations_self_all`, so RLS is what guarantees one user cannot sync another's calendar; the
// service role is never used for the lookup here. Doing it the other way — service role plus a
// user id from the body — is the same mistake calendar-authorize avoids at the other end.
//
// ## Incremental by default, full only when forced
//
// Both providers offer a delta protocol and both express it as an opaque token, which is why
// integration_accounts.cursor exists as a single text column. Google returns a nextSyncToken and
// invalidates it with HTTP 410; Microsoft returns an @odata.deltaLink. Neither token can be
// combined with a changed query, so the date range is fixed at connect time and only a full sync
// may set one — asking for a narrower window on an incremental round is what silently drops events.
//
// A 410 is not an error to report. It means the token aged out, and the only correct response is to
// discard it and do a full sync, which is why `fullSyncRequired` is handled here rather than
// surfaced to the app as a failure the owner can do nothing about.
import { decryptSecret } from "../_shared/crypto.ts";
import { json, requiredEnv } from "../_shared/http.ts";
import { authenticatedClient, postgresHexToBase64, requireUser } from "../_shared/supabase.ts";

type Provider = "google_calendar" | "outlook_calendar";

/** One event in Norfold's vocabulary, ready for the app to write as a calendar_events row. */
type SyncedEvent = {
  externalId: string;
  title: string;
  description: string;
  startAt: number;
  endAt: number;
  allDay: boolean;
  deleted: boolean;
};

// How far back and forward a sync looks. Fixed rather than configurable because both providers bake
// the range into the delta token: changing it later would silently produce a window mismatch
// between what the token tracks and what the caller expects.
const WindowPastDays = 30;
const WindowFutureDays = 365;
const PageSize = 250;
// A hard stop on paging. A provider that keeps returning a next page would otherwise hold the
// function until the platform kills it, which looks like a hang rather than a bounded failure.
const MaxPages = 20;

function providerFrom(value: unknown): Provider {
  if (value === "google_calendar" || value === "outlook_calendar") return value;
  throw new Error(`Unsupported calendar provider: ${String(value)}`);
}

async function refreshAccessToken(provider: Provider, refreshToken: string): Promise<string> {
  const google = provider === "google_calendar";
  const response = await fetch(
    google ? "https://oauth2.googleapis.com/token" : "https://login.microsoftonline.com/common/oauth2/v2.0/token",
    {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "refresh_token",
        refresh_token: refreshToken,
        client_id: requiredEnv(google ? "GOOGLE_CALENDAR_CLIENT_ID" : "MICROSOFT_CALENDAR_CLIENT_ID"),
        client_secret: requiredEnv(google ? "GOOGLE_CALENDAR_CLIENT_SECRET" : "MICROSOFT_CALENDAR_CLIENT_SECRET"),
      }),
    },
  );
  const result = await response.json();
  if (!response.ok || typeof result.access_token !== "string") {
    // invalid_grant means the owner revoked access or changed their password. It is reported
    // distinctly because it is the one failure the app should act on by clearing the connection
    // rather than retrying — a retry loop against a revoked token never recovers.
    if (result.error === "invalid_grant") throw new RevokedError();
    throw new Error(`Token refresh failed: ${result.error_description ?? result.error ?? response.status}`);
  }
  return result.access_token as string;
}

class RevokedError extends Error {
  constructor() {
    super("Provider access was revoked");
  }
}

/** Marks a cursor that the provider refused; the caller re-runs without one. */
class ExpiredCursorError extends Error {
  constructor() {
    super("Sync cursor expired");
  }
}

function windowBounds(): { start: Date; end: Date } {
  const now = Date.now();
  return {
    start: new Date(now - WindowPastDays * 86_400_000),
    end: new Date(now + WindowFutureDays * 86_400_000),
  };
}

/**
 * Reads a Google date-time, which is either `dateTime` (an instant) or `date` (an all-day value).
 *
 * An all-day event's `date` is a calendar date with no zone, and its exclusive end is the *next*
 * day. Parsing it as an instant would place a single-day event at UTC midnight and, for an owner
 * east or west of UTC, show it on the wrong day — the classic off-by-one that only appears for
 * users outside the server's timezone.
 */
function googleInstant(value: { dateTime?: string; date?: string } | undefined): { at: number; allDay: boolean } | null {
  if (!value) return null;
  if (value.dateTime) return { at: Date.parse(value.dateTime), allDay: false };
  if (value.date) return { at: Date.parse(`${value.date}T00:00:00Z`), allDay: true };
  return null;
}

async function syncGoogle(accessToken: string, cursor: string | null): Promise<{ events: SyncedEvent[]; cursor: string | null }> {
  const events: SyncedEvent[] = [];
  const bounds = windowBounds();
  let pageToken: string | null = null;
  let nextCursor: string | null = null;

  for (let page = 0; page < MaxPages; page += 1) {
    const url = new URL("https://www.googleapis.com/calendar/v3/calendars/primary/events");
    url.searchParams.set("maxResults", String(PageSize));
    // singleEvents expands a recurring series into its occurrences. Without it a weekly meeting
    // arrives as one row with a recurrence rule this function does not interpret, and the owner
    // sees a single event where they expected fifty.
    url.searchParams.set("singleEvents", "true");
    if (cursor) {
      // A syncToken cannot be combined with timeMin/timeMax — the API rejects the request. The
      // range is already baked into the token from the full sync that produced it.
      url.searchParams.set("syncToken", cursor);
    } else {
      url.searchParams.set("timeMin", bounds.start.toISOString());
      url.searchParams.set("timeMax", bounds.end.toISOString());
    }
    if (pageToken) url.searchParams.set("pageToken", pageToken);

    const response = await fetch(url, { headers: { authorization: `Bearer ${accessToken}` } });
    // 410 GONE / fullSyncRequired: the token aged out. Discard it and start over; this is routine,
    // not a failure.
    if (response.status === 410) throw new ExpiredCursorError();
    const payload = await response.json();
    if (!response.ok) throw new Error(`Google calendar sync failed: ${payload?.error?.message ?? response.status}`);

    for (const item of payload.items ?? []) {
      const externalId = typeof item.id === "string" ? item.id : null;
      if (!externalId) continue;
      // A cancelled occurrence arrives with status 'cancelled' and usually nothing else, so it is
      // reported as a deletion before any attempt to read its times.
      if (item.status === "cancelled") {
        events.push({ externalId, title: "", description: "", startAt: 0, endAt: 0, allDay: false, deleted: true });
        continue;
      }
      const start = googleInstant(item.start);
      const end = googleInstant(item.end);
      if (!start || Number.isNaN(start.at)) continue;
      const endAt = end && !Number.isNaN(end.at) ? end.at : start.at;
      events.push({
        externalId,
        title: typeof item.summary === "string" ? item.summary : "",
        description: typeof item.description === "string" ? item.description : "",
        startAt: start.at,
        // An all-day end is exclusive: a one-day event ends on the following date. Backing off a
        // millisecond keeps it inside the day the owner sees it on.
        endAt: start.allDay && endAt > start.at ? endAt - 1 : endAt,
        allDay: start.allDay,
        deleted: false,
      });
    }
    pageToken = typeof payload.nextPageToken === "string" ? payload.nextPageToken : null;
    // nextSyncToken only appears on the final page of a round; taking it earlier would skip
    // everything on the pages after it.
    if (typeof payload.nextSyncToken === "string") nextCursor = payload.nextSyncToken;
    if (!pageToken) break;
  }
  return { events, cursor: nextCursor };
}

async function syncMicrosoft(accessToken: string, cursor: string | null): Promise<{ events: SyncedEvent[]; cursor: string | null }> {
  const events: SyncedEvent[] = [];
  const bounds = windowBounds();
  let next: string | null = cursor ??
    `https://graph.microsoft.com/v1.0/me/calendarView/delta?startDateTime=${bounds.start.toISOString()}&endDateTime=${bounds.end.toISOString()}`;
  let deltaLink: string | null = null;

  for (let page = 0; page < MaxPages && next; page += 1) {
    // Annotated rather than inlined: `next` is reassigned from the response body further down, so
    // passing it straight to fetch makes its type depend on its own initializer.
    const requestUrl: string = next;
    const response = await fetch(requestUrl, {
      headers: {
        authorization: `Bearer ${accessToken}`,
        prefer: `odata.maxpagesize=${PageSize}`,
      },
    });
    // Graph invalidates a delta token with 410 as well; same routine recovery as Google.
    if (response.status === 410) throw new ExpiredCursorError();
    const payload = await response.json();
    if (!response.ok) throw new Error(`Outlook calendar sync failed: ${payload?.error?.message ?? response.status}`);

    for (const item of payload.value ?? []) {
      const externalId = typeof item.id === "string" ? item.id : null;
      if (!externalId) continue;
      // A deletion arrives as @removed with a reason. Note that Graph also reports events that
      // moved *outside* the requested range this way, which is correct to treat as a deletion here:
      // the app's copy covers the same window, so an event that left it should leave the app too.
      if (item["@removed"]) {
        events.push({ externalId, title: "", description: "", startAt: 0, endAt: 0, allDay: false, deleted: true });
        continue;
      }
      // Graph omits the offset on these when it is UTC, so Z is appended rather than assumed. Left
      // as-is, Date.parse would read the value as local time on whatever the runtime's zone is.
      const startAt = Date.parse(withZone(item.start?.dateTime, item.start?.timeZone));
      const rawEnd = Date.parse(withZone(item.end?.dateTime, item.end?.timeZone));
      if (Number.isNaN(startAt)) continue;
      const allDay = item.isAllDay === true;
      const endAt = Number.isNaN(rawEnd) ? startAt : rawEnd;
      events.push({
        externalId,
        title: typeof item.subject === "string" ? item.subject : "",
        // bodyPreview rather than body: body is HTML, and Norfold stores a Markdown description.
        description: typeof item.bodyPreview === "string" ? item.bodyPreview : "",
        startAt,
        endAt: allDay && endAt > startAt ? endAt - 1 : endAt,
        allDay,
        deleted: false,
      });
    }
    next = typeof payload["@odata.nextLink"] === "string" ? payload["@odata.nextLink"] : null;
    if (typeof payload["@odata.deltaLink"] === "string") deltaLink = payload["@odata.deltaLink"];
  }
  return { events, cursor: deltaLink };
}

/**
 * Graph renders `dateTime` without an offset and names the zone separately. Only the UTC case can
 * be repaired here; a floating local time in another zone is left for Date.parse, because guessing
 * an offset would move the event silently rather than visibly.
 */
function withZone(dateTime: unknown, timeZone: unknown): string {
  if (typeof dateTime !== "string") return "";
  if (/[Zz]$|[+-]\d{2}:?\d{2}$/.test(dateTime)) return dateTime;
  return timeZone === "UTC" || timeZone === undefined ? `${dateTime}Z` : dateTime;
}

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return new Response("ok");
  try {
    if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);
    await requireUser(request);
    const body = await request.json().catch(() => ({}));
    const provider = providerFrom(body?.provider);

    // RLS (integrations_self_all) restricts this to the caller's own row, which is why the caller's
    // client is used and not the service role.
    const supabase = authenticatedClient(request);
    const { data, error } = await supabase
      .from("integration_accounts")
      .select("id, encrypted_refresh_token, token_nonce, cursor, revoked_at")
      .eq("provider", provider)
      .is("revoked_at", null)
      .limit(1);
    if (error) throw error;
    const account = data?.[0];
    if (!account) return json({ error: "not_connected" }, 404);

    const refreshToken = await decryptSecret(
      postgresHexToBase64(account.encrypted_refresh_token),
      postgresHexToBase64(account.token_nonce),
      requiredEnv("INTEGRATION_TOKEN_KEY"),
    );
    const accessToken = await refreshAccessToken(provider, refreshToken);

    const run = (cursor: string | null) =>
      provider === "google_calendar" ? syncGoogle(accessToken, cursor) : syncMicrosoft(accessToken, cursor);
    let result: { events: SyncedEvent[]; cursor: string | null };
    let full = !account.cursor;
    try {
      result = await run(account.cursor ?? null);
    } catch (cursorError) {
      if (!(cursorError instanceof ExpiredCursorError)) throw cursorError;
      // The token aged out. A full sync is the documented recovery, and the app is told so it can
      // reconcile deletions it will not receive as delta entries.
      full = true;
      result = await run(null);
    }

    // Only the opaque cursor is written back. A round that returned no cursor leaves the previous
    // one in place rather than clearing it: overwriting with null would force a full sync forever.
    if (result.cursor && result.cursor !== account.cursor) {
      const { error: cursorWriteError } = await supabase
        .from("integration_accounts")
        .update({ cursor: result.cursor, updated_at: new Date().toISOString() })
        .eq("id", account.id);
      if (cursorWriteError) throw cursorWriteError;
    }
    return json({ provider, fullSync: full, events: result.events });
  } catch (error) {
    console.error("calendar-sync failed:", error instanceof Error ? error.message : error);
    // Revocation is the one case the app must distinguish: it should drop the connection instead of
    // retrying a token that will never work again.
    if (error instanceof RevokedError) return json({ error: "revoked" }, 409);
    return json({ error: "calendar_sync_failed" }, 400);
  }
});
