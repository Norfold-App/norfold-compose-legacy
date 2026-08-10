package com.norfold.app.collaboration.editor

import com.norfold.app.collaboration.protocol.SemanticBatch
import com.norfold.app.editor.history.EditTransaction
import com.norfold.app.editor.history.HistoryEngine
import com.norfold.app.editor.history.HistoryListener
import com.norfold.app.editor.history.HistoryConflictResolver
import com.norfold.app.editor.history.EditOperation
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.domain.BlockDocument
import com.norfold.app.editor.history.TransactionOrigin
import java.util.LinkedHashMap

/** History remains the sole owner of Undo; this listener publishes committed and compensating batches. */
class CollaborationHistoryBridge(
    private val adapter: EditorDomainMutationAdapter,
    private val publish: (SemanticBatch) -> Unit,
) : HistoryListener, HistoryConflictResolver {
    private val movedIds = object : LinkedHashMap<String, List<String?>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String?>>?): Boolean = size > 4_096
    }
    private var coalescedRecordId: String? = null
    private var coalescingKey: String? = null

    override fun onCommitted(transaction: EditTransaction) {
        val moved = transaction.operations.map { operation ->
            if (operation is EditOperation.MoveBlock) adapter.currentBlock(operation.target, operation.toIndex)?.id else null
        }
        val recordId = if (transaction.coalescingKey != null && transaction.coalescingKey == coalescingKey) {
            coalescedRecordId ?: transaction.id
        } else {
            transaction.id
        }
        movedIds[recordId] = movedIds[recordId].orEmpty() + moved
        coalescedRecordId = recordId.takeIf { transaction.coalescingKey != null }
        coalescingKey = transaction.coalescingKey
        if (transaction.origin == TransactionOrigin.LOCAL_USER || transaction.origin == TransactionOrigin.IMPORT) {
            publish(adapter.history(transaction))
        }
    }

    override fun onUndoneApplied(transaction: EditTransaction, appliedOperations: List<EditOperation>) {
        resetCoalescing()
        if (appliedOperations.isEmpty()) return
        val inverse = transaction.inverse().copy(
            id = "undo:${transaction.id}",
            label = "Undo ${transaction.label}",
            origin = TransactionOrigin.LOCAL_USER,
            coalescingKey = null,
            operations = appliedOperations,
        )
        publish(adapter.history(inverse))
    }

    override fun onRedoneApplied(transaction: EditTransaction, appliedOperations: List<EditOperation>) {
        resetCoalescing()
        if (appliedOperations.isEmpty()) return
        publish(adapter.history(transaction.copy(
            id = "redo:${transaction.id}", coalescingKey = null, operations = appliedOperations,
        )))
    }

    override fun resolveUndo(
        transaction: EditTransaction,
        inverse: List<EditOperation>,
        documentOf: (EditTarget) -> BlockDocument,
    ): List<EditOperation> = resolve(transaction, inverse, documentOf, undo = true)

    override fun resolveRedo(
        transaction: EditTransaction,
        operations: List<EditOperation>,
        documentOf: (EditTarget) -> BlockDocument,
    ): List<EditOperation> = resolve(transaction, operations, documentOf, undo = false)

    override fun redoOperationsForAppliedUndo(
        transaction: EditTransaction,
        inverse: List<EditOperation>,
        appliedInverse: List<EditOperation>,
    ): List<EditOperation> {
        if (appliedInverse.isEmpty()) return emptyList()
        val applied = appliedInverse.toMutableList()
        return inverse.zip(transaction.operations.asReversed()).mapNotNull { (candidateInverse, original) ->
            val index = applied.indexOfFirst { sameLogicalOperation(it, candidateInverse) }
            if (index < 0) null else original.also { applied.removeAt(index) }
        }.asReversed()
    }

    override fun onRemoteIntegrated(transaction: EditTransaction) = resetCoalescing()

    private fun resolve(
        transaction: EditTransaction,
        operations: List<EditOperation>,
        documentOf: (EditTarget) -> BlockDocument,
        undo: Boolean,
    ): List<EditOperation> = operations.mapIndexedNotNull { index, operation ->
        val document = documentOf(operation.target)
        when (operation) {
            is EditOperation.RemoveBlock -> {
                val actual = document.blocks.indexOfFirst { it.id == operation.block.id }
                if (actual < 0) null else operation.copy(index = actual)
            }
            is EditOperation.InsertBlock -> {
                if (document.blocks.any { it.id == operation.block.id }) null
                else operation.copy(index = operation.index.coerceIn(0, document.blocks.size))
            }
            is EditOperation.ReplaceBlock -> {
                val actual = document.blocks.indexOfFirst { it.id == operation.before.id }
                if (actual < 0 || document.blocks[actual] != operation.before) null
                else operation.copy(index = actual)
            }
            is EditOperation.MoveBlock -> {
                val originalIndex = if (undo) transaction.operations.lastIndex - index else index
                val blockId = movedIds[transaction.id]?.getOrNull(originalIndex) ?: return@mapIndexedNotNull null
                val actual = document.blocks.indexOfFirst { it.id == blockId }
                if (actual < 0 || document.blocks.isEmpty()) null
                else operation.copy(fromIndex = actual, toIndex = operation.toIndex.coerceIn(0, document.blocks.lastIndex))
            }
        }
    }

    private fun sameLogicalOperation(first: EditOperation, second: EditOperation): Boolean = when {
        first is EditOperation.InsertBlock && second is EditOperation.InsertBlock ->
            first.target == second.target && first.block.id == second.block.id
        first is EditOperation.RemoveBlock && second is EditOperation.RemoveBlock ->
            first.target == second.target && first.block.id == second.block.id
        first is EditOperation.ReplaceBlock && second is EditOperation.ReplaceBlock ->
            first.target == second.target && first.before.id == second.before.id && first.after.id == second.after.id
        first is EditOperation.MoveBlock && second is EditOperation.MoveBlock -> first.target == second.target
        else -> false
    }

    private fun resetCoalescing() {
        coalescedRecordId = null
        coalescingKey = null
    }
}

/** Remote integration updates live state atomically but never creates a local user undo step. */
fun HistoryEngine.integrateRemote(transaction: EditTransaction) = applyRemote(
    transaction.copy(origin = TransactionOrigin.REMOTE_MERGE, coalescingKey = null),
)
