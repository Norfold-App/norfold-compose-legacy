package com.norfold.app.editor.shape

import com.norfold.app.domain.ShapeKind
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Parametric shapes: the presets a user picks from, and the maths that turns parameters into a path.
 *
 * A primitive is stored as *parameters*, never as its generated outline. That is what makes a star still a
 * star after it is saved and reopened: the point count remains a number the user can drag, instead of a
 * frozen set of ten vertices that can only be node-edited. Convert to Path ([ShapeLayer.convertedToPath])
 * is the deliberate, one-way door from the first form to the second, and it exists precisely so the user
 * chooses when to give up parametric control rather than discovering it was taken from them.
 *
 * Everything generates into the [ShapeCanvas.SIDE] square. The placement box on the page stretches that
 * square, so a rounded rectangle in a wide box has correspondingly wide corner arcs — one predictable rule,
 * applied to every primitive, rather than a per-shape exception that makes resize behave differently
 * depending on what is being resized.
 *
 * Pure-JVM: no Android, no Compose, no randomness that is not seeded.
 */

/**
 * The generator behind each preset.
 *
 * Every legacy [ShapeKind] maps onto one of these ([ShapePrimitiveKind.fromLegacy]), so adopting the
 * canonical model can never be the reason an existing document loses a shape.
 */
@Serializable
enum class ShapePrimitiveKind(val displayName: String, val category: ShapePrimitiveCategory) {
    RECTANGLE("Rectangle", ShapePrimitiveCategory.BASIC),
    ROUNDED_RECTANGLE("Rounded rectangle", ShapePrimitiveCategory.BASIC),
    SQUIRCLE("Squircle", ShapePrimitiveCategory.BASIC),
    ELLIPSE("Ellipse", ShapePrimitiveCategory.BASIC),
    TRIANGLE("Triangle", ShapePrimitiveCategory.BASIC),
    DIAMOND("Diamond", ShapePrimitiveCategory.BASIC),
    POLYGON("Polygon", ShapePrimitiveCategory.BASIC),
    STAR("Star", ShapePrimitiveCategory.BASIC),
    BLOB("Blob", ShapePrimitiveCategory.ORGANIC),
    LINE("Line", ShapePrimitiveCategory.BASIC),
    ARROW("Arrow", ShapePrimitiveCategory.BASIC),
    SPEECH_BUBBLE("Speech bubble", ShapePrimitiveCategory.BASIC),
    ;

    /** True when the outline is a closed region that can be filled. A line strokes but never fills. */
    val fillable: Boolean get() = this != LINE

    companion object {
        fun fromLegacy(kind: ShapeKind): ShapePrimitiveKind = when (kind) {
            ShapeKind.Rectangle -> RECTANGLE
            ShapeKind.RoundedRectangle -> ROUNDED_RECTANGLE
            ShapeKind.Ellipse -> ELLIPSE
            ShapeKind.Triangle -> TRIANGLE
            ShapeKind.Diamond -> DIAMOND
            ShapeKind.Arrow -> ARROW
            ShapeKind.Line -> LINE
            ShapeKind.Star -> STAR
            ShapeKind.SpeechBubble -> SPEECH_BUBBLE
            ShapeKind.Hexagon -> POLYGON
        }
    }
}

/** Preset grouping, matching the browser's sections. */
@Serializable
enum class ShapePrimitiveCategory(val displayName: String) {
    BASIC("Basic"),
    ORGANIC("Organic"),

    /** Not a generator: the group a Boolean result or a converted path is listed under. */
    COMPOUND("Compound"),
}

/**
 * Every knob a primitive can expose, in one type.
 *
 * One shared parameter set rather than a per-kind one means changing preset keeps the settings that still
 * make sense — switching Star to Polygon keeps the point count the user just dialled in, instead of
 * resetting it to a default because the two shapes happened to store it in different fields.
 * [ShapePrimitiveKind.parametersUsedBy] is what the UI reads to decide which sliders to show, so an
 * irrelevant knob is hidden rather than shown doing nothing.
 */
@Serializable
data class ShapeParameters(
    /** Corner rounding as a fraction of the canvas side; clamped per-corner to what the edges allow. */
    val cornerRadius: Float = 0.18f,
    /** Superellipse continuity for [ShapePrimitiveKind.SQUIRCLE]: 0 is a rounded rect, 1 is fully smooth. */
    val smoothness: Float = 0.6f,
    /** Sides of a polygon, or points of a star. */
    val pointCount: Int = 6,
    /** Star inner radius as a fraction of the outer radius. */
    val innerRadius: Float = 0.5f,
    /** Barrel (positive) or pincushion (negative) distortion about the canvas centre. */
    val warp: Float = 0f,
    /** Curve tension for organic shapes: 0 is a polygon through the points, 1 is fully round. */
    val tension: Float = 1f,
    /** Rotation applied inside the local canvas, in degrees. Independent of the object's page rotation. */
    val rotation: Float = 0f,
    /** Deterministic variation for [ShapePrimitiveKind.BLOB]. Same seed, same blob, on every device. */
    val seed: Int = 1,
) {
    fun normalized() = ShapeParameters(
        cornerRadius = cornerRadius.finiteOr(0f).coerceIn(0f, MAX_CORNER_FRACTION),
        smoothness = smoothness.finiteOr(0f).coerceIn(0f, 1f),
        pointCount = pointCount.coerceIn(MIN_POINTS, MAX_POINTS),
        innerRadius = innerRadius.finiteOr(0.5f).coerceIn(MIN_INNER_RADIUS, 1f),
        warp = warp.finiteOr(0f).coerceIn(-1f, 1f),
        tension = tension.finiteOr(1f).coerceIn(0f, 2f),
        rotation = (((rotation.finiteOr(0f) % 360f) + 360f) % 360f),
        seed = seed,
    )

    companion object {
        const val MAX_CORNER_FRACTION = 0.5f
        const val MIN_POINTS = 3
        const val MAX_POINTS = 64

        /** A star whose inner radius reaches zero is a set of spikes with no interior; keep it a shape. */
        const val MIN_INNER_RADIUS = 0.05f
    }
}

/** Which knobs actually affect this kind, so the UI can hide the rest rather than show inert controls. */
val ShapePrimitiveKind.parametersUsedBy: Set<ShapeParameterKey>
    get() = when (this) {
        ShapePrimitiveKind.RECTANGLE -> setOf(ShapeParameterKey.WARP, ShapeParameterKey.ROTATION)
        ShapePrimitiveKind.ROUNDED_RECTANGLE -> setOf(
            ShapeParameterKey.CORNER_RADIUS, ShapeParameterKey.WARP, ShapeParameterKey.ROTATION,
        )
        ShapePrimitiveKind.SQUIRCLE -> setOf(
            ShapeParameterKey.CORNER_RADIUS, ShapeParameterKey.SMOOTHNESS,
            ShapeParameterKey.WARP, ShapeParameterKey.ROTATION,
        )
        ShapePrimitiveKind.ELLIPSE -> setOf(ShapeParameterKey.WARP, ShapeParameterKey.ROTATION)
        ShapePrimitiveKind.TRIANGLE, ShapePrimitiveKind.DIAMOND -> setOf(
            ShapeParameterKey.CORNER_RADIUS, ShapeParameterKey.WARP, ShapeParameterKey.ROTATION,
        )
        ShapePrimitiveKind.POLYGON -> setOf(
            ShapeParameterKey.POINT_COUNT, ShapeParameterKey.CORNER_RADIUS,
            ShapeParameterKey.WARP, ShapeParameterKey.ROTATION,
        )
        ShapePrimitiveKind.STAR -> setOf(
            ShapeParameterKey.POINT_COUNT, ShapeParameterKey.INNER_RADIUS, ShapeParameterKey.CORNER_RADIUS,
            ShapeParameterKey.WARP, ShapeParameterKey.ROTATION,
        )
        ShapePrimitiveKind.BLOB -> setOf(
            ShapeParameterKey.POINT_COUNT, ShapeParameterKey.TENSION, ShapeParameterKey.WARP,
            ShapeParameterKey.SEED, ShapeParameterKey.ROTATION,
        )
        ShapePrimitiveKind.LINE -> setOf(ShapeParameterKey.ROTATION)
        ShapePrimitiveKind.ARROW -> setOf(ShapeParameterKey.WARP, ShapeParameterKey.ROTATION)
        ShapePrimitiveKind.SPEECH_BUBBLE -> setOf(ShapeParameterKey.CORNER_RADIUS, ShapeParameterKey.ROTATION)
    }

@Serializable
enum class ShapeParameterKey(val displayName: String) {
    CORNER_RADIUS("Corner radius"),
    SMOOTHNESS("Smoothness"),
    POINT_COUNT("Points"),
    INNER_RADIUS("Inner radius"),
    WARP("Warp"),
    TENSION("Tension"),
    ROTATION("Rotation"),
    SEED("Variation"),
}

/** A preset the browser offers: a kind plus the parameters that make it that named shape. */
@Serializable
data class ShapePreset(
    val id: String,
    val displayName: String,
    val kind: ShapePrimitiveKind,
    val parameters: ShapeParameters = ShapeParameters(),
) {
    val category: ShapePrimitiveCategory get() = kind.category
    fun toPath(): ShapePath = ShapePrimitives.generate(kind, parameters)
}

/**
 * The generators.
 *
 * Every one produces a path in the local canvas with the same orientation and starting corner, so a
 * morph between two presets (and a diff of two documents) lines up rather than appearing to jump.
 */
object ShapePrimitives {

    /** Cubic approximation constant for a quarter circle: `4/3 · (√2 − 1)`. */
    const val KAPPA = 0.5522847f

    fun generate(kind: ShapePrimitiveKind, parameters: ShapeParameters = ShapeParameters()): ShapePath {
        val p = parameters.normalized()
        val path = when (kind) {
            ShapePrimitiveKind.RECTANGLE -> rectangle(0f)
            ShapePrimitiveKind.ROUNDED_RECTANGLE -> rectangle(p.cornerRadius)
            ShapePrimitiveKind.SQUIRCLE -> squircle(p.cornerRadius, p.smoothness)
            ShapePrimitiveKind.ELLIPSE -> ellipse()
            ShapePrimitiveKind.TRIANGLE -> regularPolygon(3, p.cornerRadius)
            ShapePrimitiveKind.DIAMOND -> regularPolygon(4, p.cornerRadius)
            ShapePrimitiveKind.POLYGON -> regularPolygon(p.pointCount, p.cornerRadius)
            ShapePrimitiveKind.STAR -> star(p.pointCount, p.innerRadius, p.cornerRadius)
            ShapePrimitiveKind.BLOB -> blob(p.pointCount, p.seed, p.tension)
            ShapePrimitiveKind.LINE -> line()
            ShapePrimitiveKind.ARROW -> arrow()
            ShapePrimitiveKind.SPEECH_BUBBLE -> speechBubble(p.cornerRadius)
        }
        return path.warped(p.warp).rotated(p.rotation).normalized().withDerivedIds()
    }

    /**
     * Gives generated geometry ids derived from its position in the output, not from [UUID].
     *
     * A generated node has no identity of its own beyond "the nth node of this description": ask for the
     * same hexagon twice and you should get the same nodes, because nothing about the shape has changed.
     * Minting random ids here breaks that, and the breakage is not cosmetic — a primitive layer generates
     * its path on every read, so a node reference taken from one read (a tap, a selection, a drag in
     * progress) names ids that the very next read has already replaced. The selection would evaporate
     * between the tap and the move.
     *
     * Deriving them also makes Convert to Path identity-preserving: the ids the user's selection is
     * holding survive the conversion, so converting mid-edit does not deselect what they were editing.
     *
     * Uniqueness is by construction — indices within one path — which is the only scope a node ref has.
     */
    private fun ShapePath.withDerivedIds(): ShapePath = copy(
        subpaths = subpaths.mapIndexed { subpathIndex, subpath ->
            subpath.copy(
                id = "sp-g$subpathIndex",
                nodes = subpath.nodes.mapIndexed { nodeIndex, node -> node.copy(id = "nd-g$subpathIndex-$nodeIndex") },
            )
        },
    )

    /** The presets the browser lists, in the order it lists them. */
    val presets: List<ShapePreset> = listOf(
        ShapePreset("square", "Square", ShapePrimitiveKind.RECTANGLE),
        ShapePreset("rounded", "Rounded", ShapePrimitiveKind.ROUNDED_RECTANGLE, ShapeParameters(cornerRadius = 0.18f)),
        ShapePreset("squircle", "Squircle", ShapePrimitiveKind.SQUIRCLE, ShapeParameters(cornerRadius = 0.42f, smoothness = 0.7f)),
        ShapePreset("circle", "Circle", ShapePrimitiveKind.ELLIPSE),
        ShapePreset("triangle", "Triangle", ShapePrimitiveKind.TRIANGLE, ShapeParameters(cornerRadius = 0f)),
        ShapePreset("diamond", "Diamond", ShapePrimitiveKind.DIAMOND, ShapeParameters(cornerRadius = 0f)),
        ShapePreset("pentagon", "Pentagon", ShapePrimitiveKind.POLYGON, ShapeParameters(pointCount = 5, cornerRadius = 0f)),
        ShapePreset("hexagon", "Hexagon", ShapePrimitiveKind.POLYGON, ShapeParameters(pointCount = 6, cornerRadius = 0f)),
        ShapePreset("star", "Star", ShapePrimitiveKind.STAR, ShapeParameters(pointCount = 5, innerRadius = 0.42f)),
        ShapePreset("burst", "Burst", ShapePrimitiveKind.STAR, ShapeParameters(pointCount = 12, innerRadius = 0.72f)),
        ShapePreset("blob", "Blob", ShapePrimitiveKind.BLOB, ShapeParameters(pointCount = 6, seed = 1)),
        ShapePreset("pebble", "Pebble", ShapePrimitiveKind.BLOB, ShapeParameters(pointCount = 5, seed = 7, warp = 0.1f)),
        ShapePreset("line", "Line", ShapePrimitiveKind.LINE),
        ShapePreset("arrow", "Arrow", ShapePrimitiveKind.ARROW),
        ShapePreset("bubble", "Speech bubble", ShapePrimitiveKind.SPEECH_BUBBLE, ShapeParameters(cornerRadius = 0.16f)),
    )

    fun preset(id: String): ShapePreset? = presets.firstOrNull { it.id == id }

    // ── Generators ───────────────────────────────────────────────────────────────────────────────

    private fun rectangle(cornerFraction: Float): ShapePath {
        val s = ShapeCanvas.SIDE
        val corners = listOf(
            ShapePoint(0f, 0f), ShapePoint(s, 0f), ShapePoint(s, s), ShapePoint(0f, s),
        )
        return ShapePath.of(roundedRing(corners, cornerFraction * s))
    }

    /**
     * A superellipse-flavoured rounded rectangle.
     *
     * [smoothness] lengthens the corner handles and pulls the trim points further along each edge, which is
     * what turns a circular-arc corner into the continuous-curvature corner people recognise as a squircle.
     * At `smoothness = 0` it is exactly [rectangle], so the two presets are the ends of one continuum
     * rather than two unrelated shapes that happen to look similar.
     */
    private fun squircle(cornerFraction: Float, smoothness: Float): ShapePath {
        val s = ShapeCanvas.SIDE
        val radius = (cornerFraction * s).coerceIn(0f, s / 2f)
        if (radius <= 0f) return rectangle(0f)
        // Beyond the arc's own trim distance, smoothness spends up to another 60% of the corner extending
        // the transition into the straight edge. Capped so opposite corners cannot overlap.
        val trim = min(radius * (1f + smoothness * 0.6f), s / 2f)
        val handle = radius * KAPPA * (1f + smoothness * 0.55f)
        val nodes = listOf(
            // Top edge, left to right.
            node(ShapePoint(trim, 0f), ShapePoint(-handle, 0f), ShapePoint(0f, 0f)),
            node(ShapePoint(s - trim, 0f), ShapePoint(0f, 0f), ShapePoint(handle, 0f)),
            // Right edge.
            node(ShapePoint(s, trim), ShapePoint(0f, -handle), ShapePoint(0f, 0f)),
            node(ShapePoint(s, s - trim), ShapePoint(0f, 0f), ShapePoint(0f, handle)),
            // Bottom edge, right to left.
            node(ShapePoint(s - trim, s), ShapePoint(handle, 0f), ShapePoint(0f, 0f)),
            node(ShapePoint(trim, s), ShapePoint(0f, 0f), ShapePoint(-handle, 0f)),
            // Left edge.
            node(ShapePoint(0f, s - trim), ShapePoint(0f, handle), ShapePoint(0f, 0f)),
            node(ShapePoint(0f, trim), ShapePoint(0f, 0f), ShapePoint(0f, -handle)),
        )
        return ShapePath.of(ShapeSubpath(nodes = nodes, closed = true))
    }

    private fun ellipse(): ShapePath {
        val r = ShapeCanvas.SIDE / 2f
        val c = ShapeCanvas.SIDE / 2f
        val k = r * KAPPA
        val nodes = listOf(
            node(ShapePoint(c, 0f), ShapePoint(-k, 0f), ShapePoint(k, 0f)),
            node(ShapePoint(ShapeCanvas.SIDE, c), ShapePoint(0f, -k), ShapePoint(0f, k)),
            node(ShapePoint(c, ShapeCanvas.SIDE), ShapePoint(k, 0f), ShapePoint(-k, 0f)),
            node(ShapePoint(0f, c), ShapePoint(0f, k), ShapePoint(0f, -k)),
        ).map { it.copy(type = ShapeNodeType.SYMMETRIC) }
        return ShapePath.of(ShapeSubpath(nodes = nodes, closed = true))
    }

    /** A regular n-gon inscribed in the canvas, first vertex at the top. */
    private fun regularPolygon(sides: Int, cornerFraction: Float): ShapePath {
        val n = sides.coerceIn(ShapeParameters.MIN_POINTS, ShapeParameters.MAX_POINTS)
        val r = ShapeCanvas.SIDE / 2f
        val points = (0 until n).map { i ->
            val angle = -PI / 2.0 + 2.0 * PI * i / n
            ShapePoint(r + r * cos(angle).toFloat(), r + r * sin(angle).toFloat())
        }
        return ShapePath.of(roundedRing(points, cornerFraction * ShapeCanvas.SIDE))
    }

    private fun star(points: Int, innerRatio: Float, cornerFraction: Float): ShapePath {
        val n = points.coerceIn(ShapeParameters.MIN_POINTS, ShapeParameters.MAX_POINTS)
        val outer = ShapeCanvas.SIDE / 2f
        val inner = outer * innerRatio.coerceIn(ShapeParameters.MIN_INNER_RADIUS, 1f)
        val ring = (0 until n * 2).map { i ->
            val radius = if (i % 2 == 0) outer else inner
            val angle = -PI / 2.0 + PI * i / n
            ShapePoint(outer + radius * cos(angle).toFloat(), outer + radius * sin(angle).toFloat())
        }
        return ShapePath.of(roundedRing(ring, cornerFraction * ShapeCanvas.SIDE))
    }

    /**
     * A closed organic curve through deterministically jittered points.
     *
     * The jitter comes from [ShapeNoise], not from a random source, so the same seed produces the same blob
     * on every device and in every session — a shape that redrew itself differently after a reload would
     * not be a shape, it would be a slot machine.
     */
    private fun blob(points: Int, seed: Int, tension: Float): ShapePath {
        val n = points.coerceIn(ShapeParameters.MIN_POINTS, ShapeParameters.MAX_POINTS)
        val center = ShapeCanvas.SIDE / 2f
        val ring = (0 until n).map { i ->
            val angle = -PI / 2.0 + 2.0 * PI * i / n
            // 0.62..1.0 of the half-side: enough variation to read as organic, never so much that a lobe
            // collapses through the centre and self-intersects.
            val radius = center * (0.62f + 0.38f * ShapeNoise.unit(seed, i))
            ShapePoint(center + radius * cos(angle).toFloat(), center + radius * sin(angle).toFloat())
        }
        return ShapePath.of(smoothRing(ring, tension))
    }

    private fun line(): ShapePath = ShapePath.of(
        ShapeSubpath(
            nodes = listOf(
                ShapeNode.at(0f, ShapeCanvas.SIDE / 2f),
                ShapeNode.at(ShapeCanvas.SIDE, ShapeCanvas.SIDE / 2f),
            ),
            closed = false,
        ),
    )

    private fun arrow(): ShapePath {
        val s = ShapeCanvas.SIDE
        val shaftTop = s * 0.32f
        val shaftBottom = s * 0.68f
        val headStart = s * 0.58f
        return ShapePath.of(
            ShapeSubpath.polygon(
                listOf(
                    ShapePoint(0f, shaftTop),
                    ShapePoint(headStart, shaftTop),
                    ShapePoint(headStart, s * 0.1f),
                    ShapePoint(s, s / 2f),
                    ShapePoint(headStart, s * 0.9f),
                    ShapePoint(headStart, shaftBottom),
                    ShapePoint(0f, shaftBottom),
                ),
            ),
        )
    }

    /** A rounded body plus a tail, as one subpath so fill and stroke treat it as a single outline. */
    private fun speechBubble(cornerFraction: Float): ShapePath {
        val s = ShapeCanvas.SIDE
        val bodyBottom = s * 0.74f
        val radius = (cornerFraction * s).coerceIn(0f, min(s, bodyBottom) / 2f)
        val body = listOf(
            ShapePoint(0f, 0f), ShapePoint(s, 0f), ShapePoint(s, bodyBottom),
            ShapePoint(s * 0.44f, bodyBottom), ShapePoint(s * 0.26f, s),
            ShapePoint(s * 0.26f, bodyBottom), ShapePoint(0f, bodyBottom),
        )
        // Only the four body corners round; rounding the tail would blunt the point that makes it a tail.
        return ShapePath.of(roundedRing(body, radius, roundable = setOf(0, 1, 2, 6)))
    }

    // ── Shared construction helpers ──────────────────────────────────────────────────────────────

    private fun node(point: ShapePoint, controlIn: ShapePoint, controlOut: ShapePoint) =
        ShapeNode(point = point, controlIn = controlIn, controlOut = controlOut, type = ShapeNodeType.SMOOTH)

    /**
     * A closed ring with circular-arc corners of [radius], sharp where [radius] is zero.
     *
     * Each corner is trimmed back along both adjacent edges and bridged with the exact cubic approximation
     * of the arc: for a turn of φ, the handle runs `(4/3)·tan(φ/4) / tan(φ/2)` of the way from the trim
     * point back to the original vertex. At φ = 90° that is [KAPPA], so a rounded rectangle's corners are
     * the same quarter circles a dedicated rectangle generator would emit.
     *
     * The radius is clamped per corner to half the shorter adjacent edge, so two large radii on a short
     * edge meet rather than overshooting into each other and inverting the outline.
     */
    private fun roundedRing(
        points: List<ShapePoint>,
        radius: Float,
        roundable: Set<Int>? = null,
    ): ShapeSubpath {
        if (points.size < 3 || radius <= 0f) return ShapeSubpath.polygon(points)
        val n = points.size
        val nodes = ArrayList<ShapeNode>(n * 2)
        for (i in 0 until n) {
            val current = points[i]
            val previous = points[(i - 1 + n) % n]
            val next = points[(i + 1) % n]
            if (roundable != null && i !in roundable) {
                nodes += ShapeNode(point = current, type = ShapeNodeType.CORNER)
                continue
            }
            val toPrevious = (previous - current)
            val toNext = (next - current)
            val limit = min(toPrevious.length, toNext.length) / 2f
            val trim = min(radius, limit)
            if (trim <= 1e-4f) {
                nodes += ShapeNode(point = current, type = ShapeNodeType.CORNER)
                continue
            }
            val entry = current + toPrevious.withLength(trim)
            val exit = current + toNext.withLength(trim)
            val handle = arcHandleFraction(toPrevious.unit(), toNext.unit()) * trim
            nodes += ShapeNode(
                point = entry,
                controlIn = ShapePoint(),
                controlOut = (current - entry).withLength(handle),
                type = ShapeNodeType.CORNER,
            )
            nodes += ShapeNode(
                point = exit,
                controlIn = (current - exit).withLength(handle),
                controlOut = ShapePoint(),
                type = ShapeNodeType.CORNER,
            )
        }
        return ShapeSubpath(nodes = nodes, closed = true)
    }

    /**
     * How far along the trim-to-vertex line the arc's control point sits, as a fraction of the trim
     * distance. Derived from the turn angle so any corner angle — not just 90° — gets a true arc.
     */
    private fun arcHandleFraction(toPrevious: ShapePoint, toNext: ShapePoint): Float {
        val cosInterior = (toPrevious.x * toNext.x + toPrevious.y * toNext.y).coerceIn(-1f, 1f)
        val interior = kotlin.math.acos(cosInterior)
        val turn = (PI - interior).coerceIn(1e-3, PI - 1e-3)
        val fraction = (4.0 / 3.0) * tan(turn / 4.0) / tan(turn / 2.0)
        return fraction.toFloat().finiteOr(KAPPA).coerceIn(0f, 1f)
    }

    /**
     * A closed curve *through* every point, using the Catmull-Rom form of a cubic spline.
     *
     * Interpolating rather than approximating matters here: the points are the shape's silhouette, and a
     * curve that merely approaches them would make the point count slider look like it does nothing at
     * high counts. [tension] at 1 gives the standard uniform spline; 0 collapses to the polygon.
     */
    private fun smoothRing(points: List<ShapePoint>, tension: Float): ShapeSubpath {
        if (points.size < 3) return ShapeSubpath.polygon(points)
        val n = points.size
        val scale = tension.coerceIn(0f, 2f) / 6f
        val nodes = points.mapIndexed { i, point ->
            val previous = points[(i - 1 + n) % n]
            val next = points[(i + 1) % n]
            val tangent = (next - previous) * scale
            ShapeNode(point = point, controlIn = -tangent, controlOut = tangent, type = ShapeNodeType.SYMMETRIC)
        }
        return ShapeSubpath(nodes = nodes, closed = true)
    }
}

/**
 * Barrel/pincushion distortion about the canvas centre.
 *
 * Positive [amount] pushes interior geometry outward — the "puffy" look — while leaving the extremities
 * almost fixed, so a warped shape still fills its box rather than shrinking away from the handles the user
 * is holding. Negative pinches. Zero is exactly the identity, checked rather than computed, so the common
 * case costs nothing and cannot introduce rounding drift.
 */
fun ShapePath.warped(amount: Float): ShapePath {
    val clamped = amount.finiteOr(0f).coerceIn(-1f, 1f)
    if (clamped == 0f) return this
    val center = ShapeCanvas.CENTER
    val maxRadius = ShapeCanvas.SIDE / 2f
    return mapPoints { point ->
        val offset = point - center
        val radius = offset.length
        if (radius <= 1e-4f) point
        else {
            val normalized = (radius / maxRadius).coerceIn(0f, 1f)
            val factor = 1f + clamped * 0.35f * (1f - normalized * normalized)
            center + offset * factor
        }
    }
}

/** Rotation *inside* the local canvas, about its centre. Independent of the object's rotation on the page. */
fun ShapePath.rotated(degrees: Float): ShapePath {
    val clean = degrees.finiteOr(0f)
    if (clean % 360f == 0f) return this
    val radians = clean * PI.toFloat() / 180f
    val cosine = cos(radians)
    val sine = sin(radians)
    val center = ShapeCanvas.CENTER
    return mapPoints { point ->
        val offset = point - center
        center + ShapePoint(offset.x * cosine - offset.y * sine, offset.x * sine + offset.y * cosine)
    }
}

/**
 * Deterministic pseudo-random values for organic shapes.
 *
 * A hash rather than a generator: [unit] is a pure function of (seed, index), so it needs no state, is
 * identical across platforms and JVM versions, and is unaffected by the order in which callers ask for
 * values. `java.util.Random` would satisfy the first two but not the third, which is exactly the property
 * that keeps a blob stable while its point count is being dragged.
 */
object ShapeNoise {
    /** A value in `0f..1f`. */
    fun unit(seed: Int, index: Int): Float = (hash(seed, index) ushr 8).toFloat() / (1 shl 24).toFloat()

    private fun hash(seed: Int, index: Int): Int {
        var h = seed * 0x9E3779B1.toInt() xor (index + 0x85EBCA6B.toInt())
        h = h xor (h ushr 16)
        h *= 0x7FEB352D
        h = h xor (h ushr 15)
        h *= 0x846CA68B.toInt()
        h = h xor (h ushr 16)
        return h and 0x7FFFFFFF
    }
}
