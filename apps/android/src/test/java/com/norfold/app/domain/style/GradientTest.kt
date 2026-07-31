package com.norfold.app.domain.style

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gradient domain model (roadmap Phase 3 §7, §37.8–§37.12).
 *
 * The model's two hard requirements are that a gradient is *data* (never a Compose `Brush`) and that a stop
 * keeps its identity across reordering. Both are tested here as behaviour rather than as structure: the
 * first by round-tripping through JSON, the second by dragging a stop past its neighbours and checking the
 * same stop is still the one being edited.
 */
class GradientTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun ramp() = Gradient.of(ColorValue(255, 0, 0), ColorValue(0, 0, 255))

    // ── §37.8 serialization ──────────────────────────────────────────────────────────────────────

    /**
     * A gradient must survive JSON exactly — it is document data.
     *
     * This is the test that would fail if anyone stored a platform `Brush`: a `Brush` has no stop list to
     * serialize, so the round trip could not even be attempted.
     */
    @Test
    fun `gradient round trips through json with stop identity intact`() {
        val original = ramp()
            .addStopAt(0.4f)
            .withAngle(215f)
            .copy(kind = GradientKind.RADIAL, centerX = 0.25f, radius = 0.8f)
            .let { it.setStopOpacity(it.stops[1].id, 0.35f) }
        val decoded = json.decodeFromString<Gradient>(json.encodeToString(original))
        assertEquals(original, decoded)
        assertEquals(
            "stop ids must survive serialization",
            original.stops.map { it.id },
            decoded.stops.map { it.id },
        )
        assertEquals(215f, decoded.angle, 0.001f)
        assertEquals(GradientKind.RADIAL, decoded.kind)
    }

    @Test
    fun `color paint round trips as a discriminated union`() {
        val flat: ColorPaint = ColorPaint.Flat(ColorValue(12, 34, 56, 200))
        val gradient: ColorPaint = ColorPaint.Ramp(ramp())
        assertEquals(flat, json.decodeFromString<ColorPaint>(json.encodeToString(flat)))
        assertEquals(gradient, json.decodeFromString<ColorPaint>(json.encodeToString(gradient)))
    }

    // ── §37.9 stop identity across reordering ────────────────────────────────────────────────────

    /**
     * Dragging a stop past its neighbour keeps editing the same stop.
     *
     * The defect this catches is index-based stop identity: with indices, moving stop A from 0.1 to 0.9
     * makes the *other* stop occupy index 0, so the drag's next update recolours the wrong stop and the
     * colour under the user's finger jumps.
     */
    @Test
    fun `dragging a stop past its neighbours keeps its identity`() {
        var gradient = ramp().addStopAt(0.5f)
        val middle = gradient.stops.last().id
        val middleColor = gradient.stopById(middle)!!.color

        // Drag it left past the first stop, then all the way right past the last.
        gradient = gradient.moveStop(middle, -0.2f)
        assertEquals("clamped to the ramp start", 0f, gradient.stopById(middle)!!.position, 0.0001f)
        gradient = gradient.moveStop(middle, 1.2f)
        assertEquals(1f, gradient.stopById(middle)!!.position, 0.0001f)

        val stop = gradient.stopById(middle)
        assertNotNull("the dragged stop must still exist under its own id", stop)
        assertEquals("and must still be the same colour", middleColor, stop!!.color)
        assertEquals("no stop may be created or destroyed by a drag", 3, gradient.stopCount)
    }

    /** Equal positions keep authored order, so the render is deterministic rather than arbitrary. */
    @Test
    fun `equal positions keep authored order`() {
        val a = GradientStop(id = "a", color = ColorValue(1, 1, 1), position = 0.5f)
        val b = GradientStop(id = "b", color = ColorValue(2, 2, 2), position = 0.5f)
        val c = GradientStop(id = "c", color = ColorValue(3, 3, 3), position = 0f)
        val gradient = Gradient(stops = listOf(a, b, c))
        assertEquals(listOf("c", "a", "b"), gradient.sortedStops.map { it.id })
        // And repeatedly asking gives the same answer.
        assertEquals(gradient.sortedStops.map { it.id }, gradient.sortedStops.map { it.id })
    }

    @Test
    fun `reorder moves the named stop and nothing else`() {
        val gradient = ramp().addStopAt(0.5f)
        val target = gradient.stops.last().id
        val reordered = gradient.reorderStop(target, 0)
        assertEquals(target, reordered.stops.first().id)
        assertEquals(gradient.stops.map { it.id }.toSet(), reordered.stops.map { it.id }.toSet())
        assertEquals("positions are untouched by reordering", 0.5f, reordered.stopById(target)!!.position, 0.0001f)
    }

    // ── §37.10 insertion, removal, normalization ─────────────────────────────────────────────────

    /**
     * Inserting a stop mid-ramp samples the existing colour, so the ramp does not visibly change.
     *
     * Inventing a default colour instead makes every insertion a recolour, which is the difference between
     * "add a control point" and "wreck my gradient".
     */
    @Test
    fun `inserting a stop does not change the rendered ramp`() {
        val gradient = ramp()
        val before = (0..10).map { gradient.colorAt(it / 10f) }
        val after = gradient.addStopAt(0.37f)
        val sampled = (0..10).map { after.colorAt(it / 10f) }
        for (i in before.indices) {
            val a = before[i]
            val b = sampled[i]
            assertTrue(
                "sample $i changed from $a to $b",
                kotlin.math.abs(a.red - b.red) <= 1 &&
                    kotlin.math.abs(a.green - b.green) <= 1 &&
                    kotlin.math.abs(a.blue - b.blue) <= 1,
            )
        }
    }

    @Test
    fun `a gradient refuses to drop below two stops`() {
        val two = ramp()
        assertEquals("removing from a two-stop ramp is refused", 2, two.removeStop(two.stops.first().id).stopCount)
        val three = two.addStopAt(0.5f)
        assertEquals(2, three.removeStop(three.stops.last().id).stopCount)
    }

    /** Normalization repairs invalid persisted data deterministically, without reordering intent. */
    @Test
    fun `normalization repairs invalid data without rearranging it`() {
        val messy = Gradient(
            stops = listOf(
                GradientStop(id = "x", color = ColorValue(1, 2, 3), position = Float.NaN),
                GradientStop(id = "x", color = ColorValue(4, 5, 6), position = 7f, opacity = -3f),
            ),
            angle = 900f,
            centerX = Float.POSITIVE_INFINITY,
            radius = 0f,
        )
        val fixed = messy.normalized()
        assertTrue("positions must be finite and in range", fixed.stops.all { it.position in 0f..1f })
        assertTrue("opacity must be in range", fixed.stops.all { it.opacity in 0f..1f })
        assertEquals("angle wraps", 180f, fixed.angle, 0.001f)
        assertTrue(fixed.centerX in 0f..1f)
        assertTrue("radius must be usable", fixed.radius > 0f)
        assertEquals("duplicate ids must be made unique", 2, fixed.stops.map { it.id }.distinct().size)
        // Determinism: normalizing twice is the same as once, apart from the ids already made unique.
        val again = fixed.normalized()
        assertEquals(fixed, again)
    }

    @Test
    fun `a single stop becomes a renderable ramp of that colour`() {
        val one = Gradient(stops = listOf(GradientStop(color = ColorValue(9, 9, 9), position = 0.2f)))
        val fixed = one.normalized()
        assertTrue(fixed.isRenderable)
        assertEquals(2, fixed.stopCount)
        assertEquals("the user's colour is kept, not replaced by a default", ColorValue(9, 9, 9), fixed.colorAt(0.5f))
    }

    @Test
    fun `an empty stop list normalizes to the default ramp`() {
        val fixed = Gradient(stops = emptyList()).normalized()
        assertTrue(fixed.isRenderable)
        assertEquals(2, fixed.stopCount)
    }

    // ── §37.11 kind switching preserves settings ─────────────────────────────────────────────────

    /**
     * Linear → radial → linear returns the user to the angle they had.
     *
     * Zeroing the irrelevant field instead means a user exploring gradient kinds silently loses the 215°
     * they had dialled in, and has to redo it.
     */
    @Test
    fun `switching kind preserves the settings of the other kinds`() {
        val original = ramp().withAngle(215f).copy(centerX = 0.2f, centerY = 0.8f, radius = 0.9f)
        val roundTrip = original.withKind(GradientKind.RADIAL).withKind(GradientKind.LINEAR)
        assertEquals(215f, roundTrip.angle, 0.001f)
        assertEquals(0.2f, roundTrip.centerX, 0.001f)
        assertEquals(0.8f, roundTrip.centerY, 0.001f)
        assertEquals(0.9f, roundTrip.radius, 0.001f)
        assertEquals(original.stops, roundTrip.stops)
    }

    @Test
    fun `angle normalizes without losing the direction`() {
        assertEquals(0f, Gradient.normalizeAngle(360f), 0.001f)
        assertEquals(90f, Gradient.normalizeAngle(450f), 0.001f)
        assertEquals(270f, Gradient.normalizeAngle(-90f), 0.001f)
        assertEquals(0f, Gradient.normalizeAngle(Float.NaN), 0.001f)
    }

    // ── §37.12 opacity and interpolation ─────────────────────────────────────────────────────────

    /** Stop opacity multiplies the colour's own alpha instead of replacing it. */
    @Test
    fun `stop opacity composes with the colour's own alpha`() {
        val stop = GradientStop(color = ColorValue(10, 20, 30, 128), position = 0f, opacity = 0.5f)
        val effective = stop.effectiveColor()
        assertTrue("both facts must survive, alpha was ${effective.alpha}", effective.alpha in 63..65)
        assertEquals(10, effective.red)
        // Setting opacity must not overwrite the stored alpha.
        val gradient = ramp().let { it.setStopOpacity(it.stops.first().id, 0.25f) }
        assertEquals(255, gradient.stops.first().color.alpha)
        assertEquals(0.25f, gradient.stops.first().opacity, 0.0001f)
    }

    @Test
    fun `colorAt interpolates monotonically between stops`() {
        val gradient = Gradient.of(ColorValue(0, 0, 0), ColorValue(255, 255, 255))
        var previous = -1
        for (i in 0..20) {
            val value = gradient.colorAt(i / 20f).red
            assertTrue("red must not decrease along a black-to-white ramp", value >= previous)
            previous = value
        }
        assertEquals(0, gradient.colorAt(0f).red)
        assertEquals(255, gradient.colorAt(1f).red)
        assertEquals("out-of-range samples clamp", 255, gradient.colorAt(5f).red)
        assertEquals(0, gradient.colorAt(-5f).red)
    }

    @Test
    fun `reversing mirrors positions and the authored order together`() {
        val gradient = ramp().addStopAt(0.25f)
        val reversed = gradient.reversed()
        assertEquals(
            "positions mirror",
            gradient.stops.map { 1f - it.position }.reversed(),
            reversed.stops.map { it.position },
        )
        assertEquals(
            "authored order reverses with them",
            gradient.stops.map { it.id }.reversed(),
            reversed.stops.map { it.id },
        )
        // Reversing twice is the identity.
        assertEquals(gradient.stops.map { it.id }, reversed.reversed().stops.map { it.id })
    }

    @Test
    fun `even distribution spreads stops across the full ramp`() {
        val gradient = ramp().addStopAt(0.9f).addStopAt(0.95f).evenlyDistributed()
        val positions = gradient.stops.map { it.position }
        assertEquals(listOf(0f, 1f / 3f, 2f / 3f, 1f), positions.map { it })
        assertEquals(4, gradient.stopCount)
    }

    @Test
    fun `interpolation mode is stored so a perceptual ramp stays forward readable`() {
        val perceptual = ramp().copy(interpolation = GradientInterpolation.OKLAB)
        assertEquals(
            GradientInterpolation.OKLAB,
            json.decodeFromString<Gradient>(json.encodeToString(perceptual)).interpolation,
        )
    }

    @Test
    fun `describe states the kind and stop count for a screen reader`() {
        val text = ramp().addStopAt(0.5f).withAngle(45f).describe()
        assertTrue(text, text.contains("Linear"))
        assertTrue(text, text.contains("3"))
    }

    @Test
    fun `paint representatives never return a transparent placeholder for a real ramp`() {
        val paint = ColorPaint.Ramp(ramp())
        assertFalse(paint.representative() == ColorValue.TRANSPARENT)
        assertEquals(2, paint.colors().size)
    }

    @Test
    fun `stop ids are unique per stop`() {
        val gradient = ramp().addStopAt(0.3f).addStopAt(0.6f).addStopAt(0.9f)
        assertEquals(gradient.stopCount, gradient.stops.map { it.id }.distinct().size)
        assertNull(gradient.stopById("nope"))
    }
}
