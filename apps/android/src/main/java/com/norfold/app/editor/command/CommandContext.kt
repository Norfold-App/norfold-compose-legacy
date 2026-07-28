package com.norfold.app.editor.command

/**
 * The immutable context a command is resolved and executed against (spec §4.17 "resolve context").
 *
 * Phase 0 keeps this deliberately minimal and pure-JVM: it models *what the current selection can do*
 * ([offeredCapabilities]) and how many targets are selected, which is everything the universal contract
 * needs to decide visibility/eligibility/applicability without binding to a concrete selection or document
 * type yet (those arrive with the Selection Engine and per-domain hosts). Richer context (actual targets,
 * primary, mixed-value probes) is layered in later phases behind the same interface.
 *
 * @param offeredCapabilities capabilities the current selection supports (spec §4.3).
 * @param targetCount number of selected targets (0 = no selection).
 * @param hasPrimary whether the selection has an explicit primary target (spec §4.9).
 * @param activeCapabilities capabilities currently "on" for the selection, used to resolve ACTIVE state.
 * @param mixedCapabilities capabilities whose value differs across the selection, used to resolve MIXED.
 * @param surface the invoking surface (spec §4.1); does not affect eligibility.
 */
data class CommandContext(
    val offeredCapabilities: Set<Capability>,
    val targetCount: Int = 0,
    val hasPrimary: Boolean = false,
    val activeCapabilities: Set<Capability> = emptySet(),
    val mixedCapabilities: Set<Capability> = emptySet(),
    val surface: CommandSurface = CommandSurface.DOCK,
) {
    val hasSelection: Boolean get() = targetCount > 0
}

/** Outcome of executing a command (spec §4.17 "report success or failure"). */
sealed interface CommandResult {
    /** The command committed. [affectedCount] reports how many targets changed (spec §4.10 honesty). */
    data class Success(val affectedCount: Int = 1, val message: String? = null) : CommandResult

    /** The command did not run. [reason] is human-facing. */
    data class Failure(val reason: String) : CommandResult

    /** The command was eligible but had nothing to do (e.g. no-op on an already-correct selection). */
    data object NoOp : CommandResult
}
