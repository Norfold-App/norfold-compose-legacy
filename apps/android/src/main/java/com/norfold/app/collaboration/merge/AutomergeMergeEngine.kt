package com.norfold.app.collaboration.merge

import com.norfold.app.collaboration.ops.SemanticOperation
import com.norfold.app.collaboration.ops.TextOperation
import com.norfold.app.collaboration.ops.SceneOperation
import java.util.UUID

/**
 * Automerge Java pilot engine. Sits behind [CollaborationMergeEngine] — the production editor
 * never imports Automerge directly.
 *
 * BENCHMARK/PROTOTYPE status: do not migrate the production editor until ABI/memory/rich-text
 * performance is measured. This class is intentionally isolated in the merge package.
 *
 * Automerge Java dependency is NOT yet added to build.gradle.kts — it is added only when this
 * pilot is activated for benchmarking, to avoid bloating the production APK. See the benchmark
 * harness for activation instructions.
 *
 * This file compiles without the Automerge dependency by using a reflection-based shim that
 * fails fast at runtime if the library is absent, so the production build is never broken.
 */
class AutomergeMergeEngine : CollaborationMergeEngine {
    override val engineId = "automerge-java"
    override val supportsSelectiveUndo = false // measure first
    override val supportsBinarySync = true

    override fun createDocument(artifactId: String, actorId: String): MergeDocument {
        checkAutomergeAvailable()
        return AutomergeDocument(artifactId, actorId, isNew = true)
    }

    override fun loadDocument(artifactId: String, actorId: String, snapshot: Snapshot): MergeDocument {
        checkAutomergeAvailable()
        return AutomergeDocument(artifactId, actorId, isNew = false, snapshotBytes = snapshot.bytes)
    }

    override fun selectiveUndoFor(document: MergeDocument): SelectiveUndo? = null

    private fun checkAutomergeAvailable() {
        try {
            Class.forName("org.automerge.Document")
        } catch (e: ClassNotFoundException) {
            error(
                "Automerge Java library not on classpath. Add " +
                "'implementation(\"org.automerge:automerge:0.0.4\")' to build.gradle.kts " +
                "for benchmark builds only. Do not add to production builds until " +
                "ABI/memory/rich-text benchmarks justify it."
            )
        }
    }
}

/**
 * Automerge document wrapper. Uses reflection so the file compiles without the dependency.
 * A direct-import version should be written once the benchmark confirms the library is viable.
 */
private class AutomergeDocument(
    private val artifactId: String,
    private val actorId: String,
    isNew: Boolean,
    snapshotBytes: ByteArray? = null,
) : MergeDocument {
    override val engineId = "automerge-java"

    // Held as Any to avoid compile-time dependency on Automerge classes.
    private val doc: Any = if (isNew) createNew() else loadFrom(snapshotBytes!!)
    private val applied = LinkedHashSet<String>()

    override fun currentRevision(): Revision {
        val heads = invokeDoc("getHeads") as? Array<*> ?: return Revision.EMPTY
        return Revision(heads.map { it.toString() })
    }

    override fun applyLocal(op: LocalOperation): Revision {
        applied.add(op.opId)
        applySemanticOp(op.op)
        return currentRevision()
    }

    override fun applyRemote(op: RemoteOperation): Revision {
        if (!applied.add(op.opId)) return currentRevision() // idempotent
        applySemanticOp(op.op)
        return currentRevision()
    }

    override fun snapshot(): Snapshot {
        val bytes = invokeDoc("save") as? ByteArray ?: ByteArray(0)
        return Snapshot("automerge-java", bytes)
    }

    override fun syncSince(theirRevision: Revision): SyncPayload {
        // Automerge sync protocol: generate sync message given peer's sync state.
        // Full implementation requires SyncState per peer; this returns a full save as fallback.
        val bytes = invokeDoc("save") as? ByteArray ?: ByteArray(0)
        return SyncPayload("automerge-java", bytes)
    }

    override fun integrate(payload: SyncPayload): Revision {
        // Load and merge the peer's document bytes.
        val peerDoc = loadFrom(payload.bytes)
        val mergeMethod = peerDoc.javaClass.getMethod("merge", peerDoc.javaClass)
        mergeMethod.invoke(doc, peerDoc)
        return currentRevision()
    }

    private fun applySemanticOp(op: SemanticOperation) {
        // Map semantic operations to Automerge mutations. This is a representative prototype —
        // the full mapping is defined once the editor spec is finalized.
        when (op) {
            is TextOperation.Insert -> {
                // Automerge text: doc.text(op.targetId).splice(anchor, 0, op.text)
                // Anchor-to-index resolution is editor-spec-dependent; deferred.
            }
            is TextOperation.Delete -> { /* deferred */ }
            is TextOperation.Mark -> { /* deferred */ }
            is SceneOperation.SetProperty -> {
                // doc.map(op.targetId).set(op.key, op.value)
            }
            is SceneOperation.Create, is SceneOperation.Delete,
            is SceneOperation.Transform -> { /* deferred */ }
            else -> { /* OpaqueOperation and other domains: deferred */ }
        }
        // Commit the transaction so heads advance.
        try { invokeDoc("commit") } catch (_: Exception) {}
    }

    private fun invokeDoc(method: String, vararg args: Any?): Any? =
        doc.javaClass.getMethod(method, *args.map { it?.javaClass ?: Any::class.java }.toTypedArray())
            .invoke(doc, *args)

    companion object {
        private fun createNew(): Any {
            val cls = Class.forName("org.automerge.Document")
            return cls.getDeclaredConstructor().newInstance()
        }

        private fun loadFrom(bytes: ByteArray): Any {
            val cls = Class.forName("org.automerge.Document")
            val method = cls.getMethod("load", ByteArray::class.java)
            return method.invoke(null, bytes)
        }
    }
}
