package com.norfold.app.editor.history

/**
 * One atomic, reversible unit in the authoritative history (spec §13.1, §13.4).
 *
 * A transaction bundles one or more [EditOperation]s. All operations commit together and undo
 * together; if any operation in the transaction fails to apply, none of them are applied (atomicity).
 * Because operations may target different [EditTarget]s, a single transaction can span multiple
 * documents — this is what makes **cross-target undo atomic**.
 *
 * [label] is a human-facing description shown by the Phase-10 History UI. [origin] records where the
 * transaction came from so filtered/contextual history (§13.6) is a *view* over this one record rather
 * than a competing stack. The core itself does not filter — it stores the authoritative linear order.
 *
 * [createdAtMillis] satisfies the §13.1 requirement that a history record carry a timestamp. It stays a
 * defaulted field so every existing caller keeps compiling: [HistoryEngine.commit] stamps it on the way
 * in when it is still [UNSTAMPED], which keeps the clock out of every call site and out of the domain.
 */
data class EditTransaction(
    val id: String,
    val label: String,
    val operations: List<EditOperation>,
    val origin: TransactionOrigin = TransactionOrigin.LOCAL_USER,
    val coalescingKey: String? = null,
    val createdAtMillis: Long = UNSTAMPED,
) {
    init {
        require(operations.isNotEmpty()) { "An EditTransaction must contain at least one operation" }
    }

    /** Distinct targets touched by this transaction, in first-seen order. */
    val targets: List<EditTarget> get() = operations.map { it.target }.distinct()

    /** True iff this transaction touches more than one target (a cross-target transaction). */
    val isCrossTarget: Boolean get() = targets.size > 1

    /** The inverse transaction: operations reversed in order, each individually inverted. */
    fun inverse(): EditTransaction = copy(operations = operations.asReversed().map { it.inverse() })

    companion object {
        /** Sentinel for "the engine has not stamped this yet". Keeps the domain free of a clock. */
        const val UNSTAMPED = 0L
    }
}

/**
 * Provenance of a transaction. The History Engine stays authoritative over all of these; the tag only
 * exists so later phases can present a *filtered view* (§13.6) without the core making policy choices.
 *
 * Note: `REMOTE_MERGE` is a provenance tag only. Phase 0 does not implement remote merge, does not bind
 * to `SemanticOperation`, and does not select a CRDT (§14.7). The tag reserves the vocabulary so the
 * Phase-12 integration seam has a place to attach without reshaping the core.
 */
enum class TransactionOrigin {
    LOCAL_USER,
    MIGRATION,
    IMPORT,
    RECOVERY_RESTORE,
    REMOTE_MERGE,
}
