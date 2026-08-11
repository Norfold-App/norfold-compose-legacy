package com.norfold.app.editor.interchange

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.withInterchangeData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class InterchangeRuntimeHydrationTest {
    @Test
    fun `persisted fidelity is available immediately after reopening`() {
        val report = FidelityReport(
            format = ImportFormat.DOCX,
            mode = ImportMode.COMPATIBILITY,
            intent = ImportIntent.PRESERVE_APPEARANCE,
            regions = listOf(
                FidelityRegion("title", "title", "word/document.xml", FidelityState.NATIVE, "Native heading"),
            ),
        )
        val metadata = InterchangeArtifactMetadata(
            source = SourceArtifactMetadata(
                format = ImportFormat.DOCX,
                originalName = "sample.docx",
                mediaType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                byteSize = 128,
                sha256 = "9a7ce20508c205c2b441f0bed8b2dc00d6027e0710c840403c54f45f15fb27d8",
            ),
            compatibility = CompatibilityMetadata(),
            fidelity = report,
        )
        val document = BlockDocument(
            listOf(ParagraphBlock("title", listOf(InlineText("Sample")))),
        ).withInterchangeData(InterchangeMetadataCodec.encode(metadata))

        val runtime = InterchangeRuntime { document }

        assertNotNull(runtime.state.fidelity)
        assertEquals(report, runtime.state.fidelity)
    }

    @Test
    fun `malformed persisted metadata does not block editor startup`() {
        val document = BlockDocument().withInterchangeData("not-json")

        val runtime = InterchangeRuntime { document }

        assertEquals(null, runtime.state.fidelity)
    }
}
