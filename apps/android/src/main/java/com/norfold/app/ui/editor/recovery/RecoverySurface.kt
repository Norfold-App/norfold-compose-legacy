package com.norfold.app.ui.editor.recovery

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
import androidx.compose.runtime.rememberUpdatedState
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
import com.norfold.app.editor.presentation.PresentationMode
import com.norfold.app.editor.recovery.RecoveryBrowserRuntime
import com.norfold.app.editor.recovery.RecoveryListFilter
import com.norfold.app.editor.recovery.RecoveryRestoreOutcome
import com.norfold.app.editor.recovery.RecoveryRestorePrompt
import com.norfold.app.editor.recovery.RecoveryRow
import com.norfold.app.editor.recovery.RecoveryStorageReport
import com.norfold.app.ui.NorfoldChrome

/**
 * Compose invalidation adapter over [RecoveryBrowserRuntime].
 *
 * Holds the transient text of the "new restore point" form and the outcome of the last restore. The outcome
 * is kept because it carries the safety snapshot: without somewhere to hold it, "undo this restore" would
 * have nothing to offer, and a user who restored the wrong point would be stuck.
 */
@Stable
class RecoveryUiController internal constructor(
    val runtime: RecoveryBrowserRuntime?,
    /**
     * Told after a restore has written the workspace, so the host republishes what it draws.
     *
     * A restore replaces document content outside the host's own edit path, so nothing else would bump the
     * host's revision: without this the browser would report success over a stale page.
     */
    private val onWorkspaceChanged: () -> Unit = {},
) {
    var revision by mutableIntStateOf(0)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var nameDraft by mutableStateOf("")
        private set
    var prompt by mutableStateOf<RecoveryRestorePrompt.Ready?>(null)
        private set
    var lastRestore by mutableStateOf<RecoveryRestoreOutcome?>(null)
        private set

    fun open() { if (runtime?.open() == true) invalidate() }
    fun close() { runtime?.close(); prompt = null; invalidate() }
    fun back() { if (runtime?.back() == true) { prompt = null; invalidate() } }
    fun filter(value: RecoveryListFilter) { runtime?.setListFilter(value); invalidate() }
    fun select(id: String) { runtime?.select(id); invalidate() }
    fun openDetail() { runtime?.openDetail(); invalidate() }
    fun closeDetail() { runtime?.closeDetail(); invalidate() }
    fun scrolled(index: Int) { runtime?.setScrollIndex(index); invalidate() }
    fun name(value: String) { nameDraft = value; invalidate() }
    fun clearMessage() { message = null; invalidate() }

    fun createNamedPoint() {
        val created = runtime?.createNamedPoint(nameDraft)
        message = if (created == null) "Give the restore point a name so you can find it later."
        else "Saved \"${created.displayName()}\"."
        if (created != null) nameDraft = ""
        invalidate()
    }

    fun delete(id: String) {
        message = if (runtime?.delete(id) == true) "Restore point deleted." else null
        invalidate()
    }

    /** Arms the confirmation. Never restores — that is [confirmRestore], one deliberate step later. */
    fun requestRestore(id: String) {
        when (val result = runtime?.requestRestore(id)) {
            is RecoveryRestorePrompt.Ready -> { prompt = result; message = null }
            is RecoveryRestorePrompt.Refused -> {
                prompt = null
                message = refusalMessage(result)
            }
            is RecoveryRestorePrompt.Unreadable -> { prompt = null; message = result.reason }
            null -> Unit
        }
        invalidate()
    }

    fun cancelRestore() { runtime?.cancelRestore(); prompt = null; invalidate() }

    fun confirmRestore() {
        prompt = null
        val outcome = runtime?.confirmRestore() ?: return
        lastRestore = outcome
        message = when (outcome) {
            is RecoveryRestoreOutcome.Restored ->
                "Restored ${outcome.targets.size} document${if (outcome.targets.size == 1) "" else "s"}."
            is RecoveryRestoreOutcome.Refused -> refusalMessage(RecoveryRestorePrompt.Refused(outcome.validation))
            is RecoveryRestoreOutcome.Failed ->
                if (outcome.workspaceIntact) outcome.reason
                // The rollback failed too. Reporting only the technical reason here would let someone
                // close the browser believing their documents are untouched when they may be part-way
                // through a restore, which is the one thing they need to know before editing further.
                else "${outcome.reason} Your documents may be part-way through the restore."
        }
        // A failed rollback means the workspace was written even though nothing succeeded; the host has to
        // re-read it either way, or it would keep drawing the page from before the restore.
        val wrote = outcome is RecoveryRestoreOutcome.Restored ||
            (outcome is RecoveryRestoreOutcome.Failed && !outcome.workspaceIntact)
        if (wrote) onWorkspaceChanged()
        invalidate()
    }

    /** Puts the document back to the moment before the last restore, using the pre-image it captured. */
    fun undoRestore() {
        val previous = lastRestore ?: return
        val outcome = runtime?.undoRestore(previous) ?: return
        lastRestore = null
        message = if (outcome.succeeded) "The restore was undone." else "The restore could not be undone."
        if (outcome.succeeded) onWorkspaceChanged()
        invalidate()
    }

    private fun refusalMessage(refused: RecoveryRestorePrompt.Refused): String {
        val validation = refused.validation
        return when {
            validation.isDamaged -> RecoveryBrowserRuntime.DAMAGED_REASON
            validation.unknownTargetIds.isNotEmpty() ->
                "This restore point covers a document that is no longer here."
            else -> RecoveryBrowserRuntime.DAMAGED_REASON
        }
    }

    private fun invalidate() { revision++ }
}

@Composable
fun rememberRecoveryUiController(
    runtime: RecoveryBrowserRuntime?,
    onWorkspaceChanged: () -> Unit = {},
): RecoveryUiController {
    // Re-read at call time rather than captured once, so a recomposition with a new lambda does not leave
    // the controller republishing through a host that is no longer on screen.
    val latest by rememberUpdatedState(onWorkspaceChanged)
    return remember(runtime) { RecoveryUiController(runtime) { latest() } }
}

/** Toolbar entry point. Omitted, not disabled, when recovery is off — see [HistoryEntryButton]'s reasoning. */
@Composable
fun RecoveryEntryButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).semantics {
            contentDescription = "Open restore points"
            role = Role.Button
        },
    ) {
        Text("⎌", style = MaterialTheme.typography.titleLarge)
    }
}

/** Draws nothing when recovery is disabled or the browser is closed, so hosts can call it unconditionally. */
@Composable
fun RecoveryHostLayer(controller: RecoveryUiController, modifier: Modifier = Modifier) {
    val runtime = controller.runtime ?: return
    controller.revision
    val state = runtime.state()
    BackHandler(enabled = state.open || controller.prompt != null) {
        if (controller.prompt != null) controller.cancelRestore() else controller.back()
    }
    if (state.open) RecoveryBrowserDialog(controller, runtime, modifier)
    controller.prompt?.let { RecoveryConfirmDialog(controller, it) }
}

@Composable
private fun RecoveryBrowserDialog(
    controller: RecoveryUiController,
    runtime: RecoveryBrowserRuntime,
    modifier: Modifier,
) {
    val state = runtime.state()
    val mode = runtime.presentationMode() ?: PresentationMode.PHONE_SHEET
    val shell = when (mode) {
        PresentationMode.PHONE_POPOVER, PresentationMode.PHONE_SHEET -> Modifier.fillMaxSize()
        PresentationMode.TABLET_PANE -> Modifier.fillMaxHeight(.92f).widthIn(max = 680.dp)
        PresentationMode.EXPANDED_THREE_PANEL -> Modifier.fillMaxHeight().widthIn(max = 760.dp)
    }
    val rows = runtime.rows()
    val report = runtime.storageReport()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.scrollIndex)
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
                            "Restore points",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(
                            "Saved on this device only",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = controller::close, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Close")
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = controller.nameDraft,
                        onValueChange = controller::name,
                        label = { Text("Name a restore point") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = controller::createNamedPoint,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Save now") }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RecoveryListFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = state.listFilter == filter,
                            onClick = { controller.filter(filter) },
                            label = { Text(filter.label()) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }

                controller.message?.let { text ->
                    Surface(
                        Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(text, Modifier.weight(1f))
                            if (controller.lastRestore is RecoveryRestoreOutcome.Restored) {
                                TextButton(onClick = controller::undoRestore) { Text("Undo restore") }
                            }
                            TextButton(onClick = controller::clearMessage) { Text("Dismiss") }
                        }
                    }
                }

                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (rows.isEmpty()) {
                        Text(
                            "No restore points yet. One is taken automatically before a risky change.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        )
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(rows, key = { it.id }) { row ->
                                RecoveryRowItem(
                                    row = row,
                                    selected = row.id == state.selectedId,
                                    onSelect = { controller.select(row.id) },
                                    onRestore = { controller.requestRestore(row.id) },
                                    onDelete = { controller.delete(row.id) },
                                )
                            }
                        }
                    }
                }

                Text(
                    report.summaryLine(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (report.isOverBudget) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RecoveryRowItem(
    row: RecoveryRow,
    selected: Boolean,
    onSelect: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    // A damaged or too-new point is listed and labelled rather than hidden: a restore point that silently
    // disappears is indistinguishable from one that was never taken, and that is the more alarming belief.
    val fill = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    Surface(
        Modifier.fillMaxWidth(),
        color = fill,
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
                    contentDescription = row.title
                    stateDescription = row.statusLabel()
                },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(row.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    row.detailLine(),
                    style = MaterialTheme.typography.bodySmall,
                    // This is the line that says a restore point is damaged or too new to use, and it was
                    // the least readable when the row was selected — 3.9043:1 light, 3.4548:1 dark, against
                    // 5.0502:1 unselected — because `onSurfaceVariant` is paired with the theme's surfaces
                    // and `secondaryContainer` is Material's baseline. Same defect as the history row.
                    color = NorfoldChrome.legibleInk(MaterialTheme.colorScheme.onSurfaceVariant, fill),
                )
            }
            TextButton(onClick = onSelect, modifier = Modifier.heightIn(min = 48.dp)) { Text("Details") }
            TextButton(
                onClick = onRestore,
                enabled = row.isRestorable,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Restore") }
            TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete") }
        }
    }
}

/**
 * The step between tapping Restore and losing the current document.
 *
 * Restoring is destructive and, from the user's side, irreversible unless they know about the safety point.
 * So the sheet says what will be overwritten before it happens, rather than reporting it afterwards.
 */
@Composable
private fun RecoveryConfirmDialog(controller: RecoveryUiController, prompt: RecoveryRestorePrompt.Ready) {
    Dialog(onDismissRequest = controller::cancelRestore) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 8.dp) {
            Column(
                Modifier.widthIn(max = 520.dp).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    prompt.confirmTitle(),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(prompt.consequenceLine(), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "A point is saved first, so you can undo this.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(
                        onClick = controller::cancelRestore,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Cancel") }
                    Button(
                        onClick = controller::confirmRestore,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Restore") }
                }
            }
        }
    }
}

/*
 * Announced strings, kept as pure functions for the same reason as History's: a screen reader's whole
 * experience of recovery is text, and text that can only be reached by rendering Compose is text no
 * non-device gate can check.
 */

/**
 * What a row is, in one word the user can act on.
 *
 * Damage and unsupported-version are announced *ahead* of the manual/automatic distinction, because a point
 * the user named themselves but that this build cannot read is first and foremost unusable — hearing "Named
 * by you" and then finding Restore disabled is the confusing order.
 */
internal fun RecoveryRow.statusLabel(): String = when {
    damaged -> "Damaged"
    unsupported -> "Made by a newer version"
    isManual -> "Named by you"
    else -> "Automatic"
}

/** The visible second line: what the point is, how big it is, and whether it covers only part of the document. */
internal fun RecoveryRow.detailLine(): String =
    "${statusLabel()} · ${formatBytes(sizeBytes)}" + if (isScoped) " · Part of the document" else ""

/** Says what a restore will overwrite, before it overwrites it. */
internal fun RecoveryRestorePrompt.Ready.consequenceLine(): String = if (isScoped) {
    "This replaces $scopedBlockCount part${if (scopedBlockCount == 1) "" else "s"} of the document. " +
        "Everything else stays as it is now."
} else {
    "This replaces ${targetIds.size} document${if (targetIds.size == 1) "" else "s"} with the saved version."
}

internal fun RecoveryRestorePrompt.Ready.confirmTitle(): String = "Restore \"$title\"?"

/** Honest accounting, including when it is over budget — a silent overrun is how storage stops being trusted. */
internal fun RecoveryStorageReport.summaryLine(): String =
    "$snapshotCount point${if (snapshotCount == 1) "" else "s"} · $manualCount named · " +
        "${formatBytes(totalBytes)} of ${formatBytes(budgetBytes)}"

internal fun RecoveryListFilter.label(): String = when (this) {
    RecoveryListFilter.ALL -> "All"
    RecoveryListFilter.MANUAL -> "Named"
    RecoveryListFilter.AUTOMATIC -> "Automatic"
}

/** Storage sizes in units a person reads, without pulling in a formatter that needs a Context. */
internal fun formatBytes(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_024 * 1_024 -> "${bytes / 1_024} KB"
    else -> "${bytes / (1_024 * 1_024)} MB"
}
