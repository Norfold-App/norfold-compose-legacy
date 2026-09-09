package com.norfold.app.editor.lab

/**
 * Experimental, developer-only editor UI variants — the "Editor Lab".
 *
 * This is deliberately **distinct** from [com.norfold.app.editor.flags.EditorFeatureFlag]. That enum is
 * the source of truth for *what ships*: product identity (which host, engine, and domain), and it has no
 * toggle on purpose. These are the opposite kind of flag:
 *
 *  - every entry **defaults off** and describes something being *trialled*, not something that ships;
 *  - it is flippable at runtime **only in a debug build**, through [com.norfold.app.ui.editor.lab.EditorLab]
 *    and the debug-only `EditorLabActivity`;
 *  - a release build never writes an override, so every read resolves to the `false` default and no shipped
 *    behaviour changes.
 *
 * The point is to let the owner turn candidate Canva-style treatments on and compare them on a real device,
 * then keep or drop each one — not to introduce a user-facing preference. When a variant is accepted its flag
 * and branch are removed and it becomes unconditional; when it is rejected the variant and its flag are
 * deleted together.
 */
enum class EditorLabFlag(
    /** Stable persistence/logging key. Namespaced `lab.*` so it can never collide with an architectural flag. */
    val key: String,
    /** Short label shown in the Editor Lab toggle list. */
    val title: String,
    /** One-line explanation of what turning it on does. */
    val description: String,
    /** Trialled features are off until accepted; this is always `false` here by contract. */
    val defaultEnabled: Boolean = false,
) {
    HOME_SKELETON_STATES(
        key = "lab.home.skeleton",
        title = "Home · skeleton loading",
        description = "Placeholder skeleton rows while the workspace hub loads, instead of a blank pane.",
    ),
    DEGRADED_MODE_MESSAGING(
        key = "lab.editor.degraded",
        title = "Editor · degraded-mode notice",
        description = "An explicit 'not fully editable on mobile' notice where a surface can only partially edit.",
    ),
    EDITOR_UNDO_TOAST(
        key = "lab.editor.undoToast",
        title = "Editor · undo toast",
        description = "A transient 'Done — Undo' snackbar after a structural or destructive editor action.",
    ),
    DOCUMENT_FLOATING_OBJECT_TOOLBAR(
        key = "lab.document.floatingToolbar",
        title = "Document · floating object toolbar",
        description = "A floating duplicate / delete / more bar pinned above the selected Document object.",
    ),
    DOCUMENT_LIVE_TRANSFORM_READOUT(
        key = "lab.document.transformReadout",
        title = "Document · live transform readout",
        description = "A live X/Y, size and angle bubble while dragging, resizing or rotating an object.",
    ),
    DOCUMENT_CONFIRM_CONTEXT_BAR(
        key = "lab.document.confirmBar",
        title = "Document · confirm context bar",
        description = "A selection-type-keyed bottom action row terminated by a check-to-confirm, Canva-style.",
    ),
    DOCUMENT_LAYERS_PANEL(
        key = "lab.document.layers",
        title = "Document · layers panel",
        description = "A draggable z-order layers list for the objects on the current Document page.",
    ),
    DOCUMENT_PAGE_THUMBNAIL_STRIP(
        key = "lab.document.pageStrip",
        title = "Document · page thumbnail strip",
        description = "A persistent page thumbnail strip with add, duplicate and delete.",
    ),
    ;

    companion object {
        /** Resolves a flag from its persisted [key], or null if the key is unknown (e.g. a removed experiment). */
        fun byKey(key: String): EditorLabFlag? = entries.firstOrNull { it.key == key }
    }
}
