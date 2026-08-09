package com.norfold.app.editor.history

import com.norfold.app.editor.command.ApplicabilityPolicy
import com.norfold.app.editor.command.Capability
import com.norfold.app.editor.command.CommandContext
import com.norfold.app.editor.command.CommandRegistry
import com.norfold.app.editor.command.CommandResult
import com.norfold.app.editor.command.CommandStatus
import com.norfold.app.editor.command.EditorCommand
import com.norfold.app.editor.recovery.RecoveryBrowserRuntime

/**
 * Ids for the History and restore-point commands (spec §4.4).
 *
 * [UNDO] and [REDO] are the ids the table catalog already publishes, reused rather than re-minted per host.
 * Two ids for one idea is how a Command Explorer ends up offering "Undo" twice with different behaviour
 * depending on which editor happens to be open, which is exactly what §4.1 forbids.
 */
object HistoryCommandIds {
    const val UNDO = "history.undo"
    const val REDO = "history.redo"
    const val OPEN_BROWSER = "history.browser.open"
    const val CLEAR_FILTER = "history.filter.clear"
    const val OPEN_RESTORE_POINTS = "history.recovery.open"
    const val CREATE_RESTORE_POINT = "history.recovery.create"

    /** The ids a host registers when the History browser exists. Recovery ids are added only with a store. */
    val core = listOf(UNDO, REDO, OPEN_BROWSER, CLEAR_FILTER)
    val recovery = listOf(OPEN_RESTORE_POINTS, CREATE_RESTORE_POINT)
}

/**
 * One History command.
 *
 * [status] is answered by a live probe instead of the capability rules, because undo is not a per-selection
 * command: what decides whether it can run is the artifact's history, not what happens to be selected. The
 * probe returns the same reason the toolbar shows, so a row that cannot run says why rather than greying out.
 *
 * [description] is derived on read for the same reason the toolbar affordance is: "Undo Typing" that still
 * says "Typing" after the typing was undone is worse than no description at all.
 */
class HistoryCommand(
    override val id: String,
    override val label: String,
    private val describe: () -> String,
    private val probe: () -> CommandStatus,
    private val action: () -> CommandResult,
) : EditorCommand {
    override val description: String get() = describe()
    override val icon: String? = null
    override val category: String = CATEGORY
    override val aliases: List<String> = listOf(label.lowercase())
    override val requiredCapabilities: Set<Capability> = emptySet()

    // A history step applies to the artifact, never per selected target. The policy is declared because the
    // contract requires one; it is never consulted, since [status] does not use the capability rules.
    override val applicability: ApplicabilityPolicy = ApplicabilityPolicy.SELECTION_AS_UNIT

    override fun status(context: CommandContext): CommandStatus = probe()

    override fun perform(context: CommandContext): CommandResult = action()

    companion object {
        const val CATEGORY = "History"
    }
}

/**
 * The History commands for one artifact (spec §4.1, §13.2–§13.7).
 *
 * Every command here delegates to [HistoryRuntime] or [RecoveryBrowserRuntime] and reaches the workspace
 * through nothing else, so the palette cannot take a step the toolbar and the chord would refuse — including
 * a filtered undo that reaches past what the user pointed at, which is disclosed before it runs whichever
 * route asked for it.
 */
object HistoryCommands {
    const val DISCLOSURE_PENDING =
        "This reverses more than the change you pointed at. Confirm it in History first."
    const val NOT_AVAILABLE = "That is not available right now."
    const val NOT_FILTERED = "The history is not filtered."

    /**
     * Registers History commands, and restore-point commands when [recovery] exists.
     *
     * The recovery pair is omitted rather than registered-and-disabled when there is no browser: a restore
     * point this build cannot take or list is not something to advertise in a command palette.
     *
     * [includeStepCommands] is false for a host whose own catalog already publishes [HistoryCommandIds.UNDO]
     * and [HistoryCommandIds.REDO] — the table catalog does, and its actions route through the same History
     * path. Registering them twice would be rejected outright, and re-minting them under a second id is the
     * duplication §4.1 exists to prevent.
     */
    fun register(
        registry: CommandRegistry,
        history: HistoryRuntime,
        recovery: RecoveryBrowserRuntime? = null,
        includeStepCommands: Boolean = true,
    ) {
        if (includeStepCommands) {
            registry.registerAll(
                HistoryCommand(
                    id = HistoryCommandIds.UNDO,
                    label = "Undo",
                    describe = { history.availability().undoDescription ?: "Reverse the most recent change." },
                    probe = {
                        val availability = history.availability()
                        if (availability.canUndo) CommandStatus.Available
                        else CommandStatus.disabled(availability.undoReason)
                    },
                    action = { report(history, history.undo()) },
                ),
                HistoryCommand(
                    id = HistoryCommandIds.REDO,
                    label = "Redo",
                    describe = { history.availability().redoDescription ?: "Reapply the change you just undid." },
                    probe = {
                        val availability = history.availability()
                        if (availability.canRedo) CommandStatus.Available
                        else CommandStatus.disabled(availability.redoReason)
                    },
                    action = { report(history, history.redo()) },
                ),
            )
        }
        registry.registerAll(
            HistoryCommand(
                id = HistoryCommandIds.OPEN_BROWSER,
                label = "History",
                describe = { "See every change to this document and undo back to any of them." },
                probe = { CommandStatus.Available },
                action = { if (history.open()) CommandResult.Success() else CommandResult.NoOp },
            ),
            HistoryCommand(
                id = HistoryCommandIds.CLEAR_FILTER,
                label = "Clear history filter",
                describe = { "Show the whole history again, so Undo applies to the newest change." },
                probe = {
                    if (history.mode() == HistoryMode.CONTEXTUAL) CommandStatus.Available
                    else CommandStatus.disabled(NOT_FILTERED)
                },
                action = {
                    if (history.mode() == HistoryMode.GLOBAL) CommandResult.NoOp
                    else {
                        history.clearFilter()
                        CommandResult.Success()
                    }
                },
            ),
        )
        if (recovery == null) return
        registry.registerAll(
            HistoryCommand(
                id = HistoryCommandIds.OPEN_RESTORE_POINTS,
                label = "Restore points",
                describe = { "Open the restore points kept for this document." },
                probe = { CommandStatus.Available },
                action = { if (recovery.open()) CommandResult.Success() else CommandResult.NoOp },
            ),
            // Naming happens in the browser: a point created from here with a name the user never chose
            // would be indistinguishable from the automatic ones, which defeats the reason for taking it.
            HistoryCommand(
                id = HistoryCommandIds.CREATE_RESTORE_POINT,
                label = "Create restore point",
                describe = { "Open restore points and name a point you can come back to." },
                probe = { CommandStatus.Available },
                action = { if (recovery.open()) CommandResult.Success() else CommandResult.NoOp },
            ),
        )
    }

    /**
     * Reports what the step actually did.
     *
     * A refusal pending disclosure is neither a success nor a plain failure: the runtime is holding a plan
     * the user has to see first, and [HistoryRuntime.pendingDisclosure] carries it whichever route asked.
     * Saying so beats reporting a reversal that has not happened.
     */
    private fun report(history: HistoryRuntime, outcome: HistoryCommandOutcome): CommandResult {
        val failure = outcome.failure
        return when {
            outcome.succeeded -> CommandResult.Success(outcome.moved.size.coerceAtLeast(1))
            outcome.pendingDisclosure != null -> CommandResult.Failure(DISCLOSURE_PENDING)
            failure != null -> CommandResult.Failure(failure)
            else -> CommandResult.Failure(reasonFor(history, outcome.direction) ?: NOT_AVAILABLE)
        }
    }

    private fun reasonFor(history: HistoryRuntime, direction: HistoryDirection): String? {
        val availability = history.availability()
        return when (direction) {
            HistoryDirection.UNDO -> availability.undoReason
            HistoryDirection.REDO -> availability.redoReason
        }
    }
}
