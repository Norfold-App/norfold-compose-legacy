package com.norfold.app.editor.history

import com.norfold.app.domain.BlockDocument
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
import com.norfold.app.domain.InlineText
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
import com.norfold.app.domain.TableCell
import com.norfold.app.domain.TodoListBlock
import com.norfold.app.domain.UnknownBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-10 coverage for the History *view* layer: projection, classification, filtering and step planning
 * (spec §13.1–§13.6).
 *
 * The property every test here defends is the same one: a projection is derived state over the single
 * authoritative history. It never owns a transaction, never reorders one, and "filtering" is selection —
 * so no test in this file may ever observe the engine's own stacks changing as a result of looking at them.
 *
 * Pure JVM: no Android, no Compose, no persistence, no collaboration.
 */
class HistoryProjectionTest {

    private val noteA = EditTarget("note:A")
    private val noteB = EditTarget("note:B")

    private fun para(id: String, text: String = "x") =
        ParagraphBlock(id = id, content = listOf(InlineText(text)))

    private fun heading(id: String, text: String) =
        HeadingBlock(id = id, level = 1, content = listOf(InlineText(text)))

    private fun table(id: String, value: String = "v") = TableBlock(
        id = id,
        headers = listOf(TableCell(listOf(InlineText("H")))),
        rows = listOf(listOf(TableCell(listOf(InlineText(value))))),
    )

    private fun tx(
        id: String,
        vararg operations: EditOperation,
        label: String = id,
        origin: TransactionOrigin = TransactionOrigin.LOCAL_USER,
        createdAt: Long = 1_000L,
    ) = EditTransaction(
        id = id,
        label = label,
        operations = operations.toList(),
        origin = origin,
        createdAtMillis = createdAt,
    )

    private fun insert(block: DocumentBlock, target: EditTarget = noteA, index: Int = 0) =
        EditOperation.InsertBlock(target, index, block)

    private fun replace(before: DocumentBlock, after: DocumentBlock, target: EditTarget = noteA, index: Int = 0) =
        EditOperation.ReplaceBlock(target, index, before, after)

    private data class Engine(val engine: HistoryEngine, val workspace: InMemoryEditWorkspace)

    /** A live engine over two real documents, so projections are proven against the shipping history path. */
    private fun engine(): Engine {
        val workspace = InMemoryEditWorkspace(
            mapOf(noteA to BlockDocument(), noteB to BlockDocument()),
        )
        return Engine(HistoryEngine(workspace), workspace)
    }

    // --- Projection over the one authoritative order --------------------------------------------------

    @Test fun projectionOrdersAppliedOldestFirstThenUndoneInChronologicalOrder() {
        val fixture = engine()
        fixture.engine.commit(tx("t1", insert(para("p1"), index = 0)))
        fixture.engine.commit(tx("t2", insert(para("p2"), index = 1)))
        fixture.engine.commit(tx("t3", insert(para("p3"), index = 2)))
        fixture.engine.undo()
        fixture.engine.undo()

        val projection = HistoryProjection.of(fixture.engine.history(), fixture.engine.redoHistory())

        assertEquals(listOf("t1", "t2", "t3"), projection.entries.map { it.transactionId })
        assertEquals(listOf(0, 1, 2), projection.entries.map { it.sequence })
        assertEquals(1, projection.appliedCount)
        assertEquals(2, projection.undoneCount)
        assertEquals("t1", projection.nextUndo()?.transactionId)
        assertEquals("t2", projection.nextRedo()?.transactionId)
        assertTrue(projection.entries.first { it.transactionId == "t1" }.isNextUndo)
        assertTrue(projection.entries.first { it.transactionId == "t2" }.isNextRedo)
        assertFalse(projection.entries.first { it.transactionId == "t3" }.isNextRedo)
    }

    @Test fun emptyHistoryProjectsNoRowsAndNoNextStep() {
        val projection = HistoryProjection.of(emptyList(), emptyList())
        assertTrue(projection.isEmpty)
        assertNull(projection.nextUndo())
        assertNull(projection.nextRedo())
        assertNull(projection.entryBySequence(0))
    }

    @Test fun buildingAndFilteringAProjectionNeverTouchesTheAuthoritativeStacks() {
        val fixture = engine()
        fixture.engine.commit(tx("t1", insert(para("p1"), index = 0)))
        fixture.engine.commit(tx("t2", insert(table("tbl"), index = 1)))
        val appliedBefore = fixture.engine.history()
        val undoneBefore = fixture.engine.redoHistory()

        val projection = HistoryProjection.of(appliedBefore, undoneBefore)
        val narrowed = projection.filter(HistoryFilter(scope = HistoryScope.OneDomain(HistoryDomain.TABLE)))

        assertEquals(1, narrowed.entries.size)
        assertEquals(2, projection.entries.size)
        assertEquals("Filtering removed no transaction", appliedBefore, fixture.engine.history())
        assertEquals(undoneBefore, fixture.engine.redoHistory())
        assertEquals(2, fixture.engine.undoDepth())
    }

    // --- Classification comes from real operation metadata, never from the label -----------------------

    @Test fun domainsAreDerivedFromOperationPayloadsNotFromTheTransactionLabel() {
        val misleading = tx("t1", insert(para("p1")), label = "Insert chart")
        val entry = HistoryProjection.of(listOf(misleading)).entries.single()

        assertTrue("A paragraph insert is TEXT however it was labelled", HistoryDomain.TEXT in entry.domains)
        assertTrue(HistoryDomain.STRUCTURE in entry.domains)
        assertFalse("The word 'chart' in a label must not classify the entry", HistoryDomain.CHART in entry.domains)
    }

    @Test fun oneTransactionCarriesEveryDomainItActuallyTouched() {
        val paste = tx(
            "t1",
            insert(para("p1"), index = 0),
            insert(ChartBlock(id = "c1"), index = 1),
            insert(table("tbl"), index = 2),
            label = "Paste",
        )
        val entry = HistoryProjection.of(listOf(paste)).entries.single()

        assertEquals(
            setOf(HistoryDomain.STRUCTURE, HistoryDomain.TEXT, HistoryDomain.CHART, HistoryDomain.TABLE),
            entry.domains,
        )
        assertEquals(setOf("p1", "c1", "tbl"), entry.blockIds)
        assertEquals(3, entry.operationCount)
    }

    @Test fun replacingABlockWithADifferentTypeReportsBothStructureAndTheNewDomain() {
        val entry = HistoryProjection.of(listOf(tx("t1", replace(para("p1"), heading("p1", "Title"))))).entries.single()
        assertTrue(HistoryDomain.TEXT in entry.domains)
        assertTrue("A type change is structural", HistoryDomain.STRUCTURE in entry.domains)
    }

    @Test fun aMoveOperationClassifiesAsStructureOnly() {
        val move = tx("t1", EditOperation.MoveBlock(noteA, fromIndex = 0, toIndex = 1))
        val entry = HistoryProjection.of(listOf(move)).entries.single()
        assertEquals(setOf(HistoryDomain.STRUCTURE), entry.domains)
        assertTrue(entry.blockIds.isEmpty())
    }

    @Test fun aPageReplaceNarrowsToTheObjectsThatActuallyChanged() {
        val kept = SceneObject(id = "obj-kept", content = para("k1", "kept"))
        val edited = SceneObject(id = "obj-edited", content = para("e1", "before"))
        val before = DocumentPageBlock(id = "page-1", objects = listOf(kept, edited))
        val after = before.copy(objects = listOf(kept, edited.copy(content = para("e1", "after"))))

        val entry = HistoryProjection.of(listOf(tx("t1", replace(before, after)))).entries.single()

        assertEquals("Only the changed object is in scope", setOf("obj-edited"), entry.objectIds)
        assertEquals(setOf("page-1"), entry.pageIds)
        assertTrue(HistoryDomain.OBJECT in entry.domains)
    }

    @Test fun anUnclassifiableTransactionIsUnknownRatherThanEmpty() {
        val unknown = UnknownBlock(id = "u1", rawJson = """{"v":1,"block":{"kind":"future","id":"u1"}}""")
        val entry = HistoryProjection.of(listOf(tx("t1", replace(unknown, unknown)))).entries.single()
        assertEquals(setOf(HistoryDomain.UNKNOWN), entry.domains)
    }

    /**
     * A domain filter is only as complete as the classifier behind it.
     *
     * Classification is a `when` over block types, and an unlisted type falls through to
     * [HistoryDomain.UNKNOWN]. The consequence is quiet and specific: edits to that block keep working and
     * keep appearing in the unfiltered list, so nothing looks broken — but the block's own domain filter
     * never matches them, and a user narrowing History to "Charts" or "Tables" is shown a view that is
     * missing edits it claims to include. That is worse than an empty filter, because it reads as complete.
     *
     * Enumerating [DocumentBlock.sealedSubclasses] is what makes this hold over time: adding a block type is
     * a compile-time fact, so a type introduced later and never classified fails here rather than shipping as
     * a silently incomplete filter. A type with no [sample] is failed rather than skipped — skipping is how a
     * new block type would slip past a test whose whole job is to notice new block types. [UnknownBlock] is
     * excluded because being unclassifiable is its purpose: it is the carrier for a block a newer build wrote.
     */
    @Test fun everyBlockTypeIsClassifiedIntoADomainSoNoFilterSilentlyOmitsIt() {
        val unclassifiable = setOf(UnknownBlock::class)
        val subtypes = DocumentBlock::class.sealedSubclasses.filterNot { it in unclassifiable }
        assertTrue("the enumeration must actually find the block types", subtypes.size >= 15)

        val unsampled = subtypes.filter { sample(it) == null }
        assertEquals(
            "a new block type needs a sample here, or this test stops covering it",
            emptyList<String>(),
            unsampled.map { it.simpleName.orEmpty() },
        )

        val unclassified = subtypes.filter { type ->
            val entry = HistoryProjection.of(listOf(tx("t1", insert(checkNotNull(sample(type)))))).entries.single()
            entry.domains == setOf(HistoryDomain.UNKNOWN)
        }
        assertEquals(
            "every block type must classify into a domain a filter can select",
            emptyList<String>(),
            unclassified.map { it.simpleName.orEmpty() },
        )
    }

    /** One instance of [type] for the classification sweep, or null when this test has no sample for it. */
    private fun sample(type: kotlin.reflect.KClass<out DocumentBlock>): DocumentBlock? = when (type) {
        ParagraphBlock::class -> para("b1", "text")
        HeadingBlock::class -> HeadingBlock(id = "b1", level = 1, content = listOf(InlineText("h")))
        QuoteBlock::class -> QuoteBlock(id = "b1")
        CalloutBlock::class -> CalloutBlock(id = "b1")
        BulletListBlock::class -> BulletListBlock(id = "b1", items = emptyList())
        NumberedListBlock::class -> NumberedListBlock(id = "b1", items = emptyList())
        TodoListBlock::class -> TodoListBlock(id = "b1", items = emptyList())
        TableBlock::class -> TableBlock(id = "b1", headers = emptyList(), rows = emptyList())
        ChartBlock::class -> ChartBlock(id = "b1")
        ImageBlock::class -> ImageBlock(id = "b1", source = "photo.jpg")
        ShapeBlock::class -> ShapeBlock(id = "b1")
        FileBlock::class -> FileBlock(id = "b1")
        EmbedBlock::class -> EmbedBlock(id = "b1")
        CodeBlock::class -> CodeBlock(id = "b1", code = "x = 1")
        MathBlock::class -> MathBlock(id = "b1")
        MermaidBlock::class -> MermaidBlock(id = "b1")
        DividerBlock::class -> DividerBlock(id = "b1")
        ContainerBlock::class -> ContainerBlock(id = "b1", children = emptyList())
        DocumentPageBlock::class -> DocumentPageBlock(id = "b1", objects = emptyList())
        DocumentSettingsBlock::class -> DocumentSettingsBlock(id = "b1")
        StylesheetBlock::class -> StylesheetBlock(id = "b1")
        RelationshipDataBlock::class -> RelationshipDataBlock(id = "b1", payload = "{}")
        InterchangeDataBlock::class -> InterchangeDataBlock(id = "b1", payload = "{}")
        else -> null
    }

    // --- A history row is metadata. It is never a side channel for document content -------------------

    @Test fun entriesCarryIdsAndLabelsButNeverDocumentText() {
        val secret = "patient-record-4471"
        val transaction = tx("t1", insert(para("p1", secret)), label = "Typing")

        val entry = HistoryProjection.of(listOf(transaction)).entries.single()

        assertFalse(
            "A history row must not carry document content in any field",
            entry.toString().contains(secret),
        )
        assertEquals("Typing", entry.label)
        assertEquals(setOf("p1"), entry.blockIds)
    }

    // --- Filtering is selection: scope, provenance, query ---------------------------------------------

    @Test fun everyScopeVariantSelectsOnlyTheEntriesThatTouchedIt() {
        val page = DocumentPageBlock(id = "page-1", objects = listOf(SceneObject(id = "obj-1")))
        val history = listOf(
            tx("text", insert(para("p1"))),
            tx("chart", insert(ChartBlock(id = "c1"), index = 1)),
            tx("page", insert(page, index = 2)),
            tx("other", insert(para("p2"), target = noteB)),
        )
        val projection = HistoryProjection.of(history)

        fun ids(scope: HistoryScope) =
            projection.filter(HistoryFilter(scope = scope)).entries.map { it.transactionId }

        assertEquals(listOf("text", "chart", "page"), ids(HistoryScope.OneTarget(noteA)))
        assertEquals(listOf("other"), ids(HistoryScope.OneTarget(noteB)))
        assertEquals(listOf("chart"), ids(HistoryScope.OneBlock("c1")))
        assertEquals(listOf("page"), ids(HistoryScope.OnePage("page-1")))
        assertEquals(listOf("page"), ids(HistoryScope.OneObject("obj-1")))
        assertEquals(listOf("chart"), ids(HistoryScope.OneDomain(HistoryDomain.CHART)))
        assertEquals(4, ids(HistoryScope.WholeArtifact).size)
    }

    @Test fun onlyMineExcludesMergedRemoteChangesAndNothingElse() {
        val history = listOf(
            tx("local", insert(para("p1"))),
            tx("import", insert(para("p2"), index = 1), origin = TransactionOrigin.IMPORT),
            tx("remote", insert(para("p3"), index = 2), origin = TransactionOrigin.REMOTE_MERGE),
        )
        val mine = HistoryProjection.of(history).filter(HistoryFilter(onlyMine = true))

        assertEquals(listOf("local", "import"), mine.entries.map { it.transactionId })
    }

    @Test fun originFilterSelectsExactlyTheRequestedProvenances() {
        val history = listOf(
            tx("local", insert(para("p1"))),
            tx("migration", insert(para("p2"), index = 1), origin = TransactionOrigin.MIGRATION),
            tx("restore", insert(para("p3"), index = 2), origin = TransactionOrigin.RECOVERY_RESTORE),
        )
        val filtered = HistoryProjection.of(history)
            .filter(HistoryFilter(origins = setOf(TransactionOrigin.MIGRATION, TransactionOrigin.RECOVERY_RESTORE)))

        assertEquals(listOf("migration", "restore"), filtered.entries.map { it.transactionId })
    }

    @Test fun queryMatchesLabelsAndDomainNamesCaseInsensitivelyAndNeverContent() {
        val history = listOf(
            tx("typing", insert(para("p1", "confidential")), label = "Typing"),
            tx("charted", insert(ChartBlock(id = "c1"), index = 1), label = "Insert block"),
        )
        val projection = HistoryProjection.of(history)

        assertEquals(listOf("typing"), projection.filter(HistoryFilter(query = "tYpI")).entries.map { it.transactionId })
        assertEquals(listOf("charted"), projection.filter(HistoryFilter(query = "chart")).entries.map { it.transactionId })
        assertTrue(
            "Document text is not searchable from History",
            projection.filter(HistoryFilter(query = "confidential")).entries.isEmpty(),
        )
    }

    @Test fun aDefaultFilterIsTheWholeArtifactAndReportsItself() {
        assertTrue(HistoryFilter().isDefault)
        assertFalse(HistoryFilter(onlyMine = true).isDefault)
        assertFalse(HistoryFilter(query = "x").isDefault)
        assertFalse(HistoryFilter(scope = HistoryScope.OneBlock("b")).isDefault)
        assertFalse(HistoryScope.WholeArtifact.isContextual)
        assertTrue(HistoryScope.OneBlock("b").isContextual)
    }

    // --- Step planning: no mid-stack removal, ever ----------------------------------------------------

    @Test fun globalUndoPlansTheNewestEntryAndDisclosesNothing() {
        val projection = HistoryProjection.of(
            listOf(tx("t1", insert(para("p1"))), tx("t2", insert(para("p2"), index = 1))),
        )
        val plan = HistoryStepPlanner.planUndo(projection) as HistoryStepPlan.Ready

        assertEquals("t2", plan.requested.transactionId)
        assertTrue(plan.intervening.isEmpty())
        assertFalse(plan.requiresDisclosure)
        assertEquals(listOf("t2"), plan.steps.map { it.transactionId })
    }

    @Test fun contextualUndoNamesEveryNewerTransactionItMustReverseFirst() {
        val projection = HistoryProjection.of(
            listOf(
                tx("chart", insert(ChartBlock(id = "c1"))),
                tx("text1", insert(para("p1"), index = 1)),
                tx("text2", insert(para("p2"), index = 2)),
            ),
        )
        val plan = HistoryStepPlanner.planUndo(
            projection,
            HistoryFilter(scope = HistoryScope.OneDomain(HistoryDomain.CHART)),
        ) as HistoryStepPlan.Ready

        assertEquals("chart", plan.requested.transactionId)
        assertEquals(
            "Newest first: a contextual undo reverses the stack in order, it never cuts one out",
            listOf("text2", "text1"),
            plan.intervening.map { it.transactionId },
        )
        assertEquals(listOf("text2", "text1", "chart"), plan.steps.map { it.transactionId })
        assertTrue(plan.requiresDisclosure)
    }

    @Test fun contextualUndoOfTheNewestEntryNeedsNoDisclosure() {
        val projection = HistoryProjection.of(
            listOf(tx("text", insert(para("p1"))), tx("chart", insert(ChartBlock(id = "c1"), index = 1))),
        )
        val plan = HistoryStepPlanner.planUndo(
            projection,
            HistoryFilter(scope = HistoryScope.OneDomain(HistoryDomain.CHART)),
        ) as HistoryStepPlan.Ready

        assertEquals("chart", plan.requested.transactionId)
        assertTrue(plan.intervening.isEmpty())
        assertFalse(plan.requiresDisclosure)
    }

    @Test fun contextualRedoWalksForwardThroughEverythingBetween() {
        val fixture = engine()
        fixture.engine.commit(tx("chart", insert(ChartBlock(id = "c1"), index = 0)))
        fixture.engine.commit(tx("text1", insert(para("p1"), index = 1)))
        fixture.engine.commit(tx("text2", insert(para("p2"), index = 2)))
        repeat(3) { fixture.engine.undo() }

        val projection = HistoryProjection.of(fixture.engine.history(), fixture.engine.redoHistory())
        val plan = HistoryStepPlanner.planRedo(
            projection,
            HistoryFilter(scope = HistoryScope.OneBlock("p2")),
        ) as HistoryStepPlan.Ready

        assertEquals("text2", plan.requested.transactionId)
        assertEquals(listOf("chart", "text1"), plan.intervening.map { it.transactionId })
        assertTrue(plan.requiresDisclosure)
    }

    @Test fun aScopeWithNoMatchingEntryIsUnavailableWithItsOwnBlocker() {
        val projection = HistoryProjection.of(listOf(tx("t1", insert(para("p1")))))
        val plan = HistoryStepPlanner.planUndo(
            projection,
            HistoryFilter(scope = HistoryScope.OneBlock("never-touched")),
        )
        assertEquals(
            HistoryStepPlan.Unavailable(HistoryDirection.UNDO, HistoryStepBlocker.NOTHING_IN_SCOPE),
            plan,
        )
    }

    @Test fun anEmptyHistoryIsNothingRecordedInBothDirections() {
        val empty = HistoryProjection.of(emptyList())
        assertEquals(
            HistoryStepPlan.Unavailable(HistoryDirection.UNDO, HistoryStepBlocker.NOTHING_RECORDED),
            HistoryStepPlanner.planUndo(empty),
        )
        assertEquals(
            HistoryStepPlan.Unavailable(HistoryDirection.REDO, HistoryStepBlocker.NOTHING_RECORDED),
            HistoryStepPlanner.planRedo(empty),
        )
    }

    @Test fun redoIsNothingRecordedWhileEverythingIsStillApplied() {
        val projection = HistoryProjection.of(listOf(tx("t1", insert(para("p1")))))
        assertEquals(
            HistoryStepPlan.Unavailable(HistoryDirection.REDO, HistoryStepBlocker.NOTHING_RECORDED),
            HistoryStepPlanner.planRedo(projection),
        )
    }

    // --- Cross-target steps (§13.4) -------------------------------------------------------------------

    @Test fun aCrossTargetStepUnderAOneTargetScopeDisclosesTheOtherTargets() {
        val crossTarget = tx(
            "cross",
            insert(para("p1"), target = noteA),
            insert(para("p2"), target = noteB),
            label = "Move between notes",
        )
        val projection = HistoryProjection.of(listOf(crossTarget))
        assertTrue(projection.entries.single().isCrossTarget)

        val plan = HistoryStepPlanner.planUndo(
            projection,
            HistoryFilter(scope = HistoryScope.OneTarget(noteA)),
        ) as HistoryStepPlan.Ready

        assertEquals(listOf(noteB), plan.extraTargets)
        assertTrue("Reaching outside the scoped target must be disclosed", plan.requiresDisclosure)
    }

    @Test fun theSameCrossTargetStepUnderTheWholeArtifactScopeDisclosesNothing() {
        val crossTarget = tx("cross", insert(para("p1"), target = noteA), insert(para("p2"), target = noteB))
        val plan = HistoryStepPlanner.planUndo(HistoryProjection.of(listOf(crossTarget))) as HistoryStepPlan.Ready

        assertTrue(
            "The engine reverses a cross-target transaction atomically; asking for everything discloses nothing",
            plan.extraTargets.isEmpty(),
        )
        assertFalse(plan.requiresDisclosure)
    }

    @Test fun aCrossTargetStepUnderANonTargetContextualScopeStillDisclosesItsReach() {
        val crossTarget = tx("cross", insert(para("p1"), target = noteA), insert(para("p2"), target = noteB))
        val plan = HistoryStepPlanner.planUndo(
            HistoryProjection.of(listOf(crossTarget)),
            HistoryFilter(scope = HistoryScope.OneBlock("p1")),
        ) as HistoryStepPlan.Ready

        assertEquals(listOf(noteA, noteB), plan.extraTargets)
        assertTrue(plan.requiresDisclosure)
    }

    // --- The HistoryHost seam is the engine's own, not a copy of it -----------------------------------

    @Test fun theHostSeamReadsAndMovesTheSameEngineItWasBuiltFrom() {
        val fixture = engine()
        val host = HistoryHost.of(fixture.engine)
        fixture.engine.commit(tx("t1", insert(para("p1"))))

        assertEquals(fixture.engine.history(), host.appliedHistory())
        assertEquals("t1", host.undo()?.id)
        assertEquals(0, fixture.engine.undoDepth())
        assertEquals(1, fixture.engine.redoDepth())
        assertEquals("t1", host.redo()?.id)
        assertEquals(1, fixture.engine.undoDepth())
        assertNull("Nothing left to redo", host.redo())
    }

    @Test fun routesReportWhetherHistoryActuallyMoved() {
        assertTrue(HistoryRoute.GLOBAL.moved)
        assertTrue(HistoryRoute.LOCAL_FOCUS.moved)
        assertFalse("Consumed is not moved", HistoryRoute.BLOCKED_BY_FOCUS.moved)
        assertFalse(HistoryRoute.UNAVAILABLE.moved)
    }

    @Test fun projectingTheSameStacksTwiceProducesTheSameRows() {
        val fixture = engine()
        fixture.engine.commit(tx("t1", insert(para("p1"))))
        fixture.engine.commit(tx("t2", insert(para("p2"), index = 1)))
        fixture.engine.undo()

        val first = HistoryProjection.of(fixture.engine.history(), fixture.engine.redoHistory())
        val second = HistoryProjection.of(fixture.engine.history(), fixture.engine.redoHistory())

        assertEquals("A projection is a pure function of the two stacks", first, second)
    }
}
