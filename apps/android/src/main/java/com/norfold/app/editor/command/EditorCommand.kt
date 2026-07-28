package com.norfold.app.editor.command

/**
 * One semantic command, defined once and invoked from any surface (spec §4.1, §4.4).
 *
 * This is the universal command contract's Phase-0 core. A command targets **capabilities**, not object
 * types (§4.3), and exposes presentation-independent resolvers for visibility / eligibility / active /
 * mixed state (§4.2) plus an applicability policy (§4.10). Every route — dock, slash, keyboard, menu,
 * inspector, accessibility — shares this single definition, so eligibility, state, execution, and undo are
 * identical no matter where the user triggers it.
 *
 * The metadata block (id/label/description/icon/category/aliases) matches §4.4. Parameters, placement,
 * preview and transaction/collaboration/permission policy are declared by later-phase commands on top of
 * this core; Phase 0 fixes the invariant surface so those extensions never reshape it.
 */
interface EditorCommand {
    /** Stable unique id (e.g. `"text.bold"`, `"object.group"`). */
    val id: String

    /** Human label for menus/docks/accessibility. */
    val label: String

    val description: String
    val icon: String?
    val category: String

    /** Alternate names for command search / slash matching (spec §4.4). */
    val aliases: List<String>

    /** Capabilities the current selection must offer for this command to be eligible (spec §4.3). */
    val requiredCapabilities: Set<Capability>

    /** How the command applies across a multi-target selection (spec §4.10). */
    val applicability: ApplicabilityPolicy

    /**
     * Resolves presentation-independent [CommandStatus] for [context] (spec §4.2). The default implements
     * the universal rules so simple commands need no override:
     *  - hidden when the required capabilities are structurally irrelevant to the context;
     *  - disabled (with a reason) when relevant but not currently satisfiable;
     *  - active when every required capability is currently "on";
     *  - mixed when any required capability reports mixed values;
     *  - available otherwise.
     */
    fun status(context: CommandContext): CommandStatus {
        if (requiredCapabilities.isEmpty()) {
            // A context-free command (e.g. Paste) is always at least available.
            return CommandStatus.Available
        }
        if (!context.hasSelection) {
            return CommandStatus.Hidden
        }
        val missing = requiredCapabilities - context.offeredCapabilities
        if (missing.isNotEmpty()) {
            return when (applicability) {
                // A per-eligible-target command stays visible-but-disabled to explain the gap; an
                // all-targets command that can't apply anywhere is simply hidden.
                ApplicabilityPolicy.ELIGIBLE_SUBSET ->
                    CommandStatus.disabled("No selected item supports ${missing.joinToString { it.name }}")
                else -> CommandStatus.Hidden
            }
        }
        if (requiredCapabilities.any { it in context.mixedCapabilities }) {
            return CommandStatus.Mixed
        }
        if (requiredCapabilities.all { it in context.activeCapabilities }) {
            return CommandStatus.Active
        }
        return CommandStatus.Available
    }

    /**
     * Executes the command against [context]. Callers must only invoke when [status] is invokable; the
     * default guards this and returns a [CommandResult.Failure] otherwise so no command silently runs while
     * ineligible. Concrete commands override [perform] with the real transaction.
     */
    fun execute(context: CommandContext): CommandResult {
        val status = status(context)
        if (!status.isInvokable) {
            return CommandResult.Failure(status.reason ?: "Command '$id' is not available in this context")
        }
        return perform(context)
    }

    /** The real work. Phase-0 commands may be pure/no-op; domain phases commit an [EditTransaction]. */
    fun perform(context: CommandContext): CommandResult
}
