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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import com.norfold.app.domain.style.ChromeGeometry
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
 * ## Focus, on a target that is nothing but a colour
 *
 * Every other ringed component in the app has chrome the ring can be measured against. This one is a 30 dp
 * disc of arbitrary user-chosen colour, which breaks two of the substrate's defaults at once.
 *
 * **The ring cannot be measured against the page.** It is drawn on the swatch, so the swatch's own fill is
 * the adjacent colour WCAG 1.4.11 asks about — and the default ring colour is `primary`, the currently
 * selected accent, which is the exact fill of the currently selected swatch. Measured against the page and
 * drawn on the swatch, the ring on the selected dot would sit at 1.00:1 against what it is actually drawn
 * on: invisible, on the one swatch a keyboard user is most likely to reach first. `ringOnCircle` measures
 * against the fill instead, so the ring resolves light on a dark accent and dark on a light one.
 *
 * **The ring cannot use the default inset**, because this component states selection with a 2.5 dp border at
 * its own boundary — heavier than the substrate's 1 dp edge, which is what the default inset is derived to
 * clear. At 3 dp the ring's outer edge would land half a dp inside that border and the two would merge into
 * one thick stroke, saying "selected" where it should say "focused". [ChromeGeometry.focusRingInsetOver]
 * clears the real boundary and then leaves the gap.
 *
 * The radius comes from the size rather than from a [Corner] tier for the ordinary reason that a circle's
 * radius is half its own size, and no fixed tier is right for both a 30 dp swatch and a 46 dp avatar.
 */
@Composable
fun AccentDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    val focus = rememberChromeFocus()
    Box(
        Modifier
            .size(AccentDotSize.dp)
            .clip(CircleShape)
            .background(color)
            .then(
                if (selected) Modifier.border(SelectedSwatchBorder.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                else Modifier,
            )
            .clickable(interactionSource = focus.source, indication = ripple(), onClick = onClick)
            .then(
                focus.ringOnCircle(
                    sizeDp = AccentDotSize,
                    surface = color,
                    boundaryWidthDp = if (selected) SelectedSwatchBorder else ChromeGeometry.RESTING_EDGE_WIDTH,
                ),
            ),
    )
}

/** The swatch diameter, named because the focus ring's radius is derived from it. */
private const val AccentDotSize = 30f

/**
 * The weight this component draws at its own boundary when selected.
 *
 * Named for the same reason: the focus ring's inset has to clear it, so the two numbers cannot drift apart.
 */
private const val SelectedSwatchBorder = 2.5f
