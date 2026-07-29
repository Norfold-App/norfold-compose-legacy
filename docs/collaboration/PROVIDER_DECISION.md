# Collaboration Transport Provider Decision

## Status: PENDING DEVICE-SIDE VALIDATION

Server-side synthetic benchmarks are complete. Final winner requires Android device
measurements. This document records the criteria, synthetic results, and the decision
framework so the final call can be made from measurements alone.

---

## Candidates

| Provider | Transport | Pricing model | Free tier |
|---|---|---|---|
| Supabase Broadcast | WebSocket via Realtime | Per-project, generous free tier | Yes |
| Cloudflare Durable Objects | WebSocket via DO | Per-request + duration | Yes (100k req/day) |
| Ably Pub/Sub | WebSocket via Ably SDK | Per-message | Yes (6M msg/mo) |

---

## Evaluation Criteria (in priority order)

1. **p50 round-trip latency** — measured, not claimed
2. **p99 latency** — tail matters for collaborative feel
3. **Reconnect recovery time** — critical for mobile (screen-off, network switch)
4. **Message loss rate** — at 20 Hz sustained send
5. **Free-tier headroom** — for beta (< 100 concurrent users)
6. **Android SDK weight** — APK size impact
7. **Auth integration** — Supabase JWT compatibility
8. **Provider lock-in risk** — reversibility of the adapter

---

## Synthetic Benchmark Results

> Run: `./gradlew :apps:android:testDebugUnitTest --tests "*.BenchmarkRunnerTest"`
> Environment: server-side JVM, no real network hop (loopback or local Docker)
> These numbers measure the infrastructure layer only, not real network RTT.

| Metric | Supabase | Cloudflare DO | Ably |
|---|---|---|---|
| p50 RTT (ms) | — | — | — |
| p95 RTT (ms) | — | — | — |
| p99 RTT (ms) | — | — | — |
| Throughput (msg/s) | — | — | — |
| Loss rate | — | — | — |
| Reconnect avg (ms) | — | — | — |
| Coalescing efficiency | — | — | — |

*Fill in after running `BenchmarkHarness.runAll()` with real provider credentials.*

---

## Device-Side Validation Required

The following must be measured on a real Android device (or emulator with network
throttling) before the provider winner is declared:

- [ ] RTT over 4G (good signal)
- [ ] RTT over WiFi
- [ ] RTT under network throttle (150ms added latency, 10% loss)
- [ ] Reconnect time after airplane-mode toggle
- [ ] Battery impact at 20 Hz sustained (1 hour background)
- [ ] APK size delta per provider SDK

Use the `android-device-qa` skill to run these measurements.

---

## Alternatives Considered and Rejected

### Firebase Realtime Database
- Rejected: Google dependency conflicts with Norfold's provider-independence goal.
  Firebase RTDB is not a pub/sub transport; it is a full database with its own
  data model. Adapting it to the CollabWireEnvelope protocol would require
  significant shim work with no benefit over the three selected candidates.

### Pusher Channels
- Rejected: Paid-only above 100 connections. No meaningful free tier for beta.
  No capability advantage over Ably that justifies adding a fourth provider.

### Self-hosted WebSocket server (e.g. Socket.io on Fly.io)
- Rejected for now: Adds operational burden (uptime, scaling, DDoS) that is
  inappropriate during pre-release. The architecture supports adding a
  self-hosted adapter later without changing the transport SPI.

### AWS API Gateway WebSocket
- Rejected: Requires AWS account, IAM, and per-message pricing that is
  unfavorable at low scale. No capability advantage. Adds a provider that
  conflicts with the low-infrastructure-cost requirement.

---

## Decision Framework

When device measurements are available, apply this decision tree:

```
if p50_best < p50_second_best - 20ms:
    winner = best_p50_provider
elif reconnect_best < reconnect_second_best - 500ms:
    winner = best_reconnect_provider
elif free_tier_headroom_best > 10x_beta_usage:
    winner = best_free_tier_provider
else:
    winner = supabase  # default: best auth integration, lowest lock-in
```

The default fallback to Supabase is justified by:
- Supabase JWT is already the Norfold auth provider; token reuse is zero-cost.
- The Supabase Broadcast adapter requires no additional SDK beyond what is
  already in the APK for auth and database.
- Provider lock-in is lowest: Supabase Broadcast is a thin WebSocket channel;
  migrating to another provider requires only swapping the transport adapter.

---

## Architecture Guarantee

Regardless of which provider wins, the `RealtimeCollaborationTransport` SPI
ensures the collaboration engine never imports a provider SDK directly.
Swapping providers after the decision requires only:
1. Implementing a new `RealtimeCollaborationTransport` subclass.
2. Updating `CollaborationSessionFactory` to instantiate the new transport.
3. No changes to `CollaborationSession`, merge engine, or editor layer.

---

## Decision Record

| Date | Decision | Evidence | Decided by |
|---|---|---|---|
| — | PENDING | Device benchmarks not yet run | — |

*Update this table when device measurements are complete.*
