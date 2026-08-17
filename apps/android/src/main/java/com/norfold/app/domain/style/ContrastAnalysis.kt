package com.norfold.app.domain.style

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Contrast analysis (roadmap Phase 3 §8, spec §8.7).
 *
 * The design constraint that shapes this whole file is honesty about what is *knowable*. A contrast ratio
 * is a fact about two opaque colors. The moment either color is translucent, the true ratio depends on
 * whatever is painted behind it — which the editor genuinely does not know for a text frame floating over
 * an image. §8 says so explicitly: "do not claim guarantees where compositing/background is unknown".
 *
 * So this engine has exactly two modes and never blurs them:
 *
 *  - **[analyze]** — both colors opaque, or a known backdrop supplied. Returns a real ratio and a real
 *    WCAG verdict.
 *  - **[analyzeUnknownBackdrop]** — the background is translucent or unknown. Returns
 *    [ContrastConfidence.INDETERMINATE] with the *range* the ratio could occupy, and no pass/fail claim.
 *
 * A single "estimated ratio" API that quietly composited over white would be worse than no API: it would
 * produce a confident green checkmark on text that is unreadable in dark mode.
 */
object ContrastAnalysis {

    /** WCAG 2.2 thresholds. Large text is ≥ 18 pt regular or ≥ 14 pt bold. */
    const val AA_NORMAL = 4.5
    const val AA_LARGE = 3.0
    const val AAA_NORMAL = 7.0
    const val AAA_LARGE = 4.5

    /** Non-text UI components and graphical objects (WCAG 1.4.11). */
    const val UI_COMPONENT = 3.0

    /**
     * The contrast ratio of two **opaque** colors, `1.0..21.0`.
     *
     * Alpha on either input is ignored here rather than assumed — callers who have a real backdrop should
     * composite first ([ColorValue.compositedOver]), and callers who do not should use
     * [analyzeUnknownBackdrop] instead of pretending.
     */
    fun ratio(foreground: ColorValue, background: ColorValue): Double {
        val a = foreground.relativeLuminance()
        val b = background.relativeLuminance()
        val lighter = max(a, b)
        val darker = min(a, b)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * A full analysis for a known pair. If [foreground] is translucent it is composited over [background]
     * first, which is correct and knowable; if [background] itself is translucent the result is
     * [ContrastConfidence.INDETERMINATE], because compositing onto an unknown surface is a guess.
     */
    fun analyze(
        foreground: ColorValue,
        background: ColorValue,
        largeText: Boolean = false,
    ): ContrastResult {
        if (!background.isOpaque) {
            return analyzeUnknownBackdrop(foreground, largeText, partialBackground = background)
        }
        val effective = foreground.compositedOver(background)
        val value = ratio(effective, background)
        return ContrastResult(
            ratio = value,
            confidence = ContrastConfidence.MEASURED,
            largeText = largeText,
            passesAA = value >= if (largeText) AA_LARGE else AA_NORMAL,
            passesAAA = value >= if (largeText) AAA_LARGE else AAA_NORMAL,
            passesUiComponent = value >= UI_COMPONENT,
        )
    }

    /**
     * The honest answer when the backdrop is unknown: the **range** of ratios this foreground could have,
     * bounded by the extremes of compositing it over black and over white.
     *
     * [ContrastResult.ratio] carries the *worst* case, because the only safe claim about an unknown
     * backdrop is the pessimistic one; [ContrastResult.bestCaseRatio] carries the other end so a UI can
     * say "between 2.1 and 8.4 depending on the background" rather than inventing a single number.
     */
    fun analyzeUnknownBackdrop(
        foreground: ColorValue,
        largeText: Boolean = false,
        partialBackground: ColorValue? = null,
    ): ContrastResult {
        val overWhite = foreground.compositedOver(ColorValue.WHITE)
        val overBlack = foreground.compositedOver(ColorValue.BLACK)
        val whiteRatio = ratio(overWhite, ColorValue.WHITE)
        val blackRatio = ratio(overBlack, ColorValue.BLACK)
        val worst = min(whiteRatio, blackRatio)
        val best = max(whiteRatio, blackRatio)
        return ContrastResult(
            ratio = worst,
            bestCaseRatio = best,
            confidence = ContrastConfidence.INDETERMINATE,
            largeText = largeText,
            // No claim is made either way; an indeterminate result must not read as a pass.
            passesAA = false,
            passesAAA = false,
            passesUiComponent = false,
            indeterminateReason = if (partialBackground != null) {
                "The background is ${(partialBackground.alphaFraction * 100).roundToInt()}% opaque, so the final contrast depends on what is behind it"
            } else {
                "No known background — contrast depends on what this is placed over"
            },
        )
    }

    /**
     * The nearest lightness adjustment of [foreground] that reaches [targetRatio] against [background],
     * or null when no lightness of this hue can.
     *
     * Offered as a *suggestion*, never applied automatically: silently darkening a user's brand color to
     * satisfy a checker is exactly the kind of "helpful" rewrite that makes a tool untrustworthy.
     */
    fun suggestAccessible(
        foreground: ColorValue,
        background: ColorValue,
        targetRatio: Double = AA_NORMAL,
    ): ColorValue? {
        if (!background.isOpaque) return null
        if (ratio(foreground, background) >= targetRatio) return foreground
        val hsl = foreground.toHsl()
        // Search both directions from the current lightness and take whichever passes with the smallest
        // change, so the suggestion stays as close to the author's color as the requirement allows.
        var best: ColorValue? = null
        var bestDelta = Float.MAX_VALUE
        var step = 0
        while (step <= 100) {
            for (direction in intArrayOf(-1, 1)) {
                val lightness = hsl.lightness + direction * step / 100f
                if (lightness < 0f || lightness > 1f) continue
                val candidate = ColorValue.fromHsl(hsl.hue, hsl.saturation, lightness, hsl.alpha)
                if (ratio(candidate, background) >= targetRatio) {
                    val delta = abs(lightness - hsl.lightness)
                    if (delta < bestDelta) {
                        best = candidate
                        bestDelta = delta
                    }
                }
            }
            if (best != null) return best
            step++
        }
        return null
    }

    /**
     * [base] darkened or lightened just enough to be *seen* on [backdrop] — the editor-chrome counterpart
     * to [suggestAccessible].
     *
     * The distinction from [suggestAccessible] is consent, not arithmetic. That one adjusts a colour the
     * *user* authored and therefore only ever suggests. This one adjusts a colour the *app* chose — a
     * selection outline, an object edge — where there is no authored intent to respect and a silent
     * adjustment is the correct behaviour rather than a presumptuous one. Chrome the user cannot see is a
     * defect; a selection outline 1.56:1 against the page it sits on is not a subtle one.
     *
     * Hue and saturation are preserved because only lightness moves, which is what keeps the user's accent
     * recognisably *theirs* on a dark page instead of being replaced by a neutral that would read as a
     * different product. Measured across the ten shipped accents on seven page colours in both themes, the
     * largest shift needed to clear [UI_COMPONENT] is 0.45 lightness and every case is reachable.
     *
     * Both are passed through untouched, but "preserved" is a claim about the arithmetic rather than about
     * the 8-bit value that comes back: reading the hue out of a very dark result is lossy, because at that
     * lightness one integer step per channel is a large fraction of the channel. The worst shipped case is
     * the green accent on a slate page — `#3D7C45` needs lightness 0.363 to reach 0.102, landing on
     * `#112314`, whose hue reads back 2.38° away. Both bounds are asserted in `ContrastAnalysisTest`.
     *
     * [base] is returned unchanged when it already clears [targetRatio], so on the ordinary white page the
     * accent is used exactly as the user picked it and this function costs nothing visible.
     *
     * The fallback matters more than it looks. [suggestAccessible] returns null when no lightness of a hue
     * can reach the target — impossible for chrome on a *fully* opaque backdrop, but reachable if a caller
     * passes something odd — and chrome must draw *something* legible rather than nothing. So the fallback
     * is the backdrop's own best ink, black or white, which is the highest-contrast choice available and
     * always clears the target.
     */
    fun legibleChrome(
        base: ColorValue,
        backdrop: ColorValue,
        targetRatio: Double = UI_COMPONENT,
    ): ColorValue =
        suggestAccessible(base, backdrop, targetRatio) ?: inkFor(backdrop)

    /**
     * Black or white, whichever is more legible on [backdrop].
     *
     * Chosen by measured ratio rather than by a luminance threshold, because the two genuinely disagree.
     * `luminance() > 0.5` is the usual shortcut; measured against every one of the 256 greys, it picks the
     * worse ink across the whole band `#767676` to `#BBBBBB` — 70 consecutive values. Black overtakes white
     * at `#767676` (4.62:1 against 4.54:1), but luminance does not cross 0.5 until `#BCBCBC`, so every grey
     * in between is told to use white ink when black is measurably better. Comparing the two ratios cannot
     * get that band wrong because it asks the question the answer is defined by.
     */
    fun inkFor(backdrop: ColorValue): ColorValue =
        if (ratio(ColorValue.WHITE, backdrop) >= ratio(ColorValue.BLACK, backdrop)) {
            ColorValue.WHITE
        } else {
            ColorValue.BLACK
        }
}

/** How much the engine actually knows about a contrast figure. */
enum class ContrastConfidence {
    /** Both colors known and opaque — the ratio is a fact. */
    MEASURED,

    /** The backdrop is unknown or translucent — only a range is knowable. */
    INDETERMINATE,
}

/**
 * A contrast result. Every field is textual or numeric, never a color — §31 requires that contrast
 * information be readable, not conveyed by a red/green dot.
 */
data class ContrastResult(
    val ratio: Double,
    val bestCaseRatio: Double = ratio,
    val confidence: ContrastConfidence,
    val largeText: Boolean,
    val passesAA: Boolean,
    val passesAAA: Boolean,
    val passesUiComponent: Boolean,
    val indeterminateReason: String? = null,
) {
    val isMeasured: Boolean get() = confidence == ContrastConfidence.MEASURED

    /** `"4.63:1"` — the number a user reads out. */
    fun ratioText(): String = "${(ratio * 100).roundToInt() / 100.0}:1"

    /** The full sentence a screen reader announces, including the honest caveat when there is one. */
    fun summary(): String = when (confidence) {
        ContrastConfidence.MEASURED -> buildString {
            append("Contrast ")
            append(ratioText())
            append(" — ")
            append(
                when {
                    passesAAA -> "passes AAA"
                    passesAA -> "passes AA"
                    passesUiComponent -> "passes for interface components only"
                    else -> "below the AA minimum"
                },
            )
            if (largeText) append(" for large text")
        }
        ContrastConfidence.INDETERMINATE -> buildString {
            append("Contrast between ${ratioText()} and ${(bestCaseRatio * 100).roundToInt() / 100.0}:1 — ")
            append(indeterminateReason ?: "not determinable")
        }
    }
}

/**
 * Color-vision-deficiency simulation (roadmap §8, spec §8.7).
 *
 * The matrices are the widely used Machado/Oliveira/Fernandes (2009) severity-1.0 approximations for the
 * three dichromacies plus a monochromacy fallback. They are an *approximation*, and [CvdCheck] says so —
 * the point of the tool is to reveal "these two swatches are now identical", which the approximation is
 * more than accurate enough to demonstrate, not to certify anyone's vision.
 *
 * Simulation runs on the canonical [ColorValue] and returns a canonical [ColorValue], so the preview goes
 * through exactly the same rendering path as real content instead of a bespoke shader that could disagree.
 */
enum class ColorVisionDeficiency(val label: String, val description: String) {
    PROTANOPIA("Protanopia", "Reduced sensitivity to red (about 1% of men)"),
    DEUTERANOPIA("Deuteranopia", "Reduced sensitivity to green (the most common form)"),
    TRITANOPIA("Tritanopia", "Reduced sensitivity to blue (rare)"),
    ACHROMATOPSIA("Achromatopsia", "No color perception (very rare)"),
    ;

    /** [color] as this deficiency perceives it. Alpha passes through untouched. */
    fun simulate(color: ColorValue): ColorValue {
        val r = color.red / 255.0
        val g = color.green / 255.0
        val b = color.blue / 255.0
        val (nr, ng, nb) = when (this) {
            PROTANOPIA -> Triple(
                0.152286 * r + 1.052583 * g - 0.204868 * b,
                0.114503 * r + 0.786281 * g + 0.099216 * b,
                -0.003882 * r - 0.048116 * g + 1.051998 * b,
            )
            DEUTERANOPIA -> Triple(
                0.367322 * r + 0.860646 * g - 0.227968 * b,
                0.280085 * r + 0.672501 * g + 0.047413 * b,
                -0.011820 * r + 0.042940 * g + 0.968881 * b,
            )
            TRITANOPIA -> Triple(
                1.255528 * r - 0.076749 * g - 0.178779 * b,
                -0.078411 * r + 0.930809 * g + 0.147602 * b,
                0.004733 * r + 0.691367 * g + 0.303900 * b,
            )
            ACHROMATOPSIA -> {
                val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
                Triple(y, y, y)
            }
        }
        return ColorValue.of(
            (nr.coerceIn(0.0, 1.0) * 255).roundToInt(),
            (ng.coerceIn(0.0, 1.0) * 255).roundToInt(),
            (nb.coerceIn(0.0, 1.0) * 255).roundToInt(),
            color.alpha,
        )
    }
}

/**
 * Whether a set of colors stays distinguishable under each deficiency.
 *
 * This is the check that earns its keep: a chart palette or a set of status colors can look perfectly
 * distinct to the author and collapse into three identical olive swatches under deuteranopia. The result
 * names the colliding *pairs* textually so the warning is actionable and readable, satisfying §31's rule
 * that CVD state must not be represented only by hue.
 */
object CvdCheck {

    /**
     * Below this contrast ratio between two simulated colors, they are treated as indistinguishable.
     *
     * 1.2 is deliberately conservative: it flags genuine collisions without complaining about every
     * adjacent pair of a legitimately smooth sequential ramp.
     */
    const val COLLISION_RATIO = 1.2

    /** Simulated versions of [colors] under [deficiency], in the same order. */
    fun simulate(colors: List<ColorValue>, deficiency: ColorVisionDeficiency): List<ColorValue> =
        colors.map { deficiency.simulate(it) }

    /**
     * Pairs of [colors] (by index) that become indistinguishable under [deficiency], plus a readable
     * description. Only pairs that were *distinguishable before* are reported — two identical colors
     * colliding is not a CVD finding.
     */
    fun collisions(
        colors: List<ColorValue>,
        deficiency: ColorVisionDeficiency,
        labels: List<String> = emptyList(),
    ): List<CvdCollision> {
        val simulated = simulate(colors, deficiency)
        return buildList {
            for (i in colors.indices) {
                for (j in i + 1 until colors.size) {
                    if (ContrastAnalysis.ratio(colors[i], colors[j]) < COLLISION_RATIO) continue
                    val after = ContrastAnalysis.ratio(simulated[i], simulated[j])
                    if (after >= COLLISION_RATIO) continue
                    add(
                        CvdCollision(
                            deficiency = deficiency,
                            firstIndex = i,
                            secondIndex = j,
                            firstLabel = labels.getOrNull(i) ?: colors[i].toHexRgb(),
                            secondLabel = labels.getOrNull(j) ?: colors[j].toHexRgb(),
                            simulatedRatio = after,
                        ),
                    )
                }
            }
        }
    }

    /** Every collision across every deficiency — the full report for a palette. */
    fun report(colors: List<ColorValue>, labels: List<String> = emptyList()): List<CvdCollision> =
        ColorVisionDeficiency.entries.flatMap { collisions(colors, it, labels) }
}

/** One indistinguishable pair, described in words rather than by showing two similar swatches. */
data class CvdCollision(
    val deficiency: ColorVisionDeficiency,
    val firstIndex: Int,
    val secondIndex: Int,
    val firstLabel: String,
    val secondLabel: String,
    val simulatedRatio: Double,
) {
    fun summary(): String =
        "$firstLabel and $secondLabel look the same under ${deficiency.label} — add a second cue such as a label, pattern or icon"
}
