package com.norfold.app.editor.calculation

import com.norfold.app.editor.flow.FlowSaveCoordinator
import kotlinx.coroutines.CoroutineScope

class SpreadsheetDocumentSession(
    val runtime: SpreadsheetHostRuntime,
    val saves: FlowSaveCoordinator,
)

class SpreadsheetSessionRegistry {
    private val sessions = LinkedHashMap<String, SpreadsheetDocumentSession>()

    fun open(documentId: String, scope: CoroutineScope, create: () -> SpreadsheetDocumentSession): SpreadsheetDocumentSession {
        sessions[documentId]?.let { existing ->
            existing.saves.start(scope)
            return existing
        }
        val session = create()
        sessions[documentId] = session
        session.saves.onRetired { sessions.remove(documentId, session) }
        session.saves.start(scope)
        return session
    }

    fun close(documentId: String) { sessions[documentId]?.saves?.close() }
    fun closeAllExcept(keep: String?) { sessions.forEach { (id, session) -> if (id != keep) session.saves.close() } }
    fun closeAllExceptDocument(documentId: String?) {
        sessions.forEach { (id, session) ->
            if (documentId == null || !id.startsWith("$documentId:")) session.saves.close()
        }
    }
    fun closeAll() { sessions.values.toList().forEach { it.saves.close() } }
    fun size(): Int = sessions.size
}
