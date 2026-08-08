package com.norfold.app.editor.interchange

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.InterchangeDataBlock
import com.norfold.app.domain.interchangeDataBlock
import com.norfold.app.domain.walk
import com.norfold.app.domain.withInterchangeData
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class InterchangeArtifactMetadata(
    val schemaVersion: Int = 1,
    val source: SourceArtifactMetadata,
    val compatibility: CompatibilityMetadata,
    val fidelity: FidelityReport,
) {
    init { require(schemaVersion == 1) }
}

object InterchangeMetadataCodec {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; explicitNulls = false }
    fun encode(metadata: InterchangeArtifactMetadata): String = json.encodeToString(metadata)
    fun decode(payload: String): InterchangeArtifactMetadata = json.decodeFromString(payload)
}

/** Keeps imported-region fidelity honest as ordinary Flow/Document edits pass through canonical history. */
fun BlockDocument.updateInterchangeFidelity(before: BlockDocument): BlockDocument {
    if (before.interchangeDataBlock() == null) return this
    val data = interchangeDataBlock() ?: return this
    val changedTargets = changedTargetIds(before, this)
    if (changedTargets.isEmpty()) return this
    val metadata = runCatching { InterchangeMetadataCodec.decode(data.payload) }.getOrNull() ?: return this
    val updated = changedTargets.fold(metadata.fidelity) { report, target -> report.afterNativeEdit(target) }
    return if (updated == metadata.fidelity) this else withInterchangeData(
        InterchangeMetadataCodec.encode(metadata.copy(fidelity = updated)),
    )
}

private fun changedTargetIds(before: BlockDocument, after: BlockDocument): Set<String> {
    val old = linkedMapOf<String, Any>()
    val new = linkedMapOf<String, Any>()
    before.walk { block -> if (block !is InterchangeDataBlock) old[block.id] = block }
    after.walk { block -> if (block !is InterchangeDataBlock) new[block.id] = block }
    before.blocks.filterIsInstance<DocumentPageBlock>().flatMap { it.objects }.forEach { old[it.id] = it }
    after.blocks.filterIsInstance<DocumentPageBlock>().flatMap { it.objects }.forEach { new[it.id] = it }
    return (old.keys + new.keys).filterTo(linkedSetOf()) { old[it] != new[it] }
}
