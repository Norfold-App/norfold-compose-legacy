package com.norfold.app.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Durable, non-rendering Phase-8 source/fidelity metadata. Source bytes remain in attachment storage. */
@Serializable
@SerialName("interchange_data")
data class InterchangeDataBlock(
    override val id: String = BLOCK_ID,
    val payload: String,
) : DocumentBlock {
    override fun plainText(): String = ""

    companion object { const val BLOCK_ID = "document-interchange" }
}

fun BlockDocument.interchangeDataBlock(): InterchangeDataBlock? =
    blocks.filterIsInstance<InterchangeDataBlock>().firstOrNull()

fun BlockDocument.withInterchangeData(payload: String): BlockDocument {
    val index = blocks.indexOfFirst { it is InterchangeDataBlock }.let { if (it < 0) blocks.size else it }
    val updated = blocks.filterNot { it is InterchangeDataBlock }.toMutableList()
    updated.add(index.coerceAtMost(updated.size), InterchangeDataBlock(payload = payload))
    return copy(blocks = updated).normalized()
}

