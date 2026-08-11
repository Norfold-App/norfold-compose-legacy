package com.norfold.app.editor.document

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.ChartBlock
import com.norfold.app.domain.DocCanvasSpec
import com.norfold.app.domain.DocOverlapMode
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.EmbedBlock
import com.norfold.app.domain.FileBlock
import com.norfold.app.domain.FreeformPlacement
import com.norfold.app.domain.ImageBlock
import com.norfold.app.domain.MermaidBlock
import com.norfold.app.domain.PageGeometry
import com.norfold.app.domain.SceneGeometry
import com.norfold.app.domain.SceneObject
import com.norfold.app.domain.SceneObjectKind
import com.norfold.app.domain.ShapeBlock
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.UnknownBlock
import com.norfold.app.domain.isBoundedDocument
import com.norfold.app.domain.pages

/**
 * The copy-on-write migration from the legacy overlap/freeform document to a real bounded scene
 * (roadmap Phase 2 §9).
 *
 * **Never destructively rewrite the only copy.** This object produces a *candidate* — a new
 * [BlockDocument] — and reports what it did. It writes nothing, touches no repository and mutates no
 * workspace, so a caller that is not satisfied with the report simply discards the result and the
 * original artifact is untouched because it was never in the pipeline to begin with. The caller
 * (see [DocumentHostRuntime.migrateLegacyDocument]) takes the recovery snapshot and commits.
 *
 * **Nothing is silently dropped.** The legacy model stores content in the block list and *position* in
 * a side map keyed by block id, which means the two can disagree in both directions:
 *
 *  - a block with no placement — the legacy editor drew it at the origin, so it exists but the user
 *    never positioned it. It is placed in reading order on the page it belongs to rather than being
 *    stacked at 0,0 where it would be invisible under everything else;
 *  - a placement with no block — the content it described is already gone. There is nothing to
 *    migrate, and inventing an empty object to hold the coordinates would produce a phantom the user
 *    cannot identify. It is *reported* as an orphan rather than acted on.
 *
 * Anything this build cannot natively interpret ([UnknownBlock] and friends) becomes a
 * [SceneObjectKind.Preserved] object carrying its content verbatim: it moves, resizes, round-trips
 * and is visibly marked, which is the §9 rule — preserve and mark, never flatten.
 *
 * Pure: no I/O, no Android, no history, no persistence. Every decision it makes is therefore testable
 * against real documents without a device.
 */
object LegacyDocumentMigration {

    /** dp → points. Legacy [FreeformPlacement] is in dp; the scene model is in points (72/inch). */
    private const val POINTS_PER_DP = 72f / 160f

    /** Where an unplaced block lands, and the step between consecutive ones, in points. */
    private const val FLOW_INSET = 36f
    private const val FLOW_STEP = 12f

    /**
     * What a migration did, in enough detail to decide whether to accept it.
     *
     * Deliberately explicit about the lossy edges: [orphanedPlacements] and [preserved] are the two
     * things a caller must be able to see, because they are exactly what a migration that "worked"
     * would otherwise have quietly discarded or reinterpreted.
     */
    data class Report(
        val document: BlockDocument,
        val pageCount: Int,
        val objectCount: Int,
        /** Blocks the legacy layout never positioned; laid out in reading order rather than stacked. */
        val unplacedBlocks: List<String>,
        /** Placements whose block no longer exists. Reported, never turned into empty objects. */
        val orphanedPlacements: List<String>,
        /** Object ids carrying content this build cannot natively interpret — preserved and marked. */
        val preserved: List<String>,
    ) {
        val isLossless: Boolean get() = orphanedPlacements.isEmpty()

        /** A one-line summary for the migration prompt. */
        fun summary(): String = buildString {
            append("$objectCount object${if (objectCount == 1) "" else "s"} across ")
            append("$pageCount page${if (pageCount == 1) "" else "s"}")
            if (preserved.isNotEmpty()) append(" · ${preserved.size} preserved as-is")
            if (orphanedPlacements.isNotEmpty()) append(" · ${orphanedPlacements.size} stale position(s) ignored")
        }

        /**
         * What a refusal is actually refusing over, in terms the owner can act on.
         *
         * Names the first few orphaned placements rather than only counting them: "3 stale positions" tells
         * the owner nothing they can check, whereas the ids are something they can look for. The full list
         * stays available in [orphanedPlacements] — this is the readable projection of it, not a replacement.
         */
        fun refusalDetail(): String {
            if (isLossless) return ""
            val shown = orphanedPlacements.take(3).joinToString(", ")
            val rest = orphanedPlacements.size - 3
            return buildString {
                append("${orphanedPlacements.size} stored position")
                append(if (orphanedPlacements.size == 1) "" else "s")
                append(" no longer matches any content ($shown")
                if (rest > 0) append(", +$rest more")
                append("). Your document is unchanged — nothing was converted or removed.")
            }
        }
    }

    /** True when [document] is already a bounded scene and needs no migration. */
    fun isMigrated(document: BlockDocument): Boolean = document.isBoundedDocument()

    /**
     * Builds the migration candidate for a legacy document.
     *
     * @param document the legacy block list — the content truth.
     * @param layout the legacy `Note.freeformLayout` side map, block id → placement (in dp).
     * @param canvas the legacy page description; its page count and size decide the resulting pages.
     * @param mode the legacy layout mode. [DocOverlapMode.Reflow] documents have no meaningful
     *   placement at all, so every block is laid out in reading order rather than read from a map
     *   that would describe an editor the user never opened.
     */
    fun migrate(
        document: BlockDocument,
        layout: Map<String, FreeformPlacement> = emptyMap(),
        canvas: DocCanvasSpec = DocCanvasSpec(),
        mode: DocOverlapMode = DocOverlapMode.Bounded,
    ): Report {
        if (isMigrated(document)) {
            val pages = document.pages()
            return Report(document, pages.size, pages.sumOf { it.objects.size }, emptyList(), emptyList(), emptyList())
        }

        val spec = canvas.normalized()
        // Document-level records are not visible scene content. In particular, relationship/review
        // metadata must remain a top-level record so Flow -> Document migration cannot turn it into a
        // text frame and sever the canonical Phase-7 store.
        val documentMetadata = document.blocks.filter {
            it is com.norfold.app.domain.DocumentSettingsBlock ||
                it is com.norfold.app.domain.StylesheetBlock ||
                it is com.norfold.app.domain.RelationshipDataBlock ||
                it is com.norfold.app.domain.InterchangeDataBlock
        }
        val contentBlocks = document.blocks.filterNot { it in documentMetadata }
        val effectiveLayout = if (mode == DocOverlapMode.Reflow) emptyMap() else layout
        val blockIds = contentBlocks.mapTo(HashSet()) { it.id }
        val orphans = effectiveLayout.keys.filter { it !in blockIds }.sorted()

        val geometry = pageGeometryOf(spec)
        // The page count must cover both what the canvas declared and how far the content actually
        // reaches: a legacy document could be saved with content below the last declared page, and
        // trusting the declaration alone would put that content on a page that does not exist.
        val pageCount = maxOf(spec.pageCount, spec.pagesRequiredFor(effectiveLayout)).coerceIn(1, 100)
        val buckets = List(pageCount) { mutableListOf<SceneObject>() }
        val unplaced = mutableListOf<String>()
        val preserved = mutableListOf<String>()

        // Unplaced blocks cascade down the first page rather than stacking at the origin, where every
        // one of them would sit exactly under the last and only the top would be visible.
        var flowIndex = 0

        contentBlocks.forEachIndexed { index, block ->
            val placement = effectiveLayout[block.id]
            val pageIndex = placement?.let { spec.rawPageIndexOf(it) }?.coerceIn(0, pageCount - 1) ?: 0
            val sceneGeometry = if (placement != null) {
                geometryOf(placement, spec, pageIndex)
            } else {
                unplaced += block.id
                flowGeometry(flowIndex++, geometry)
            }
            val kind = kindOf(block)
            if (kind == SceneObjectKind.Preserved) preserved += block.id
            buckets[pageIndex] += SceneObject(
                // A new id: a scene object is a new thing that *wraps* the block. Reusing the block's
                // id would make the wrapper and its content indistinguishable to anything that looks
                // up by id, and the block keeps its own identity inside `content` — which is what §9
                // means by preserving block ids where the same object remains.
                id = com.norfold.app.domain.sceneId("obj"),
                kind = kind,
                geometry = sceneGeometry,
                z = placement?.z ?: index,
                content = block,
            )
        }

        val pages = buckets.mapIndexed { index, objects ->
            DocumentPageBlock(
                // The legacy canvas already carries stable page ids; reusing them means a later
                // reconciliation can still recognize the page it is looking at.
                id = spec.pageIds.getOrNull(index) ?: com.norfold.app.domain.sceneId("page"),
                geometry = geometry,
                objects = objects,
            ).normalized()
        }

        return Report(
            document = BlockDocument(documentMetadata + pages).normalized(),
            pageCount = pages.size,
            objectCount = pages.sumOf { it.objects.size },
            unplacedBlocks = unplaced,
            orphanedPlacements = orphans,
            preserved = preserved,
        )
    }

    /**
     * The page geometry a legacy canvas describes.
     *
     * [PageGeometry.structureLocked] is left at its default `true`: a migrated document arrives with
     * its structure protected, so the first thing that happens to imported content is never an
     * accidental reshape of the pages it was laid out against.
     */
    private fun pageGeometryOf(spec: DocCanvasSpec): PageGeometry =
        PageGeometry(preset = spec.preset, width = spec.width, height = spec.height).normalized()

    /**
     * A legacy placement as page-relative scene geometry.
     *
     * The legacy model stores y in one continuous run across all pages, so the page's origin must be
     * subtracted — otherwise every page after the first would place its content thousands of points
     * below the sheet it belongs to.
     */
    private fun geometryOf(
        placement: FreeformPlacement,
        spec: DocCanvasSpec,
        pageIndex: Int,
    ): SceneGeometry = SceneGeometry(
        x = placement.x * POINTS_PER_DP,
        y = (placement.y - spec.pageOrigin(pageIndex)) * POINTS_PER_DP,
        width = placement.width * POINTS_PER_DP,
        height = placement.height * POINTS_PER_DP,
    ).normalized()

    /** Reading-order placement for a block the legacy layout never positioned. */
    private fun flowGeometry(index: Int, page: PageGeometry): SceneGeometry = SceneGeometry(
        x = page.contentLeft + FLOW_INSET + index * FLOW_STEP,
        y = page.contentTop + FLOW_INSET + index * FLOW_STEP,
        width = page.columnWidth,
        height = 120f,
    ).normalized()

    /**
     * The scene kind that matches a block's content.
     *
     * An [UnknownBlock] is content this build genuinely cannot read — a newer schema, or a payload
     * from a future phase. It becomes [SceneObjectKind.Preserved] so it is placed, moved and written
     * back verbatim while being visibly marked as not-natively-understood, rather than being coerced
     * into a text frame that would imply the app knows what it is.
     */
    private fun kindOf(block: DocumentBlock): SceneObjectKind = when (block) {
        is UnknownBlock -> SceneObjectKind.Preserved
        is ImageBlock -> SceneObjectKind.Image
        is ShapeBlock -> SceneObjectKind.Shape
        is TableBlock -> SceneObjectKind.Table
        is ChartBlock -> SceneObjectKind.Chart
        is EmbedBlock, is FileBlock, is MermaidBlock -> SceneObjectKind.Media
        else -> SceneObjectKind.TextFrame
    }
}
