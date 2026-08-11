package com.norfold.app.ui.editor

import com.norfold.app.editor.flags.EditorFeatureFlags

/**
 * The process-local editor flags every host entry point resolves against.
 *
 * The three host flags default **on** and nothing reads them to choose a host any more: FlowHost,
 * DocumentHost and SpreadsheetHost are the product's only editor hosts, so each route constructs its
 * host unconditionally. What survives here is the resolution of *domain* flags — Chart Studio, Image
 * Focus, Relationships, raw import/export, Guide, History UI — which the hosts read to decide which
 * finished surfaces to offer, not which editor to be.
 *
 * Shared by [FlowNoteEditorScreen], [BoundedDocumentEditorScreen] and [SpreadsheetEditorScreen] because
 * one configuration must answer for all three: two instances would let two routes disagree about the
 * same flag, and a person would see a domain appear in one host and not another.
 */
internal var editorFlags: EditorFeatureFlags = EditorFeatureFlags.productionDefaults()
    private set

/**
 * **Test/internal-only injection path.** Allows instrumentation tests to pin a flag configuration
 * without changing the production default.
 *
 * Call before the first composition of any editor entry point. Resets to production defaults if [flags]
 * is null. There is deliberately no production caller and no persisted override: a person cannot select
 * an editor architecture, because product identity is a host, not a preference.
 */
@Suppress("TestOnlyProblems") // This annotation documents intent; enforcement is via code-review only
internal fun setEditorFlagsForTesting(flags: EditorFeatureFlags?) {
    editorFlags = flags ?: EditorFeatureFlags.productionDefaults()
}
