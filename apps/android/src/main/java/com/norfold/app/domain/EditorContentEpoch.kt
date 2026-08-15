package com.norfold.app.domain

/**
 * Editor content epochs.
 *
 * An epoch names a generation of editor content: notes, documents, their blocks, the rich bodies
 * attached to tasks and calendar events, and the editor history, comments, and restore points that
 * hang off them. Raising [CurrentEditorContentEpoch] declares that everything authored under the
 * previous epoch is gone, permanently and on purpose.
 *
 * Epoch 1 exists because the app used to create documents on the owner's behalf — a guide, demo
 * notes, template content, a stress-test note — and a repair pass put them back when they were
 * deleted. There is no reliable way to tell that content apart from authored content after the
 * fact: it was written through the same tables by the same code. So the epoch boundary erases the
 * editor generation wholesale rather than guessing which rows were the app's idea.
 *
 * What an epoch boundary never touches: accounts, authentication, workspaces, settings, task and
 * card metadata, calendar metadata, chat, and standalone files that were not editor artifacts.
 *
 * Old backups and queued sync operations carry the epoch they were made in. A payload from an
 * earlier epoch is refused for editor artifacts by [acceptsEditorArtifacts] — restoring one would
 * repopulate exactly what the boundary erased — while the preserved categories in the same payload
 * still restore normally.
 */
const val CurrentEditorContentEpoch: Int = 1

/** The epoch a payload with no epoch stamp belongs to: everything written before epochs existed. */
const val UnstampedEditorContentEpoch: Int = 0

/**
 * Whether editor artifacts stamped [payloadEpoch] may be written into an install currently at
 * [installEpoch].
 *
 * Equal or newer is accepted. Older is refused: it predates a boundary this install has already
 * crossed. A newer payload is accepted rather than refused because refusing it would silently
 * discard content authored on a device that is further ahead, which is a worse failure than
 * carrying content whose epoch this build does not yet know about.
 */
fun acceptsEditorArtifacts(installEpoch: Int, payloadEpoch: Int): Boolean = payloadEpoch >= installEpoch

/** What an install would accept from one backup, reported before anything is written. */
data class RestoreEpochPreview(
    val installEpoch: Int,
    val snapshotEpoch: Int,
    val editorArtifactsAccepted: Boolean,
    val refusedNoteCount: Int,
    val refusedOwnedDocumentCount: Int,
)

/** The result of a restore attempt. A refusal has written nothing. */
sealed interface RestoreOutcome {
    data object Restored : RestoreOutcome

    /**
     * The backup predates an editor content epoch boundary this install has crossed, so its notes
     * and documents were not written and neither was anything else. The caller decides whether to
     * restore only the preserved categories.
     */
    data class EditorEpochRefused(val preview: RestoreEpochPreview) : RestoreOutcome
}
