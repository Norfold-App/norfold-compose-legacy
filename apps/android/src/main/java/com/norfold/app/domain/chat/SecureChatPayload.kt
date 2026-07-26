package com.norfold.app.domain.chat

import kotlinx.serialization.Serializable

/** Versioned plaintext schema that exists only before seal/after open. */
@Serializable
data class SecureChatPayloadV1(
    val version: Int = 1,
    val type: String = "text",
    val body: String,
    val createdAt: Long,
    val replyPreview: String? = null,
    val targetMessageId: String? = null,
    val editedAt: Long? = null,
    val reactionsJson: String? = null,
    val pinned: Boolean? = null,
)
