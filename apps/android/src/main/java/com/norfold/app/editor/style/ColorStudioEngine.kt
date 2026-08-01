package com.norfold.app.editor.style

import com.norfold.app.domain.style.ColorDomain
import com.norfold.app.domain.style.ColorHarmony
import com.norfold.app.domain.style.ColorPaint
import com.norfold.app.domain.style.ColorRole
import com.norfold.app.domain.style.ColorTarget
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.domain.style.ColorVisionDeficiency
import com.norfold.app.domain.style.ContrastAnalysis
import com.norfold.app.domain.style.ContrastResult
import com.norfold.app.domain.style.CvdCheck
import com.norfold.app.domain.style.CvdCollision
import com.norfold.app.domain.style.DocumentToken
import com.norfold.app.domain.style.Gradient
import com.norfold.app.domain.style.GradientKind
import com.norfold.app.domain.style.HslColor
import com.norfold.app.domain.style.Stylesheet
import com.norfold.app.domain.style.TokenKind

/**
 * The Color Studio's state and behaviour, with no Compose dependency (roadmap Phase 3 §4, §6, §28, §29).
 *
 * This class is the answer to two of §40's named defects at once:
 *
 *  - *"tool state stored only in disposable remember"* — the studio's truth lives here, in an object the
 *    host holds across recompositions and form-factor changes, so rotating the device cannot lose the
 *    gradient stop the user was dragging;
 *  - *"HEX/RGB/HSL fields maintain independent drifting state"* — there is exactly one
 *    [ColorStudioState.color], and every field is a *view* of it. A field's editing buffer is a draft
 *    ([ColorStudioState.draft]) that either parses into the one canonical value or is discarded.
 *
 * The other structural decision is **preview vs commit**. A wheel drag or an alpha slide updates the
 * studio continuously and calls [ColorStudioHost.preview]; only [commit] produces a History transaction.
 * That is what makes "one gesture = one undo step" (§29) a property of the engine rather than a discipline
 * every gesture handler has to remember, and it is what makes [cancel] able to restore the exact
 * pre-adjustment value — it kept it.
 *
 * Pure JVM, not thread-safe; the editor loop serializes access.
 */
class ColorStudioEngine(
    private val host: ColorStudioHost,
    /** Bounded recent-colour memory, newest first. */
    private val recentLimit: Int = 12,
) {
    private var state: ColorStudioState = ColorStudioState.CLOSED

    /** Value at the start of the current continuous adjustment, for an exact [cancel]. */
    private var adjustmentBaseline: ColorPaint? = null

    private val recent = ArrayDeque<ColorValue>()

    fun state(): ColorStudioState = state

    val isOpen: Boolean get() = state.target != null

    /** True only while one preview-only adjustment owns an exact cancel baseline. */
    val hasActiveAdjustment: Boolean get() = adjustmentBaseline != null

    // ── Lifecycle ────────────────────────────────────────────────────────────────────────────────

    /**
     * Opens the studio on [target] with [initial].
     *
     * Re-opening on the *same* target preserves the whole tool state — selected tab, active stop, harmony
     * choice — because that is a user returning to work they were doing, not starting over (§28). A
     * different target resets the view selection but keeps recents, which are a user-history concept and
     * belong to the person, not the target.
     */
    fun open(target: ColorTarget, initial: ColorPaint, mixed: Boolean = false): ColorStudioState {
        val sameTarget = state.target?.let { it.role == target.role && it.targetIds == target.targetIds } == true
        state = if (sameTarget) {
            state.copy(target = target, paint = initial, draft = null, isMixed = mixed)
        } else {
            ColorStudioState(
                target = target,
                paint = initial,
                isMixed = mixed,
                view = defaultViewFor(target),
                activeStopId = (initial as? ColorPaint.Ramp)?.gradient?.stops?.firstOrNull()?.id,
            )
        }
        adjustmentBaseline = null
        return state
    }

    fun close() {
        state = ColorStudioState.CLOSED
        adjustmentBaseline = null
    }

    /**
     * Retargets the open studio onto a new property without closing it (§28, §5.6).
     *
     * The current colour is *not* carried over — that would silently paint the new target with the old
     * target's colour the instant the user selected something else. The tool's *view* state is carried,
     * because that is a preference about how the user likes to work.
     */
    fun retarget(target: ColorTarget, initial: ColorPaint): ColorStudioState {
        if (state.target == null) return open(target, initial)
        state = state.copy(
            target = target,
            paint = initial,
            draft = null,
            activeStopId = (initial as? ColorPaint.Ramp)?.gradient?.stops?.firstOrNull()?.id,
            view = if (state.view.isAvailableFor(target)) state.view else defaultViewFor(target),
        )
        adjustmentBaseline = null
        return state
    }

    private fun defaultViewFor(target: ColorTarget): ColorStudioView =
        if (target.isBoundToToken) ColorStudioView.TOKENS else ColorStudioView.SWATCHES

    // ── View selection (never touches the colour) ────────────────────────────────────────────────

    /**
     * Switches which tool the user is looking at.
     *
     * This is the method that must be *provably* non-destructive, because "switching representation must
     * NOT progressively alter the color" is §2's central invariant. It writes only [ColorStudioState.view]
     * and clears any half-typed draft — it cannot reach the colour at all.
     */
    fun setView(view: ColorStudioView): ColorStudioState {
        if (state.target?.let { view.isAvailableFor(it) } == false) return state
        state = state.copy(view = view, draft = null)
        return state
    }

    // ── Editing ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Sets the flat colour, previewing without committing.
     *
     * On the first call of a gesture the pre-gesture paint is captured, so a whole wheel drag — however
     * many hundreds of these calls it makes — has exactly one baseline and therefore exactly one
     * transaction and one exact cancel target.
     */
    fun previewColor(color: ColorValue): ColorStudioState {
        if (state.isMixed) return state
        return when (val paint = state.paint) {
            is ColorPaint.Flat -> {
                beginAdjustment()
                state = state.copy(paint = ColorPaint.Flat(color), draft = null)
                host.preview(requireTarget(), state.paint)
                state
            }
            is ColorPaint.Ramp -> {
                val stopId = state.activeStopId ?: paint.gradient.sortedStops.firstOrNull()?.id ?: return state
                previewGradient(paint.gradient.setStopColor(stopId, color))
            }
        }
    }

    /** HSL-side preview — one rounding, at [ColorValue.fromHsl]. */
    fun previewHsl(hsl: HslColor): ColorStudioState = previewColor(hsl.toColorValue())

    fun previewAlpha(alpha: Float): ColorStudioState = when (val paint = state.paint) {
        is ColorPaint.Flat -> previewColor(paint.color.withAlphaFraction(alpha))
        is ColorPaint.Ramp -> {
            val stopId = state.activeStopId
            if (stopId == null) state else previewGradient(paint.gradient.setStopOpacity(stopId, alpha))
        }
    }

    fun previewGradient(gradient: Gradient): ColorStudioState {
        if (state.isMixed) return state
        beginAdjustment()
        state = state.copy(paint = ColorPaint.Ramp(gradient.normalized()), draft = null)
        host.preview(requireTarget(), state.paint)
        return state
    }

    /**
     * Ends the current adjustment and commits **one** transaction.
     *
     * A commit with no adjustment in flight is a no-op rather than an empty transaction, so tapping a
     * swatch that is already selected does not occupy an undo step.
     */
    fun commitActiveAdjustment(label: String = "Change color"): Boolean {
        val target = state.target ?: return false
        // No adjustment in flight means nothing was edited: every editing path (preview, gradient toggle,
        // draft) captures a baseline first, so a null baseline here is a commit with no gesture behind it.
        // Committing anyway would write an "empty" transaction that occupies an undo step and undoes nothing.
        val baseline = adjustmentBaseline ?: return false
        if (baseline == state.paint) {
            adjustmentBaseline = null
            return false
        }
        val committed = host.commit(target, state.paint, label)
        adjustmentBaseline = null
        if (committed) {
            state.paint.colors().forEach(::rememberRecent)
        } else {
            // A refused canonical commit must not leave a preview painted with no way to cancel it.
            state = state.copy(paint = baseline, draft = null)
            host.preview(target, baseline)
        }
        return committed
    }

    /** Compatibility alias for pre-correction callers; new presentation code names the boundary explicitly. */
    fun commit(label: String = "Change color"): Boolean = commitActiveAdjustment(label)

    /**
     * Abandons the current adjustment, restoring the exact pre-adjustment paint (§29).
     *
     * "Exact" is why the baseline is a stored value rather than a re-derivation: re-deriving it from the
     * target would give back whatever the target *now* holds, which after a preview is the previewed value —
     * so cancel would confirm the change it was asked to discard.
     */
    fun cancelActiveAdjustment(): ColorStudioState {
        val baseline = adjustmentBaseline ?: return state
        adjustmentBaseline = null
        state = state.copy(paint = baseline, draft = null)
        state.target?.let { host.preview(it, baseline) }
        return state
    }

    /** Compatibility alias for pre-correction callers. */
    fun cancel(): ColorStudioState = cancelActiveAdjustment()

    /** Captures exactly one baseline for a pointer/slider gesture. Repeated starts are harmless. */
    fun beginAdjustment(): Boolean {
        if (state.target == null || state.isMixed || adjustmentBaseline != null) return false
        adjustmentBaseline = state.paint
        return true
    }

    // ── Text-field drafts ────────────────────────────────────────────────────────────────────────

    /**
     * Records what the user has typed into a numeric/hex field **without** applying it.
     *
     * A draft exists because `#1A2B` is a legitimate intermediate state of typing `#1A2B3C`, and applying
     * every keystroke would either reject valid typing or repeatedly recolour the document. Drafts are also
     * what stops the fields from drifting apart: only one can be drafting, and committing it goes through
     * the same single canonical parse as everything else.
     */
    fun draft(field: ColorField, text: String): ColorStudioState {
        if (state.isMixed && field != ColorField.HEX) return state
        state = state.copy(draft = ColorDraft(field, text))
        return state
    }

    /**
     * Applies the current draft if it parses, and reports the outcome.
     *
     * An unparseable draft is *discarded*, not clamped: the fields re-read from the canonical colour, so the
     * user sees their invalid entry replaced by the real value rather than by a number the app invented.
     */
    fun commitDraft(field: ColorField? = null): DraftOutcome {
        val draft = state.draft ?: return DraftOutcome.NOTHING_TO_COMMIT
        if (field != null && draft.field != field) return DraftOutcome.NOTHING_TO_COMMIT
        val parsed = draft.parse(state.color())
        state = state.copy(draft = null)
        if (parsed == null) return DraftOutcome.REJECTED
        if (state.isMixed) {
            if (draft.field != ColorField.HEX) return DraftOutcome.REJECTED
            return if (commitExplicitPaint(ColorPaint.Flat(parsed), "Set color")) {
                DraftOutcome.COMMITTED
            } else {
                DraftOutcome.UNCHANGED
            }
        }
        if (draft.field == ColorField.ALPHA) previewAlpha(parsed.alphaFraction) else previewColor(parsed)
        return if (commitActiveAdjustment("Set color")) DraftOutcome.COMMITTED else DraftOutcome.UNCHANGED
    }

    fun discardDraft(field: ColorField? = null): ColorStudioState {
        if (field != null && state.draft?.field != field) return state
        state = state.copy(draft = null)
        return state
    }

    /** Establishes one explicit common paint for a mixed target without inventing a preview baseline. */
    fun commitExplicitPaint(paint: ColorPaint, label: String): Boolean {
        val target = state.target ?: return false
        if (!state.isMixed) return false
        val committed = host.commit(target, paint, label)
        if (committed) {
            state = state.copy(
                paint = paint,
                isMixed = false,
                draft = null,
                activeStopId = (paint as? ColorPaint.Ramp)?.gradient?.stops?.firstOrNull()?.id,
            )
            paint.colors().forEach(::rememberRecent)
        }
        return committed
    }

    /** Mirrors a token action that already committed through StyleController; never adds History itself. */
    fun adoptCommittedPaint(paint: ColorPaint) {
        if (state.target == null) return
        state = state.copy(
            paint = paint,
            isMixed = false,
            draft = null,
            activeStopId = (paint as? ColorPaint.Ramp)?.gradient?.stops?.firstOrNull()?.id,
        )
    }

    // ── Gradient stop editing ────────────────────────────────────────────────────────────────────

    fun selectStop(stopId: String?): ColorStudioState {
        state = state.copy(activeStopId = stopId, draft = null)
        return state
    }

    fun gradient(): Gradient? = (state.paint as? ColorPaint.Ramp)?.gradient

    /**
     * Converts a flat colour into a gradient, or back.
     *
     * Flat → gradient seeds the ramp from the current colour rather than a default, so the switch reads as
     * "make this a gradient" instead of "replace this with a purple ramp". Gradient → flat takes the first
     * stop's colour, which is the one the user sees at the ramp's origin.
     */
    fun setGradientEnabled(enabled: Boolean): ColorStudioState {
        if (state.isMixed) return state
        val target = state.target ?: return state
        val next = when {
            enabled && state.paint is ColorPaint.Flat -> {
                if (!target.supportsGradient) return state
                val base = (state.paint as ColorPaint.Flat).color
                val kind = target.gradientKinds.firstOrNull() ?: GradientKind.LINEAR
                ColorPaint.Ramp(Gradient.of(base, base.withLightness(base.toHsl().lightness * 0.55f), kind))
            }
            !enabled && state.paint is ColorPaint.Ramp ->
                ColorPaint.Flat((state.paint as ColorPaint.Ramp).gradient.sortedStops.first().color)
            else -> return state
        }
        beginAdjustment()
        state = state.copy(
            paint = next,
            activeStopId = (next as? ColorPaint.Ramp)?.gradient?.stops?.firstOrNull()?.id,
        )
        host.preview(target, next)
        return state
    }

    fun addStopAt(position: Float): ColorStudioState {
        val gradient = gradient() ?: return state
        val next = gradient.addStopAt(position)
        val added = next.stops.lastOrNull()?.id
        previewGradient(next)
        return selectStop(added)
    }

    fun removeActiveStop(): ColorStudioState {
        val gradient = gradient() ?: return state
        val stopId = state.activeStopId ?: return state
        val next = gradient.removeStop(stopId)
        if (next.stopCount == gradient.stopCount) return state
        previewGradient(next)
        return selectStop(next.stops.firstOrNull()?.id)
    }

    fun setGradientKind(kind: GradientKind): ColorStudioState {
        val target = state.target ?: return state
        if (kind !in target.gradientKinds) return state
        val gradient = gradient() ?: return state
        return previewGradient(gradient.copy(kind = kind))
    }

    fun moveActiveStop(position: Float): ColorStudioState {
        val stopId = state.activeStopId ?: return state
        val gradient = gradient() ?: return state
        return previewGradient(gradient.moveStop(stopId, position))
    }

    fun setGradientAngle(angle: Float): ColorStudioState {
        val gradient = gradient() ?: return state
        return previewGradient(gradient.withAngle(angle))
    }

    fun reverseGradient(): ColorStudioState {
        val gradient = gradient() ?: return state
        return previewGradient(gradient.reversed())
    }

    fun reorderActiveStop(delta: Int): ColorStudioState {
        val stopId = state.activeStopId ?: return state
        val gradient = gradient() ?: return state
        val index = gradient.stops.indexOfFirst { it.id == stopId }
        if (index < 0) return state
        return previewGradient(gradient.reorderStop(stopId, index + delta))
    }

    // ── Palette sources (§6) ─────────────────────────────────────────────────────────────────────

    /**
     * Records a colour in the bounded recent list, newest first, duplicates collapsed.
     *
     * "Collapsed" means re-using a colour *moves* it to the front rather than adding a second entry — so a
     * user who keeps picking the same three colours sees three swatches, not twelve copies of one.
     */
    fun rememberRecent(color: ColorValue) {
        recent.removeAll { it == color }
        recent.addFirst(color)
        while (recent.size > recentLimit) recent.removeLast()
    }

    fun recentColors(): List<ColorValue> = recent.toList()

    /** Seeds recents from persisted interaction memory when the editor opens. */
    fun restoreRecent(colors: List<ColorValue>) {
        recent.clear()
        colors.take(recentLimit).forEach { recent.addLast(it) }
    }

    /**
     * The colours in this document, filtered to the domains a document may own (§3, §6).
     *
     * The filter is belt-and-braces: [com.norfold.app.domain.documentPalette] already only walks authored
     * content, so a chrome or collaborator colour cannot be in the list. Filtering again here means the
     * invariant holds even if a future caller passes a palette assembled some other way.
     */
    fun documentColors(palette: List<ColorValue>, domain: ColorDomain = ColorDomain.CONTENT): List<ColorValue> =
        if (domain.tokenizable) palette else emptyList()

    /** Colour tokens available to the current target, or empty when the target's domain cannot use them. */
    fun availableTokens(sheet: Stylesheet): List<DocumentToken> {
        val domain = state.target?.domain ?: return emptyList()
        if (!domain.tokenizable) return emptyList()
        return sheet.tokensOf(TokenKind.COLOR).filter { it.domain == domain }
    }

    // ── Analysis ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Contrast against [background], or the honest indeterminate answer when the backdrop is unknown.
     *
     * A null [background] is the common case for a floating text frame, and it is answered honestly rather
     * than by assuming white.
     */
    fun contrast(background: ColorValue?, largeText: Boolean = false): ContrastResult {
        val foreground = state.color()
        return if (background == null) {
            ContrastAnalysis.analyzeUnknownBackdrop(foreground, largeText)
        } else {
            ContrastAnalysis.analyze(foreground, background, largeText)
        }
    }

    fun harmony(harmony: ColorHarmony): List<ColorValue> = harmony.generate(state.color())

    fun cvdPreview(deficiency: ColorVisionDeficiency): ColorValue = deficiency.simulate(state.color())

    /** CVD collisions between the studio's colour and [others] — e.g. the rest of a palette. */
    fun cvdCollisions(others: List<ColorValue>): List<CvdCollision> =
        CvdCheck.report(listOf(state.color()) + others)

    private fun requireTarget(): ColorTarget =
        state.target ?: error("Color Studio has no target; open() first")
}

/**
 * The presentation-to-engine lifecycle seam shared by phone sheets and tablet panes in both editor hosts.
 * Compose reports pointer/slider boundaries here; the controller never owns a second baseline or History.
 */
class ColorStudioInteractionController(
    private val engine: ColorStudioEngine,
) {
    fun beginContinuousAdjustment(): Boolean = engine.beginAdjustment()

    fun finishContinuousAdjustment(label: String): Boolean = engine.commitActiveAdjustment(label)

    fun cancelContinuousAdjustment(): Boolean {
        val active = engine.hasActiveAdjustment
        engine.cancelActiveAdjustment()
        return active
    }

    /** A tap/button action is immediately its own undo unit while the Studio remains open. */
    fun oneShot(label: String, update: ColorStudioEngine.() -> Unit): Boolean {
        // Defensive separation: a missed gesture-end can never merge this tap into the older adjustment.
        engine.commitActiveAdjustment()
        engine.update()
        return engine.commitActiveAdjustment(label)
    }

    fun chooseExplicitColor(color: ColorValue, label: String = "Choose color"): Boolean =
        if (engine.state().isMixed) {
            engine.commitExplicitPaint(ColorPaint.Flat(color), label)
        } else {
            oneShot(label) { previewColor(color) }
        }

    /** Done resolves only the adjustment still in flight; earlier actions were already committed. */
    fun done(): Boolean = engine.commitActiveAdjustment()

    /** Cancel/Back abandons only the unfinished adjustment. */
    fun cancel(): Boolean = cancelContinuousAdjustment()

    /** Re-presentation uses exact cancel; it never commits a partial pointer gesture. */
    fun onFormFactorChanged(): Boolean = cancelContinuousAdjustment()
}

/**
 * What the host must provide so the studio can preview and commit without knowing about either host.
 *
 * Two methods, and the split is the whole contract: [preview] paints, [commit] makes history. A single
 * "apply" method would force every caller to decide when to make a transaction, which is exactly how you
 * end up with one undo record per slider pixel.
 */
interface ColorStudioHost {
    /** Show [paint] on [target] without any history entry. Called continuously during a gesture. */
    fun preview(target: ColorTarget, paint: ColorPaint)

    /** Commit [paint] as one transaction. Returns false when nothing changed or the target refused. */
    fun commit(target: ColorTarget, paint: ColorPaint, label: String): Boolean
}

/**
 * The studio's complete presentation-independent state (§4, §28).
 *
 * Every field here survives a form-factor change, because the whole object is carried forward — which is
 * what makes "the SAME Color Studio state must survive presentation changes" true by construction rather
 * than by a copy list someone has to keep in sync.
 */
data class ColorStudioState(
    val target: ColorTarget? = null,
    val paint: ColorPaint = ColorPaint.Flat(ColorValue.BLACK),
    val view: ColorStudioView = ColorStudioView.SWATCHES,
    /** Which gradient stop the tools are editing. Null for a flat paint. */
    val activeStopId: String? = null,
    /** In-progress text entry, if any. At most one field drafts at a time. */
    val draft: ColorDraft? = null,
    /** Selected targets disagree; relative controls cannot use [paint] as an invented common baseline. */
    val isMixed: Boolean = false,
) {
    val isGradient: Boolean get() = paint is ColorPaint.Ramp

    /**
     * The single colour every representation derives from.
     *
     * For a gradient this is the active stop's colour, so the wheel, the HEX field and the sliders all act
     * on the stop the user selected rather than on some aggregate of the ramp.
     */
    fun color(): ColorValue = when (val p = paint) {
        is ColorPaint.Flat -> p.color
        is ColorPaint.Ramp -> activeStopId?.let { p.gradient.stopById(it) }?.color
            ?: p.gradient.sortedStops.firstOrNull()?.color
            ?: ColorValue.BLACK
    }

    /** Text a field should display: the draft while typing, else the canonical value formatted. */
    fun fieldText(field: ColorField): String =
        draft?.takeIf { it.field == field }?.text ?: if (isMixed) "" else field.format(color())

    fun accessibleLabel(): String = buildString {
        append(target?.describe() ?: "Color")
        append(": ")
        append(if (isMixed) "Mixed" else paint.describe())
    }

    companion object {
        val CLOSED = ColorStudioState()
    }
}

/** Which tool of the studio is showing. Presentation decides *how*; this decides *what*. */
enum class ColorStudioView(val label: String) {
    CURRENT("Current"),
    SWATCHES("Swatches"),
    SPECTRUM("Spectrum"),
    WHEEL("Wheel"),
    SLIDERS("Sliders"),
    RECENT("Recent"),
    DOCUMENT("Document"),
    THEME("Theme"),
    TOKENS("Variables"),
    HARMONIES("Harmonies"),
    GRADIENT("Gradient"),
    CONTRAST("Contrast"),
    VISION("Color vision"),
    ;

    /** Whether this view makes sense for [target]. The gradient editor needs a gradient-capable target. */
    fun isAvailableFor(target: ColorTarget): Boolean = when (this) {
        GRADIENT -> target.supportsGradient
        TOKENS, DOCUMENT -> target.domain.tokenizable
        else -> true
    }
}

/** A numeric/text field of the studio. Each formats and parses the one canonical value. */
enum class ColorField(val label: String) {
    HEX("Hex"),
    RED("R"),
    GREEN("G"),
    BLUE("B"),
    ALPHA("Alpha"),
    HUE("H"),
    SATURATION("S"),
    LIGHTNESS("L"),
    VALUE("V"),
    ;

    /** How this field displays [color]. */
    fun format(color: ColorValue): String = when (this) {
        HEX -> color.toHex()
        RED -> color.red.toString()
        GREEN -> color.green.toString()
        BLUE -> color.blue.toString()
        ALPHA -> Math.round(color.alphaFraction * 100).toString()
        HUE -> Math.round(color.toHsl().hue).toString()
        SATURATION -> Math.round(color.toHsl().saturation * 100).toString()
        LIGHTNESS -> Math.round(color.toHsl().lightness * 100).toString()
        VALUE -> Math.round(color.toHsv().value * 100).toString()
    }
}

/** An in-progress field edit. [parse] is the single point where typed text becomes a canonical colour. */
data class ColorDraft(val field: ColorField, val text: String) {
    /**
     * The colour this draft means, applied on top of [current], or null when the text is not valid.
     *
     * Every branch goes through a `ColorValue` factory, so a typed HSL edit rounds exactly once — the same
     * guarantee a slider drag gets.
     */
    fun parse(current: ColorValue): ColorValue? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return when (field) {
            ColorField.HEX -> ColorValue.parseHex(trimmed)
            ColorField.RED -> ColorValue.parseRgb(trimmed, current.green.toString(), current.blue.toString())
                ?.withAlpha(current.alpha)
            ColorField.GREEN -> ColorValue.parseRgb(current.red.toString(), trimmed, current.blue.toString())
                ?.withAlpha(current.alpha)
            ColorField.BLUE -> ColorValue.parseRgb(current.red.toString(), current.green.toString(), trimmed)
                ?.withAlpha(current.alpha)
            ColorField.ALPHA -> percent(trimmed)?.let { current.withAlphaFraction(it) }
            ColorField.HUE -> degrees(trimmed)?.let { current.withHue(it) }
            ColorField.SATURATION -> percent(trimmed)?.let { current.withSaturation(it) }
            ColorField.LIGHTNESS -> percent(trimmed)?.let { current.withLightness(it) }
            ColorField.VALUE -> percent(trimmed)?.let { value -> current.withHsv(current.toHsv().copy(value = value)) }
        }
    }

    /** `0..100` as a fraction. ASCII-only, so a localized digit cannot be misread. */
    private fun percent(text: String): Float? {
        if (text.length > 3 || text.any { it !in '0'..'9' }) return null
        val value = text.toIntOrNull() ?: return null
        return if (value in 0..100) value / 100f else null
    }

    /** `0..360`, wrapping, since a hue is an angle. */
    private fun degrees(text: String): Float? {
        if (text.length > 3 || text.any { it !in '0'..'9' }) return null
        val value = text.toIntOrNull() ?: return null
        return ColorValue.normalizeHue(value.toFloat())
    }
}

/** Outcome of applying a typed field edit. */
enum class DraftOutcome {
    /** Parsed and committed as one transaction. */
    COMMITTED,

    /** Parsed, but the value was already current — no transaction. */
    UNCHANGED,

    /** Not a valid value; the draft was discarded and the field re-reads the canonical colour. */
    REJECTED,

    /** No draft was in progress. */
    NOTHING_TO_COMMIT,
}

/** Well-known target descriptors, so both hosts build identical targets for identical properties. */
object ColorTargets {
    fun flowTextColor(blockIds: List<String>) = ColorTarget(
        domain = ColorDomain.CONTENT,
        role = ColorRole.TEXT,
        targetIds = blockIds,
        host = "flow",
    )

    fun flowHighlight(blockIds: List<String>) = ColorTarget(
        domain = ColorDomain.CONTENT,
        role = ColorRole.HIGHLIGHT,
        targetIds = blockIds,
        host = "flow",
    )

    fun documentTextColor(objectIds: List<String>) = ColorTarget(
        domain = ColorDomain.CONTENT,
        role = ColorRole.TEXT,
        targetIds = objectIds,
        host = "document",
    )

    fun documentShapeFill(objectIds: List<String>) = ColorTarget(
        domain = ColorDomain.CONTENT,
        role = ColorRole.FILL,
        targetIds = objectIds,
        host = "document",
        supportsGradient = true,
        gradientKinds = setOf(GradientKind.LINEAR, GradientKind.RADIAL, GradientKind.CONIC),
    )

    fun documentShapeStroke(objectIds: List<String>) = ColorTarget(
        domain = ColorDomain.CONTENT,
        role = ColorRole.STROKE,
        targetIds = objectIds,
        host = "document",
    )

    fun documentPageBackground(pageIds: List<String>) = ColorTarget(
        domain = ColorDomain.CONTENT,
        role = ColorRole.PAGE_BACKGROUND,
        targetIds = pageIds,
        host = "document",
        supportsGradient = true,
        gradientKinds = setOf(GradientKind.LINEAR, GradientKind.RADIAL),
    )
}
