package com.norfold.app.editor.flow

import com.norfold.app.domain.ContainerAxis
import com.norfold.app.editor.command.CommandRegistry
import com.norfold.app.editor.command.EditorCommand

/**
 * The Flow command catalogue (roadmap Phase 1 deliverables 2–4 and 8).
 *
 * Every structural thing FlowHost can do is defined here, once, as an [EditorCommand] over the shared
 * [CommandRegistry]. The touch `+` surface, the slash Local Command Resolver, the block menu, the context
 * dock, the keyboard map and the accessibility action list are all *views* of this catalogue — they select
 * and present commands, they never define behavior. That is the concrete difference from the legacy host,
 * where each of those routes carried its own ad-hoc implementation inside one 5,636-line composable, and it
 * is what makes keyboard/accessibility parity (deliverable 6) a property of the architecture rather than a
 * checklist item to re-verify per surface.
 *
 * Notably absent, on purpose:
 *  - **Inline text formatting** (bold/italic/link/highlight/font) — those are Phase-3 style/typography
 *    commands over the same registry; Phase 1 owns *structure*.
 *  - **Picker-backed insertion** (image, file, embed, chart, shape) — each needs a picker surface owned by a
 *    later phase; registering an insert command that cannot complete would be a false promise.
 *  - **Table deletion / calculation** — Phase 4 attaches those to the same `flow.table.*` family the
 *    directional insertion hooks establish here.
 */
object FlowCommands {

    /**
     * Builds the full catalogue bound to [scope], in the order surfaces should present it: insertion first
     * (the `+` menu), then conversion ("turn into"), then structure, then table hooks.
     */
    fun catalogue(scope: FlowCommandScope, chartStudioEnabled: Boolean = false): List<EditorCommand> = buildList {
        val insertable = FlowBlockKind.insertable + listOfNotNull(FlowBlockKind.CHART.takeIf { chartStudioEnabled })
        insertable.forEach { add(FlowInsertCommand(scope, it)) }
        add(FlowInsertAboveCommand(scope))

        FlowBlockKind.convertTargets.forEach { add(FlowConvertCommand(scope, it)) }

        add(FlowDuplicateCommand(scope))
        add(FlowDeleteCommand(scope))
        add(FlowMoveCommand(scope, up = true))
        add(FlowMoveCommand(scope, up = false))
        add(FlowDropCommand(scope))
        add(FlowNestCommand(scope))
        add(FlowUnnestCommand(scope))
        add(FlowWrapCommand(scope, ContainerAxis.Row))
        add(FlowWrapCommand(scope, ContainerAxis.Column))
        add(FlowUnwrapCommand(scope))
        add(FlowSplitCommand(scope))
        add(FlowMergePreviousCommand(scope))

        FlowTableDirection.entries.forEach { add(FlowTableInsertCommand(scope, it)) }
    }

    /** A [CommandRegistry] holding the whole catalogue. FlowHost builds exactly one of these. */
    fun registry(scope: FlowCommandScope, chartStudioEnabled: Boolean = false): CommandRegistry =
        CommandRegistry().also { registry -> catalogue(scope, chartStudioEnabled).forEach(registry::register) }
}
