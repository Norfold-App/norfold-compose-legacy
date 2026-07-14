package com.norfold.app.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

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
