package com.norfold.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.norfold.app.domain.style.ChromeGeometry
import com.norfold.app.ui.EditorChromeColors
import com.norfold.app.ui.NorfoldChrome
import com.norfold.app.ui.rememberChromeFocus

@Composable
fun NorfoldPageHeader(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Box(modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
        Surface(Modifier.align(Alignment.CenterStart), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(icon, null, Modifier.padding(9.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.align(Alignment.Center).padding(horizontal = 58.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/**
 * The plain content card: a bordered, unelevated surface.
 *
 * **Not a focus site, and the reason is worth recording** — the census counted this as a population of 6
 * interactive sites needing a focus ring. It is not: the composable took an `onClick` and every one of its
 * six call sites omitted it, so the clickable branch was unreachable and the card has only ever been a
 * static container. Ringing it would have added a focus affordance to a code path no user can reach, and
 * kept a parameter alive that invites a seventh caller to make it interactive without the semantics —
 * `Modifier.clickable` with no `role`, no `onClickLabel` — that a real target needs. Removed instead. A
 * future caller that genuinely needs a clickable card should use [SettingsRow] or add the parameter back
 * together with its semantics and its ring.
 */
@Composable
fun NorfoldCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        content = content,
    )
}

@Composable
fun NorfoldMetric(label: String, value: String, icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    NorfoldCard(modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Surface(shape = RoundedCornerShape(9.dp), color = tint.copy(alpha = 0.12f)) {
                Icon(icon, null, Modifier.padding(7.dp), tint = tint)
            }
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A segmented mode strip: one option active, the rest transparent.
 *
 * ## Focus, and the accent-on-accent trap
 *
 * The active option is filled with `primary`, and the substrate's default ring colour *is* `primary`. A ring
 * measured against the page and drawn on the active segment would sit at 1.00:1 against the surface it is
 * drawn on — invisible on exactly the segment the user is most likely to tab to, since it is the one already
 * selected. So each option measures its ring against its own fill: `onPrimary` territory on the active one,
 * the strip's `surfaceVariant` on the others.
 *
 * The ring is inward because the options are packed 3 dp apart inside a strip that clips them; an outward
 * ring would be drawn on the neighbouring segment or clipped by the strip's own 10 dp shape.
 *
 * Focus is not selection here either: the active segment is already stated by fill, text colour and weight.
 * A user tabbing across the strip is focused on inactive segments most of the time, so the ring has to be
 * legible on a transparent segment as well as on the filled one.
 */
@Composable
fun NorfoldSegmentedControl(options: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            options.forEach { option ->
                val active = option == selected
                val focus = rememberChromeFocus()
                // What the ring is drawn on, not what the strip sits on: the active segment's own accent
                // fill would otherwise measure 1.00:1 against an accent-coloured ring.
                val ringBackdrop = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                Surface(
                    Modifier
                        .clickable(interactionSource = focus.source, indication = ripple()) { onSelect(option) }
                        .then(focus.ringOnShape(SegmentRadius, ringBackdrop)),
                    shape = RoundedCornerShape(SegmentRadius.dp),
                    color = if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                ) {
                    Text(option, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

/**
 * The option's own corner radius, named because the focus ring's radius is derived from it.
 *
 * Not a [com.norfold.app.domain.style.ChromeGeometry.Corner] tier: this strip predates the substrate and
 * 7 dp inside a 10 dp shell is a proportion the tiers do not have. Naming it keeps the shape and the ring
 * reading the same number, which is the property that matters — a ring derived from a different radius than
 * the shape it marks bulges or pinches at every corner.
 */
private const val SegmentRadius = 7f


@Composable
fun NorfoldStatusPill(label: String, color: Color) {
    Text(
        label,
        Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
    )
}

/**
 * One colour a user can choose, from a row of them.
 *
 * ## Why this exists as a component
 *
 * The app shipped **ten** independent versions of this control: the accent picker in Settings, the task accent
 * palette, the chart-builder theme row, a chart row's colour override, the diagram box palette, the colour
 * studio's swatch grid and its gradient stops, the colour-vision preview, the image-markup pen colours, and
 * the chart studio's palette. They agreed on nothing. Diameters ran 22, 26, 30, 32, 34, 36 and 44 dp. Four
 * drew a border, six drew none. Three had a focus ring, seven had none. Selection was stated by a 3 dp border,
 * a 2.5 dp border, an inset ring of surface colour, a tick, or — on the gradient stops — by growing the swatch
 * from 36 to 44 dp. Two of the ten were not controls at all: the colour-vision preview passed an empty click
 * lambda to an interactive swatch, and the chart row's menu put the target on the text beside the dot. Those
 * two are [NorfoldColorDot]. Ringing the rest separately would have made an eleventh variant, so they collapse
 * here.
 *
 * ## Three measured defects this fixes, not one
 *
 * **A swatch with no border can be invisible.** WCAG 1.4.11 asks 3:1 of a control's own boundary, and a bare
 * disc of colour has no boundary but the colour itself. The chart and diagram rows offer
 * `primaryContainer` — `accent.mix(White, 0.88f)` — which measures **1.12:1 to 1.23:1** against the white
 * surface it sits on, across all ten shipped accents. The image-markup row's first pen is pure white on
 * `surface`: **1.00:1**, a swatch that is not there. So the resting edge is not optional here and is drawn
 * unconditionally, from [NorfoldChrome.restingEdge] rather than from `outlineVariant`, which is itself only
 * 1.07:1 on the light page.
 *
 * **A tick tinted `onPrimary` is a tick tinted for the wrong colour.** The task palette drew its selected
 * check in `onPrimary`, which resolves against the *theme accent*, not against the swatch. On the seven
 * fixed palette entries that is white ink, and white measures **2.43:1 on cyan, 2.54:1 on green and 2.15:1
 * on amber** — three of seven below 3:1. [EditorChromeColors.badgeInk] picks the ink from the swatch's own
 * fill, which is 8.65:1, 8.28:1 and 9.78:1 on those three.
 *
 * **A swatch under 24 dp is an undersized target.** WCAG 2.5.8 (AA) asks 24×24, and the palette shipped a
 * 22 dp entry. Every swatch here reserves 48 dp of *layout* — Material's figure, and the stricter one — while
 * keeping its drawn diameter, so a dense row stays dense.
 *
 * ## Focus, on a target that is nothing but a colour
 *
 * Two of the substrate's defaults break here, which is why [AccentDot] — the first of the ten to be ringed —
 * could not just take `Modifier.chromeFocusRing()`. The ring is drawn *on* the swatch, so [color] — not the
 * page — is the adjacent colour the 3:1 is measured against; the default ring colour is `primary`, which on
 * the accent picker's selected swatch is the fill exactly, giving a 1.00:1 ring on the one swatch a keyboard
 * user reaches first. And the inset has to clear whatever this swatch draws at its own boundary, which is
 * [SelectedSwatchBorder] when selected and the substrate's 1 dp edge otherwise — a ring that merges with the
 * selection border says "selected" where it must say "focused".
 *
 * @param selected whether this is the chosen colour. Announced as a selection state rather than drawn only,
 *   so the row is comprehensible without sight; put the row in a `selectableGroup()` and its exclusivity is
 *   announced too.
 * @param label what this colour *is*, for a screen reader. There is no automatic answer: a hex string is
 *   accurate and useless, so the caller names it ("Theme primary", "Amber", "Gradient stop 2").
 * @param size the drawn diameter. Defaults to [SwatchSize]; a caller with an established visual rhythm may
 *   pass its own, and the ring's radius follows it.
 */
@Composable
fun NorfoldColorSwatch(
    color: Color,
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Float = SwatchSize,
) {
    val focus = rememberChromeFocus()
    // The boundary this swatch actually draws, which is what the ring's inset has to clear. Read once so the
    // border below and the inset here cannot disagree.
    val boundary = if (selected) SelectedSwatchBorder else ChromeGeometry.RESTING_EDGE_WIDTH
    Box(
        modifier
            // Order is load-bearing. The ring sits *outside* `minimumInteractiveComponentSize` so its draw
            // scope measures the 48 dp box rather than a 22 dp glyph; `size` comes last, per that modifier's
            // own contract, so it cannot fight the expansion.
            .then(focus.ringOnCircle(size, color, boundaryWidthDp = boundary))
            .minimumInteractiveComponentSize()
            .size(size.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                boundary.dp,
                // Selection is stated in the theme's own ink so it reads as a decision the app made. The
                // resting edge is *solved* against this swatch's fill instead, because the fill is arbitrary:
                // no fixed token is visible on both white and near-black.
                if (selected) MaterialTheme.colorScheme.onSurface else with(NorfoldChrome) { restingEdge(color) },
                CircleShape,
            )
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                interactionSource = focus.source,
                indication = ripple(),
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                // The selection is already in the semantics via `selectable`; a description here would have
                // the reader announce it twice.
                contentDescription = null,
                tint = EditorChromeColors.badgeInk(color),
                modifier = Modifier.size((size * SwatchTickRatio).dp),
            )
        }
    }
}

/** The default drawn diameter, named because the focus ring's radius and the tick's size derive from it. */
const val SwatchSize = 30f

/**
 * A colour shown, not offered.
 *
 * The non-interactive sibling of [NorfoldColorSwatch], and the distinction is the whole point: this takes no
 * `onClick`, so it draws no focus ring, claims no role and occupies no focus stop. Two sites in the app showed
 * a colour the user cannot pick — the colour-vision preview, which simulates what the current colour looks
 * like to a dichromat, and the chart row's colour menu, where the *text* beside the dot is the target. Both
 * had reached for the interactive swatch, which put a keyboard stop on something that does nothing when
 * activated. One of them had an empty click lambda to prove it.
 *
 * It still draws a boundary, for [NorfoldColorSwatch]'s first reason: a bare disc of colour has no edge but
 * the colour, and the palettes these two show include `*Container` tokens measuring about 1.15:1 against the
 * surface beneath them. Without the edge, those entries are a gap in the row rather than a colour.
 *
 * @param label what this colour is, for a screen reader, when this dot is the *only* thing that says so.
 *   Defaults to null — decorative, which is correct whenever adjacent text already names the colour.
 */
@Composable
fun NorfoldColorDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Float = ColorDotSize,
    label: String? = null,
) {
    Spacer(
        modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color)
            .border(ChromeGeometry.RESTING_EDGE_WIDTH.dp, with(NorfoldChrome) { restingEdge(color) }, CircleShape)
            .then(if (label == null) Modifier else Modifier.semantics { contentDescription = label }),
    )
}

/** The default dot diameter: the size a colour reads at beside a line of text. */
const val ColorDotSize = 22f

/**
 * The weight a swatch draws at its own boundary when selected.
 *
 * Named because the focus ring's inset has to clear it: the two numbers cannot drift apart without the ring
 * merging into the selection border. Matches [AccentDot], which set this weight before the component existed.
 */
private const val SelectedSwatchBorder = 2.5f

/**
 * The tick's size as a fraction of the swatch.
 *
 * A fraction rather than a fixed dp because this component is sized by its caller — 16 dp of tick reads
 * correctly inside 26 dp and swamps 22 dp.
 */
private const val SwatchTickRatio = 0.55f

/**
 * The one empty-state pattern (reference: chat's empty state): icon in a tinted rounded
 * surface → Black title → capped-width detail → optional action content (button/chips).
 */
@Composable
fun NorfoldEmptyState(
    icon: ImageVector,
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 42.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(18.dp).size(34.dp),
            )
        }
        Text(title, fontWeight = FontWeight.Black, fontSize = 20.sp, textAlign = TextAlign.Center)
        Text(
            detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 520.dp),
        )
        action?.invoke()
    }
}
