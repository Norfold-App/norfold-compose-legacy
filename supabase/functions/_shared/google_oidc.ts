const INVALID_TOKEN = "Invalid Pub/Sub identity token";
const ACCEPTED_ISSUERS = new Set([
  "accounts.google.com",
  "https://accounts.google.com",
]);

export type GoogleIdTokenClaims = {
  aud?: string;
  email?: string;
  email_verified?: boolean | string;
  exp?: number;
  iss?: string;
};

export type GoogleIdTokenVerifier = (
  token: string,
  audience: string,
) => Promise<GoogleIdTokenClaims | undefined>;

async function createGoogleClient() {
  const { OAuth2Client } = await import("npm:google-auth-library@9");
  return new OAuth2Client();
}

let googleClientPromise: ReturnType<typeof createGoogleClient> | undefined;

async function verifyWithGoogle(
  token: string,
  audience: string,
): Promise<GoogleIdTokenClaims | undefined> {
  const googleClient = await (googleClientPromise ??= createGoogleClient());
  const ticket = await googleClient.verifyIdToken({ idToken: token, audience });
  return ticket.getPayload();
}

export function parseBearerToken(authorization: string | null): string {
  const match = authorization?.match(/^Bearer ([^\s,]+)$/i);
  if (!match) throw new Error(INVALID_TOKEN);
  return match[1];
}

export async function verifyPubSubIdentity(
  authorization: string | null,
  expectedAudience: string,
  expectedEmail: string,
  verifier: GoogleIdTokenVerifier = verifyWithGoogle,
  nowSeconds = Math.floor(Date.now() / 1000),
): Promise<void> {
  try {
    if (!expectedAudience || !expectedEmail) throw new Error(INVALID_TOKEN);
    const token = parseBearerToken(authorization);
    const claims = await verifier(token, expectedAudience);
    if (
      !claims ||
      claims.aud !== expectedAudience ||
      !claims.iss ||
      !ACCEPTED_ISSUERS.has(claims.iss) ||
      typeof claims.exp !== "number" ||
      claims.exp <= nowSeconds ||
      claims.email !== expectedEmail ||
      claims.email_verified !== true
    ) {
      throw new Error(INVALID_TOKEN);
    }
  } catch {
    throw new Error(INVALID_TOKEN);
  }
}
