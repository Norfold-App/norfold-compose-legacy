package com.norfold.app.domain

/**
 * One event exactly as the sync function normalised it, in the units Room stores.
 *
 * Lives in the domain rather than beside the parser so that the cloud layer, the repository and the
 * planner all name the same seven fields. A second copy of this shape with a hand-written mapping
 * between them is how a field stops being imported without anything failing: the parser reads
 * `allDay`, the mapping forgets it, and every all-day event silently becomes a midnight appointment.
 *
 * A tombstone is one of these too — [deleted] with no usable times. That is why it is a *change*
 * rather than an event.
 */
data class SyncedCalendarChange(
    val externalId: String,
    val title: String,
    val description: String,
    val startAt: Long,
    val endAt: Long,
    val allDay: Boolean,
    val deleted: Boolean,
)

/**
 * What one applied round actually did, for the line the owner reads afterwards.
 *
 * Reported rather than kept internal because a sync with no visible result is indistinguishable from
 * a sync that failed silently — which is the whole class of failure this feature is prone to. Even
 * "nothing changed" has to be sayable.
 */
data class CalendarImportOutcome(
    val inserted: Int,
    val updated: Int,
    val deleted: Int,
    val unchanged: Int,
) {
    val changed: Boolean get() = inserted > 0 || updated > 0 || deleted > 0

    /**
     * One line naming only what happened. A round that changed nothing says so, because "Synced" with
     * no detail reads as a failure the owner cannot investigate.
     */
    fun describe(): String {
        if (!changed) return if (unchanged > 0) "No changes" else "Nothing to import"
        return buildList {
            if (inserted > 0) add("$inserted added")
            if (updated > 0) add("$updated updated")
            if (deleted > 0) add("$deleted removed")
        }.joinToString(", ")
    }
}

/**
 * Decides what an imported calendar round does to the events already stored.
 *
 * Separated from the repository for the same reason [PushDelivery] is separated from the messaging
 * service: the writes are unavoidably device-bound — Room, a transaction, a document row — while the
 * decision is arithmetic over two lists, and it is the decision that goes silently wrong. Every
 * failure here is quiet. An event matched by the wrong key is a duplicate the owner sees twice. A
 * deletion inferred on an incremental round removes an event that never changed. Neither throws,
 * neither logs, and both look like the provider's fault.
 *
 * ## Why a full round deletes and an incremental round does not
 *
 * The two round types answer *different questions* with the same silence. On an incremental round
 * the provider sends only what changed, so an event that is absent is an event that is unchanged —
 * inferring deletion from absence would empty the calendar on the first round after a quiet week.
 * On a full round the response is the complete window, so absence is the only way a deletion can be
 * reported at all: a provider that dropped an event while the app held no cursor never sends a
 * tombstone for it.
 *
 * Both rounds honour an explicit tombstone. Google marks a cancelled occurrence `status:
 * 'cancelled'` and Microsoft Graph sends `@odata.removed`, and the sync function normalises both to
 * [SyncedCalendarChange.deleted]. That is a statement, not an absence, so it is obeyed either way.
 *
 * ## What is never touched
 *
 * Only events this provider imported. An event the owner typed into Norfold has no external id and
 * a different source, and a round from Google must not be able to delete one — nor an event
 * imported from Outlook. That is why [plan] takes the stored set already filtered to one source and
 * still matches on the external id rather than on the title or the time.
 */
object CalendarImportPlan {
    /**
     * What an event with no title is stored as.
     *
     * Declared here rather than at the write site because both halves have to agree: the planner
     * compares an incoming title against a stored one, so a write path that substituted a different
     * placeholder would make every untitled event differ from itself and report an update on every
     * round, forever.
     */
    const val UntitledEvent = "Untitled event"

    /**
     * The title a provider's value is stored under.
     *
     * Providers permit an untitled event and both send one as an empty string, so a placeholder is
     * unavoidable — and applying it here rather than at the write site is what keeps the comparison
     * and the store in the same vocabulary. Idempotent, which matters because the insert path trims
     * and substitutes again on the way in.
     */
    fun storedTitle(raw: String): String = raw.trim().ifBlank { UntitledEvent }

    /** An event already stored, reduced to what matching and change detection need. */
    data class StoredCalendarEvent(
        val localId: Long,
        val externalId: String,
        val title: String,
        val description: String,
        val startAt: Long,
        val endAt: Long,
        val allDay: Boolean,
    )

    /**
     * A new event to insert.
     *
     * [title] is already normalised; [description] is the provider's own text, because the write path
     * projects it through the block codec and doing that twice is not guaranteed to be a fixed point.
     */
    data class Insertion(
        val externalId: String,
        val title: String,
        val description: String,
        val startAt: Long,
        val endAt: Long,
        val allDay: Boolean,
    )

    /** An existing event whose stored values no longer match the provider's. Same field rules as [Insertion]. */
    data class Update(
        val localId: Long,
        val externalId: String,
        val title: String,
        val description: String,
        val startAt: Long,
        val endAt: Long,
        val allDay: Boolean,
    )

    /** What one round amounts to. [unchanged] is reported so a no-op round is visibly a no-op. */
    data class Plan(
        val insertions: List<Insertion>,
        val updates: List<Update>,
        val deletions: List<Long>,
        val unchanged: Int,
    ) {
        val isEmpty: Boolean get() = insertions.isEmpty() && updates.isEmpty() && deletions.isEmpty()
    }

    /**
     * The plan for one round.
     *
     * [stored] must already be narrowed to the events this provider imported into this workspace;
     * [plan] cannot tell otherwise, and passing a wider set is how a Google round would delete an
     * Outlook event. [fullSync] selects which question absence answers — see the class note.
     *
     * [summarise] is how a description becomes the string the row actually holds. The write path runs
     * the text through the block codec and stores the projection, not the original, so comparing the
     * original against the stored value would report an update on every round for any description
     * the codec touches at all — which is most of them. It is injected rather than called directly
     * because the projection belongs to the document layer, and because a test can then pin the
     * comparison without parsing markdown. The default is identity, which is only correct for a
     * caller that stores descriptions verbatim.
     */
    fun plan(
        incoming: List<SyncedCalendarChange>,
        stored: List<StoredCalendarEvent>,
        fullSync: Boolean,
        summarise: (String) -> String = { it },
    ): Plan {
        // Last write wins on a duplicate external id. A provider can legitimately repeat one across
        // pages of the same round, and taking the first would apply a stale copy of an event whose
        // later entry is the current one.
        val byExternalId = LinkedHashMap<String, SyncedCalendarChange>()
        incoming.forEach { change ->
            if (change.externalId.isNotBlank()) byExternalId[change.externalId] = change
        }
        val storedByExternalId = stored.associateBy { it.externalId }

        val insertions = mutableListOf<Insertion>()
        val updates = mutableListOf<Update>()
        val deletions = mutableListOf<Long>()
        var unchanged = 0

        byExternalId.values.forEach { change ->
            val existing = storedByExternalId[change.externalId]
            when {
                change.deleted -> existing?.let { deletions += it.localId }
                existing == null -> insertions += Insertion(
                    externalId = change.externalId,
                    title = storedTitle(change.title),
                    description = change.description,
                    startAt = change.startAt,
                    endAt = change.endAt,
                    allDay = change.allDay,
                )
                differs(existing, change, summarise) -> updates += Update(
                    localId = existing.localId,
                    externalId = change.externalId,
                    title = storedTitle(change.title),
                    description = change.description,
                    startAt = change.startAt,
                    endAt = change.endAt,
                    allDay = change.allDay,
                )
                // Rewriting an identical row would bump updatedAt and re-persist the event's
                // document body, which makes every round look like a change to anything watching.
                else -> unchanged += 1
            }
        }

        if (fullSync) {
            // Absence is a deletion only here. The response was the whole window, so an event the
            // provider did not mention is one it no longer has.
            val seen = byExternalId.keys
            stored.forEach { row ->
                if (row.externalId !in seen) deletions += row.localId
            }
        }
        // An explicit tombstone and an absence can name the same row; deleting it twice is harmless
        // but reports a count the owner cannot reconcile with what they see.
        return Plan(insertions, updates, deletions.distinct(), unchanged)
    }

    private fun differs(
        stored: StoredCalendarEvent,
        change: SyncedCalendarChange,
        summarise: (String) -> String,
    ): Boolean =
        stored.title != storedTitle(change.title) ||
            stored.description != summarise(change.description) ||
            stored.startAt != change.startAt ||
            stored.endAt != change.endAt ||
            stored.allDay != change.allDay
}
