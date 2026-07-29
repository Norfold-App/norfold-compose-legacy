package com.norfold.app.editor.flow

import com.norfold.app.editor.command.CommandResult
import com.norfold.app.editor.command.CommandSurface
import com.norfold.app.editor.presentation.FormFactor
import com.norfold.app.editor.presentation.PresentationMode
import com.norfold.app.editor.surface.DismissTrigger

/**
 * Observation points for FlowHost (roadmap Phase 1 deliverable 10).
 *
 * Every hook fires from the **runtime**, never from a Compose callback, so instrumentation reports what
 * actually happened to the shared engines rather than what a particular widget believed it did. That
 * distinction matters for the one number Phase 1 has to defend — whether a command reached from the dock,
 * slash, keyboard or accessibility is genuinely the same command — because with the hooks here, "same id,
 * different surface" is a fact recorded at the single invocation path rather than four separate call sites
 * that could drift.
 *
 * All methods are no-ops by default; the host installs an implementation when profiling or logging.
 * Pure-JVM, so tests can assert on the emitted sequence without an Android runtime.
 */
interface FlowHostInstrumentation {

    /** A command finished, whatever its outcome. [surface] is the route the user actually took. */
    fun onCommandInvoked(commandId: String, surface: CommandSurface, result: CommandResult) {}

    fun onSurfaceOpened(surfaceId: String) {}

    fun onSurfaceDismissed(surfaceId: String, trigger: DismissTrigger) {}

    /** Multi-select entered/exited, with the live selected count (the number the Cancel/Done bar shows). */
    fun onSelectionModeChanged(active: Boolean, selectedCount: Int) {}

    /** The window reflowed; [mode] is what the Adaptive Presentation runtime chose for the open tools. */
    fun onPresentationChanged(mode: PresentationMode, formFactor: FormFactor) {}

    /**
     * The virtualized block list reported the range it is actually composing. This is the measurement that
     * proves Flow rendering stays lazy: a host that quietly composed the whole document would report the
     * full range on every scroll.
     */
    fun onVisibleRangeChanged(firstIndex: Int, lastIndex: Int, totalBlocks: Int) {}

    /** The slash menu opened, updated or closed. [resultCount] is -1 when the menu is closed. */
    fun onSlashSessionChanged(query: String?, resultCount: Int) {}

    companion object {
        /** The default: measure nothing, cost nothing. */
        val None: FlowHostInstrumentation = object : FlowHostInstrumentation {}
    }
}

/** Records every hook in order. For tests and for a future in-app diagnostics screen. */
class RecordingFlowHostInstrumentation : FlowHostInstrumentation {
    private val entries = mutableListOf<String>()

    fun events(): List<String> = entries.toList()
    fun clear() = entries.clear()

    override fun onCommandInvoked(commandId: String, surface: CommandSurface, result: CommandResult) {
        val outcome = when (result) {
            is CommandResult.Success -> "success(${result.affectedCount})"
            is CommandResult.Failure -> "failure"
            CommandResult.NoOp -> "noop"
        }
        entries += "command:$commandId:$surface:$outcome"
    }

    override fun onSurfaceOpened(surfaceId: String) { entries += "surface.open:$surfaceId" }

    override fun onSurfaceDismissed(surfaceId: String, trigger: DismissTrigger) {
        entries += "surface.dismiss:$surfaceId:$trigger"
    }

    override fun onSelectionModeChanged(active: Boolean, selectedCount: Int) {
        entries += "selectionMode:$active:$selectedCount"
    }

    override fun onPresentationChanged(mode: PresentationMode, formFactor: FormFactor) {
        entries += "presentation:$mode:${formFactor.widthDp}x${formFactor.heightDp}"
    }

    override fun onVisibleRangeChanged(firstIndex: Int, lastIndex: Int, totalBlocks: Int) {
        entries += "visible:$firstIndex-$lastIndex/$totalBlocks"
    }

    override fun onSlashSessionChanged(query: String?, resultCount: Int) {
        entries += "slash:${query ?: "-"}:$resultCount"
    }
}
