import {
  assertEquals,
  assertRejects,
} from "https://deno.land/std@0.224.0/assert/mod.ts";
import {
  type GoogleIdTokenClaims,
  type GoogleIdTokenVerifier,
  parseBearerToken,
  verifyPubSubIdentity,
} from "./google_oidc.ts";

const audience = "https://example.supabase.co/functions/v1/play-rtdn";
const email = "norfold-pubsub@example.iam.gserviceaccount.com";
const now = 1_800_000_000;

function claims(
  overrides: Partial<GoogleIdTokenClaims> = {},
): GoogleIdTokenClaims {
  return {
    aud: audience,
    email,
    email_verified: true,
    exp: now + 3600,
    iss: "https://accounts.google.com",
    ...overrides,
  };
}

function verifier(
  payload: GoogleIdTokenClaims | undefined,
): GoogleIdTokenVerifier {
  return () => Promise.resolve(payload);
}

Deno.test("Pub/Sub identity verification passes the exact bearer token and audience", async () => {
  let receivedToken = "";
  let receivedAudience = "";
  await verifyPubSubIdentity(
    "Bearer signed.jwt.value",
    audience,
    email,
    (token, targetAudience) => {
      receivedToken = token;
      receivedAudience = targetAudience;
      return Promise.resolve(claims());
    },
    now,
  );
  assertEquals(receivedToken, "signed.jwt.value");
  assertEquals(receivedAudience, audience);
});

Deno.test("bearer parsing rejects ambiguous and malformed authorization values", () => {
  assertEquals(parseBearerToken("Bearer signed.jwt.value"), "signed.jwt.value");
  for (
    const value of [
      null,
      "",
      "Basic abc",
      "signed.jwt.value",
      "Bearer ",
      "Bearer one two",
      "Bearer one,two",
    ]
  ) {
    try {
      parseBearerToken(value);
      throw new Error(`accepted malformed authorization value: ${value}`);
    } catch (error) {
      assertEquals((error as Error).message, "Invalid Pub/Sub identity token");
    }
  }
});

Deno.test("verifier failures are returned as a generic identity error", async () => {
  await assertRejects(
    () =>
      verifyPubSubIdentity(
        "Bearer forged",
        audience,
        email,
        () => Promise.reject(new Error("bad signature")),
        now,
      ),
    Error,
    "Invalid Pub/Sub identity token",
  );
});

Deno.test("wrong audience and issuer are rejected", async () => {
  for (
    const payload of [
      claims({ aud: "https://another-service.example" }),
      claims({ iss: "https://attacker.example" }),
      claims({ iss: undefined }),
    ]
  ) {
    await assertRejects(
      () =>
        verifyPubSubIdentity(
          "Bearer token",
          audience,
          email,
          verifier(payload),
          now,
        ),
      Error,
      "Invalid Pub/Sub identity token",
    );
  }
});

Deno.test("expired and missing expiry claims are rejected", async () => {
  for (
    const payload of [
      claims({ exp: now - 1 }),
      claims({ exp: now }),
      claims({ exp: undefined }),
    ]
  ) {
    await assertRejects(
      () =>
        verifyPubSubIdentity(
          "Bearer token",
          audience,
          email,
          verifier(payload),
          now,
        ),
      Error,
      "Invalid Pub/Sub identity token",
    );
  }
});

Deno.test("unexpected or unverified service-account identities are rejected", async () => {
  for (
    const payload of [
      claims({ email: "other@example.iam.gserviceaccount.com" }),
      claims({ email: undefined }),
      claims({ email_verified: false }),
      claims({ email_verified: undefined }),
      claims({ email_verified: "true" }),
      undefined,
    ]
  ) {
    await assertRejects(
      () =>
        verifyPubSubIdentity(
          "Bearer token",
          audience,
          email,
          verifier(payload),
          now,
        ),
      Error,
      "Invalid Pub/Sub identity token",
    );
  }
});

Deno.test("missing verifier configuration fails closed", async () => {
  for (
    const [configuredAudience, configuredEmail] of [["", email], [audience, ""]]
  ) {
    await assertRejects(
      () =>
        verifyPubSubIdentity(
          "Bearer token",
          configuredAudience,
          configuredEmail,
          verifier(claims()),
          now,
        ),
      Error,
      "Invalid Pub/Sub identity token",
    );
  }
});
