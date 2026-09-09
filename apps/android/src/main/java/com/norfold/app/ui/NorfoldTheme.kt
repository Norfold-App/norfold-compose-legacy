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

// Neutral surface/ink tokens shared by both schemes. These are intentionally hue-free — pure
// greyscale, r==g==b — so the only colour in the app shell is the user-selected
// [AppSettings.accentColor]. Per the binding monochrome contract (light starts at #FFFFFF, dark at
// #000000; neutral tiers may carry hierarchy but must not be hue-tinted), the earlier blue-grey
// cast was removed. Authored content (documents, thumbnails, semantic error/warning colours) keeps
// its own colour and is not governed by these tokens.
private val LightInk = Color(0xFF1B1B1B)
private val DarkInk = Color(0xFFF5F5F5)

val LocalControlLabelMode = staticCompositionLocalOf { ControlLabelMode.Adaptive }
val LocalAppIconFamily = staticCompositionLocalOf { AppIconFamily.Lucide }
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

/**
 * Why both schemes set the five container tokens, `surfaceBright`/`surfaceDim`, the selection/emphasis
 * containers (`secondaryContainer`, `tertiaryContainer`) and the inverse roles explicitly.
 *
 * A `ColorScheme` these were left out of does not go without them — it inherits Material's baseline,
 * and Material's baseline is lavender (light `surfaceContainer` `#F3EDF7` reads hue 276°). That hue
 * shows through in the two least-escapable places: [androidx.compose.material3.AlertDialogDefaults]
 * resolves `DialogTokens.ContainerColor` to `surfaceContainerHigh`, and
 * [androidx.compose.material3.BottomSheetDefaults] resolves the docked sheet to `surfaceContainerLow`,
 * so every dialog and every modal sheet would otherwise stand on a colour the theme never chose.
 * Setting the whole ramp here keeps the shell strictly neutral.
 *
 * **The ramp is pure greyscale (`r == g == b`) and each stop is luminance-matched to the value the
 * previous blue-grey ramp was hand-tuned to**, so the AA contrast guarantees carry over unchanged while
 * the hue is gone. This is the monochrome contract: light starts at `#FFFFFF`, dark at `#000000`, and
 * the neutral tiers give hierarchy without a tint.
 *
 * **`onSurfaceVariant` is the binding contrast constraint.** Light `#6B6B6B` needs a container no darker
 * than `surfaceVariant` / `surfaceContainerHighest` `#F1F1F1` to hold 4.5:1 (it measures 4.72:1 there,
 * and ≥ 5.3:1 on the whiter stops), so the usable light band is white down to `#F1F1F1` and no further.
 * Dark `#9A9A9A` clears 4.5:1 on every stop up to `surfaceContainerHighest` `#2E2E2E` (4.83:1). Both
 * terminals were checked against WCAG AA with the project contrast guardrail.
 *
 * **`surfaceContainerLow` must never equal `surface`.** [com.norfold.app.ui.NorfoldChrome]'s
 * `resolvedBackdrop` treats a backdrop equal to `surface` as the tonal-elevation case, so a container
 * stop colliding with `surface` would trip that branch. Light keeps `surfaceContainerLow` `#FBFBFB`
 * below white `surface`; dark keeps `surfaceContainerLowest` `#0E0E0E` below `surface` `#121212`, and
 * `surfaceContainerLow` `#161616` distinct from it, exactly as the previous ramp did.
 *
 * `surfaceBright` and `surfaceDim` are the band's own extremes rather than a step beyond it; neither has
 * a consumer in this app today, and they are set so a future one inherits this palette, not the baseline.
 *
 * The Flow canvas still overrides all five container stops in
 * [com.norfold.app.ui.editor.flow.FlowVisualTheme] with its own greyscale on its own secondary ink; that
 * is a documented content surface, and it clears AA on every stop too.
 */
private fun lightScheme(accent: Color) = lightColorScheme(
    primary = accent,
    onPrimary = onColorFor(accent),
    secondary = accent,
    onSecondary = onColorFor(accent),
    tertiary = accent,
    background = Color(0xFFFFFFFF),
    onBackground = LightInk,
    surface = Color.White,
    onSurface = LightInk,
    surfaceVariant = Color(0xFFF1F1F1),
    // Secondary ink, pure grey. Clears AA on every light surface it can land on: 5.33:1 on white
    // `surface`/`background`, and 4.72:1 on the darkest stop it is paired with, `#F1F1F1`.
    onSurfaceVariant = Color(0xFF6B6B6B),
    outline = Color(0xFFE6E6E6),
    outlineVariant = Color(0xFFF0F0F0),
    // The container ramp. Pure greyscale, luminance-matched to the previous ramp; band stops at `#F1F1F1`.
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBFBFB),
    surfaceContainer = Color(0xFFF7F7F7),
    surfaceContainerHigh = Color(0xFFF4F4F4),
    surfaceContainerHighest = Color(0xFFF1F1F1),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFF1F1F1),
    primaryContainer = accent.mix(Color.White, 0.88f),
    onPrimaryContainer = accent.mix(LightInk, 0.30f),
    // Selection/emphasis containers (`secondaryContainer`, `tertiaryContainer`) and the inverse
    // (snackbar) roles: de-hued to the luminance-matched grey of Material's baseline, so every contrast
    // ratio these carried is preserved exactly while the lavender/pink tint is gone. `secondaryContainer`
    // `#E2E2E2` == baseline `#E8DEF8`'s luminance (0.762); `tertiaryContainer` == baseline `#FFD8E4`'s
    // (0.760). The accessibility proof in ChromeGeometryTest that pins the popup-row selection to this
    // value stays valid verbatim, because WCAG contrast depends only on luminance.
    secondaryContainer = Color(0xFFE2E2E2),
    onSecondaryContainer = Color(0xFF1C1C1C),
    tertiaryContainer = Color(0xFFE2E2E2),
    onTertiaryContainer = Color(0xFF1C1C1C),
    inverseSurface = Color(0xFF303030),
    inverseOnSurface = Color(0xFFF1F1F1),
    inversePrimary = Color(0xFFC6C6C6),
    error = Color(0xFFEF4D5D),
)

private fun darkScheme(accent: Color): androidx.compose.material3.ColorScheme {
    // A dark/graphite accent would vanish on the near-black background, so lift it toward white.
    val surface = Color(0xFF121212)
    val brand = accent.mix(Color.White, 0.35f)
    return darkColorScheme(
        primary = brand,
        onPrimary = onColorFor(brand),
        secondary = brand,
        onSecondary = onColorFor(brand),
        tertiary = brand,
        background = Color(0xFF000000),
        onBackground = DarkInk,
        surface = surface,
        onSurface = DarkInk,
        surfaceVariant = Color(0xFF1E1E1E),
        onSurfaceVariant = Color(0xFF9A9A9A),
        // `#2B2B2B` (not `#2C2C2C`): on the pure-black background this scheme now ships, `#2C2C2C` measured
        // 1.5037:1 — a hair over the 1.5:1 ceiling the substrate relies on for `outline` to never read as a
        // visible boundary. One grey step down lands at 1.483:1 and restores the invariant.
        outline = Color(0xFF2B2B2B),
        outlineVariant = Color(0xFF222222),
        // The container ramp, pure greyscale. `Lowest` sits *below* `surface`, and no stop equals
        // `surface`, so the tonal-elevation branch in `resolvedBackdrop` is not tripped.
        surfaceContainerLowest = Color(0xFF0E0E0E),
        surfaceContainerLow = Color(0xFF161616),
        surfaceContainer = Color(0xFF1E1E1E),
        surfaceContainerHigh = Color(0xFF262626),
        surfaceContainerHighest = Color(0xFF2E2E2E),
        surfaceBright = Color(0xFF2E2E2E),
        surfaceDim = Color(0xFF000000),
        primaryContainer = accent.mix(surface, 0.72f),
        onPrimaryContainer = accent.mix(Color.White, 0.62f),
        // See the light scheme: de-hued selection/emphasis + inverse roles, luminance-matched to
        // Material's dark baseline. `secondaryContainer` `#474747` == baseline `#4A4458`'s luminance
        // (0.063); this is the value the dark editor status ribbon and every selected row now paint.
        secondaryContainer = Color(0xFF474747),
        onSecondaryContainer = Color(0xFFE2E2E2),
        tertiaryContainer = Color(0xFF474747),
        onTertiaryContainer = Color(0xFFE2E2E2),
        inverseSurface = Color(0xFFE2E2E2),
        inverseOnSurface = Color(0xFF303030),
        inversePrimary = Color(0xFF5E5E5E),
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
