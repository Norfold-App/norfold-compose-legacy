package com.norfold.app.editor.selection

import com.norfold.app.editor.command.ApplicabilityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 0 D5 validation-gate coverage for the [SelectionEngine] across all selection domains
 * (spec §4.5–§4.14). Proves: every domain's granularities are representable; primary/secondary semantics;
 * distinct text/structural state; remote-selection isolation from local state; honest applicability
 * resolution; and deterministic post-command selection. Pure JVM.
 */
class SelectionEngineTest {

    private fun t(id: String, g: SelectionGranularity, locked: LockScope = LockScope.NONE, hidden: Boolean = false) =
        SelectionTarget(id, g, locked, hidden)

    // --- All selection domains representable (§4.6) -------------------------------------------------

    @Test fun everyDomain_hasSelectableGranularities_andEngineHoldsThem() {
        val samples = mapOf(
            SelectionDomain.TEXT to SelectionGranularity.TEXT_PARAGRAPH,
            SelectionDomain.FLOW to SelectionGranularity.FLOW_BLOCK,
            SelectionDomain.DOCUMENT to SelectionGranularity.DOC_OBJECT,
            SelectionDomain.TABLE to SelectionGranularity.TABLE_CELL,
            SelectionDomain.CHART to SelectionGranularity.CHART_MARK,
            SelectionDomain.RELATIONSHIP to SelectionGranularity.RELATIONSHIP_ENDPOINT,
            SelectionDomain.APP_ITEM to SelectionGranularity.APP_TASK,
        )
        // Spec §4.6 enumerates all seven domains; assert each is covered and round-trips through the engine.
        assertEquals(SelectionDomain.entries.toSet(), samples.keys)
        for ((domain, gran) in samples) {
            val engine = SelectionEngine()
            engine.select(t("x", gran))
            assertEquals(domain, engine.primary()!!.domain)
        }
    }

    @Test fun granularity_domainTagging_isConsistent() {
        assertTrue(SelectionGranularity.entries.all { it.domain in SelectionDomain.entries })
        assertEquals(SelectionDomain.TABLE, SelectionGranularity.TABLE_COLUMN.domain)
        assertEquals(SelectionDomain.CHART, SelectionGranularity.CHART_SERIES.domain)
    }

    // --- Primary / secondary (§4.9) -----------------------------------------------------------------

    @Test fun addingToSelection_neverSilentlyReplacesPrimary() {
        val engine = SelectionEngine()
        engine.select(t("a", SelectionGranularity.DOC_OBJECT))
        engine.addToSelection(t("b", SelectionGranularity.DOC_OBJECT))
        engine.addToSelection(t("c", SelectionGranularity.DOC_OBJECT))
        assertEquals("a", engine.primary()!!.id)
        assertEquals(listOf("b", "c"), engine.secondaries().map { it.id })
        assertTrue(engine.isMultiSelect)
    }

    @Test fun promote_changesPrimaryOnlyOnExplicitAct() {
        val engine = SelectionEngine()
        engine.select(t("a", SelectionGranularity.DOC_OBJECT))
        engine.addToSelection(t("b", SelectionGranularity.DOC_OBJECT))
        engine.promote("b")
        assertEquals("b", engine.primary()!!.id)
        assertEquals(listOf("a"), engine.secondaries().map { it.id })
    }

    @Test fun promote_unknownTarget_throws() {
        val engine = SelectionEngine()
        engine.select(t("a", SelectionGranularity.DOC_OBJECT))
        assertThrows(IllegalArgumentException::class.java) { engine.promote("ghost") }
    }

    @Test fun deselectingPrimary_promotesNextRemaining() {
        val engine = SelectionEngine()
        engine.select(t("a", SelectionGranularity.DOC_OBJECT))
        engine.addToSelection(t("b", SelectionGranularity.DOC_OBJECT))
        engine.deselect("a")
        assertEquals("b", engine.primary()!!.id)
        assertEquals(1, engine.count)
    }

    @Test fun toggle_addsAndRemoves() {
        val engine = SelectionEngine()
        engine.toggle(t("a", SelectionGranularity.FLOW_BLOCK))
        assertEquals(1, engine.count)
        engine.toggle(t("a", SelectionGranularity.FLOW_BLOCK))
        assertEquals(0, engine.count)
        assertNull(engine.primary())
    }

    @Test fun selection_returnsPrimaryFirst() {
        val engine = SelectionEngine()
        engine.select(t("a", SelectionGranularity.DOC_OBJECT))
        engine.addToSelection(t("b", SelectionGranularity.DOC_OBJECT))
        engine.promote("b")
        assertEquals(listOf("b", "a"), engine.selection().map { it.id })
    }

    @Test fun mixedDomainSelection_reportsAllDomains() {
        val engine = SelectionEngine()
        engine.select(t("cell", SelectionGranularity.TABLE_CELL))
        engine.addToSelection(t("obj", SelectionGranularity.DOC_OBJECT))
        assertEquals(setOf(SelectionDomain.TABLE, SelectionDomain.DOCUMENT), engine.domains())
    }

    // --- Distinct text caret / range / structural state (§4.5) --------------------------------------

    @Test fun caretAndRange_areMutuallyExclusive_butSeparateFromStructural() {
        val engine = SelectionEngine()
        engine.setStructural(t("frame", SelectionGranularity.DOC_FRAME))
        engine.setCaret(TextCaret("frame", 3))
        assertEquals(3, engine.caret()!!.offset)
        assertNull("Setting a caret clears any range", engine.textRange())
        // Structural selection is a distinct field and is untouched by caret changes.
        assertEquals("frame", engine.structural()!!.id)

        engine.setTextRange(TextRange("frame", 1, 5))
        assertNull("Setting a range clears the caret", engine.caret())
        assertEquals(4, engine.textRange()!!.end - engine.textRange()!!.start)
    }

    @Test fun textRange_validation() {
        assertThrows(IllegalArgumentException::class.java) { TextRange("x", 5, 2) }
        assertTrue(TextRange("x", 2, 2).isCollapsed)
    }

    // --- Remote selection isolation (§4.5) ----------------------------------------------------------

    @Test fun remoteSelection_neverLeaksIntoLocalSelection() {
        val engine = SelectionEngine()
        engine.select(t("local", SelectionGranularity.DOC_OBJECT))
        engine.setRemoteSelection("alice", listOf(t("r1", SelectionGranularity.DOC_OBJECT), t("r2", SelectionGranularity.TABLE_CELL)))

        // Local queries see only the local target.
        assertEquals(listOf("local"), engine.selection().map { it.id })
        assertEquals(1, engine.count)
        // Remote is retrievable only through the remote API, keyed by collaborator.
        assertEquals(listOf("r1", "r2"), engine.remoteSelection("alice").map { it.id })
        assertEquals(setOf("alice"), engine.remoteCollaborators())
    }

    @Test fun clearingLocalSelection_doesNotAffectRemote_andViceVersa() {
        val engine = SelectionEngine()
        engine.select(t("local", SelectionGranularity.DOC_OBJECT))
        engine.setRemoteSelection("bob", listOf(t("r", SelectionGranularity.DOC_OBJECT)))
        engine.clear()
        assertTrue(engine.selection().isEmpty())
        assertEquals(listOf("r"), engine.remoteSelection("bob").map { it.id })

        engine.clearRemoteSelection("bob")
        assertTrue(engine.remoteSelection("bob").isEmpty())
    }

    // --- Locked / hidden stay selectable (§4.13) ----------------------------------------------------

    @Test fun lockedAndHiddenTargets_remainSelectable() {
        val engine = SelectionEngine()
        engine.select(t("locked", SelectionGranularity.DOC_OBJECT, locked = LockScope.FULL))
        engine.addToSelection(t("hidden", SelectionGranularity.DOC_OBJECT, hidden = true))
        assertEquals(2, engine.count)
        assertEquals(LockScope.FULL, engine.primary()!!.locked)
    }

    // --- Applicability resolution (§4.10) -----------------------------------------------------------

    @Test fun allTargets_policy_requiresEveryTargetEligible() {
        val engine = SelectionEngine()
        engine.select(t("a", SelectionGranularity.DOC_OBJECT))
        engine.addToSelection(t("b", SelectionGranularity.DOC_OBJECT))
        val allEligible = engine.resolveApplicability(ApplicabilityPolicy.ALL_TARGETS) { true }
        assertEquals(ApplicabilityResolution.AllTargets(2), allEligible)
        val oneIneligible = engine.resolveApplicability(ApplicabilityPolicy.ALL_TARGETS) { it.id == "a" }
        assertEquals(ApplicabilityResolution.NotApplicable, oneIneligible)
    }

    @Test fun eligibleSubset_reportsAffectedAndTotal_honestly() {
        val engine = SelectionEngine()
        engine.select(t("a", SelectionGranularity.DOC_OBJECT))
        engine.addToSelection(t("b", SelectionGranularity.DOC_OBJECT))
        engine.addToSelection(t("c", SelectionGranularity.DOC_OBJECT))
        val result = engine.resolveApplicability(ApplicabilityPolicy.ELIGIBLE_SUBSET) { it.id != "c" }
        assertEquals(ApplicabilityResolution.EligibleSubset(2, 3), result)
    }

    @Test fun eligibleSubset_withNoEligible_isNotApplicable() {
        val engine = SelectionEngine()
        engine.select(t("a", SelectionGranularity.DOC_OBJECT))
        assertEquals(
            ApplicabilityResolution.NotApplicable,
            engine.resolveApplicability(ApplicabilityPolicy.ELIGIBLE_SUBSET) { false },
        )
    }

    @Test fun primaryTarget_policy_actsOnPrimaryOnly() {
        val engine = SelectionEngine()
        engine.select(t("a", SelectionGranularity.DOC_OBJECT))
        engine.addToSelection(t("b", SelectionGranularity.DOC_OBJECT))
        assertEquals(
            ApplicabilityResolution.PrimaryOnly("a"),
            engine.resolveApplicability(ApplicabilityPolicy.PRIMARY_TARGET) { true },
        )
    }

    @Test fun asUnit_policy_treatsWholeSetAsComposite() {
        val engine = SelectionEngine()
        engine.select(t("a", SelectionGranularity.DOC_OBJECT))
        engine.addToSelection(t("b", SelectionGranularity.DOC_OBJECT))
        assertEquals(
            ApplicabilityResolution.AsUnit(2),
            engine.resolveApplicability(ApplicabilityPolicy.SELECTION_AS_UNIT) { true },
        )
    }

    @Test fun applicability_onEmptySelection_isNotApplicable() {
        val engine = SelectionEngine()
        assertEquals(
            ApplicabilityResolution.NotApplicable,
            engine.resolveApplicability(ApplicabilityPolicy.ALL_TARGETS) { true },
        )
    }

    // --- Selection after commands (§4.14) -----------------------------------------------------------

    @Test fun afterDuplicate_resultBecomesSelected() {
        val engine = SelectionEngine()
        engine.select(t("orig", SelectionGranularity.DOC_OBJECT))
        val after = engine.selectionAfter(
            PostCommandOutcome.SelectResult(listOf(t("copy", SelectionGranularity.DOC_OBJECT))),
        )
        assertEquals(listOf("copy"), after.map { it.id })
        assertEquals("copy", engine.primary()!!.id)
    }

    @Test fun afterGroup_groupIsPrimary_ofResultSet() {
        val engine = SelectionEngine()
        engine.select(t("a", SelectionGranularity.DOC_OBJECT))
        engine.addToSelection(t("b", SelectionGranularity.DOC_OBJECT))
        val after = engine.selectionAfter(
            PostCommandOutcome.SelectResult(listOf(t("group", SelectionGranularity.DOC_GROUP))),
        )
        assertEquals(listOf("group"), after.map { it.id })
    }

    @Test fun afterInsertText_caretPlacedInside() {
        val engine = SelectionEngine()
        val after = engine.selectionAfter(PostCommandOutcome.CaretInside(TextCaret("new", 0)))
        assertTrue("Caret-inside outcome leaves no object selection", after.isEmpty())
        assertEquals(TextCaret("new", 0), engine.caret())
    }

    @Test fun afterDelete_nearestSiblingSelected_orEmptyWhenNoneRemains() {
        val engine = SelectionEngine()
        engine.select(t("deleted", SelectionGranularity.FLOW_BLOCK))
        val after = engine.selectionAfter(
            PostCommandOutcome.SelectNearestSibling(t("sibling", SelectionGranularity.FLOW_BLOCK)),
        )
        assertEquals(listOf("sibling"), after.map { it.id })

        val empty = engine.selectionAfter(PostCommandOutcome.SelectNearestSibling(null))
        assertTrue(empty.isEmpty())
        assertNull(engine.primary())
    }
}
