package com.norfold.app.collaboration.merge

import com.norfold.app.collaboration.ops.SemanticOperation
import com.norfold.app.collaboration.ops.TextOperation
import com.norfold.app.collaboration.ops.SceneOperation

/**
 * Yjs JVM pilot engine. Sits behind [CollaborationMergeEngine] — the production editor
 * never imports Yjs directly.
 *
 * BENCHMARK/PROTOTYPE status: do not migrate the production editor until ABI/memory/rich-text
 * performance is measured against the Automerge pilot. This class is intentionally isolated
 * in the merge package.
 *
 * Yjs does not have an official JVM/Android SDK. Two binding options exist:
 *   1. yjs-kt (Kotlin Multiplatform port) — experimental, limited API surface
 *   2. GraalVM JS runtime embedding Yjs — high overhead, not suitable for mobile
 *
 * This file compiles without any Yjs dependency by using a reflection-based shim that
 * fails fast at runtime if the library is absent. The benchmark harness activates it by
 * adding the dependency; the production build is never broken.
 *
 * Benchmark activation (build.gradle.kts, benchmark builds only):
 *   implementation("com.github.y-crdt:yjs-kt:<version>")  // when stable
 *
 * If no viable JVM Yjs binding reaches stability before the provider decision, this engine
 * remains SKIPPED and the Automerge pilot is the only CRDT candidate.
 */
class YjsMergeEngine : CollaborationMergeEngine {
    override val engineId = "yjs-jvm"
    override val supportsSelectiveUndo = false // Yjs undo manager exists but needs evaluation
    override val supportsBinarySync = true     // Yjs update protocol is binary-diff based

    override fun createDocument(artifactId: String, actorId: String): MergeDocument {
        checkYjsAvailable()
        return YjsDocument(artifactId, actorId, isNew = true)
    }

    override fun loadDocument(artifactId: String, actorId: String, snapshot: Snapshot): MergeDocument {
        checkYjsAvailable()
        return YjsDocument(artifactId, actorId, isNew = false, snapshotBytes = snapshot.bytes)
    }

    override fun selectiveUndoFor(document: MergeDocument): SelectiveUndo? = null

    private fun checkYjsAvailable() {
        try {
            Class.forName("com.github.ycrdt.YDoc")
        } catch (_: ClassNotFoundException) {
            try {
                Class.forName("org.yjs.YDoc")
            } catch (_: ClassNotFoundException) {
                error(
                    "No Yjs JVM binding found on classpath. " +
                    "Add a yjs-kt or equivalent JVM binding to build.gradle.kts " +
                    "for benchmark builds only. See BENCHMARK_PROTOCOL.md §Merge Engine Benchmark."
                )
            }
        }
    }
}

/**
 * Yjs document wrapper. Uses reflection so the file compiles without the dependency.
 * A direct-import version should be written once a stable JVM binding is confirmed viable.
 *
 * Yjs data model mapping (representative prototype):
 *   - TextOperation targets → Y.Text instances keyed by targetId
 *   - SceneOperation targets → Y.Map instances keyed by targetId
 *   - TableOperation targets → Y.Array of Y.Map rows keyed by targetId
 *   - Sync → Y.encodeStateAsUpdate / Y.applyUpdate (binary diff)
 *
 * The exact mapping is deferred until the editor spec is finalized; this prototype
 * validates the SPI boundary and benchmark harness wiring.
 */
private class YjsDocument(
    private val artifactId: String,
    private val actorId: String,
    isNew: Boolean,
    snapshotBytes: ByteArray? = null,
) : MergeDocument {
    override val engineId = "yjs-jvm"

    // Held as Any to avoid compile-time dependency on Yjs classes.
    private val doc: Any = if (isNew) createNew() else loadFrom(snapshotBytes!!)
    private val applied = LinkedHashSet<String>()
    private var clock = 0L

    override fun currentRevision(): Revision {
        // Yjs clock/state-vector: doc.store.getStateVector() → Map<clientId, clock>
        // Simplified: use our local op counter as a proxy until the binding is wired.
        return if (clock == 0L) Revision.EMPTY else Revision(listOf("yjs:$clock"))
    }

    override fun applyLocal(op: LocalOperation): Revision {
        applied.add(op.opId)
        applySemanticOp(op.op)
        clock++
        return currentRevision()
    }

    override fun applyRemote(op: RemoteOperation): Revision {
        if (!applied.add(op.opId)) return currentRevision() // idempotent
        applySemanticOp(op.op)
        clock++
        return currentRevision()
    }

    override fun snapshot(): Snapshot {
        // Y.encodeStateAsUpdate(doc) → ByteArray (full state)
        val bytes = tryInvokeStatic("encodeStateAsUpdate", doc) as? ByteArray ?: ByteArray(0)
        return Snapshot("yjs-jvm", bytes)
    }

    override fun syncSince(theirRevision: Revision): SyncPayload {
        // Y.encodeStateAsUpdate(doc, theirStateVector) → incremental update bytes
        // Without the binding, fall back to full state.
        val bytes = tryInvokeStatic("encodeStateAsUpdate", doc) as? ByteArray ?: ByteArray(0)
        return SyncPayload("yjs-jvm", bytes)
    }

    override fun integrate(payload: SyncPayload): Revision {
        // Y.applyUpdate(doc, updateBytes)
        tryInvokeStatic("applyUpdate", doc, payload.bytes)
        clock++
        return currentRevision()
    }

    private fun applySemanticOp(op: SemanticOperation) {
        // Map semantic operations to Yjs mutations. Representative prototype —
        // full mapping deferred until editor spec is finalized.
        when (op) {
            is TextOperation.Insert -> {
                // doc.getText(op.targetId).insert(index, op.text, op.marks)
                // Anchor-to-index resolution is editor-spec-dependent; deferred.
            }
            is TextOperation.Delete -> { /* deferred */ }
            is TextOperation.Mark -> {
                // doc.getText(op.targetId).format(index, length, {mark: value})
            }
            is SceneOperation.SetProperty -> {
                // doc.getMap(op.targetId).set(op.key, op.value)
            }
            is SceneOperation.Create, is SceneOperation.Delete,
            is SceneOperation.Transform -> { /* deferred */ }
            else -> { /* OpaqueOperation and other domains: deferred */ }
        }
    }

    private fun tryInvokeStatic(method: String, vararg args: Any?): Any? = try {
        val ydocClass = doc.javaClass
        val m = ydocClass.enclosingClass?.methods?.find { it.name == method }
            ?: ydocClass.methods.find { it.name == method }
        m?.invoke(null, *args)
    } catch (_: Exception) { null }

    companion object {
        private fun createNew(): Any {
            // Try known binding class names in order of preference.
            val candidates = listOf("com.github.ycrdt.YDoc", "org.yjs.YDoc", "com.yjs.Doc")
            for (name in candidates) {
                try {
                    val cls = Class.forName(name)
                    return cls.getDeclaredConstructor().newInstance()
                } catch (_: ClassNotFoundException) {}
            }
            error("No Yjs JVM binding found — see YjsMergeEngine.checkYjsAvailable()")
        }

        private fun loadFrom(bytes: ByteArray): Any {
            val doc = createNew()
            // Y.applyUpdate(doc, bytes) — static method on the companion/module object
            val ydocClass = doc.javaClass
            try {
                val applyUpdate = ydocClass.enclosingClass?.getMethod("applyUpdate", ydocClass, ByteArray::class.java)
                applyUpdate?.invoke(null, doc, bytes)
            } catch (_: Exception) {}
            return doc
        }
    }
}
