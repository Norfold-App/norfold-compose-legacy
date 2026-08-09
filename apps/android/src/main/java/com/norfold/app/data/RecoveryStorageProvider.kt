package com.norfold.app.data

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.norfold.app.editor.recovery.FileRecoverySnapshotStore
import com.norfold.app.editor.recovery.InMemoryRecoverySnapshotStore
import com.norfold.app.editor.recovery.RecoveryRetentionPolicy
import com.norfold.app.editor.recovery.RecoverySnapshotStore
import java.io.File

/**
 * Android wiring for durable recovery storage (spec §13.7; Phase-10 §17–§19).
 *
 * Where the data lives *is* the protection. `filesDir` is the app-private sandbox: other apps cannot read
 * it, it is excluded from shared external storage, and nothing here is uploaded, synced, logged, or sent to
 * an analytics or collaboration service. There is no cipher and no key derivation in this path — adding one
 * would mean inventing a key-management story, and a home-rolled one protects nothing while looking like it
 * does. Sandbox storage is the honest boundary for this phase.
 *
 * One directory per artifact keeps a restore point from ever being offered against the wrong document: an
 * id collision between two notes cannot cross a directory boundary. The artifact key is sanitized for the
 * same reason [FileRecoverySnapshotStore] sanitizes ids — a path segment derived from data is exactly where
 * a stray separator turns into a write outside the sandbox.
 */
class RecoveryStorageProvider(
    context: Context,
    private val retention: RecoveryRetentionPolicy = RecoveryRetentionPolicy(),
) {
    private val root = File(context.applicationContext.filesDir, DIRECTORY)

    /** The durable store for one artifact. Cheap to construct; the index is read lazily on first use. */
    fun storeFor(artifactKey: String): FileRecoverySnapshotStore =
        FileRecoverySnapshotStore(File(root, sanitize(artifactKey)), retention)

    /** Directory backing one artifact's restore points, for diagnostics that report storage location only. */
    fun directoryFor(artifactKey: String): File = File(root, sanitize(artifactKey))

    /**
     * Sweeps records left behind by a process killed between the payload write and the index write.
     *
     * Called at a natural idle point rather than on every launch: it walks directories, and recovery must
     * never be the reason a cold start is slow.
     */
    fun pruneOrphans(): Int = root.listFiles().orEmpty()
        .filter(File::isDirectory)
        .sumOf { FileRecoverySnapshotStore(it, retention).pruneOrphans() }

    /** Total bytes recovery currently occupies across every artifact, for honest storage reporting. */
    fun totalBytes(): Long = root.listFiles().orEmpty()
        .filter(File::isDirectory)
        .sumOf { FileRecoverySnapshotStore(it, retention).storageBytes() }

    private fun sanitize(key: String): String {
        val cleaned = key.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
        return cleaned.take(MAX_SEGMENT_LENGTH).ifBlank { FALLBACK_SEGMENT }
    }

    companion object {
        const val DIRECTORY = "editor-recovery-v1"
        private const val MAX_SEGMENT_LENGTH = 96
        private const val FALLBACK_SEGMENT = "artifact"
    }
}

/**
 * The durable store for one artifact, or an in-memory one when the durable path is not wanted.
 *
 * Keyed on [durable] as well as the artifact so turning the flag off does not leave a file-backed store
 * behind: a store that keeps writing after its feature was disabled is exactly the kind of leftover that
 * makes "off" untestable. Nothing touches the disk until the first snapshot is stored.
 */
@Composable
fun rememberRecoverySnapshotStore(artifactKey: String, durable: Boolean): RecoverySnapshotStore {
    val context = LocalContext.current.applicationContext
    return remember(context, artifactKey, durable) {
        if (durable) RecoveryStorageProvider(context).storeFor(artifactKey)
        else InMemoryRecoverySnapshotStore()
    }
}
