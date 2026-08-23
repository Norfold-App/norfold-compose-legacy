// Round-trip tests for the OAuth `state` that calendar-authorize signs and calendar-callback
// verifies. These are the only check on a convention that cannot be observed from either end: the
// signature covers the base64url *text* of the payload, and a signer that covers the JSON bytes
// instead produces a state that looks correct, survives the round trip through the provider, and
// fails verification on return — where the callback deliberately logs the reason and redirects the
// user to a generic `status=error`. The symptom is "calendar connection does not work", with
// nothing in the app to point at.
//
// Run with: deno test supabase/functions/_shared/crypto_test.ts
import { assertEquals, assertRejects } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { decryptSecret, encryptSecret, signState, verifySignedState } from "./crypto.ts";

const secret = "test-calendar-state-secret";
type CallbackState = { userId: string; provider: string; redirectUri: string; expiresAt: number };

function state(overrides: Partial<CallbackState> = {}): CallbackState {
  return {
    userId: "11111111-2222-3333-4444-555555555555",
    provider: "google_calendar",
    redirectUri: "https://example.supabase.co/functions/v1/calendar-callback",
    expiresAt: Date.now() + 600_000,
    ...overrides,
  };
}

Deno.test("a signed state verifies and returns every field intact", async () => {
  const payload = state();
  const verified = await verifySignedState<CallbackState>(await signState(payload, secret), secret);
  assertEquals(verified, payload);
});

Deno.test("the signature covers the encoded payload, not the raw JSON", async () => {
  // The load-bearing pin. If signState ever signs JSON.stringify(payload) directly, this fails
  // here instead of in production against a real provider.
  const signed = await signState(state(), secret);
  const [encoded, signature] = signed.split(".");
  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const expected = new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(encoded)));
  let binary = "";
  expected.forEach((byte) => binary += String.fromCharCode(byte));
  assertEquals(signature, btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", ""));
});

Deno.test("the encoded payload is base64url with no padding", async () => {
  // A '+', '/' or '=' in the state survives signing but is mangled by URL encoding somewhere
  // between here and the provider's redirect, and arrives as a signature mismatch.
  const [encoded, signature] = (await signState(state(), secret)).split(".");
  for (const part of [encoded, signature]) {
    assertEquals(/^[A-Za-z0-9_-]+$/.test(part), true, part);
  }
});

Deno.test("a state signed with a different secret is rejected", async () => {
  const signed = await signState(state(), "some-other-secret");
  await assertRejects(() => verifySignedState<CallbackState>(signed, secret), Error, "signature mismatch");
});

Deno.test("a tampered payload is rejected", async () => {
  // The attack the signature exists to stop: swapping in someone else's user id would otherwise
  // attach the attacker's calendar to that account.
  const [, signature] = (await signState(state(), secret)).split(".");
  const forged = new TextEncoder().encode(JSON.stringify(state({ userId: "victim" })));
  let binary = "";
  forged.forEach((byte) => binary += String.fromCharCode(byte));
  const encoded = btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
  await assertRejects(() => verifySignedState<CallbackState>(`${encoded}.${signature}`, secret), Error);
});

Deno.test("an expired state is rejected", async () => {
  const signed = await signState(state({ expiresAt: Date.now() - 1 }), secret);
  await assertRejects(() => verifySignedState<CallbackState>(signed, secret), Error, "expired");
});

Deno.test("a malformed state is rejected rather than parsed", async () => {
  for (const value of ["", ".", "onlypayload", ".onlysignature"]) {
    await assertRejects(() => verifySignedState<CallbackState>(value, secret), Error);
  }
});

Deno.test("a refresh token survives encrypt and decrypt", async () => {
  // The other half of what calendar-callback stores. A key of the wrong length must fail loudly
  // rather than write a row nothing can ever read back.
  const key = btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(32))));
  const token = "1//0eXAMPLEreFRESHtoken_with-url_safe.chars";
  const { ciphertext, nonce } = await encryptSecret(token, key);
  assertEquals(await decryptSecret(ciphertext, nonce, key), token);
  await assertRejects(() => encryptSecret(token, btoa("too-short")), Error, "256-bit key");
});
