package com.norfold.app.ui.editor.relationship

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.norfold.app.editor.relationship.CommentStatus
import com.norfold.app.editor.relationship.CommentMention
import com.norfold.app.editor.relationship.RelationshipEndpoint
import com.norfold.app.editor.relationship.RelationshipId
import com.norfold.app.editor.relationship.RelationshipKind
import com.norfold.app.editor.relationship.RelationshipKinds
import com.norfold.app.editor.relationship.RelationshipReviewUiState
import com.norfold.app.editor.relationship.ReviewSnapshotId
import com.norfold.app.editor.relationship.ThreadId

/** One adaptive, virtualized presentation for manager, comments, and review state. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelationshipReviewSurface(
    title: String,
    state: RelationshipReviewUiState,
    onDismiss: () -> Unit,
    mentionCandidates: List<CommentMention>,
    onAddComment: (String, List<CommentMention>) -> Unit,
    onRemoveRelationship: (RelationshipId) -> Unit,
    onChangeRelationshipType: (RelationshipId, RelationshipKind) -> Unit,
    onRenameRelationship: (RelationshipId, String?) -> Unit,
    onReverseRelationship: (RelationshipId) -> Unit,
    onBeginRebind: (RelationshipId, String) -> Unit,
    onNavigateEndpoint: (RelationshipEndpoint) -> Unit,
    onNavigateThread: (ThreadId) -> Unit,
    onReply: (ThreadId, String, List<CommentMention>) -> Unit,
    onResolve: (ThreadId) -> Unit,
    onReopen: (ThreadId) -> Unit,
    onCreateSnapshot: () -> Unit,
    onRestoreSnapshot: (ReviewSnapshotId) -> Unit,
    onExitConnect: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var selectedMentions by remember { mutableStateOf(emptyList<CommentMention>()) }
    var pendingRestore by remember { mutableStateOf<ReviewSnapshotId?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(
                "${state.unresolvedCount} unresolved comments · ${state.relationships.size} relationships",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics {
                    stateDescription = "${state.unresolvedCount} unresolved comments and ${state.relationships.size} relationships"
                },
            )
            state.connectMode?.let {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Connect mode", style = MaterialTheme.typography.titleMedium)
                            Text("Choose an eligible target. Back exits without changing the document.")
                        }
                        TextButton(onClick = onExitConnect) { Text("Exit") }
                    }
                }
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("New comment") },
                supportingText = { Text("Anchored to the current selection") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            if (mentionCandidates.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Mention a workspace member", style = MaterialTheme.typography.labelMedium)
                    mentionCandidates.distinctBy { it.stableUserId }.take(8).forEach { mention ->
                        val selected = selectedMentions.any { it.stableUserId == mention.stableUserId }
                        TextButton(onClick = {
                            selectedMentions = if (selected) {
                                selectedMentions.filterNot { it.stableUserId == mention.stableUserId }
                            } else {
                                selectedMentions + mention
                            }
                            if (!selected && !draft.contains("@${mention.displayText}")) {
                                draft = draft.trimEnd() + if (draft.isBlank()) "@${mention.displayText} " else " @${mention.displayText} "
                            }
                        }) { Text(if (selected) "Remove @${mention.displayText}" else "Add @${mention.displayText}") }
                    }
                }
            }
            Button(
                onClick = { onAddComment(draft, selectedMentions); draft = ""; selectedMentions = emptyList() },
                enabled = draft.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add comment") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Review snapshots", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onCreateSnapshot) { Text("Create snapshot") }
            }
            HorizontalDivider()
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.relationships.isNotEmpty()) {
                    item { Text("Relationships", style = MaterialTheme.typography.titleMedium) }
                    state.relationships.groupBy { it.kind.id }.forEach { (_, grouped) ->
                        item(key = "relationship-group:${grouped.first().kind.id}") {
                            Text(grouped.first().kind.displayName, style = MaterialTheme.typography.labelLarge)
                        }
                        items(grouped, key = { "relationship:${it.id.value}" }) { relationship ->
                            var label by remember(relationship.id, relationship.label) { mutableStateOf(relationship.label.orEmpty()) }
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.large,
                                modifier = Modifier.fillMaxWidth().semantics {
                                    stateDescription = relationship.textualDescription()
                                    customActions = listOf(
                                        CustomAccessibilityAction("Navigate to relationship source") { onNavigateEndpoint(relationship.source); true },
                                        CustomAccessibilityAction("Navigate to relationship target") { onNavigateEndpoint(relationship.target); true },
                                        CustomAccessibilityAction("Remove relationship") { onRemoveRelationship(relationship.id); true },
                                    )
                                },
                            ) {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(relationship.label ?: relationship.kind.displayName, style = MaterialTheme.typography.titleSmall)
                                    Text(relationship.textualDescription(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        TextButton(onClick = { onNavigateEndpoint(relationship.source) }) { Text("Source") }
                                        TextButton(onClick = { onNavigateEndpoint(relationship.target) }) { Text("Target") }
                                        TextButton(onClick = {
                                            val choices = RelationshipKinds.baseline
                                            val next = choices[(choices.indexOfFirst { it.id == relationship.kind.id } + 1).coerceAtLeast(0) % choices.size]
                                            onChangeRelationshipType(relationship.id, next)
                                        }) { Text("Type") }
                                        if (relationship.kind.direction.name == "DIRECTED") {
                                            TextButton(onClick = { onReverseRelationship(relationship.id) }) { Text("Reverse") }
                                        }
                                    }
                                    OutlinedTextField(
                                        value = label,
                                        onValueChange = { label = it },
                                        label = { Text("Custom label") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        TextButton(onClick = { onRenameRelationship(relationship.id, label) }) { Text("Save label") }
                                        relationship.source.takeIf { it.broken }?.let { broken ->
                                            TextButton(onClick = { onBeginRebind(relationship.id, broken.stableId) }) { Text("Rebind source") }
                                        }
                                        relationship.target.takeIf { it.broken }?.let { broken ->
                                            TextButton(onClick = { onBeginRebind(relationship.id, broken.stableId) }) { Text("Rebind target") }
                                        }
                                        TextButton(onClick = { onRemoveRelationship(relationship.id) }) { Text("Remove") }
                                    }
                                }
                            }
                        }
                    }
                }
                if (state.threads.isNotEmpty()) {
                    item { Text("Discussion", style = MaterialTheme.typography.titleMedium) }
                    items(state.threads, key = { "thread:${it.id.value}" }) { thread ->
                        var reply by remember(thread.id) { mutableStateOf("") }
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth().semantics {
                                stateDescription = if (thread.status == CommentStatus.OPEN) "Unresolved comment thread" else "Resolved comment thread"
                                customActions = listOf(
                                    CustomAccessibilityAction("Navigate to comment anchor") { onNavigateThread(thread.id); true },
                                    CustomAccessibilityAction(if (thread.status == CommentStatus.OPEN) "Resolve thread" else "Reopen thread") {
                                        if (thread.status == CommentStatus.OPEN) onResolve(thread.id) else onReopen(thread.id); true
                                    },
                                )
                            },
                        ) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(if (thread.anchor.state.name == "DETACHED") "Detached anchor" else "Anchored discussion", style = MaterialTheme.typography.labelLarge)
                                thread.comments.forEach { comment ->
                                    Text(comment.author.displayName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    Text(comment.body)
                                }
                                OutlinedTextField(
                                    value = reply,
                                    onValueChange = { reply = it },
                                    label = { Text("Reply") },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 1,
                                )
                                Row {
                                    TextButton(onClick = { onNavigateThread(thread.id) }) { Text("Go to anchor") }
                                    TextButton(
                                        enabled = reply.isNotBlank(),
                                        onClick = { onReply(thread.id, reply, selectedMentions); reply = "" },
                                    ) { Text("Reply") }
                                    TextButton(onClick = { if (thread.status == CommentStatus.OPEN) onResolve(thread.id) else onReopen(thread.id) }) {
                                        Text(if (thread.status == CommentStatus.OPEN) "Resolve" else "Reopen")
                                    }
                                }
                            }
                        }
                    }
                }
                if (state.snapshots.isNotEmpty()) {
                    item { Text("Snapshots", style = MaterialTheme.typography.titleMedium) }
                    items(state.snapshots, key = { "snapshot:${it.id.value}" }) { snapshot ->
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(snapshot.label ?: "Review snapshot", style = MaterialTheme.typography.titleSmall)
                                    Text("${snapshot.createdAt} · ${snapshot.contentHash.take(12)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { pendingRestore = snapshot.id }) { Text("Restore") }
                            }
                        }
                    }
                }
                if (state.relationships.isEmpty() && state.threads.isEmpty() && state.snapshots.isEmpty()) {
                    item { Text("No relationships, comments, or review snapshots yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
    pendingRestore?.let { snapshotId ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Restore review snapshot?") },
            text = { Text("Current content will be protected by a recovery snapshot first. Newer discussions will be preserved.") },
            confirmButton = {
                TextButton(onClick = { pendingRestore = null; onRestoreSnapshot(snapshotId) }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { pendingRestore = null }) { Text("Cancel") } },
        )
    }
}
