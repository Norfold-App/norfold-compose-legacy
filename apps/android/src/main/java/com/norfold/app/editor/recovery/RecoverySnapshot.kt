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
    /**
     * User-chosen name, for manual restore points only (§13.7). Null means "unnamed", which is what every
     * automatic snapshot is: the app names the *reason*, the user names the *moment*.
     */
    val name: String? = null,
    /** User-written note describing why this restore point exists. Manual snapshots only. */
    val description: String = "",
    /**
     * Top-level block ids this snapshot is scoped to, or empty for a whole-artifact snapshot.
     *
     * A scoped snapshot still captures the complete document payload — the scope narrows what a *restore*
     * writes back, not what was recorded. Capturing everything keeps integrity verification simple and
     * means a scoped restore point can always be widened later, never the reverse.
     */
    val scopeBlockIds: List<String> = emptyList(),
) {
    init {
        require(documents.isNotEmpty()) { "A recovery snapshot must capture at least one document" }
    }

    /** The targets this snapshot can restore. */
    val targets: List<EditTarget> get() = documents.keys.map { EditTarget(it) }

    /** True iff the user asked for this restore point rather than the app taking it defensively. */
    val isManual: Boolean get() = reason.isManual

    /** True iff restoring this snapshot rewrites only [scopeBlockIds] rather than whole documents. */
    val isScoped: Boolean get() = scopeBlockIds.isNotEmpty()

    /** What a browser row should call this restore point. Never document content. */
    fun displayName(): String = name?.takeIf(String::isNotBlank)
        ?: label.takeIf(String::isNotBlank)
        ?: reason.defaultLabel

    /** Approximate stored size, used for the retention budget and for showing storage cost honestly. */
    val payloadBytes: Long get() = documents.values.sumOf { it.length.toLong() }
}

/**
 * Why a snapshot was taken. The store is neutral about policy; the reason exists so a recovery UI and
 * audit can explain each restore point, and so migrations (the highest-risk class) are always tagged.
 *
 * [isManual] drives retention: automatic snapshots are plentiful and cheap to lose, a named restore point
 * the user deliberately created is not. Eviction must never trade the second for the first.
 */
enum class RecoveryReason(val isManual: Boolean, val defaultLabel: String) {
    /** A schema/content migration is about to mutate documents. Highest-risk class (§13.7). */
    MIGRATION(isManual = false, defaultLabel = "Before migration"),

    /** A destructive bulk edit (large delete, replace-all, structural rewrite). */
    DESTRUCTIVE_EDIT(isManual = false, defaultLabel = "Before a destructive edit"),

    /** An import that overwrites existing content. */
    IMPORT(isManual = false, defaultLabel = "Before import"),

    /** A validated product Raw Source draft is about to replace an artifact. */
    RAW_SOURCE(isManual = false, defaultLabel = "Before applying Raw Source"),

    /** A format conversion is about to rewrite an artifact's representation (§13.7). */
    FORMAT_CONVERSION(isManual = false, defaultLabel = "Before format conversion"),

    /** A mass style replacement is about to restyle many blocks at once (§13.7). */
    STYLE_REPLACE(isManual = false, defaultLabel = "Before a style replacement"),

    /** A risky restructure (bulk move, page reorganization, container rewrite) (§13.7). */
    RESTRUCTURE(isManual = false, defaultLabel = "Before a restructure"),

    /**
     * The live state, captured immediately before a restore.
     *
     * Restoring is itself destructive, so it gets the same protection as any other risky operation — which
     * is what makes "restore, look, change your mind" survivable rather than a one-way door.
     */
    BEFORE_RESTORE(isManual = false, defaultLabel = "Before restoring a recovery point"),

    /** A user- or app-requested manual checkpoint. */
    MANUAL_CHECKPOINT(isManual = true, defaultLabel = "Manual restore point"),
}
