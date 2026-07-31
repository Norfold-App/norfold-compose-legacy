package com.norfold.app.editor.history

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.DocumentBlock

/**
 * Produces canonical top-level operations for an arbitrary block-document mutation.
 *
 * Both editor hosts persist and undo at the top-level block boundary. Keeping the diff here, next to the
 * History Engine, prevents shared document features (styles, tokens, imported objects) from depending on a
 * host-specific implementation while preserving nested edits as a replace of their owning top-level block.
 */
object BlockDocumentDiff {
    fun diff(target: EditTarget, before: BlockDocument, after: BlockDocument): List<EditOperation> {
        val beforeBlocks = before.blocks
        val afterBlocks = after.blocks
        if (beforeBlocks == afterBlocks) return emptyList()

        val afterIds = afterBlocks.mapTo(HashSet()) { it.id }
        val beforeIds = beforeBlocks.mapTo(HashSet()) { it.id }
        val operations = mutableListOf<EditOperation>()
        val working = beforeBlocks.toMutableList()

        for (index in working.lastIndex downTo 0) {
            val block = working[index]
            if (block.id !in afterIds) {
                operations += EditOperation.RemoveBlock(target, index, block)
                working.removeAt(index)
            }
        }

        afterBlocks.forEachIndexed { index, targetBlock ->
            val current = working.getOrNull(index)
            if (current?.id == targetBlock.id) {
                if (current != targetBlock) {
                    operations += EditOperation.ReplaceBlock(target, index, current, targetBlock)
                    working[index] = targetBlock
                }
                return@forEachIndexed
            }

            if (targetBlock.id !in beforeIds) {
                operations += EditOperation.InsertBlock(target, index, targetBlock)
                working.add(index, targetBlock)
                return@forEachIndexed
            }

            val sourceIndex = working.indexOfFirst { it.id == targetBlock.id }
            check(sourceIndex > index) {
                "diff invariant: kept block ${targetBlock.id} expected after index $index but found at $sourceIndex"
            }
            operations += EditOperation.MoveBlock(target, sourceIndex, index)
            val moved = working.removeAt(sourceIndex)
            working.add(index, moved)
            if (moved != targetBlock) {
                operations += EditOperation.ReplaceBlock(target, index, moved, targetBlock)
                working[index] = targetBlock
            }
        }

        return operations
    }

    fun dirtyTopLevelIds(saved: BlockDocument, current: BlockDocument): Set<String> {
        val savedById = saved.blocks.associateBy(DocumentBlock::id)
        return current.blocks.mapNotNullTo(linkedSetOf()) { block ->
            val previous = savedById[block.id]
            block.id.takeIf { previous == null || previous != block }
        }
    }
}
