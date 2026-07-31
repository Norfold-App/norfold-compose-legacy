package com.norfold.app.domain.style

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.abs

/**
 * The Phase-3 gradient domain model (roadmap §7, spec §8.5).
 *
 * Deliberately toolkit-independent: a gradient is *data*, and the renderer builds a Compose `Brush` from
 * it on the way to the screen. Persisting a `Brush` — the failure §40 names — would store an opaque
 * platform object that cannot be exported to PDF, cannot be inspected by a test, cannot round-trip
 * through JSON, and cannot even be re-edited, because a `Brush` has no readable stop list.
 *
 * [ColorValue] remains the stop color model, so a gradient stop and a flat fill are the same kind of
 * color and the same Color Studio edits both.
 */
@Serializable
enum class GradientKind(val label: String) {
    @SerialName("linear") LINEAR("Linear"),
    @SerialName("radial") RADIAL("Radial"),
    @SerialName("conic") CONIC("Angular"),
}

/**
 * How colors interpolate between stops. An enum with a documented default rather than a boolean, because
 * perceptual interpolation is a real later feature and a boolean would have to be replaced.
 */
@Serializable
enum class GradientInterpolation(val label: String) {
    /** Straight sRGB channel interpolation — what every current renderer does natively. */
    @SerialName("srgb") SRGB("sRGB"),

    /** Reserved seam: perceptually even blending. Modelled now so stored gradients stay forward-readable. */
    @SerialName("oklab") OKLAB("Perceptual"),
}

/**
 * One gradient stop.
 *
 * [id] is stable and survives reordering, which is the whole point: a stop drag must be able to move a
 * stop *past* its neighbours and still be the same stop when the gesture ends. Identifying a stop by its
 * index instead — the obvious shortcut — silently retargets the drag onto a different stop the moment the
 * order changes, so the color the user was dragging jumps.
 *
 * [position] is `0f..1f` along the gradient. [opacity] multiplies the stop color's own alpha rather than
 * replacing it, so a stop can be both "a 50 % translucent blue" and "faded to 20 % here" without one
 * fact destroying the other.
 */
@Serializable
data class GradientStop(
    val id: String = newStopId(),
    val color: ColorValue,
    val position: Float,
    val opacity: Float = 1f,
) {
    /** The color actually painted at this stop: its own alpha scaled by [opacity]. */
    fun effectiveColor(): ColorValue =
        color.withAlphaFraction(color.alphaFraction * opacity.coerceIn(0f, 1f))

    fun normalized(): GradientStop = copy(
        position = if (position.isFinite()) position.coerceIn(0f, 1f) else 0f,
        opacity = if (opacity.isFinite()) opacity.coerceIn(0f, 1f) else 1f,
    )

    companion object {
        fun newStopId(): String = "stop-${UUID.randomUUID()}"
    }
}

/**
 * A complete gradient.
 *
 * **Ordering.** [stops] is stored in the order the user arranged, and [sortedStops] is the render order.
 * Keeping the authored order rather than eagerly sorting means a stop dragged to an identical position as
 * its neighbour does not swap unpredictably: [sortedStops] is a *stable* sort by position, so equal
 * positions keep their authored sequence. That makes the render deterministic without the model
 * silently rewriting what the user did.
 *
 * [angle] applies to [GradientKind.LINEAR] and [GradientKind.CONIC]; [center] and [radius] apply to
 * radial and conic. A field irrelevant to the current kind is preserved rather than zeroed, so toggling
 * linear → radial → linear returns the user to the angle they had.
 */
@Serializable
data class Gradient(
    val kind: GradientKind = GradientKind.LINEAR,
    val stops: List<GradientStop> = defaultStops(),
    /** Degrees, clockwise from "up". Normalized to `0..360`. */
    val angle: Float = 90f,
    /** Radial/conic center in fractional object coordinates. */
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    /** Radial radius as a fraction of the object's larger side. */
    val radius: Float = 0.5f,
    val interpolation: GradientInterpolation = GradientInterpolation.SRGB,
) {
    /** Render order: stable sort by position, so equal positions keep their authored sequence. */
    val sortedStops: List<GradientStop> get() = stops.sortedBy { it.position }

    val stopCount: Int get() = stops.size

    /**
     * A gradient needs at least two stops to *be* a gradient. This is checked rather than enforced by the
     * constructor because a normalization pass on persisted data must be able to repair it, not reject it.
     */
    val isRenderable: Boolean get() = stops.size >= 2

    fun stopById(id: String): GradientStop? = stops.firstOrNull { it.id == id }

    /**
     * Normalizes into a state that always renders: finite positions, sane angle/center/radius, at least
     * two stops, and no duplicate ids.
     *
     * Ordering is *not* changed — the authored order is user intent. Only genuinely invalid data is
     * repaired, which is what "normalize deterministically" means: same input, same output, and nothing
     * silently rearranged.
     */
    fun normalized(): Gradient {
        val seen = mutableSetOf<String>()
        val cleaned = stops.map { it.normalized() }.map { stop ->
            if (seen.add(stop.id)) stop else stop.copy(id = GradientStop.newStopId()).also { seen.add(it.id) }
        }
        val repaired = when {
            cleaned.isEmpty() -> defaultStops()
            cleaned.size == 1 -> {
                // One stop is a flat color, not a gradient. Mirror it to the far end so the gradient still
                // renders as the color the user chose rather than collapsing to a default ramp.
                val only = cleaned.single()
                val far = if (only.position <= 0.5f) 1f else 0f
                listOf(only, only.copy(id = GradientStop.newStopId(), position = far))
            }
            else -> cleaned
        }
        return copy(
            stops = repaired,
            angle = normalizeAngle(angle),
            centerX = centerX.finiteIn(0f, 1f, 0.5f),
            centerY = centerY.finiteIn(0f, 1f, 0.5f),
            radius = radius.finiteIn(0.01f, 2f, 0.5f),
        )
    }

    // ── Stop editing (each returns a new gradient; one gesture = one commit at the call site) ─────

    /** Adds [stop], keeping it last in authored order. */
    fun addStop(stop: GradientStop): Gradient = copy(stops = stops + stop.normalized())

    /**
     * Adds a stop at [position] whose color is sampled from the gradient there — so inserting a stop in
     * the middle of a ramp does not visibly change the ramp, which is what a user inserting a stop
     * expects. Inventing a default color instead makes every insertion a recolour.
     */
    fun addStopAt(position: Float): Gradient =
        addStop(GradientStop(color = colorAt(position), position = position.coerceIn(0f, 1f)))

    /** Removes [stopId]. Refuses below two stops: a one-stop gradient is not a gradient. */
    fun removeStop(stopId: String): Gradient {
        if (stops.size <= 2) return this
        return copy(stops = stops.filterNot { it.id == stopId })
    }

    fun updateStop(stopId: String, transform: (GradientStop) -> GradientStop): Gradient =
        copy(stops = stops.map { if (it.id == stopId) transform(it).normalized() else it })

    /** Moves [stopId] to [position] — the committed end of a stop drag. */
    fun moveStop(stopId: String, position: Float): Gradient =
        updateStop(stopId) { it.copy(position = position) }

    fun setStopColor(stopId: String, color: ColorValue): Gradient =
        updateStop(stopId) { it.copy(color = color) }

    fun setStopOpacity(stopId: String, opacity: Float): Gradient =
        updateStop(stopId) { it.copy(opacity = opacity) }

    /** Reorders [stopId] to [index] in authored order, leaving positions untouched. */
    fun reorderStop(stopId: String, index: Int): Gradient {
        val current = stops.indexOfFirst { it.id == stopId }
        if (current < 0) return this
        val target = index.coerceIn(0, stops.size - 1)
        if (current == target) return this
        val mutable = stops.toMutableList()
        mutable.add(target, mutable.removeAt(current))
        return copy(stops = mutable)
    }

    /**
     * Mirrors every stop's position and reverses the authored order.
     *
     * Both halves matter: mirroring positions alone would leave the authored list backwards relative to
     * what is drawn, so a subsequent reorder-by-index would move the wrong stop.
     */
    fun reversed(): Gradient = copy(stops = stops.map { it.copy(position = 1f - it.position) }.reversed())

    fun withAngle(degrees: Float): Gradient = copy(angle = normalizeAngle(degrees))

    fun withKind(next: GradientKind): Gradient = copy(kind = next)

    /**
     * Distributes stops evenly across `0..1` in authored order — the "even" action a gradient editor has.
     * With fewer than two stops there is nothing to distribute.
     */
    fun evenlyDistributed(): Gradient {
        if (stops.size < 2) return this
        val step = 1f / (stops.size - 1)
        return copy(stops = stops.mapIndexed { index, stop -> stop.copy(position = index * step) })
    }

    /**
     * The color at [position], interpolating between the surrounding stops.
     *
     * Used for stop insertion and for a non-Compose preview (and therefore unit-testable). Interpolation
     * is done on effective colors, so a stop's own alpha and its opacity both participate.
     */
    fun colorAt(position: Float): ColorValue {
        val ordered = sortedStops
        if (ordered.isEmpty()) return ColorValue.TRANSPARENT
        val p = position.coerceIn(0f, 1f)
        val first = ordered.first()
        if (p <= first.position) return first.effectiveColor()
        val last = ordered.last()
        if (p >= last.position) return last.effectiveColor()
        for (i in 0 until ordered.size - 1) {
            val a = ordered[i]
            val b = ordered[i + 1]
            if (p in a.position..b.position) {
                val span = b.position - a.position
                val t = if (abs(span) < 1e-6f) 0f else (p - a.position) / span
                return lerp(a.effectiveColor(), b.effectiveColor(), t)
            }
        }
        return last.effectiveColor()
    }

    /** Every distinct color referenced by this gradient — what the Document palette indexes. */
    fun colors(): List<ColorValue> = stops.map { it.color }.distinct()

    fun describe(): String = buildString {
        append(kind.label)
        append(" gradient, ${stops.size} stops")
        if (kind != GradientKind.RADIAL) append(", ${angle.toInt()}°")
    }

    companion object {
        fun defaultStops(): List<GradientStop> = listOf(
            GradientStop(color = ColorValue(0x6F, 0x36, 0xFF), position = 0f),
            GradientStop(color = ColorValue(0xD8, 0xC8, 0xFF), position = 1f),
        )

        fun of(from: ColorValue, to: ColorValue, kind: GradientKind = GradientKind.LINEAR): Gradient =
            Gradient(
                kind = kind,
                stops = listOf(
                    GradientStop(color = from, position = 0f),
                    GradientStop(color = to, position = 1f),
                ),
            )

        fun normalizeAngle(degrees: Float): Float {
            if (!degrees.isFinite()) return 0f
            return ((degrees % 360f) + 360f) % 360f
        }

        fun lerp(a: ColorValue, b: ColorValue, t: Float): ColorValue {
            val f = t.coerceIn(0f, 1f)
            fun mix(x: Int, y: Int) = (x + (y - x) * f).toInt().coerceIn(0, 255)
            return ColorValue.of(
                mix(a.red, b.red),
                mix(a.green, b.green),
                mix(a.blue, b.blue),
                mix(a.alpha, b.alpha),
            )
        }
    }
}

/**
 * A color-valued paint: either a flat [ColorValue] or a [Gradient].
 *
 * A sealed pair rather than a nullable gradient beside a color, so a target can never be in the
 * contradictory state "has both a fill color and a fill gradient" and leave the renderer to guess.
 */
@Serializable
sealed interface ColorPaint {
    /** Every color this paint references, for the Document palette. */
    fun colors(): List<ColorValue>

    /** A representative single color — for a swatch chip, or for a target that cannot take a gradient. */
    fun representative(): ColorValue

    fun describe(): String

    @Serializable
    @SerialName("flat")
    data class Flat(val color: ColorValue) : ColorPaint {
        override fun colors(): List<ColorValue> = listOf(color)
        override fun representative(): ColorValue = color
        override fun describe(): String = color.accessibleLabel()
    }

    @Serializable
    @SerialName("gradient")
    data class Ramp(val gradient: Gradient) : ColorPaint {
        override fun colors(): List<ColorValue> = gradient.colors()
        override fun representative(): ColorValue = gradient.colorAt(0.5f)
        override fun describe(): String = gradient.describe()
    }
}

private fun Float.finiteIn(min: Float, max: Float, fallback: Float): Float =
    if (isFinite()) coerceIn(min, max) else fallback
