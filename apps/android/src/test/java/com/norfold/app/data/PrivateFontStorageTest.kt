package com.norfold.app.data

import com.norfold.app.domain.style.FontFileValidator
import com.norfold.app.domain.style.FontValidation
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class PrivateFontStorageTest {
    @Test fun `accepted font is atomically copied indexed deduplicated and removable`() {
        val source = File("src/main/assets/fonts/faces").listFiles().orEmpty().firstOrNull { it.extension == "ttf" }
        assumeTrue(source?.isFile == true)
        val bytes = source!!.readBytes()
        val accepted = FontFileValidator.validate(bytes, source.name) as FontValidation.Accepted
        val root = Files.createTempDirectory("norfold-font-store").toFile()
        try {
            val store = PrivateFontStorage(root)
            val first = store.install(bytes, accepted, 10L)
            assertTrue(first is FontImportResult.Imported)
            val entry = first.entryOrNull!!
            assertTrue(store.fileFor(entry).isFile)
            assertEquals(bytes.toList(), store.fileFor(entry).readBytes().toList())
            assertEquals(1, store.records().size)
            assertFalse(root.listFiles().orEmpty().any { it.extension == "part" })

            val second = store.install(bytes.copyOf(), accepted, 20L)
            assertTrue(second is FontImportResult.AlreadyImported)
            assertEquals(1, store.index().entries.size)

            assertEquals(1, store.remove(entry.identity))
            assertTrue(store.records().isEmpty())
            assertFalse(store.fileFor(entry).exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
