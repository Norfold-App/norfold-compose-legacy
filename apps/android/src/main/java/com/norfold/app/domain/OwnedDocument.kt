package com.norfold.app.domain

/** A persisted block document belongs to exactly one product object. */
enum class DocumentOwnerType(val storageValue: String) {
    Note("note"),
    Task("task"),
    CalendarEvent("calendar_event"),
    ;

    companion object {
        fun fromStorage(raw: String): DocumentOwnerType? = entries.firstOrNull { it.storageValue == raw }
    }
}

data class DocumentOwner(
    val type: DocumentOwnerType,
    val id: Long,
) {
    init {
        require(id > 0) { "Document owner id must be persisted before a document is created" }
    }

    /** Stable local identifier used by document_blocks and encrypted snapshots. */
    val documentId: String get() = "${type.storageValue}:$id"

    companion object {
        fun note(id: Long) = DocumentOwner(DocumentOwnerType.Note, id)
        fun task(id: Long) = DocumentOwner(DocumentOwnerType.Task, id)
        fun calendarEvent(id: Long) = DocumentOwner(DocumentOwnerType.CalendarEvent, id)
    }
}

data class OwnedDocument(
    val owner: DocumentOwner,
    val document: BlockDocument,
    val layoutMode: DocOverlapMode = DocOverlapMode.Reflow,
    val freeformLayout: Map<String, FreeformPlacement> = emptyMap(),
    val canvasSpec: DocCanvasSpec = DocCanvasSpec(),
    val createdAt: Long,
    val updatedAt: Long,
)
