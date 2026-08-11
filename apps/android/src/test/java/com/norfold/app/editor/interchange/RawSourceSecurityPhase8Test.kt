package com.norfold.app.editor.interchange

import com.norfold.app.domain.*
import com.norfold.app.editor.flow.FlowEditController
import com.norfold.app.editor.history.EditTarget
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RawSourceSecurityPhase8Test {
    private val document = BlockDocument(listOf(
        ParagraphBlock("p", listOf(BoldInline(listOf(InlineText("Hello"))))),
        TableBlock("t", listOf(TableCell(listOf(InlineText("H")))), listOf(listOf(TableCell(listOf(InlineText("V")))))),
        ImageBlock("i", "norfold-import://image", "Photo"),
        ChartBlock("c", chartDocument = "{\"v\":1}"),
    ))

    @Test fun rawSourceIsDeterministicProductFormatAndRoundTrips() {
        val first = RawSourceSerializer.serialize(document)
        val second = RawSourceSerializer.serialize(document)
        assertEquals(first, second)
        assertTrue(first.startsWith("norfold-source 1\n"))
        assertFalse(first.contains("schemaVersion"))
        val parsed = RawSourceParser.parse(first) as RawSourceParseResult.Success
        assertEquals(document.normalized(), RawSourceSerializer.toArtifact(parsed.ast).normalized())
    }

    @Test fun typingAndCancelNeverMutateLiveArtifact() {
        val controller = FlowEditController(EditTarget("note:raw"), document)
        val draft = RawSourceEditor.edit(RawSourceEditor.open(controller.document()), "norfold-source 1\n{")
        assertFalse(draft.canApply)
        assertEquals(document.normalized(), controller.document())
        assertTrue(controller.recoverySnapshots().isEmpty())
    }

    @Test fun rawApplySnapshotsAndUsesOneHistoryTransaction() {
        val controller = FlowEditController(EditTarget("note:raw"), document)
        val opened = RawSourceEditor.open(document)
        val changed = RawSourceEditor.edit(opened, opened.source.replace("Hello", "Changed"))
        assertTrue(InterchangeImportService.applyRawSource(controller, changed))
        assertEquals(1, controller.undoDepth())
        assertEquals(com.norfold.app.editor.recovery.RecoveryReason.RAW_SOURCE, controller.recoverySnapshots().single().reason)
        assertTrue(controller.undo())
        assertEquals(document.normalized(), controller.document())
    }

    @Test fun unknownArtifactBlocksPreventDestructiveRoundTrip() {
        val draft = RawSourceEditor.open(BlockDocument(listOf(UnknownBlock("future", "{\"future\":true}"))))
        assertFalse(draft.canApply)
        assertTrue(draft.applyDisabledReason!!.contains("Unknown block"))
    }

    @Test fun malformedVersionInputNestingAndTokenLimitsReturnDiagnostics() {
        assertTrue(RawSourceParser.parse("bad") is RawSourceParseResult.Failure)
        assertTrue(RawSourceParser.parse("norfold-source 1\n{\"version\":2,\"blocks\":[]}") is RawSourceParseResult.Failure)
        val small = ImportSecurityLimits(maxRawSourceBytes = 20)
        assertTrue(RawSourceParser.parse(RawSourceSerializer.serialize(document), small) is RawSourceParseResult.Failure)
        val nested = "norfold-source 1\n" + "[".repeat(66) + "0" + "]".repeat(66)
        assertTrue(RawSourceParser.parse(nested) is RawSourceParseResult.Failure)
        val tokens = ImportSecurityLimits(maxRawTokens = 3)
        assertTrue(RawSourceParser.parse("norfold-source 1\n{\"version\":1,\"blocks\":[]}", tokens) is RawSourceParseResult.Failure)
    }

    @Test fun zipTraversalDuplicateEntryAndEntryLimitsAreRejected() {
        val limits = ImportSecurityLimits(maxZipEntries = 1, maxEntryBytes = 4, maxExpandedBytes = 8)
        assertFails { BoundedPackageReader.read(zip(listOf("../evil" to "x")), ImportSecurityLimits()) }
        assertFails { BoundedPackageReader.read(zip(listOf("a" to "1", "b" to "2")), limits.copy(maxEntryBytes = 10)) }
        assertFails { BoundedPackageReader.read(zip(listOf("a" to "12345")), limits.copy(maxZipEntries = 2)) }
        assertFails { BoundedPackageReader.read(zip(listOf("a" to "1234", "b" to "1234", "c" to "1")), limits.copy(maxZipEntries = 4, maxEntryBytes = 10)) }
    }

    @Test fun dtdXxeAndDeepXmlAreRejected() {
        assertFails { SecureXml.parse("<!DOCTYPE x [<!ENTITY y SYSTEM 'file:///etc/passwd'>]><x>&y;</x>".toByteArray(), ImportSecurityLimits()) }
        val deep = "<x>".repeat(70) + "v" + "</x>".repeat(70)
        assertFails { SecureXml.parse(deep.toByteArray(), ImportSecurityLimits(maxXmlDepth = 16)) }
    }

    @Test fun commandFeatureGateShipsOnAndOnlyRegistersWhenTheRuntimeIsGivenIt() {
        assertTrue(com.norfold.app.editor.flags.EditorFeatureFlag.IMPORT_EXPORT_RAW.defaultEnabled)
        assertTrue(com.norfold.app.editor.flags.EditorFeatureFlags.productionDefaults().isEnabled(com.norfold.app.editor.flags.EditorFeatureFlag.IMPORT_EXPORT_RAW))
        val controller = FlowEditController(EditTarget("note:commands"), document)
        val off = com.norfold.app.editor.flow.FlowHostRuntime(controller, com.norfold.app.editor.presentation.FormFactor(390, 844))
        assertNull(off.registry.get(InterchangeCommandIds.RAW_OPEN))
        val on = com.norfold.app.editor.flow.FlowHostRuntime(FlowEditController(EditTarget("note:on"), document), com.norfold.app.editor.presentation.FormFactor(390, 844), importExportRawEnabled = true)
        assertNotNull(on.registry.get(InterchangeCommandIds.RAW_OPEN))
        assertNotNull(on.registry.get(InterchangeCommandIds.EXPORT_DOCX))
    }

    private fun zip(entries: List<Pair<String, String>>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> entries.forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() } }
        return output.toByteArray()
    }

    private fun assertFails(block: () -> Unit) { try { block(); fail("Expected failure") } catch (_: ImportSecurityException) { } }
}
