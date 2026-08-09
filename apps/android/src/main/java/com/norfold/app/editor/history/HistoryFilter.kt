package com.norfold.app.editor.history

/**
 * What part of the artifact a History view is scoped to (spec §13.6).
 *
 * A scope is a *lens*, not a stack. Every variant selects rows out of the one authoritative order; none of
 * them owns transactions, and switching between them is free and lossless because nothing is copied.
 */
sealed interface HistoryScope {
    /** Everything in the artifact — the default, and the only scope global undo uses. */
    data object WholeArtifact : HistoryScope

    /** One edit target (one note/document/table identity). */
    data class OneTarget(val target: EditTarget) : HistoryScope

    /** One top-level block, by id. */
    data class OneBlock(val blockId: String) : HistoryScope

    /** One bounded-document page, by id. */
    data class OnePage(val pageId: String) : HistoryScope

    /** One scene object on a page, by id. */
    data class OneObject(val objectId: String) : HistoryScope

    /** Every entry that touched a given content domain (charts, tables, styling, …). */
    data class OneDomain(val domain: HistoryDomain) : HistoryScope

    /** True iff this scope selects a specific thing rather than the whole artifact. */
    val isContextual: Boolean get() = this !is WholeArtifact
}

/**
 * A History view specification: scope + provenance + text query (spec §13.6).
 *
 * Applying a filter is a pure read. It creates no transaction, mutates no document and moves no undo
 * position — a user narrowing the list to "charts" has not edited anything, and History must not pretend
 * otherwise by appearing in itself.
 */
data class HistoryFilter(
    val scope: HistoryScope = HistoryScope.WholeArtifact,
    /** Empty means "every origin". */
    val origins: Set<TransactionOrigin> = emptySet(),
    /**
     * The §13.6 "My changes" lens. Offline, every locally-produced transaction is the user's; the only
     * provenance that is *not* theirs is a merged remote change, which Phase 12 owns. Filtering on the
     * origin tag keeps this correct now and correct later without a second concept.
     */
    val onlyMine: Boolean = false,
    val query: String = "",
) {
    val isDefault: Boolean
        get() = scope is HistoryScope.WholeArtifact && origins.isEmpty() && !onlyMine && query.isBlank()

    fun matches(entry: HistoryEntry): Boolean =
        matchesScope(entry) && matchesOrigin(entry) && matchesQuery(entry)

    private fun matchesScope(entry: HistoryEntry): Boolean = when (val scope = scope) {
        is HistoryScope.WholeArtifact -> true
        is HistoryScope.OneTarget -> scope.target in entry.targets
        is HistoryScope.OneBlock -> scope.blockId in entry.blockIds
        is HistoryScope.OnePage -> scope.pageId in entry.pageIds
        is HistoryScope.OneObject -> scope.objectId in entry.objectIds
        is HistoryScope.OneDomain -> scope.domain in entry.domains
    }

    private fun matchesOrigin(entry: HistoryEntry): Boolean {
        if (onlyMine && entry.origin == TransactionOrigin.REMOTE_MERGE) return false
        return origins.isEmpty() || entry.origin in origins
    }

    /** Matches the human label and the derived domain names. Never document content — there is none here. */
    private fun matchesQuery(entry: HistoryEntry): Boolean {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return true
        if (entry.label.contains(trimmed, ignoreCase = true)) return true
        return entry.domains.any { it.name.contains(trimmed, ignoreCase = true) }
    }

    companion object {
        /** Longest query the History browser accepts, so an accidental paste cannot become a scan cost. */
        const val MAX_QUERY_LENGTH = 120
    }
}
