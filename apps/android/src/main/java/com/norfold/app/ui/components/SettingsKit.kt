package com.norfold.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.norfold.app.domain.style.ChromeGeometry
import com.norfold.app.domain.style.ChromeGeometry.Corner
import com.norfold.app.ui.LocalChromeContainer
import com.norfold.app.ui.NorfoldChrome
import com.norfold.app.ui.rememberChromeFocus
import com.norfold.app.ui.selectableChrome

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

/**
 * A rounded card that clips a vertical stack of rows and paints hairline dividers between them.
 *
 * Provides [LocalChromeContainer] because the card's fill is **not** the page, and in light mode it is not a
 * theme colour either: it is `Color.White`, deliberately brighter than the `#F7F8FC` page so the group reads as
 * raised. Every row inside draws its focus ring on that card, so a ring solved against the page is solved
 * against a colour that is nowhere inside this group.
 *
 * **This one is measured as correct either way, and is stated anyway.** All ten shipped accents clear WCAG
 * 1.4.11's 3:1 with or without the container — worst Gold 3.19:1 light and Graphite 4.17:1 dark solved against
 * the page, worst Gold 3.05:1 and Graphite 4.17:1 solved against the card. So this line fixes no present
 * failure. It is here because the *reason* it passes is luck: white is close enough to `#F7F8FC` that a ring
 * solved against one lands legibly on the other, and nothing keeps that true. A group that tinted its card, or
 * a row that painted a selection wash of its own, would composite onto the page and be wrong immediately —
 * which is exactly how the five populations [LocalChromeContainer] documents came to fail. Naming the container
 * costs one line and removes the coincidence.
 *
 * The dark figures are equal because both backdrops are dark enough that the solver lands on the same colour;
 * they are also the *Oklab* figures. The dark accent reaches this row as `accent.mix(White, 0.35f)`, and
 * `NorfoldTheme.mix` is `androidx.compose.ui.graphics.lerp`, which interpolates in Oklab rather than per
 * channel — Graphite lifts to `#747785`, not the `#7A7D89` a channel-wise mix gives, and 4.17:1 rather than
 * 4.53:1. `ChromeGeometryTest.liftedAccents` holds the measured table.
 *
 * Note the light figure got *smaller* when the measurement got correct (3.19 → 3.05). A ring is solved by
 * searching outward from the accent until it clears the ratio, so a search against the wrong backdrop can
 * overshoot as easily as undershoot; a bigger number is not evidence of a better measurement.
 */
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
                    width = ChromeGeometry.RESTING_EDGE_WIDTH.dp,
                    // Measured, and it has to be: this edge is the only thing that says where a Settings group
                    // begins, because the other cue is not doing any work. The card's fill separates from the
                    // page by 1.0613:1 light and 1.0664:1 dark — a difference no eye reads as a boundary — so a
                    // faint stroke here is not one cue among several, it is the cue. As written it was
                    // `outline` reduced to 0.7/0.5 alpha, which drew 1.1637:1 light and 1.1320:1 dark against
                    // the card it surrounds; the alpha was cutting into a token that already only reaches
                    // 1.2453:1 and 1.3256:1 at full strength. Dropping the mode conditional with it: it was
                    // compensating for the two modes' tokens differing, which is precisely what the solver does
                    // from the measured pixel instead of from a guess.
                    //
                    // [cardColor] is named rather than left to the ambient container on purpose. The provider
                    // below is *inside* this `Column`, so at the point this modifier chain is built the ambient
                    // answer is still the Settings page — the card publishes itself for its rows, not for its
                    // own edge.
                    color = with(NorfoldChrome) { restingEdge(cardColor) },
                    shape = GroupShape,
                ),
        ) {
            // The card's own `ColumnScope`, captured so `content` still receives it inside the provider —
            // the provider's lambda has no receiver and every group writes column-scoped rows.
            val scope = this
            CompositionLocalProvider(LocalChromeContainer provides cardColor) {
                with(scope) { content() }
            }
        }
    }
}

/**
 * Hairline divider that lines up under the row text (past the icon tile).
 *
 * Deliberately left on the raw token at 0.35 alpha, and not routed through
 * [NorfoldChrome.restingEdge] like [SettingsGroup]'s edge above. A divider is not a container edge: it does not
 * say where anything begins or ends, it says two adjacent rows are separate — and the rows already say that
 * themselves, through 60 dp of consistent text inset, a tile per row and the whole card's shape. Solving this
 * line to 2.2:1 would make a stack of eight settings rows read as eight boxes, which is the box farm the group
 * card exists to avoid. It measures 1.0776:1 light and 1.0863:1 dark against the card, and that faintness is the
 * intent rather than an oversight: it is a seam, not a boundary. `ChromeGeometryTest` exempts it by name.
 */
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
    SettingsRowBody(base, title, subtitle, icon, iconTint, titleColor, trailing)
}

/**
 * A settings row whose whole width toggles one boolean, and which is **one** accessibility node rather than two.
 *
 * ## The defect this exists to fix
 *
 * The pattern it replaces is `SettingsRow(title, trailing = { RowSwitch(checked, onChange) })`. That renders a
 * label and a working switch, and it looks correct. What it produces in the semantics tree is two nodes for one
 * setting:
 *
 * - the row, which carries the **title** and no state, and — at thirteen of the fourteen sites — no action either
 * - the `Switch`, which carries the **state and the action** and no name
 *
 * `clickable` does declare `shouldMergeDescendantSemantics = true`, so it is easy to assume the switch is folded
 * into the row. It is not: Compose's merge walk stops descending at any child that itself merges, and every
 * Material selection control is such a child — `Switch` applies `Modifier.toggleable`, which merges. So the two
 * nodes survive as two, and a screen reader reaches a named thing that reports no state followed by a stateful
 * thing that reports no name. That is WCAG 4.1.2 (Name, Role, Value) at Level A, one level stricter than the
 * 2.4.7 focus work this file's rings are for.
 *
 * It is not merely untidy. `SettingsScreen`'s workspace-permissions group stacks six of these, so a reader
 * hears six unnamed switches in a row and has to count to know which permission it is about to change.
 *
 * ## Why the row takes the action rather than the switch keeping it
 *
 * Because the label lives on the row. Naming the switch instead would mean repeating the title inside a
 * `contentDescription` at every call site — the same string written twice, free to drift, and still leaving two
 * focus stops for one setting. Moving the action outward gives one stop that has all three of what 4.1.2 asks:
 * the title as its name, `Role.Switch` as its role, and the toggle state as its value. `Modifier.toggleable`
 * supplies role and value from `checked`, so nothing here is hand-written.
 *
 * The `Switch` then receives `null` for its handler, which is not a cosmetic disable. Material gates its
 * *entire* interaction modifier on that parameter being non-null — verified in `SwitchKt`, where the null branch
 * substitutes a bare `Modifier` — so the control stops being a semantics node, stops being focusable, and stops
 * consuming the tap. It keeps drawing, and it keeps animating from `checked`.
 *
 * **One consequence of that gate has to be paid back here.** The branch Material skips also carries
 * `minimumInteractiveComponentSize`, the 48 dp floor the switch was providing on the row's behalf. With the
 * handler gone, that floor goes with it, so this row states `heightIn(min = 48.dp)` itself. Today the drawn
 * switch is 32 dp tall inside 24 dp of vertical padding, so the row already measures 56 dp and the constraint
 * changes no pixel; it is here so that the target survives a future change to either number.
 *
 * The focus ring is inward at [Corner.Card], for the same structural reason [SettingsRow] gives: [SettingsGroup]
 * clips its children, so an outward ring would be cut away on the first and last row of every group.
 *
 * **Why this is wired by hand rather than through [com.norfold.app.ui.toggleableChrome].** That modifier also
 * clips the ripple to the radius it is given, which is right for a row that is its own rounded surface and wrong
 * for this one. These rows are stacked flush inside [SettingsGroup], which clips the whole group at 20 dp: the
 * rows in the middle are square, and the first and last follow the *group's* radius, not a row radius. Clipping
 * each row at [Corner.Card] would round four corners that the user sees as straight. The group's own clip already
 * bounds the ripple correctly, so there is nothing left for a per-row clip to fix.
 */
@Composable
fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
) {
    val focus = rememberChromeFocus()
    SettingsRowBody(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = checked,
                interactionSource = focus.source,
                indication = ripple(),
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onChange,
            )
            .then(focus.ring(Corner.Card)),
        title,
        subtitle,
        icon,
        iconTint,
        titleColor,
    ) { RowSwitch(checked, null, enabled) }
}

/**
 * The row's visuals, with no opinion about how it is activated.
 *
 * Extracted so [SettingsRow] and [SettingsToggleRow] cannot drift: they differ in exactly one thing — whether
 * the row is `clickable` or `toggleable` — and everything a user sees is shared. [modifier] arrives with the
 * interaction and the ring already applied, and the interior padding is added here so a ring is drawn on the
 * row's full bounds rather than inside its text inset.
 */
@Composable
private fun SettingsRowBody(
    modifier: Modifier,
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    iconTint: Color,
    titleColor: Color,
    trailing: @Composable (() -> Unit)?,
) {
    Row(
        modifier.padding(horizontal = 14.dp, vertical = 12.dp),
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
 * @param backdrop the colour the row is drawn on, which is what its ring is measured against. Defaults to
 *   `Color.Unspecified`, which the substrate resolves to [com.norfold.app.ui.LocalChromeContainer] — the colour
 *   of whatever container the row was placed in, set by the dialog, sheet or popup that placed it. That is the
 *   right default because a row cannot know its own host: five of these rows appear in a dialog, one on a page,
 *   and the same composable is reused in both. Naming a colour here would have been an assertion about the
 *   caller's ancestor, and the earlier default — `surface` — was measurably the wrong one inside a dialog, where
 *   3 of the 10 shipped accents fell under 3:1. Pass a colour only when the row paints a fill of its own that
 *   the container does not: a selection wash, a swatch, a coloured tile.
 *
 *   A row inside an *elevated* surface needs nothing either, **but only because its host announces itself.**
 *   `NorfoldChrome.focusRing` does read the ambient elevation, and it also honours Material's own gate: the
 *   tint is applied only when the resolved backdrop **is** `colorScheme.surface`. So an unspecified backdrop
 *   inside a host that provides no container resolved to `background` and skipped the correction entirely —
 *   the two rows placed in [com.norfold.app.ui.components.NorfoldContentDialog] were solved against the page
 *   behind the dialog and drawn on 4-6 dp of accent-tinted surface, at **2 of 10 accents under 3:1, floor
 *   2.8816:1** in light mode (dark was unaffected: the solver returns the lifted primary unchanged there).
 *   That is fixed where it belonged, at the dialog, which now names the colour all seventeen of its callers
 *   paint. The rule for a caller here is unchanged and now actually holds.
 */
@Composable
fun ChoiceRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    corner: Corner = Corner.Media,
    contentPadding: PaddingValues = PaddingValues(8.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(10.dp),
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    backdrop: Color = Color.Unspecified,
    content: @Composable RowScope.() -> Unit,
) {
    val focus = rememberChromeFocus()
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(corner.radiusDp.dp))
            .clickable(interactionSource = focus.source, indication = ripple(), onClick = onClick)
            .then(focus.ring(corner, backdrop))
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

/**
 * Trailing switch styled to the mockup's purple accent track.
 *
 * @param onChange the handler, **nullable on purpose**. Passing `null` is how [SettingsToggleRow] makes this
 *   switch decorative: Material gates the switch's entire `toggleable` modifier on this parameter being
 *   non-null, so a null handler removes it from the semantics tree and from the focus order, leaving the row it
 *   sits in as the single node that carries the setting's name, role and state. It still draws, and still
 *   animates from [checked]. A caller that owns the tap itself should pass `null`; a bare switch with no row
 *   around it must pass a handler, or it will render as an inert graphic.
 * @param enabled forwarded so a disabled row and its switch cannot disagree about whether the setting is
 *   available.
 */
@Composable
fun RowSwitch(checked: Boolean, onChange: ((Boolean) -> Unit)?, enabled: Boolean = true) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        enabled = enabled,
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
 *
 * ## One node per option, and a group around them
 *
 * The row is selectable rather than clickable, and the [RadioButton] is passed a null `onClick`. Before, both were
 * live, and because Compose's semantics merge stops at any descendant that merges — which every Material
 * selection control is — each option was **two** nodes: a row carrying the option's text and no selection
 * state, then a radio button carrying the state and no text. A reader moving through a font list heard the name
 * and the selection as two separate stops, neither complete.
 *
 * [com.norfold.app.ui.selectableChrome] gives one node with all three: the option's text as its name,
 * `Role.RadioButton`, and the selection as its state. `selectableGroup` on the column then makes the list announce
 * itself as one exclusive choice with a position — "2 of 9" — rather than as N independent controls that happen to
 * be stacked. Unlike [SettingsToggleRow], each option here *is* its own rounded surface, so the modifier's ripple
 * clip at [Corner.Media] is what the row already draws.
 *
 * The radio button keeps drawing from `selected`; only its interaction is gone. That also removes the 48 dp
 * floor Material was attaching to it, which the modifier restates. The 12 dp of vertical padding around a 20 dp
 * indicator already exceeds it, so nothing moves today.
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
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
                    .selectableGroup(),
            ) {
                options.forEach { opt ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectableChrome(
                                selected = opt == selected,
                                corner = Corner.Media,
                            ) { onPick(opt); onDismiss() }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = opt == selected, onClick = null)
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
