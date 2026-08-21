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

/**
 * Why both schemes set the five container tokens, `surfaceBright` and `surfaceDim` explicitly.
 *
 * A `ColorScheme` these were left out of does not go without them — it inherits Material's baseline, and
 * Material's baseline is lavender. Light `surfaceContainer` `#F3EDF7` reads hue 276°; Norfold's own light
 * surfaces sit at 228-230°. Dark `surfaceContainerHigh` `#2B2930` reads 257° at 7.9% saturation against a
 * `surface` of 222° at 37.8%. That is not a near-miss, it is a different palette showing through, and it
 * shows through in the two places it is least escapable: [androidx.compose.material3.AlertDialogDefaults]
 * resolves `DialogTokens.ContainerColor` to `surfaceContainerHigh` and
 * [androidx.compose.material3.BottomSheetDefaults] resolves `SheetBottomTokens.DockedContainerColor` to
 * `surfaceContainerLow`, so every dialog and every modal sheet in the app stood on a colour the theme never
 * chose — and published it to its content as `LocalChromeContainer`.
 *
 * **The light ramp is short, and that is a constraint rather than a preference.** `onSurfaceVariant` is
 * `#6C6D84`, which needs a container at luminance ≥ 0.8856 to hold 4.5:1. `surfaceVariant` is at 0.8892 —
 * already the darkest light surface in the scheme that clears AA for secondary text. So the usable light band
 * is white down to `surfaceVariant` and no further: a span of 1.119:1 where Material spans 1.296:1. Material
 * has the room because its own `onSurfaceVariant` is `#49454F`, which measures 9.34:1 on white against this
 * palette's 5.05:1; a darker secondary ink buys a longer ramp, and Norfold's ink is the lighter one.
 *
 * This is also the defect the ramp fixes rather than merely a hue correction. `DialogTokens` pairs
 * `ContainerColor = SurfaceContainerHigh` with `SupportingTextColor = OnSurfaceVariant`, and baseline
 * `#ECE6F0` sits *below* that 0.8856 bound: a light dialog's supporting text measured **4.12:1** — a real
 * AA failure on the most-used container in the app. On the value below it measures 4.64:1. Every one of the
 * ten stops clears 4.5:1 on its scheme's secondary ink, the two terminals deliberately by the smallest
 * margin the 8-bit grid allows (light 4.5143, dark 4.5014).
 *
 * **Spacing is even in contrast, not in luminance.** Each neighbouring pair differs by a constant ratio —
 * 1.026-1.034 light, 1.123 dark — because a ramp spaced evenly in luminance bunches its light end into
 * steps of one or two bits per channel, which is banding rather than hierarchy.
 *
 * **Hue is held by channel signature, not by an interpolation.** Norfold's light surfaces are all
 * `(r, r+1, r+5..6)`; the dark ones hold `g/r ≈ 1.29-1.43` and `b/r ≈ 1.9-2.6`. The stops were chosen from
 * the set of colours matching those signatures, because a colour picked purely by luminance along a line can
 * land on `r == g` and read as hue 240° — a violet-blue, which is the thing this replaces.
 *
 * `surfaceBright` and `surfaceDim` are the band's own extremes rather than a step beyond it: the light end is
 * already pure white and the dark end is already at the AA ceiling, so there is nothing outside the ramp to
 * extend into. Neither token has a consumer in this app or in Material's own components; they are set so a
 * future one inherits this palette instead of the baseline.
 *
 * The Flow canvas overrides all five container stops in
 * [com.norfold.app.ui.editor.flow.FlowVisualTheme] with a deliberate greyscale, on its own secondary ink, and
 * that ramp clears AA on every stop too. It is a documented exception, not a straggler — and because that
 * wrapper encloses the whole canvas from `FlowHostScreen`, the two `surfaceContainer` reads in
 * `FlowBlockContent` resolve to Flow's grey `#F7F7F5`, never to the value below.
 *
 * **One near-collision, deliberately kept.** `surfaceContainer` `#F7F8FD` sits one bit of blue from
 * `background` `#F7F8FC` — 1.0006:1, which no eye resolves. Nothing in the app draws them adjacently: outside
 * Flow the token has three readers, all in `ImageFocusSheet`, and that sheet stands on a modal scrim rather
 * than on the page. Pulling the stop further from the page would have to come out of the band above it, and the
 * band is the constrained resource here — the alternative is a `surfaceContainerLow` that collides with
 * `surface` instead, which is worse, because that one *would* trip the tonal-elevation branch in
 * `resolvedBackdrop`.
 */
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
    // The container ramp. Derived above — including why the light band stops at `surfaceVariant`.
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBFCFF),
    surfaceContainer = Color(0xFFF7F8FD),
    surfaceContainerHigh = Color(0xFFF4F5FA),
    surfaceContainerHighest = Color(0xFFF1F2F6),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFF1F2F6),
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
        // The container ramp, derived in the note on [lightScheme]. `Lowest` sits *below* `surface` here at
        // Material's own 1.039 surface:Lowest ratio — the light scheme cannot do that, because nothing is
        // lighter than white, and a stop equal to `surface` would trip the tonal-elevation branch in
        // `resolvedBackdrop`.
        surfaceContainerLowest = Color(0xFF0B0E17),
        surfaceContainerLow = Color(0xFF151B2B),
        surfaceContainer = Color(0xFF1B253B),
        surfaceContainerHigh = Color(0xFF232E43),
        surfaceContainerHighest = Color(0xFF283650),
        surfaceBright = Color(0xFF283650),
        surfaceDim = Color(0xFF070A12),
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
