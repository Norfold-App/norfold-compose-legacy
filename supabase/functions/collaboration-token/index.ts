import { corsHeaders, json, requiredEnv } from "../_shared/http.ts";
import { authenticatedClient, requireUser } from "../_shared/supabase.ts";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const ROOM = /^artifact:([0-9a-f-]{36}):([0-9a-f-]{36})$/i;

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (request.method !== "POST") return json({ error: "Method not allowed" }, 405);
  try {
    const user = await requireUser(request);
    const body = await request.json();
    const room = String(body.room ?? "");
    const deviceId = String(body.deviceId ?? "");
    const scope = ROOM.exec(room);
    if (!scope || !UUID.test(scope[1]) || !UUID.test(scope[2]) || !UUID.test(deviceId)) {
      return json({ error: "Invalid collaboration scope" }, 400);
    }

    const client = authenticatedClient(request);
    const { data: allowed, error: authorizationError } = await client.rpc(
      "authorize_collaboration_artifact",
      { p_workspace: scope[1], p_artifact: scope[2], p_mutate: true },
    );
    if (authorizationError || allowed !== true) return json({ error: "Artifact access denied" }, 403);
    const { data: device, error: deviceError } = await client
      .from("devices")
      .select("id")
      .eq("id", deviceId)
      .eq("user_id", user.id)
      .maybeSingle();
    if (deviceError || !device) return json({ error: "Device access denied" }, 403);

    const apiKey = requiredEnv("ABLY_API_KEY");
    const keyName = apiKey.split(":", 1)[0];
    if (!keyName || !apiKey.includes(":")) throw new Error("Invalid Ably server configuration");
    const form = new URLSearchParams({
      clientId: `${user.id}:${deviceId}`,
      ttl: "900000",
      // Direct production publishing would let a room member bypass the durable
      // operation RPC. This evaluation token is receive/presence-scoped only.
      capability: JSON.stringify({ [room]: ["subscribe", "presence"] }),
    });
    const tokenResponse = await fetch(
      `https://rest.ably.io/keys/${encodeURIComponent(keyName)}/requestToken`,
      {
        method: "POST",
        headers: {
          authorization: `Basic ${btoa(apiKey)}`,
          "content-type": "application/x-www-form-urlencoded",
        },
        body: form,
      },
    );
    if (!tokenResponse.ok) throw new Error(`Collaboration token provider rejected request (${tokenResponse.status})`);
    const token = await tokenResponse.json();
    if (typeof token?.token !== "string" || !token.token) throw new Error("Collaboration token provider returned no token");
    return json({ token: token.token, expires: token.expires, room, clientId: `${user.id}:${deviceId}` });
  } catch (error) {
    const message = error instanceof Error ? error.message.toLowerCase() : "";
    const authenticationFailure = message === "authentication required" || message === "invalid authentication token";
    return json(
      { error: authenticationFailure ? "Authentication required" : "Collaboration token unavailable" },
      authenticationFailure ? 401 : 503,
    );
  }
});
