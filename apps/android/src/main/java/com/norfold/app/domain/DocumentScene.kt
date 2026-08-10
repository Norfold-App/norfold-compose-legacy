package com.norfold.app.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The bounded-page document model (roadmap Phase 2).
 *
 * Three things are deliberately kept apart, because conflating any two of them is what made the legacy
 * document editor unable to grow:
 *
 *  - **content truth** — an ordinary [DocumentBlock]. A paragraph on a page is the *same* paragraph the
 *    Flow editor understands, with the same id and the same payload. Nothing about being placed on a page
 *    changes what the content *is*;
 *  - **scene placement** — [SceneObject]. Where that content sits, how big it is, how it is rotated, which
 *    layer it is on, and whether it is locked. This wraps content rather than mutating it, which is the
 *    whole reason the legacy `FreeformPlacement` side-map could drift out of sync with the blocks it
 *    described: a placement whose block had been deleted simply lingered, and a block whose placement was
 *    missing silently jumped to the origin. A wrapper cannot desynchronize from the thing it wraps;
 *  - **page structure** — [DocumentPageBlock]. The physical sheet: its geometry, margins, columns and the
 *    ordered objects it owns.
 *
 * **Why pages are top-level blocks.** The repository persists one row per top-level block and the canonical
 * History Engine's operations are top-level-block granular. Making a page a block therefore buys durable
 * per-page rows, atomic undo, recovery-snapshot coverage and the verbatim unknown-payload round trip from
 * machinery that is already proven — instead of a parallel scene store with its own persistence, its own
 * undo stack and its own migration story. Objects nest *inside* their page for the same structural reason:
 * page ownership is then a fact about the tree rather than a foreign key that reordering can invalidate.
 *
 * **Why this does not bump [BlockDocumentJson.SCHEMA_VERSION].** These are new block kinds, not a
 * reinterpretation of existing ones. An older build that meets a `page` block cannot decode it, so it
 * degrades to [UnknownBlock] and writes the payload back verbatim — content is preserved without the older
 * build having to understand it. A version bump would instead make every *existing* block unreadable to
 * that same build, which is a migration nobody needs.
 *
 * Geometry is in **document points** (72 per inch), matching [DocCanvasSpec], PDF and CSS print units, so
 * it is independent of screen density and exports without a conversion step.
 */

/** Points per millimetre. Page limits are specified in millimetres; geometry is stored in points. */
const val POINTS_PER_MM: Float = 72f / 25.4f

/** Canonical custom-page limits. Both apply: a page must satisfy the side limit *and* the area limit. */
object PageLimits {
    /** No page side may exceed 2000 mm. */
    const val MAX_SIDE_MM: Float = 2000f

    /** No page may exceed 2 m² (2,000,000 mm²). */
    const val MAX_AREA_MM2: Float = 2_000_000f

    /** Below this a page cannot hold anything meaningful, and zero/negative sizes break layout maths. */
    const val MIN_SIDE_MM: Float = 10f

    val MAX_SIDE_PT: Float get() = MAX_SIDE_MM * POINTS_PER_MM
    val MIN_SIDE_PT: Float get() = MIN_SIDE_MM * POINTS_PER_MM
    val MAX_AREA_PT2: Float get() = MAX_AREA_MM2 * POINTS_PER_MM * POINTS_PER_MM

    /**
     * The largest page not exceeding either limit that keeps [widthPt] × [heightPt]'s aspect ratio.
     *
     * Sides are clamped first, then — if the result is still too large in area — both sides are scaled by
     * the same factor. Scaling proportionally rather than truncating one side means an over-large page
     * arrives at a legal size that still looks like the page the user asked for, which matters because this
     * runs on *persisted* data during migration, not only on fresh user input.
     */
    fun fit(widthPt: Float, heightPt: Float): Pair<Float, Float> {
        val w = widthPt.coerceIn(MIN_SIDE_PT, MAX_SIDE_PT).takeIf { it.isFinite() } ?: MIN_SIDE_PT
        val h = heightPt.coerceIn(MIN_SIDE_PT, MAX_SIDE_PT).takeIf { it.isFinite() } ?: MIN_SIDE_PT
        val area = w * h
        if (area <= MAX_AREA_PT2) return w to h
        val scale = sqrt(MAX_AREA_PT2 / area)
        return (w * scale).coerceAtLeast(MIN_SIDE_PT) to (h * scale).coerceAtLeast(MIN_SIDE_PT)
    }
}

@Serializable
enum class PageOrientation { Portrait, Landscape }

/** Uniform page margins in points. */
@Serializable
data class PageMargins(
    val top: Float = 72f,
    val right: Float = 72f,
    val bottom: Float = 72f,
    val left: Float = 72f,
) {
    fun normalized(maxWidth: Float, maxHeight: Float): PageMargins {
        // Margins may never consume the whole sheet: a page with no content box cannot be laid out at all,
        // and a persisted document with absurd margins must still open.
        val maxH = (maxWidth / 2f - 1f).coerceAtLeast(0f)
        val maxV = (maxHeight / 2f - 1f).coerceAtLeast(0f)
        return PageMargins(
            top = top.coerceIn(0f, maxV),
            right = right.coerceIn(0f, maxH),
            bottom = bottom.coerceIn(0f, maxV),
            left = left.coerceIn(0f, maxH),
        )
    }

    companion object {
        val NONE = PageMargins(0f, 0f, 0f, 0f)
    }
}

/** Multi-column text flow within the content box. [count] of 1 means a single column. */
@Serializable
data class PageColumns(val count: Int = 1, val gutter: Float = 18f) {
    fun normalized() = PageColumns(count = count.coerceIn(1, 12), gutter = gutter.coerceIn(0f, 144f))
}

/** Optional printed border drawn [inset] points inside the page edge. Null [color] means no border. */
@Serializable
data class PageBorder(val color: Long? = null, val width: Float = 1f, val inset: Float = 0f) {
    val isVisible: Boolean get() = color != null && width > 0f
    fun normalized() = PageBorder(color = color, width = width.coerceIn(0f, 48f), inset = inset.coerceIn(0f, 144f))
}

/** Design grid and guide metadata. Guides are authoring aids and are never printed or exported. */
@Serializable
data class PageGrid(
    val visible: Boolean = false,
    val spacing: Float = 36f,
    val subdivisions: Int = 2,
    val snapToGrid: Boolean = false,
    /** Custom guide positions in points, measured from the page's left/top edge respectively. */
    val verticalGuides: List<Float> = emptyList(),
    val horizontalGuides: List<Float> = emptyList(),
) {
    fun normalized() = PageGrid(
        visible = visible,
        spacing = spacing.coerceIn(2f, 288f),
        subdivisions = subdivisions.coerceIn(1, 10),
        snapToGrid = snapToGrid,
        verticalGuides = verticalGuides.filter { it.isFinite() },
        horizontalGuides = horizontalGuides.filter { it.isFinite() },
    )
}

/**
 * Print-production metadata: [bleed] extends artwork past the trim edge, [safeArea] keeps content away from
 * it. Phase 2 stores and round-trips these; the export pipeline that consumes them arrives in Phase 8.
 */
@Serializable
data class PageBleed(val bleed: Float = 0f, val safeArea: Float = 0f) {
    fun normalized() = PageBleed(bleed = bleed.coerceIn(0f, 144f), safeArea = safeArea.coerceIn(0f, 144f))
}

/**
 * Header/footer band metadata. A seam: Phase 2 reserves and round-trips the bands and their text, so page
 * geometry and the content box are already correct; rich header/footer *content* is a later phase.
 */
@Serializable
data class PageHeaderFooter(
    val headerHeight: Float = 0f,
    val footerHeight: Float = 0f,
    val headerText: String = "",
    val footerText: String = "",
    /** A section's first page commonly suppresses the running head. */
    val differentFirstPage: Boolean = false,
) {
    val hasHeader: Boolean get() = headerHeight > 0f || headerText.isNotBlank()
    val hasFooter: Boolean get() = footerHeight > 0f || footerText.isNotBlank()

    fun normalized(maxHeight: Float): PageHeaderFooter {
        val limit = (maxHeight / 3f).coerceAtLeast(0f)
        return copy(
            headerHeight = headerHeight.coerceIn(0f, limit),
            footerHeight = footerHeight.coerceIn(0f, limit),
        )
    }
}

@Serializable
enum class PageNumberFormat {
    Decimal,
    RomanLower,
    RomanUpper,
    AlphaLower,
    AlphaUpper,
    ;

    /** How this format reads in Page Control — an example, because "RomanLower" is not what a user calls it. */
    fun displayLabel(): String = when (this) {
        Decimal -> "1, 2, 3"
        RomanLower -> "i, ii, iii"
        RomanUpper -> "I, II, III"
        AlphaLower -> "a, b, c"
        AlphaUpper -> "A, B, C"
    }

    /**
     * [number] rendered in this format. Used by the page-number band, so what is drawn matches the format
     * the user chose rather than always being decimal.
     */
    fun format(number: Int): String {
        if (number <= 0) return number.toString()
        return when (this) {
            Decimal -> number.toString()
            RomanLower -> roman(number).lowercase()
            RomanUpper -> roman(number)
            AlphaLower -> alpha(number).lowercase()
            AlphaUpper -> alpha(number)
        }
    }

    private fun roman(value: Int): String {
        // Beyond the classical range the numeral becomes unreadably long, so it degrades to digits rather
        // than printing a page number nobody can parse.
        if (value > 3999) return value.toString()
        val numerals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val symbols = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var remaining = value
        return buildString {
            numerals.forEachIndexed { index, numeral ->
                while (remaining >= numeral) {
                    append(symbols[index])
                    remaining -= numeral
                }
            }
        }
    }

    /** Spreadsheet-style: 1→A, 26→Z, 27→AA. Wrapping at Z would give two pages the same label. */
    private fun alpha(value: Int): String {
        var remaining = value
        return buildString {
            while (remaining > 0) {
                val index = (remaining - 1) % 26
                insert(0, ('A' + index))
                remaining = (remaining - 1) / 26
            }
        }
    }
}

/**
 * Numbering and sectioning metadata. [startsSection] is what makes "This section" a real Page Control
 * scope: a section runs from a page that starts one up to (not including) the next such page.
 */
@Serializable
data class PageNumbering(
    val startsSection: Boolean = false,
    val sectionName: String = "",
    /** Restarts numbering at this value when this page starts a section; null continues the sequence. */
    val restartAt: Int? = null,
    val format: PageNumberFormat = PageNumberFormat.Decimal,
    val showNumber: Boolean = false,
) {
    fun normalized() = copy(restartAt = restartAt?.coerceIn(0, 100_000))
}

/**
 * The complete physical description of one sheet.
 *
 * [structureLocked] defaults to **true**, which encodes the "page structure locked; content editable" rule
 * as a property of the data rather than a convention the UI is trusted to remember. Editing objects never
 * consults it; only page-geometry commands do, and they refuse rather than silently reshaping a sheet the
 * user is composing against.
 */
@Serializable
data class PageGeometry(
    val preset: DocPagePreset = DocPagePreset.A4,
    val width: Float = 595f,
    val height: Float = 842f,
    val margins: PageMargins = PageMargins(),
    val columns: PageColumns = PageColumns(),
    val background: Long? = null,
    /** Rich page paint. Null preserves the legacy flat [background] value exactly. */
    val backgroundPaint: com.norfold.app.domain.style.ColorPaint? = null,
    /** Stable document-token binding. Null keeps every pre-Phase-3 payload backward compatible. */
    val backgroundTokenId: String? = null,
    val border: PageBorder = PageBorder(),
    val grid: PageGrid = PageGrid(),
    val bleed: PageBleed = PageBleed(),
    val headerFooter: PageHeaderFooter = PageHeaderFooter(),
    val numbering: PageNumbering = PageNumbering(),
    val structureLocked: Boolean = true,
) {
    /** Derived, never stored: a second stored copy could contradict the dimensions it describes. */
    val orientation: PageOrientation
        get() = if (width > height) PageOrientation.Landscape else PageOrientation.Portrait

    /** The area objects are laid out against — the sheet inset by its margins. */
    val contentLeft: Float get() = margins.left
    val contentTop: Float get() = margins.top + headerFooter.headerHeight
    val contentWidth: Float get() = (width - margins.left - margins.right).coerceAtLeast(1f)
    val contentHeight: Float
        get() = (height - margins.top - margins.bottom - headerFooter.headerHeight - headerFooter.footerHeight)
            .coerceAtLeast(1f)

    /** Width of a single column within the content box, accounting for gutters. */
    val columnWidth: Float
        get() {
            val c = columns.count.coerceAtLeast(1)
            return ((contentWidth - columns.gutter * (c - 1)) / c).coerceAtLeast(1f)
        }

    /** Left edge of column [index] relative to the page's left edge. */
    fun columnLeft(index: Int): Float =
        contentLeft + index.coerceIn(0, columns.count - 1) * (columnWidth + columns.gutter)

    fun normalized(): PageGeometry {
        val (w, h) = PageLimits.fit(width, height)
        return copy(
            width = w,
            height = h,
            margins = margins.normalized(w, h),
            columns = columns.normalized(),
            border = border.normalized(),
            grid = grid.normalized(),
            bleed = bleed.normalized(),
            headerFooter = headerFooter.normalized(h),
            numbering = numbering.normalized(),
        )
    }

    /** Swaps the sheet's dimensions if it is not already in [orientation]. Margins follow the rotation. */
    fun withOrientation(target: PageOrientation): PageGeometry {
        if (orientation == target) return this
        return copy(
            width = height,
            height = width,
            margins = PageMargins(
                top = margins.left,
                right = margins.top,
                bottom = margins.right,
                left = margins.bottom,
            ),
        ).normalized()
    }

    /** Applies a size in millimetres, honouring both canonical limits. */
    fun withSizeMm(widthMm: Float, heightMm: Float): PageGeometry =
        copy(preset = DocPagePreset.Custom, width = widthMm * POINTS_PER_MM, height = heightMm * POINTS_PER_MM)
            .normalized()

    companion object {
        fun a4() = PageGeometry(DocPagePreset.A4, 595f, 842f)
        fun letter() = PageGeometry(DocPagePreset.Letter, 612f, 792f)
        fun legal() = PageGeometry(DocPagePreset.Legal, 612f, 1008f)

        fun ofPreset(preset: DocPagePreset): PageGeometry = when (preset) {
            DocPagePreset.A4 -> a4()
            DocPagePreset.Letter -> letter()
            DocPagePreset.Legal -> legal()
            DocPagePreset.Custom -> a4().copy(preset = DocPagePreset.Custom)
        }
    }
}

/** Page paint used by renderers; old documents resolve their legacy ARGB word without migration. */
fun PageGeometry.effectiveBackgroundPaint(): com.norfold.app.domain.style.ColorPaint =
    backgroundPaint ?: com.norfold.app.domain.style.ColorPaint.Flat(
        com.norfold.app.domain.style.ColorValue.fromArgb(background ?: 0xFFFFFFFF),
    )

/** What a scene object may have done to it. Drives capability mapping and the lock rules. */
@Serializable
enum class SceneObjectKind {
    /** A text frame: content reflows within the object's box. */
    TextFrame,

    /** An image, resizable and rotatable. */
    Image,

    /** A vector/geometric shape. */
    Shape,

    /** A table placed on the page. Resizable, but rotation is not meaningful. */
    Table,

    /** A chart placed on the page. */
    Chart,

    /** An embed/file/diagram card. */
    Media,

    /**
     * Legacy content preserved from an earlier document model that this version can place but not natively
     * interpret. It moves, resizes and round-trips; it is never silently flattened or dropped.
     */
    Preserved,
    ;

    val supportsRotation: Boolean get() = this != SceneObjectKind.Table
}

/**
 * Lock scope on a scene object (§4.13). A locked object stays **selectable and inspectable** — it is the
 * commands that refuse and explain, never the selection that pretends the object is not there. Mirrors
 * `editor.selection.LockScope`; declared here because the serialized form belongs to the domain and the
 * domain layer must not depend on the editor layer.
 */
@Serializable
enum class SceneLock {
    None,
    Position,
    Size,
    Content,
    Full,
    ;

    val blocksMove: Boolean get() = this == Position || this == Full
    val blocksResize: Boolean get() = this == Size || this == Full
    val blocksRotate: Boolean get() = this == Size || this == Full
    val blocksContent: Boolean get() = this == Content || this == Full
}

/** An object's box on its page, in points, relative to the page's top-left corner. */
@Serializable
data class SceneGeometry(
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 240f,
    val height: Float = 120f,
    val rotation: Float = 0f,
) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height
    val centerX: Float get() = x + width / 2f
    val centerY: Float get() = y + height / 2f

    fun normalized(): SceneGeometry = SceneGeometry(
        x = x.finiteOr(0f),
        y = y.finiteOr(0f),
        width = width.finiteOr(MIN_SIZE).coerceIn(MIN_SIZE, PageLimits.MAX_SIDE_PT),
        height = height.finiteOr(MIN_SIZE).coerceIn(MIN_SIZE, PageLimits.MAX_SIDE_PT),
        // Normalizing into [0, 360) keeps repeated rotations from accumulating an unbounded value that
        // eventually loses float precision.
        rotation = ((rotation.finiteOr(0f) % 360f) + 360f) % 360f,
    )

    fun translatedBy(dx: Float, dy: Float): SceneGeometry = copy(x = x + dx, y = y + dy).normalized()

    /**
     * Grows or shrinks by a delta, anchored at the top-left.
     *
     * [normalized] does the clamping, so a shrink past [MIN_SIZE] stops at the minimum rather than inverting
     * the box — an object driven to zero extent would be unhittable and so unrecoverable without undo.
     */
    fun resizedBy(dWidth: Float, dHeight: Float): SceneGeometry =
        copy(width = width + dWidth, height = height + dHeight).normalized()

    /** Rotates by a delta in degrees; [normalized] wraps the result back into `[0, 360)`. */
    fun rotatedBy(degrees: Float): SceneGeometry = copy(rotation = rotation + degrees).normalized()

    /** True when this box overlaps [other]. Axis-aligned; rotation is ignored for coarse hit tests. */
    fun intersects(other: SceneGeometry): Boolean =
        x < other.right && right > other.x && y < other.bottom && bottom > other.y

    /** True when [px], [py] falls inside this box, in the object's own unrotated coordinate space. */
    fun contains(px: Float, py: Float): Boolean = px >= x && px <= right && py >= y && py <= bottom

    /**
     * A point of this box's **own unrotated frame** mapped to where it is actually drawn on the page.
     *
     * This is *the* forward rotation: one function, about [centerX]/[centerY], that the renderer, the resize
     * handles, the rotation handle and [pageToLocal] all go through. The defect this exists to prevent is the
     * one where drawing rotates about one centre and hit testing un-rotates about another — the two agree here
     * by construction rather than by two call sites happening to compute the same thing.
     */
    fun localToPage(px: Float, py: Float): Pair<Float, Float> {
        if (rotation == 0f) return px to py
        val radians = rotation.toDouble() * PI / 180.0
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        val dx = px - centerX
        val dy = py - centerY
        return (centerX + dx * c - dy * s) to (centerY + dx * s + dy * c)
    }

    /** The exact inverse of [localToPage]: a page point expressed in this box's own unrotated frame. */
    fun pageToLocal(px: Float, py: Float): Pair<Float, Float> {
        if (rotation == 0f) return px to py
        val radians = -rotation.toDouble() * PI / 180.0
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        val dx = px - centerX
        val dy = py - centerY
        return (centerX + dx * c - dy * s) to (centerY + dx * s + dy * c)
    }

    /**
     * A *displacement* measured in this box's rotated frame, expressed in page space.
     *
     * Vectors rotate but do not translate, so this is deliberately not [localToPage]: a drag delta reported
     * inside a rotated layer is a direction, and adding the centre to it would turn a two-point nudge into a
     * jump to somewhere near the middle of the object.
     */
    fun rotateVectorToPage(dx: Float, dy: Float): Pair<Float, Float> {
        if (rotation == 0f) return dx to dy
        val radians = rotation.toDouble() * PI / 180.0
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        return (dx * c - dy * s) to (dx * s + dy * c)
    }

    /**
     * True when [px], [py] falls inside this box **as drawn**, honouring [rotation].
     *
     * The point is brought into the box's own frame by [pageToLocal] and then tested against the unrotated
     * box, so this is the exact inverse of the transform the renderer applies. Testing the axis-aligned box
     * instead would disagree with what the user sees the moment anything is rotated: the corners of a
     * 45°-rotated frame would be selectable where nothing is drawn, and its visible corners would not respond.
     */
    fun containsRotated(px: Float, py: Float): Boolean {
        if (rotation == 0f) return contains(px, py)
        val (lx, ly) = pageToLocal(px, py)
        return contains(lx, ly)
    }

    /** Where [corner] is actually **drawn**, honouring rotation — the position its handle must sit at. */
    fun rotatedCornerOf(corner: ResizeCorner): Pair<Float, Float> {
        val (cx, cy) = corner.positionIn(this)
        return localToPage(cx, cy)
    }

    /**
     * Where the rotation handle is actually **drawn**: [offsetPt] above the top edge's midpoint, carried
     * around by the object's own rotation so the handle stays at the object's "up" rather than the page's.
     */
    fun rotationHandlePoint(offsetPt: Float): Pair<Float, Float> = localToPage(centerX, y - offsetPt)

    /**
     * The box produced by dragging one [corner] to ([px], [py]) in page points, with the opposite corner
     * anchored.
     *
     * Two rules make this behave the way a human expects. The opposite corner is computed *first* and held
     * fixed, so dragging the top-left moves x and y as well as width and height rather than only resizing
     * downward. And each axis is clamped at [MIN_SIZE] against that anchor rather than being allowed to pass
     * through it: dragging the left edge past the right one pins the box at its minimum width instead of
     * inverting it into a negative-width box that the renderer would draw inside-out and hit testing would
     * miss entirely.
     */
    fun resizedTo(corner: ResizeCorner, px: Float, py: Float): SceneGeometry {
        val anchorX = if (corner.movingRight) x else right
        val anchorY = if (corner.movingBottom) y else bottom
        val nx = px.finiteOr(anchorX)
        val ny = py.finiteOr(anchorY)
        val width = if (corner.movingRight) nx - anchorX else anchorX - nx
        val height = if (corner.movingBottom) ny - anchorY else anchorY - ny
        val w = width.coerceAtLeast(MIN_SIZE)
        val h = height.coerceAtLeast(MIN_SIZE)
        return copy(
            x = if (corner.movingRight) anchorX else anchorX - w,
            y = if (corner.movingBottom) anchorY else anchorY - h,
            width = w,
            height = h,
        )
    }

    /**
     * The box produced by dragging [corner] to the **page** point ([px], [py]), honouring rotation.
     *
     * Two conversions make a rotated resize behave the way the handle looks. The pointer is brought into the
     * object's own frame first, because the box's width and height are measured along the object's axes, not
     * the page's — resizing a 45°-rotated frame with raw page coordinates would grow it diagonally. Then the
     * result is translated so the anchored corner stays where it is **on the page**: resizing changes the
     * centre, and since the rotation pivots about the centre, a box that was merely resized in local space
     * would swing about that moving pivot and drag the "fixed" corner away from the user's other hand.
     */
    fun resizedToRotated(corner: ResizeCorner, px: Float, py: Float): SceneGeometry {
        if (rotation == 0f) return resizedTo(corner, px, py)
        val (lx, ly) = pageToLocal(px, py)
        val resized = resizedTo(corner, lx, ly)
        // The anchored corner is the one *opposite* the handle being dragged, and it must not move on screen.
        val anchor = corner.opposite()
        val (beforeX, beforeY) = rotatedCornerOf(anchor)
        val (afterX, afterY) = resized.rotatedCornerOf(anchor)
        return resized.copy(x = resized.x + (beforeX - afterX), y = resized.y + (beforeY - afterY))
    }

    /**
     * The axis-aligned box that fully contains this one **as drawn**, honouring [rotation].
     *
     * Used for culling and coarse overlap. A rotated frame's corners reach outside its own x/y/width/height,
     * so culling against the unrotated box would drop an object whose visible corner is still on screen.
     */
    fun boundingBox(): SceneGeometry {
        if (rotation == 0f) return this
        val radians = rotation.toDouble() * PI / 180.0
        val cos = abs(cos(radians)).toFloat()
        val sin = abs(sin(radians)).toFloat()
        val w = width * cos + height * sin
        val h = width * sin + height * cos
        return SceneGeometry(centerX - w / 2f, centerY - h / 2f, w, h)
    }

    private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

    companion object {
        /** Below this an object cannot present a usable touch target or resize handle. */
        const val MIN_SIZE = 8f
    }
}

/**
 * One of the four corners a resize gesture can drag.
 *
 * The two flags are what [SnapGuideEngine] already asks for — *which* edges are moving — so a corner carries
 * its own snapping contract rather than each call site re-deriving it and one of them eventually getting the
 * pair backwards.
 */
enum class ResizeCorner(val movingRight: Boolean, val movingBottom: Boolean) {
    TOP_LEFT(movingRight = false, movingBottom = false),
    TOP_RIGHT(movingRight = true, movingBottom = false),
    BOTTOM_LEFT(movingRight = false, movingBottom = true),
    BOTTOM_RIGHT(movingRight = true, movingBottom = true),
    ;

    /** The corner held fixed while this one is dragged. */
    fun anchorOf(geometry: SceneGeometry): Pair<Float, Float> =
        (if (movingRight) geometry.x else geometry.right) to
            (if (movingBottom) geometry.y else geometry.bottom)

    /** Where this corner currently sits, for placing its handle. */
    fun positionIn(geometry: SceneGeometry): Pair<Float, Float> =
        (if (movingRight) geometry.right else geometry.x) to
            (if (movingBottom) geometry.bottom else geometry.y)

    /** The corner diagonally across from this one — the one a drag on this handle holds fixed. */
    fun opposite(): ResizeCorner = entries.first {
        it.movingRight != movingRight && it.movingBottom != movingBottom
    }
}

/**
 * One independently addressable thing on a page: placement, capability and identity around a piece of
 * content.
 *
 * [content] is an ordinary [DocumentBlock] — the same value the Flow editor would hold — so placing content
 * on a page never rewrites what it is, and an object can be moved between the two products without a
 * content conversion. [extensions] carries payload this build does not interpret: a later phase (styles,
 * relationships, effects) can attach data that survives being opened, edited and saved by *this* build,
 * which is the forward-compatibility guarantee an unknown-kind fallback alone cannot give.
 */
@Serializable
data class SceneObject(
    val id: String = sceneId("obj"),
    val kind: SceneObjectKind = SceneObjectKind.TextFrame,
    val geometry: SceneGeometry = SceneGeometry(),
    /** Higher draws in front. Kept contiguous from 0 by [DocumentPageBlock.normalized]. */
    val z: Int = 0,
    val lock: SceneLock = SceneLock.None,
    val visible: Boolean = true,
    val name: String = "",
    val content: DocumentBlock = ParagraphBlock(),
    val extensions: Map<String, String> = emptyMap(),
) {
    val canMove: Boolean get() = !lock.blocksMove
    val canResize: Boolean get() = !lock.blocksResize
    val canRotate: Boolean get() = kind.supportsRotation && !lock.blocksRotate
    val canEditContent: Boolean get() = !lock.blocksContent

    /** A human label for accessibility and the layer list; falls back to the content's own text. */
    fun displayName(): String = name.ifBlank {
        content.plainText().lineSequence().firstOrNull()?.take(40)?.ifBlank { null } ?: kind.name
    }

    fun normalized(): SceneObject = copy(geometry = geometry.normalized())
}

/**
 * One page of a bounded document: its [geometry] and the [objects] it owns.
 *
 * Objects are stored **in z-order** (index 0 is furthest back) and their [SceneObject.z] is renormalized to
 * match on every [normalized] call. One ordering, kept in two forms that cannot disagree: the list order is
 * what iteration and hit-testing use, and the explicit z survives serialization for anything that reads
 * objects out of order.
 */
@Serializable @SerialName("page")
data class DocumentPageBlock(
    override val id: String = sceneId("page"),
    val geometry: PageGeometry = PageGeometry(),
    val objects: List<SceneObject> = emptyList(),
    val extensions: Map<String, String> = emptyMap(),
) : DocumentBlock {

    override fun plainText(): String =
        objects.sortedBy { it.z }.joinToString("\n") { it.content.plainText() }.trim()

    fun objectById(objectId: String): SceneObject? = objects.firstOrNull { it.id == objectId }

    fun indexOfObject(objectId: String): Int = objects.indexOfFirst { it.id == objectId }

    /** Objects from front to back — the order hit-testing must consider, since the front one wins. */
    fun objectsFrontToBack(): List<SceneObject> = objects.sortedByDescending { it.z }

    /**
     * Every object whose box contains the point, front-most first. The full list is returned rather than
     * just the winner so overlapping objects have a deterministic deep-selection path: tapping repeatedly
     * walks down this list instead of being stuck on whatever is on top.
     *
     * Rotation is honoured ([SceneGeometry.containsRotated]), so what responds to a tap is what is drawn.
     * The ordering is still strictly z-descending — rotating an object changes its shape, never its layer —
     * which is what keeps repeat-tap cycling stable while a rotated object is in the stack.
     */
    fun hitTest(px: Float, py: Float): List<SceneObject> =
        objectsFrontToBack().filter { it.visible && it.geometry.containsRotated(px, py) }

    fun normalized(): DocumentPageBlock {
        val ordered = objects
            .sortedWith(compareBy({ it.z }, { it.id }))
            .mapIndexed { index, obj -> obj.normalized().copy(z = index) }
        return copy(geometry = geometry.normalized(), objects = ordered)
    }

    fun withObject(obj: SceneObject): DocumentPageBlock {
        val index = indexOfObject(obj.id)
        val next = if (index < 0) objects + obj else objects.toMutableList().apply { set(index, obj) }
        return copy(objects = next).normalized()
    }

    fun withoutObject(objectId: String): DocumentPageBlock =
        copy(objects = objects.filterNot { it.id == objectId }).normalized()
}

/**
 * Document-level bounded-document settings, as a top-level block.
 *
 * **Why a block and not a field on [BlockDocument].** Persistence stores one row per top-level block and
 * History operates on top-level-block indices. A field on the document would have neither a row of its own
 * nor an undoable representation, so "New pages by default" would live only in memory and quietly reset on
 * the next session — which is exactly the failure this type exists to prevent. As a block it is persisted,
 * undone and round-tripped by machinery that is already proven.
 *
 * [newPageTemplate] is nullable and defaults to null, which is a *distinct* state from any concrete geometry:
 * null means "a new page inherits from the page it follows", the behaviour a document has until the user
 * deliberately overrides it. Seeding a default here would silently convert every document into one with an
 * explicit template, overriding the inherit rule that makes inserting into a Letter document produce Letter.
 *
 * It carries no text, so it is invisible in any surface that renders content.
 */
@Serializable @SerialName("document_settings")
data class DocumentSettingsBlock(
    override val id: String = sceneId("settings"),
    val newPageTemplate: PageGeometry? = null,
    val extensions: Map<String, String> = emptyMap(),
) : DocumentBlock {
    override fun plainText(): String = ""

    fun normalized(): DocumentSettingsBlock = copy(newPageTemplate = newPageTemplate?.normalized())
}

/** The document's settings block, or null when it has none (the ordinary case for an untouched document). */
fun BlockDocument.documentSettings(): DocumentSettingsBlock? =
    blocks.firstNotNullOfOrNull { it as? DocumentSettingsBlock }

/** Index of the settings block within the top-level block list, or -1. */
fun BlockDocument.blockIndexOfSettings(): Int = blocks.indexOfFirst { it is DocumentSettingsBlock }

/**
 * The pages of [this] document, in order. Non-page blocks are ignored rather than coerced: a bounded
 * document that also carries preserved legacy content must not have that content reinterpreted as a page.
 */
fun BlockDocument.pages(): List<DocumentPageBlock> = blocks.filterIsInstance<DocumentPageBlock>()

fun BlockDocument.pageById(pageId: String): DocumentPageBlock? =
    blocks.firstOrNull { it.id == pageId } as? DocumentPageBlock

/** Index of [pageId] within the document's **top-level block list** — the index History operations use. */
fun BlockDocument.blockIndexOfPage(pageId: String): Int = blocks.indexOfFirst { it.id == pageId }

/** True when this document is a bounded-page document rather than a continuous Flow document. */
fun BlockDocument.isBoundedDocument(): Boolean = blocks.any { it is DocumentPageBlock }

/** Locates the page owning [objectId], with the object itself. */
fun BlockDocument.locateObject(objectId: String): Pair<DocumentPageBlock, SceneObject>? {
    for (page in pages()) {
        val obj = page.objectById(objectId) ?: continue
        return page to obj
    }
    return null
}

/**
 * Total height of the document when pages are stacked vertically with [gap] between them, and the top of
 * each page in that stack. Presentation reads these to virtualize; they are derived, never stored.
 */
fun List<DocumentPageBlock>.stackedPageTops(gap: Float = DEFAULT_PAGE_GAP): List<Float> {
    var y = 0f
    return map { page ->
        val top = y
        y += page.geometry.height + gap
        top
    }
}

fun List<DocumentPageBlock>.stackedHeight(gap: Float = DEFAULT_PAGE_GAP): Float {
    if (isEmpty()) return 0f
    return sumOf { it.geometry.height.toDouble() }.toFloat() + gap * (size - 1)
}

/** Vertical gap drawn between stacked pages. A presentation constant, not page geometry. */
const val DEFAULT_PAGE_GAP: Float = 24f

/** Largest page width in the document — the width the page stack must be able to accommodate. */
fun List<DocumentPageBlock>.widestPage(): Float = maxOfOrNull { it.geometry.width } ?: 0f

/** True when the document mixes page sizes or orientations, which the stack layout must tolerate. */
fun List<DocumentPageBlock>.hasMixedGeometry(): Boolean {
    val first = firstOrNull()?.geometry ?: return false
    return any { abs(it.geometry.width - first.width) > 0.5f || abs(it.geometry.height - first.height) > 0.5f }
}

/**
 * The section each page belongs to, as an index. A section starts at a page whose numbering declares
 * [PageNumbering.startsSection] and runs to the page before the next one that does; the first page always
 * opens section 0 so every page has an answer.
 */
fun List<DocumentPageBlock>.sectionIndices(): List<Int> {
    var section = 0
    return mapIndexed { index, page ->
        if (index > 0 && page.geometry.numbering.startsSection) section++
        section
    }
}

/**
 * The printed number of each page, honouring section restarts.
 *
 * Numbering runs from 1 and continues across pages until a page both starts a section and declares a
 * [PageNumbering.restartAt]; from there the sequence resumes at that value. Derived rather than stored,
 * because a stored number would fall out of step the moment a page was inserted or reordered — and a page
 * number that disagrees with the page's position is worse than none.
 */
fun List<DocumentPageBlock>.pageNumbers(): List<Int> {
    var current = 0
    return map { page ->
        val numbering = page.geometry.numbering
        current = if (numbering.startsSection && numbering.restartAt != null) numbering.restartAt else current + 1
        current
    }
}

/** Each page's number as it should be *printed*, in that page's own chosen format. */
fun List<DocumentPageBlock>.pageNumberLabels(): List<String> {
    val numbers = pageNumbers()
    return mapIndexed { index, page -> page.geometry.numbering.format.format(numbers[index]) }
}

internal fun sceneId(prefix: String): String = "$prefix-${UUID.randomUUID()}"
