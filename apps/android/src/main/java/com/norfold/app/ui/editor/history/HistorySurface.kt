package com.norfold.app.ui.editor.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.norfold.app.editor.history.HistoryAvailability
import com.norfold.app.editor.history.HistoryCommandOutcome
import com.norfold.app.editor.history.HistoryDirection
import com.norfold.app.editor.history.HistoryDomain
import com.norfold.app.editor.history.HistoryEntry
import com.norfold.app.editor.history.HistoryFilter
import com.norfold.app.editor.history.HistoryMode
import com.norfold.app.editor.history.HistoryPosition
import com.norfold.app.editor.history.HistoryRoute
import com.norfold.app.editor.history.HistoryRuntime
import com.norfold.app.editor.history.HistoryScope
import com.norfold.app.editor.history.HistoryStepPlan
import com.norfold.app.editor.presentation.PresentationMode

/**
 * Compose invalidation adapter over [HistoryRuntime].
 *
 * The runtime is pure JVM and holds no Compose state; this class holds exactly one observable counter and
 * bumps it after anything that changes what the list should draw. Keeping the two apart is what lets the
 * History rules be unit-tested without a Compose host, and it is why a filter change here cannot accidentally
 * become a document change: this class has no way to reach the workspace except through the runtime.
 */
@Stable
class HistoryUiController internal constructor(val runtime: HistoryRuntime?) {
    var revision by mutableIntStateOf(0)
        private set

    /** The last thing the user needs told — a refusal reason, a blocked focus step, a completed walk. */
    var message by mutableStateOf<String?>(null)
        private set

    /**
     * The step awaiting confirmation, if any.
     *
     * Mirrors [HistoryRuntime.pendingDisclosure] rather than owning it, because the keyboard chord reaches
     * the runtime without passing through this class. Compose cannot observe a plain getter on the runtime,
     * so the value is copied into snapshot state here and re-read by [syncFromRuntime] after any route that
     * bypassed the controller.
     */
    var disclosure by mutableStateOf<HistoryStepPlan.Ready?>(null)
        private set

    fun open() { if (runtime?.open() == true) invalidate() }
    fun close() { runtime?.close(); disclosure = null; invalidate() }
    fun back() { if (runtime?.back() == true) invalidate() }
    fun query(value: String) { runtime?.setQuery(value); adopt(); invalidate() }
    fun scope(value: HistoryScope) { runtime?.setScope(value); adopt(); invalidate() }
    fun onlyMine(value: Boolean) { runtime?.setOnlyMine(value); adopt(); invalidate() }
    fun clearFilter() { runtime?.clearFilter(); adopt(); invalidate() }
    fun select(sequence: Int) { runtime?.select(sequence); invalidate() }
    fun openDetail() { runtime?.openDetail(); invalidate() }
    fun closeDetail() { runtime?.closeDetail(); invalidate() }
    fun scrolled(index: Int) { runtime?.setScrollIndex(index); invalidate() }
    fun clearMessage() { message = null; invalidate() }
    fun dismissDisclosure() { runtime?.dismissDisclosure(); disclosure = null; invalidate() }

    /**
     * Adopts the runtime's pending step after a route that did not come through this class.
     *
     * The keyboard chord goes host → runtime, so without this a Ctrl+Z that needs disclosing would refuse
     * the step and show nothing — the worst of both answers. Recomposition is requested only when the
     * pending step actually changed, so this stays cheap enough to call after every key press.
     */
    fun syncFromRuntime() {
        if (adopt()) invalidate()
    }

    private fun adopt(): Boolean {
        val pending = runtime?.pendingDisclosure()
        if (pending === disclosure) return false
        disclosure = pending
        return true
    }

    fun undo() = report(runtime?.undo())
    fun redo() = report(runtime?.redo())

    /** Undo back to and including [sequence]. Discloses every newer transaction it will reverse first. */
    fun undoTo(sequence: Int) = report(runtime?.undoTo(sequence))

    /** Runs the step the user just confirmed in the disclosure sheet. */
    fun confirmDisclosed() {
        val pending = disclosure ?: return
        disclosure = null
        val outcome = when (pending.direction) {
            HistoryDirection.UNDO -> runtime?.undoTo(pending.requested.sequence, confirmed = true)
            HistoryDirection.REDO -> runtime?.redo(confirmed = true)
        }
        report(outcome)
    }

    private fun report(outcome: HistoryCommandOutcome?) {
        if (outcome == null) return
        adopt()
        message = when {
            outcome.pendingDisclosure != null -> null
            outcome.failure != null -> outcome.failure
            outcome.route == HistoryRoute.BLOCKED_BY_FOCUS -> HistoryRuntime.FOCUS_EXHAUSTED_REASON
            outcome.route == HistoryRoute.UNAVAILABLE -> unavailableMessage(outcome)
            outcome.moved.size > 1 -> "${outcome.moved.size} changes were reversed."
            else -> null
        }
        invalidate()
    }

    private fun unavailableMessage(outcome: HistoryCommandOutcome): String? {
        val runtime = runtime ?: return null
        val availability = runtime.availability()
        return when (outcome.direction) {
            HistoryDirection.UNDO -> availability.undoReason
            HistoryDirection.REDO -> availability.redoReason
        }
    }

    private fun invalidate() { revision++ }
}

@Composable
fun rememberHistoryUiController(runtime: HistoryRuntime?): HistoryUiController =
    remember(runtime) { HistoryUiController(runtime) }

/**
 * Toolbar entry point.
 *
 * Takes the action rather than the controller so a host whose History runtime is absent omits the button
 * entirely — a control that is present and inert teaches the user that History is broken, not that it is off.
 */
@Composable
fun HistoryEntryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).semantics {
            contentDescription = "Open History"
            role = Role.Button
        },
    ) {
        Text("⟲", style = MaterialTheme.typography.titleLarge)
    }
}

/**
 * The History browser, plus the disclosure sheet that guards a multi-step undo.
 *
 * Draws nothing when the runtime is absent or the browser is closed, so a host can call this unconditionally.
 */
@Composable
fun HistoryHostLayer(
    controller: HistoryUiController,
    modifier: Modifier = Modifier,
) {
    val runtime = controller.runtime ?: return
    controller.revision
    val view = runtime.viewState()
    BackHandler(enabled = view.open || controller.disclosure != null) {
        if (controller.disclosure != null) controller.dismissDisclosure() else controller.back()
    }
    if (view.open) HistoryBrowserDialog(controller, runtime, modifier)
    controller.disclosure?.let { plan -> HistoryDisclosureDialog(controller, plan) }
}

@Composable
private fun HistoryBrowserDialog(
    controller: HistoryUiController,
    runtime: HistoryRuntime,
    modifier: Modifier,
) {
    val view = runtime.viewState()
    val mode = runtime.presentationMode() ?: PresentationMode.PHONE_SHEET
    val shell = when (mode) {
        PresentationMode.PHONE_POPOVER, PresentationMode.PHONE_SHEET -> Modifier.fillMaxSize()
        PresentationMode.TABLET_PANE -> Modifier.fillMaxHeight(.92f).widthIn(max = 680.dp)
        PresentationMode.EXPANDED_THREE_PANEL -> Modifier.fillMaxHeight().widthIn(max = 760.dp)
    }
    val availability = runtime.availability()
    val visible = runtime.visibleProjection()
    val rows = visible.entries.asReversed()

    // The runtime holds the scroll index so it survives a form-factor change; the list state is restored
    // from it rather than reset, which is the difference between "rotate" and "lose your place".
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = view.scrollIndex)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect(controller::scrolled)
    }

    Dialog(
        onDismissRequest = controller::close,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier.then(shell),
            shape = if (mode == PresentationMode.PHONE_SHEET) RectangleShape else MaterialTheme.shapes.extraLarge,
            tonalElevation = 8.dp,
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "History",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            availability.mode.summaryLine(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = controller::close, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Close")
                    }
                }

                OutlinedTextField(
                    value = view.filter.query,
                    onValueChange = controller::query,
                    label = { Text("Search history") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                HistoryFilterChips(controller, runtime)

                controller.message?.let { text ->
                    Surface(
                        Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(text, Modifier.weight(1f))
                            TextButton(onClick = controller::clearMessage) { Text("Dismiss") }
                        }
                    }
                }

                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (rows.isEmpty()) {
                        Text(
                            view.filter.emptyListMessage(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        )
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            // Keyed by transaction id: a stable identity that survives undo, redo and
                            // filtering, so rows are not recycled onto the wrong entry mid-scroll.
                            items(rows, key = { it.transactionId }) { entry ->
                                HistoryRowItem(
                                    entry = entry,
                                    selected = entry.sequence == view.selectedSequence,
                                    onSelect = { controller.select(entry.sequence) },
                                    onUndoTo = { controller.undoTo(entry.sequence) },
                                )
                            }
                        }
                    }
                }

                runtime.selectedEntry()?.takeIf { view.detailOpen }?.let { entry ->
                    HistoryDetailPanel(entry, onClose = controller::closeDetail)
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    availability.undoReason?.let {
                        Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(
                        onClick = controller::redo,
                        enabled = availability.canRedo,
                        modifier = Modifier.heightIn(min = 48.dp).semantics {
                            contentDescription = availability.redoAnnouncement()
                            stateDescription = availabilityStateDescription(availability.canRedo)
                        },
                    ) { Text("Redo") }
                    Button(
                        onClick = controller::undo,
                        enabled = availability.canUndo,
                        modifier = Modifier.heightIn(min = 48.dp).semantics {
                            contentDescription = availability.undoAnnouncement()
                            stateDescription = availabilityStateDescription(availability.canUndo)
                        },
                    ) { Text("Undo") }
                }
            }
        }
    }
}

@Composable
private fun HistoryFilterChips(controller: HistoryUiController, runtime: HistoryRuntime) {
    val view = runtime.viewState()
    // Only domains that actually occur are offered. A chip that always returns nothing teaches the user
    // that the filters are broken.
    val domains = remember(controller.revision) {
        runtime.projection().entries.flatMap { it.domains }.distinct().sortedBy { it.name }
    }
    LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            FilterChip(
                selected = view.filter.isDefault,
                onClick = controller::clearFilter,
                label = { Text("All changes") },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
        item {
            FilterChip(
                selected = view.filter.onlyMine,
                onClick = { controller.onlyMine(!view.filter.onlyMine) },
                label = { Text("Mine only") },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
        items(domains, key = { it.name }) { domain ->
            val selected = (view.filter.scope as? HistoryScope.OneDomain)?.domain == domain
            FilterChip(
                selected = selected,
                onClick = {
                    if (selected) controller.scope(HistoryScope.WholeArtifact)
                    else controller.scope(HistoryScope.OneDomain(domain))
                },
                label = { Text(domain.label()) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
private fun HistoryRowItem(
    entry: HistoryEntry,
    selected: Boolean,
    onSelect: () -> Unit,
    onUndoTo: () -> Unit,
) {
    Surface(
        Modifier.fillMaxWidth(),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                Modifier.weight(1f).semantics {
                    this.selected = selected
                    role = Role.Button
                    contentDescription = entry.label
                    // Position is state, not a label: TalkBack announces it on change rather than reading
                    // it as part of the row's name every time the list is traversed.
                    stateDescription = entry.rowStateDescription()
                },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(entry.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    entry.rowDetailLine(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onSelect, modifier = Modifier.heightIn(min = 48.dp)) { Text("Details") }
            if (entry.position == HistoryPosition.APPLIED) {
                TextButton(onClick = onUndoTo, modifier = Modifier.heightIn(min = 48.dp)) { Text("Undo to here") }
            }
        }
    }
}

@Composable
private fun HistoryDetailPanel(entry: HistoryEntry, onClose: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.label,
                    Modifier.weight(1f).semantics { heading() },
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") }
            }
            Text(
                "${entry.operationCount} operation${if (entry.operationCount == 1) "" else "s"} · " +
                    entry.domains.joinToString(", ") { it.label() }.ifEmpty { "Unclassified" },
                style = MaterialTheme.typography.bodySmall,
            )
            if (entry.isCrossTarget) {
                Text(
                    "Affects ${entry.targets.size} documents. Undoing reverses all of them together.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * The confirmation a multi-step or cross-target undo must pass through.
 *
 * Every intervening transaction is named, because the alternative — reversing them silently — is how a user
 * loses work they never asked to lose. The confirm button re-derives the plan, so a sheet left open while the
 * document changed underneath refuses rather than acting on a stale picture.
 */
@Composable
private fun HistoryDisclosureDialog(controller: HistoryUiController, plan: HistoryStepPlan.Ready) {
    Dialog(onDismissRequest = controller::dismissDisclosure) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 8.dp) {
            Column(
                Modifier.widthIn(max = 520.dp).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    plan.disclosureTitle(),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(plan.disclosureSummary(), style = MaterialTheme.typography.bodyMedium)
                plan.intervening.take(MAX_DISCLOSED_ROWS).forEach { entry ->
                    Text("• ${entry.label}", style = MaterialTheme.typography.bodySmall)
                }
                if (plan.intervening.size > MAX_DISCLOSED_ROWS) {
                    Text(
                        "• and ${plan.intervening.size - MAX_DISCLOSED_ROWS} more",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                plan.extraTargetLine()?.let { line ->
                    Text(line, style = MaterialTheme.typography.bodyMedium)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(
                        onClick = controller::dismissDisclosure,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Cancel") }
                    Button(
                        onClick = controller::confirmDisclosed,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Continue") }
                }
            }
        }
    }
}

/** Caps the disclosure list so a hundred-step walk does not produce an unreadable, unscrollable dialog. */
private const val MAX_DISCLOSED_ROWS = 8

/*
 * The announced strings live below as pure functions rather than inline in the composables above.
 *
 * Everything a screen reader speaks about History is derived here, so it can be asserted directly by a unit
 * test instead of inferred from a Compose tree that no non-device gate can render. Accessibility text that is
 * only reachable by rendering is accessibility text nobody checks.
 */

/** Says which of the two undo behaviours is in force, because they reverse different things. */
internal fun HistoryMode.summaryLine(): String = when (this) {
    HistoryMode.GLOBAL -> "Every change to this document, newest first"
    HistoryMode.CONTEXTUAL -> "Filtered view — Undo applies to what is shown"
}

/** Where this entry sits relative to the user. Announced as state, never as part of the row's name. */
internal fun HistoryEntry.positionLabel(): String = when {
    isNextUndo -> "Current position"
    position == HistoryPosition.UNDONE -> "Undone"
    else -> "Applied"
}

/**
 * The row's state description.
 *
 * A cross-target entry says so here: undoing it moves documents the user is not looking at, and finding that
 * out afterwards is exactly the surprise §13.4 atomicity is supposed to be safe about.
 */
internal fun HistoryEntry.rowStateDescription(): String = buildString {
    append(positionLabel())
    if (isCrossTarget) append(". Affects ${targets.size} documents")
}

/** The visible second line of a row: position first, then which domains it touched. */
internal fun HistoryEntry.rowDetailLine(): String = buildString {
    append(positionLabel())
    if (domains.isNotEmpty()) {
        append(" · ")
        append(domains.joinToString(", ") { it.label() })
    }
}

/**
 * What Undo is called right now — the actual next step, never a generic verb when a step is reachable.
 *
 * A button announced as "Undo" while it would reverse "Delete page" is the defect this exists to prevent:
 * the user hears one thing and the document does another.
 */
internal fun HistoryAvailability.undoAnnouncement(): String = undoDescription ?: "Undo"

internal fun HistoryAvailability.redoAnnouncement(): String = redoDescription ?: "Redo"

internal fun availabilityStateDescription(available: Boolean): String =
    if (available) "Available" else "Unavailable"

/** Distinguishes "you have not edited anything" from "your filter hides everything", which are not the same. */
internal fun HistoryFilter.emptyListMessage(): String =
    if (isDefault) "Nothing has been changed yet."
    else "No changes match this filter. The rest of the history is still there."

/** Names the cost of a multi-step walk before it happens, in the count the user can check against the list. */
internal fun HistoryStepPlan.Ready.disclosureSummary(): String =
    "Reaching \"${requested.label}\" also affects " +
        "${intervening.size} newer change${if (intervening.size == 1) "" else "s"}."

internal fun HistoryStepPlan.Ready.disclosureTitle(): String = when (direction) {
    HistoryDirection.UNDO -> "Undo more than one change?"
    HistoryDirection.REDO -> "Redo more than one change?"
}

/** Warns that the walk reaches outside the current view. Empty means it does not. */
internal fun HistoryStepPlan.Ready.extraTargetLine(): String? =
    if (extraTargets.isEmpty()) null
    else "This also changes ${extraTargets.size} document" +
        "${if (extraTargets.size == 1) "" else "s"} outside what you are looking at."

internal fun HistoryDomain.label(): String = when (this) {
    HistoryDomain.TEXT -> "Text"
    HistoryDomain.LIST -> "Lists"
    HistoryDomain.TABLE -> "Tables"
    HistoryDomain.CHART -> "Charts"
    HistoryDomain.IMAGE -> "Images"
    HistoryDomain.SHAPE -> "Shapes"
    HistoryDomain.MEDIA -> "Media"
    HistoryDomain.CODE -> "Code"
    HistoryDomain.MATH -> "Math"
    HistoryDomain.PAGE -> "Pages"
    HistoryDomain.OBJECT -> "Objects"
    HistoryDomain.STYLE -> "Styles"
    HistoryDomain.STRUCTURE -> "Structure"
    HistoryDomain.RELATIONSHIP -> "Comments and links"
    HistoryDomain.INTERCHANGE -> "Import and export"
    HistoryDomain.UNKNOWN -> "Other"
}
