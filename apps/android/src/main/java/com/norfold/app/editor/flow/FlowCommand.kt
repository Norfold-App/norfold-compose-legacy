package com.norfold.app.editor.flow

import com.norfold.app.domain.BlockCursor
import com.norfold.app.domain.BlockEditorSession
import com.norfold.app.editor.command.ApplicabilityPolicy
import com.norfold.app.editor.command.CommandContext
import com.norfold.app.editor.command.CommandResult
import com.norfold.app.editor.command.CommandStatus
import com.norfold.app.editor.command.EditorCommand
import com.norfold.app.editor.selection.ApplicabilityResolution
import com.norfold.app.editor.selection.SelectionDomain
import com.norfold.app.editor.selection.SelectionTarget

/**
 * Base class for every Flow command (roadmap Phase 1 deliverables 4–8).
 *
 * It closes the loop between the three Phase-0 engines so no concrete command has to:
 *
 *  - **Eligibility (§4.3, §4.10)** — [status] resolves the command's [applicability] against the *actual*
 *    selection through [com.norfold.app.editor.selection.SelectionEngine.resolveApplicability], so an
 *    `ALL_TARGETS` command is correctly ineligible when only some blocks offer its capability. The Phase-0
 *    default `status()` can only see the union of offered capabilities on [CommandContext]; here the
 *    Selection Engine knows the individual targets, so this is the honest resolution and the reason this
 *    class overrides the default rather than inheriting it.
 *  - **Honest counts (§4.10)** — [perform] hands the concrete command exactly the eligible target ids and
 *    the result reports how many were affected. Silent partial application is impossible by construction.
 *  - **One command, one transaction (§4.14, §13.1)** — [edit] routes through [FlowEditController] with a
 *    null coalescing key, so every structural command is exactly one undo unit and breaks any typing run.
 *  - **Deterministic post-command selection (§4.14)** — concrete commands return a
 *    [com.norfold.app.editor.selection.PostCommandOutcome] intent; the Selection Engine applies it.
 *
 * Subclasses override [runOn] and, where a command has a precondition beyond capabilities (a predecessor to
 * merge with, a caret to split at, a resolved drop), [refine].
 */
abstract class FlowCommand(protected val scope: FlowCommandScope) : EditorCommand {

    override val description: String get() = label
    override val icon: String? get() = null
    override val category: String get() = "Flow"
    override val aliases: List<String> get() = emptyList()

    /** Human-facing explanation shown when the command is visible but cannot run right now (§4.2). */
    protected open fun unavailableReason(): String = "Not available for this selection"

    /**
     * Sharpens the resolved status with command-specific facts — ACTIVE/MIXED for state-bearing commands,
     * DISABLED for unmet preconditions, HIDDEN for commands that are meaningless outside a live gesture.
     * [resolution] is the §4.10 outcome, already known to be applicable, or `null` for a context-free
     * command (one with no required capabilities, such as insertion).
     */
    protected open fun refine(resolution: ApplicabilityResolution?): CommandStatus = CommandStatus.Available

    final override fun status(context: CommandContext): CommandStatus {
        if (requiredCapabilities.isEmpty()) {
            // Context-free (insertion): always at least available — Flow always has somewhere to insert.
            return refine(null)
        }
        val resolution = resolveApplicability()
        if (resolution is ApplicabilityResolution.NotApplicable) {
            if (scope.selectedBlockIds().isEmpty()) return CommandStatus.Hidden
            // Visible-but-disabled explains the gap for subset commands; an all-or-nothing command that
            // cannot apply anywhere is simply not shown (matches the Phase-0 universal rule).
            return if (applicability == ApplicabilityPolicy.ELIGIBLE_SUBSET) {
                CommandStatus.disabled(unavailableReason())
            } else {
                CommandStatus.Hidden
            }
        }
        return refine(resolution)
    }

    final override fun perform(context: CommandContext): CommandResult = runOn(eligibleBlockIds())

    /** The real work, against the ids this command's policy resolved as eligible (possibly empty). */
    protected abstract fun runOn(targetIds: List<String>): CommandResult

    // --- Shared resolution helpers ------------------------------------------------------------------

    /** This command's §4.10 outcome against the live selection. */
    protected fun resolveApplicability(): ApplicabilityResolution =
        scope.selection.resolveApplicability(applicability, ::isEligible)

    /** Whether one selection target satisfies this command's required capabilities. */
    protected open fun isEligible(target: SelectionTarget): Boolean =
        target.domain == SelectionDomain.FLOW &&
            requiredCapabilities.all { it in scope.capabilitiesOf(target.id) }

    /** The block ids this command will actually act on, honoring [applicability]. */
    protected fun eligibleBlockIds(): List<String> {
        val selected = scope.selection.selection().filter(::isEligible).map { it.id }
        return when (applicability) {
            ApplicabilityPolicy.PRIMARY_TARGET -> listOfNotNull(selected.firstOrNull { it == scope.primaryBlockId() })
            else -> selected
        }
    }

    /**
     * Commits [mutation] as exactly one canonical transaction labelled with this command's [label].
     * The null coalescing key is deliberate: a structural command always opens a new undo unit and severs
     * any in-progress typing run.
     */
    protected fun edit(mutation: (BlockEditorSession) -> BlockCursor): BlockCursor? =
        scope.controller.mutate(label, coalescingKey = null, mutation = mutation)
}
