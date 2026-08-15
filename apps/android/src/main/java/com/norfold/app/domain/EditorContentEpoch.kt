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
 * The key a shared document's payload carries its epoch under, on the wire and on the server.
 *
 * A synced document is the one editor artifact that outlives the device it was written on, so the
 * stamp travels inside the payload rather than beside it: the server reads this key to refuse a
 * write from a device that has not crossed the boundary, and this device reads it to refuse a
 * document pulled from a server that has not been reset yet.
 */
const val EditorContentEpochPayloadKey: String = "editorContentEpoch"

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

/**
 * The result of asking the server to erase the editor generation it still holds.
 *
 * The local boundary is crossed in one transaction on the device; the remote half cannot be, so it
 * is owed rather than done. This type exists to say which failures are worth retrying: a device
 * that is offline, signed out, or holding an expired token still owes the reset and must ask again,
 * while a device that is not an owner or admin of the workspace never will be able to do it and has
 * to stop asking. Nothing here carries a privileged credential — the request is authorized by the
 * signed-in user's own token and the ownership check happens on the server.
 */
sealed interface RemoteEditorResetOutcome {
    /** The server erased its editor generation and is now stamped with this install's epoch. */
    data class Applied(val deletedArtifactCount: Int) : RemoteEditorResetOutcome

    /** The server had already crossed this boundary. Indistinguishable from success, on purpose. */
    data object AlreadyApplied : RemoteEditorResetOutcome

    /**
     * This account may not reset this workspace, or the workspace is gone. Asking again cannot
     * change either answer, so the device stops owing the reset and records why.
     */
    data class Refused(val code: String) : RemoteEditorResetOutcome

    /** Offline, signed out, expired, or a server error. Still owed; ask again later. */
    data class Retryable(val code: String) : RemoteEditorResetOutcome
}

/** Storage values for the settings row's remote-reset outcome column. Never shown to the owner. */
object RemoteEditorResetStatus {
    /** A fresh install never had a remote editor generation, so it owes nothing. */
    const val NotNeeded: String = "not_needed"

    /** The server acknowledged the reset. */
    const val Applied: String = "applied"

    /** The server refused permanently: not an owner or admin, or no such workspace. */
    const val Refused: String = "refused"
}

/**
 * Reads a failed reset attempt as either "ask again" or "stop asking".
 *
 * The only thing the server hands back on failure is an error message, so the decision has to be
 * made by reading it. Order matters: an expired token often reports itself as an authorization
 * problem, and a signed-out device is exactly the case that must keep the debt rather than give it
 * up, so authentication is classified before authorization. Anything unrecognised is retryable —
 * abandoning the boundary on a message this build does not know how to read would leave the erased
 * generation sitting on the server forever.
 */
fun classifyRemoteEditorResetFailure(message: String?): RemoteEditorResetOutcome {
    val text = message.orEmpty().lowercase()
    return when {
        "jwt" in text || "401" in text || "expired" in text ->
            RemoteEditorResetOutcome.Retryable("authentication_expired")
        "editor_reset_denied" in text || "403" in text || "denied" in text ->
            RemoteEditorResetOutcome.Refused("authorization_denied")
        "editor_reset_unknown_workspace" in text || "404" in text ->
            RemoteEditorResetOutcome.Refused("unknown_workspace")
        "editor_reset_invalid_epoch" in text -> RemoteEditorResetOutcome.Refused("invalid_epoch")
        else -> RemoteEditorResetOutcome.Retryable("reset_service_unavailable")
    }
}
