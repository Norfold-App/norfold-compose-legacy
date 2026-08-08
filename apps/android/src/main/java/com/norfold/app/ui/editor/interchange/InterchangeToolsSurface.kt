package com.norfold.app.ui.editor.interchange

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.norfold.app.editor.interchange.FidelityReport
import com.norfold.app.editor.interchange.FidelityState
import com.norfold.app.editor.interchange.ImportPreflight
import com.norfold.app.editor.interchange.ImportIntent
import com.norfold.app.editor.interchange.ImportMode
import com.norfold.app.editor.interchange.RawSourceDraft
import com.norfold.app.editor.interchange.RawSourceEditor
import com.norfold.app.editor.interchange.RawSourceParseResult

/** Shared state-driven content; hosts choose a full-screen phone sheet or expanded inspector pane. */
@Composable
fun ImportPreflightSurface(
    preflight: ImportPreflight,
    intent: ImportIntent,
    mode: ImportMode,
    onIntentChange: (ImportIntent) -> Unit,
    onModeChange: (ImportMode) -> Unit,
    applyDisabledReason: String?,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenGuide: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GuideHeader("Import preflight", onOpenGuide)
        Text("Format: ${preflight.detectedFormat.name}. Mode: ${preflight.proposedMode.name}. Intent: ${preflight.proposedIntent.name.replace('_', ' ')}.", Modifier.semantics { stateDescription = "${preflight.detectedFormat.name} import, ${preflight.proposedMode.name} mode" })
        Text("File size: ${preflight.source.byteSize} bytes. Pages: ${preflight.source.pageCount ?: "not applicable"}. Sheets: ${preflight.source.sheetCount ?: "not applicable"}.")
        Text("Import mode", style = MaterialTheme.typography.titleSmall)
        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ImportMode.entries, key = { it.name }) { option ->
                FilterChip(
                    selected = option == mode,
                    onClick = { onModeChange(option) },
                    label = { Text(option.name.lowercase().replaceFirstChar(Char::uppercase)) },
                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                        stateDescription = if (option == mode) "Selected import mode" else "Not selected"
                    },
                )
            }
        }
        Text("Import intent", style = MaterialTheme.typography.titleSmall)
        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ImportIntent.entries, key = { it.name }) { option ->
                FilterChip(
                    selected = option == intent,
                    onClick = { onIntentChange(option) },
                    label = { Text(option.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)) },
                    modifier = Modifier.heightIn(min = 48.dp).semantics {
                        stateDescription = if (option == intent) "Selected import intent" else "Not selected"
                    },
                )
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(preflight.errors + preflight.warnings, key = { "${it.code}:${it.regionId}" }) { issue ->
                Text("${issue.severity.name}: ${issue.message}", Modifier.semantics { contentDescription = "${issue.severity.name}: ${issue.message}" })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onCancel, Modifier.heightIn(min = 48.dp)) { Text("Cancel import") }
            Button(onApply, Modifier.heightIn(min = 48.dp), enabled = applyDisabledReason == null) { Text("Apply import") }
        }
        if (applyDisabledReason != null) Text("Apply unavailable: $applyDisabledReason")
    }
}

@Composable
fun FidelityInspectorSurface(
    report: FidelityReport,
    selected: Set<FidelityState>,
    onFilter: (Set<FidelityState>) -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenGuide: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GuideHeader("Fidelity Inspector", onOpenGuide)
        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(FidelityState.entries, key = { it.name }) { state ->
                FilterChip(
                    selected = state in selected,
                    onClick = { onFilter(if (state in selected) selected - state else selected + state) },
                    label = { Text("${state.name.lowercase().replaceFirstChar(Char::uppercase)} ${report.count(state)}") },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { stateDescription = if (state in selected) "Filter on" else "Filter off" },
                )
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(report.filtered(selected), key = { it.id }) { region ->
                Button(
                    onClick = { onNavigate(region.stableTargetId) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                        contentDescription = "${region.state.name}: ${region.summary}. ${region.details.ifBlank { "No additional detail" }}. Navigate to region"
                    },
                ) { Text("${region.state.name}: ${region.summary}\n${region.details}") }
            }
        }
    }
}

@Composable
fun RawSourceSurface(draft: RawSourceDraft, onSourceChange: (String) -> Unit, onApply: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier, onOpenGuide: (() -> Unit)? = null) {
    val diagnostics = (draft.parse as? RawSourceParseResult.Failure)?.diagnostics.orEmpty()
    val disabled = draft.applyDisabledReason ?: diagnostics.firstOrNull()?.let { "Line ${it.line}, column ${it.column}: ${it.message}" }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GuideHeader("Raw Source", onOpenGuide)
        Text("Advanced versioned Norfold product source. Changes remain a draft until Apply.")
        OutlinedTextField(
            value = draft.source,
            onValueChange = onSourceChange,
            label = { Text("Norfold Raw Source") },
            modifier = Modifier.fillMaxWidth().weight(1f).semantics { contentDescription = "Norfold Raw Source editor" },
        )
        Text(
            disabled ?: "Source is valid and ready to apply.",
            Modifier.semantics { liveRegion = LiveRegionMode.Polite; stateDescription = disabled ?: "Valid" },
        )
        val changeSummary = runCatching {
            val next = RawSourceEditor.materialize(draft)
            when {
                next == draft.original -> "Change summary: no semantic changes."
                else -> "Change summary: replace ${draft.original.blocks.size} top-level items with ${next.blocks.size}; one undoable edit will be created."
            }
        }.getOrNull()
        if (changeSummary != null) Text(changeSummary, Modifier.semantics { contentDescription = changeSummary })
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onCancel, Modifier.heightIn(min = 48.dp)) { Text("Cancel Raw Source") }
            Button(onApply, Modifier.heightIn(min = 48.dp), enabled = draft.canApply) { Text("Apply Raw Source") }
        }
        if (disabled != null) Text("Apply unavailable: $disabled")
    }
}

@Composable
private fun GuideHeader(title: String, onOpenGuide: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        onOpenGuide?.let { com.norfold.app.ui.editor.guide.GuideEntryButton(title, it) }
    }
}
