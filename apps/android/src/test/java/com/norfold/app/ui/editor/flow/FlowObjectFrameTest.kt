package com.norfold.app.ui.editor.flow

import com.norfold.app.domain.ChartBlock
import com.norfold.app.domain.CodeBlock
import com.norfold.app.domain.DividerBlock
import com.norfold.app.domain.EmbedBlock
import com.norfold.app.domain.FileBlock
import com.norfold.app.domain.ImageBlock
import com.norfold.app.domain.MathBlock
import com.norfold.app.domain.MermaidBlock
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.ShapeBlock
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.deriveFlowObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of the object frame a JVM test can actually reach.
 *
 * There is no Compose test harness in the unit-test source set — no `createComposeRule` and no Robolectric
 * runner anywhere in the tree — so the composable itself, its border widths and its derived edge colour are
 * only observable on a device. What is testable is the two pure decisions the frame delegates to: which radius
 * a kind gets, and what a selected kind is called out loud. Both are exactly the kind of thing that rots
 * silently when a block kind is added, which is why they are pinned here rather than left to capture.
 */
class FlowObjectFrameTest {

    // ── Radius policy ────────────────────────────────────────────────────────────────────────────────

    /**
     * The whole point of the frame: one radius per *reason*, not one per call site.
     *
     * Before this existed the object paths used five values — 10 dp for a code block, 12 dp for HTML, an
     * equation, a diagram and an embed, 14 dp for an image and a table, 16 dp for a chart, and no frame at all
     * for a file card. This asserts the collapse actually happened, so re-introducing a third radius fails
     * here instead of being noticed by eye three screens later.
     */
    @Test
    fun `every framed kind resolves to one of two radii`() {
        val framed = listOf(
            ImageBlock(), ChartBlock(), ShapeBlock(), TableBlock(),
            CodeBlock(), MathBlock(), MermaidBlock(), EmbedBlock(), FileBlock(),
        )

        val radii = framed.map { FlowFrameMetrics.radiusOf(it) }.toSet()

        assertEquals(setOf(FlowFrameMetrics.MEDIA_RADIUS, FlowFrameMetrics.CARD_RADIUS), radii)
    }

    /**
     * Full-bleed content takes the tighter radius, and everything else is a card.
     *
     * A chart is the interesting case and the reason this is asserted rather than assumed: it is media-*sized*
     * but card-*framed* — it insets its canvas and paints its own fill — so it must not follow the image. It
     * previously drew 16 dp and still does.
     */
    @Test
    fun `only content that reaches the frame takes the media radius`() {
        assertEquals(FlowFrameMetrics.MEDIA_RADIUS, FlowFrameMetrics.radiusOf(ImageBlock()))
        assertEquals(FlowFrameMetrics.MEDIA_RADIUS, FlowFrameMetrics.radiusOf(ShapeBlock()))

        assertEquals(FlowFrameMetrics.CARD_RADIUS, FlowFrameMetrics.radiusOf(ChartBlock()))
        assertEquals(FlowFrameMetrics.CARD_RADIUS, FlowFrameMetrics.radiusOf(TableBlock()))
        assertEquals(FlowFrameMetrics.CARD_RADIUS, FlowFrameMetrics.radiusOf(CodeBlock()))
        assertEquals(FlowFrameMetrics.CARD_RADIUS, FlowFrameMetrics.radiusOf(MathBlock()))
        assertEquals(FlowFrameMetrics.CARD_RADIUS, FlowFrameMetrics.radiusOf(MermaidBlock()))
        assertEquals(FlowFrameMetrics.CARD_RADIUS, FlowFrameMetrics.radiusOf(EmbedBlock()))
        assertEquals(FlowFrameMetrics.CARD_RADIUS, FlowFrameMetrics.radiusOf(FileBlock()))
    }

    /**
     * A selection edge has to be visibly heavier than a resting one, not one dp heavier.
     *
     * Pinned as a relation rather than as two numbers, because the numbers are a visual judgement and the
     * relation is the requirement: the chart card previously moved 1 dp → 2 dp on selection, which at arm's
     * length on a phone is not a state change.
     */
    @Test
    fun `a selection edge is substantially heavier than a resting edge`() {
        assertTrue(FlowFrameMetrics.SELECTED_WIDTH >= FlowFrameMetrics.RESTING_WIDTH * 2)
    }

    /** A radius is a real measurement for every kind, framed or not — the function is total. */
    @Test
    fun `radius is defined for unframed kinds too`() {
        assertNotNull(FlowFrameMetrics.radiusOf(ParagraphBlock()))
        assertNotNull(FlowFrameMetrics.shapeOf(DividerBlock()))
    }

    // ── Spoken labels ────────────────────────────────────────────────────────────────────────────────

    /**
     * Every kind that can *be* the selection can be named as one.
     *
     * This is the assertion that would have caught the original defect. Nine kinds are selectable and only two
     * drew anything at all, so seven of them could be selected and announce nothing. A label is what the
     * frame's `contentDescription` is built from, so a missing one is a screen reader saying "selected" with no
     * subject.
     *
     * Deliberately checked against [deriveFlowObject] rather than against a hand-written list of kinds: the
     * domain model decides what is selectable, and a second list here could disagree with it.
     */
    @Test
    fun `every selectable kind has a spoken label`() {
        val selectable = listOf(
            ImageBlock(), ChartBlock(), ShapeBlock(), TableBlock(),
            CodeBlock(), MathBlock(), MermaidBlock(), EmbedBlock(), FileBlock(),
        ).filter { it.deriveFlowObject().selectable }

        assertEquals(9, selectable.size)
        selectable.forEach { block ->
            val label = flowObjectKindLabel(block)
            assertTrue("$block produced a blank label", label.isNotBlank())
            // "Block" is the generic the fallback exists to avoid; a named kind must never reach it.
            assertFalse("$block fell through to the generic fallback", label == "Object")
        }
    }

    /** The labels are the words a user hears, so they are pinned verbatim rather than merely non-blank. */
    @Test
    fun `labels read as prose rather than as class names`() {
        assertEquals("Image", flowObjectKindLabel(ImageBlock()))
        assertEquals("Chart", flowObjectKindLabel(ChartBlock()))
        assertEquals("Table", flowObjectKindLabel(TableBlock()))
        assertEquals("Shape", flowObjectKindLabel(ShapeBlock()))
        assertEquals("Code block", flowObjectKindLabel(CodeBlock()))
        assertEquals("Equation", flowObjectKindLabel(MathBlock()))
        assertEquals("Diagram", flowObjectKindLabel(MermaidBlock()))
        assertEquals("Embedded link", flowObjectKindLabel(EmbedBlock()))
        assertEquals("File", flowObjectKindLabel(FileBlock()))
    }

    /**
     * A kind added later announces something approximately right rather than "Object".
     *
     * The fallback strips the `Block` suffix off the class name, so a hypothetical `TimelineBlock` says
     * "Timeline" the day it is written and before anyone remembers to add it above. Asserted on a real kind
     * that is genuinely absent from the list — a paragraph — so the test cannot pass by coincidence.
     */
    @Test
    fun `an unlisted kind derives a label from its own name`() {
        assertEquals("Paragraph", flowObjectKindLabel(ParagraphBlock()))
        assertEquals("Divider", flowObjectKindLabel(DividerBlock()))
    }
}
