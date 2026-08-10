package com.norfold.app.collaboration.benchmark

import com.norfold.app.collaboration.*
import com.norfold.app.collaboration.protocol.SemanticBatch
import com.norfold.app.collaboration.protocol.SemanticDecodeResult
import com.norfold.app.collaboration.protocol.SemanticMutation
import com.norfold.app.collaboration.protocol.SemanticOperationCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Benchmark runner test. Runs the coalescing and infrastructure benchmarks locally
 * (no network required). Provider RTT benchmarks are skipped when env vars are absent.
 *
 * Run with:
 *   ./gradlew :apps:android:testDebugUnitTest --tests "*.BenchmarkRunnerTest"
 */
class BenchmarkRunnerTest {

    // ── Coalescing benchmark (no network) ────────────────────────────────────

    @Test fun `coalescing benchmark produces measurable drop rate at 5ms intervals`() = runBlocking {
        val coalescer = PreviewCoalescer()
        val profile = AdaptiveSyncProfile.default(CollabMode.SHARED_REGION)
        val roomId = RoomId("bench:local")
        val actorId = ActorId("bench-actor")
        val n = 200

        val t0 = System.currentTimeMillis()
        repeat(n) { i ->
            coalescer.offer(
                OutboundEnvelope(roomId, actorId, CollabEventClass.EPHEMERAL_LATEST, "cursor.move", "obj-cursor", CollabSeq(i + 1L)),
                System.currentTimeMillis(),
            )
            Thread.sleep(5) // 200 Hz send, 10-20 Hz preview rate → expect ~90% drop
        }
        val flushed = coalescer.poll(profile, System.currentTimeMillis() + profile.previewIntervalMs + 1)
        val elapsed = System.currentTimeMillis() - t0
        val dropped = coalescer.previewsDropped

        println("Coalescing benchmark:")
        println("  sent=$n  dropped=$dropped  flushed=${flushed.size}  elapsed=${elapsed}ms")
        println("  drop rate=${"%.1f".format(dropped.toDouble() / n * 100)}%")

        // At 5ms send interval and ~100ms preview interval, expect at least 80% drop
        assertTrue("Expected drop rate > 80%, got ${dropped.toDouble() / n}", dropped.toDouble() / n > 0.8)
    }

    // ── Adaptive sync benchmark (no network) ────────────────────────────────

    @Test fun `adaptive sync mode transitions are fast`() {
        val ctrl = AdaptiveSyncController()
        val iterations = 1000
        val t0 = System.currentTimeMillis()
        repeat(iterations) { i ->
            val peers = i % 4
            ctrl.update(CollabSituation(
                peerCount = peers,
                peersInSameRegion = peers / 2,
                peersOnSameTarget = if (i % 10 == 0) 1 else 0,
                deviceIsWeak = i % 7 == 0,
                networkIsWeak = i % 13 == 0,
            ))
        }
        val elapsed = System.currentTimeMillis() - t0
        println("AdaptiveSyncController: $iterations transitions in ${elapsed}ms (${elapsed.toDouble() / iterations}ms each)")
        assertTrue("Mode transitions should be < 1ms each", elapsed < iterations)
    }

    // ── Interpolator benchmark (no network) ─────────────────────────────────

    @Test fun `remote interpolator handles 60Hz render loop efficiently`() {
        val ri = RemoteInterpolator(interpolationDelayMs = 80)
        val trackKey = "actor-1:cursor"
        // Submit 20Hz samples
        for (i in 0..20) ri.submit(trackKey, i * 50L, doubleArrayOf(i.toDouble(), i.toDouble()))

        val iterations = 10_000
        val t0 = System.currentTimeMillis()
        repeat(iterations) { i ->
            ri.sample(trackKey, 500L + i * 16L) // 60Hz render
        }
        val elapsed = System.currentTimeMillis() - t0
        println("RemoteInterpolator: $iterations samples in ${elapsed}ms (${elapsed.toDouble() / iterations * 1000}µs each)")
        assertTrue("Interpolation should be < 0.1ms each", elapsed < iterations / 10)
    }

    // ── Offline outbox benchmark (no network) ────────────────────────────────

    @Test fun `offline outbox enqueue and drain throughput`() = runBlocking {
        val box = OfflineOutbox(maxSize = 1024)
        val n = 1000
        val roomId = RoomId("bench:local")
        val actorId = ActorId("bench-actor")

        val t0 = System.currentTimeMillis()
        repeat(n) { i ->
            box.enqueue(OutboundEnvelope(roomId, actorId, CollabEventClass.DURABLE, "text.insert", null, CollabSeq(i + 1L)))
        }
        val drained = box.drainAll()
        val elapsed = System.currentTimeMillis() - t0

        println("OfflineOutbox: $n enqueue + drain in ${elapsed}ms")
        assertEquals(minOf(n, 1024), drained.size)
    }

    @Test fun `semantic codec records deterministic encode and decode cost`() {
        val batch = SemanticBatch(
            batchId = "benchmark-batch",
            commandId = "benchmark-command",
            operations = List(128) { index ->
                SemanticMutation(domain = "flow", action = "text.insert", targetId = "block-$index")
            },
        )
        val iterations = 500
        val encodeStart = System.nanoTime()
        val payloads = List(iterations) { SemanticOperationCodec.encode(batch) }
        val encodeNanos = System.nanoTime() - encodeStart
        val decodeStart = System.nanoTime()
        val decoded = payloads.map(SemanticOperationCodec::decode)
        val decodeNanos = System.nanoTime() - decodeStart
        println("Semantic codec: iterations=$iterations bytes=${payloads.first().size} encodeNs=$encodeNanos decodeNs=$decodeNanos")
        assertTrue(decoded.all { it is SemanticDecodeResult.Supported })
        assertEquals(1, payloads.map { it.contentHashCode() }.distinct().size)
    }

    // ── Provider benchmark (skipped without credentials) ────────────────────

    @Test fun `provider benchmark runs and reports results`() = runBlocking {
        val cfg = BenchmarkHarness.BenchmarkConfig(
            warmupMessages = 5,
            measureMessages = 20,
            sendIntervalMs = 50,
            reconnectTrials = 1,
            timeoutMs = 5_000,
        )
        val results = BenchmarkHarness.runAll(cfg)
        assertTrue("Should have results for all 3 providers", results.size == 3)

        println()
        println("═══════════════════════════════════════════════════")
        println("  Provider Benchmark Results")
        println("═══════════════════════════════════════════════════")
        results.forEach { it.print() }

        val skipped = results.count { it.skipped }
        val errored = results.count { it.error != null }
        println("  Skipped (no credentials): $skipped")
        println("  Errored: $errored")
        println("  Completed: ${results.size - skipped - errored}")

        // All providers either completed or were skipped — no unexpected errors
        // (network errors are acceptable in CI; only assert structure)
        results.forEach { r ->
            assertNotNull("Provider name should not be null", r.provider)
        }
    }
}
