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

/** Persisted product identity. Layout metadata describes format, never which editor owns a row. */
enum class DocumentArtifactKind(val storageValue: String) {
    Flow("flow"),
    Document("document"),
    Spreadsheet("spreadsheet"),
    ;

    companion object {
        fun fromStorage(raw: String?): DocumentArtifactKind? =
            entries.firstOrNull { it.storageValue.equals(raw, ignoreCase = true) }

        fun inferredFrom(layoutMode: DocOverlapMode, document: BlockDocument): DocumentArtifactKind =
            when {
                document.blocks.size == 1 &&
                    (document.blocks.single() as? TableBlock)?.calculationPayload != null -> Spreadsheet
                layoutMode == DocOverlapMode.Reflow -> Flow
                else -> Document
            }
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
    val artifactKind: DocumentArtifactKind = DocumentArtifactKind.inferredFrom(layoutMode, document),
)
