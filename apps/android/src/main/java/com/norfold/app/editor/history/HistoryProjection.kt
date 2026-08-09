package com.norfold.app.editor.history

import com.norfold.app.domain.BulletListBlock
import com.norfold.app.domain.CalloutBlock
import com.norfold.app.domain.ChartBlock
import com.norfold.app.domain.CodeBlock
import com.norfold.app.domain.ContainerBlock
import com.norfold.app.domain.DividerBlock
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.DocumentSettingsBlock
import com.norfold.app.domain.EmbedBlock
import com.norfold.app.domain.FileBlock
import com.norfold.app.domain.HeadingBlock
import com.norfold.app.domain.ImageBlock
import com.norfold.app.domain.InterchangeDataBlock
import com.norfold.app.domain.MathBlock
import com.norfold.app.domain.MermaidBlock
import com.norfold.app.domain.NumberedListBlock
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.QuoteBlock
import com.norfold.app.domain.RelationshipDataBlock
import com.norfold.app.domain.SceneObject
import com.norfold.app.domain.ShapeBlock
import com.norfold.app.domain.StylesheetBlock
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.TodoListBlock
import com.norfold.app.domain.UnknownBlock
import com.norfold.app.domain.style.StyleAssignment

/**
 * The content domain a history entry touched, derived from **real operation metadata** — the actual
 * [DocumentBlock] payloads carried by [EditOperation] — rather than from the English transaction label
 * (spec §13.6; Phase-10 §5). Labels are for humans; classification is for filtering, and the two must not
 * be the same string.
 *
 * A single transaction can carry several domains (a paste that adds a paragraph and a chart is both
 * [TEXT] and [CHART]), which is why [HistoryEntry.domains] is a set.
 */
enum class HistoryDomain {
    TEXT,
    LIST,
    TABLE,
    CHART,
    IMAGE,
    SHAPE,
    MEDIA,
    CODE,
    MATH,
    PAGE,
    OBJECT,
    STYLE,
    STRUCTURE,
    RELATIONSHIP,
    INTERCHANGE,
    UNKNOWN,
}

/**
 * Where an entry sits relative to the user's current position in the one linear history.
 *
 * There is no third stack: [APPLIED] entries are the engine's undo stack and [UNDONE] entries are its redo
 * stack, presented as one timeline so the History UI can show "you are here" instead of pretending undone
 * work disappeared.
 */
enum class HistoryPosition { APPLIED, UNDONE }

/**
 * One row of a History *view*. Derived state only — never a second authoritative record.
 *
 * Deliberately carries **no document text**: [label], [domains], [targets] and id sets are metadata. Spec
 * §13.6 wants filtering and navigation, not a second copy of the document in a side channel that could
 * outlive it or leak into a log. [blockIds]/[objectIds]/[pageIds] are ids, which is what "navigate to what
 * this entry changed" needs and all it needs.
 */
data class HistoryEntry(
    /** Position in the one linear order, oldest = 0, counting applied and undone entries together. */
    val sequence: Int,
    val transactionId: String,
    val label: String,
    val origin: TransactionOrigin,
    val createdAtMillis: Long,
    val position: HistoryPosition,
    val targets: List<EditTarget>,
    val domains: Set<HistoryDomain>,
    val blockIds: Set<String>,
    val pageIds: Set<String>,
    val objectIds: Set<String>,
    val operationCount: Int,
    /** True iff this entry spans more than one [EditTarget] and therefore undoes as one cross-target step. */
    val isCrossTarget: Boolean,
    /** True iff this is the entry a global undo would reverse right now. */
    val isNextUndo: Boolean,
    /** True iff this is the entry a global redo would reapply right now. */
    val isNextRedo: Boolean,
)

/**
 * A read-only view over the one authoritative history (spec §13.6).
 *
 * Built by [HistoryProjection.of] from the engine's applied and undone transactions. Filtering produces
 * another projection; it never mutates, reorders or deletes anything in the engine. "Filtering" that
 * physically removed transactions would silently destroy the invariant Phase 10 exists to protect, so the
 * only operation here is selection.
 */
data class HistoryProjection(val entries: List<HistoryEntry>) {
    val appliedCount: Int get() = entries.count { it.position == HistoryPosition.APPLIED }
    val undoneCount: Int get() = entries.count { it.position == HistoryPosition.UNDONE }
    val isEmpty: Boolean get() = entries.isEmpty()

    /** The entry a global undo would reverse, or null when there is nothing applied. */
    fun nextUndo(): HistoryEntry? = entries.lastOrNull { it.position == HistoryPosition.APPLIED }

    /** The entry a global redo would reapply, or null when nothing has been undone. */
    fun nextRedo(): HistoryEntry? = entries.firstOrNull { it.position == HistoryPosition.UNDONE }

    fun entryBySequence(sequence: Int): HistoryEntry? = entries.firstOrNull { it.sequence == sequence }

    fun filter(filter: HistoryFilter): HistoryProjection =
        HistoryProjection(entries.filter(filter::matches))

    companion object {
        /**
         * Projects the engine's two stacks into one timeline.
         *
         * [applied] is oldest-first (its last element is the next undo). [undone] comes from
         * [HistoryEngine.redoHistory], whose **last** element is the next redo — so it is reversed here to
         * keep the timeline in true chronological order: the most recently undone transaction sits closest
         * to the applied entries, which is where the user left it.
         */
        fun of(applied: List<EditTransaction>, undone: List<EditTransaction> = emptyList()): HistoryProjection {
            val chronological = applied + undone.asReversed()
            val lastAppliedIndex = applied.lastIndex
            val firstUndoneIndex = if (undone.isEmpty()) -1 else applied.size
            val entries = chronological.mapIndexed { index, transaction ->
                val position = if (index <= lastAppliedIndex) HistoryPosition.APPLIED else HistoryPosition.UNDONE
                entryOf(
                    sequence = index,
                    transaction = transaction,
                    position = position,
                    isNextUndo = index == lastAppliedIndex,
                    isNextRedo = index == firstUndoneIndex,
                )
            }
            return HistoryProjection(entries)
        }

        private fun entryOf(
            sequence: Int,
            transaction: EditTransaction,
            position: HistoryPosition,
            isNextUndo: Boolean,
            isNextRedo: Boolean,
        ): HistoryEntry {
            val classification = HistoryClassifier.classify(transaction)
            return HistoryEntry(
                sequence = sequence,
                transactionId = transaction.id,
                label = transaction.label,
                origin = transaction.origin,
                createdAtMillis = transaction.createdAtMillis,
                position = position,
                targets = transaction.targets,
                domains = classification.domains,
                blockIds = classification.blockIds,
                pageIds = classification.pageIds,
                objectIds = classification.objectIds,
                operationCount = transaction.operations.size,
                isCrossTarget = transaction.isCrossTarget,
                isNextUndo = isNextUndo,
                isNextRedo = isNextRedo,
            )
        }
    }
}

/** What [HistoryClassifier] derives from one transaction's real operation payloads. */
data class HistoryClassification(
    val domains: Set<HistoryDomain>,
    val blockIds: Set<String>,
    val pageIds: Set<String>,
    val objectIds: Set<String>,
)

/**
 * Derives filterable metadata from the operations themselves.
 *
 * The rule this file exists to enforce: classification reads block types, ids and style assignments off the
 * [EditOperation] payloads. It never parses [EditTransaction.label], because a label is a localizable human
 * sentence and a filter built on one would break the moment someone reworded a string.
 */
object HistoryClassifier {

    fun classify(transaction: EditTransaction): HistoryClassification {
        val domains = LinkedHashSet<HistoryDomain>()
        val blockIds = LinkedHashSet<String>()
        val pageIds = LinkedHashSet<String>()
        val objectIds = LinkedHashSet<String>()

        for (operation in transaction.operations) {
            when (operation) {
                is EditOperation.InsertBlock -> {
                    domains += HistoryDomain.STRUCTURE
                    collectBlock(operation.block, domains, blockIds, pageIds, objectIds)
                }
                is EditOperation.RemoveBlock -> {
                    domains += HistoryDomain.STRUCTURE
                    collectBlock(operation.block, domains, blockIds, pageIds, objectIds)
                }
                is EditOperation.ReplaceBlock -> {
                    collectReplace(operation.before, operation.after, domains, blockIds, pageIds, objectIds)
                }
                is EditOperation.MoveBlock -> {
                    // A move carries indices, not payloads: reordering is genuinely all this operation knows.
                    domains += HistoryDomain.STRUCTURE
                }
            }
        }
        if (domains.isEmpty()) domains += HistoryDomain.UNKNOWN
        return HistoryClassification(domains, blockIds, pageIds, objectIds)
    }

    private fun collectReplace(
        before: DocumentBlock,
        after: DocumentBlock,
        domains: MutableSet<HistoryDomain>,
        blockIds: MutableSet<String>,
        pageIds: MutableSet<String>,
        objectIds: MutableSet<String>,
    ) {
        blockIds += after.id
        if (before is DocumentPageBlock && after is DocumentPageBlock) {
            collectPageReplace(before, after, domains, pageIds, objectIds)
            return
        }
        domains += domainOf(after)
        if (styleOf(before) != styleOf(after)) domains += HistoryDomain.STYLE
        if (before::class != after::class) domains += HistoryDomain.STRUCTURE
        if (after is DocumentPageBlock) {
            pageIds += after.id
            after.objects.forEach { objectIds += it.id }
        }
    }

    /**
     * Narrows a page replacement to the objects that actually changed.
     *
     * Every scene edit arrives as one whole-page [EditOperation.ReplaceBlock], so without this a filter for
     * "this object" would match every edit on the page — which is the same as no filter at all.
     */
    private fun collectPageReplace(
        before: DocumentPageBlock,
        after: DocumentPageBlock,
        domains: MutableSet<HistoryDomain>,
        pageIds: MutableSet<String>,
        objectIds: MutableSet<String>,
    ) {
        pageIds += after.id
        val beforeById = before.objects.associateBy(SceneObject::id)
        val afterById = after.objects.associateBy(SceneObject::id)

        val changed = LinkedHashSet<SceneObject>()
        afterById.forEach { (id, obj) ->
            val previous = beforeById[id]
            if (previous == null || previous != obj) changed += obj
        }
        beforeById.forEach { (id, obj) -> if (id !in afterById) changed += obj }

        for (obj in changed) {
            objectIds += obj.id
            domains += HistoryDomain.OBJECT
            domains += domainOf(obj.content)
            val previous = beforeById[obj.id]
            if (previous != null && styleOf(previous.content) != styleOf(obj.content)) domains += HistoryDomain.STYLE
        }
        val orderChanged = before.objects.map(SceneObject::id) != after.objects.map(SceneObject::id)
        if (orderChanged) domains += HistoryDomain.STRUCTURE
        if (before.geometry != after.geometry || before.extensions != after.extensions) domains += HistoryDomain.PAGE
        if (domains.isEmpty()) domains += HistoryDomain.PAGE
    }

    private fun collectBlock(
        block: DocumentBlock,
        domains: MutableSet<HistoryDomain>,
        blockIds: MutableSet<String>,
        pageIds: MutableSet<String>,
        objectIds: MutableSet<String>,
    ) {
        blockIds += block.id
        domains += domainOf(block)
        if (block is DocumentPageBlock) {
            pageIds += block.id
            block.objects.forEach {
                objectIds += it.id
                domains += domainOf(it.content)
            }
        }
    }

    /**
     * Maps a block payload to its content domain. Exhaustive over the shipped block vocabulary.
     *
     * Exhaustive matters more here than it looks: a block type that falls through to [HistoryDomain.UNKNOWN]
     * still appears in the unfiltered list, so nothing looks broken — but its own domain filter never matches
     * it, and a user narrowing History to that domain gets a view that silently omits the edits it claims to
     * show. [UnknownBlock] is the one legitimate UNKNOWN: it carries a block written by a newer build, whose
     * domain this build genuinely does not know.
     */
    fun domainOf(block: DocumentBlock): HistoryDomain = when (block) {
        is ParagraphBlock, is HeadingBlock, is QuoteBlock, is CalloutBlock -> HistoryDomain.TEXT
        is BulletListBlock, is NumberedListBlock, is TodoListBlock -> HistoryDomain.LIST
        is TableBlock -> HistoryDomain.TABLE
        is ChartBlock -> HistoryDomain.CHART
        is ImageBlock -> HistoryDomain.IMAGE
        is ShapeBlock -> HistoryDomain.SHAPE
        is FileBlock, is EmbedBlock -> HistoryDomain.MEDIA
        is CodeBlock -> HistoryDomain.CODE
        is MathBlock, is MermaidBlock -> HistoryDomain.MATH
        // Page setup travels with the page: a settings change is what "Page" means to someone filtering for it.
        is DocumentPageBlock, is DocumentSettingsBlock -> HistoryDomain.PAGE
        // The stylesheet is the document's styles; an edit to it belongs under the same lens as a style change
        // on a block, which is the only filter a user would think to reach for.
        is StylesheetBlock -> HistoryDomain.STYLE
        is ContainerBlock, is DividerBlock -> HistoryDomain.STRUCTURE
        is RelationshipDataBlock -> HistoryDomain.RELATIONSHIP
        is InterchangeDataBlock -> HistoryDomain.INTERCHANGE
        is UnknownBlock -> HistoryDomain.UNKNOWN
    }

    /**
     * The style assignment a block carries, or null for blocks that have none.
     *
     * Only blocks that actually declare a [StyleAssignment] can report a style change; returning null
     * elsewhere keeps "restyled" from being claimed about a block that has no style to change.
     */
    private fun styleOf(block: DocumentBlock): StyleAssignment? = when (block) {
        is ParagraphBlock -> block.style
        is HeadingBlock -> block.style
        is BulletListBlock -> block.style
        is NumberedListBlock -> block.style
        is TodoListBlock -> block.style
        is QuoteBlock -> block.style
        is CalloutBlock -> block.style
        is CodeBlock -> block.style
        is ShapeBlock -> block.style
        is UnknownBlock -> null
        else -> null
    }
}
