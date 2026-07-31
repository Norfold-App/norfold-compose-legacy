package com.norfold.app.domain.style

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Named styles and document tokens (roadmap Phase 3 §9–§11, §25, spec §10).
 *
 * The single decision this file turns on: a style is a **sparse property map**, not a complete look.
 *
 * A "style blob" model — every style carrying a full set of resolved values — cannot express the case §10
 * makes central. If Body says `font = Inter, size = 11pt, color = TextPrimary` and a paragraph is bold, a
 * blob model stores the paragraph's whole look including `font = Inter`. Change Body's font to Source Sans 3
 * and that paragraph keeps Inter, because its stored blob claims Inter explicitly and nothing can tell
 * "the author chose Inter" apart from "the author inherited Inter". Property-level sparseness is what makes
 * "changing Body's font updates the inherited font *and* preserves the local bold" mechanically true rather
 * than a behaviour someone has to remember to implement.
 *
 * So: a style holds only the properties it *asserts*; a target holds only the properties it *overrides*;
 * resolution walks the chain and the first assertion wins. Nothing stores a resolved value as truth.
 */

/** Which kind of thing a style definition can be applied to. */
@Serializable
enum class StyleCategory(val label: String) {
    @SerialName("text") TEXT("Text"),
    @SerialName("object") OBJECT("Object"),
}

/**
 * A single styleable property.
 *
 * An enum rather than free-form string keys, so a typo cannot create a phantom property that inherits from
 * nothing and is never rendered. [category] lets the inspector show only relevant controls, and it is what
 * stops an object style from asserting a line height.
 */
@Serializable
enum class StyleProperty(val key: String, val category: StyleCategory, val label: String) {
    // Text
    @SerialName("font") FONT_FAMILY("font", StyleCategory.TEXT, "Font"),
    @SerialName("size") FONT_SIZE("size", StyleCategory.TEXT, "Size"),
    @SerialName("weight") FONT_WEIGHT("weight", StyleCategory.TEXT, "Weight"),
    @SerialName("italic") ITALIC("italic", StyleCategory.TEXT, "Italic"),
    @SerialName("underline") UNDERLINE("underline", StyleCategory.TEXT, "Underline"),
    @SerialName("strike") STRIKETHROUGH("strike", StyleCategory.TEXT, "Strikethrough"),
    @SerialName("color") TEXT_COLOR("color", StyleCategory.TEXT, "Text color"),
    @SerialName("highlight") HIGHLIGHT("highlight", StyleCategory.TEXT, "Highlight"),
    @SerialName("lineHeight") LINE_HEIGHT("lineHeight", StyleCategory.TEXT, "Line height"),
    @SerialName("letterSpacing") LETTER_SPACING("letterSpacing", StyleCategory.TEXT, "Letter spacing"),
    @SerialName("align") TEXT_ALIGN("align", StyleCategory.TEXT, "Alignment"),
    @SerialName("spaceBefore") SPACE_BEFORE("spaceBefore", StyleCategory.TEXT, "Space before"),
    @SerialName("spaceAfter") SPACE_AFTER("spaceAfter", StyleCategory.TEXT, "Space after"),

    // Object
    @SerialName("fill") FILL("fill", StyleCategory.OBJECT, "Fill"),
    @SerialName("stroke") STROKE_COLOR("stroke", StyleCategory.OBJECT, "Stroke"),
    @SerialName("strokeWidth") STROKE_WIDTH("strokeWidth", StyleCategory.OBJECT, "Stroke width"),
    @SerialName("corner") CORNER_RADIUS("corner", StyleCategory.OBJECT, "Corner radius"),
    @SerialName("padding") PADDING("padding", StyleCategory.OBJECT, "Padding"),
    @SerialName("shadow") SHADOW("shadow", StyleCategory.OBJECT, "Shadow"),
    @SerialName("opacity") OPACITY("opacity", StyleCategory.OBJECT, "Opacity"),
    ;

    companion object {
        fun byKey(key: String): StyleProperty? = entries.firstOrNull { it.key == key }
        fun of(category: StyleCategory): List<StyleProperty> = entries.filter { it.category == category }
    }
}

/**
 * A property's value: either a literal or a **reference to a document token**.
 *
 * The reference case is what makes §25 work. A token reference resolves dynamically, so renaming the token
 * cannot break it (the id is what's stored) and changing the token's value updates every consumer without
 * touching any of them. Storing the token's current value instead — "flattening" — would make a token a
 * one-time copy-paste, which is exactly what a design-token system exists not to be.
 */
@Serializable
sealed interface StyleValue {

    /** A concrete value. [text] is the canonical serialized form. */
    @Serializable
    @SerialName("literal")
    data class Literal(val text: String) : StyleValue

    /** A reference to a document token by its stable id. */
    @Serializable
    @SerialName("token")
    data class TokenRef(val tokenId: String) : StyleValue

    companion object {
        fun of(value: String) = Literal(value)
        fun of(value: Float) = Literal(formatFloat(value))
        fun of(value: Int) = Literal(value.toString())
        fun of(value: Boolean) = Literal(value.toString())
        fun of(value: ColorValue) = Literal(value.toHex())
        fun of(value: FontIdentity) = Literal(value.encode())
        fun token(id: String) = TokenRef(id)

        /**
         * Locale-independent float formatting. `String.format` would emit `11,0` under a comma-decimal
         * locale and the value would then fail to re-parse — a document that opens differently depending
         * on the device's language setting.
         */
        fun formatFloat(value: Float): String {
            if (!value.isFinite()) return "0"
            if (value == value.toLong().toFloat()) return value.toLong().toString()
            val scaled = Math.round(value * 1000.0) / 1000.0
            return scaled.toString()
        }
    }
}

/**
 * A named style definition.
 *
 * [id] is stable and is the only thing content stores. [name] is presentation, and renaming it must not
 * break a single reference — the failure §9 forbids ("do not identify a style solely by its current display
 * name"). [basedOn] gives single inheritance between styles, so "Heading 2" can be "Heading 1 but smaller"
 * without restating the family, colour, or spacing.
 *
 * [properties] is sparse by design: absence means "inherit", which is a different fact from "same value as
 * the parent". Only sparseness can tell them apart, and the difference is what the inspector shows as
 * inherited-vs-override.
 */
@Serializable
data class StyleDefinition(
    val id: String,
    val name: String,
    val category: StyleCategory,
    val properties: Map<String, StyleValue> = emptyMap(),
    /** Parent style id. A cycle is refused at resolution time rather than trusted not to exist. */
    val basedOn: String? = null,
    /** True for the built-in Title/Body/… set, which may be edited but not deleted. */
    val builtIn: Boolean = false,
    /** Semantic block role this style is the default for, when it is one (`"heading:1"`, `"quote"`). */
    val semanticRole: String? = null,
    /**
     * Properties a future version understands and this one does not, preserved verbatim.
     *
     * Without this, opening a document in an older build and saving it would silently strip whatever the
     * newer build added — the same forward-compatibility contract [com.norfold.app.domain.UnknownBlock]
     * provides for blocks, applied to styles.
     */
    val unknownProperties: Map<String, String> = emptyMap(),
) {
    fun get(property: StyleProperty): StyleValue? = properties[property.key]

    fun asserts(property: StyleProperty): Boolean = property.key in properties

    fun with(property: StyleProperty, value: StyleValue): StyleDefinition =
        copy(properties = properties + (property.key to value))

    fun without(property: StyleProperty): StyleDefinition =
        copy(properties = properties - property.key)

    /** Token ids this definition references, for the "is this token in use?" question. */
    fun referencedTokenIds(): Set<String> =
        properties.values.filterIsInstance<StyleValue.TokenRef>().map { it.tokenId }.toSet()

    companion object {
        fun newId(): String = "style-${UUID.randomUUID()}"
    }
}

/**
 * A document token: a named value other things point at (roadmap §25, spec §10.4).
 *
 * [id] is stable and is what references store; [name] is free to change. That asymmetry is the entire
 * feature — it is why renaming "Brand Purple" to "Accent" updates one record instead of rewriting every
 * paragraph that used it.
 */
@Serializable
data class DocumentToken(
    val id: String,
    val name: String,
    val kind: TokenKind,
    /** Canonical serialized value, interpreted per [kind]. */
    val value: String,
    /** For colour tokens, the domain the value belongs to — enforced so chrome cannot become content (§3). */
    val domain: ColorDomain = ColorDomain.CONTENT,
    val description: String = "",
) {
    fun colorValue(): ColorValue? = if (kind == TokenKind.COLOR) ColorValue.parse(value) else null

    fun scalarValue(): Float? = if (kind != TokenKind.COLOR) value.toFloatOrNull() else null

    /** Accessible description: name *and* value, never a swatch alone (§31). */
    fun accessibleLabel(): String = when (kind) {
        TokenKind.COLOR -> "$name, ${colorValue()?.accessibleLabel() ?: value}"
        TokenKind.SPACING -> "$name, ${value}pt spacing"
        TokenKind.TYPE_SCALE -> "$name, ${value}pt type size"
    }

    companion object {
        fun newId(): String = "token-${UUID.randomUUID()}"

        fun color(name: String, value: ColorValue, domain: ColorDomain = ColorDomain.CONTENT, id: String = newId()) =
            DocumentToken(id, name, TokenKind.COLOR, value.toHex(), domain)

        fun spacing(name: String, points: Float, id: String = newId()) =
            DocumentToken(id, name, TokenKind.SPACING, StyleValue.formatFloat(points))

        fun typeScale(name: String, points: Float, id: String = newId()) =
            DocumentToken(id, name, TokenKind.TYPE_SCALE, StyleValue.formatFloat(points))
    }
}

@Serializable
enum class TokenKind(val label: String) {
    @SerialName("color") COLOR("Color"),
    @SerialName("spacing") SPACING("Spacing"),
    @SerialName("typeScale") TYPE_SCALE("Type scale"),
}

/**
 * What a piece of content stores about its styling: a style reference plus its own local overrides.
 *
 * This is the "style reference + local overrides → resolved presentation" shape §10 asks for. The resolved
 * look is never stored, which is why editing the style definition is enough to restyle every consumer.
 */
@Serializable
data class StyleAssignment(
    val styleId: String? = null,
    val overrides: Map<String, StyleValue> = emptyMap(),
) {
    val hasOverrides: Boolean get() = overrides.isNotEmpty()

    fun override(property: StyleProperty, value: StyleValue): StyleAssignment =
        copy(overrides = overrides + (property.key to value))

    /** Clears one local override, so the property returns to whatever it inherits. */
    fun clearOverride(property: StyleProperty): StyleAssignment =
        copy(overrides = overrides - property.key)

    fun clearAllOverrides(): StyleAssignment = copy(overrides = emptyMap())

    fun overrides(property: StyleProperty): Boolean = property.key in overrides

    /**
     * Detaches from the named style while **keeping the current look**, by flattening the resolved values
     * into local overrides.
     *
     * The flattening is the point: "detach" must not mean "lose your formatting". The user is saying "stop
     * following Body", not "become unstyled", so every property Body was providing becomes an explicit
     * local value first.
     */
    fun detached(resolved: Map<StyleProperty, StyleValue>): StyleAssignment =
        StyleAssignment(styleId = null, overrides = resolved.mapKeys { it.key.key } + overrides)

    companion object {
        val NONE = StyleAssignment()
    }
}
