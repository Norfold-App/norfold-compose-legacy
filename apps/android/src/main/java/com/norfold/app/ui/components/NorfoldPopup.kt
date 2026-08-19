package com.norfold.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.norfold.app.ui.rememberChromeFocus

enum class NorfoldPopupPlacement { Auto, Below, Above }

data class NorfoldPopupTokens(
    val cardRadius: Dp = 12.dp,
    val cardPadding: Dp = 6.dp,
    val anchorGap: Dp = 6.dp,
    val minWidth: Dp = 150.dp,
    val maxWidth: Dp = 330.dp,
    val rowRadius: Dp = 8.dp,
    val rowHorizontalPadding: Dp = 10.dp,
    val rowVerticalPadding: Dp = 8.dp,
)

val LocalNorfoldPopupTokens = staticCompositionLocalOf { NorfoldPopupTokens() }

private class NorfoldPopupPositionProvider(
    private val placement: NorfoldPopupPlacement,
    private val gapPx: Int,
    private val fixedAnchorBounds: IntRect? = null,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val resolvedAnchor = fixedAnchorBounds ?: anchorBounds
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val preferredX = if (layoutDirection == LayoutDirection.Ltr) resolvedAnchor.left else resolvedAnchor.right - popupContentSize.width
        val below = resolvedAnchor.bottom + gapPx
        val above = resolvedAnchor.top - popupContentSize.height - gapPx
        val fitsBelow = below + popupContentSize.height <= windowSize.height
        val fitsAbove = above >= 0
        val y = when (placement) {
            NorfoldPopupPlacement.Below -> below
            NorfoldPopupPlacement.Above -> above
            NorfoldPopupPlacement.Auto -> when {
                fitsBelow -> below
                fitsAbove -> above
                else -> below.coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0))
            }
        }.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(preferredX.coerceIn(0, maxX), y)
    }
}

/**
 * Shared host for anchors measured elsewhere in the layout (for example, a task property row).
 * The supplied bounds are window coordinates and receive the same RTL/clamping/dismissal rules
 * as [NorfoldAnchoredPopup].
 */
@Composable
fun NorfoldAnchoredPopupAt(
    anchorBounds: IntRect,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    placement: NorfoldPopupPlacement = NorfoldPopupPlacement.Auto,
    focusable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalNorfoldPopupTokens.current
    val gapPx = with(LocalDensity.current) { tokens.anchorGap.roundToPx() }
    Popup(
        popupPositionProvider = NorfoldPopupPositionProvider(placement, gapPx, anchorBounds),
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(
            focusable = focusable,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            clippingEnabled = false,
        ),
    ) {
        NorfoldPopupCard(modifier = modifier, content = content)
    }
}

/**
 * Shared anchored-card host. The popup follows the measured trigger, clamps to the visible window,
 * dismisses on outside/back, and exposes active state so the trigger can draw the prototype ring.
 */
@Composable
fun NorfoldAnchoredPopup(
    expanded: Boolean,
    onOpenRequest: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    placement: NorfoldPopupPlacement = NorfoldPopupPlacement.Auto,
    focusable: Boolean = false,
    anchor: @Composable (active: Boolean, open: () -> Unit) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalNorfoldPopupTokens.current
    val gapPx = with(LocalDensity.current) { tokens.anchorGap.roundToPx() }
    Box(modifier) {
        anchor(expanded, onOpenRequest)
        if (expanded) {
            Popup(
                popupPositionProvider = NorfoldPopupPositionProvider(placement, gapPx),
                onDismissRequest = onDismissRequest,
                properties = PopupProperties(
                    focusable = focusable,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                    clippingEnabled = false,
                ),
            ) {
                NorfoldPopupCard(content = content)
            }
        }
    }
}

@Composable
fun NorfoldPopupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalNorfoldPopupTokens.current
    Surface(
        modifier = modifier.widthIn(min = tokens.minWidth, max = tokens.maxWidth),
        shape = RoundedCornerShape(tokens.cardRadius),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 14.dp,
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.padding(tokens.cardPadding), content = content)
    }
}

@Composable
fun NorfoldPopupRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    destructive: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val tokens = LocalNorfoldPopupTokens.current
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val fill = if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .55f) else MaterialTheme.colorScheme.surface
    val focus = rememberChromeFocus()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            // The radius is a popup token, not one of the substrate's tiers, so the ring reads the same
            // token the shape does. A popup that retunes `rowRadius` retunes both together.
            .then(focus.ringOnShape(tokens.rowRadius.value, fill))
            .semantics {
                role = Role.Button
                this.selected = selected
            },
        shape = RoundedCornerShape(tokens.rowRadius),
        color = fill,
        interactionSource = focus.source,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = tokens.rowHorizontalPadding, vertical = tokens.rowVerticalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = if (selected) MaterialTheme.colorScheme.primary else color)
            Text(text, Modifier.weight(1f), color = color, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            trailing?.invoke(this)
        }
    }
}

@Composable
fun NorfoldPopupSectionLabel(text: String) {
    Text(
        text.uppercase(),
        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun NorfoldPopupDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 4.dp, vertical = 5.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/** Material menu compatibility entry point using the same card tokens as anchored popups. */
@Composable
fun NorfoldDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    focusable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalNorfoldPopupTokens.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = tokens.minWidth, max = tokens.maxWidth),
        offset = offset,
        properties = PopupProperties(focusable = focusable),
        shape = RoundedCornerShape(tokens.cardRadius),
        containerColor = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 3.dp,
        shadowElevation = 14.dp,
        content = content,
    )
}

/**
 * Popup styling shared by every Norfold dialog and sheet. Defaults match the Material3 look the
 * app already ships, so providing this local changes nothing until a style is customized.
 * (User-facing customization — persistence + a Settings section — is a parked follow-up.)
 */
data class PopupStyle(
    val dialogCornerRadius: Dp = 28.dp,
    val sheetCornerRadius: Dp = 28.dp,
)

val LocalPopupStyle = staticCompositionLocalOf { PopupStyle() }

/**
 * The single dialog entry point. Every alert-style dialog in the app goes through here so popup
 * styling stays consistent and future customization lands in one place. Signature mirrors
 * [AlertDialog] for mechanical migration.
 */
@Composable
fun NorfoldDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    val style = LocalPopupStyle.current
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = RoundedCornerShape(style.dialogCornerRadius),
        properties = properties,
    )
}

/** The single modal-bottom-sheet entry point; wraps [ModalBottomSheet]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NorfoldBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val style = LocalPopupStyle.current
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = RoundedCornerShape(topStart = style.sheetCornerRadius, topEnd = style.sheetCornerRadius),
        content = content,
    )
}

/** Free-form dialog for fully custom card content (platform default width). */
@Composable
fun NorfoldContentDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest, properties = properties, content = content)
}

/** Edge-to-edge dialog (no platform width cap) for fullscreen surfaces. */
@Composable
fun NorfoldFullscreenDialog(
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        content = content,
    )
}
