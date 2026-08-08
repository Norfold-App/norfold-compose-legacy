package com.norfold.app.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Durable, non-rendering storage for the editor relationship/discussion domain. */
@Serializable
@SerialName("relationship_data")
data class RelationshipDataBlock(
    override val id: String = BLOCK_ID,
    val payload: String,
) : DocumentBlock {
    override fun plainText(): String = ""

    companion object { const val BLOCK_ID = "document-relationships" }
}

fun BlockDocument.relationshipDataBlock(): RelationshipDataBlock? =
    blocks.filterIsInstance<RelationshipDataBlock>().firstOrNull()

fun BlockDocument.withRelationshipData(payload: String): BlockDocument {
    val index = blocks.indexOfFirst { it is RelationshipDataBlock }.let { if (it < 0) blocks.size else it }
    val withoutDuplicates = blocks.filterNot { it is RelationshipDataBlock }.toMutableList()
    withoutDuplicates.add(index.coerceAtMost(withoutDuplicates.size), RelationshipDataBlock(payload = payload))
    return copy(blocks = withoutDuplicates).normalized()
}
