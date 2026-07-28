package com.norfold.app.editor.history

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.DocumentBlock

/**
 * A single reversible edit against one [EditTarget]'s top-level block list.
 *
 * Roadmap Phase 0 D2 / spec §13.1–§13.4. Each operation carries enough recorded state to produce a
 * **deterministic inverse** locally — the History Engine never reverse-engineers an inverse and never
 * asks a CRDT/merge engine for one (§14.7 boundary; merge engines may *later* supply additional
 * inversion primitives only through the Phase-12 seam, never in Phase 0).
 *
 * Granularity is the top-level block — the same unit the repository persists at
 * ([com.norfold.app.data] `DocumentBlockEntity`). This is a genuine operation-record transaction
 * model, not a whole-document snapshot deque, and it is not the final per-domain editor schema
 * (those are finalized in their owning phases).
 *
 * Every operation both validates against and transforms an immutable [BlockDocument], returning a new
 * document; it never mutates in place. [inverse] returns the operation that exactly undoes it.
 */
sealed interface EditOperation {
    val target: EditTarget

    /** Applies this operation to [document]. Throws [HistoryStateException] if preconditions do not hold. */
    fun applyTo(document: BlockDocument): BlockDocument

    /** The operation that exactly reverses [applyTo]. Composing `op.inverse().applyTo(op.applyTo(d)) == d`. */
    fun inverse(): EditOperation

    /** Insert [block] at [index] in the target's top-level block list. */
    data class InsertBlock(
        override val target: EditTarget,
        val index: Int,
        val block: DocumentBlock,
    ) : EditOperation {
        override fun applyTo(document: BlockDocument): BlockDocument {
            val blocks = document.blocks
            if (index < 0 || index > blocks.size) {
                throw HistoryStateException("InsertBlock index $index out of bounds for size ${blocks.size} on ${target.id}")
            }
            if (blocks.any { it.id == block.id }) {
                throw HistoryStateException("InsertBlock duplicate block id ${block.id} on ${target.id}")
            }
            return document.copy(blocks = blocks.toMutableList().apply { add(index, block) })
        }

        override fun inverse(): EditOperation = RemoveBlock(target, index, block)
    }

    /** Remove the block at [index]; [block] is the recorded value, used to invert. */
    data class RemoveBlock(
        override val target: EditTarget,
        val index: Int,
        val block: DocumentBlock,
    ) : EditOperation {
        override fun applyTo(document: BlockDocument): BlockDocument {
            val blocks = document.blocks
            if (index < 0 || index >= blocks.size) {
                throw HistoryStateException("RemoveBlock index $index out of bounds for size ${blocks.size} on ${target.id}")
            }
            if (blocks[index].id != block.id) {
                throw HistoryStateException("RemoveBlock id mismatch at $index on ${target.id}: expected ${block.id}, found ${blocks[index].id}")
            }
            return document.copy(blocks = blocks.toMutableList().apply { removeAt(index) })
        }

        override fun inverse(): EditOperation = InsertBlock(target, index, block)
    }

    /** Replace the block at [index]; both [before] and [after] are recorded for a symmetric inverse. */
    data class ReplaceBlock(
        override val target: EditTarget,
        val index: Int,
        val before: DocumentBlock,
        val after: DocumentBlock,
    ) : EditOperation {
        override fun applyTo(document: BlockDocument): BlockDocument {
            val blocks = document.blocks
            if (index < 0 || index >= blocks.size) {
                throw HistoryStateException("ReplaceBlock index $index out of bounds for size ${blocks.size} on ${target.id}")
            }
            if (blocks[index].id != before.id) {
                throw HistoryStateException("ReplaceBlock id mismatch at $index on ${target.id}: expected ${before.id}, found ${blocks[index].id}")
            }
            return document.copy(blocks = blocks.toMutableList().apply { set(index, after) })
        }

        override fun inverse(): EditOperation = ReplaceBlock(target, index, before = after, after = before)
    }

    /** Move a block from [fromIndex] to [toIndex] within the same target. */
    data class MoveBlock(
        override val target: EditTarget,
        val fromIndex: Int,
        val toIndex: Int,
    ) : EditOperation {
        override fun applyTo(document: BlockDocument): BlockDocument {
            val blocks = document.blocks
            if (fromIndex < 0 || fromIndex >= blocks.size) {
                throw HistoryStateException("MoveBlock fromIndex $fromIndex out of bounds for size ${blocks.size} on ${target.id}")
            }
            if (toIndex < 0 || toIndex >= blocks.size) {
                throw HistoryStateException("MoveBlock toIndex $toIndex out of bounds for size ${blocks.size} on ${target.id}")
            }
            if (fromIndex == toIndex) return document
            val mutable = blocks.toMutableList()
            val moved = mutable.removeAt(fromIndex)
            mutable.add(toIndex, moved)
            return document.copy(blocks = mutable)
        }

        override fun inverse(): EditOperation = MoveBlock(target, fromIndex = toIndex, toIndex = fromIndex)
    }
}

/** Thrown when an operation's recorded preconditions do not match the live document state. */
class HistoryStateException(message: String) : IllegalStateException(message)
