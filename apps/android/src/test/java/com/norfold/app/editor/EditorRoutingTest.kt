package com.norfold.app.editor

import com.norfold.app.domain.Destination
import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.DocOverlapMode
import com.norfold.app.domain.DocumentArtifactKind
import com.norfold.app.domain.TableBlock
import com.norfold.app.editor.calculation.SpreadsheetWorkbookFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Certifies which host opens a stored document.
 *
 * After the cutover there are exactly three editor hosts and no switch inside any of them, so "which
 * editor am I in" is answered once, by [EditorRoute], before a host is constructed. This suite pins
 * that answer. The properties are small, but the thing they protect is not: routing a document to the
 * wrong host either shows a person the wrong product for their content, or — for a positioned
 * document opened in a host with no concept of position — silently drops the geometry on the next
 * save.
 *
 * The rule is asserted as a total function over [DocOverlapMode] rather than as three separate
 * examples, so a mode added later is a failure here rather than an untested path in production.
 */
class EditorRoutingTest {

    @Test
    fun persistedArtifactIdentityRoutesToExactlyItsOwningHost() {
        assertEquals(Destination.NoteEditor, EditorRoute.forArtifact(DocumentArtifactKind.Flow))
        assertEquals(Destination.DocumentEditor, EditorRoute.forArtifact(DocumentArtifactKind.Document))
        assertEquals(Destination.SpreadsheetEditor, EditorRoute.forArtifact(DocumentArtifactKind.Spreadsheet))
    }

    @Test
    fun legacyInferenceDistinguishesAFlowTableFromACalculationWorkbook() {
        assertEquals(
            DocumentArtifactKind.Flow,
            DocumentArtifactKind.inferredFrom(DocOverlapMode.Reflow, BlockDocument(listOf(TableBlock("plain-table")))),
        )
        assertEquals(
            DocumentArtifactKind.Spreadsheet,
            DocumentArtifactKind.inferredFrom(
                DocOverlapMode.Reflow,
                BlockDocument(listOf(SpreadsheetWorkbookFactory.createBlock("Workbook"))),
            ),
        )
    }

    @Test
    fun aReflowRowOpensTheFlowHost() {
        assertEquals(Destination.NoteEditor, EditorRoute.forLayoutMode(DocOverlapMode.Reflow))
    }

    @Test
    fun aBoundedRowOpensTheDocumentHost() {
        assertEquals(Destination.DocumentEditor, EditorRoute.forLayoutMode(DocOverlapMode.Bounded))
    }

    @Test
    fun aRetiredOverlapRowOpensTheDocumentHostRatherThanTheFlowHost() {
        // The Overlap *product* is retired; rows tagged Overlap are not. Such a row holds real
        // geometry, and the document host is the only host that can read it — and the only one that
        // runs the legacy conversion. Routing it to the flow host would open a positioned document in
        // a host that cannot represent position, which is how the placements would be lost.
        assertEquals(Destination.DocumentEditor, EditorRoute.forLayoutMode(DocOverlapMode.Overlap))
    }

    @Test
    fun everyStoredModeRoutesToAnEditorHostAndNoneRoutesToTheSpreadsheet() {
        // Exhaustive over the enum, so this keeps holding as the enum changes. The spreadsheet is
        // deliberately unreachable from a layout mode: it is entered from a table identity or the
        // workspace entry, never inferred from a stored tag.
        DocOverlapMode.entries.forEach { mode ->
            val destination = EditorRoute.forLayoutMode(mode)
            assertTrue("$mode must route to an editor host", EditorRoute.isEditorHost(destination))
            assertFalse("$mode must not route to the spreadsheet", destination == Destination.SpreadsheetEditor)
        }
    }

    @Test
    fun theAppHasExactlyThreeEditorHosts() {
        // Pinned against the enum rather than against a list restated here: a fourth editor
        // destination added to `Destination` without being routed would otherwise pass every test
        // above while being unreachable.
        assertEquals(3, EditorRoute.EDITOR_HOSTS.size)
        val editorNamed = Destination.entries.filter { it.name.endsWith("Editor") }
        assertEquals(editorNamed.toSet(), EditorRoute.EDITOR_HOSTS)
    }

    @Test
    fun nonEditorDestinationsAreNotMistakenForHosts() {
        listOf(Destination.NotesHome, Destination.DocumentsHome, Destination.Settings, Destination.Search)
            .forEach { assertFalse("$it is not an editor host", EditorRoute.isEditorHost(it)) }
    }
}
