package com.norfold.app.editor.history

/**
 * The one authoritative undo/redo history for the editor (roadmap Phase 0 D2 / spec §13.1).
 *
 * Design (production core, not a stub):
 *  - **Single linear history.** One undo stack + one redo stack of [EditTransaction] records. There is
 *    exactly one authoritative order across every [EditTarget]; there is no per-session deque.
 *  - **Atomic commit.** [commit] applies every operation in a transaction or none of them. If any
 *    operation throws, already-applied targets are rolled back to their pre-commit documents and the
 *    history is left unchanged.
 *  - **Atomic cross-target undo/redo.** [undo]/[redo] apply the (inverse) transaction across all of its
 *    targets atomically, with the same rollback guarantee (spec §13.4).
 *  - **Deterministic inverses.** Inverses come from the operations themselves ([EditOperation.inverse]),
 *    never from a CRDT/merge engine (§14.7). History owns undo semantics.
 *  - **Typing coalescing.** Consecutive commits sharing a non-null [EditTransaction.coalescingKey] merge
 *    into one undo unit, preserving the fine-grained redo-clearing behavior users expect while keeping a
 *    single logical step. Coalescing never crosses a target boundary silently: a differing key or an undo
 *    breaks the run.
 *
 * The engine mutates the supplied [EditWorkspace] as the single source of live document state and keeps
 * only transaction records itself. It is not thread-safe; callers serialize access (the editor is a
 * single-user, single-threaded interaction loop). Nothing here touches persistence, Android, or
 * collaboration; wiring is deferred to later phases behind [com.norfold.app.editor.flags] flags.
 */
class HistoryEngine(
    private val workspace: EditWorkspace,
    private val listener: HistoryListener? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val undoStack = ArrayDeque<EditTransaction>()
    private val redoStack = ArrayDeque<EditTransaction>()

    /** Key of the transaction currently at the top of the undo stack, for coalescing. Null after undo/redo. */
    private var lastCoalescingKey: String? = null

    fun canUndo(): Boolean = undoStack.isNotEmpty()
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    /** Number of undoable steps (coalesced runs count as one). */
    fun undoDepth(): Int = undoStack.size
    fun redoDepth(): Int = redoStack.size

    /** Read-only snapshot of the applied history, oldest first. The last element is the next undo. */
    fun history(): List<EditTransaction> = undoStack.toList()

    /**
     * Read-only snapshot of the undone (redoable) history, oldest-undone first. The **last** element is
     * the next redo. Phase 10 projects this alongside [history] so the History UI can show the user's
     * current position within one linear order instead of pretending undone work vanished.
     */
    fun redoHistory(): List<EditTransaction> = redoStack.toList()

    /** The transaction a global undo would reverse, without performing it. */
    fun peekUndo(): EditTransaction? = undoStack.lastOrNull()

    /** The transaction a global redo would reapply, without performing it. */
    fun peekRedo(): EditTransaction? = redoStack.lastOrNull()

    /**
     * Applies [transaction] atomically and records it. Clears the redo stack (a new edit forks history).
     *
     * If [transaction] shares a non-null [EditTransaction.coalescingKey] with the current top of the undo
     * stack, the two are merged into a single undo unit whose committed state is the later one.
     *
     * @throws HistoryStateException if any operation fails; the workspace and history are left unchanged.
     */
    fun commit(transaction: EditTransaction) {
        applyOperationsAtomic(transaction.operations)

        val stamped = if (transaction.createdAtMillis == EditTransaction.UNSTAMPED) {
            transaction.copy(createdAtMillis = clock())
        } else {
            transaction
        }
        val coalesceInto = undoStack.lastOrNull()
        val shouldCoalesce = stamped.coalescingKey != null &&
            stamped.coalescingKey == lastCoalescingKey &&
            coalesceInto != null
        if (shouldCoalesce) {
            // A coalesced run keeps the *first* keystroke's timestamp: it is one logical step that began then.
            undoStack[undoStack.lastIndex] = coalesceInto.copy(
                operations = coalesceInto.operations + stamped.operations,
                label = stamped.label,
            )
        } else {
            undoStack.addLast(stamped)
        }
        lastCoalescingKey = stamped.coalescingKey
        redoStack.clear()
        listener?.onCommitted(stamped)
        listener?.onHistoryChanged(this)
    }

    /**
     * Undoes the most recent transaction atomically across all of its targets.
     * @return the transaction that was undone, or null if there was nothing to undo.
     */
    fun undo(): EditTransaction? {
        val transaction = undoStack.removeLastOrNull() ?: return null
        try {
            applyOperationsAtomic(transaction.inverse().operations)
        } catch (e: HistoryStateException) {
            // Re-push so history stays consistent with workspace, then surface the failure.
            undoStack.addLast(transaction)
            throw e
        }
        redoStack.addLast(transaction)
        lastCoalescingKey = null
        listener?.onUndone(transaction)
        listener?.onHistoryChanged(this)
        return transaction
    }

    /**
     * Redoes the most recently undone transaction atomically across all of its targets.
     * @return the transaction that was redone, or null if there was nothing to redo.
     */
    fun redo(): EditTransaction? {
        val transaction = redoStack.removeLastOrNull() ?: return null
        try {
            applyOperationsAtomic(transaction.operations)
        } catch (e: HistoryStateException) {
            redoStack.addLast(transaction)
            throw e
        }
        undoStack.addLast(transaction)
        lastCoalescingKey = null
        listener?.onRedone(transaction)
        listener?.onHistoryChanged(this)
        return transaction
    }

    /** Clears all history without touching document state. Used when a session is torn down. */
    fun clear() {
        undoStack.clear()
        redoStack.clear()
        lastCoalescingKey = null
        listener?.onHistoryChanged(this)
    }

    /**
     * Applies [operations] in order across their targets with all-or-nothing semantics. On any failure,
     * every touched target is restored to the document it had before this call, so a partially-applied
     * cross-target transaction can never be observed.
     */
    private fun applyOperationsAtomic(operations: List<EditOperation>) {
        val touched = operations.map { it.target }.distinct()
        val rollback = touched.associateWith { workspace.documentOf(it) }
        try {
            for (op in operations) {
                val current = workspace.documentOf(op.target)
                workspace.setDocument(op.target, op.applyTo(current))
            }
        } catch (e: Throwable) {
            rollback.forEach { (target, document) -> workspace.setDocument(target, document) }
            if (e is HistoryStateException) throw e
            throw HistoryStateException("Transaction failed to apply: ${e.message}")
        }
    }
}

/** Observes history mutations. All callbacks are optional; a future History UI / persistence hook. */
interface HistoryListener {
    fun onCommitted(transaction: EditTransaction) {}
    fun onUndone(transaction: EditTransaction) {}
    fun onRedone(transaction: EditTransaction) {}
    fun onHistoryChanged(engine: HistoryEngine) {}
}
