import { GoogleAuth } from "npm:google-auth-library@9";
import { requiredEnv } from "./http.ts";

export async function serviceAccountAccessToken(secretName: string, scopes: string[]): Promise<string> {
  const credentials = JSON.parse(requiredEnv(secretName));
  const auth = new GoogleAuth({ credentials, scopes });
  const client = await auth.getClient();
  const token = await client.getAccessToken();
  if (!token.token) throw new Error("Google service account did not return an access token");
  return token.token;
}

export async function googleAccessToken(scopes: string[]): Promise<string> {
  return serviceAccountAccessToken("PLAY_SERVICE_ACCOUNT_JSON", scopes);
}

export async function verifyPlaySubscription(packageName: string, productId: string, purchaseToken: string) {
  const token = await googleAccessToken(["https://www.googleapis.com/auth/androidpublisher"]);
  const endpoint = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(packageName)}/purchases/subscriptionsv2/tokens/${encodeURIComponent(purchaseToken)}`;
  const response = await fetch(endpoint, { headers: { authorization: `Bearer ${token}` } });
  const body = await response.json();
  if (!response.ok) throw new Error(`Play verification failed (${response.status}): ${body.error?.message ?? "unknown error"}`);
  const matchingLine = body.lineItems?.find((item: { productId?: string }) => item.productId === productId);
  if (!matchingLine) throw new Error("Verified purchase does not contain the requested subscription product");
  return { body, lineItem: matchingLine };
}
