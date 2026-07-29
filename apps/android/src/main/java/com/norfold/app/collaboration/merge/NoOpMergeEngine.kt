package com.norfold.app.collaboration.merge

import com.norfold.app.collaboration.ops.SemanticOperation
import com.norfold.app.collaboration.ops.OpaqueOperation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * No-op merge engine for infrastructure testing. Accepts all ops, tracks revision as a
 * monotonic counter, and produces a snapshot that round-trips. Used to validate the SPI
 * and benchmark harness before Automerge Java is wired.
 */
class NoOpMergeEngine : CollaborationMergeEngine {
    override val engineId = "noop"
    override val supportsSelectiveUndo = false
    override val supportsBinarySync = false

    override fun createDocument(artifactId: String, actorId: String): MergeDocument =
        NoOpDocument(artifactId, actorId)

    override fun loadDocument(artifactId: String, actorId: String, snapshot: Snapshot): MergeDocument {
        val doc = NoOpDocument(artifactId, actorId)
        val counter = snapshot.bytes.toString(Charsets.UTF_8).toLongOrNull() ?: 0L
        doc.counter = counter
        return doc
    }

    override fun selectiveUndoFor(document: MergeDocument): SelectiveUndo? = null
}

class NoOpDocument(
    private val artifactId: String,
    private val actorId: String,
) : MergeDocument {
    override val engineId = "noop"
    var counter = 0L
    private val applied = LinkedHashSet<String>()

    override fun currentRevision() = if (counter == 0L) Revision.EMPTY else Revision(listOf(counter.toString()))

    override fun applyLocal(op: LocalOperation): Revision {
        applied.add(op.opId)
        counter++
        return currentRevision()
    }

    override fun applyRemote(op: RemoteOperation): Revision {
        if (applied.add(op.opId)) counter++
        return currentRevision()
    }

    override fun snapshot() = Snapshot("noop", counter.toString().toByteArray(Charsets.UTF_8))

    override fun syncSince(theirRevision: Revision): SyncPayload {
        val theirCounter = theirRevision.heads.firstOrNull()?.toLongOrNull() ?: 0L
        return SyncPayload("noop", "delta:${theirCounter}:${counter}".toByteArray(Charsets.UTF_8))
    }

    override fun integrate(payload: SyncPayload): Revision {
        val text = payload.bytes.toString(Charsets.UTF_8)
        val peerCounter = text.removePrefix("delta:").substringAfterLast(":").toLongOrNull() ?: 0L
        if (peerCounter > counter) counter = peerCounter
        return currentRevision()
    }
}
