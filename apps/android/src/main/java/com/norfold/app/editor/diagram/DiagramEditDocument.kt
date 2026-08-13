package com.norfold.app.editor.diagram

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DiagramNodePosition(
    val x: Float,
    val y: Float,
) {
    fun normalized(): DiagramNodePosition = DiagramNodePosition(
        x.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: .5f,
        y.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: .5f,
    )
}

/** Additive visual metadata for Mermaid facts that source cannot represent without changing meaning. */
@Serializable
data class DiagramEditDocument(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val nodePositions: Map<String, DiagramNodePosition> = emptyMap(),
) {
    companion object { const val CURRENT_SCHEMA_VERSION = 1 }

    fun normalized(validNodeIds: Set<String>): DiagramEditDocument = copy(
        schemaVersion = CURRENT_SCHEMA_VERSION,
        nodePositions = nodePositions.filterKeys(validNodeIds::contains).mapValues { it.value.normalized() },
    )
}

object DiagramEditDocumentJson {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun encode(document: DiagramEditDocument): String = json.encodeToString(DiagramEditDocument.serializer(), document)

    fun decodeOrDefault(payload: String?): DiagramEditDocument = payload
        ?.takeIf(String::isNotBlank)
        ?.let { runCatching { json.decodeFromString(DiagramEditDocument.serializer(), it) }.getOrNull() }
        ?.takeIf { it.schemaVersion <= DiagramEditDocument.CURRENT_SCHEMA_VERSION }
        ?: DiagramEditDocument()
}
