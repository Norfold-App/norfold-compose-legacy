package com.norfold.app.editor.history

import com.norfold.app.domain.BlockDocument

/**
 * The live document state the History Engine reads and writes when it applies or inverts operations.
 *
 * A workspace holds the current [BlockDocument] for every [EditTarget] the engine may touch. Keeping
 * this behind an interface means the engine is pure (no persistence dependency) and tests can back it
 * with an in-memory map, while a later phase can back it with the Room-backed document store — without
 * changing the engine.
 *
 * Contract: [documentOf] returns the current document for a known target; [setDocument] replaces it.
 * The engine only ever writes a target it has already applied a validated operation against.
 */
interface EditWorkspace {
    fun documentOf(target: EditTarget): BlockDocument
    fun setDocument(target: EditTarget, document: BlockDocument)
    fun knownTargets(): Set<EditTarget>
}

/** Simple in-memory [EditWorkspace] for tests and headless engine use. */
class InMemoryEditWorkspace(
    initial: Map<EditTarget, BlockDocument> = emptyMap(),
) : EditWorkspace {
    private val documents = LinkedHashMap<EditTarget, BlockDocument>().apply { putAll(initial) }

    override fun documentOf(target: EditTarget): BlockDocument =
        documents[target] ?: throw HistoryStateException("Unknown target ${target.id} in workspace")

    override fun setDocument(target: EditTarget, document: BlockDocument) {
        documents[target] = document
    }

    override fun knownTargets(): Set<EditTarget> = documents.keys.toSet()

    /** Registers a target's initial document (idempotent overwrite). */
    fun register(target: EditTarget, document: BlockDocument) {
        documents[target] = document
    }
}
