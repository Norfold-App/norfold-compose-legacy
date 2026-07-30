package com.norfold.app.editor.document

import com.norfold.app.domain.DocumentPageBlock
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

    fun setIntent(next: WorkingIntent) {
        intent = next
    }

    fun setRepresentation(next: LayoutRepresentation) {
        representation = next
    }

    fun setZoom(next: Float) {
        zoom = next.coerceIn(MIN_ZOOM, MAX_ZOOM)
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

    /** Updates a resize preview for the single object being resized. */
    fun updateResize(
        geometry: SceneGeometry,
        page: DocumentPageBlock?,
        movingRight: Boolean,
        movingBottom: Boolean,
    ): ActiveManipulation? {
        val active = manipulation ?: return null
        if (active.kind != ActiveManipulation.Kind.RESIZE) return active
        val objectId = active.objectIds.first()
        val snapped = page?.let {
            SnapGuideEngine.snapResize(geometry, it, movingRight, movingBottom, setOf(objectId))
        }
        return active.copy(
            preview = mapOf(objectId to (snapped?.geometry ?: geometry.normalized())),
            guides = snapped?.guides.orEmpty(),
        ).also { manipulation = it }
    }

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
}
