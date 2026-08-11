package com.norfold.app.editor

import com.norfold.app.domain.Destination
import com.norfold.app.domain.DocOverlapMode

/**
 * The one place that decides which editor host opens a stored document.
 *
 * Every editor destination in this app resolves to exactly one of three hosts — FlowHost,
 * DocumentHost, SpreadsheetHost — and this object is the whole of that decision. It is deliberately a
 * pure function of stored state: no view model, no Compose, no Android. That is what lets the rule be
 * asserted directly rather than inferred from the behaviour of a screen.
 *
 * **Why the stored layout mode still decides this.** [DocOverlapMode] is a persistence tag, not a
 * product. Reading it here is not a layout flag choosing an editor — it is the router asking what a row
 * on disk says it is, which is the only question available before the document is open. The retired
 * arrangement was different in kind: one host that changed its own behaviour according to the same tag,
 * so the tag was the product. Here the tag selects between hosts that do not know it exists.
 *
 * **Why `Overlap` opens the bounded host.** The Overlap product is retired; its stored rows are not.
 * A row tagged Overlap is a bounded-geometry document written by an editor that no longer ships, so it
 * opens in the host that understands bounded geometry, which converts it on open. Routing it anywhere
 * else would either resurrect a retired product or open a positioned document in a host with no
 * concept of position.
 */
object EditorRoute {

    /**
     * The editor destination for a document whose stored layout mode is [mode].
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
