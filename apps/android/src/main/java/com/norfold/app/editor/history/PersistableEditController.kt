package com.norfold.app.editor.history

import com.norfold.app.domain.BlockDocument

/**
 * What a save coordinator needs from an edit controller, and nothing more.
 *
 * Both hosts arrived at the same persistence contract independently — the repository writes one Room row
 * per top-level block, so "which blocks changed", "which were removed" and "is the baseline I measured
 * against still current" are the questions any host must answer, regardless of whether its blocks are
 * paragraphs in a flow or pages in a scene. Naming that contract lets one proven save state machine serve
 * both hosts instead of each host growing its own copy of the debounce, the serialization, the bounded
 * retry and the stale-completion guard — four things that are individually subtle and collectively where
 * durability bugs live.
 *
 * This is deliberately *not* a shared host: it says nothing about selection, commands, presentation or
 * document semantics. It is the persistence seam only.
 */
interface PersistableEditController {

    /** The document this controller edits — the target a save is scoped to. */
    val target: EditTarget

    /** The live, authoritative document. */
    fun document(): BlockDocument

    /** Top-level block ids whose content is new or changed since the last accepted save. */
    fun dirtyBlockIds(): Set<String>

    /** Top-level block ids present at the last save but gone now — their rows must be deleted. */
    fun deletedBlockIds(): Set<String>

    fun hasUnsavedChanges(): Boolean

    /**
     * Advances every time the baseline moves. A save reads this before starting and hands it back on
     * success, which is how a completion that raced a remote merge is recognized as describing a baseline
     * that no longer exists.
     */
    fun baselineGeneration(): Long

    /**
     * Marks [persisted] as the baseline **only if** the baseline has not moved since [expectedGeneration].
     * Returns true when it was applied. Declining leaves everything dirty for the next attempt, which is a
     * far cheaper outcome than declaring content saved that the accepted snapshot never contained.
     */
    fun markSavedIfCurrent(persisted: BlockDocument, expectedGeneration: Long): Boolean
}
