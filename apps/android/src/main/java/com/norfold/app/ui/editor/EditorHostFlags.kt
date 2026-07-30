package com.norfold.app.ui.editor

import com.norfold.app.editor.flags.EditorFeatureFlags

/**
 * The process-local editor flags both parallel-host entry points resolve against.
 *
 * Every flag defaults **off**, so each route resolves to its legacy host until an internal build or a test
 * flips the corresponding flag — the parallel-host arrangement Phase 0 established, with no user-visible
 * change while a phase is in progress.
 *
 * Shared between [FlowNoteEditorScreen] and [BoundedDocumentEditorScreen] because the two hosts are gated
 * independently but from one configuration: a test that enables DocumentHost must not have to know which
 * file happens to hold the flag instance, and two instances would let the two routes disagree about the
 * same flag.
 */
internal var editorFlags: EditorFeatureFlags = EditorFeatureFlags.productionDefaults()
    private set

/**
 * **Test/internal-only injection path.** Allows instrumentation tests to enable a host flag without
 * changing the production default.
 *
 * Call before the first composition of either editor entry point. Resets to production defaults if [flags]
 * is null.
 */
@Suppress("TestOnlyProblems") // This annotation documents intent; enforcement is via code-review only
internal fun setEditorFlagsForTesting(flags: EditorFeatureFlags?) {
    editorFlags = flags ?: EditorFeatureFlags.productionDefaults()
}
