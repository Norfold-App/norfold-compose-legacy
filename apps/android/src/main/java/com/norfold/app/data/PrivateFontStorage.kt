package com.norfold.app.data

import com.norfold.app.domain.style.FontFamilyRecord
import com.norfold.app.domain.style.FontIdentity
import com.norfold.app.domain.style.FontValidation
import com.norfold.app.domain.style.ImportedFontEntry
import com.norfold.app.domain.style.ImportedFontIndex
import java.io.File

/** Pure app-private storage boundary used after font validation has accepted the bytes. */
class PrivateFontStorage(private val directory: File) {
    private val indexFile: File get() = File(directory, INDEX_FILE)

    init { directory.mkdirs() }

    fun index(): ImportedFontIndex =
        ImportedFontIndex.decode(runCatching { indexFile.takeIf(File::isFile)?.readText() }.getOrNull())

    fun fileFor(entry: ImportedFontEntry): File = File(directory, entry.fileName)

    fun records(): List<FontFamilyRecord> {
        val present = index().entries.filter { fileFor(it).isFile }
        return ImportedFontIndex(present).toRecords { fileFor(it).absolutePath }
    }

    fun missingEntries(): List<ImportedFontEntry> = index().entries.filterNot { fileFor(it).isFile }

    fun install(bytes: ByteArray, accepted: FontValidation.Accepted, now: Long): FontImportResult {
        val current = index()
        current.findByChecksum(accepted.sha256)?.let { existing ->
            if (!fileFor(existing).isFile && !writeAtomically(fileFor(existing), bytes)) {
                return FontImportResult.Rejected("This font couldn't be saved to your device.")
            }
            return FontImportResult.AlreadyImported(existing)
        }
        val storageName = ImportedFontEntry.storageNameFor(accepted.sha256, accepted.format.extension)
        val entry = ImportedFontEntry.of(accepted, storageName, now)
        val target = File(directory, storageName)
        if (!writeAtomically(target, bytes)) return FontImportResult.Rejected("This font couldn't be saved to your device.")
        if (!writeIndex(current.withEntry(entry))) {
            target.delete()
            return FontImportResult.Rejected("This font couldn't be saved to your device.")
        }
        return FontImportResult.Imported(entry)
    }

    fun remove(identity: FontIdentity): Int {
        val current = index()
        val victims = current.entries.filter { it.familyId == identity.id }
        if (victims.isEmpty()) return 0
        val next = victims.fold(current) { acc, entry -> acc.without(entry.id) }
        if (!writeIndex(next)) return 0
        victims.forEach { fileFor(it).delete() }
        return victims.size
    }

    fun remove(id: String): Boolean {
        val current = index()
        val entry = current.find(id) ?: return false
        if (!writeIndex(current.without(id))) return false
        fileFor(entry).delete()
        return true
    }

    fun pruneOrphans(): Int {
        val known = index().entries.map { it.fileName }.toSet() + INDEX_FILE
        val orphans = directory.listFiles().orEmpty().filter { it.isFile && it.name !in known }
        orphans.forEach { it.delete() }
        return orphans.size
    }

    private fun writeIndex(next: ImportedFontIndex): Boolean =
        writeAtomically(indexFile, next.encode().toByteArray())

    private fun writeAtomically(target: File, bytes: ByteArray): Boolean = runCatching {
        directory.mkdirs()
        val temp = File(target.parentFile, "${target.name}.part")
        temp.writeBytes(bytes)
        if (target.exists() && !target.delete()) {
            temp.delete()
            return false
        }
        if (!temp.renameTo(target)) {
            temp.delete()
            return false
        }
        true
    }.getOrDefault(false)

    companion object { const val INDEX_FILE = "index.json" }
}
