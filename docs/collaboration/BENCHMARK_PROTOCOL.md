# Collaboration Benchmark Protocol

## Purpose

Define the measurement methodology for the provider selection decision and adaptive QoS
parameter tuning. All numbers in `AdaptiveSyncProfile.default()` are hypotheses until
device measurements replace them.

---

## Phase 1 — Synthetic / Server-Side (COMPLETE)

Run without real network hops. Validates infrastructure, not provider performance.

```
./gradlew :apps:android:testDebugUnitTest --tests "com.norfold.app.collaboration.*"
```

Covers: coalescing efficiency, adaptive mode transitions, interpolator throughput,
outbox enqueue/drain, sequence dedup, reconnect policy math.

---

## Phase 2 — Provider RTT (requires credentials)

```
export NORFOLD_SUPABASE_URL=https://<project>.supabase.co
export NORFOLD_SUPABASE_ANON_KEY=eyJ...
export NORFOLD_CF_DO_URL=https://<worker>.workers.dev
export NORFOLD_CF_DO_TOKEN=<bench-shared-secret>
export NORFOLD_ABLY_BENCH_TOKEN=<ably-token>

./gradlew :apps:android:testDebugUnitTest --tests "*.BenchmarkRunnerTest.provider*"
```

Measures per provider:
- p50 / p95 / p99 round-trip latency (ms)
- Throughput (envelopes/sec) at 20 Hz sustained
- Message loss rate
- Reconnect recovery time (avg over 3 trials)
- Coalescing efficiency (previews dropped / previews sent)

Fill results into `PROVIDER_DECISION.md` §Synthetic Benchmark Results.

---

## Phase 3 — Device-Side Validation (PENDING)

Must run on a real Android device or emulator with network throttling before the
provider winner is declared. Use the `android-device-qa` skill.

| Scenario | Tool | Target metric |
|---|---|---|
| 4G good signal | adb + throttle | p50 RTT |
| WiFi | adb | p50 RTT |
| 150ms added latency, 10% loss | tc netem | p99 RTT, loss rate |
| Airplane-mode toggle | manual | reconnect time |
| 20 Hz sustained, 1 hour | battery historian | mAh delta |
| APK size delta per SDK | ./gradlew assembleRelease | bytes |

---

## Adaptive QoS Hypothesis Rates

These are the starting hypotheses in `AdaptiveSyncProfile.default()`. Replace with
measured values once Phase 3 is complete.

| Mode | previewHz | textOpBatchWindowMs | Rationale |
|---|---|---|---|
| SOLO | 0 | 120 | No peers — no preview traffic |
| SHARED_ARTIFACT | 6 | 100 | Coarse awareness; 6 Hz ≈ 167ms latency budget |
| SHARED_REGION | 10 | 80 | Same page; 10 Hz ≈ 100ms |
| CONTENDED_TARGET | 16 | 50 | Same object; 16 Hz ≈ 62ms |
| DEGRADED | 3 | 150 | Weak device/network; correctness over richness |

Tuning rule: if measured p50 RTT > 2 × previewIntervalMs for a mode, halve previewHz
for that mode. If p99 RTT > 500ms under CONTENDED_TARGET, drop to SHARED_REGION rates.

---

## Merge Engine Benchmark (PENDING)

Once Automerge Java and Yjs are activated for benchmarking:

```
# Add to build.gradle.kts (benchmark builds only):
# implementation("org.automerge:automerge:0.0.4")
# implementation("org.yjs:yjs-kotlin:0.1.0")  // or equivalent JVM binding

./gradlew :apps:android:testDebugUnitTest --tests "*.MergeEngineBenchmarkTest"
```

Measure per engine:
- Apply throughput (ops/sec) for TextOperation.Insert at 20 Hz
- Memory footprint after 10k ops (heap delta)
- Snapshot size (bytes) after 10k ops
- Sync payload size (bytes) for incremental diff
- Selective undo support (yes/no)
- Binary sync support (yes/no)
- JVM ABI stability (reflection shim vs direct import)

Decision criteria: if no engine achieves < 2ms per op at 20 Hz on a mid-range device
(Pixel 6a equivalent), defer CRDT adoption and keep the NoOp engine until the editor
spec is finalized.

---

## Decision Gates

| Gate | Condition | Action |
|---|---|---|
| Provider winner | Phase 3 complete | Update PROVIDER_DECISION.md decision record |
| Adaptive QoS rates | Phase 3 complete | Replace hypotheses in AdaptiveSyncProfile.default() |
| Merge engine | Benchmark complete | Update AutomergeMergeEngine / YjsMergeEngine status |
| Production rooms | Auth wired | Remove throw in index.ts artifact_authorization_not_wired |
