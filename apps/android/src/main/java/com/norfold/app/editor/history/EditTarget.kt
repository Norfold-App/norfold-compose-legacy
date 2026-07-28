package com.norfold.app.editor.history

/**
 * Identifies an undo/redo target — one editable document owned by exactly one product object.
 *
 * Mirrors [com.norfold.app.domain.DocumentOwner.documentId] (`"note:42"`, `"task:7"`, …) but is a
 * standalone value so the History Engine stays pure-JVM and does not depend on persistence types.
 *
 * A single [EditTransaction] may span multiple targets; that is what makes cross-target undo atomic
 * (roadmap Phase 0 D2 / spec §13.4).
 */
@JvmInline
value class EditTarget(val id: String) {
    init {
        require(id.isNotBlank()) { "EditTarget id must not be blank" }
    }

    companion object {
        fun note(id: Long) = EditTarget("note:$id")
        fun task(id: Long) = EditTarget("task:$id")
        fun calendarEvent(id: Long) = EditTarget("calendar_event:$id")
    }
}
