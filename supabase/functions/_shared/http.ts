// CORS only gates browsers — the native app talks to these functions directly and ignores
// it. No web client exists today, so no origin is allowed by default; a future web app gets
// in by setting the ALLOWED_WEB_ORIGIN function secret to its exact origin. The previous
// wildcard let any website drive a logged-in visitor's JWT against every function.
const allowedWebOrigin = Deno.env.get("ALLOWED_WEB_ORIGIN")?.trim();

export const corsHeaders = {
  ...(allowedWebOrigin ? { "access-control-allow-origin": allowedWebOrigin } : {}),
  "access-control-allow-headers": "authorization, apikey, content-type, stripe-signature",
  "access-control-allow-methods": "GET, POST, OPTIONS",
};

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "content-type": "application/json; charset=utf-8" },
  });
}

export function requiredEnv(name: string): string {
  const value = Deno.env.get(name);
  if (!value) throw new Error(`Missing server configuration: ${name}`);
  return value;
}
