package com.norfold.app.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.norfold.app.editor.inspector.InspectorAction
import com.norfold.app.editor.inspector.InspectorModel
import com.norfold.app.editor.inspector.InspectorSlot

/**
 * The one object inspector, drawn.
 *
 * In `ui.editor` rather than in `ui.editor.flow` or `ui.editor.document` because that is the claim: one
 * composable, two editors. It knows nothing about either — it draws an [InspectorModel], which each editor's
 * adapter produces from its own commands — so the two cannot drift into two sheets that agree on nothing.
 *
 * **What it does not own.** Not the surface: a caller places this inside whatever it is already presenting —
 * `ModalBottomSheet` on a phone, `ElevatedCard` in a tablet pane, both chosen by the Adaptive Presentation
 * runtime — and elevation, shape and edge come from `NorfoldChromeSurface` at that call site rather than from a
 * second opinion here. Not the actions: it renders what the model holds and reports taps back. Not enablement:
 * a disabled row is drawn disabled *with the editor's own reason under it*, because the alternative the tree
 * used to do was hide the row, and a user who cannot find "Move out of container" cannot be told why it is
 * unavailable.
 *
 * @param onAction the tap. Enabled rows only — a disabled row is not clickable, so a caller never has to
 *   re-check what the model already decided.
 */
@Composable
fun ObjectInspectorContent(
    model: InspectorModel,
    onAction: (InspectorAction) -> Unit,
    modifier: Modifier = Modifier,
    maxHeight: Dp = Dp.Unspecified,
) {
    Column(modifier.padding(bottom = 24.dp)) {
        Text(
            model.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(horizontal = INSPECTOR_GUTTER, vertical = 8.dp)
                .semantics { heading() },
        )
        LazyColumn(if (maxHeight == Dp.Unspecified) Modifier else Modifier.heightIn(max = maxHeight)) {
            items(model.head, key = { "head:${it.id}" }) { action ->
                InspectorActionRow(action, onAction)
            }
            // The head is separated from the slots rather than labelled. It has no name a user would recognise
            // — "the actions every object has" is a fact about the design, not a heading — and an unlabelled
            // group above the first named one reads as the sheet's own actions, which is what it is.
            if (model.head.isNotEmpty() && model.slots.isNotEmpty()) {
                item(key = "head-divider") {
                    HorizontalDivider(Modifier.padding(horizontal = INSPECTOR_GUTTER, vertical = 6.dp))
                }
            }
            model.slots.forEach { slot ->
                item(key = "slot:${slot.label}") { InspectorSlotHeading(slot) }
                items(slot.actions, key = { "${slot.label}:${it.id}" }) { action ->
                    InspectorActionRow(action, onAction)
                }
            }
        }
    }
}

/**
 * A slot's heading.
 *
 * `clearAndSetSemantics` with the slot's own name and count, so a screen reader announces "Arrange, 5 actions"
 * once instead of reading a label that a sighted user understands from position alone. The count is the part
 * that is genuinely useful non-visually: it says how far the group runs.
 */
@Composable
private fun InspectorSlotHeading(slot: InspectorSlot) {
    val count = slot.actions.size
    Text(
        slot.label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(horizontal = INSPECTOR_GUTTER, vertical = 6.dp)
            .clearAndSetSemantics {
                heading()
                contentDescription = if (count == 1) "${slot.label}, 1 action" else "${slot.label}, $count actions"
            },
    )
}

/**
 * One row.
 *
 * The destructive colour comes from [InspectorAction.role], not from the label. `FlowDockButton` decided it
 * with `label.equals("Delete", ignoreCase = true)`, which means a reworded label silently stops being red and
 * a differently-worded destructive action never starts being red. The role is set by the editor's adapter from
 * a command id, so it cannot come loose from the command it describes.
 *
 * Disabled wins over destructive: a delete the user cannot perform should read as unavailable rather than as
 * dangerous, since the danger is not currently on offer.
 */
@Composable
private fun InspectorActionRow(action: InspectorAction, onAction: (InspectorAction) -> Unit) {
    val color = when {
        !action.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        action.role.destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = action.enabled) { onAction(action) }
            .padding(horizontal = INSPECTOR_GUTTER, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                action.label,
                style = MaterialTheme.typography.bodyLarge,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        action.disabledReason?.let { reason ->
            Text(
                reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The horizontal inset shared by the title, the headings and the rows.
 *
 * 20 dp because that is what the Flow palette list this replaces already used, so adopting the inspector does
 * not shift the sheet's text sideways. One constant rather than a repeated literal, because a row and its own
 * disabled reason going out of alignment is the visible defect.
 */
private val INSPECTOR_GUTTER = 20.dp
