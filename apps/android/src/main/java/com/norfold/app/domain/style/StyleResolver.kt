package com.norfold.app.domain.style

/**
 * Resolves style references + local overrides into presentation, and reports **where each value came
 * from** (roadmap Phase 3 §10–§11, spec §10.2–§10.3).
 *
 * Two jobs, and the second is the one that is usually missing:
 *
 *  1. **resolve** — walk override → style → base chain → default and produce the value to render;
 *  2. **explain** — say whether that value was inherited, locally overridden, or mixed across a selection.
 *
 * Without (2), the inspector cannot distinguish "this paragraph is 11 pt because Body says so" from "this
 * paragraph is 11 pt because someone typed 11", and every control has to guess. Guessing is what makes
 * "clear override" either a no-op or a surprise, and what makes a multi-selection silently adopt the first
 * item's value — the two defects §11 and §40 name.
 *
 * Pure JVM: resolution is arithmetic over data, so the whole cascade is unit-testable without Compose.
 */
object StyleResolver {

    /** Cycle guard depth. A `basedOn` chain deeper than this is malformed data, not a design. */
    private const val MAX_CHAIN = 16

    /**
     * The style chain for [styleId], nearest first: the style itself, then its base, and so on.
     *
     * Cycles are *broken* rather than trusted absent: persisted data can contain a cycle (a hand-edited
     * file, a bad merge), and a resolver that recursed naively would hang the editor on open. Breaking at
     * the first repeat means such a document still renders, using the part of the chain that is sound.
     */
    fun chainOf(sheet: Stylesheet, styleId: String?): List<StyleDefinition> {
        val chain = mutableListOf<StyleDefinition>()
        val seen = mutableSetOf<String>()
        var current = sheet.style(styleId)
        while (current != null && seen.add(current.id) && chain.size < MAX_CHAIN) {
            chain += current
            current = sheet.style(current.basedOn)
        }
        return chain
    }

    /**
     * Where a resolved property value came from.
     *
     * [ValueOrigin.LOCAL] and [ValueOrigin.INHERITED] must be distinguishable in the UI — §40 lists
     * "local override is visually indistinguishable from inheritance" as a defect to fix, and this is the
     * datum that makes fixing it possible.
     */
    fun originOf(
        sheet: Stylesheet,
        assignment: StyleAssignment,
        property: StyleProperty,
    ): ValueOrigin = when {
        assignment.overrides(property) -> ValueOrigin.LOCAL
        chainOf(sheet, assignment.styleId).any { it.asserts(property) } -> ValueOrigin.INHERITED
        else -> ValueOrigin.DEFAULT
    }

    /**
     * The raw [StyleValue] for [property], before token resolution — override first, then the chain.
     *
     * Returning the *unresolved* value matters: an inspector showing a token-bound colour should say
     * "Accent", not `#6F36FF`, so the user can see the binding rather than a value that mysteriously
     * changes when someone edits the token.
     */
    fun rawValue(
        sheet: Stylesheet,
        assignment: StyleAssignment,
        property: StyleProperty,
    ): StyleValue? {
        assignment.overrides[property.key]?.let { return it }
        for (definition in chainOf(sheet, assignment.styleId)) {
            definition.get(property)?.let { return it }
        }
        return null
    }

    /** What a cascade found for a property: nothing, a value the app shipped, or a value a person chose. */
    sealed interface Authorship {
        /** Nothing in the cascade asserts the property. */
        object Absent : Authorship

        /** The nearest assertion is a built-in default exactly as shipped, so no one chose it. */
        object Shipped : Authorship

        /** Someone put this value here: a local override, a style they wrote, or a default they edited. */
        data class Authored(val literal: String?) : Authorship
    }

    /** The stylesheet every document starts from — the reference for "was this value actually chosen?". */
    private val shipped: Stylesheet by lazy(LazyThreadSafetyMode.PUBLICATION) { Stylesheet.defaults() }

    /**
     * Whether [property] was *chosen* for this block, and if so what it resolves to.
     *
     * The screen editor and the page host disagree about size on purpose. The built-in stylesheet is a
     * print stylesheet — an 11 pt body on a modular scale — because a page has to match paper. A phone
     * screen has no paper to match, and the reader's own preference (see
     * `com.norfold.app.editor.style.ReadingScale`) is the better authority there. But the two must not
     * fight: if the built-in 11 pt simply won, the reading-size preference would silently do nothing, and
     * if the reading size simply won, a size the user typed would be discarded.
     *
     * So the question the renderer needs is not "what is the value" but "did anyone choose it". A shipped
     * default yields to the renderer's own base; anything authored — a local override, a user-defined
     * style, an edited built-in, or an edited token a built-in points at — wins as it always did.
     *
     * Precedence follows [rawValueCascaded]: the *nearest* assertion decides, and it decides both ways.
     * A built-in that asserts a shipped default stops the walk with [Authorship.Shipped] rather than
     * letting an outer wrapper's value leak into a block whose own style already spoke.
     *
     * The implicit semantic default is never consulted: it is by definition the shipped hierarchy.
     */
    fun authorshipCascaded(
        sheet: Stylesheet,
        child: StyleAssignment,
        wrappersNearestFirst: List<StyleAssignment>,
        property: StyleProperty,
    ): Authorship {
        child.overrides[property.key]?.let { return Authorship.Authored(literalOf(sheet, it)) }
        authorshipInChain(sheet, child.styleId, property)?.let { return it }
        for (wrapper in wrappersNearestFirst) {
            wrapper.overrides[property.key]?.let { return Authorship.Authored(literalOf(sheet, it)) }
            authorshipInChain(sheet, wrapper.styleId, property)?.let { return it }
        }
        return Authorship.Absent
    }

    /** [authorshipCascaded] reduced to the value a renderer would use, or null to keep its own base. */
    fun authoredLiteralCascaded(
        sheet: Stylesheet,
        child: StyleAssignment,
        wrappersNearestFirst: List<StyleAssignment>,
        property: StyleProperty,
    ): String? = (authorshipCascaded(sheet, child, wrappersNearestFirst, property) as? Authorship.Authored)?.literal

    /** Null when the chain says nothing about [property]; otherwise how its nearest assertion got there. */
    private fun authorshipInChain(
        sheet: Stylesheet,
        styleId: String?,
        property: StyleProperty,
    ): Authorship? {
        for (definition in chainOf(sheet, styleId)) {
            val value = definition.get(property) ?: continue
            // A style the user wrote asserts nothing by accident.
            if (!definition.builtIn) return Authorship.Authored(literalOf(sheet, value))
            // A built-in id this build does not ship is a newer version's default arriving through a
            // round-trip. Honouring it beats dropping a value we cannot prove is a default.
            val original = shipped.style(definition.id) ?: return Authorship.Authored(literalOf(sheet, value))
            if (value != original.get(property)) return Authorship.Authored(literalOf(sheet, value))
            // Built-ins point at tokens (Body's size is `token.type.base`). Editing the token is how the
            // print model is meant to be re-based, so an edited token counts as authorship even though the
            // definition holding the reference is untouched.
            if (value is StyleValue.TokenRef) {
                val current = sheet.token(value.tokenId)?.value
                if (current != shipped.token(value.tokenId)?.value) return Authorship.Authored(current)
            }
            return Authorship.Shipped
        }
        return null
    }

    /**
     * Resolves a text property through a structural wrapper cascade.
     *
     * Precedence is deliberately property-by-property:
     * child local override > child explicit style > nearest wrapper > outer wrapper > child's implicit
     * semantic default. Wrapper values are not copied into descendants, so editing a wrapper definition
     * remains one stylesheet transaction and child overrides continue to win dynamically.
     */
    fun rawValueCascaded(
        sheet: Stylesheet,
        child: StyleAssignment,
        implicitStyleId: String?,
        wrappersNearestFirst: List<StyleAssignment>,
        property: StyleProperty,
    ): StyleValue? {
        child.overrides[property.key]?.let { return it }
        if (child.styleId != null) {
            for (definition in chainOf(sheet, child.styleId)) {
                definition.get(property)?.let { return it }
            }
        }
        for (wrapper in wrappersNearestFirst) {
            wrapper.overrides[property.key]?.let { return it }
            for (definition in chainOf(sheet, wrapper.styleId)) {
                definition.get(property)?.let { return it }
            }
        }
        if (child.styleId == null) {
            for (definition in chainOf(sheet, implicitStyleId)) {
                definition.get(property)?.let { return it }
            }
        }
        return null
    }

    fun resolveAllCascaded(
        sheet: Stylesheet,
        child: StyleAssignment,
        implicitStyleId: String?,
        wrappersNearestFirst: List<StyleAssignment>,
        category: StyleCategory = StyleCategory.TEXT,
    ): Map<StyleProperty, String> = buildMap {
        for (property in StyleProperty.of(category)) {
            literalOf(
                sheet,
                rawValueCascaded(sheet, child, implicitStyleId, wrappersNearestFirst, property),
            )?.let { put(property, it) }
        }
    }

    /** Resolves a raw value through the token table to a literal string, or null if unresolvable. */
    fun literalOf(sheet: Stylesheet, value: StyleValue?): String? = when (value) {
        null -> null
        is StyleValue.Literal -> value.text
        // A dangling token reference resolves to nothing rather than to an invented default, so the caller
        // falls through to its own default instead of rendering a colour nobody chose.
        is StyleValue.TokenRef -> sheet.token(value.tokenId)?.value
    }

    /** The final string value of [property], or null when nothing in the chain provides one. */
    fun resolve(sheet: Stylesheet, assignment: StyleAssignment, property: StyleProperty): String? =
        literalOf(sheet, rawValue(sheet, assignment, property))

    fun resolveColor(sheet: Stylesheet, assignment: StyleAssignment, property: StyleProperty): ColorValue? =
        ColorValue.parse(resolve(sheet, assignment, property))

    fun resolveFloat(sheet: Stylesheet, assignment: StyleAssignment, property: StyleProperty): Float? =
        resolve(sheet, assignment, property)?.toFloatOrNull()

    fun resolveInt(sheet: Stylesheet, assignment: StyleAssignment, property: StyleProperty): Int? =
        resolve(sheet, assignment, property)?.toIntOrNull()

    fun resolveBoolean(sheet: Stylesheet, assignment: StyleAssignment, property: StyleProperty): Boolean? =
        when (resolve(sheet, assignment, property)?.lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }

    fun resolveFont(sheet: Stylesheet, assignment: StyleAssignment): FontIdentity? =
        FontIdentity.decode(resolve(sheet, assignment, StyleProperty.FONT_FAMILY))

    /** Every property the chain or the overrides provide, resolved. The renderer's input. */
    fun resolveAll(
        sheet: Stylesheet,
        assignment: StyleAssignment,
        category: StyleCategory = StyleCategory.TEXT,
    ): Map<StyleProperty, String> = buildMap {
        for (property in StyleProperty.of(category)) {
            resolve(sheet, assignment, property)?.let { put(property, it) }
        }
    }

    /** Every raw (unresolved) value the chain provides — what [StyleAssignment.detached] needs. */
    fun rawAll(
        sheet: Stylesheet,
        assignment: StyleAssignment,
        category: StyleCategory = StyleCategory.TEXT,
    ): Map<StyleProperty, StyleValue> = buildMap {
        for (property in StyleProperty.of(category)) {
            rawValue(sheet, assignment, property)?.let { put(property, it) }
        }
    }

    /**
     * A full description of one property for the inspector: value, origin, and which style provided it.
     */
    fun describe(
        sheet: Stylesheet,
        assignment: StyleAssignment,
        property: StyleProperty,
    ): PropertyState {
        val raw = rawValue(sheet, assignment, property)
        val origin = originOf(sheet, assignment, property)
        val provider = if (origin == ValueOrigin.INHERITED) {
            chainOf(sheet, assignment.styleId).firstOrNull { it.asserts(property) }
        } else {
            null
        }
        return PropertyState(
            property = property,
            value = literalOf(sheet, raw),
            raw = raw,
            origin = origin,
            providedBy = provider?.name,
            tokenName = (raw as? StyleValue.TokenRef)?.let { sheet.token(it.tokenId)?.name },
            tokenId = (raw as? StyleValue.TokenRef)?.tokenId,
        )
    }

    /**
     * The state of [property] across **many** targets — the mixed-state answer §11 requires.
     *
     * The rule is strict on purpose: identical value *and* identical origin, or it is mixed. Two paragraphs
     * both rendering 11 pt are not the same state if one inherits it and the other overrides it, because
     * "clear override" would do different things to each — so an inspector that called them equal would
     * offer an action whose effect it could not describe.
     *
     * An empty selection is [MixedState.UNAVAILABLE], not "default": there is nothing to report on, and
     * showing a default would invite the user to edit nothing.
     */
    fun describeAcross(
        sheet: Stylesheet,
        assignments: List<StyleAssignment>,
        property: StyleProperty,
    ): MultiPropertyState {
        if (assignments.isEmpty()) {
            return MultiPropertyState(property, MixedState.UNAVAILABLE, null, null, 0, 0)
        }
        val states = assignments.map { describe(sheet, it, property) }
        val distinctValues = states.map { it.value }.distinct()
        val distinctOrigins = states.map { it.origin }.distinct()
        val overrideCount = states.count { it.origin == ValueOrigin.LOCAL }
        return when {
            distinctValues.size > 1 || distinctOrigins.size > 1 -> MultiPropertyState(
                property = property,
                state = MixedState.MIXED,
                value = null,
                origin = null,
                targetCount = assignments.size,
                overrideCount = overrideCount,
            )
            states.first().value == null -> MultiPropertyState(
                property = property,
                state = MixedState.UNSET,
                value = null,
                origin = ValueOrigin.DEFAULT,
                targetCount = assignments.size,
                overrideCount = overrideCount,
            )
            else -> MultiPropertyState(
                property = property,
                state = MixedState.UNIFORM,
                value = states.first().value,
                origin = states.first().origin,
                targetCount = assignments.size,
                overrideCount = overrideCount,
            )
        }
    }

    /**
     * The style shared by every target, or null when they differ — the "style = MIXED" case §11 gives as
     * its first example (2 Body + 1 Quote).
     */
    fun sharedStyleId(assignments: List<StyleAssignment>): SharedStyle {
        if (assignments.isEmpty()) return SharedStyle(null, MixedState.UNAVAILABLE)
        val ids = assignments.map { it.styleId }.distinct()
        return when {
            ids.size > 1 -> SharedStyle(null, MixedState.MIXED)
            ids.single() == null -> SharedStyle(null, MixedState.UNSET)
            else -> SharedStyle(ids.single(), MixedState.UNIFORM)
        }
    }

    /**
     * Whether editing [property] on [styleId] would be **invisible** to a target with this assignment.
     *
     * Two ways an edit can be swallowed, and both are worth telling the user about rather than letting them
     * conclude the edit failed:
     *
     *  - the target overrides the property locally, so nothing upstream reaches it;
     *  - a *nearer* style in the target's chain asserts it, so editing a farther ancestor is shadowed.
     *    (Heading 1 sets its own size; changing Body's size will not move it.)
     *
     * A target that does not reference [styleId] at all is not "shadowed" — it is simply unrelated, and
     * returns false so a warning is not shown for content the edit was never going to touch.
     */
    fun isShadowedBy(
        sheet: Stylesheet,
        assignment: StyleAssignment,
        property: StyleProperty,
        styleId: String,
    ): Boolean {
        val chain = chainOf(sheet, assignment.styleId)
        val editedIndex = chain.indexOfFirst { it.id == styleId }
        if (editedIndex < 0) return false
        if (assignment.overrides(property)) return true
        return chain.take(editedIndex).any { it.asserts(property) }
    }

    /**
     * Targets that would visibly change if [property] on [styleId] were edited — the honest affected count
     * for a style-definition transaction's user-facing message.
     */
    fun consumersAffectedBy(
        sheet: Stylesheet,
        assignments: List<StyleAssignment>,
        property: StyleProperty,
        styleId: String,
    ): Int = assignments.count { assignment ->
        chainOf(sheet, assignment.styleId).any { it.id == styleId } &&
            !isShadowedBy(sheet, assignment, property, styleId)
    }
}

/** Where a resolved value came from. */
enum class ValueOrigin(val label: String) {
    /** The target asserts it itself — a local override. */
    LOCAL("Local override"),

    /** A named style in the chain provides it. */
    INHERITED("Inherited"),

    /** Nothing provides it; the renderer's own default applies. */
    DEFAULT("Default"),
}

/** Multi-target agreement for one property. */
enum class MixedState(val label: String) {
    /** Every target agrees on value and origin. */
    UNIFORM("Same"),

    /** Targets disagree. The inspector must show "Mixed", not one target's value. */
    MIXED("Mixed"),

    /** Every target agrees that nothing sets this. */
    UNSET("Not set"),

    /** Nothing selected, or the property does not apply here. */
    UNAVAILABLE("Unavailable"),
}

/** One property's full state for a single target. */
data class PropertyState(
    val property: StyleProperty,
    val value: String?,
    val raw: StyleValue?,
    val origin: ValueOrigin,
    /** Name of the style that provided the value, when inherited. */
    val providedBy: String? = null,
    val tokenName: String? = null,
    val tokenId: String? = null,
) {
    val isTokenBound: Boolean get() = tokenId != null
    val canClearOverride: Boolean get() = origin == ValueOrigin.LOCAL

    /**
     * The sentence a screen reader reads. §31 requires inherited/override/mixed to be *described*, not
     * encoded in a tint.
     */
    fun accessibleLabel(): String = buildString {
        append(property.label)
        append(": ")
        append(value ?: "not set")
        if (tokenName != null) append(" (token ${tokenName})")
        append(" — ")
        append(
            when (origin) {
                ValueOrigin.LOCAL -> "local override"
                ValueOrigin.INHERITED -> "inherited${providedBy?.let { " from $it" }.orEmpty()}"
                ValueOrigin.DEFAULT -> "default"
            },
        )
    }
}

/** One property's state across a multi-target selection. */
data class MultiPropertyState(
    val property: StyleProperty,
    val state: MixedState,
    val value: String?,
    val origin: ValueOrigin?,
    val targetCount: Int,
    val overrideCount: Int,
) {
    val isMixed: Boolean get() = state == MixedState.MIXED

    /** Text for the control — "Mixed" rather than a value that only applies to some of the selection. */
    fun displayValue(): String = when (state) {
        MixedState.UNIFORM -> value ?: "—"
        MixedState.MIXED -> "Mixed"
        MixedState.UNSET -> "Not set"
        MixedState.UNAVAILABLE -> "—"
    }

    fun accessibleLabel(): String = buildString {
        append(property.label)
        append(": ")
        append(displayValue())
        if (targetCount > 1) {
            append(" across $targetCount items")
            if (state == MixedState.MIXED && overrideCount > 0) append(", $overrideCount with local overrides")
        }
        if (state == MixedState.UNIFORM && origin != null) append(" — ${origin.label.lowercase()}")
    }
}

/** The named style shared across a selection, if any. */
data class SharedStyle(val styleId: String?, val state: MixedState) {
    val isMixed: Boolean get() = state == MixedState.MIXED
}
