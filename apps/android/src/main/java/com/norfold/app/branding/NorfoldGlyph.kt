package com.norfold.app.branding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/*
 * The Norfold glyph, drawn natively from the exact vector trace of the brand raster
 * (potrace, 99.8% pixel match — same path as brand/norfold-glyph.svg). Rendering it
 * as a Path instead of a PNG means it tints for any theme, stays crisp at any
 * density, and can replay the brand intro: three round-cap capsule strokes sweep
 * the outline into view (left stem → diagonal → i-stem), then the green dot pops.
 * Geometry and timing come verbatim from brand/norfold-logo-animated.html — that
 * file is the canonical source; change it first, then mirror here.
 */

/** Brand colours from the traced logo. These are fixed brand ink, not theme tokens. */
val NorfoldGreen = Color(0xFF56AA5F)
val NorfoldInk = Color(0xFF1E2024)
val NorfoldInkOnDark = Color(0xFFF4F4F6)

/**
 * The overshoot from the brand dot's pop (cubic-bezier(.34,1.56,.64,1)). This is the
 * app's signature success easing — reuse it (via Motion.Pop) instead of inventing new springs.
 */
val NorfoldPopEasing: Easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

/** Stroke draw-in easing from the brand animation source. */
private val DrawEasing: Easing = CubicBezierEasing(0.55f, 0.06f, 0.28f, 1f)

/** Wordmark rise easing from the brand animation source. */
private val RiseEasing: Easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/** When the wordmark starts rising relative to the start of the glyph draw-in. */
const val NorfoldIntroWordmarkDelayMillis = 1340

/** Full intro length (wordmark rise included) — hold branded loaders at least this long. */
const val NorfoldIntroTotalMillis = 1840

private const val GlyphViewport = 1688f
private const val DotRadius = 131f
private val DotCenter = Offset(1159f, 436f)

/** Exact traced outline of the N glyph, parsed once on first draw. */
private val GlyphOutline: Path by lazy {
    PathParser().parsePathString(
        "M 370 293.6 c -1.4 0.2 -6.5 0.9 -11.5 1.5 -66.1 7.9 -130.5 49.3 -166.3 106.9 -17.5 28 -27.9 55.1 -34.3 89 l -2.2 11.5 -0.4 433.5 c -0.3 312.5 -0.1 436.3 0.7 443.5 2.8 25.5 10.5 52.3 21.5 75.1 27.6 57.5 75.9 100.4 134.6 119.7 54 17.8 111.3 16 161.4 -5 40.9 -17.2 78.9 -49.6 103.2 -88.1 16.6 -26.3 29.5 -62.3 34.3 -95.6 2 -13.8 2 -17.8 1.7 -183.1 -0.3 -189.2 0.2 -174.6 -7.6 -191 -6.5 -13.6 -5.4 -12.3 -64.5 -75.6 -47.3 -50.6 -55 -58.3 -63.1 -63.8 -25 -16.9 -52.4 -20.5 -85.5 -11.2 -5.5 1.6 -10 2.4 -10 1.8 0 -3.4 17.3 -21.3 26.5 -27.5 32.6 -21.7 71.2 -22.8 108.5 -3.1 13.8 7.4 22.5 14.4 35.7 29.2 6.5 7.2 15.9 17.4 20.8 22.7 5 5.2 16.4 17.6 25.4 27.5 9 9.9 22.1 24.1 29 31.6 6.9 7.4 24.3 26.3 38.6 41.9 14.3 15.7 28.8 31.4 32.1 35 3.4 3.6 13.5 14.7 22.5 24.6 9 10 24.7 27.3 34.8 38.5 43.3 48 57.7 64.2 100.8 113.4 9.7 11 22.4 25.5 28.4 32.1 6 6.7 12.6 14.2 14.7 16.7 2 2.4 19.5 22.3 38.7 44.2 19.3 21.9 38.4 43.6 42.5 48.4 4.1 4.7 19.8 22.7 35 40 15.1 17.3 29.1 33.3 31 35.5 1.9 2.3 11.4 13.1 21 24 9.6 11 20.4 23.3 23.9 27.5 10.1 12.2 14 15.9 23.6 22.6 19 13.3 37.4 20.6 59.4 23.4 67.2 8.7 126.8 -34.1 142 -101.9 4.3 -19.4 4.3 -16.3 4.2 -323 -0.1 -260.6 -0.3 -295.7 -1.7 -305 -6.3 -42.8 -20.9 -71.2 -51.7 -100.6 -20.9 -20 -46.6 -32.9 -78.7 -39.5 -13.4 -2.8 -42.4 -3.3 -56.5 -1 -33.5 5.4 -62.5 18.5 -84.3 38 -18.4 16.6 -27.6 31.8 -34.5 57.4 l -2.2 8.2 -0.2 278.2 c -0.2 173.9 -0.7 278.3 -1.3 278.3 -0.5 -0 -3.4 -3.3 -6.3 -7.3 -3 -3.9 -9.2 -11.3 -13.7 -16.2 -15.5 -16.8 -22 -25.2 -24.5 -31.4 l -2.5 -6.1 0 -208.7 c 0 -128.5 -0.4 -212.4 -1 -218.1 -2 -19.1 -6.2 -33.9 -14 -49.7 -6.3 -12.7 -13.5 -22.2 -28.4 -37.7 -6.6 -6.8 -19.2 -19.7 -28 -28.8 -8.9 -9.1 -21.7 -22.4 -28.6 -29.5 -22.1 -23 -39.1 -40.6 -57 -59 -9.6 -9.9 -21.1 -21.8 -25.4 -26.5 -4.4 -4.7 -15.4 -16.2 -24.4 -25.5 -9.1 -9.4 -23.1 -23.8 -31.1 -32 -8 -8.3 -22.2 -22.9 -31.6 -32.5 -18.8 -19.4 -40.2 -41.4 -63 -65 -30.3 -31.4 -61.2 -62.4 -68 -68.3 -42.1 -36.4 -88.1 -56.9 -142 -63.2 -11.6 -1.3 -43.4 -1.9 -50.5 -0.9 Z"
    ).toPath()
}

/**
 * Reveal capsules from the brand source, verified to cover 100% of the glyph's pixels.
 * Because they are straight lines, animating the sweep is just lerping the endpoint.
 */
private class RevealStroke(val start: Offset, val end: Offset, val width: Float)

private val RevealStrokes = listOf(
    RevealStroke(Offset(384f, 540f), Offset(384f, 1340f), 500f),   // left stem
    RevealStroke(Offset(400f, 480f), Offset(1150f, 1290f), 460f),  // diagonal
    RevealStroke(Offset(1165f, 810f), Offset(1178f, 1340f), 360f), // i-stem
)

/**
 * Draws the glyph at the current [DrawScope] size. [stem]/[diag]/[iStem] are 0..1 sweep
 * progress for the reveal capsules; [dot] scales the green dot (overshoot above 1 is fine).
 */
fun DrawScope.drawNorfoldGlyph(
    ink: Color,
    stem: Float = 1f,
    diag: Float = 1f,
    iStem: Float = 1f,
    dot: Float = 1f,
) {
    val factor = size.minDimension / GlyphViewport
    val dx = (size.width - GlyphViewport * factor) / 2f
    val dy = (size.height - GlyphViewport * factor) / 2f
    withTransform({
        translate(dx, dy)
        scale(factor, factor, pivot = Offset.Zero)
    }) {
        val progress = floatArrayOf(stem, diag, iStem)
        when {
            progress.all { it >= 1f } -> drawPath(GlyphOutline, ink)
            progress.any { it > 0f } -> {
                // Layered reveal, same trick as the HTML mask: draw the full glyph into a
                // layer, then composite the capsule strokes over it with DstIn so only the
                // swept area survives. Two layers because DstIn draws don't union.
                val canvas = drawContext.canvas
                val bounds = Rect(0f, 0f, GlyphViewport, GlyphViewport)
                canvas.saveLayer(bounds, Paint())
                drawPath(GlyphOutline, ink)
                canvas.saveLayer(bounds, Paint().apply { blendMode = BlendMode.DstIn })
                RevealStrokes.forEachIndexed { index, s ->
                    val p = progress[index].coerceIn(0f, 1f)
                    if (p > 0f) drawLine(
                        color = Color.White,
                        start = s.start,
                        end = lerp(s.start, s.end, p),
                        strokeWidth = s.width,
                        cap = StrokeCap.Round,
                    )
                }
                canvas.restore()
                canvas.restore()
            }
        }
        if (dot > 0f) drawCircle(NorfoldGreen, radius = DotRadius * dot, center = DotCenter)
    }
}

/** The glyph at rest — replaces the old PNG rasters everywhere a static mark is needed. */
@Composable
fun NorfoldGlyphMark(size: Dp, ink: Color = NorfoldInk, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawNorfoldGlyph(ink) }
}

/**
 * The brand intro: stem, diagonal and i-stem sweep in, then the dot pops. Timing is
 * verbatim from the animation source. Plays once per composition; [reduceMotion]
 * (or a restart with it set) renders the finished glyph immediately.
 */
@Composable
fun NorfoldGlyphDrawIn(
    size: Dp,
    ink: Color = NorfoldInk,
    reduceMotion: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val stem = remember { Animatable(0f) }
    val diag = remember { Animatable(0f) }
    val iStem = remember { Animatable(0f) }
    val dot = remember { Animatable(0f) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) {
            listOf(stem, diag, iStem, dot).forEach { it.snapTo(1f) }
        } else {
            launch { stem.animateTo(1f, tween(550, easing = DrawEasing)) }
            launch { diag.animateTo(1f, tween(620, delayMillis = 280, easing = DrawEasing)) }
            launch { iStem.animateTo(1f, tween(400, delayMillis = 920, easing = DrawEasing)) }
            launch { dot.animateTo(1f, tween(420, delayMillis = 1200, easing = NorfoldPopEasing)) }
        }
    }
    Canvas(modifier.size(size)) {
        drawNorfoldGlyph(ink, stem.value, diag.value, iStem.value, dot.value)
    }
}

/** The brand wordmark — "Norfold" with the green full stop. */
@Composable
fun NorfoldWordmarkText(fontSize: TextUnit, color: Color, modifier: Modifier = Modifier) {
    Text(
        buildAnnotatedString {
            append("Norfold")
            withStyle(SpanStyle(color = NorfoldGreen)) { append(".") }
        },
        fontSize = fontSize,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = fontSize * -0.02f,
        color = color,
        modifier = modifier,
    )
}

/**
 * The wordmark's rise from the intro (fade + 10dp lift), timed to land right after the
 * dot pops. Attach to whatever should arrive with the wordmark.
 */
@Composable
fun Modifier.norfoldRiseIn(
    delayMillis: Int = NorfoldIntroWordmarkDelayMillis,
    reduceMotion: Boolean = false,
): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) progress.snapTo(1f)
        else progress.animateTo(1f, tween(500, delayMillis = delayMillis, easing = RiseEasing))
    }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 10.dp.toPx()
    }
}
