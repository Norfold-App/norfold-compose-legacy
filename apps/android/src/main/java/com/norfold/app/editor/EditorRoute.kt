package com.norfold.app.editor

import com.norfold.app.domain.Destination
import com.norfold.app.domain.DocOverlapMode
import com.norfold.app.domain.DocumentArtifactKind

/**
 * The one place that decides which editor host opens a stored document.
 *
 * Every editor destination in this app resolves to exactly one of three hosts — FlowHost,
 * DocumentHost, SpreadsheetHost — and this object is the whole of that decision. It is deliberately a
 * pure function of stored state: no view model, no Compose, no Android. That is what lets the rule be
 * asserted directly rather than inferred from the behaviour of a screen.
 *
 * Product identity is now stored explicitly. [DocOverlapMode] remains only as a compatibility router
 * for rows written before that identity existed and as input to the one-time legacy conversion path.
 *
 * **Why `Overlap` opens the bounded host.** The Overlap product is retired; its stored rows are not.
 * A row tagged Overlap is a bounded-geometry document written by an editor that no longer ships, so it
 * opens in the host that understands bounded geometry, which converts it on open. Routing it anywhere
 * else would either resurrect a retired product or open a positioned document in a host with no
 * concept of position.
 */
object EditorRoute {

    /** Product identity is authoritative; layout mode is retained only for legacy format conversion. */
    fun forArtifact(kind: DocumentArtifactKind): Destination = when (kind) {
        DocumentArtifactKind.Flow -> Destination.NoteEditor
        DocumentArtifactKind.Document -> Destination.DocumentEditor
        DocumentArtifactKind.Spreadsheet -> Destination.SpreadsheetEditor
    }

    /**
     * Compatibility-only destination for a row that predates explicit artifact identity.
     *
     * Total by construction: [DocOverlapMode] is exhaustive here with no `else` arm, so a mode added
     * later fails to compile rather than silently falling through to the flow host — which is how a
     * document with real geometry would come to be opened by a host that would discard it.
     */
    fun forLayoutMode(mode: DocOverlapMode): Destination = when (mode) {
        DocOverlapMode.Reflow -> Destination.NoteEditor
        DocOverlapMode.Bounded -> Destination.DocumentEditor
        DocOverlapMode.Overlap -> Destination.DocumentEditor
    }

    /** True iff [destination] is one of the three editor hosts, as opposed to any other app surface. */
    fun isEditorHost(destination: Destination): Boolean = destination in EDITOR_HOSTS

    /**
     * The complete set of editor destinations. Stated once so a test can assert that the app has
     * exactly three editor hosts, rather than asserting three names it chose itself and passing even
     * if a fourth were added.
     */
    val EDITOR_HOSTS: Set<Destination> = setOf(
        Destination.NoteEditor,
        Destination.DocumentEditor,
        Destination.SpreadsheetEditor,
    )
}
