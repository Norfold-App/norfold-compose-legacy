import { createClient, SupabaseClient, User } from "npm:@supabase/supabase-js@2";
import { requiredEnv } from "./http.ts";

export function serviceClient(): SupabaseClient {
  return createClient(requiredEnv("SUPABASE_URL"), requiredEnv("SUPABASE_SERVICE_ROLE_KEY"), {
    auth: { persistSession: false, autoRefreshToken: false },
  });
}

export function authenticatedClient(request: Request): SupabaseClient {
  const authorization = request.headers.get("Authorization");
  if (!authorization?.startsWith("Bearer ")) throw new Error("Authentication required");
  return createClient(requiredEnv("SUPABASE_URL"), requiredEnv("SUPABASE_ANON_KEY"), {
    global: { headers: { Authorization: authorization } },
    auth: { persistSession: false, autoRefreshToken: false },
  });
}

export async function requireUser(request: Request): Promise<User> {
  const authorization = request.headers.get("Authorization");
  if (!authorization?.startsWith("Bearer ")) throw new Error("Authentication required");
  // Validate the caller's JWT explicitly; getUser() without an argument depends on the
  // client's merged global headers, which is fragile across supabase-js versions.
  const { data, error } = await authenticatedClient(request).auth.getUser(
    authorization.slice("Bearer ".length),
  );
  if (error || !data.user) throw new Error("Invalid authentication token");
  return data.user;
}

export function bytesToPostgresHex(value: Uint8Array): string {
  return `\\x${Array.from(value).map((byte) => byte.toString(16).padStart(2, "0")).join("")}`;
}

export function postgresHexToBase64(value: string): string {
  const hex = value.startsWith("\\x") ? value.slice(2) : value;
  const bytes = new Uint8Array(hex.match(/.{1,2}/g)?.map((pair) => Number.parseInt(pair, 16)) ?? []);
  let binary = "";
  bytes.forEach((byte) => binary += String.fromCharCode(byte));
  return btoa(binary);
}
