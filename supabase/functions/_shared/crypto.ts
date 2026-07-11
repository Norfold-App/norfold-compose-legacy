const encoder = new TextEncoder();

function base64UrlToBytes(value: string): Uint8Array<ArrayBuffer> {
  const padded = value.replaceAll("-", "+").replaceAll("_", "/").padEnd(Math.ceil(value.length / 4) * 4, "=");
  return base64ToBytes(padded);
}

function base64ToBytes(value: string): Uint8Array<ArrayBuffer> {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
  return bytes;
}

function bytesToBase64(value: Uint8Array): string {
  let binary = "";
  value.forEach((byte) => binary += String.fromCharCode(byte));
  return btoa(binary);
}

export async function verifySignedState<T>(state: string, secret: string): Promise<T> {
  const [payload, signature] = state.split(".");
  if (!payload || !signature) throw new Error("Invalid authorization state");
  const key = await crypto.subtle.importKey("raw", encoder.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
  const valid = await crypto.subtle.verify("HMAC", key, base64UrlToBytes(signature), encoder.encode(payload));
  if (!valid) throw new Error("Authorization state signature mismatch");
  const parsed = JSON.parse(new TextDecoder().decode(base64UrlToBytes(payload))) as T & { expiresAt?: number };
  if (parsed.expiresAt && parsed.expiresAt < Date.now()) throw new Error("Authorization state expired");
  return parsed;
}

export async function encryptSecret(plaintext: string, encodedKey: string): Promise<{ ciphertext: string; nonce: string }> {
  const rawKey = base64ToBytes(encodedKey);
  if (rawKey.length !== 32) throw new Error("INTEGRATION_TOKEN_KEY must be a base64-encoded 256-bit key");
  const key = await crypto.subtle.importKey("raw", rawKey, "AES-GCM", false, ["encrypt"]);
  const nonce = crypto.getRandomValues(new Uint8Array(12));
  const encrypted = await crypto.subtle.encrypt({ name: "AES-GCM", iv: nonce }, key, encoder.encode(plaintext));
  return { ciphertext: bytesToBase64(new Uint8Array(encrypted)), nonce: bytesToBase64(nonce) };
}

export async function decryptSecret(ciphertext: string, nonce: string, encodedKey: string): Promise<string> {
  const rawKey = base64ToBytes(encodedKey);
  if (rawKey.length !== 32) throw new Error("INTEGRATION_TOKEN_KEY must be a base64-encoded 256-bit key");
  const key = await crypto.subtle.importKey("raw", rawKey, "AES-GCM", false, ["decrypt"]);
  const plaintext = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: base64ToBytes(nonce) },
    key,
    base64ToBytes(ciphertext),
  );
  return new TextDecoder().decode(plaintext);
}
