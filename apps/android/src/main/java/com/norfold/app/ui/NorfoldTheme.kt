package com.norfold.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.norfold.app.domain.AppSettings
import com.norfold.app.domain.AppIconFamily
import com.norfold.app.domain.ControlLabelMode
import com.norfold.app.domain.EditorIconFamily
import com.norfold.app.domain.ThemeMode
import com.norfold.app.editor.style.ReadingPreference
import com.norfold.app.ui.components.LocalPopupStyle
import com.norfold.app.ui.components.LocalReduceMotion
import com.norfold.app.ui.components.PopupStyle
import com.norfold.app.ui.fonts.NorfoldFontFamilies

// Neutral surface/ink tokens shared by both schemes. These are intentionally hue-free so the
// only brand color in the app is the user-selected [AppSettings.accentColor].
private val LightInk = Color(0xFF171725)
private val DarkInk = Color(0xFFF6F7FB)

val LocalControlLabelMode = staticCompositionLocalOf { ControlLabelMode.Adaptive }
val LocalAppIconFamily = staticCompositionLocalOf { AppIconFamily.MaterialSymbolsRounded }
val LocalEditorIconFamily = staticCompositionLocalOf { EditorIconFamily.UseApp }
val LocalEditorToolbarScale = staticCompositionLocalOf { 1f }

/**
 * How large document *content* is and how wide a line of it may get.
 *
 * A composition local rather than a parameter because the editor hosts are deep and take no [AppSettings] —
 * threading two floats through `FlowHostScreen`, every block renderer and every canvas host would put the
 * preference in dozens of signatures and still miss the next surface someone adds. Its default is the
 * shipped default, so a surface that never receives one renders at 1.0x rather than at nothing.
 *
 * Read by block renderers only. Chrome reads [MaterialTheme] like the rest of the app.
 */
val LocalReadingPreference = staticCompositionLocalOf { ReadingPreference() }

private fun Color.mix(other: Color, t: Float): Color = lerp(this, other, t)

/** Pick black/white ink that stays legible on top of [bg]. */
private fun onColorFor(bg: Color): Color = if (bg.luminance() > 0.5f) LightInk else Color.White

private fun lightScheme(accent: Color) = lightColorScheme(
    primary = accent,
    onPrimary = onColorFor(accent),
    secondary = accent,
    onSecondary = onColorFor(accent),
    tertiary = accent,
    background = Color(0xFFF7F8FC),
    onBackground = LightInk,
    surface = Color.White,
    onSurface = LightInk,
    surfaceVariant = Color(0xFFF1F2F7),
    // Secondary ink has to clear AA on all three light surfaces, not just the whitest one. The previous
    // 0xFF72738A is 4.63:1 on `surface` but 4.36:1 on `background` — which is the colour most screens
    // actually stand on — and 4.14:1 on `surfaceVariant`. This value clears 4.5:1 on all three (5.05 /
    // 4.76 / 4.52) with the same blue-grey cast, so nothing about the palette's character changes.
    onSurfaceVariant = Color(0xFF6C6D84),
    outline = Color(0xFFE4E6EF),
    outlineVariant = Color(0xFFEEF0F5),
    primaryContainer = accent.mix(Color.White, 0.88f),
    onPrimaryContainer = accent.mix(LightInk, 0.30f),
    error = Color(0xFFEF4D5D),
)

private fun darkScheme(accent: Color): androidx.compose.material3.ColorScheme {
    // A dark/graphite accent would vanish on the near-black background, so lift it toward white.
    val surface = Color(0xFF0E131F)
    val brand = accent.mix(Color.White, 0.35f)
    return darkColorScheme(
        primary = brand,
        onPrimary = onColorFor(brand),
        secondary = brand,
        onSecondary = onColorFor(brand),
        tertiary = brand,
        background = Color(0xFF070A12),
        onBackground = DarkInk,
        surface = surface,
        onSurface = DarkInk,
        surfaceVariant = Color(0xFF151B28),
        onSurfaceVariant = Color(0xFF999DB0),
        outline = Color(0xFF252C3A),
        outlineVariant = Color(0xFF1B2230),
        primaryContainer = accent.mix(surface, 0.72f),
        onPrimaryContainer = accent.mix(Color.White, 0.62f),
        error = Color(0xFFFF6471),
    )
}

private fun appFontFamily(name: String): FontFamily = NorfoldFontFamilies.resolve(name)

private fun norfoldTypography(family: FontFamily) = Typography(
    headlineLarge = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 28.sp),
    headlineMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleLarge = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    titleMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    bodyLarge = TextStyle(fontFamily = family, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = family, fontSize = 13.sp),
    labelLarge = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
)

private val NorfoldShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)


private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun NorfoldTheme(
    settings: AppSettings,
    unscaledOverlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    val accent = Color(settings.accentColor)
    val dark = when (settings.themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val view = LocalView.current
    SideEffect {
        view.context.findActivity()?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val density = LocalDensity.current
    val uiScale = settings.uiScale.coerceIn(0.5f, 2f)
    val fontScale = settings.fontScale.coerceIn(0.5f, 2f)
    val layoutScale = uiScale * if (settings.uiDensityCompact) 0.92f else 1f
    val typography = norfoldTypography(appFontFamily(settings.appFont))
    MaterialTheme(
        colorScheme = if (dark) darkScheme(accent) else lightScheme(accent),
        typography = typography,
        shapes = NorfoldShapes,
    ) {
        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = density.density * layoutScale,
                    fontScale = density.fontScale * fontScale,
                ),
                LocalPopupStyle provides PopupStyle(),
                LocalControlLabelMode provides settings.appLabelMode,
                LocalAppIconFamily provides settings.appIconFamily,
                LocalEditorIconFamily provides settings.editorIconFamily,
                LocalEditorToolbarScale provides settings.editorToolbarScale,
                // Provided here rather than threaded through each screen so a new editor surface
                // inherits the accessibility preference by existing, not by remembering to ask.
                LocalReduceMotion provides settings.reduceMotion,
                // Same reasoning for the reading preference: two settings that were persisted but unread
                // until now, resolved once here so every block renderer sees the same pair.
                LocalReadingPreference provides ReadingPreference.of(
                    fraction = settings.editorFontSize,
                    lineWidth = settings.editorLineWidth,
                    face = settings.editorFontFamily,
                ),
                content = content,
            )
            unscaledOverlay()
        }
    }
}
