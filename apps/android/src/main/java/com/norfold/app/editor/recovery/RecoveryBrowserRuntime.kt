package com.norfold.app.editor.recovery

import com.norfold.app.editor.presentation.AdaptivePresentationRuntime
import com.norfold.app.editor.presentation.CompactPreference
import com.norfold.app.editor.presentation.FormFactor
import com.norfold.app.editor.presentation.PresentationMode
import com.norfold.app.editor.presentation.ToolPresentationState
import com.norfold.app.editor.surface.DismissTrigger
import com.norfold.app.editor.surface.DismissalPolicy
import com.norfold.app.editor.surface.SurfaceClass
import com.norfold.app.editor.surface.SurfaceCoordinator
import com.norfold.app.editor.surface.ToolSurface

/** Which restore points the browser is showing. A view, never a deletion. */
enum class RecoveryListFilter {
    /** Everything the store holds, newest first. */
    ALL,

    /** Only the named points the user created. */
    MANUAL,

    /** Only the points the app took defensively before a risky operation. */
    AUTOMATIC,
}

/** Browser state, carried verbatim across a form-factor change. Ids only — never a payload. */
data class RecoveryBrowserState(
    val open: Boolean = false,
    val listFilter: RecoveryListFilter = RecoveryListFilter.ALL,
    val selectedId: String? = null,
    val scrollIndex: Int = 0,
    val detailOpen: Boolean = false,
    /** True while a restore is awaiting confirmation for [selectedId]. */
    val confirmingRestore: Boolean = false,
)

/**
 * One row as the browser draws it.
 *
 * [damaged] and [unsupported] rows are listed rather than hidden. A restore point that silently vanishes
 * because this build cannot read it is indistinguishable, from the user's side, from one that was never
 * taken — and the second is a much more alarming thing to believe.
 */
data class RecoveryRow(
    val id: String,
    val title: String,
    val description: String,
    val reason: RecoveryReason,
    val createdAt: Long,
    val isManual: Boolean,
    val targetIds: List<String>,
    val isScoped: Boolean,
    val sizeBytes: Long,
    val damaged: Boolean,
    val unsupported: Boolean,
) {
    /** True when this row can be offered as a restore at all. */
    val isRestorable: Boolean get() = !damaged && !unsupported
}

/** Honest storage accounting for the browser footer (§19). */
data class RecoveryStorageReport(
    val snapshotCount: Int,
    val manualCount: Int,
    val automaticCount: Int,
    val totalBytes: Long,
    val budgetBytes: Long,
) {
    val isOverBudget: Boolean get() = totalBytes > budgetBytes
}

/** Content-free counters for Phase-10 evidence. */
data class RecoveryMetrics(
    val listBuilds: Long,
    val validations: Long,
    val restoresAttempted: Long,
    val restoresRefused: Long,
    val restoresSucceeded: Long,
    val namedPointsCreated: Long,
    val deletions: Long,
)

private class MutableRecoveryMetrics {
    var listBuilds = 0L
    var validations = 0L
    var restoresAttempted = 0L
    var restoresRefused = 0L
    var restoresSucceeded = 0L
    var namedPointsCreated = 0L
    var deletions = 0L
    fun snapshot() = RecoveryMetrics(
        listBuilds, validations, restoresAttempted, restoresRefused,
        restoresSucceeded, namedPointsCreated, deletions,
    )
}

/**
 * The recovery browser view-model over one artifact's restore points (spec §13.7; Phase-10 §20–§23).
 *
 * Listing never decodes a payload: rows come from the store's summaries, so opening the browser costs one
 * small index read no matter how much recovery data has accumulated. A payload is decoded exactly twice in
 * a restore's life — once to validate, once to apply — and both happen only after the user asks.
 *
 * The restore lifecycle here is deliberately three-step: **validate, capture, apply**. Validation refuses a
 * damaged or orphaned point before anything moves; the capture is the pre-image that makes a wrong restore
 * survivable; only then is the workspace written. A restore that fails after the capture is rolled back
 * from it, so there is no state in which a restore has half-happened.
 *
 * Pure JVM: no Android, no Compose, no network. The store's directory is supplied by the host.
 */
class RecoveryBrowserRuntime(
    private val manager: RecoverySnapshotManager,
    private val surfaces: SurfaceCoordinator,
    private val presentation: AdaptivePresentationRuntime,
    private val retention: RecoveryRetentionPolicy = RecoveryRetentionPolicy(),
) {
    /**
     * The manager's own store, not a second reference to one.
     *
     * Taking the store as a separate constructor argument would allow a host to list from one store while
     * restoring from another — the rows would look right and the restores would come from somewhere else.
     */
    private val store: RecoverySnapshotStore get() = manager.store

    private val metrics = MutableRecoveryMetrics()
    private var state = RecoveryBrowserState()

    fun state(): RecoveryBrowserState = state
    fun metrics(): RecoveryMetrics = metrics.snapshot()

    /**
     * Every restore point, newest first, without decoding a payload.
     *
     * A [FileRecoverySnapshotStore] answers from its index; any other store falls back to its snapshots,
     * which for the in-memory store costs nothing since the payloads are already resident.
     */
    fun rows(): List<RecoveryRow> {
        metrics.listBuilds++
        val all = summaries().map { summary ->
            RecoveryRow(
                id = summary.id,
                title = summary.displayName(),
                description = summary.description,
                reason = summary.reason,
                createdAt = summary.createdAt,
                isManual = summary.isManual,
                targetIds = summary.targetIds,
                isScoped = summary.scopeBlockIds.isNotEmpty(),
                sizeBytes = summary.sizeBytes,
                damaged = summary.damaged,
                unsupported = summary.unsupported,
            )
        }.sortedByDescending { it.createdAt }
        return when (state.listFilter) {
            RecoveryListFilter.ALL -> all
            RecoveryListFilter.MANUAL -> all.filter { it.isManual }
            RecoveryListFilter.AUTOMATIC -> all.filterNot { it.isManual }
        }
    }

    fun storageReport(): RecoveryStorageReport {
        val all = summaries()
        return RecoveryStorageReport(
            snapshotCount = all.size,
            manualCount = all.count { it.isManual },
            automaticCount = all.count { !it.isManual },
            totalBytes = all.sumOf { it.sizeBytes },
            budgetBytes = retention.maxTotalBytes,
        )
    }

    // --- Browser state -------------------------------------------------------------------------------

    fun open(): Boolean {
        if (state.open) return false
        state = state.copy(open = true)
        openSurface()
        presentation.present(presentationState(), CompactPreference.SHEET)
        return true
    }

    fun close() {
        if (!state.open) return
        state = state.copy(open = false, detailOpen = false, confirmingRestore = false)
        if (surfaces.active()?.id == BROWSER_SURFACE) surfaces.dispatch(DismissTrigger.CANCEL)
        presentation.close(BROWSER_SURFACE)
    }

    fun setListFilter(filter: RecoveryListFilter) {
        if (filter == state.listFilter) return
        val stillVisible = state.selectedId?.let { id -> rowsFor(filter).any { it.id == id } } ?: false
        state = state.copy(
            listFilter = filter,
            selectedId = if (stillVisible) state.selectedId else null,
            detailOpen = if (stillVisible) state.detailOpen else false,
            confirmingRestore = false,
            scrollIndex = 0,
        )
        updatePresentationState()
    }

    fun select(id: String): Boolean {
        if (rows().none { it.id == id }) return false
        state = state.copy(selectedId = id, confirmingRestore = false)
        updatePresentationState()
        return true
    }

    fun clearSelection() {
        state = state.copy(selectedId = null, detailOpen = false, confirmingRestore = false)
        updatePresentationState()
    }

    fun selectedRow(): RecoveryRow? = state.selectedId?.let { id -> rows().firstOrNull { it.id == id } }

    fun openDetail(): Boolean {
        if (state.selectedId == null) return false
        state = state.copy(detailOpen = true)
        updatePresentationState()
        return true
    }

    fun closeDetail() {
        if (!state.detailOpen) return
        state = state.copy(detailOpen = false, confirmingRestore = false)
        updatePresentationState()
    }

    fun setScrollIndex(index: Int) {
        val safe = index.coerceAtLeast(0)
        if (safe == state.scrollIndex) return
        state = state.copy(scrollIndex = safe)
        updatePresentationState()
    }

    /** Back unwinds one step at a time: confirmation, then detail, then the browser (§5.4). */
    fun back(): Boolean = when {
        state.confirmingRestore -> { cancelRestore(); true }
        state.detailOpen -> { closeDetail(); true }
        state.open -> { close(); true }
        else -> false
    }

    // --- Named restore points ------------------------------------------------------------------------

    /**
     * Creates a named restore point over [targets] (all known targets when omitted).
     *
     * Returns null for a blank name rather than inventing one: an unnamed manual point is indistinguishable
     * from an automatic one in the list, which defeats the reason the user created it.
     *
     * The new point is selected, and the list is widened if the active filter would have hidden it. Selecting
     * a row that is not on screen is the one state the rest of this class refuses to enter — [select] and
     * [setListFilter] both enforce it — and it matters most here: a restore confirmed from a detail surface
     * over an invisible row is a destructive action taken against something the user never saw listed.
     */
    fun createNamedPoint(
        name: String,
        description: String = "",
        scopeBlockIds: List<String> = emptyList(),
    ): RecoverySnapshot? {
        val created = manager.createNamedSnapshot(name, description, scopeBlockIds = scopeBlockIds)
            ?: return null
        metrics.namedPointsCreated++
        val hidden = state.listFilter == RecoveryListFilter.AUTOMATIC
        state = state.copy(
            listFilter = if (hidden) RecoveryListFilter.ALL else state.listFilter,
            selectedId = created.id,
        )
        updatePresentationState()
        return created
    }

    /** Deletes a restore point. The user's own points are theirs to remove. */
    fun delete(id: String): Boolean {
        val removed = manager.delete(id)
        if (!removed) return false
        metrics.deletions++
        if (state.selectedId == id) {
            state = state.copy(selectedId = null, detailOpen = false, confirmingRestore = false)
        }
        updatePresentationState()
        return true
    }

    // --- Restore lifecycle ---------------------------------------------------------------------------

    /** Checks a restore point without touching the workspace. Null when the record cannot be read at all. */
    fun validate(id: String): RecoveryValidation? {
        metrics.validations++
        val snapshot = readSnapshot(id) ?: return null
        return manager.validate(snapshot)
    }

    /**
     * Arms the confirmation step for [id], returning what a restore would do.
     *
     * Restoring is destructive and irreversible from the user's point of view, so it is never a single tap
     * from a list row. Validation runs here, before the confirmation is even offered, so an unrestorable
     * point never shows a button that would fail.
     */
    fun requestRestore(id: String): RecoveryRestorePrompt {
        val snapshot = readSnapshot(id)
            ?: return RecoveryRestorePrompt.Unreadable(id, damagedReason(id))
        val validation = manager.validate(snapshot)
        metrics.validations++
        if (!validation.isRestorable) return RecoveryRestorePrompt.Refused(validation)
        state = state.copy(selectedId = id, confirmingRestore = true)
        updatePresentationState()
        return RecoveryRestorePrompt.Ready(
            snapshotId = id,
            title = snapshot.displayName(),
            targetIds = snapshot.documents.keys.sorted(),
            isScoped = snapshot.isScoped,
            scopedBlockCount = snapshot.scopeBlockIds.size,
        )
    }

    fun cancelRestore() {
        if (!state.confirmingRestore) return
        state = state.copy(confirmingRestore = false)
        updatePresentationState()
    }

    /**
     * Performs the restore the user confirmed.
     *
     * Refuses unless [requestRestore] armed this exact id, so a stale tap on a re-rendered list cannot
     * restore something the user never confirmed.
     */
    fun confirmRestore(): RecoveryRestoreOutcome {
        val id = state.selectedId
        if (!state.confirmingRestore || id == null) {
            return RecoveryRestoreOutcome.Failed(NOT_CONFIRMED_REASON, null)
        }
        metrics.restoresAttempted++
        val snapshot = readSnapshot(id)
            ?: return RecoveryRestoreOutcome.Failed(damagedReason(id), null).also {
                state = state.copy(confirmingRestore = false)
            }
        val outcome = manager.restoreSafely(snapshot)
        when (outcome) {
            is RecoveryRestoreOutcome.Restored -> metrics.restoresSucceeded++
            is RecoveryRestoreOutcome.Refused -> metrics.restoresRefused++
            is RecoveryRestoreOutcome.Failed -> Unit
        }
        state = state.copy(confirmingRestore = false, detailOpen = false)
        updatePresentationState()
        return outcome
    }

    /**
     * Puts the workspace back to where it was immediately before a restore.
     *
     * The safety snapshot is an ordinary restore point, so undoing a restore is the same proven path as any
     * other restore — not a special case with its own failure modes.
     */
    fun undoRestore(outcome: RecoveryRestoreOutcome): RecoveryRestoreOutcome {
        val safety = when (outcome) {
            is RecoveryRestoreOutcome.Restored -> outcome.safetySnapshot
            is RecoveryRestoreOutcome.Failed -> outcome.safetySnapshot
            is RecoveryRestoreOutcome.Refused -> null
        } ?: return RecoveryRestoreOutcome.Failed(NO_SAFETY_POINT_REASON, null)
        metrics.restoresAttempted++
        return manager.restoreSafely(safety).also {
            if (it is RecoveryRestoreOutcome.Restored) metrics.restoresSucceeded++
        }
    }

    // --- Presentation --------------------------------------------------------------------------------

    fun onFormFactorChanged(next: FormFactor): PresentationMode? {
        val represented = presentation.onFormFactorChanged(next) { CompactPreference.SHEET }
        return represented[BROWSER_SURFACE]?.mode
    }

    fun presentationMode(): PresentationMode? =
        presentation.stateOf(BROWSER_SURFACE)?.let { presentation.modeFor(BROWSER_SURFACE, CompactPreference.SHEET) }

    // --- internals -----------------------------------------------------------------------------------

    private fun rowsFor(filter: RecoveryListFilter): List<RecoveryRow> {
        val saved = state
        state = state.copy(listFilter = filter)
        val computed = rows()
        state = saved
        return computed
    }

    /**
     * Summaries from the durable store when there is one, otherwise derived from resident snapshots.
     *
     * The file store additionally reports records it could not read as damaged rows; the in-memory store
     * has no such failure mode, so its rows are always healthy. The verdict comes from
     * [FileRecoverySnapshotStore.healthOf], which is memoized — listing must stay an index read, so a row
     * is never the cost of decoding the record it describes.
     */
    private fun summaries(): List<RecoverySnapshotSummary> = when (val backing = store) {
        is FileRecoverySnapshotStore -> backing.summaries().map { summary ->
            when (backing.healthOf(summary.id)) {
                RecoveryRecordHealth.HEALTHY -> summary
                RecoveryRecordHealth.UNSUPPORTED -> summary.copy(unsupported = true)
                RecoveryRecordHealth.DAMAGED -> summary.copy(damaged = true)
            }
        }
        else -> backing.all().map { snapshot ->
            RecoverySnapshotSummary(
                id = snapshot.id,
                reason = snapshot.reason,
                createdAt = snapshot.createdAt,
                label = snapshot.label,
                name = snapshot.name,
                description = snapshot.description,
                targetIds = snapshot.documents.keys.sorted(),
                scopeBlockIds = snapshot.scopeBlockIds,
                sizeBytes = snapshot.payloadBytes,
            )
        }
    }

    private fun readSnapshot(id: String): RecoverySnapshot? = store.get(id)

    private fun damagedReason(id: String): String = when (val backing = store) {
        is FileRecoverySnapshotStore -> when (backing.healthOf(id)) {
            RecoveryRecordHealth.UNSUPPORTED -> UNSUPPORTED_REASON
            else -> DAMAGED_REASON
        }
        else -> DAMAGED_REASON
    }

    private fun presentationState() = ToolPresentationState(
        toolId = BROWSER_SURFACE,
        selectedTab = state.listFilter.name,
        scroll = state.scrollIndex,
        values = mapOf(
            "selected" to state.selectedId.orEmpty(),
            "detail" to state.detailOpen.toString(),
            "confirming" to state.confirmingRestore.toString(),
        ),
    )

    private fun updatePresentationState() {
        if (!state.open) return
        val next = presentationState()
        if (presentation.stateOf(BROWSER_SURFACE) == null) presentation.present(next, CompactPreference.SHEET)
        else presentation.update(next)
    }

    private fun openSurface() {
        if (surfaces.active()?.id == BROWSER_SURFACE) return
        val surface = ToolSurface(
            id = BROWSER_SURFACE,
            surfaceClass = SurfaceClass.PICKER_BROWSER,
            dismissal = DismissalPolicy.OUTSIDE_OR_BACK,
            compatibleDomains = setOf("recovery"),
        )
        if (surfaces.depth == 0) surfaces.open(surface) else surfaces.openChild(surface)
    }

    companion object {
        const val BROWSER_SURFACE = "recovery.surface.browser"

        const val DAMAGED_REASON = "This restore point is damaged and cannot be used."
        const val UNSUPPORTED_REASON = "This restore point was made by a newer version of Norfold."
        const val NOT_CONFIRMED_REASON = "Confirm the restore before it can run."
        const val NO_SAFETY_POINT_REASON = "There is no point to go back to."
    }
}

/** What [RecoveryBrowserRuntime.requestRestore] found. Never carries a payload or document content. */
sealed interface RecoveryRestorePrompt {
    /** Validated and armed; [targetIds] is what the restore will overwrite. */
    data class Ready(
        val snapshotId: String,
        val title: String,
        val targetIds: List<String>,
        val isScoped: Boolean,
        val scopedBlockCount: Int,
    ) : RecoveryRestorePrompt

    /** The record read, but restoring it would fail. */
    data class Refused(val validation: RecoveryValidation) : RecoveryRestorePrompt

    /** The record could not be read at all — damaged, missing, or written by a newer build. */
    data class Unreadable(val snapshotId: String, val reason: String) : RecoveryRestorePrompt
}

/** No construction, index read, or surface registration occurs while HISTORY_UI is disabled. */
object RecoveryBrowserIntegration {
    fun create(enabled: Boolean, factory: () -> RecoveryBrowserRuntime): RecoveryBrowserRuntime? =
        if (enabled) factory() else null
}
