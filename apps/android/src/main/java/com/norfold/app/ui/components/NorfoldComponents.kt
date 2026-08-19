package com.norfold.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
