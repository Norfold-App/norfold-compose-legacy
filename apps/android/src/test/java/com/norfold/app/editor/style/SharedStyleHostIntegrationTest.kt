package com.norfold.app.editor.style

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BlockDocumentJson
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.PageGeometry
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.SceneGeometry
import com.norfold.app.domain.SceneObject
import com.norfold.app.domain.SceneObjectKind
import com.norfold.app.domain.ShapeBlock
import com.norfold.app.domain.findById
import com.norfold.app.domain.style.ColorRole
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.domain.style.FontCategory
import com.norfold.app.domain.style.FontFamilyRecord
import com.norfold.app.domain.style.FontIdentity
import com.norfold.app.domain.style.FontLicense
import com.norfold.app.domain.style.FontPortability
import com.norfold.app.domain.style.FontRegistry
import com.norfold.app.domain.style.FontSource
import com.norfold.app.domain.style.StyleProperty
import com.norfold.app.domain.style.StyleCategory
import com.norfold.app.domain.style.Stylesheet
import com.norfold.app.editor.command.CommandResult
import com.norfold.app.editor.command.CommandSurface
import com.norfold.app.editor.document.DocumentHostRuntime
import com.norfold.app.editor.document.DocumentSceneController
import com.norfold.app.editor.flow.FlowEditController
import com.norfold.app.editor.flow.FlowHostRuntime
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.presentation.FormFactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedStyleHostIntegrationTest {
    private val font = FontFamilyRecord(
        id = "integration-font",
        name = "Integration Font",
        source = FontSource.BUILT_IN,
        category = FontCategory.SANS,
        license = FontLicense.OFL,
        portability = FontPortability.BUILT_IN,
    )
    private val registry = FontRegistry(listOf(font))

    @Test
    fun `Flow applies shared color style and font through canonical history`() {
        val paragraph = ParagraphBlock(id = "flow-p", content = listOf(InlineText("Flow text")))
        val runtime = FlowHostRuntime(
            FlowEditController(EditTarget("flow"), BlockDocument(listOf(paragraph))),
            FormFactor(390, 840),
            fontRegistry = registry,
        )
        runtime.scope.selectBlocks(listOf(paragraph.id))

        assertTrue(runtime.editorStyle.openColor(ColorRole.TEXT))
        runtime.editorStyle.colorStudio.previewColor(ColorValue(12, 34, 56))
        assertTrue(runtime.editorStyle.colorStudio.commit())
        assertEquals("#0C2238", runtime.styleController.propertyState(paragraph.id, StyleProperty.TEXT_COLOR)?.value)

        assertTrue(runtime.editorStyle.openFont())
        assertEquals(1, runtime.editorStyle.applyFont(font.identity))
        assertEquals(font.identity.encode(), runtime.styleController.resolvedFont(paragraph.id)?.encode())
        assertTrue(runtime.controller.undo())
        assertTrue(runtime.styleController.resolvedFont(paragraph.id)?.encode() != font.identity.encode())
        assertTrue(runtime.controller.redo())
        assertEquals(font.identity.encode(), runtime.styleController.resolvedFont(paragraph.id)?.encode())
        assertEquals(1, runtime.editorStyle.applyNamedStyle(Stylesheet.TITLE))
        assertEquals(Stylesheet.TITLE, runtime.styleController.sharedStyle(listOf(paragraph.id)).styleId)
    }

    @Test
    fun `Document applies the same style engine without changing scene geometry`() {
        val paragraph = ParagraphBlock(id = "doc-p", content = listOf(InlineText("Document text")))
        val geometry = SceneGeometry(x = 42f, y = 84f, width = 260f, height = 120f, rotation = 17f)
        val object1 = SceneObject(id = "object", kind = SceneObjectKind.TextFrame, geometry = geometry, content = paragraph)
        val page = DocumentPageBlock(id = "page", geometry = PageGeometry.a4(), objects = listOf(object1))
        val controller = DocumentSceneController(EditTarget("document"), BlockDocument(listOf(page)))
        val runtime = DocumentHostRuntime(controller, FormFactor(1280, 800), fontRegistry = registry)
        runtime.selectObjects(listOf(object1.id))

        assertTrue(runtime.editorStyle.openColor(ColorRole.TEXT))
        runtime.editorStyle.colorStudio.previewColor(ColorValue(90, 80, 70))
        assertTrue(runtime.editorStyle.colorStudio.commit())
        assertEquals("#5A5046", runtime.styleController.propertyState(paragraph.id, StyleProperty.TEXT_COLOR)?.value)
        assertEquals(geometry, controller.findObject(object1.id)?.second?.geometry)

        assertTrue(runtime.editorStyle.openFont())
        assertEquals(1, runtime.editorStyle.applyFont(font.identity))
        assertEquals(font.identity.encode(), runtime.styleController.resolvedFont(paragraph.id)?.encode())
        assertTrue(controller.undo())
        assertTrue(runtime.styleController.resolvedFont(paragraph.id)?.encode() != font.identity.encode())
        assertEquals(geometry, controller.findObject(object1.id)?.second?.geometry)
    }

    @Test
    fun `style font and token payload reconstruct through the production codec`() {
        val paragraph = ParagraphBlock(id = "roundtrip", content = listOf(InlineText("Saved")))
        val runtime = FlowHostRuntime(
            FlowEditController(EditTarget("saved"), BlockDocument(listOf(paragraph))),
            FormFactor(390, 840),
            fontRegistry = registry,
        )
        runtime.scope.selectBlocks(listOf(paragraph.id))
        runtime.editorStyle.applyFont(font.identity)
        runtime.styleController.createToken(
            com.norfold.app.domain.style.DocumentToken.color("Brand", ColorValue(10, 20, 30), id = "token-brand"),
        )

        val encoded = BlockDocumentJson.encodeDocumentPayload(runtime.controller.document())
        val reopened = BlockDocumentJson.decodeDocumentPayload(encoded)
        val reopenedController = StyleController(
            mutate = { _, _ -> false },
            documentProvider = { reopened },
        )
        assertEquals(font.identity.encode(), reopenedController.resolvedFont(paragraph.id)?.encode())
        assertNotNull(reopenedController.stylesheet().token("token-brand"))
        assertNotNull(reopened.findById(paragraph.id))
    }

    @Test
    fun `shared tool state survives host form factor adaptation`() {
        val paragraph = ParagraphBlock(id = "adaptive", content = listOf(InlineText("Selected preview")))
        val runtime = FlowHostRuntime(
            FlowEditController(EditTarget("adaptive"), BlockDocument(listOf(paragraph))),
            FormFactor(390, 840),
            fontRegistry = registry,
        )
        runtime.scope.selectBlocks(listOf(paragraph.id))
        val result = runtime.invoke(EditorStyleCommandIds.FONT_BROWSER, CommandSurface.DOCK)
        assertTrue(result is CommandResult.Success)
        runtime.editorStyle.fontBrowser.setQuery("integration")
        runtime.editorStyle.fontBrowser.select(font.identity)
        runtime.onFormFactorChanged(FormFactor(1280, 800))
        assertEquals("integration", runtime.editorStyle.fontBrowser.state().query)
        assertEquals(font.identity, runtime.editorStyle.fontBrowser.state().selected)
        assertTrue(runtime.editorStyle.fontBrowser.state().usingSelectedText)
    }

    @Test
    fun `Document named object style resolves through the shared style engine`() {
        val shape = ShapeBlock(id = "shape", text = "Styled")
        val object1 = SceneObject(id = "shape-object", kind = SceneObjectKind.Shape, content = shape)
        val page = DocumentPageBlock(id = "shape-page", geometry = PageGeometry.a4(), objects = listOf(object1))
        val controller = DocumentSceneController(EditTarget("shape-document"), BlockDocument(listOf(page)))
        val runtime = DocumentHostRuntime(controller, FormFactor(1280, 800), fontRegistry = registry)
        runtime.selectObjects(listOf(object1.id))
        assertEquals(1, runtime.styleController.applyShapeFill(listOf(shape.id), com.norfold.app.domain.style.ColorPaint.Flat(ColorValue(25, 50, 75))))
        val styleId = runtime.styleController.createStyleFromBlock(shape.id, "Brand card")
        assertNotNull(styleId)
        assertEquals("#19324B", runtime.styleController.resolved(shape.id, StyleCategory.OBJECT)[StyleProperty.FILL])
        assertTrue(controller.undo())
        assertTrue(runtime.styleController.sharedStyle(listOf(shape.id)).styleId != styleId)
    }
}
