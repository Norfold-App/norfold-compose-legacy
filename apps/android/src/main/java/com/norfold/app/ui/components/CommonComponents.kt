package com.norfold.app.ui.components

import android.graphics.Color as AndroidColor
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.norfold.app.branding.BrandPalette
import com.norfold.app.branding.NorfoldGlyphDrawIn
import com.norfold.app.branding.NorfoldInk
import com.norfold.app.branding.NorfoldInkOnDark
import com.norfold.app.branding.NorfoldWordmarkText
import com.norfold.app.branding.norfoldRiseIn
import com.norfold.app.domain.WorkspaceIconKind
import com.norfold.app.branding.palette
import com.norfold.app.domain.ThemeProfile
import com.norfold.app.domain.ControlLabelMode
import com.norfold.app.domain.SemanticActionId
import com.norfold.app.ui.LocalControlLabelMode

@Composable
fun ComposeLoadingScreen(palette: BrandPalette = ThemeProfile.Norfold.palette(), reduceMotion: Boolean = false) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // Mirrors NorfoldTheme background tokens AND @color/splash_bg; keep all three in sync
    // so the native splash hands off to this screen without a visible seam.
    val bgColors = if (dark) listOf(Color(0xFF070A12), Color(0xFF12151F)) else listOf(Color(0xFFF7F8FC), Color(0xFFEEF0F5))
    val ink = if (dark) NorfoldInkOnDark else NorfoldInk
    val pulse = if (reduceMotion) {
        0.68f
    } else {
        rememberInfiniteTransition(label = "loadingPulse").animateFloat(
            initialValue = 0.35f,
            targetValue = 0.78f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "loadingTextAlpha",
        ).value
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(bgColors)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // The brand intro: the glyph draws itself in, the dot pops, the wordmark rises.
            NorfoldGlyphDrawIn(size = 116.dp, ink = ink, reduceMotion = reduceMotion)
            Column(
                Modifier.norfoldRiseIn(reduceMotion = reduceMotion),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NorfoldWordmarkText(fontSize = 30.sp, color = ink)
                Text("Your own private workspace.", fontSize = 13.sp, color = ink.copy(alpha = 0.68f))
            }
            Text("Opening workspace", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary.copy(alpha = pulse))
        }
    }
}

@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(value, onValueChange, modifier = modifier, singleLine = true, leadingIcon = { Icon(Icons.Outlined.Search, null) }, label = { Text("Search everything") })
}

@Composable
fun NavBarItem(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier = Modifier, badgeCount: Int = 0, onClick: () -> Unit) =
    NavBarItem(label, selected, modifier, badgeCount, onClick) { tint -> Icon(icon, null, tint = tint) }

@Composable
fun NavBarItem(label: String, icon: SemanticActionId, selected: Boolean, modifier: Modifier = Modifier, badgeCount: Int = 0, onClick: () -> Unit) =
    NavBarItem(label, selected, modifier, badgeCount, onClick) { tint -> SemanticAppIcon(icon, null, tint = tint) }

@Composable
private fun NavBarItem(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    badgeCount: Int,
    onClick: () -> Unit,
    iconContent: @Composable (Color) -> Unit,
) {
    val showLabel = when (LocalControlLabelMode.current) {
        ControlLabelMode.IconsOnly -> false
        ControlLabelMode.IconsAndText -> true
        ControlLabelMode.Adaptive -> selected
    }
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = label }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            iconContent(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            if (badgeCount > 0) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 9.dp, y = (-4).dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        if (badgeCount > 99) "99+" else badgeCount.toString(),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        maxLines = 1,
                    )
                }
            }
        }
        if (showLabel) Text(label, fontSize = 11.sp, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
fun EmptyNotes(onCreateNote: () -> Unit, onCreateDocument: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No notes or documents yet", fontWeight = FontWeight.Black, fontSize = 22.sp)
        Text(
            "Capture ideas before they fade away.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCreateNote) { Text("Create note") }
            OutlinedButton(onClick = onCreateDocument) { Text("Create document") }
        }
    }
}


/**
 * Workspace icon rendered consistently everywhere: image/GIF icons when set,
 * otherwise the text/emoji glyph on the workspace accent.
 */
@Composable
fun WorkspaceIconBadge(
    icon: String,
    iconKind: WorkspaceIconKind,
    iconUri: String?,
    size: Dp,
    corner: Dp,
    background: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    fontSize: TextUnit = 18.sp,
    maxGlyphs: Int = 2,
) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(corner)).background(background),
        contentAlignment = Alignment.Center,
    ) {
        if ((iconKind == WorkspaceIconKind.Image || iconKind == WorkspaceIconKind.Gif) && !iconUri.isNullOrBlank()) {
            AsyncImage(iconUri, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        } else {
            Text(icon.ifBlank { "N" }.take(maxGlyphs), color = contentColor, fontWeight = FontWeight.Black, fontSize = fontSize)
        }
    }
}
