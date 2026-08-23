package com.norfold.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Calendar import: the half that can be certified without a provider.
 *
 * Nothing here needs Google or Microsoft to be reachable, and that is the point — the reconciliation
 * is where an import goes wrong, and every one of its failures is silent. A duplicate is an event
 * the owner sees twice with no error anywhere. A wrongly inferred deletion removes an appointment
 * and reports success. Neither can be found by watching the network.
 *
 * Two pins carry more weight than the rest:
 *
 * - [anIncrementalRoundNeverInfersDeletionFromAbsence], because the delta protocols send only what
 *   changed, so treating absence as deletion empties the calendar after the first quiet week;
 * - [aFullRoundLeavesOtherSourcesAlone], because [plan] is given a pre-filtered set and cannot tell
 *   when it is not, so the contract has to be stated where it is tested rather than only where it is
 *   documented.
 */
class CalendarImportPlanTest {
    private fun incoming(
        externalId: String,
        title: String = "Standup",
        description: String = "",
        startAt: Long = 1_700_000_000_000,
        endAt: Long = 1_700_003_600_000,
        allDay: Boolean = false,
        deleted: Boolean = false,
    ) = SyncedCalendarChange(
        externalId = externalId,
        title = title,
        description = description,
        startAt = startAt,
        endAt = endAt,
        allDay = allDay,
        deleted = deleted,
    )

    private fun stored(
        localId: Long,
        externalId: String,
        title: String = "Standup",
        description: String = "",
        startAt: Long = 1_700_000_000_000,
        endAt: Long = 1_700_003_600_000,
        allDay: Boolean = false,
    ) = CalendarImportPlan.StoredCalendarEvent(
        localId = localId,
        externalId = externalId,
        title = title,
        description = description,
        startAt = startAt,
        endAt = endAt,
        allDay = allDay,
    )

    // ── The first round ──

    @Test
    fun aFirstFullRoundInsertsEverything() {
        val plan = CalendarImportPlan.plan(
            incoming = listOf(incoming("a"), incoming("b", title = "Review")),
            stored = emptyList(),
            fullSync = true,
        )
        assertEquals(listOf("a", "b"), plan.insertions.map { it.externalId })
        assertTrue(plan.updates.isEmpty())
        assertTrue(plan.deletions.isEmpty())
        assertEquals(0, plan.unchanged)
    }

    @Test
    fun anEmptyRoundAgainstAnEmptyStoreIsANoOp() {
        val plan = CalendarImportPlan.plan(emptyList(), emptyList(), fullSync = true)
        assertTrue(plan.isEmpty)
        assertEquals(0, plan.unchanged)
    }

    @Test
    fun anEventWithNoExternalIdIsIgnoredRatherThanInsertedBlank() {
        // A provider that returned an entry without an id would otherwise become a row that no later
        // round can match, so every subsequent round inserts it again.
        val plan = CalendarImportPlan.plan(listOf(incoming(""), incoming("  ")), emptyList(), fullSync = true)
        assertTrue(plan.isEmpty)
    }

    // ── Repeat rounds: the same event must not arrive twice ──

    @Test
    fun anUnchangedEventIsCountedNotRewritten() {
        // Rewriting an identical row bumps updatedAt and re-persists the event's document, which makes
        // a no-op round look like a change to everything watching the workspace.
        val plan = CalendarImportPlan.plan(
            incoming = listOf(incoming("a")),
            stored = listOf(stored(11, "a")),
            fullSync = true,
        )
        assertTrue(plan.isEmpty)
        assertEquals(1, plan.unchanged)
    }

    @Test
    fun anEventMatchedByExternalIdUpdatesInsteadOfDuplicating() {
        val plan = CalendarImportPlan.plan(
            incoming = listOf(incoming("a", title = "Standup (moved)", startAt = 1_700_007_200_000)),
            stored = listOf(stored(11, "a")),
            fullSync = false,
        )
        assertTrue(plan.insertions.isEmpty())
        assertEquals(1, plan.updates.size)
        assertEquals(11L, plan.updates.single().localId)
        assertEquals("Standup (moved)", plan.updates.single().title)
        assertEquals(1_700_007_200_000, plan.updates.single().startAt)
    }

    @Test
    fun everyStoredFieldIsComparedSoNoEditIsMissed() {
        // Each of these is a real edit a provider reports, and any one of them left out of the
        // comparison is an event that silently stops tracking its source.
        val base = stored(11, "a")
        val edits = listOf(
            incoming("a", title = "Renamed"),
            incoming("a", description = "Agenda added"),
            incoming("a", startAt = base.startAt + 60_000),
            incoming("a", endAt = base.endAt + 60_000),
            incoming("a", allDay = true),
        )
        edits.forEach { edit ->
            val plan = CalendarImportPlan.plan(listOf(edit), listOf(base), fullSync = false)
            assertEquals("missed an edit: $edit", 1, plan.updates.size)
            assertEquals(0, plan.unchanged)
        }
    }

    @Test
    fun anEmptyTitleBecomesThePlaceholderTheWriteSiteWouldStore() {
        // Both providers permit an untitled event, so clearing a title is an edit to apply. The
        // placeholder is decided here rather than at the write site for the reason the next test pins:
        // if the two disagreed, an untitled event would differ from itself on every round.
        val plan = CalendarImportPlan.plan(
            incoming = listOf(incoming("a", title = "")),
            stored = listOf(stored(11, "a", title = "Standup")),
            fullSync = false,
        )
        assertEquals(1, plan.updates.size)
        assertEquals(CalendarImportPlan.UntitledEvent, plan.updates.single().title)
    }

    @Test
    fun anUntitledEventIsUnchangedOnTheRoundAfterItWasImported() {
        // The failure the shared placeholder exists to stop, stated as the round that follows: the row
        // holds "Untitled event", the provider still sends "", and a planner that compared the raw
        // values would report an update on this round and on every round after it, forever.
        val plan = CalendarImportPlan.plan(
            incoming = listOf(incoming("a", title = "")),
            stored = listOf(stored(11, "a", title = CalendarImportPlan.UntitledEvent)),
            fullSync = false,
        )
        assertTrue(plan.isEmpty)
        assertEquals(1, plan.unchanged)
    }

    @Test
    fun aTitleIsComparedAfterTrimmingBecauseThatIsHowItIsStored() {
        // createCalendarEvent trims. A provider that padded a title would otherwise be a permanent
        // update, and the padding would never appear on screen to explain it.
        val plan = CalendarImportPlan.plan(
            incoming = listOf(incoming("a", title = "  Standup  ")),
            stored = listOf(stored(11, "a", title = "Standup")),
            fullSync = false,
        )
        assertTrue(plan.isEmpty)
        assertEquals(1, plan.unchanged)
    }

    @Test
    fun aDescriptionIsComparedAsTheRowStoresItNotAsItArrived() {
        // The write path stores the block codec's projection of the description, not the provider's
        // text. Comparing the text itself makes every event with any markdown in its body a permanent
        // update. `summarise` stands in for that projection so the pin needs no parser.
        val plan = CalendarImportPlan.plan(
            incoming = listOf(incoming("a", description = "**Agenda**")),
            stored = listOf(stored(11, "a", description = "Agenda")),
            fullSync = false,
            summarise = { it.replace("**", "") },
        )
        assertTrue(plan.isEmpty)
        assertEquals(1, plan.unchanged)
    }

    @Test
    fun anUpdatedDescriptionCarriesTheProvidersTextNotTheProjection() {
        // The projection is for comparing; the write path re-runs it. Handing back the summary would
        // store a description with its formatting already stripped, so the event's document would lose
        // its markdown on the first round that touched it.
        val plan = CalendarImportPlan.plan(
            incoming = listOf(incoming("a", description = "**Agenda**")),
            stored = listOf(stored(11, "a", description = "Something else")),
            fullSync = false,
            summarise = { it.replace("**", "") },
        )
        assertEquals("**Agenda**", plan.updates.single().description)
    }

    @Test
    fun theLastEntryWinsWhenOneRoundRepeatsAnExternalId() {
        // A provider can legitimately repeat an id across pages of the same round; taking the first
        // would apply a stale copy of an event whose later entry is the current one.
        val plan = CalendarImportPlan.plan(
            incoming = listOf(incoming("a", title = "Stale"), incoming("a", title = "Current")),
            stored = emptyList(),
            fullSync = true,
        )
        assertEquals(1, plan.insertions.size)
        assertEquals("Current", plan.insertions.single().title)
    }

    // ── Deletion: the asymmetry between the two round types ──

    @Test
    fun anIncrementalRoundNeverInfersDeletionFromAbsence() {
        // The load-bearing pin. On a delta round the provider sends only what changed, so absence
        // means unchanged. Reading it as deletion would empty the calendar on the first round after a
        // week in which nothing moved — and report success while doing it.
        val plan = CalendarImportPlan.plan(
            incoming = listOf(incoming("a")),
            stored = listOf(stored(11, "a"), stored(12, "b"), stored(13, "c")),
            fullSync = false,
        )
        assertTrue("absence must not delete on an incremental round", plan.deletions.isEmpty())
        assertEquals(1, plan.unchanged)
    }

    @Test
    fun aFullRoundDeletesWhatTheProviderNoLongerMentions() {
        // The mirror image: a full round is the complete window, so absence is the only way a
        // deletion made while the app held no cursor can be reported at all.
        val plan = CalendarImportPlan.plan(
            incoming = listOf(incoming("a")),
            stored = listOf(stored(11, "a"), stored(12, "b")),
            fullSync = true,
        )
        assertEquals(listOf(12L), plan.deletions)
        assertEquals(1, plan.unchanged)
    }

    @Test
    fun anExplicitTombstoneIsObeyedOnEitherRoundType() {
        // Google sends status 'cancelled' and Graph sends @removed; both are statements rather than
        // absences, so both are honoured whether or not the round was full.
        listOf(true, false).forEach { full ->
            val plan = CalendarImportPlan.plan(
                incoming = listOf(incoming("a", deleted = true)),
                stored = listOf(stored(11, "a")),
                fullSync = full,
            )
            assertEquals("fullSync=$full", listOf(11L), plan.deletions)
            assertTrue(plan.updates.isEmpty())
            assertTrue(plan.insertions.isEmpty())
        }
    }

    @Test
    fun aTombstoneForAnEventNeverStoredIsNotAnInsertion() {
        // Graph reports an event that moved out of the window as removed, and it can do so for one the
        // app never held. Inserting it would create a row from a deletion notice.
        val plan = CalendarImportPlan.plan(
            incoming = listOf(incoming("a", deleted = true)),
            stored = emptyList(),
            fullSync = false,
        )
        assertTrue(plan.isEmpty)
    }

    @Test
    fun aTombstonedEventIsNotAlsoCountedTwiceOnAFullRound() {
        // An explicit tombstone and an absence can name the same row on a full round. Deleting it
        // twice is harmless, but reporting two deletions gives the owner a figure they cannot
        // reconcile with what they see.
        val plan = CalendarImportPlan.plan(
            incoming = listOf(incoming("a", deleted = true)),
            stored = listOf(stored(11, "a")),
            fullSync = true,
        )
        assertEquals(listOf(11L), plan.deletions)
    }

    @Test
    fun aFullRoundThatReturnsNothingDeletesEverythingItImported() {
        // Deliberate rather than defensive: an owner who cleared their provider calendar should see
        // Norfold's copy cleared too. It is also the shape a misconfigured window would take, which is
        // why the round type is decided by the function that knows whether a cursor was used.
        val plan = CalendarImportPlan.plan(
            incoming = emptyList(),
            stored = listOf(stored(11, "a"), stored(12, "b")),
            fullSync = true,
        )
        assertEquals(listOf(11L, 12L), plan.deletions)
    }

    // ── What must never be touched ──

    @Test
    fun aFullRoundLeavesOtherSourcesAlone() {
        // The contract stated where it is tested: [stored] is already narrowed to one provider's
        // imports, so a locally typed event or an Outlook import is simply not in the list a Google
        // round can see. This pin fails if a caller ever widens the query.
        val googleRows = listOf(stored(11, "g1"), stored(12, "g2"))
        val plan = CalendarImportPlan.plan(listOf(incoming("g1")), googleRows, fullSync = true)
        assertEquals(listOf(12L), plan.deletions)
        assertTrue(plan.deletions.none { it == 21L || it == 22L })
    }

    @Test
    fun aRoundOnlyEverNamesRowsItWasGiven() {
        // The invariant behind the one above: every id in the plan came from [stored], so no plan can
        // reach a row the caller did not hand over.
        val rows = listOf(stored(11, "a"), stored(12, "b"), stored(13, "c"))
        val ids = rows.map { it.localId }.toSet()
        listOf(true, false).forEach { full ->
            val plan = CalendarImportPlan.plan(
                incoming = listOf(incoming("a", title = "Edited"), incoming("b", deleted = true), incoming("z")),
                stored = rows,
                fullSync = full,
            )
            assertTrue(plan.deletions.all { it in ids })
            assertTrue(plan.updates.all { it.localId in ids })
        }
    }

    // ── A mixed round, which is what a real one looks like ──

    @Test
    fun aMixedFullRoundIsPlannedInOnePass() {
        val plan = CalendarImportPlan.plan(
            incoming = listOf(
                incoming("a"),                                  // unchanged
                incoming("b", title = "Renamed"),               // updated
                incoming("c", deleted = true),                  // tombstoned
                incoming("e", title = "New"),                   // inserted
            ),
            stored = listOf(stored(11, "a"), stored(12, "b"), stored(13, "c"), stored(14, "d")),
            fullSync = true,
        )
        assertEquals(listOf("e"), plan.insertions.map { it.externalId })
        assertEquals(listOf(12L), plan.updates.map { it.localId })
        // 13 by tombstone, 14 by absence.
        assertEquals(setOf(13L, 14L), plan.deletions.toSet())
        assertEquals(1, plan.unchanged)
        assertFalse(plan.isEmpty)
    }

    @Test
    fun anAllDayEventKeepsTheBoundsTheSyncFunctionNormalised() {
        // The function backs an exclusive all-day end off by a millisecond so a one-day event stays
        // inside the day the owner sees it on. Re-deriving that here would put the two halves in
        // different vocabularies, and the plan would report an update on every round.
        val dayStart = 1_700_000_000_000
        val exclusiveEndMinusOne = dayStart + 86_400_000 - 1
        val plan = CalendarImportPlan.plan(
            incoming = listOf(incoming("a", startAt = dayStart, endAt = exclusiveEndMinusOne, allDay = true)),
            stored = listOf(stored(11, "a", startAt = dayStart, endAt = exclusiveEndMinusOne, allDay = true)),
            fullSync = true,
        )
        assertTrue(plan.isEmpty)
        assertEquals(1, plan.unchanged)
    }

    // ── What the owner is told afterwards ──
    // A sync that reports nothing is indistinguishable from a sync that failed silently, which is
    // exactly the failure class this feature is prone to. So every outcome has to be sayable.

    @Test
    fun anOutcomeNamesOnlyWhatHappened() {
        assertEquals("2 added", CalendarImportOutcome(2, 0, 0, 5).describe())
        assertEquals("1 updated", CalendarImportOutcome(0, 1, 0, 0).describe())
        assertEquals("3 removed", CalendarImportOutcome(0, 0, 3, 0).describe())
        assertEquals("2 added, 1 updated, 3 removed", CalendarImportOutcome(2, 1, 3, 4).describe())
    }

    @Test
    fun aRoundThatChangedNothingStillSaysSo() {
        // "Synced" with no detail reads as a failure the owner cannot investigate. The two silences
        // are distinguished: nothing needed doing, versus nothing came back at all.
        assertEquals("No changes", CalendarImportOutcome(0, 0, 0, 12).describe())
        assertEquals("Nothing to import", CalendarImportOutcome(0, 0, 0, 0).describe())
        assertFalse(CalendarImportOutcome(0, 0, 0, 12).changed)
        assertTrue(CalendarImportOutcome(0, 0, 1, 0).changed)
    }
}
