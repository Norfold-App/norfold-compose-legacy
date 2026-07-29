package com.norfold.app.collaboration.merge

import com.norfold.app.collaboration.ops.OpaqueOperation
import com.norfold.app.collaboration.ops.TextOperation
import com.norfold.app.collaboration.ops.SceneOperation
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

/**
 * Merge engine benchmark and SPI conformance tests.
 *
 * Runs against all registered engines. Engines that require an absent dependency
 * (Automerge Java, Yjs JVM) are skipped gracefully — they do not fail the build.
 *
 * Run with:
 *   ./gradlew :apps:android:testDebugUnitTest --tests "*.MergeEngineBenchmarkTest"
 *
 * To activate Automerge or Yjs, add the dependency to build.gradle.kts (benchmark builds only)
 * and re-run. See BENCHMARK_PROTOCOL.md §Merge Engine Benchmark.
 */
class MergeEngineBenchmarkTest {

    private val engines: List<CollaborationMergeEngine> = listOf(
        NoOpMergeEngine(),
        AutomergeMergeEngine(),
        YjsMergeEngine(),
    )

    // ── SPI conformance (NoOp always runs) ──────────────────────────────────

    @Test fun `all engines create documents with EMPTY initial revision`() {
        forEachAvailable { engine ->
            val doc = engine.createDocument("art-1", "actor-1")
            assertEquals("${engine.engineId}: initial revision should be EMPTY",
                Revision.EMPTY, doc.currentRevision())
        }
    }

    @Test fun `all engines advance revision after local op`() {
        forEachAvailable { engine ->
            val doc = engine.createDocument("art-1", "actor-1")
            val op = localOp("op-1", "actor-1")
            doc.applyLocal(op)
            assertNotEquals("${engine.engineId}: revision should advance after local op",
                Revision.EMPTY, doc.currentRevision())
        }
    }

    @Test fun `all engines are idempotent for remote ops`() {
        forEachAvailable { engine ->
            val doc = engine.createDocument("art-1", "actor-1")
            val op = remoteOp("op-r1", "actor-2")
            doc.applyRemote(op)
            val rev1 = doc.currentRevision()
            doc.applyRemote(op) // duplicate
            assertEquals("${engine.engineId}: duplicate remote op must not change revision",
                rev1, doc.currentRevision())
        }
    }

    @Test fun `all engines snapshot round-trips`() {
        forEachAvailable { engine ->
            val doc = engine.createDocument("art-1", "actor-1")
            doc.applyLocal(localOp("op-1", "actor-1"))
            val snap = doc.snapshot()
            assertEquals("${engine.engineId}: snapshot engineId mismatch", engine.engineId, snap.engineId)
            val doc2 = engine.loadDocument("art-1", "actor-1", snap)
            assertEquals("${engine.engineId}: loaded doc revision must match original",
                doc.currentRevision(), doc2.currentRevision())
        }
    }

    @Test fun `all engines produce sync payload and integrate`() {
        forEachAvailable { engine ->
            val doc1 = engine.createDocument("art-1", "actor-1")
            val doc2 = engine.createDocument("art-1", "actor-2")
            doc1.applyLocal(localOp("op-1", "actor-1"))
            val payload = doc1.syncSince(Revision.EMPTY)
            assertEquals("${engine.engineId}: sync payload engineId mismatch", engine.engineId, payload.engineId)
            doc2.integrate(payload)
            // After integration doc2 should have advanced (or at least not regressed)
            assertNotEquals("${engine.engineId}: doc2 should advance after integrating doc1 payload",
                Revision.EMPTY, doc2.currentRevision())
        }
    }

    @Test fun `all engines handle TextOperation Insert without crashing`() {
        forEachAvailable { engine ->
            val doc = engine.createDocument("art-1", "actor-1")
            val op = LocalOperation(
                opId = "op-text-1", actorId = "actor-1", commandId = "cmd-1",
                op = TextOperation.Insert(targetId = "text-block-1", anchor = "start", text = "Hello"),
            )
            doc.applyLocal(op) // must not throw
        }
    }

    @Test fun `all engines handle SceneOperation SetProperty without crashing`() {
        forEachAvailable { engine ->
            val doc = engine.createDocument("art-1", "actor-1")
            val op = LocalOperation(
                opId = "op-scene-1", actorId = "actor-1", commandId = "cmd-1",
                op = SceneOperation.SetProperty(targetId = "obj-1", key = "fill", value = "#ff0000"),
            )
            doc.applyLocal(op) // must not throw
        }
    }

    // ── Throughput benchmark ─────────────────────────────────────────────────

    @Test fun `NoOp engine apply throughput baseline`() {
        val engine = NoOpMergeEngine()
        val doc = engine.createDocument("art-bench", "actor-1")
        val n = 10_000
        val t0 = System.currentTimeMillis()
        repeat(n) { i ->
            doc.applyLocal(localOp("op-$i", "actor-1"))
        }
        val elapsed = System.currentTimeMillis() - t0
        val opsPerSec = n * 1000.0 / elapsed.coerceAtLeast(1)
        println("NoOp engine: $n ops in ${elapsed}ms (${opsPerSec.toInt()} ops/sec)")
        assertTrue("NoOp should handle > 10k ops/sec", opsPerSec > 10_000)
    }

    @Test fun `NoOp engine snapshot size is minimal`() {
        val engine = NoOpMergeEngine()
        val doc = engine.createDocument("art-bench", "actor-1")
        repeat(1_000) { i -> doc.applyLocal(localOp("op-$i", "actor-1")) }
        val snap = doc.snapshot()
        println("NoOp snapshot size after 1000 ops: ${snap.bytes.size} bytes")
        assertTrue("NoOp snapshot should be < 64 bytes", snap.bytes.size < 64)
    }

    @Test fun `NoOp engine sync payload size is minimal`() {
        val engine = NoOpMergeEngine()
        val doc = engine.createDocument("art-bench", "actor-1")
        repeat(1_000) { i -> doc.applyLocal(localOp("op-$i", "actor-1")) }
        val payload = doc.syncSince(Revision.EMPTY)
        println("NoOp sync payload size after 1000 ops: ${payload.bytes.size} bytes")
        assertTrue("NoOp sync payload should be < 128 bytes", payload.bytes.size < 128)
    }

    @Test fun `Automerge engine benchmark (skipped if library absent)`() {
        val engine = AutomergeMergeEngine()
        if (!isAvailable(engine)) {
            println("Automerge Java: SKIPPED (library not on classpath)")
            return
        }
        runThroughputBenchmark(engine, n = 1_000)
    }

    @Test fun `Yjs engine benchmark (skipped if library absent)`() {
        val engine = YjsMergeEngine()
        if (!isAvailable(engine)) {
            println("Yjs JVM: SKIPPED (no JVM binding on classpath)")
            return
        }
        runThroughputBenchmark(engine, n = 1_000)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun runThroughputBenchmark(engine: CollaborationMergeEngine, n: Int) {
        val doc = engine.createDocument("art-bench", "actor-1")
        val t0 = System.currentTimeMillis()
        repeat(n) { i -> doc.applyLocal(localOp("op-$i", "actor-1")) }
        val elapsed = System.currentTimeMillis() - t0
        val opsPerSec = n * 1000.0 / elapsed.coerceAtLeast(1)
        val snap = doc.snapshot()
        val payload = doc.syncSince(Revision.EMPTY)
        println("${engine.engineId}: $n ops in ${elapsed}ms (${opsPerSec.toInt()} ops/sec)")
        println("  snapshot: ${snap.bytes.size} bytes  sync-payload: ${payload.bytes.size} bytes")
        println("  selectiveUndo=${engine.supportsSelectiveUndo}  binarySync=${engine.supportsBinarySync}")
        // No hard assertion on throughput for non-NoOp engines — record and compare.
    }

    private fun isAvailable(engine: CollaborationMergeEngine): Boolean = try {
        engine.createDocument("probe", "probe")
        true
    } catch (_: IllegalStateException) { false }
    catch (_: Exception) { false }

    private fun forEachAvailable(block: (CollaborationMergeEngine) -> Unit) {
        for (engine in engines) {
            if (!isAvailable(engine)) {
                println("${engine.engineId}: SKIPPED")
                continue
            }
            block(engine)
        }
    }

    private fun localOp(opId: String, actorId: String) = LocalOperation(
        opId = opId, actorId = actorId, commandId = "cmd-$opId",
        op = OpaqueOperation("test", "t1", "noop", buildJsonObject { put("i", opId) }),
    )

    private fun remoteOp(opId: String, actorId: String) = RemoteOperation(
        opId = opId, actorId = actorId, commandId = "cmd-$opId",
        op = OpaqueOperation("test", "t1", "noop", buildJsonObject { put("i", opId) }),
    )
}
