package com.norfold.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import com.norfold.app.ui.components.NorfoldDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.norfold.app.domain.style.ChromeGeometry.Corner
import com.norfold.app.ui.rememberChromeFocus

/**
 * Shared iOS-style "grouped rows" design system used across every Settings screen,
 * matching the reference mockup: small-caps section headers, one rounded surface card
 * per group with hairline dividers, leading rounded icon tiles, and a trailing slot
 * (chevron / value / switch). Theme-adaptive for light and dark.
 */

private val GroupShape = RoundedCornerShape(20.dp)

@Composable
private fun isDark(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/** Small-caps gray label that sits above a [SettingsGroup]. */
@Composable
fun SettingsSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(start = 6.dp, top = 6.dp, bottom = 6.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
    )
}

/** A rounded card that clips a vertical stack of rows and paints hairline dividers between them. */
@Composable
fun SettingsGroup(
    header: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (header != null) SettingsSectionLabel(header)
        val dark = isDark()
        val cardColor = if (dark) MaterialTheme.colorScheme.surface else Color.White
        Column(
            Modifier
                .fillMaxWidth()
                .clip(GroupShape)
                .background(cardColor)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = if (dark) 0.5f else 0.7f),
                    shape = GroupShape,
                ),
            content = content,
        )
    }
}

/** Hairline divider that lines up under the row text (past the icon tile). */
@Composable
fun RowDivider(inset: Boolean = true) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = if (inset) 60.dp else 0.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
    )
}

/**
 * A single settings row: leading rounded icon tile, title + optional subtitle,
 * and a trailing slot ([trailing]). Whole row is clickable when [onClick] is set.
 *
 * ## Focus
 *
 * This one composable is how 83 call sites reach the user, which is why the focus ring belongs here and not
 * at any of them. The ring is drawn *inward* ([ChromeFocus.ring]) for a structural reason specific to this
 * row: [SettingsGroup] clips its children to a 20 dp shape, so a ring drawn outside the row's bounds would
 * be clipped away entirely on the first and last row of every group and drawn on top of the divider
 * everywhere else. An inward ring also takes no layout space, so adding it moves nothing — a group of rows
 * measures identically focused and unfocused.
 *
 * A row with no [onClick] gets no ring, because it is not a target: `clickable` is what makes an element
 * focusable, so a static row never receives focus and a ring on it would be a permanent outline.
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val focus = rememberChromeFocus()
    val base = modifier
        .fillMaxWidth()
        .then(
            if (onClick != null) {
                Modifier
                    .clickable(interactionSource = focus.source, indication = ripple(), onClick = onClick)
                    .then(focus.ring(Corner.Card))
            } else {
                Modifier
            },
        )
    Row(
        base.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconTint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = iconTint, modifier = Modifier.size(19.dp)) }
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = titleColor)
            if (subtitle != null) {
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
        trailing?.invoke()
    }
}

/**
 * A full-width row inside a list, sheet or dialog whose whole width is one target: clipped, clickable, and
 * focus-ringed.
 *
 * ## Why this is a component rather than a modifier chain
 *
 * The chain `fillMaxWidth().clip(RoundedCornerShape(r)).clickable { … }.padding(p)` appears at seven sites
 * across three files — workspace switching, new-workspace, the sidebar account row, font picking, sort mode,
 * property multi-select and property-type picking — with `r` drifting between 10 and 12 dp for no reason a user
 * could name. Seven copies is also seven places a focus ring has to be remembered, and the evidence that it
 * would not be is that none of them had one.
 *
 * Taking the row means the radius is stated once, the ring is derived from that radius rather than guessed, and
 * the next one of these gets both without its author having to know the substrate exists.
 *
 * **What the seven have in common is not "pick one of these".** Three commit a selection, one navigates, one
 * toggles a checkbox, one creates. What they share is exactly the geometry the ring depends on: full width, a
 * clip, one `onClick` covering the whole band, interior padding. That is deliberately all this composable
 * knows — a `selected` parameter would be wrong for the three that select nothing, and a `trailing` slot would
 * be wrong for the rows that put their control first. Each caller states its own meaning in [content].
 *
 * Inward ring, for the reason every row in this file uses one: these rows sit inside dialogs, sheets and cards
 * that clip their content and stack the rows flush, so a ring outside the bounds is either deleted by the clip
 * or drawn on the neighbour.
 *
 * **Not for a short row.** The default tier is 14 dp, so a row under 28 dp tall would have its radius clamped
 * to half its height by [RoundedCornerShape] — it would render as a pill, and the ring, whose radius is
 * derived from 14 rather than from the clamped value, would stop being concentric with it. Every one of the
 * seven is at least 42 dp tall because each carries a 26 dp badge, a 34 dp avatar, two lines of text, or a
 * Material control at its 48 dp minimum. The four *disclosure* headers found in the same sweep
 * (`TasksBoardScreen` 3295 and 3425, `SidebarScreen` 226 and 1098) are ~24 dp of icon and one text line, and
 * two of them draw no clip at all, so they are a separate population and not callers of this.
 *
 * @param corner the row's own radius. [Corner.Media] rather than either of the two radii found, because 10 and
 *   12 dp were the same intention written twice; collapsing near-duplicates onto one tier is what the
 *   substrate is for. It does move these corners by 2-4 dp, which is a visible change on a clipped row and is
 *   the point rather than a side effect.
 * @param contentPadding interior padding, which varies legitimately: a radio row needs less than an
 *   icon-and-two-lines row.
 */
@Composable
fun ChoiceRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    corner: Corner = Corner.Media,
    contentPadding: PaddingValues = PaddingValues(8.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(10.dp),
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    content: @Composable RowScope.() -> Unit,
) {
    val focus = rememberChromeFocus()
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(corner.radiusDp.dp))
            .clickable(interactionSource = focus.source, indication = ripple(), onClick = onClick)
            .then(focus.ring(corner))
            .padding(contentPadding),
        verticalAlignment = verticalAlignment,
        horizontalArrangement = horizontalArrangement,
        content = content,
    )
}

/** Trailing chevron with an optional value label to its left ("Medium", "30 minutes"). */
@Composable
fun RowChevron(value: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (value != null) {
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
    }
}

/** Trailing plain value label (no chevron). */
@Composable
fun RowValue(text: String, accent: Boolean = false) {
    Text(
        text,
        color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
    )
}

/** Trailing switch styled to the mockup's purple accent track. */
@Composable
fun RowSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    )
}

/** Single-field inline edit dialog, reused by every "edit this value" affordance. */
@Composable
fun EditFieldDialog(
    title: String,
    initial: String,
    label: String = title,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    NorfoldDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(value, { value = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onSave(value); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Radio-list picker so multi-option value rows show choices instead of blind-cycling.
 *
 * ## Focus
 *
 * Six call sites, and the population where a missing ring is most disorienting: a scrolling list of up to
 * dozens of options inside a dialog, where the whole row is the target but only the [RadioButton] gets a
 * ring from Material. Without a ring on the row, a keyboard user tabbing the list sees the indicator jump
 * between radio buttons while the thing that actually activates is the 300 dp band beside it.
 *
 * Inward, like every other row in this file, because the list is inside a dialog that clips its content and
 * the rows are packed flush with no spacing between them.
 */
@Composable
fun OptionPickerDialog(
    title: String,
    options: List<String>,
    selected: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    NorfoldDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                options.forEach { opt ->
                    val focus = rememberChromeFocus()
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Corner.Media.radiusDp.dp))
                            .clickable(
                                interactionSource = focus.source,
                                indication = ripple(),
                            ) { onPick(opt); onDismiss() }
                            .then(focus.ring(Corner.Media))
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = opt == selected, onClick = { onPick(opt); onDismiss() })
                        Text(opt, fontSize = 15.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/**
 * A colored dot for the accent-picker row.
 *
 * ## Why this is a thin wrapper and not an implementation
 *
 * It was an implementation — the first of what turned out to be **ten** hand-rolled colour swatches in the
 * app, and the one whose focus problem was solved first. That reasoning now lives in [NorfoldColorSwatch],
 * because it is not specific to Settings: a ring drawn on a swatch must be measured against the swatch's fill
 * rather than the page, and its inset must clear the 2.5 dp border this control draws when selected. Both are
 * the shared component's job now.
 *
 * Collapsing it fixed two things this version got wrong, both measured:
 *
 * **It drew no resting edge, so two of the ten accents are nearly invisible.** WCAG 1.4.11 asks 3:1 of a
 * control's boundary, and a bare disc has no boundary but its fill. Gold (`#E08A12`) measures **2.69:1** on
 * the white settings card, and Graphite (`#33374A`) measures **1.58:1** on the dark surface — the accent a
 * dark-theme user is most likely to want is the one that disappears. The shared component's unconditional
 * [NorfoldChrome.restingEdge] is solved against the fill, so it resolves light on Graphite and dark on Gold.
 *
 * **None of the six dots had a name.** The row rendered six unlabelled circles: a screen reader announced six
 * identical buttons, so the accent picker was operable without sight only by trial and error. [BrandPalette]
 * has carried a `displayName` since it was written; the caller passes it.
 *
 * The dot keeps its own 30 dp diameter, which is [SwatchSize] exactly — the two numbers agreed before they
 * were shared, which is part of why this was the right component to generalise from.
 */
@Composable
fun AccentDot(color: Color, selected: Boolean, label: String, onClick: () -> Unit) {
    NorfoldColorSwatch(color = color, selected = selected, label = label, onClick = onClick)
}
