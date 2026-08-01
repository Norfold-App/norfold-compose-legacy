package com.norfold.app.editor.style

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.PageGeometry
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.SceneObject
import com.norfold.app.domain.SceneObjectKind
import com.norfold.app.domain.ShapeBlock
import com.norfold.app.domain.style.ColorPaint
import com.norfold.app.domain.style.ColorRole
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.domain.style.GradientKind
import com.norfold.app.editor.document.DocumentHostRuntime
import com.norfold.app.editor.document.DocumentSceneController
import com.norfold.app.editor.flow.FlowEditController
import com.norfold.app.editor.flow.FlowHostRuntime
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.presentation.FormFactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for the real presentation-to-engine action-boundary seam. */
class ColorStudioGestureLifecycleTest {
    @Test fun `one spectrum drag with one hundred previews is one canonical undo step`() {
        val runtime = flowRuntime()
        val interactions = runtime.editorStyle.colorInteractions
        interactions.beginContinuousAdjustment()
        repeat(100) { runtime.editorStyle.colorStudio.previewColor(ColorValue(it, 20, 30)) }
        assertEquals(0, runtime.controller.undoDepth())
        assertTrue(interactions.finishContinuousAdjustment("Change color"))
        assertEquals(1, runtime.controller.undoDepth())
        assertTrue(runtime.editorStyle.colorStudio.isOpen)
    }

    @Test fun `two spectrum drags while Studio remains open are two undo steps`() {
        val runtime = flowRuntime()
        repeat(2) { gesture ->
            runtime.editorStyle.colorInteractions.beginContinuousAdjustment()
            runtime.editorStyle.colorStudio.previewColor(ColorValue(40 + gesture, 50, 60))
            assertTrue(runtime.editorStyle.colorInteractions.finishContinuousAdjustment("Change color"))
        }
        assertEquals(2, runtime.controller.undoDepth())
        assertTrue(runtime.editorStyle.colorStudio.isOpen)
    }

    @Test fun `cancelled spectrum drag restores exact paint and records no history`() {
        val runtime = flowRuntime(ColorValue(17, 31, 47, 191))
        val before = runtime.editorStyle.colorStudio.state().paint
        runtime.editorStyle.colorInteractions.beginContinuousAdjustment()
        runtime.editorStyle.colorStudio.previewColor(ColorValue(220, 1, 2, 99))
        assertTrue(runtime.editorStyle.colorInteractions.cancelContinuousAdjustment())
        assertEquals(before, runtime.editorStyle.colorStudio.state().paint)
        assertEquals(0, runtime.controller.undoDepth())
    }

    @Test fun `wheel hue and alpha gestures each have independent boundaries`() {
        val runtime = flowRuntime()
        runtime.editorStyle.colorInteractions.beginContinuousAdjustment()
        runtime.editorStyle.colorStudio.previewHsl(runtime.editorStyle.colorStudio.state().color().toHsl().copy(hue = 120f))
        assertTrue(runtime.editorStyle.colorInteractions.finishContinuousAdjustment("Change hue"))
        runtime.editorStyle.colorInteractions.beginContinuousAdjustment()
        runtime.editorStyle.colorStudio.previewAlpha(0.35f)
        assertTrue(runtime.editorStyle.colorInteractions.finishContinuousAdjustment("Change opacity"))
        assertEquals(2, runtime.controller.undoDepth())
    }

    @Test fun `gradient position opacity and angle gestures commit separately`() {
        val runtime = gradientRuntime()
        val base = runtime.controller.undoDepth()
        runtime.editorStyle.colorInteractions.beginContinuousAdjustment()
        runtime.editorStyle.colorStudio.moveActiveStop(0.25f)
        assertTrue(runtime.editorStyle.colorInteractions.finishContinuousAdjustment("Move gradient stop"))
        runtime.editorStyle.colorInteractions.beginContinuousAdjustment()
        runtime.editorStyle.colorStudio.previewAlpha(0.4f)
        assertTrue(runtime.editorStyle.colorInteractions.finishContinuousAdjustment("Change gradient stop opacity"))
        runtime.editorStyle.colorInteractions.beginContinuousAdjustment()
        runtime.editorStyle.colorStudio.setGradientAngle(135f)
        assertTrue(runtime.editorStyle.colorInteractions.finishContinuousAdjustment("Change gradient angle"))
        assertEquals(base + 3, runtime.controller.undoDepth())
    }

    @Test fun `cancelled direct gradient drag restores exact gradient`() {
        val runtime = gradientRuntime()
        val before = runtime.editorStyle.colorStudio.state().paint
        val depth = runtime.controller.undoDepth()
        runtime.editorStyle.colorInteractions.beginContinuousAdjustment()
        runtime.editorStyle.colorStudio.moveActiveStop(0.71f)
        assertTrue(runtime.editorStyle.colorInteractions.cancelContinuousAdjustment())
        assertEquals(before, runtime.editorStyle.colorStudio.state().paint)
        assertEquals(depth, runtime.controller.undoDepth())
    }

    @Test fun `swatches commit immediately current swatch is no op and two choices are two steps`() {
        val runtime = flowRuntime()
        val current = runtime.editorStyle.colorStudio.state().color()
        assertFalse(runtime.editorStyle.colorInteractions.oneShot("Choose color") { previewColor(current) })
        assertEquals(0, runtime.controller.undoDepth())
        assertTrue(runtime.editorStyle.colorInteractions.oneShot("Choose color") { previewColor(ColorValue(1, 2, 3)) })
        assertTrue(runtime.editorStyle.colorInteractions.oneShot("Choose color") { previewColor(ColorValue(4, 5, 6)) })
        assertEquals(2, runtime.controller.undoDepth())
        assertTrue(runtime.editorStyle.colorStudio.isOpen)
    }

    @Test fun `applying a color token is one canonical step while Studio stays open`() {
        val runtime = flowRuntime()
        val tokenId = runtime.editorStyle.createColorToken("Accent")!!
        val before = runtime.controller.undoDepth()
        assertEquals(1, runtime.editorStyle.applyColorToken(tokenId))
        assertEquals(before + 1, runtime.controller.undoDepth())
        assertTrue(runtime.editorStyle.colorStudio.isOpen)
    }

    @Test fun `gradient one shot commands each own one transaction`() {
        val runtime = gradientRuntime()
        var depth = runtime.controller.undoDepth()
        assertTrue(runtime.editorStyle.colorInteractions.oneShot("Reverse gradient") { reverseGradient() })
        assertEquals(++depth, runtime.controller.undoDepth())
        assertTrue(runtime.editorStyle.colorInteractions.oneShot("Change gradient kind") { setGradientKind(GradientKind.RADIAL) })
        assertEquals(++depth, runtime.controller.undoDepth())
        assertTrue(runtime.editorStyle.colorInteractions.oneShot("Add gradient stop") { addStopAt(0.5f) })
        assertEquals(++depth, runtime.controller.undoDepth())
        assertTrue(runtime.editorStyle.colorInteractions.oneShot("Remove gradient stop") { removeActiveStop() })
        assertEquals(++depth, runtime.controller.undoDepth())
    }

    @Test fun `numeric draft sequence commits once and cannot merge into next gesture`() {
        val runtime = flowRuntime(ColorValue(0, 20, 30))
        runtime.editorStyle.colorStudio.draft(ColorField.RED, "1")
        runtime.editorStyle.colorStudio.draft(ColorField.RED, "12")
        runtime.editorStyle.colorStudio.draft(ColorField.RED, "120")
        assertEquals(DraftOutcome.COMMITTED, runtime.editorStyle.colorStudio.commitDraft(ColorField.RED))
        assertEquals(1, runtime.controller.undoDepth())
        runtime.editorStyle.colorInteractions.beginContinuousAdjustment()
        runtime.editorStyle.colorStudio.previewColor(ColorValue(121, 20, 30))
        runtime.editorStyle.colorInteractions.finishContinuousAdjustment("Change color")
        assertEquals(2, runtime.controller.undoDepth())
    }

    @Test fun `invalid or cancelled numeric draft never mutates canonical color`() {
        val runtime = flowRuntime(ColorValue(10, 20, 30))
        val before = runtime.editorStyle.colorStudio.state().color()
        runtime.editorStyle.colorStudio.draft(ColorField.RED, "999")
        assertEquals(DraftOutcome.REJECTED, runtime.editorStyle.colorStudio.commitDraft(ColorField.RED))
        runtime.editorStyle.colorStudio.draft(ColorField.HEX, "#123")
        runtime.editorStyle.colorStudio.discardDraft(ColorField.HEX)
        assertEquals(before, runtime.editorStyle.colorStudio.state().color())
        assertEquals(0, runtime.controller.undoDepth())
    }

    @Test fun `Done does not recommit an already completed gesture`() {
        val runtime = flowRuntime()
        runtime.editorStyle.colorInteractions.beginContinuousAdjustment()
        runtime.editorStyle.colorStudio.previewColor(ColorValue(8, 9, 10))
        runtime.editorStyle.colorInteractions.finishContinuousAdjustment("Change color")
        assertFalse(runtime.editorStyle.colorInteractions.done())
        assertEquals(1, runtime.controller.undoDepth())
    }

    @Test fun `Cancel abandons only latest unfinished gesture and preserves earlier commit`() {
        val runtime = flowRuntime()
        runtime.editorStyle.colorInteractions.oneShot("Choose color") { previewColor(ColorValue(11, 12, 13)) }
        val committed = runtime.editorStyle.colorStudio.state().paint
        runtime.editorStyle.colorInteractions.beginContinuousAdjustment()
        runtime.editorStyle.colorStudio.previewColor(ColorValue(200, 201, 202))
        assertTrue(runtime.editorStyle.colorInteractions.cancel())
        assertEquals(committed, runtime.editorStyle.colorStudio.state().paint)
        assertEquals(1, runtime.controller.undoDepth())
    }

    @Test fun `form factor re presentation exact cancels and cannot double commit`() {
        val runtime = flowRuntime()
        runtime.editorStyle.colorStudio.setView(ColorStudioView.SPECTRUM)
        val before = runtime.editorStyle.colorStudio.state().paint
        runtime.editorStyle.colorInteractions.beginContinuousAdjustment()
        runtime.editorStyle.colorStudio.previewColor(ColorValue(80, 90, 100))
        runtime.onFormFactorChanged(FormFactor(1280, 800))
        assertEquals(before, runtime.editorStyle.colorStudio.state().paint)
        assertEquals(ColorStudioView.SPECTRUM, runtime.editorStyle.colorStudio.state().view)
        assertEquals(0, runtime.controller.undoDepth())
        assertFalse(runtime.editorStyle.colorInteractions.done())
        assertEquals(0, runtime.controller.undoDepth())
    }

    private fun flowRuntime(initial: ColorValue = ColorValue(20, 40, 60)): FlowHostRuntime {
        val paragraph = ParagraphBlock(id = "p", content = listOf(InlineText("Color")))
        val runtime = FlowHostRuntime(
            FlowEditController(EditTarget("flow-color-lifecycle"), BlockDocument(listOf(paragraph))),
            FormFactor(390, 840),
        )
        runtime.scope.selectBlocks(listOf(paragraph.id))
        assertTrue(runtime.editorStyle.openColor(ColorRole.TEXT))
        if (initial != ColorValue.BLACK) {
            // Seed the engine without adding History; openColor's document default is black.
            runtime.editorStyle.colorStudio.open(
                runtime.editorStyle.colorStudio.state().target!!,
                ColorPaint.Flat(initial),
            )
        }
        return runtime
    }

    private fun gradientRuntime(): DocumentHostRuntime {
        val shape = ShapeBlock(id = "shape", text = "Gradient")
        val obj = SceneObject(id = "object", kind = SceneObjectKind.Shape, content = shape)
        val page = DocumentPageBlock(id = "page", geometry = PageGeometry.a4(), objects = listOf(obj))
        val runtime = DocumentHostRuntime(
            DocumentSceneController(EditTarget("document-color-lifecycle"), BlockDocument(listOf(page))),
            FormFactor(1280, 800),
        )
        runtime.selectObjects(listOf(obj.id))
        assertTrue(runtime.editorStyle.openColor(ColorRole.FILL))
        assertTrue(runtime.editorStyle.colorInteractions.oneShot("Toggle gradient") { setGradientEnabled(true) })
        return runtime
    }
}
