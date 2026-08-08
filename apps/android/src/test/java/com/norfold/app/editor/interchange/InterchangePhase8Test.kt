package com.norfold.app.editor.interchange

import com.norfold.app.domain.*
import com.norfold.app.editor.calculation.CellInput
import com.norfold.app.editor.calculation.CalculationWorkbookJson
import com.norfold.app.editor.flow.FlowEditController
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.relationship.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class InterchangePhase8Test {
    @Test fun formatDetectionUsesContentNotExtension() {
        assertEquals(ImportFormat.DOCX, ImportPreflightService.detect(docx()))
        assertEquals(ImportFormat.XLSX, ImportPreflightService.detect(xlsx()))
        assertEquals(ImportFormat.PDF, ImportPreflightService.detect(pdf(2)))
        assertEquals(ImportFormat.UNKNOWN, ImportPreflightService.detect("not a file".toByteArray()))
    }

    @Test fun docxImportsParagraphHeadingFormattingListsTableAndLink() {
        val request = request("wrong.bin", docx(), ImportFormat.DOCX)
        val result = DocxImporter.import(request) as ImportResult.Success
        val document = result.artifact.document
        val blocks = mutableListOf<DocumentBlock>().also { found -> document.walk(found::add) }
        assertTrue(blocks.any { it is HeadingBlock })
        assertTrue(blocks.any { it is ParagraphBlock && it.content.any(::containsBold) })
        assertTrue(blocks.any { it is BulletListBlock })
        assertTrue(blocks.any { it is NumberedListBlock })
        assertTrue(blocks.any { it is TableBlock })
        assertTrue(document.plainText().contains("Norfold"))
    }

    @Test fun docxStableRegionIdentityAndModesDiffer() {
        val bytes = docx()
        val compatibility = DocxImporter.import(request("a.docx", bytes, ImportFormat.DOCX, ImportMode.COMPATIBILITY)) as ImportResult.Success
        val native = DocxImporter.import(request("a.docx", bytes, ImportFormat.DOCX, ImportMode.NATIVE)) as ImportResult.Success
        assertEquals(compatibility.artifact.fidelity.regions.map { it.id }, DocxImporter.import(request("a.docx", bytes, ImportFormat.DOCX, ImportMode.COMPATIBILITY)).let { (it as ImportResult.Success).artifact.fidelity.regions.map { r -> r.id } })
        assertTrue(compatibility.artifact.compatibility.entries.isNotEmpty())
        assertTrue(native.artifact.compatibility.entries.isEmpty())
    }

    @Test fun pdfIsFixedOrderedAndDimensioned() {
        val result = PdfImporter.import(request("x.pdf", pdf(3), ImportFormat.PDF)) as ImportResult.Success
        val pages = result.artifact.document.blocks.filterIsInstance<DocumentPageBlock>()
        assertEquals(3, pages.size)
        assertEquals(612f, pages.first().geometry.width)
        assertTrue(result.artifact.fidelity.regions.all { it.state == FidelityState.FIXED && !it.editable })
        assertEquals("3", pages.last().extensions["norfold.pdf.page"])
    }

    @Test fun protectedAndActivePdfAreExplicit() {
        val protected = pdf(1, "/Encrypt 1 0 R")
        assertFalse(ImportPreflightService.inspect(request("x.pdf", protected, ImportFormat.PDF)).canApply)
        val active = ImportPreflightService.inspect(request("x.pdf", pdf(1, "/JavaScript"), ImportFormat.PDF))
        assertTrue(active.source.activeContent)
        assertTrue(active.warnings.any { it.code == "pdf_active_content" })
    }

    @Test fun xlsxUsesSharedWorkbookAndPreservesUnsupportedFormula() {
        val result = XlsxImporter.import(request("book.xlsx", xlsx(), ImportFormat.XLSX)) as ImportResult.Success
        val workbook = result.artifact.workbook!!
        assertEquals(listOf("Alpha", "Beta"), workbook.sheets.map { it.name })
        assertTrue(workbook.sheets.first().cells.any { it.input == CellInput.Number("42") })
        assertTrue(workbook.sheets.first().cells.any { it.input == CellInput.BooleanValue(true) })
        assertTrue(workbook.sheets.first().cells.any { it.input is CellInput.Formula })
        val table = result.artifact.document.blocks.filterIsInstance<TableBlock>().single()
        assertEquals(workbook, CalculationWorkbookJson.decode(table.calculationPayload!!))
        assertTrue(result.artifact.fidelity.issues.any { it.code.startsWith("xlsx_formula_") })
    }

    @Test fun preflightDoesNotMutateAndApplyIsOneUndoableTransaction() {
        val original = BlockDocument(listOf(ParagraphBlock("old", listOf(InlineText("old")))))
        val controller = FlowEditController(EditTarget("note:1"), original)
        val request = request("a.docx", docx(), ImportFormat.DOCX)
        InterchangeImportService.preflight(request)
        assertEquals(original.normalized(), controller.document())
        val prepared = InterchangeImportService.prepare(request)
        assertTrue(InterchangeImportService.apply(controller, request, prepared))
        assertEquals(1, controller.undoDepth())
        assertEquals(1, controller.recoverySnapshots().size)
        assertEquals(com.norfold.app.editor.recovery.RecoveryReason.IMPORT, controller.recoverySnapshots().single().reason)
        assertTrue(controller.undo())
        assertEquals(original.normalized(), controller.document())
        assertTrue(controller.redo())
        assertNotEquals(original.normalized(), controller.document())
    }

    @Test fun failedPreparedImportIsExactNoOp() {
        val original = BlockDocument(listOf(ParagraphBlock("old", listOf(InlineText("old")))))
        val controller = FlowEditController(EditTarget("note:1"), original)
        val request = request("bad.docx", byteArrayOf(1, 2, 3), ImportFormat.DOCX)
        assertFalse(InterchangeImportService.apply(controller, request, InterchangeImportService.prepare(request)))
        assertEquals(original.normalized(), controller.document())
        assertTrue(controller.recoverySnapshots().isEmpty())
    }

    @Test fun docxExportHasTablesMediaCommentsSafeXmlAndNoLocalUri() {
        val image = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val document = BlockDocument(listOf(
            ParagraphBlock("p", listOf(BoldInline(listOf(InlineText("<&"))))),
            TableBlock("t", listOf(TableCell(listOf(InlineText("Head")))), listOf(listOf(TableCell(listOf(InlineText("Body")))))),
            ImageBlock("i", "file:///private/path.png", "Picture"),
        ))
        val output = DocxExporter.exportDetailed("<&", document) { if (it.startsWith("file:")) image else null }
        val entries = unzip(output.bytes)
        assertTrue("word/document.xml" in entries)
        assertTrue(entries.keys.any { it.startsWith("word/media/") })
        val xml = entries.getValue("word/document.xml").toString(Charsets.UTF_8)
        assertTrue("<w:tbl>" in xml)
        assertTrue("&lt;&amp;" in xml)
        assertFalse("file:///private" in entries.values.joinToString { it.toString(Charsets.ISO_8859_1) })
        assertEquals(1, output.packagedImages)
        assertTrue(DocxImporter.import(request("roundtrip.docx", output.bytes, ImportFormat.DOCX)) is ImportResult.Success)
    }

    @Test fun docxExportUsesCanonicalDiscussionPackageAndAnchor() {
        val threadId = ThreadId("thread-1")
        val state = Phase7ArtifactState(threads = listOf(DiscussionThread(
            id = threadId,
            anchor = CommentAnchor.Text("artifact:1", "p", 0, 5, "Hello"),
            comments = listOf(Comment(CommentId("comment-1"), threadId, CommentAuthorRef("u", "Amina"), 1L, body = "Review this")),
        )))
        val document = BlockDocument(listOf(ParagraphBlock("p", listOf(InlineText("Hello world"))))).withRelationshipData(Phase7ArtifactCodec.encode(state))
        val output = DocxExporter.exportDetailed("Comments", document)
        val entries = unzip(output.bytes)
        assertTrue("word/comments.xml" in entries)
        assertTrue(entries.getValue("word/document.xml").toString(Charsets.UTF_8).contains("commentRangeStart"))
        assertTrue(entries.getValue("word/comments.xml").toString(Charsets.UTF_8).contains("Review this"))
        assertEquals(1, output.packagedComments)
    }

    @Test fun allFidelityStatesFilterAndLifecycleAreTextual() {
        val regions = FidelityState.entries.mapIndexed { index, state -> FidelityRegion("r$index", "b$index", "p$index", state, state.name, "reason") }
        val report = FidelityReport(ImportFormat.DOCX, ImportMode.COMPATIBILITY, ImportIntent.EDIT_AS_NORFOLD, regions)
        assertEquals(FidelityState.entries.size, report.filtered(emptySet()).size)
        assertEquals(1, report.filtered(setOf(FidelityState.UNSUPPORTED)).size)
        assertEquals(FidelityState.APPROXIMATED, report.afterNativeEdit("r2").regions[2].state)
    }

    @Test fun canonicalEditPersistsFidelityLifecycleInSameUndoTransaction() {
        val source = SourceArtifactMetadata(ImportFormat.DOCX, "source.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 12, "abc")
        val report = FidelityReport(
            ImportFormat.DOCX,
            ImportMode.COMPATIBILITY,
            ImportIntent.EDIT_AS_NORFOLD,
            regions = listOf(FidelityRegion("region", "p", "word/document.xml/p/0", FidelityState.HYBRID, "Paragraph")),
        )
        val original = BlockDocument(listOf(ParagraphBlock("p", listOf(InlineText("Before")))))
            .withInterchangeData(InterchangeMetadataCodec.encode(InterchangeArtifactMetadata(source = source, compatibility = CompatibilityMetadata(), fidelity = report)))
        val controller = FlowEditController(EditTarget("note:fidelity"), original)

        assertTrue(controller.mutateDocument("Edit paragraph") { document ->
            document.copy(blocks = document.blocks.updateBlock("p") { ParagraphBlock("p", listOf(InlineText("After"))) })
        })

        val metadata = InterchangeMetadataCodec.decode(controller.document().interchangeDataBlock()!!.payload)
        assertEquals(FidelityState.APPROXIMATED, metadata.fidelity.regions.single().state)
        assertEquals(1, controller.undoDepth())
        assertTrue(controller.undo())
        assertEquals(original.normalized(), controller.document())
    }

    private fun request(name: String, bytes: ByteArray, ignored: ImportFormat, mode: ImportMode = ImportMode.COMPATIBILITY) =
        ImportRequest(name, bytes, ImportIntent.EDIT_AS_NORFOLD, mode, "artifact:1")

    private fun containsBold(node: InlineNode): Boolean = when (node) {
        is BoldInline -> true
        is ItalicInline -> node.children.any(::containsBold)
        is UnderlineInline -> node.children.any(::containsBold)
        is StrikethroughInline -> node.children.any(::containsBold)
        is HighlightInline -> node.children.any(::containsBold)
        is FontFamilyInline -> node.children.any(::containsBold)
        is FontSizeInline -> node.children.any(::containsBold)
        is LinkInline -> node.children.any(::containsBold)
        else -> false
    }

    private fun zip(entries: Map<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> entries.forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value.toByteArray()); zip.closeEntry() } }
        return output.toByteArray()
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(bytes.inputStream()).use { zip -> while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes()) } }
    }

    private fun docx(): ByteArray = zip(mapOf(
        "[Content_Types].xml" to "<Types/>",
        "word/styles.xml" to "<w:styles xmlns:w=\"$W\"/>",
        "word/numbering.xml" to "<w:numbering xmlns:w=\"$W\"><w:abstractNum w:abstractNumId=\"1\"><w:lvl><w:numFmt w:val=\"bullet\"/></w:lvl></w:abstractNum><w:abstractNum w:abstractNumId=\"2\"><w:lvl><w:numFmt w:val=\"decimal\"/></w:lvl></w:abstractNum><w:num w:numId=\"1\"><w:abstractNumId w:val=\"1\"/></w:num><w:num w:numId=\"2\"><w:abstractNumId w:val=\"2\"/></w:num></w:numbering>",
        "word/_rels/document.xml.rels" to "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rLink\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" Target=\"https://example.invalid\" TargetMode=\"External\"/></Relationships>",
        "word/document.xml" to """<w:document xmlns:w="$W" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><w:body><w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Norfold</w:t></w:r></w:p><w:p><w:r><w:rPr><w:b/><w:i/><w:u w:val="single"/></w:rPr><w:t>Rich</w:t></w:r><w:hyperlink r:id="rLink"><w:r><w:t>Link</w:t></w:r></w:hyperlink></w:p><w:p><w:pPr><w:numPr><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>Bullet</w:t></w:r></w:p><w:p><w:pPr><w:numPr><w:numId w:val="2"/></w:numPr></w:pPr><w:r><w:t>Number</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>Cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl><w:sectPr><w:pgSz w:w="12240" w:h="15840"/></w:sectPr></w:body></w:document>""",
    ))

    private fun xlsx(): ByteArray = zip(mapOf(
        "[Content_Types].xml" to "<Types/>",
        "xl/workbook.xml" to "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Alpha\" sheetId=\"1\" r:id=\"r1\"/><sheet name=\"Beta\" sheetId=\"2\" r:id=\"r2\"/></sheets></workbook>",
        "xl/_rels/workbook.xml.rels" to "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"r1\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"r2\" Target=\"worksheets/sheet2.xml\"/></Relationships>",
        "xl/worksheets/sheet1.xml" to "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData><row><c r=\"A1\"><v>42</v></c><c r=\"B1\" t=\"b\"><v>1</v></c><c r=\"C1\"><f>SUM(A1:A2)</f><v>42</v></c><c r=\"D1\"><f>[1]Sheet!A1</f><v>7</v></c></row></sheetData></worksheet>",
        "xl/worksheets/sheet2.xml" to "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData><row><c r=\"A1\" t=\"inlineStr\"><is><t>Beta</t></is></c></row></sheetData></worksheet>",
    ))

    private fun pdf(pages: Int, extra: String = ""): ByteArray = buildString {
        append("%PDF-1.7\n")
        repeat(pages) { append("${it + 1} 0 obj << /Type /Page /MediaBox [0 0 612 792] >> endobj\n") }
        append(extra).append("\n%%EOF")
    }.toByteArray(Charsets.ISO_8859_1)

    companion object { private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main" }
}
