package com.norfold.app.editor.flow

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BulletListBlock
import com.norfold.app.domain.CalloutBlock
import com.norfold.app.domain.CodeBlock
import com.norfold.app.domain.ContainerBlock
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.HeadingBlock
import com.norfold.app.domain.NumberedListBlock
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.QuoteBlock
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.TodoListBlock
import com.norfold.app.domain.UnknownBlock
import com.norfold.app.domain.findById
import com.norfold.app.domain.locate
import com.norfold.app.editor.command.Capability

/**
 * Maps a Flow block to the [Capability] set it offers (spec §4.3).
 *
 * This is the whole point of capability-based eligibility: commands never enumerate block types, so adding
 * a block kind never means editing a list of commands, and a future/unknown payload degrades honestly
 * instead of crashing a menu. An [UnknownBlock] is the sharpest case — this app version cannot read its
 * content, so it offers movement/duplication/deletion but never text editing or conversion, which is
 * exactly what preserves the verbatim round-trip (§17.1, R5).
 */
object FlowCapabilities {

    /** Capabilities [block] offers, given its position in [document] (nesting affects un-nesting). */
    fun of(block: DocumentBlock, document: BlockDocument): Set<Capability> = buildSet {
        // Every Flow block is selectable, reorderable, duplicable — structure is the Flow domain's job.
        add(Capability.SELECTION)
        add(Capability.MOVEMENT)
        add(Capability.DUPLICATION)
        // Any block can be wrapped into a container; a container can additionally be unwrapped.
        add(Capability.GROUPING)
        add(Capability.LINKING)
        add(Capability.COMMENTS)

        if (block.bearsEditableText()) add(Capability.TEXT_EDITING)
        if (FlowBlockKind.of(block)?.convertible == true) add(Capability.CONVERSION)
        if (block is TableBlock) add(Capability.TABLE_STRUCTURE)
        // Un-nesting is only meaningful for a block that actually lives inside a container.
        if (document.blocks.locate(block.id)?.first != null) add(Capability.FRAMING)
    }

    /** Union of the capabilities offered by [blockIds] — the selection-level offer set for a command context. */
    fun ofSelection(document: BlockDocument, blockIds: Collection<String>): Set<Capability> =
        blockIds.mapNotNull { document.findById(it) }
            .flatMapTo(linkedSetOf()) { of(it, document) }
}

/**
 * Whether the block carries inline text the user edits directly. Deliberately broader than
 * `BlockEditorSession`'s private `editableInline()` (which only answers "can I splice inline nodes here"):
 * a code block and a table both hold user text even though their text lives outside the inline model.
 */
private fun DocumentBlock.bearsEditableText(): Boolean = when (this) {
    is ParagraphBlock, is HeadingBlock, is QuoteBlock,
    is BulletListBlock, is NumberedListBlock, is TodoListBlock,
    is CodeBlock, is CalloutBlock, is TableBlock,
    -> true
    is ContainerBlock -> false
    else -> false
}
