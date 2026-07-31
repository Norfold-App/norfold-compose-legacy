package com.norfold.app.editor.document

import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.ResizeCorner
import com.norfold.app.domain.SceneGeometry
import com.norfold.app.domain.SceneObject
import com.norfold.app.domain.stackedHeight
import com.norfold.app.domain.stackedPageTops
import com.norfold.app.domain.widestPage
import kotlin.math.max
import kotlin.math.min

/**
 * The two independent axes a bounded document is worked in (roadmap Phase 2 §8).
 *
 * They are independent because they answer different questions. [LayoutRepresentation] asks *what is being
 * shown* — the true printed page, or a viewport-adapted rendering of it. [WorkingIntent] asks *what the user
 * is doing* — reading, or composing. Collapsing them into one four-state mode would make "read the exact
 * printed layout" and "edit an adapted layout" unreachable, and those are the two combinations people
 * actually spend time in on a phone.
 */
enum class LayoutRepresentation {
    /** Adapted to the current viewport: stacked, reflowed, shrunk. Never mutates the underlying geometry. */
    ADAPTIVE,

    /** The document exactly as it will print: true page geometry, positions, columns, breaks. */
    ORIGINAL,
}

enum class WorkingIntent { READ, EDIT }

/**
 * A layout-sensitive operation that Adaptive View cannot perform faithfully.
 *
 * Direct manipulation is the clearest case: dragging an object to an exact position is meaningless in a
 * view that has already moved everything to fit the viewport, because the position the user sees is not the
 * position that would be stored. Rather than silently storing something else — or disabling the gesture with
 * no explanation — the host switches to Original View and says why.
 */
enum class LayoutSensitiveAction(val explanation: String) {
    MOVE_OBJECT("Switched to the exact page layout so this can be positioned precisely."),
    RESIZE_OBJECT("Switched to the exact page layout so this can be sized precisely."),
    ROTATE_OBJECT("Switched to the exact page layout so this can be rotated precisely."),
    EDIT_PAGE_SETUP("Switched to the exact page layout to change page setup."),
    INSERT_AT_POSITION("Switched to the exact page layout to place this exactly."),
}

/**
 * The result of asking to perform an action: whether the view had to change, and what to tell the user.
 *
 * Selection, caret, tool and history are deliberately absent — they are *not* touched by a transition, which
 * is the point. A representation change is a change of rendering, so resetting editor state across one would
 * be gratuitous, and losing a multi-object selection because the user dragged something is exactly the kind
 * of "the app fought me" moment this model exists to prevent.
 */
data class ViewTransition(
    val switched: Boolean,
    val representation: LayoutRepresentation,
    val message: String? = null,
)

/**
 * A manipulation in progress: local preview only, never committed per frame.
 *
 * Holding [original] is what makes Cancel exact — the pre-manipulation geometry is kept verbatim rather than
 * reconstructed by inverting the gesture, which would accumulate float error across a long drag and leave
 * the object a fraction of a point from where it started.
 */
data class ActiveManipulation(
    val objectIds: List<String>,
    val kind: Kind,
    val original: Map<String, SceneGeometry>,
    val preview: Map<String, SceneGeometry>,
    val guides: List<SnapGuideEngine.Guide> = emptyList(),
) {
    enum class Kind { MOVE, RESIZE, ROTATE }

    fun previewFor(objectId: String): SceneGeometry? = preview[objectId]
}

/**
 * The range of pages that must actually be composed, plus the scroll extent (roadmap Phase 2 §14).
 *
 * Virtualization is computed from real page geometry rather than an item count, because pages may differ in
 * height — a fixed-height assumption silently misplaces every page after the first differing one, and the
 * error grows with document length.
 */
data class PageWindow(
    val firstIndex: Int,
    val lastIndex: Int,
    val pageTops: List<Float>,
    val totalHeight: Float,
    val widestPage: Float,
) {
    val count: Int get() = if (lastIndex < firstIndex) 0 else lastIndex - firstIndex + 1
    fun topOf(index: Int): Float = pageTops.getOrElse(index) { 0f }
    operator fun contains(index: Int): Boolean = index in firstIndex..lastIndex
}

/**
 * Presentation-layer state for DocumentHost: which pages to compose, what a gesture is currently proposing,
 * and which of the four Adaptive/Original × Read/Edit combinations is active.
 *
 * Pure-JVM by design, exactly as the Flow runtime is: the window arithmetic, the transition rules and the
 * manipulation state machine are the parts most likely to be wrong, and they are all testable here without
 * an Android runtime or a screenshot.
 */
class DocumentViewState(
    representation: LayoutRepresentation = LayoutRepresentation.ADAPTIVE,
    intent: WorkingIntent = WorkingIntent.READ,
) {
    var representation: LayoutRepresentation = representation
        private set

    var intent: WorkingIntent = intent
        private set

    var manipulation: ActiveManipulation? = null
        private set

    /** Zoom factor in Original View. Adaptive View fits content to the viewport and ignores this. */
    var zoom: Float = 1f
        private set

    /**
     * Horizontal pan in **page points**, in Original View only.
     *
     * Stored in points rather than pixels so it means the same thing at every zoom and density: panning,
     * zooming, then panning again lands where the user aimed instead of drifting by the zoom ratio. There is
     * no vertical counterpart because the page stack already scrolls vertically — a second vertical offset
     * would be a second scroll position, and the two would disagree.
     */
    var panX: Float = 0f
        private set

    fun setIntent(next: WorkingIntent) {
        intent = next
    }

    fun setRepresentation(next: LayoutRepresentation) {
        representation = next
    }

    fun setZoom(next: Float) {
        zoom = next.coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    fun setPan(next: Float) {
        panX = if (next.isFinite()) next else 0f
    }

    /**
     * Zooms so the widest page exactly fills [viewportWidthPt], and re-centres.
     *
     * Fit is computed from real page geometry, so a document of mixed page sizes fits the page that would
     * otherwise overflow rather than the first one.
     */
    fun fitWidth(pages: List<DocumentPageBlock>, viewportWidthPt: Float): Float {
        val widest = pages.widestPage()
        if (widest <= 0f || viewportWidthPt <= 0f) return zoom
        setZoom(viewportWidthPt / widest)
        panX = 0f
        return zoom
    }

    /**
     * Zooms so one whole page — width *and* height — is visible, and re-centres.
     *
     * The smaller of the two ratios wins, which is what "fit page" means: satisfying only the width would
     * leave a tall page cropped, and the user asked to see the sheet.
     */
    fun fitPage(page: DocumentPageBlock?, viewportWidthPt: Float, viewportHeightPt: Float): Float {
        val geometry = page?.geometry ?: return zoom
        if (viewportWidthPt <= 0f || viewportHeightPt <= 0f) return zoom
        setZoom(min(viewportWidthPt / geometry.width, viewportHeightPt / geometry.height))
        panX = 0f
        return zoom
    }

    /**
     * Prepares for [action], switching to Original View if the action cannot be performed faithfully where
     * the user is. Selection, caret and history are untouched.
     */
    fun prepareFor(action: LayoutSensitiveAction): ViewTransition {
        if (representation == LayoutRepresentation.ORIGINAL) {
            return ViewTransition(switched = false, representation = representation)
        }
        representation = LayoutRepresentation.ORIGINAL
        return ViewTransition(switched = true, representation = representation, message = action.explanation)
    }

    // --- Manipulation --------------------------------------------------------------------------------

    /** Begins a preview-only manipulation of [objects]. Locked objects are excluded up front. */
    fun beginManipulation(objects: List<SceneObject>, kind: ActiveManipulation.Kind): ActiveManipulation? {
        val eligible = objects.filter {
            when (kind) {
                ActiveManipulation.Kind.MOVE -> it.canMove
                ActiveManipulation.Kind.RESIZE -> it.canResize
                ActiveManipulation.Kind.ROTATE -> it.canRotate
            }
        }
        if (eligible.isEmpty()) return null
        val geometries = eligible.associate { it.id to it.geometry }
        return ActiveManipulation(
            objectIds = eligible.map { it.id },
            kind = kind,
            original = geometries,
            preview = geometries,
        ).also { manipulation = it }
    }

    /**
     * Updates the live preview by a delta from the manipulation's **start**, snapping against [page].
     *
     * Deltas are measured from the original geometry rather than accumulated frame to frame: accumulating
     * would make a snap sticky, because the snapped position would become the base the next delta is added
     * to and the object could never leave the guide it caught on.
     */
    fun updateMove(dx: Float, dy: Float, page: DocumentPageBlock?): ActiveManipulation? {
        val active = manipulation ?: return null
        if (active.kind != ActiveManipulation.Kind.MOVE) return active
        val moved = active.original.mapValues { (_, geometry) -> geometry.translatedBy(dx, dy) }
        // Snapping follows the primary object; the rest keep their exact relative offsets so a
        // multi-object drag never deforms the arrangement the user built.
        val primaryId = active.objectIds.first()
        val primaryMoved = moved.getValue(primaryId)
        val snapped = page?.let {
            SnapGuideEngine.snapMove(primaryMoved, it, excludeIds = active.objectIds.toSet())
        }
        val adjustX = (snapped?.geometry?.x ?: primaryMoved.x) - primaryMoved.x
        val adjustY = (snapped?.geometry?.y ?: primaryMoved.y) - primaryMoved.y
        val preview = moved.mapValues { (_, geometry) -> geometry.translatedBy(adjustX, adjustY) }
        return active.copy(preview = preview, guides = snapped?.guides.orEmpty()).also { manipulation = it }
    }

    /**
     * Updates a resize preview by dragging [corner] to ([px], [py]) in page points.
     *
     * The corner is the input, not a width/height pair, because the two are not interchangeable: dragging the
     * top-left has to move the origin as well as the size, and a caller that could only express "the box is
     * now this big" would have to reconstruct the origin itself — which is exactly where an off-by-one anchor
     * turns a resize into a resize-plus-jump.
     *
     * Rotation-aware: [SceneGeometry.resizedToRotated] measures the drag along the object's *own* axes and
     * keeps the opposite corner fixed **on the page**. Resizing a rotated box with raw page coordinates would
     * grow it diagonally and swing it about its moving centre.
     *
     * The proposed box is always derived from the geometry the gesture *started* from, so a snap cannot become
     * the base for the next frame's arithmetic and stick.
     */
    fun updateResize(
        corner: ResizeCorner,
        px: Float,
        py: Float,
        page: DocumentPageBlock?,
    ): ActiveManipulation? {
        val active = manipulation ?: return null
        if (active.kind != ActiveManipulation.Kind.RESIZE) return active
        val objectId = active.objectIds.first()
        val original = active.original[objectId] ?: return active
        return applyResize(active, objectId, original.resizedToRotated(corner, px, py), page, corner)
    }

    /**
     * Updates a resize preview from an exact box — the geometry inspector's route.
     *
     * Snapping is deliberately not applied: a number the user typed is the number they meant, and quietly
     * moving it six points to a margin would make the field disagree with what it just reported.
     */
    fun setResizePreview(geometry: SceneGeometry): ActiveManipulation? {
        val active = manipulation ?: return null
        if (active.kind != ActiveManipulation.Kind.RESIZE) return active
        val objectId = active.objectIds.first()
        return active.copy(preview = mapOf(objectId to geometry.normalized()), guides = emptyList())
            .also { manipulation = it }
    }

    private fun applyResize(
        active: ActiveManipulation,
        objectId: String,
        proposed: SceneGeometry,
        page: DocumentPageBlock?,
        corner: ResizeCorner,
    ): ActiveManipulation {
        // Snapping is skipped for a rotated box, deliberately. The guides describe axis-aligned page edges,
        // margins and neighbouring boxes, but a rotated object's stored x/y/right/bottom are not its visible
        // edges — so snapping them would align something the user cannot see *and* would shift the origin,
        // dragging the corner they are holding fixed away from their finger. An unsnapped rotated resize is
        // honest; a snapped one is wrong in two ways at once.
        val snapped = if (proposed.rotation != 0f) {
            null
        } else {
            page?.let {
                SnapGuideEngine.snapResize(
                    proposed,
                    it,
                    movingRight = corner.movingRight,
                    movingBottom = corner.movingBottom,
                    excludeIds = setOf(objectId),
                )
            }
        }
        return active.copy(
            preview = mapOf(objectId to (snapped?.geometry ?: proposed.normalized())),
            guides = snapped?.guides.orEmpty(),
        ).also { manipulation = it }
    }

    /**
     * Updates a rotation preview to [degrees], rotating every object in the manipulation about **its own**
     * centre.
     *
     * Per-object rather than about the selection's collective centre, because rotating a group about a shared
     * pivot also *moves* each member — and a rotation that silently repositions things is the behaviour a user
     * reads as the app having lost their layout. Group-pivot rotation is a distinct operation, not a default.
     */
    fun updateRotation(degrees: Float): ActiveManipulation? {
        val active = manipulation ?: return null
        if (active.kind != ActiveManipulation.Kind.ROTATE) return active
        val preview = active.original.mapValues { (_, geometry) -> geometry.copy(rotation = degrees).normalized() }
        return active.copy(preview = preview, guides = emptyList()).also { manipulation = it }
    }

    /** Ends the manipulation and returns the geometry to commit, or null when nothing actually changed. */
    fun endManipulation(): Map<String, SceneGeometry>? {
        val active = manipulation ?: return null
        manipulation = null
        if (active.preview == active.original) return null
        return active.preview
    }

    /** Abandons the manipulation. The model was never touched, so the pre-gesture state is already exact. */
    fun cancelManipulation() {
        manipulation = null
    }

    /** The geometry to draw for [obj] — its preview while it is being manipulated, else its real geometry. */
    fun renderGeometry(obj: SceneObject): SceneGeometry =
        manipulation?.previewFor(obj.id) ?: obj.geometry

    companion object {
        const val MIN_ZOOM = 0.25f
        const val MAX_ZOOM = 8f
    }
}

/** Page-window arithmetic. Separated from [DocumentViewState] so it stays a pure function of geometry. */
object PageVirtualizer {

    /**
     * The pages intersecting the viewport, padded by [overscan] pages either side.
     *
     * Overscan exists so scrolling reveals already-composed content rather than a blank page that fills in a
     * frame later; one page is enough to cover a fling's first frame without composing the whole document.
     */
    fun window(
        pages: List<DocumentPageBlock>,
        scrollOffset: Float,
        viewportHeight: Float,
        gap: Float = com.norfold.app.domain.DEFAULT_PAGE_GAP,
        overscan: Int = 1,
    ): PageWindow {
        val tops = pages.stackedPageTops(gap)
        val total = pages.stackedHeight(gap)
        if (pages.isEmpty()) {
            return PageWindow(0, -1, emptyList(), 0f, 0f)
        }
        val viewportTop = scrollOffset
        val viewportBottom = scrollOffset + viewportHeight
        var first = -1
        var last = -1
        pages.forEachIndexed { index, page ->
            val top = tops[index]
            val bottom = top + page.geometry.height
            if (bottom >= viewportTop && top <= viewportBottom) {
                if (first < 0) first = index
                last = index
            }
        }
        if (first < 0) {
            // Scrolled past either end (over-scroll, or a stale offset after pages were deleted): compose
            // the nearest page rather than nothing, so the viewport is never blank.
            val nearest = if (viewportTop <= 0f) 0 else pages.lastIndex
            first = nearest
            last = nearest
        }
        return PageWindow(
            firstIndex = max(0, first - overscan),
            lastIndex = min(pages.lastIndex, last + overscan),
            pageTops = tops,
            totalHeight = total,
            widestPage = pages.widestPage(),
        )
    }

    /**
     * The scale that fits the widest page into [viewportWidth] in Adaptive View.
     *
     * Capped at 1: Adaptive View shrinks a page that does not fit, but never magnifies a small page to fill
     * the screen — that would make an A6 card render larger than an A4 sheet, inverting the relationship the
     * user set up.
     */
    fun adaptiveScale(pages: List<DocumentPageBlock>, viewportWidth: Float, padding: Float = 0f): Float {
        val widest = pages.widestPage()
        if (widest <= 0f || viewportWidth <= 0f) return 1f
        return ((viewportWidth - padding) / widest).coerceIn(0.05f, 1f)
    }

    /**
     * The objects of [page] that must actually be composed, in z order (back to front).
     *
     * A page can hold hundreds of objects while only a handful are on screen, so composing all of them makes
     * scrolling a long document cost the whole document. The window is the visible band of the page inflated
     * by [overscan] points, tested against each object's own box.
     *
     * Three exceptions are **always** composed regardless of where they are:
     *
     *  - anything in [keepIds] — a selected object, or one the user is mid-gesture on. Dropping it would tear
     *    the handles out from under a live drag and leave the selection outline pointing at nothing;
     *  - anything whose *preview* is on screen, via [previewOf] — an object dragged from off-page onto the
     *    visible band has stored geometry that is still outside it, and testing only stored geometry would
     *    make the thing being dragged vanish exactly while it is being dragged.
     *
     * Objects are compared by their rotated bounding box, so a rotated frame whose corner reaches into the
     * band is kept rather than being culled on the strength of an axis-aligned box that does not.
     */
    fun visibleObjects(
        page: DocumentPageBlock,
        topPt: Float,
        bottomPt: Float,
        keepIds: Set<String> = emptySet(),
        previewOf: (String) -> SceneGeometry? = { null },
        overscan: Float = OBJECT_OVERSCAN,
    ): List<SceneObject> {
        // A degenerate band (the page has not been measured yet) must not cull everything: an empty page is
        // indistinguishable from an unmeasured one, and rendering nothing looks exactly like data loss. Tested
        // on the *raw* band, before overscan — inflating it first would turn an unmeasured page into a small
        // real one and quietly cull everything outside the overscan.
        if (bottomPt <= topPt) return page.objects
        val top = topPt - overscan
        val bottom = bottomPt + overscan
        return page.objects.filter { obj ->
            if (obj.id in keepIds) return@filter true
            val geometry = previewOf(obj.id) ?: obj.geometry
            val bounds = geometry.boundingBox()
            bounds.bottom >= top && bounds.y <= bottom
        }
    }

    /** How far beyond the visible band an object is still composed, in page points. */
    const val OBJECT_OVERSCAN = 96f
}
