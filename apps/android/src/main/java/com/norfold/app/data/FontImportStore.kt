package com.norfold.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.norfold.app.domain.style.FontFileValidator
import com.norfold.app.domain.style.FontIdentity
import com.norfold.app.domain.style.FontValidation
import com.norfold.app.domain.style.ImportedFontEntry
import java.io.File

/**
 * Imports and stores user-supplied fonts (roadmap Phase 3 §21, §37.37–§37.44).
 *
 * The sequence is fixed and the order is the security property:
 *
 *  1. read the picked stream into memory under a hard byte cap;
 *  2. **validate** it ([FontFileValidator]) — nothing is written yet;
 *  3. write the bytes to a temp file and atomically rename into the font directory;
 *  4. only then record it in the index, which is what makes it visible to the registry.
 *
 * Step 2 before step 3 is §21's "never parse a font directly into authoritative state before validation
 * completes". A rejected file leaves no trace at all: no partial file, no index entry, nothing to clean up
 * later and nothing for a subsequent launch to re-parse hopefully.
 *
 * Everything lives in app-private storage, so an imported font is unreadable by other apps and is never
 * uploaded — [com.norfold.app.domain.style.FontPortability.PRIVATE_LOCAL] is enforced by where the bytes
 * are, not merely asserted by a field.
 */
class FontImportStore(context: Context) {

    private val appContext = context.applicationContext
    private val storage = PrivateFontStorage(File(appContext.filesDir, DIRECTORY))

    /** The current index, read from disk. Cheap enough to re-read; the file is a few KB at most. */
    fun index() = storage.index()

    fun fileFor(entry: ImportedFontEntry): File = storage.fileFor(entry)

    /**
     * Family records for the registry, **excluding entries whose file has gone missing**.
     *
     * A vanished file (restored backup, manual cleanup, storage reclaim) must not produce a family that
     * resolves successfully and then renders nothing. Dropping it here is what makes the document's
     * reference report Missing and take the substitution path (§37.42).
     */
    fun records() = storage.records()

    /** Entries the index knows about but whose bytes are gone — surfaced so the UI can explain. */
    fun missingEntries(): List<ImportedFontEntry> = storage.missingEntries()

    /**
     * Imports the font at [uri].
     *
     * Returns [FontImportResult.AlreadyImported] rather than an error for a duplicate, because "you already
     * have this font" is a success from the user's point of view and re-writing identical bytes would only
     * risk clobbering a file the editor is reading.
     */
    fun import(uri: Uri, now: Long = System.currentTimeMillis()): FontImportResult {
        val fileName = displayNameOf(uri)
        val bytes = runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                input.readAtMost(FontFileValidator.MAX_FILE_BYTES + 1)
            }
        }.getOrNull() ?: return FontImportResult.Rejected("That file couldn't be opened.")

        // Validation happens on the in-memory copy, before a single byte reaches storage.
        val validation = FontFileValidator.validate(bytes, fileName)
        val accepted = validation as? FontValidation.Accepted
            ?: return FontImportResult.Rejected((validation as FontValidation.Rejected).reason)

        return storage.install(bytes, accepted, now)
    }

    /**
     * Removes an imported font: index entry first, then the file.
     *
     * That order matters on a crash. Losing the entry but keeping the file leaves recoverable debris that
     * [pruneOrphans] clears; keeping the entry but losing the file leaves a family that claims to be
     * installed and renders nothing.
     */
    fun remove(id: String): Boolean {
        return storage.remove(id)
    }

    fun remove(identity: FontIdentity): Int {
        return storage.remove(identity)
    }

    /** Deletes font files with no index entry — i.e. bytes that never finished becoming a font. */
    fun pruneOrphans(): Int {
        return storage.pruneOrphans()
    }

    private fun displayNameOf(uri: Uri): String = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "font"

    private fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (total <= limit) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private companion object {
        const val DIRECTORY = "imported-fonts"
    }
}

/** The outcome of an import attempt. Every branch is something the UI can state plainly. */
sealed interface FontImportResult {
    data class Imported(val entry: ImportedFontEntry) : FontImportResult
    data class AlreadyImported(val entry: ImportedFontEntry) : FontImportResult
    data class Rejected(val reason: String) : FontImportResult

    val entryOrNull: ImportedFontEntry?
        get() = when (this) {
            is Imported -> entry
            is AlreadyImported -> entry
            is Rejected -> null
        }
}
