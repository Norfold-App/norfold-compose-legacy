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
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.norfold.app.ui.EditorChromeColors
import com.norfold.app.ui.LocalChromeContainer
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

/**
 * The anchored popup's card.
 *
 * Provides [LocalChromeContainer] for the reason [NorfoldDialog] states, and here the container is not a token
 * but the colour this `Surface` actually ends up painting: `surface` plus the 3 dp of tonal tint Material
 * composites over it, which on this app is a wash of the user's accent because `NorfoldTheme` never sets
 * `surfaceTint`. [NorfoldPopupRow]'s selected fill is translucent, so it composites onto that — and onto the
 * page instead, the ring failed **1 of 10** accents (Sunset 2.95:1) where all ten now pass.
 *
 * `EditorChromeColors.elevated` is called here rather than left to the substrate because the substrate's own
 * elevation correction applies only to a backdrop that *is* `colorScheme.surface`, matching Material's gate.
 * The row's fill is not — it is `secondaryContainer` at 55% alpha — so it takes the alpha path, and the colour
 * that path composites onto has to already carry the tint.
 */
@Composable
fun NorfoldPopupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalNorfoldPopupTokens.current
    val scheme = MaterialTheme.colorScheme
    val container = EditorChromeColors.elevated(
        surface = scheme.surface,
        tint = scheme.surfaceTint,
        elevationDp = CardTonalElevation.value,
    )
    Surface(
        modifier = modifier.widthIn(min = tokens.minWidth, max = tokens.maxWidth),
        shape = RoundedCornerShape(tokens.cardRadius),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 14.dp,
        tonalElevation = CardTonalElevation,
    ) {
        CompositionLocalProvider(LocalChromeContainer provides container) {
            Column(Modifier.padding(tokens.cardPadding), content = content)
        }
    }
}

/** The tonal elevation the popup card and the dropdown menu share, named so the two cannot drift. */
private val CardTonalElevation = 3.dp

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
    // Selected rows paint a translucent fill, which `NorfoldChrome.focusRing` composites onto the card before
    // measuring — a colour carrying alpha is one the contrast arithmetic cannot search against at all. What it
    // composites onto is `LocalChromeContainer`, which [NorfoldPopupCard] and [NorfoldDropdownMenu] set to the
    // card's own painted colour including its accent tint; against the page instead, one of the ten accents
    // fell to 2.95:1. The unselected fill is plain `surface`, which takes the substrate's elevation path.
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

/**
 * Material menu compatibility entry point using the same card tokens as anchored popups.
 *
 * Provides [LocalChromeContainer] for the same reason [NorfoldPopupCard] does, resolved the same way — this is
 * the other surface that paints `surface` plus [CardTonalElevation] of accent tint, and menu content is often
 * the same [NorfoldPopupRow] the card holds.
 */
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
    val scheme = MaterialTheme.colorScheme
    val container = EditorChromeColors.elevated(
        surface = scheme.surface,
        tint = scheme.surfaceTint,
        elevationDp = CardTonalElevation.value,
    )
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier.widthIn(min = tokens.minWidth, max = tokens.maxWidth),
        offset = offset,
        properties = PopupProperties(focusable = focusable),
        shape = RoundedCornerShape(tokens.cardRadius),
        containerColor = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = CardTonalElevation,
        shadowElevation = 14.dp,
    ) {
        val scope = this
        CompositionLocalProvider(LocalChromeContainer provides container) {
            with(scope) { content() }
        }
    }
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
 *
 * ## Why it provides [LocalChromeContainer]
 *
 * A dialog is a container, and chrome inside a container is drawn on the container rather than on the page.
 * Every focus ring and resting edge in the substrate is solved against the colour it is drawn on, so a row
 * inside this dialog measured against the page is measured against a colour that is nowhere on screen. On the
 * ten shipped accents in light mode that cost **3 of 10** rows WCAG 1.4.11's 3:1 — Gold 2.60:1, Sunset 2.61:1,
 * Forest 2.74:1 — and all ten pass against the real container. The rows do not have to know: they read the
 * local, which is provided here because this is the only place that knows what the container is.
 *
 * **The colour comes from [AlertDialogDefaults.containerColor], not from a literal.** That accessor resolves
 * `DialogTokens.ContainerColor` through the active scheme, so whatever the container is *now* is what the ring
 * is measured against. That indirection has already earned itself: the figures above were measured while
 * `NorfoldTheme` left the container tokens unset and the dialog stood on Material's baseline `#ECE6F0`. The
 * theme now derives the whole ramp in Norfold's own hue, the dialog paints `#F4F5FA`, and this line needed no
 * edit — the population fell from 3 of 10 to 2 of 10 by itself, floor Gold 2.93:1, and all ten still pass
 * against the real container. A literal here would have silently gone on measuring a colour that is no longer
 * on screen.
 *
 * It is also passed to `containerColor` rather than left to default, even though the default is this same
 * accessor. One expression paints the container and informs the local, so the two cannot drift apart — not
 * from a change here, and not from a Material release that redirects `AlertDialog`'s own default.
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
    val container = AlertDialogDefaults.containerColor
    CompositionLocalProvider(LocalChromeContainer provides container) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = modifier,
            dismissButton = dismissButton,
            icon = icon,
            title = title,
            text = text,
            shape = RoundedCornerShape(style.dialogCornerRadius),
            containerColor = container,
            properties = properties,
        )
    }
}

/**
 * The single modal-bottom-sheet entry point; wraps [ModalBottomSheet].
 *
 * Provides [LocalChromeContainer] for the reason [NorfoldDialog] states: chrome inside a sheet is drawn on the
 * sheet. This host is where the cost showed up worst, because the sidebar's nav rows appear in it — a selected
 * destination's 12% accent wash, composited onto the page instead of onto the sheet, failed **4 of 10** accents
 * (Gold 2.9430:1, Sunset 2.9747:1, Forest 2.9936:1, Candy 2.9999964:1) and a resting one failed 2 of 10. All ten
 * pass once the wash composites onto the colour the sheet actually paints.
 *
 * Candy's seven decimals are not affectation. `Color.copy(alpha = 0.12f)` composites at 31/255 = 0.121569 rather
 * than 0.12, because sRGB `Color` stores 8-bit channels — enough to move one channel by one bit, and on the
 * narrowest of these populations one bit is the difference between 2.9999964 and passing. This population was
 * documented as 3 of 10 until `ChromeGeometryTest` ran against `ColorValue`'s arithmetic instead of a
 * hand-computed model.
 *
 * That colour is [BottomSheetDefaults.containerColor], which resolves `SheetBottomTokens.DockedContainerColor`
 * — `surfaceContainerLow`. Passed to `containerColor` as well as to the local so the painted colour and the
 * measured one are one expression.
 *
 * **The figures above were measured against Material's baseline `#F7F2FA`, and the theme has since derived its
 * own ramp — which is why this local is a resolution and not a tolerance.** `surfaceContainerLow` is now
 * `#FBFCFF`, *lighter* than the page behind it, so for this one host guessing the page became the pessimistic
 * guess and both sheet populations now measure 0 of 10 either way. That is an accident of which side of the page
 * one token landed on, not a repair: `surfaceContainerHigh` moved to `#F4F5FA`, which is darker than the page, so
 * [NorfoldDialog]'s population still fails 2 of 10. A per-host exemption sized to the old numbers would have
 * been silently wrong in both directions after one theme edit; reading the painted colour cannot be.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NorfoldBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val style = LocalPopupStyle.current
    val container = BottomSheetDefaults.containerColor
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = RoundedCornerShape(topStart = style.sheetCornerRadius, topEnd = style.sheetCornerRadius),
        containerColor = container,
    ) {
        // The sheet's own `ColumnScope`, captured so `content` still receives it inside the provider — the
        // provider's lambda has no receiver of its own, and every caller writes column-scoped content.
        val scope = this
        CompositionLocalProvider(LocalChromeContainer provides container) {
            with(scope) { content() }
        }
    }
}

/**
 * Free-form dialog for fully custom card content (platform default width).
 *
 * **Why this one names a container it does not paint.** The other four providers in this file each wrap a
 * surface they own, so the colour they announce is a colour they drew. This dialog draws nothing: it is a
 * bare [Dialog], and its seventeen callers each build their own `Surface` inside it. That is the whole point
 * of the composable — a confirmation, a picker, a report form and a video player have nothing in common but
 * the window.
 *
 * It still has to announce something, because the alternative is measurably wrong. With no provider,
 * [LocalChromeContainer] stays unspecified and `NorfoldChrome.resolvedBackdrop` falls back to
 * `colorScheme.background` — the page *behind* the dialog, which no chrome inside it is drawn on. Worse, that
 * fallback also defeats the tonal-tint correction: Material applies the tint only when the resolved backdrop
 * **is** `colorScheme.surface` (verified in `ColorScheme.applyTonalElevation`, which gates on
 * `Color.equals-impl0(color, scheme.surface)` before compositing), so resolving to `background` skips a step
 * that exists precisely for this case. Chrome in here was therefore solved against the page and drawn on
 * accent-tinted surface: **2 of the 10 shipped accents fell under 1.4.11's 3:1 at 4 dp, floor 2.8816:1
 * (Sunset, then Gold 2.9230:1)**, and 3 of 10 at 6 dp. Naming `surface` puts the tint gate back in play, and
 * all ten pass at every elevation used here. `ChromeGeometryTest` holds the measured table.
 *
 * So the claim is narrow and true: *every one* of the seventeen callers paints `colorScheme.surface`, at
 * elevations from 0 to 6 dp. Sixteen name the colour; `NotesHomeScreen`'s note-actions sheet omits it and
 * takes `Surface`'s own default, which is the same token — verified in bytecode, where every `Surface` overload
 * loads `ColorScheme."getSurface-0d7_KjU"` for that parameter. The elevation is not guessed either: `Surface`
 * provides the accumulated `LocalAbsoluteTonalElevation` to its own content, so a row inside a 6 dp dialog
 * resolves at 6 dp without this dialog knowing which caller it is. Only the *colour* has to be stated here,
 * and there is one answer.
 *
 * **This is a light-mode fix.** In dark mode the two solvers return the *same colour* for all ten accents at
 * every one of these elevations, because each lifted primary already clears 3:1 against both candidate
 * backdrops and the solver returns its input unchanged. Dark floors at 3.74:1 either way. A fix that only
 * moves one theme is still a fix, but it should not be described as if it moved both.
 *
 * A caller that paints something else would have to pass its own backdrop at the chrome site, exactly as
 * chrome on any coloured fill already does. [NorfoldFullscreenDialog] gets no provider for that reason: one
 * of its two callers fills `Color.Black` for a video player, so there is no single answer to state.
 */
@Composable
fun NorfoldContentDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    val container = MaterialTheme.colorScheme.surface
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        CompositionLocalProvider(LocalChromeContainer provides container) { content() }
    }
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
