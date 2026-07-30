package com.norfold.app.editor.flow

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.DropTarget
import com.norfold.app.domain.allIds
import com.norfold.app.domain.findById
import com.norfold.app.editor.command.Capability
import com.norfold.app.editor.command.CommandContext
import com.norfold.app.editor.command.CommandSurface
import com.norfold.app.editor.selection.PostCommandOutcome
import com.norfold.app.editor.selection.SelectionDomain
import com.norfold.app.editor.selection.SelectionEngine
import com.norfold.app.editor.selection.SelectionGranularity
import com.norfold.app.editor.selection.SelectionTarget
import com.norfold.app.editor.selection.TextCaret

/**
 * The live environment a Flow command resolves and executes against (roadmap Phase 1 deliverables 4–8).
 *
 * FlowHost owns exactly one scope. It binds the three Phase-0 engines a structural command needs — the
 * [FlowEditController] (which fronts the canonical History Engine), the [SelectionEngine], and the
 * capability model — into a single object the command catalogue closes over. That indirection is what lets
 * one command instance serve the dock, the slash menu, the keyboard map, the context menu, and the
 * accessibility tree without any of them passing host state through the command call (§4.1).
 *
 * Why the scope, and not `CommandContext`, carries the Flow state: the Phase-0 [CommandContext] is
 * deliberately presentation- and domain-independent, and Phase 0 fixed that surface so later phases extend
 * *around* it rather than reshaping it. The scope is that extension for Flow. [commandContext] projects the
 * scope down into the Phase-0 contract so every registry query (visible/invokable/statuses) still works
 * unchanged.
 *
 * Selection is held by the shared [SelectionEngine], not a private list, so §4.9 primary/secondary rules and
 * §4.14 post-command selection are the engine's — not reimplemented per host.
 *
 * Pure-JVM: no Android, no persistence, no collaboration. Not thread-safe; the editor loop serializes access.
 */
class FlowCommandScope(
    val controller: FlowEditController,
    val selection: SelectionEngine = SelectionEngine(),
) {

    /**
     * The cell the caret sits in when a table is being edited. Directional row/column commands require it —
     * without a cell there is no "above" or "left", and guessing is precisely the ambiguous `addRow()`
     * behavior §4.15 forbids. Row `-1` addresses the header row.
     */
    var tableCursor: FlowTableCursor? = null

    /**
     * The list/to-do item the caret sits in, when it is inside one.
     *
     * The Selection Engine's [TextCaret] addresses the *block*, because that is the unit every structural
     * command resolves against. But a list block holds several independently editable items, so "split here"
     * and "merge backwards" are ambiguous without knowing which one. Held beside [tableCursor] for the same
     * reason and with the same rule: the host reports it, commands read it, nobody guesses it.
     */
    var caretItemId: String? = null

    /**
     * The drop the host has resolved from an in-flight structural drag. `flow.move.drop` consumes it, so
     * drag/drop is the same semantic command as every other move rather than a private gesture path (§4.1).
     */
    var pendingDrop: DropTarget? = null

    /** The live document. */
    fun document(): BlockDocument = controller.document()

    // --- Selection projection -----------------------------------------------------------------------

    /** Ids of the Flow-domain targets currently selected, primary first (selection order, §4.9). */
    fun selectedBlockIds(): List<String> =
        selection.selection().filter { it.domain == SelectionDomain.FLOW }.map { it.id }

    /** Selected blocks that still exist in the document, primary first. */
    fun selectedBlocks(): List<DocumentBlock> = selectedBlockIds().mapNotNull { document().findById(it) }

    fun primaryBlockId(): String? = selection.primary()?.takeIf { it.domain == SelectionDomain.FLOW }?.id

    fun primaryBlock(): DocumentBlock? = primaryBlockId()?.let { document().findById(it) }

    fun caret(): TextCaret? = selection.caret()

    /**
     * The block a *positional* command (insert above/below, split, merge) acts on: the primary selection,
     * else the block holding the caret. Insertion falls back to the end of the document when neither exists.
     */
    fun anchorBlockId(): String? = primaryBlockId() ?: caret()?.targetId?.takeIf { document().findById(it) != null }

    /** Replaces the Flow selection with [ids] (first becomes primary). */
    fun selectBlocks(ids: List<String>) {
        // §4.5 keeps text and structural state distinct, so changing the *structural* selection must not
        // discard the caret: during multi-select the user's insertion point has to survive every toggle.
        val caret = selection.caret()
        selection.clear()
        ids.forEachIndexed { index, id ->
            val target = SelectionTarget(id, flowGranularityFor(ids.size))
            if (index == 0) selection.select(target) else selection.addToSelection(target)
        }
        caret?.let { selection.setCaret(it) }
    }

    /** Applies the §4.14 post-command selection rule and returns the resulting selection. */
    fun applyOutcome(outcome: PostCommandOutcome): List<SelectionTarget> = selection.selectionAfter(outcome)

    /** Post-command outcome that selects [ids] (Duplicate / Convert / Group / Paste, §4.14). */
    fun selectResult(ids: List<String>): PostCommandOutcome =
        PostCommandOutcome.SelectResult(ids.map { SelectionTarget(it, flowGranularityFor(ids.size)) })

    /** Post-command outcome that places the caret inside [blockId] at [offset] (Insert, §4.14). */
    fun caretInside(blockId: String, offset: Int = 0): PostCommandOutcome =
        PostCommandOutcome.CaretInside(TextCaret(blockId, offset))

    /**
     * Post-command outcome for Delete: the nearest meaningful sibling of [removedId] *as the document stood
     * before the delete*, or null when nothing remains (§4.14).
     */
    fun nearestSiblingAfterDelete(before: BlockDocument, removedId: String): PostCommandOutcome {
        val order = before.allIds()
        val index = order.indexOf(removedId)
        val current = document()
        val nearest = when {
            index < 0 -> null
            else -> (order.drop(index + 1) + order.take(index).asReversed())
                .firstOrNull { current.findById(it) != null }
        }
        return PostCommandOutcome.SelectNearestSibling(nearest?.let { SelectionTarget(it, SelectionGranularity.FLOW_BLOCK) })
    }

    // --- Capability projection ----------------------------------------------------------------------

    fun capabilitiesOf(blockId: String): Set<Capability> {
        val document = document()
        val block = document.findById(blockId) ?: return emptySet()
        return FlowCapabilities.of(block, document)
    }

    /**
     * The Phase-0 [CommandContext] for the current scope state. [CommandContext.offeredCapabilities] is the
     * *union* across the selection, matching its documented meaning ("capabilities the current selection
     * supports"); per-target eligibility for `ALL_TARGETS` policies is resolved by [FlowCommand] against the
     * Selection Engine, which is the only component that knows the individual targets.
     */
    fun commandContext(surface: CommandSurface = CommandSurface.DOCK): CommandContext {
        val ids = selectedBlockIds()
        return CommandContext(
            offeredCapabilities = FlowCapabilities.ofSelection(document(), ids),
            targetCount = ids.size,
            hasPrimary = primaryBlockId() != null,
            surface = surface,
        )
    }

    private fun flowGranularityFor(count: Int): SelectionGranularity =
        if (count > 1) SelectionGranularity.FLOW_MULTIPLE_BLOCKS else SelectionGranularity.FLOW_BLOCK
}

/**
 * A cell address inside a [com.norfold.app.domain.TableBlock]. [row] `-1` is the header row; data rows are
 * 0-based. Directional row/column commands are resolved relative to this, never to an implicit "end of
 * table" (§4.15, §7.4–§7.5).
 */
data class FlowTableCursor(val blockId: String, val row: Int, val column: Int)
