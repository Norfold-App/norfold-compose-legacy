# Authorization Contract

## Status: SCAFFOLDED — NOT YET WIRED FOR PRODUCTION

This document defines the authorization model for the Norfold collaboration transport layer.
Authentication (who you are) and authorization (what you may access) are separate concerns.
The current implementation enforces authentication; artifact authorization is scaffolded but
not yet wired to the Norfold workspace/artifact permission model.

---

## Principles

1. **Fail-closed by default.** Any request that cannot be positively authorized is rejected.
   The Worker returns 403 for production rooms until artifact authorization is wired.

2. **Short-lived tokens.** Collaboration tokens are scoped to a specific room and expire
   quickly. Long-lived credentials never reach the transport layer.

3. **No API keys on device.** Android clients never hold a provider API key. They hold a
   Supabase JWT (already required for the app) and exchange it for a scoped collaboration
   token at a server-side issuer endpoint.

4. **Benchmark rooms are isolated.** Rooms prefixed `bench:` use a shared secret from the
   environment. This secret is never compiled into release builds. Benchmark rooms are
   disabled in production deployments (`BENCHMARK_ONLY=false` + no `BENCHMARK_SHARED_SECRET`).

---

## Token Flow (Production — not yet active)

```
Android client
    │
    │  1. Supabase JWT (from existing auth session)
    ▼
Supabase Edge Function: /collab-token
    │  2. Verify JWT
    │  3. Check artifact/workspace authorization (Norfold permission model)
    │  4. Mint short-lived scoped token (provider-specific)
    │     - Ably: Ably Token Request signed server-side
    │     - Cloudflare DO: HMAC-signed room ticket (30s TTL)
    │     - Supabase Broadcast: Supabase JWT is sufficient (same issuer)
    ▼
Android client
    │
    │  5. Present scoped token to transport adapter
    ▼
Transport provider (Ably / Cloudflare DO / Supabase Broadcast)
```

---

## Cloudflare Worker Authentication

The Worker (`infra/collaboration/cloudflare/src/index.ts`) performs:

| Room prefix | Auth method | Status |
|---|---|---|
| `bench:*` | `BENCHMARK_SHARED_SECRET` env var | Implemented |
| All others | Supabase JWT via JWKS | Implemented (auth only) |
| All others | Artifact authorization check | **NOT YET WIRED** |

The artifact authorization stub is at line marked `artifact_authorization_not_wired` in
`index.ts`. Before production rooms are enabled, replace that throw with a call to the
Norfold workspace/artifact permission service.

---

## Android-Side Token Provider

`CollabTokenProvider` is the SPI:

```kotlin
interface CollabTokenProvider {
    suspend fun freshToken(roomId: RoomId): String
}
```

Implementations:
- `AblyTokenIssuerClient` — calls the server-side issuer, presents Supabase JWT, receives
  a scoped Ably token. Never holds an Ably API key.
- `BenchmarkTokenProvider` — reads `NORFOLD_ABLY_BENCH_TOKEN` from env. Never in release.

---

## E2EE Envelope Behavior

The collaboration transport carries encrypted envelopes. The transport layer is
**content-blind**: it routes `CollabWireEnvelope` payloads without inspecting the
`payload` field. The `CollabEnvelopeCrypto` SPI encrypts/decrypts at the session layer
before the envelope reaches the transport.

This means:
- The Cloudflare Worker, Supabase Broadcast relay, and Ably channels never see plaintext
  operation content.
- Provider compromise does not expose operation content.
- Presence metadata (actorId, roomId, eventType, targetId) is visible to the relay.
  This is intentional: the relay needs it for routing. It does not include message content.

---

## What Must Be Done Before Production Rooms Are Enabled

- [ ] Implement artifact/workspace authorization check in the Supabase Edge Function
- [ ] Wire the authorization check into the Cloudflare Worker (replace the throw)
- [ ] Implement `AblyTokenIssuerClient.freshToken` against the real issuer endpoint
- [ ] Add token refresh logic to `CollaborationSession` (re-connect on 401)
- [ ] Audit `CollabWireEnvelope` fields for presence-metadata minimization
- [ ] Security review of the short-lived token TTL and rotation policy
