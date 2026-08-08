package com.norfold.app.editor.recovery

import com.norfold.app.editor.history.EditTarget

/**
 * An immutable point-in-time capture of one or more documents, taken before a risky operation
 * (roadmap Phase 0 D3 / spec §13.7).
 *
 * Each captured document is stored as its **real serialized payload** — the exact string produced by
 * [com.norfold.app.domain.BlockDocumentJson.encodeDocumentPayload] — so a snapshot is proven against the
 * same codec that persistence uses, and restore reconstructs a genuine [com.norfold.app.domain.BlockDocument]
 * rather than a shallow copy. Payloads are opaque to the store; only the manager encodes/decodes them.
 *
 * A snapshot may span multiple [EditTarget]s (e.g. a cross-target migration) so the whole set is restored
 * as one unit. This is the recovery-protection guarantee: no later-phase migration runs until create and
 * restore are proven, and every risky op has a captured pre-image to roll back to.
 */
data class RecoverySnapshot(
    val id: String,
    val reason: RecoveryReason,
    val createdAt: Long,
    /** Target id -> serialized document payload at capture time. */
    val documents: Map<String, String>,
    /** Optional free-text describing the operation this snapshot guards (for a future recovery UI). */
    val label: String = "",
) {
    init {
        require(documents.isNotEmpty()) { "A recovery snapshot must capture at least one document" }
    }

    /** The targets this snapshot can restore. */
    val targets: List<EditTarget> get() = documents.keys.map { EditTarget(it) }
}

/**
 * Why a snapshot was taken. The store is neutral about policy; the reason exists so a recovery UI and
 * audit can explain each restore point, and so migrations (the highest-risk class) are always tagged.
 */
enum class RecoveryReason {
    /** A schema/content migration is about to mutate documents. Highest-risk class (§13.7). */
    MIGRATION,

    /** A destructive bulk edit (large delete, replace-all, structural rewrite). */
    DESTRUCTIVE_EDIT,

    /** An import that overwrites existing content. */
    IMPORT,

    /** A validated product Raw Source draft is about to replace an artifact. */
    RAW_SOURCE,

    /** A user- or app-requested manual checkpoint. */
    MANUAL_CHECKPOINT,
}
