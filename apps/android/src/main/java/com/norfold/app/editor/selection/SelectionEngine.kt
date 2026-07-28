package com.norfold.app.editor.selection

import com.norfold.app.editor.command.ApplicabilityPolicy

/**
 * The authoritative local selection state for the editor (spec §4.5–§4.14).
 *
 * Design:
 *  - **Primary + secondary.** A multi-selection has exactly one explicit primary (§4.9); adding another
 *    target does not silently steal primary — the caller must [promote] it.
 *  - **Distinct state kinds.** Text caret/range and structural selection are separate fields, not one
 *    overloaded "selection" (§4.5). A caret does not clobber a structural selection implicitly.
 *  - **Remote never shares local state.** Remote collaborator selections live in their own map keyed by
 *    collaborator id and are *never* returned from local selection queries (§4.5). This is the single most
 *    important isolation invariant and is exercised directly by tests.
 *  - **Honest applicability.** [resolveApplicability] computes §4.10 outcomes with real counts so callers
 *    can never silently partially apply.
 *  - **Deterministic post-command selection.** [selectionAfter] implements the §4.14 defaults.
 *
 * Not thread-safe; the editor loop serializes access. Pure-JVM — no Android, no persistence.
 */
class SelectionEngine {

    private val primaryAndSecondary = LinkedHashMap<String, SelectionTarget>()
    private var primaryId: String? = null

    private var textCaret: TextCaret? = null
    private var textRange: TextRange? = null
    private var structural: SelectionTarget? = null
    private var hoverPreview: SelectionTarget? = null

    /** Remote collaborator selections, isolated by collaborator id. Never merged into local state (§4.5). */
    private val remoteSelections = LinkedHashMap<String, List<SelectionTarget>>()

    // --- Primary / secondary (§4.5, §4.9) -----------------------------------------------------------

    /** Replaces the whole selection with [target] as the sole primary. */
    fun select(target: SelectionTarget) {
        primaryAndSecondary.clear()
        primaryAndSecondary[target.id] = target
        primaryId = target.id
    }

    /** Adds [target] to the selection as a secondary; the existing primary is preserved (§4.9). */
    fun addToSelection(target: SelectionTarget) {
        if (primaryAndSecondary.isEmpty()) {
            select(target)
            return
        }
        primaryAndSecondary[target.id] = target
        // Primary is unchanged: toggling another item in never silently replaces it.
    }

    /** Toggles [target] in/out of the selection. Removing the primary promotes the next remaining target. */
    fun toggle(target: SelectionTarget) {
        if (primaryAndSecondary.containsKey(target.id)) {
            deselect(target.id)
        } else {
            addToSelection(target)
        }
    }

    /** Removes [id] from the selection; if it was primary, the first remaining target becomes primary. */
    fun deselect(id: String) {
        primaryAndSecondary.remove(id)
        if (primaryId == id) {
            primaryId = primaryAndSecondary.keys.firstOrNull()
        }
    }

    /** Explicitly promotes [id] to primary (spec §4.9 — only an explicit act changes primary). */
    fun promote(id: String) {
        require(primaryAndSecondary.containsKey(id)) { "Cannot promote $id: not in selection" }
        primaryId = id
    }

    fun primary(): SelectionTarget? = primaryId?.let { primaryAndSecondary[it] }

    fun secondaries(): List<SelectionTarget> =
        primaryAndSecondary.values.filter { it.id != primaryId }

    /** All selected targets, primary first, in insertion order otherwise. */
    fun selection(): List<SelectionTarget> {
        val p = primary() ?: return emptyList()
        return listOf(p) + secondaries()
    }

    val count: Int get() = primaryAndSecondary.size
    val isMultiSelect: Boolean get() = primaryAndSecondary.size > 1

    /** The domains currently represented in the selection (a mixed-domain selection has more than one). */
    fun domains(): Set<SelectionDomain> = primaryAndSecondary.values.map { it.domain }.toSet()

    fun clear() {
        primaryAndSecondary.clear()
        primaryId = null
        textCaret = null
        textRange = null
        structural = null
    }

    // --- Distinct text + structural state (§4.5) ----------------------------------------------------

    fun setCaret(caret: TextCaret) { textCaret = caret; textRange = null }
    fun setTextRange(range: TextRange) { textRange = range; textCaret = null }
    fun caret(): TextCaret? = textCaret
    fun textRange(): TextRange? = textRange

    fun setStructural(target: SelectionTarget) { structural = target }
    fun structural(): SelectionTarget? = structural

    fun setHoverPreview(target: SelectionTarget?) { hoverPreview = target }
    fun hoverPreview(): SelectionTarget? = hoverPreview

    // --- Remote collaborator selection — isolated (§4.5) --------------------------------------------

    /** Records a remote collaborator's selection. Kept entirely separate from local state. */
    fun setRemoteSelection(collaboratorId: String, targets: List<SelectionTarget>) {
        remoteSelections[collaboratorId] = targets.toList()
    }

    fun remoteSelection(collaboratorId: String): List<SelectionTarget> =
        remoteSelections[collaboratorId].orEmpty()

    fun remoteCollaborators(): Set<String> = remoteSelections.keys.toSet()

    fun clearRemoteSelection(collaboratorId: String) { remoteSelections.remove(collaboratorId) }

    // --- Applicability (§4.10) ----------------------------------------------------------------------

    /**
     * Resolves how a command with [policy] applies to the current selection, given [isEligible] for each
     * target. Counts are honest so the caller can state the affected count and never partial-applies silently.
     */
    fun resolveApplicability(
        policy: ApplicabilityPolicy,
        isEligible: (SelectionTarget) -> Boolean,
    ): ApplicabilityResolution {
        val targets = selection()
        if (targets.isEmpty()) return ApplicabilityResolution.NotApplicable
        val eligible = targets.filter(isEligible)
        return when (policy) {
            ApplicabilityPolicy.ALL_TARGETS ->
                if (eligible.size == targets.size) ApplicabilityResolution.AllTargets(targets.size)
                else ApplicabilityResolution.NotApplicable
            ApplicabilityPolicy.ELIGIBLE_SUBSET ->
                if (eligible.isEmpty()) ApplicabilityResolution.NotApplicable
                else ApplicabilityResolution.EligibleSubset(eligible.size, targets.size)
            ApplicabilityPolicy.PRIMARY_TARGET -> {
                val p = primary()
                if (p != null && isEligible(p)) ApplicabilityResolution.PrimaryOnly(p.id)
                else ApplicabilityResolution.NotApplicable
            }
            ApplicabilityPolicy.SELECTION_AS_UNIT ->
                if (eligible.size == targets.size) ApplicabilityResolution.AsUnit(targets.size)
                else ApplicabilityResolution.NotApplicable
        }
    }

    // --- Selection after commands (§4.14) -----------------------------------------------------------

    /**
     * Computes the deterministic selection that should follow a command outcome (spec §4.14). This mutates
     * the engine to the new selection and returns it, so hosts get consistent post-command focus without
     * each command reinventing the rule.
     */
    fun selectionAfter(outcome: PostCommandOutcome): List<SelectionTarget> {
        when (outcome) {
            is PostCommandOutcome.SelectResult -> {
                primaryAndSecondary.clear()
                outcome.results.forEach { primaryAndSecondary[it.id] = it }
                primaryId = outcome.results.firstOrNull()?.id
            }
            is PostCommandOutcome.CaretInside -> {
                clear()
                textCaret = outcome.caret
            }
            is PostCommandOutcome.SelectNearestSibling -> {
                primaryAndSecondary.clear()
                outcome.nearest?.let { primaryAndSecondary[it.id] = it }
                primaryId = outcome.nearest?.id
            }
        }
        return selection()
    }
}

/** A text caret position within a text-bearing target (spec §4.5). */
data class TextCaret(val targetId: String, val offset: Int) {
    init { require(offset >= 0) { "Caret offset must be non-negative" } }
}

/** A text range selection within a text-bearing target (spec §4.5). */
data class TextRange(val targetId: String, val start: Int, val end: Int) {
    init {
        require(start >= 0 && end >= start) { "Invalid text range [$start, $end)" }
    }
    val isCollapsed: Boolean get() = start == end
}

/** Post-command selection intents (spec §4.14) resolved by [SelectionEngine.selectionAfter]. */
sealed interface PostCommandOutcome {
    /** Duplicate / Convert / Group / Paste: the produced result(s) become selected. */
    data class SelectResult(val results: List<SelectionTarget>) : PostCommandOutcome

    /** Insert with a text target: caret is placed inside. */
    data class CaretInside(val caret: TextCaret) : PostCommandOutcome

    /** Delete: the nearest meaningful sibling or parent becomes selected (null if none remains). */
    data class SelectNearestSibling(val nearest: SelectionTarget?) : PostCommandOutcome
}
