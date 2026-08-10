package com.norfold.app.collaboration.benchmark

import com.norfold.app.collaboration.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.timeout
import kotlin.math.*
import kotlin.time.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Standalone benchmark harness for the three collaboration transport adapters.
 *
 * Measures:
 *   - Round-trip latency (p50, p95, p99) for EPHEMERAL and DURABLE envelopes
 *   - Throughput (envelopes/sec) at sustained send rate
 *   - Coalescing efficiency (previews dropped / previews sent)
 *   - Reconnect recovery time
 *
 * Run with:
 *   ./gradlew :apps:android:testDebugUnitTest --tests "*.BenchmarkHarness*"
 * or as a standalone JVM main (see BenchmarkMain.kt).
 *
 * Provider credentials are read from environment variables:
 *   NORFOLD_SUPABASE_URL, NORFOLD_SUPABASE_ANON_KEY  — Supabase Broadcast
 *   NORFOLD_CF_DO_URL                                 — Cloudflare DO WebSocket endpoint
 *   NORFOLD_ABLY_BENCH_TOKEN                          — Ably token (bench rooms only)
 *
 * If a variable is absent the corresponding provider is skipped and marked SKIPPED in results.
 */
object BenchmarkHarness {

    data class BenchmarkConfig(
        val roomPrefix: String = "bench:norfold",
        val warmupMessages: Int = 20,
        val measureMessages: Int = 200,
        val sendIntervalMs: Long = 50,          // 20 Hz baseline
        val durableRatio: Double = 0.1,         // 10% of messages are DURABLE
        val reconnectTrials: Int = 3,
        val timeoutMs: Long = 8_000,
    )

    data class LatencyStats(
        val p50: Double,
        val p95: Double,
        val p99: Double,
        val mean: Double,
        val min: Double,
        val max: Double,
        val n: Int,
    ) {
        override fun toString() =
            "n=$n  min=${min.fmt}  mean=${mean.fmt}  p50=${p50.fmt}  p95=${p95.fmt}  p99=${p99.fmt}  max=${max.fmt} ms"
    }

    data class ThroughputStats(
        val sentPerSec: Double,
        val receivedPerSec: Double,
        val lossRate: Double,
    ) {
        override fun toString() =
            "sent=${sentPerSec.fmt}/s  recv=${receivedPerSec.fmt}/s  loss=${(lossRate * 100).fmt}%"
    }

    data class CoalescingStats(
        val sent: Long,
        val dropped: Long,
        val efficiency: Double,
    ) {
        override fun toString() =
            "sent=$sent  dropped=$dropped  efficiency=${(efficiency * 100).fmt}%"
    }

    data class ProviderResult(
        val provider: String,
        val latency: LatencyStats?,
        val throughput: ThroughputStats?,
        val coalescing: CoalescingStats?,
        val reconnectMs: Double?,
        val skipped: Boolean = false,
        val error: String? = null,
    ) {
        fun print() {
            println("┌─ $provider ${if (skipped) "[SKIPPED]" else if (error != null) "[ERROR: $error]" else "[OK]"}")
            latency?.let    { println("│  latency    : $it") }
            throughput?.let { println("│  throughput : $it") }
            coalescing?.let { println("│  coalescing : $it") }
            reconnectMs?.let { println("│  reconnect  : ${it.fmt} ms avg") }
            println("└─")
        }
    }

    /** Run all providers and return results. Skips providers with missing credentials. */
    suspend fun runAll(cfg: BenchmarkConfig = BenchmarkConfig()): List<ProviderResult> {
        val results = mutableListOf<ProviderResult>()
        results += runProvider("Supabase Broadcast", cfg) { buildSupabaseTransport() }
        results += runProvider("Cloudflare DO", cfg) { buildCloudflareTransport() }
        results += runProvider("Ably Pub/Sub", cfg) { buildAblyTransport() }
        return results
    }

    private suspend fun runProvider(
        name: String,
        cfg: BenchmarkConfig,
        factory: () -> RealtimeCollaborationTransport?,
    ): ProviderResult {
        val transport = try {
            factory() ?: return ProviderResult(name, null, null, null, null, skipped = true)
        } catch (e: Exception) {
            return ProviderResult(name, null, null, null, null, error = e.message)
        }

        return try {
            val roomId = RoomId("${cfg.roomPrefix}:${name.lowercase().replace(" ", "-")}")
            val actorId = ActorId("bench-actor-${System.currentTimeMillis()}")
            val tokenProvider = BenchmarkTokenProvider

            withTimeout(cfg.timeoutMs * (cfg.measureMessages + cfg.warmupMessages) / 10) {
                transport.connect(roomId, actorId, tokenProvider)
                waitConnected(transport, cfg.timeoutMs)

                // Warmup
                repeat(cfg.warmupMessages) { i ->
                    transport.send(makePing(roomId, actorId, i.toLong() + 1, CollabEventClass.EPHEMERAL_LATEST))
                    delay(cfg.sendIntervalMs)
                }

                val latency = measureLatency(transport, roomId, actorId, cfg)
                val throughput = measureThroughput(transport, roomId, actorId, cfg)
                val coalescing = measureCoalescing(roomId, actorId, cfg)
                val reconnect = measureReconnect(transport, roomId, actorId, tokenProvider, cfg)

                transport.close()
                ProviderResult(name, latency, throughput, coalescing, reconnect)
            }
        } catch (e: Exception) {
            runCatching { transport.close() }
            ProviderResult(name, null, null, null, null, error = e.message)
        }
    }

    private suspend fun measureLatency(
        transport: RealtimeCollaborationTransport,
        roomId: RoomId,
        actorId: ActorId,
        cfg: BenchmarkConfig,
    ): LatencyStats {
        val rtts = mutableListOf<Double>()
        repeat(cfg.measureMessages) { i ->
            val sequence = 10_000L + i + 1
            val seq = CollabSeq(sequence)
            val sentAt = System.currentTimeMillis()
            transport.send(makePing(roomId, actorId, sequence, CollabEventClass.EPHEMERAL_LATEST))
            // Wait for echo (benchmark rooms echo back)
            val echo = withTimeoutOrNull(cfg.timeoutMs) {
                transport.inbound.first { it.seq == seq }
            }
            if (echo != null) rtts += (System.currentTimeMillis() - sentAt).toDouble()
            delay(cfg.sendIntervalMs)
        }
        return computeStats(rtts)
    }

    private suspend fun measureThroughput(
        transport: RealtimeCollaborationTransport,
        roomId: RoomId,
        actorId: ActorId,
        cfg: BenchmarkConfig,
    ): ThroughputStats {
        var sent = 0
        var received = 0
        val startMs = System.currentTimeMillis()
        val collectJob = CoroutineScope(Dispatchers.IO).launch {
            transport.inbound.collect { received++ }
        }
        repeat(cfg.measureMessages) { i ->
            transport.send(makePing(roomId, actorId, 20_000L + i + 1, CollabEventClass.EPHEMERAL_LATEST))
            sent++
            delay(cfg.sendIntervalMs)
        }
        delay(500) // drain window
        collectJob.cancel()
        val elapsedSec = (System.currentTimeMillis() - startMs) / 1000.0
        return ThroughputStats(
            sentPerSec = sent / elapsedSec,
            receivedPerSec = received / elapsedSec,
            lossRate = if (sent > 0) 1.0 - (received.toDouble() / sent) else 0.0,
        )
    }

    private suspend fun measureCoalescing(
        roomId: RoomId,
        actorId: ActorId,
        cfg: BenchmarkConfig,
    ): CoalescingStats {
        val coalescer = PreviewCoalescer()
        val profile = AdaptiveSyncProfile.default(CollabMode.SHARED_REGION)
        var sent = 0L
        repeat(cfg.measureMessages) { i ->
            coalescer.offer(
                OutboundEnvelope(roomId, actorId, CollabEventClass.EPHEMERAL_LATEST, "cursor.move", "obj-$i", CollabSeq(i.toLong() + 1)),
                System.currentTimeMillis(),
            )
            sent++
            delay(5)
        }
        coalescer.poll(profile, System.currentTimeMillis() + profile.previewIntervalMs + 1)
        val dropped = coalescer.previewsDropped
        return CoalescingStats(
            sent = sent,
            dropped = dropped,
            efficiency = if (sent > 0) dropped.toDouble() / sent else 0.0,
        )
    }

    private suspend fun measureReconnect(
        transport: RealtimeCollaborationTransport,
        roomId: RoomId,
        actorId: ActorId,
        tokenProvider: CollabTokenProvider,
        cfg: BenchmarkConfig,
    ): Double {
        // Reconnect measurement requires the transport to be closed and re-connected.
        // We measure the time from close() to CONNECTED on a fresh connect() call.
        val times = mutableListOf<Long>()
        repeat(cfg.reconnectTrials) {
            transport.close()
            val t0 = System.currentTimeMillis()
            transport.connect(roomId, actorId, tokenProvider)
            withTimeoutOrNull(cfg.timeoutMs) {
                transport.state.first { it == TransportState.CONNECTED }
            }
            times += System.currentTimeMillis() - t0
            delay(500)
        }
        return if (times.isEmpty()) Double.NaN else times.average()
    }

    // ── Transport factories ──────────────────────────────────────────────────

    private fun buildSupabaseTransport(): RealtimeCollaborationTransport? {
        val url = System.getenv("NORFOLD_SUPABASE_URL") ?: return null
        val key = System.getenv("NORFOLD_SUPABASE_ANON_KEY") ?: return null
        val client = io.github.jan.supabase.createSupabaseClient(
            supabaseUrl = url,
            supabaseKey = key,
        ) {
            install(io.github.jan.supabase.realtime.Realtime)
        }
        return SupabaseBroadcastTransport(supabase = client)
    }

    private fun buildCloudflareTransport(): RealtimeCollaborationTransport? {
        val url = System.getenv("NORFOLD_CF_DO_URL") ?: return null
        return CloudflareDurableObjectTransport(workerBaseUrl = url, deviceId = DeviceId("benchmark-device"))
    }

    private fun buildAblyTransport(): RealtimeCollaborationTransport? {
        System.getenv("NORFOLD_ABLY_BENCH_TOKEN") ?: return null
        return AblyPubSubTransport()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun makePing(roomId: RoomId, actorId: ActorId, seq: Long, cls: CollabEventClass) =
        OutboundEnvelope(roomId, actorId, cls, "bench.ping", null, CollabSeq(seq), System.currentTimeMillis())

    private suspend fun waitConnected(transport: RealtimeCollaborationTransport, timeoutMs: Long) {
        withTimeout(timeoutMs) { transport.state.first { it == TransportState.CONNECTED } }
    }

    private fun computeStats(samples: List<Double>): LatencyStats {
        if (samples.isEmpty()) return LatencyStats(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0)
        val sorted = samples.sorted()
        fun pct(p: Double) = sorted[(sorted.size * p / 100).toInt().coerceIn(0, sorted.size - 1)]
        return LatencyStats(
            p50 = pct(50.0), p95 = pct(95.0), p99 = pct(99.0),
            mean = sorted.average(), min = sorted.first(), max = sorted.last(),
            n = sorted.size,
        )
    }

    private val Double.fmt get() = "%.1f".format(this)
}

/** Benchmark token provider — reads from env, never ships in release builds. */
object BenchmarkTokenProvider : CollabTokenProvider {
    override suspend fun freshToken(roomId: RoomId): String =
        System.getenv("NORFOLD_ABLY_BENCH_TOKEN")
            ?: System.getenv("NORFOLD_CF_DO_TOKEN")
            ?: "bench-insecure-token"
}
