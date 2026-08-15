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
        // Named rather than enumerated: File.listFiles() returns readdir order, so "the first .ttf" is a
        // different font on different machines and the fixture is not a stable contract.
        val source = File("src/main/assets/fonts/faces/carlito-regular.ttf")
        assumeTrue(source.isFile)
        val bytes = source.readBytes()
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
