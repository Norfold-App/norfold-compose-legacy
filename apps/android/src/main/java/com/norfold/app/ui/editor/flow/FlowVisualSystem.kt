package com.norfold.app.ui.editor.flow

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import com.norfold.app.domain.EditorFontFamily
import com.norfold.app.editor.style.CalloutTone
import com.norfold.app.editor.style.ContentRole
import com.norfold.app.editor.style.ReadingPreference
import com.norfold.app.ui.LocalReadingPreference

/**
 * Flow's canvas: a neutral page whose only colour is the ink the user put on it.
 *
 * The editor used to tint itself ivory. That reads as a theme rather than as paper, and it fights every
 * colour a document contains — a warm background shifts the hue of a highlight, a chart palette and an
 * image alike. So the page is white (or near-black), and the app's accent appears only where the user is
 * *doing* something: a caret, a selection, focus, the command rail's highlight. Colour on this surface
 * should belong to the content.
 *
 * This is a deliberate exception to the rule that surfaces derive their colours from the app theme. Every
 * other screen follows the accent; the writing canvas does not, because a document is not chrome.
 */
@Composable
internal fun FlowVisualTheme(content: @Composable () -> Unit) {
    val outer = MaterialTheme.colorScheme
    val dark = outer.background.luminance() < 0.35f
    val colors = outer.copy(
        background = if (dark) Color(0xFF191919) else Color(0xFFFFFFFF),
        surface = if (dark) Color(0xFF191919) else Color(0xFFFFFFFF),
        surfaceVariant = if (dark) Color(0xFF262626) else Color(0xFFF7F7F5),
        surfaceContainerLowest = if (dark) Color(0xFF141414) else Color(0xFFFFFFFF),
        surfaceContainerLow = if (dark) Color(0xFF1D1D1D) else Color(0xFFFBFBFA),
        surfaceContainer = if (dark) Color(0xFF222222) else Color(0xFFF7F7F5),
        surfaceContainerHigh = if (dark) Color(0xFF2A2A2A) else Color(0xFFF1F1EF),
        surfaceContainerHighest = if (dark) Color(0xFF323232) else Color(0xFFEAEAE8),
        outline = if (dark) Color(0xFF3F3F3F) else Color(0xFFDFDFDC),
        outlineVariant = if (dark) Color(0xFF2F2F2F) else Color(0xFFE9E9E7),
        onBackground = if (dark) Color(0xFFE9E9E7) else Color(0xFF37352F),
        onSurface = if (dark) Color(0xFFE9E9E7) else Color(0xFF37352F),
        onSurfaceVariant = if (dark) Color(0xFF9B9B9B) else Color(0xFF787774),
    )

    // `primary` is deliberately *not* overridden: it arrives from the outer scheme, which is how the user's
    // accent reaches the caret, the selection and the rail highlight while touching nothing else.

    // Typography is no longer overridden here. Chrome inside the editor — toolbars, menus, the command
    // rail, cards, placeholders — reads the app typography like every other screen, and only block
    // renderers read [FlowContent.type]. Baking a 1.2x multiplier into MaterialTheme.typography made the
    // editor's *furniture* grow with its prose, which is the failure this split exists to end.
    val reading = LocalReadingPreference.current
    val appTypography = MaterialTheme.typography
    val flowType = remember(reading, appTypography) { contentTypography(appTypography, reading) }

    // Only the colour scheme is replaced. Typography and shapes fall through to the app theme by omission,
    // which is the whole point of the split: chrome inside the editor is chrome.
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(
            LocalFlowContentTypography provides flowType,
            content = content,
        )
    }
}

/**
 * The typography of the document itself, keyed by Material role so the call sites read unchanged.
 *
 * A full [Typography] rather than a bag of loose sizes: `FlowBlockContent` already asks for `bodyLarge` and
 * `headlineMedium`, and keeping those names means the split is a one-word change per call site instead of a
 * rewrite that invites a mistake in every block renderer.
 *
 * Default is a plain [Typography] — 1.0x Material defaults — so a composable that reads this outside the
 * editor is merely *ordinary*, not broken. The alternative, throwing on a missing provider, would turn a
 * forgotten opt-in into a crash in a block renderer.
 */
internal val LocalFlowContentTypography = staticCompositionLocalOf { Typography() }

/**
 * What a block renderer reads instead of `MaterialTheme`.
 *
 * Two members, both content-only: [type] for document text and [measure] for how wide a line of it may get.
 * Anything that is not the document — a toolbar, a card, a tab strip, a placeholder — keeps reading
 * `MaterialTheme` directly.
 */
internal object FlowContent {
    val type: Typography
        @Composable @ReadOnlyComposable get() = LocalFlowContentTypography.current

    /**
     * The measure cap in dp, converted from sp at the current density.
     *
     * Going through sp is what makes the cap hold a character *count* rather than a physical width: a
     * reader at a 1.3x platform font scale gets a proportionally wider column and the same 70 characters,
     * where a fixed dp cap would have given them 54.
     */
    val measure: Dp
        @Composable @ReadOnlyComposable get() = with(LocalDensity.current) {
            LocalReadingPreference.current.measureSp.sp.toDp()
        }

    /**
     * The width of a list's marker column.
     *
     * Scaled with the type rather than fixed at 32 dp: a numbered list at 2.0x needs room for `10.` in
     * 36 sp digits, and a fixed gutter clips it. Clamped at both ends so a nested list stays aligned
     * instead of marching across the page.
     */
    val gutter: Dp
        @Composable @ReadOnlyComposable get() = with(LocalDensity.current) {
            (LocalReadingPreference.current.sizeSp(ContentRole.BODY) * 1.8f).sp.toDp().coerceIn(28.dp, 64.dp)
        }
}

/**
 * Builds the content typography from the app typography and the reading preference.
 *
 * Font *families* come from the document face. `Sans` — the shipped value — means "keep the app's font", and
 * in that one case the display roles keep a serif face: a serif H1 over a sans paragraph is the default
 * pairing, and it is a typographic decision rather than a colour one. Choosing an explicit document face
 * replaces that pairing outright, because a reader who asked for a monospace document did not ask for
 * monospace prose under serif headings.
 */
private fun contentTypography(base: Typography, reading: ReadingPreference): Typography {
    val appFamily = base.bodyLarge.fontFamily
    val chosen = when (reading.face) {
        EditorFontFamily.Sans -> null
        EditorFontFamily.Serif -> FontFamily.Serif
        EditorFontFamily.Mono -> FontFamily.Monospace
    }
    val bodyFamily = chosen ?: appFamily
    val displayFamily = chosen ?: FontFamily.Serif

    fun style(source: TextStyle, role: ContentRole, family: FontFamily?, weight: FontWeight?) = source.copy(
        fontFamily = family,
        fontWeight = weight ?: source.fontWeight,
        fontSize = reading.sizeSp(role).sp,
        lineHeight = reading.lineHeightSp(role).sp,
    )

    return base.copy(
        // Headings. H1 and H2 carry the display face; from H3 down the hierarchy is weight and size,
        // because a serif at paragraph size next to sans prose reads as an accident rather than a level.
        headlineLarge = style(base.headlineLarge, ContentRole.HEADING_1, displayFamily, FontWeight.Medium),
        headlineMedium = style(base.headlineMedium, ContentRole.HEADING_2, displayFamily, FontWeight.SemiBold),
        headlineSmall = style(base.headlineSmall, ContentRole.HEADING_3, bodyFamily, FontWeight.SemiBold),
        titleLarge = style(base.titleLarge, ContentRole.HEADING_4, bodyFamily, FontWeight.SemiBold),
        titleMedium = style(base.titleMedium, ContentRole.HEADING_5, bodyFamily, FontWeight.SemiBold),
        titleSmall = style(base.titleSmall, ContentRole.HEADING_6, bodyFamily, FontWeight.SemiBold),
        // Prose. bodyLarge is the paragraph — the size everything else is a ratio of.
        bodyLarge = style(base.bodyLarge, ContentRole.BODY, bodyFamily, null),
        bodyMedium = style(base.bodyMedium, ContentRole.SECONDARY, bodyFamily, null),
        bodySmall = style(base.bodySmall, ContentRole.SMALL, bodyFamily, null),
        // Labels, for content that is a label *inside* a block: a table header, a list marker, a caption.
        labelLarge = style(base.labelLarge, ContentRole.SECONDARY, bodyFamily, FontWeight.SemiBold),
        labelMedium = style(base.labelMedium, ContentRole.SMALL, bodyFamily, FontWeight.Medium),
        labelSmall = style(base.labelSmall, ContentRole.SMALL, bodyFamily, FontWeight.Medium),
    )
}

/**
 * A callout tone resolved for the page it is drawn on.
 *
 * Three colours rather than one because a tone has to survive on both pages: [tint] is the surface, [edge]
 * the hairline that keeps a pale tint from dissolving into white, and [ink] belongs to the tone's *icon* — not
 * to the text. The text inside a callout keeps the page's ink, which is what lets a colour the user applied to
 * a word keep working instead of being overruled by the container it happens to sit in.
 */
internal data class FlowTonePalette(val tint: Color, val ink: Color, val edge: Color)

/**
 * The palette for [tone] on the current canvas.
 *
 * Light/dark is decided from the canvas background rather than from `isSystemInDarkTheme()`, because
 * [FlowVisualTheme] is the thing that chose the page colour and the tint has to match *that* — the same
 * luminance test it used, so the two cannot disagree.
 */
@Composable
@ReadOnlyComposable
internal fun flowTonePalette(tone: CalloutTone): FlowTonePalette {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.35f
    val tint = Color(if (dark) tone.darkTint else tone.lightTint)
    val ink = Color(if (dark) tone.darkInk else tone.lightInk)
    return FlowTonePalette(tint = tint, ink = ink, edge = lerp(tint, ink, 0.20f))
}

/** Monospace content — code, math source, a rendered-source preview — at the reading scale. */
internal val FlowContentMono: TextStyle
    @Composable @ReadOnlyComposable get() {
        val reading = LocalReadingPreference.current
        return FlowContent.type.bodyLarge.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = reading.sizeSp(ContentRole.CODE).sp,
            lineHeight = reading.lineHeightSp(ContentRole.CODE).sp,
        )
    }
