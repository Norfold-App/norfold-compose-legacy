package com.norfold.app.editor.style

import com.norfold.app.domain.style.ColorDomain
import com.norfold.app.domain.style.ColorHarmony
import com.norfold.app.domain.style.ColorPaint
import com.norfold.app.domain.style.ColorTarget
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.domain.style.ColorVisionDeficiency
import com.norfold.app.domain.style.ContrastConfidence
import com.norfold.app.domain.style.Gradient
import com.norfold.app.domain.style.GradientKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Color Studio engine (roadmap Phase 3 §4, §6, §28, §29, §37.13–§37.16).
 *
 * The behaviours under test are the ones §40 names as defects elsewhere in the app: a tool that closes after
 * every change, one undo record per slider pixel, a cancel that confirms what it was asked to discard, and
 * numeric fields that drift apart. Each is asserted against a recording host so the *number* of
 * transactions is observable, not just the final colour.
 */
class ColorStudioEngineTest {

    /** Records previews and commits so undo granularity is a measurable fact. */
    private class RecordingHost : ColorStudioHost {
        val previews = mutableListOf<ColorPaint>()
        val commits = mutableListOf<Pair<String, ColorPaint>>()
        var applied: ColorPaint = ColorPaint.Flat(ColorValue.BLACK)
        var refuse = false

        override fun preview(target: ColorTarget, paint: ColorPaint) {
            previews += paint
            applied = paint
        }

        override fun commit(target: ColorTarget, paint: ColorPaint, label: String): Boolean {
            if (refuse) return false
            commits += label to paint
            applied = paint
            return true
        }
    }

    private val host = RecordingHost()
    private val engine = ColorStudioEngine(host)

    private val textTarget = ColorTargets.flowTextColor(listOf("b1"))
    private val fillTarget = ColorTargets.documentShapeFill(listOf("s1"))

    private fun open(target: ColorTarget = textTarget, color: ColorValue = ColorValue(20, 40, 60)) =
        engine.open(target, ColorPaint.Flat(color))

    // ── §37.13 view switching is non-destructive ─────────────────────────────────────────────────

    /**
     * Cycling every view leaves the colour byte-identical and writes nothing.
     *
     * This is §2's central invariant. Zero previews and zero commits is the strong form: it is not merely
     * that the value came back the same, it is that switching a tab cannot reach the colour at all.
     */
    @Test
    fun `cycling every view changes nothing and commits nothing`() {
        val start = ColorValue(203, 87, 41, 200)
        open(color = start)
        for (pass in 1..3) {
            for (view in ColorStudioView.entries) {
                engine.setView(view)
                assertEquals(
                    "colour changed after switching to $view on pass $pass",
                    start,
                    engine.state().color(),
                )
            }
        }
        assertTrue("switching views must not preview", host.previews.isEmpty())
        assertTrue("switching views must not commit", host.commits.isEmpty())
    }

    @Test
    fun `switching views clears a half typed draft rather than applying it`() {
        open()
        engine.draft(ColorField.HEX, "#1A2B")
        engine.setView(ColorStudioView.SLIDERS)
        assertNull("an incomplete draft must not survive a view change", engine.state().draft)
        assertTrue(host.commits.isEmpty())
    }

    // ── §37.14 one gesture, one transaction ──────────────────────────────────────────────────────

    /**
     * A 200-step drag is one undo step.
     *
     * The named defect is "one History transaction per slider pixel"; the fix is that previews never commit,
     * so the count of commits is the count of *gestures*, not of frames.
     */
    @Test
    fun `a long drag produces exactly one transaction`() {
        open(color = ColorValue(0, 0, 0))
        repeat(200) { step -> engine.previewColor(ColorValue(step, step, step)) }
        assertEquals("previews are continuous", 200, host.previews.size)
        assertEquals("but nothing is committed mid-gesture", 0, host.commits.size)
        assertTrue(engine.commit("Change color"))
        assertEquals("one gesture is one transaction", 1, host.commits.size)
        assertEquals(ColorPaint.Flat(ColorValue(199, 199, 199)), host.commits.single().second)
    }

    @Test
    fun `two gestures produce two transactions`() {
        open()
        engine.previewColor(ColorValue(10, 10, 10))
        engine.commit()
        engine.previewColor(ColorValue(20, 20, 20))
        engine.commit()
        assertEquals(2, host.commits.size)
    }

    /** Re-picking the colour that is already applied must not occupy an undo step. */
    @Test
    fun `committing an unchanged colour is a no-op`() {
        val color = ColorValue(77, 88, 99)
        open(color = color)
        engine.previewColor(color)
        assertFalse("no change means no transaction", engine.commit())
        assertTrue(host.commits.isEmpty())
    }

    @Test
    fun `commit without an adjustment in flight does nothing`() {
        open()
        assertFalse(engine.commit())
        assertTrue(host.commits.isEmpty())
    }

    // ── §37.15 cancel restores exactly ───────────────────────────────────────────────────────────

    /**
     * Cancel restores the pre-gesture value exactly, not "approximately" and not the previewed value.
     *
     * The subtle failure is a cancel that re-derives the baseline from the target: after previewing, the
     * target already holds the new colour, so such a cancel confirms the change it was asked to discard.
     */
    @Test
    fun `cancel restores the exact pre gesture paint`() {
        val start = ColorValue(120, 34, 200, 180)
        open(color = start)
        repeat(50) { engine.previewColor(ColorValue(it, it * 2 % 256, 9)) }
        engine.cancel()
        assertEquals(start, engine.state().color())
        assertEquals("the host must be repainted with the original", ColorPaint.Flat(start), host.applied)
        assertTrue("cancel is not a transaction", host.commits.isEmpty())
    }

    @Test
    fun `cancel after commit does not undo the committed value`() {
        val start = ColorValue(5, 5, 5)
        open(color = start)
        engine.previewColor(ColorValue(9, 9, 9))
        engine.commit()
        engine.cancel()
        assertEquals(
            "cancel has nothing to cancel once the gesture committed",
            ColorValue(9, 9, 9),
            engine.state().color(),
        )
    }

    // ── §37.16 the studio stays open ─────────────────────────────────────────────────────────────

    /**
     * Applying a colour leaves the studio open (§40: "Color Studio closes after every slider change").
     *
     * Choosing colour is comparative — apply, look, adjust — so closing after each application turns one
     * decision into a dozen reopenings.
     */
    @Test
    fun `applying a colour leaves the studio open`() {
        open()
        repeat(5) { step ->
            engine.previewColor(ColorValue(step * 10, 0, 0))
            engine.commit()
            assertTrue("the studio closed after commit $step", engine.isOpen)
            assertNotNull(engine.state().target)
        }
        assertEquals(5, host.commits.size)
    }

    /** Retargeting keeps the tool open but must not carry the old colour onto the new target. */
    @Test
    fun `retargeting keeps tool state but not the colour`() {
        open(color = ColorValue(200, 10, 10))
        engine.setView(ColorStudioView.SLIDERS)
        engine.retarget(fillTarget, ColorPaint.Flat(ColorValue(1, 2, 3)))
        assertTrue(engine.isOpen)
        assertEquals("the new target's own colour is shown", ColorValue(1, 2, 3), engine.state().color())
        assertEquals("the user's chosen view is a preference and travels", ColorStudioView.SLIDERS, engine.state().view)
        assertTrue("retargeting is not an edit", host.commits.isEmpty())
    }

    /** Re-opening on the same target resumes; a different target resets the view. */
    @Test
    fun `reopening the same target resumes the session`() {
        open()
        engine.setView(ColorStudioView.HARMONIES)
        engine.open(textTarget, ColorPaint.Flat(ColorValue(20, 40, 60)))
        assertEquals(ColorStudioView.HARMONIES, engine.state().view)
        engine.open(fillTarget, ColorPaint.Flat(ColorValue(20, 40, 60)))
        assertEquals(ColorStudioView.SWATCHES, engine.state().view)
    }

    // ── Text field drafts ────────────────────────────────────────────────────────────────────────

    /**
     * A half-typed hex is held as a draft and applied only when complete.
     *
     * Applying every keystroke would either reject `#1A2B` as invalid mid-typing or recolour the document
     * six times per hex entry.
     */
    @Test
    fun `an incomplete hex draft does not touch the document`() {
        open(color = ColorValue(0, 0, 0))
        listOf("#", "#1", "#1A", "#1A2", "#1A2B", "#1A2B3").forEach { engine.draft(ColorField.HEX, it) }
        assertTrue("no partial entry may be applied", host.previews.isEmpty())
        assertEquals(ColorValue(0, 0, 0), engine.state().color())
        engine.draft(ColorField.HEX, "#1A2B3C")
        assertEquals(DraftOutcome.COMMITTED, engine.commitDraft())
        assertEquals(ColorValue(0x1A, 0x2B, 0x3C), engine.state().color())
        assertEquals(1, host.commits.size)
    }

    @Test
    fun `an invalid draft is discarded and the field re-reads the canonical colour`() {
        val start = ColorValue(0x11, 0x22, 0x33)
        open(color = start)
        engine.draft(ColorField.HEX, "#GGGGGG")
        assertEquals(DraftOutcome.REJECTED, engine.commitDraft())
        assertEquals("the real value is restored, not an invented one", start, engine.state().color())
        assertEquals("#112233", engine.state().fieldText(ColorField.HEX))
        assertTrue(host.commits.isEmpty())
    }

    /**
     * Every field displays a value that parses back to the same colour.
     *
     * This is the fields-drift guard expressed as a property: display then re-enter must be the identity,
     * which is only true when each field is a *view* of one canonical value.
     */
    @Test
    fun `every field round trips its own displayed text`() {
        val colors = listOf(
            ColorValue(0, 0, 0),
            ColorValue(255, 255, 255),
            ColorValue(203, 87, 41),
            ColorValue(18, 52, 86, 128),
            ColorValue(128, 128, 128),
        )
        for (color in colors) {
            for (field in ColorField.entries) {
                open(color = color)
                val shown = engine.state().fieldText(field)
                engine.draft(field, shown)
                val outcome = engine.commitDraft()
                assertTrue(
                    "field $field showed '$shown' for $color but re-entering it was $outcome",
                    outcome == DraftOutcome.UNCHANGED || outcome == DraftOutcome.COMMITTED,
                )
                val after = engine.state().color()
                assertTrue(
                    "re-entering $field's own text moved $color to $after",
                    kotlin.math.abs(after.red - color.red) <= 2 &&
                        kotlin.math.abs(after.green - color.green) <= 2 &&
                        kotlin.math.abs(after.blue - color.blue) <= 2 &&
                        kotlin.math.abs(after.alpha - color.alpha) <= 3,
                )
                host.commits.clear()
                host.previews.clear()
            }
        }
    }

    @Test
    fun `a localized digit is rejected rather than misread`() {
        open(color = ColorValue(10, 20, 30))
        engine.draft(ColorField.RED, "২৫")
        assertEquals(DraftOutcome.REJECTED, engine.commitDraft())
        assertEquals(10, engine.state().color().red)
    }

    @Test
    fun `commitDraft with no draft reports nothing to commit`() {
        open()
        assertEquals(DraftOutcome.NOTHING_TO_COMMIT, engine.commitDraft())
    }

    // ── Gradients ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `enabling a gradient seeds it from the current colour`() {
        val base = ColorValue(40, 120, 200)
        engine.open(fillTarget, ColorPaint.Flat(base))
        engine.setGradientEnabled(true)
        val gradient = engine.gradient()
        assertNotNull("the fill target supports gradients", gradient)
        assertEquals("the ramp starts from the colour that was there", base, gradient!!.sortedStops.first().color)
        assertNotNull("a stop must be selected so the tools have a subject", engine.state().activeStopId)
    }

    /** A target that cannot take a gradient must refuse rather than store one it cannot render. */
    @Test
    fun `a text target refuses a gradient`() {
        open()
        engine.setGradientEnabled(true)
        assertNull("text colour is flat-only", engine.gradient())
        assertFalse(engine.state().isGradient)
    }

    @Test
    fun `turning a gradient back into a flat colour keeps the first stop`() {
        engine.open(fillTarget, ColorPaint.Ramp(Gradient.of(ColorValue(1, 2, 3), ColorValue(9, 9, 9))))
        engine.setGradientEnabled(false)
        assertEquals(ColorValue(1, 2, 3), engine.state().color())
    }

    /** Editing a gradient stop is still one transaction per gesture. */
    @Test
    fun `dragging a gradient stop is one transaction`() {
        engine.open(fillTarget, ColorPaint.Ramp(Gradient.of(ColorValue(255, 0, 0), ColorValue(0, 0, 255))))
        val stopId = engine.gradient()!!.stops.first().id
        repeat(40) { step -> engine.previewGradient(engine.gradient()!!.moveStop(stopId, step / 40f)) }
        assertEquals(0, host.commits.size)
        assertTrue(engine.commit("Move gradient stop"))
        assertEquals(1, host.commits.size)
        assertEquals("the stop the user dragged is the one that moved", stopId, engine.gradient()!!.stops.first().id)
    }

    @Test
    fun `alpha preview edits the active stop of a gradient`() {
        engine.open(fillTarget, ColorPaint.Ramp(Gradient.of(ColorValue(255, 0, 0), ColorValue(0, 0, 255))))
        val second = engine.gradient()!!.stops[1].id
        engine.selectStop(second)
        engine.previewAlpha(0.25f)
        assertEquals(0.25f, engine.gradient()!!.stopById(second)!!.opacity, 0.001f)
        assertEquals("the other stop is untouched", 1f, engine.gradient()!!.stops[0].opacity, 0.001f)
    }

    /** Numeric and swatch edits target the selected stop; they must never flatten the whole ramp. */
    @Test
    fun `editing the active gradient stop preserves the ramp and its other stop`() {
        val firstColor = ColorValue(255, 0, 0)
        val secondColor = ColorValue(0, 0, 255)
        val replacement = ColorValue(12, 34, 56)
        engine.open(fillTarget, ColorPaint.Ramp(Gradient.of(firstColor, secondColor)))
        val second = engine.gradient()!!.stops[1].id
        engine.selectStop(second)

        engine.previewColor(replacement)
        assertTrue("editing one stop must keep gradient mode", engine.state().paint is ColorPaint.Ramp)
        assertEquals("the unselected stop is untouched", firstColor, engine.gradient()!!.stops[0].color)
        assertEquals(replacement, engine.gradient()!!.stopById(second)!!.color)

        engine.draft(ColorField.ALPHA, "40")
        assertEquals(DraftOutcome.COMMITTED, engine.commitDraft())
        assertTrue("an alpha field edit must also keep gradient mode", engine.state().paint is ColorPaint.Ramp)
        assertEquals(0.4f, engine.gradient()!!.stopById(second)!!.opacity, 0.001f)
    }

    @Test
    fun `removing the last removable stop is refused`() {
        engine.open(fillTarget, ColorPaint.Ramp(Gradient.of(ColorValue(1, 1, 1), ColorValue(2, 2, 2))))
        engine.selectStop(engine.gradient()!!.stops.first().id)
        engine.removeActiveStop()
        assertEquals("a gradient keeps at least two stops", 2, engine.gradient()!!.stopCount)
    }

    // ── Recents, palette, tokens ─────────────────────────────────────────────────────────────────

    @Test
    fun `recents are bounded newest first with duplicates collapsed`() {
        val engine = ColorStudioEngine(host, recentLimit = 4)
        listOf(1, 2, 3, 4, 5, 2).forEach { engine.rememberRecent(ColorValue(it, it, it)) }
        val recents = engine.recentColors()
        assertEquals(4, recents.size)
        assertEquals("re-used colours move to the front", ColorValue(2, 2, 2), recents.first())
        assertEquals("no duplicates", recents.size, recents.distinct().size)
    }

    @Test
    fun `committing records the colour as recent`() {
        open()
        engine.previewColor(ColorValue(7, 7, 7))
        engine.commit()
        assertTrue(ColorValue(7, 7, 7) in engine.recentColors())
    }

    /** A refused commit must not pollute recents with a colour that was never applied. */
    @Test
    fun `a refused commit records nothing`() {
        open()
        host.refuse = true
        engine.previewColor(ColorValue(3, 3, 3))
        assertFalse(engine.commit())
        assertFalse(ColorValue(3, 3, 3) in engine.recentColors())
    }

    /** Only tokenizable domains offer document colours; chrome and collaborator identity do not. */
    @Test
    fun `non tokenizable domains offer no document palette`() {
        val palette = listOf(ColorValue(1, 1, 1))
        assertEquals(palette, engine.documentColors(palette, ColorDomain.CONTENT))
        assertTrue(engine.documentColors(palette, ColorDomain.COLLABORATOR_IDENTITY).isEmpty())
        assertTrue(engine.documentColors(palette, ColorDomain.APP_CHROME).isEmpty())
    }

    // ── Analysis ─────────────────────────────────────────────────────────────────────────────────

    /** An unknown backdrop is answered honestly rather than by assuming white. */
    @Test
    fun `unknown background yields an indeterminate contrast result`() {
        open(color = ColorValue(128, 128, 128))
        val result = engine.contrast(background = null)
        assertEquals(ContrastConfidence.INDETERMINATE, result.confidence)
        assertFalse("an unknown backdrop cannot be claimed to pass", result.passesAA)
        assertTrue(result.summary().isNotBlank())
    }

    @Test
    fun `known background gives a definite ratio`() {
        open(color = ColorValue(0, 0, 0))
        val result = engine.contrast(ColorValue.WHITE)
        assertEquals(ContrastConfidence.MEASURED, result.confidence)
        assertEquals("black on white is 21:1", 21.0, result.ratio, 0.05)
        assertTrue(result.passesAAA)
    }

    @Test
    fun `harmony returns the base colour plus its partners`() {
        open(color = ColorValue(200, 50, 50))
        val complementary = engine.harmony(ColorHarmony.COMPLEMENTARY)
        assertTrue("the base colour is part of the scheme", complementary.isNotEmpty())
        assertEquals(ColorValue(200, 50, 50), complementary.first())
        assertTrue(engine.harmony(ColorHarmony.TRIADIC).size >= 3)
    }

    @Test
    fun `cvd simulation changes a red but not a grey`() {
        open(color = ColorValue(200, 30, 30))
        val simulated = engine.cvdPreview(ColorVisionDeficiency.PROTANOPIA)
        assertFalse("protanopia must alter a saturated red", simulated == ColorValue(200, 30, 30))
        open(color = ColorValue(128, 128, 128))
        val grey = engine.cvdPreview(ColorVisionDeficiency.PROTANOPIA)
        assertTrue(
            "a neutral grey should be nearly unchanged, was $grey",
            kotlin.math.abs(grey.red - 128) <= 6 && kotlin.math.abs(grey.blue - 128) <= 6,
        )
    }

    /**
     * A collision is reported only for colours that were distinguishable *before* the simulation.
     *
     * Two already-identical colours converging is not a colour-vision finding — telling the user "these two
     * identical swatches look the same" is noise that trains them to ignore the real warnings.
     */
    @Test
    fun `cvd collisions only report pairs that were distinguishable to begin with`() {
        open(color = ColorValue(200, 60, 60))
        assertTrue(
            "identical colours are not a CVD finding",
            engine.cvdCollisions(listOf(ColorValue(200, 60, 60))).isEmpty(),
        )
        // A red/green pair is the canonical protan/deutan confusion, and must be caught.
        val redGreen = engine.cvdCollisions(listOf(ColorValue(40, 160, 40)))
        assertTrue("a red/green pair must be flagged", redGreen.isNotEmpty())
        assertTrue(redGreen.first().summary().isNotBlank())
        assertTrue(
            "the summary must name a second cue rather than only the colours",
            redGreen.first().summary().contains("label") || redGreen.first().summary().contains("pattern"),
        )
    }

    // ── State survives a form-factor change ──────────────────────────────────────────────────────

    /**
     * The whole tool state is one carried object (§28).
     *
     * Copying the state and restoring it — which is what a presentation change does — must preserve the
     * view, the active stop and the target, because losing the stop mid-drag is the concrete failure.
     */
    @Test
    fun `studio state is a single carryable value`() {
        engine.open(fillTarget, ColorPaint.Ramp(Gradient.of(ColorValue(1, 2, 3), ColorValue(4, 5, 6), GradientKind.RADIAL)))
        engine.setView(ColorStudioView.GRADIENT)
        val second = engine.gradient()!!.stops[1].id
        engine.selectStop(second)

        val carried = engine.state()
        val rebuilt = ColorStudioEngine(host)
        rebuilt.open(carried.target!!, carried.paint)
        rebuilt.setView(carried.view)
        rebuilt.selectStop(carried.activeStopId)

        assertEquals(carried.view, rebuilt.state().view)
        assertEquals(carried.activeStopId, rebuilt.state().activeStopId)
        assertEquals(carried.paint, rebuilt.state().paint)
        assertEquals(carried.color(), rebuilt.state().color())
    }

    @Test
    fun `closing clears the target so a stale gesture cannot paint`() {
        open()
        engine.close()
        assertFalse(engine.isOpen)
        assertNull(engine.state().target)
        assertEquals(DraftOutcome.NOTHING_TO_COMMIT, engine.commitDraft())
        assertFalse(engine.commit())
    }
}
