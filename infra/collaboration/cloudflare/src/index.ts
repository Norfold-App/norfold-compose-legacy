import { DurableObject } from "cloudflare:workers";
import { createRemoteJWKSet, jwtVerify } from "jose";

interface Env {
  COLLAB_ROOM: DurableObjectNamespace<CollabRoom>;
  SUPABASE_URL: string;
  BENCHMARK_ONLY: string;
  BENCHMARK_SHARED_SECRET?: string;
}

type SocketAttachment = {
  userId: string;
  roomId: string;
  connectedAt: number;
  isBenchmark: boolean;
};

type CollabEnvelope = {
  type: string;
  seq?: number;
  targetId?: string;
  payload?: unknown;
  clientSentAt?: number;
};

type PeerInfo = {
  userId: string;
  connectedAt: number;
};

const MAX_WS_MESSAGE_BYTES = 32 * 1024;
const MAX_PEERS_PER_ROOM = 32;
const RATE_LIMIT_WINDOW_MS = 1_000;
const RATE_LIMIT_MAX_MESSAGES = 60; // 60 msg/s per connection
const HEARTBEAT_INTERVAL_MS = 25_000;

const jwksCache = new Map<string, ReturnType<typeof createRemoteJWKSet>>();

function bearer(request: Request): string | null {
  const value = request.headers.get("authorization");
  return value?.startsWith("Bearer ") ? (value.slice(7).trim() || null) : null;
}

async function authenticate(request: Request, env: Env, roomId: string): Promise<string> {
  const token = bearer(request);
  if (!token) throw new Response("missing_bearer", { status: 401 });

  if (roomId.startsWith("bench:")) {
    if (!env.BENCHMARK_SHARED_SECRET || token !== env.BENCHMARK_SHARED_SECRET) {
      throw new Response("invalid_benchmark_bearer", { status: 401 });
    }
    return `benchmark-user-${crypto.randomUUID().slice(0, 8)}`;
  }

  if (env.BENCHMARK_ONLY === "true") throw new Response("production_rooms_disabled", { status: 403 });

  const issuer = `${env.SUPABASE_URL.replace(/\/$/, "")}/auth/v1`;
  const jwksUrl = `${issuer}/.well-known/jwks.json`;
  let jwks = jwksCache.get(jwksUrl);
  if (!jwks) {
    jwks = createRemoteJWKSet(new URL(jwksUrl));
    jwksCache.set(jwksUrl, jwks);
  }
  const verified = await jwtVerify(token, jwks, { issuer, audience: "authenticated" });
  const userId = verified.payload.sub;
  if (!userId) throw new Response("jwt_missing_sub", { status: 401 });

  // Authentication is NOT authorization. Before production rooms are enabled,
  // add a fail-closed Norfold artifact/workspace authorization check here.
  throw new Response("artifact_authorization_not_wired", { status: 403 });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname === "/health") {
      return Response.json({
        ok: true,
        service: "norfold-collab-edge",
        benchmarkOnly: env.BENCHMARK_ONLY === "true",
        ts: Date.now(),
      });
    }

    // Benchmark latency probe — no DO hop, just measures edge RTT.
    if (url.pathname === "/bench/ping") {
      const token = bearer(request);
      if (!env.BENCHMARK_SHARED_SECRET || token !== env.BENCHMARK_SHARED_SECRET) {
        return new Response("invalid_benchmark_bearer", { status: 401 });
      }
      return Response.json({ pong: true, serverAt: Date.now() });
    }

    const prefix = "/v1/collab/";
    if (!url.pathname.startsWith(prefix)) return new Response("not_found", { status: 404 });
    const roomId = decodeURIComponent(url.pathname.slice(prefix.length));
    if (!roomId) return new Response("missing_room", { status: 400 });

    let userId: string;
    try { userId = await authenticate(request, env, roomId); }
    catch (error) { return error instanceof Response ? error : new Response("authentication_failed", { status: 401 }); }

    const stub = env.COLLAB_ROOM.get(env.COLLAB_ROOM.idFromName(roomId));
    const forwarded = new Request(request);
    forwarded.headers.set("x-norfold-user-id", userId);
    forwarded.headers.set("x-norfold-room-id", roomId);
    forwarded.headers.set("x-norfold-is-benchmark", roomId.startsWith("bench:") ? "1" : "0");
    return stub.fetch(forwarded);
  },
} satisfies ExportedHandler<Env>;

export class CollabRoom extends DurableObject<Env> {
  // Per-room rate-limit state: socketId -> { windowStart, count }
  private rateLimits = new Map<WebSocket, { windowStart: number; count: number }>();
  // Metrics
  private messagesRelayed = 0;
  private messagesDropped = 0;
  private peakPeers = 0;

  async fetch(request: Request): Promise<Response> {
    if (request.headers.get("upgrade")?.toLowerCase() !== "websocket") {
      return new Response("websocket_required", { status: 426 });
    }

    const userId = request.headers.get("x-norfold-user-id");
    const roomId = request.headers.get("x-norfold-room-id");
    const isBenchmark = request.headers.get("x-norfold-is-benchmark") === "1";
    if (!userId || !roomId) return new Response("missing_internal_auth_context", { status: 403 });

    // Capacity check
    const currentPeers = this.ctx.getWebSockets().length;
    if (currentPeers >= MAX_PEERS_PER_ROOM) {
      return new Response("room_full", { status: 503 });
    }

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment({ userId, roomId, connectedAt: Date.now(), isBenchmark } satisfies SocketAttachment);

    const newPeerCount = currentPeers + 1;
    if (newPeerCount > this.peakPeers) this.peakPeers = newPeerCount;

    // Announce join to existing peers (not to the joining socket itself)
    this.broadcast(
      { type: "peer.joined", userId, roomId, peerCount: newPeerCount, serverAt: Date.now() },
      server,
    );

    // Send current peer list to the joining socket
    const peers: PeerInfo[] = this.ctx.getWebSockets()
      .filter(ws => ws !== server)
      .map(ws => {
        const a = ws.deserializeAttachment() as SocketAttachment | null;
        return a ? { userId: a.userId, connectedAt: a.connectedAt } : null;
      })
      .filter((p): p is PeerInfo => p !== null);

    server.send(JSON.stringify({
      type: "room.state",
      roomId,
      peers,
      peerCount: newPeerCount,
      serverAt: Date.now(),
    }));

    // Schedule heartbeat alarm if not already set
    const currentAlarm = await this.ctx.storage.getAlarm();
    if (currentAlarm === null) {
      await this.ctx.storage.setAlarm(Date.now() + HEARTBEAT_INTERVAL_MS);
    }

    return new Response(null, { status: 101, webSocket: client });
  }

  async alarm(): Promise<void> {
    const sockets = this.ctx.getWebSockets();
    if (sockets.length === 0) return; // room empty, let alarm expire
    const ping = JSON.stringify({ type: "ping", serverAt: Date.now() });
    for (const ws of sockets) {
      try { ws.send(ping); } catch {}
    }
    await this.ctx.storage.setAlarm(Date.now() + HEARTBEAT_INTERVAL_MS);
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    const bytes = typeof message === "string"
      ? new TextEncoder().encode(message).byteLength
      : message.byteLength;

    if (bytes > MAX_WS_MESSAGE_BYTES) {
      ws.close(1009, "message_too_large");
      return;
    }

    const attachment = ws.deserializeAttachment() as SocketAttachment | null;
    if (!attachment) { ws.close(1011, "missing_socket_attachment"); return; }

    // Rate limiting
    if (!this.checkRateLimit(ws)) {
      this.messagesDropped++;
      ws.send(JSON.stringify({ type: "error", code: "rate_limited", retryAfterMs: RATE_LIMIT_WINDOW_MS }));
      return;
    }

    let parsed: CollabEnvelope;
    try {
      parsed = JSON.parse(typeof message === "string" ? message : new TextDecoder().decode(message));
    } catch {
      ws.send(JSON.stringify({ type: "error", code: "invalid_json" }));
      return;
    }

    if (!parsed.type || typeof parsed.type !== "string") {
      ws.send(JSON.stringify({ type: "error", code: "missing_type" }));
      return;
    }

    // Pong response to client-initiated ping
    if (parsed.type === "ping") {
      ws.send(JSON.stringify({ type: "pong", seq: parsed.seq, serverAt: Date.now(), clientSentAt: parsed.clientSentAt }));
      return;
    }

    const outbound = {
      ...parsed,
      actorUserId: attachment.userId,
      roomId: attachment.roomId,
      serverAt: Date.now(),
    };

    if (attachment.isBenchmark && parsed.type === "bench.ping") {
      // Benchmark echo: send back to sender only (RTT measurement)
      ws.send(JSON.stringify({ ...outbound, type: "bench.pong" }));
      return;
    }

    this.broadcast(outbound, ws);
    this.messagesRelayed++;
  }

  async webSocketClose(ws: WebSocket): Promise<void> {
    this.rateLimits.delete(ws);
    const a = ws.deserializeAttachment() as SocketAttachment | null;
    if (a) {
      const remaining = this.ctx.getWebSockets().filter(s => s !== ws).length;
      this.broadcast(
        { type: "peer.left", userId: a.userId, roomId: a.roomId, peerCount: remaining, serverAt: Date.now() },
        ws,
      );
    }
  }

  async webSocketError(ws: WebSocket): Promise<void> {
    this.rateLimits.delete(ws);
    try { ws.close(1011, "websocket_error"); } catch {}
  }

  private broadcast(payload: unknown, exclude?: WebSocket): void {
    const encoded = JSON.stringify(payload);
    for (const socket of this.ctx.getWebSockets()) {
      if (socket === exclude) continue;
      try { socket.send(encoded); } catch {}
    }
  }

  private checkRateLimit(ws: WebSocket): boolean {
    const now = Date.now();
    let state = this.rateLimits.get(ws);
    if (!state || now - state.windowStart >= RATE_LIMIT_WINDOW_MS) {
      state = { windowStart: now, count: 0 };
      this.rateLimits.set(ws, state);
    }
    state.count++;
    return state.count <= RATE_LIMIT_MAX_MESSAGES;
  }
}
