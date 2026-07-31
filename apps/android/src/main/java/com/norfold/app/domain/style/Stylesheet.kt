package com.norfold.app.domain.style

import kotlinx.serialization.Serializable

/**
 * A document's complete styling truth: its named styles and its tokens (roadmap Phase 3 §9–§11, §25–§26).
 *
 * One stylesheet per artifact, shared verbatim by both hosts. Flow and Document do not each keep a copy —
 * that is the "same definition/id/resolution semantics" proof §13 demands, and the reason a token defined
 * while writing prose is already there when the same content is placed on a page.
 *
 * The stylesheet is immutable; every edit returns a new one. That is what lets a style-definition change be
 * a single History transaction over a single value, rather than an in-place mutation whose inverse someone
 * has to reconstruct.
 */
@Serializable
data class Stylesheet(
    val styles: List<StyleDefinition> = emptyList(),
    val tokens: List<DocumentToken> = emptyList(),
    /**
     * Chart palette seam (§9: "may have the required forward-compatible seam, but do NOT implement Chart
     * Studio"). Stored and round-tripped; nothing in Phase 3 reads it for rendering.
     */
    val chartPalettes: Map<String, List<String>> = emptyMap(),
) {
    private val styleIndex: Map<String, StyleDefinition> get() = styles.associateBy { it.id }
    private val tokenIndex: Map<String, DocumentToken> get() = tokens.associateBy { it.id }

    fun style(id: String?): StyleDefinition? = id?.let { styles.firstOrNull { s -> s.id == it } }

    fun token(id: String?): DocumentToken? = id?.let { tokens.firstOrNull { t -> t.id == it } }

    fun styleNamed(name: String): StyleDefinition? = styles.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun stylesOf(category: StyleCategory): List<StyleDefinition> = styles.filter { it.category == category }

    fun tokensOf(kind: TokenKind): List<DocumentToken> = tokens.filter { it.kind == kind }

    // ── Style editing ────────────────────────────────────────────────────────────────────────────

    /** Adds or replaces a definition by id. One call = one History transaction at the call site. */
    fun withStyle(definition: StyleDefinition): Stylesheet {
        val index = styles.indexOfFirst { it.id == definition.id }
        return if (index < 0) copy(styles = styles + definition)
        else copy(styles = styles.toMutableList().apply { set(index, definition) })
    }

    fun withStyles(definitions: List<StyleDefinition>): Stylesheet =
        definitions.fold(this) { sheet, definition -> sheet.withStyle(definition) }

    /**
     * Renames a style. References are untouched **because they were never names** — this method exists to
     * make that explicit and to be the thing the rename test asserts against.
     */
    fun renameStyle(id: String, name: String): Stylesheet =
        style(id)?.let { withStyle(it.copy(name = name)) } ?: this

    /**
     * Removes a style, re-parenting any style that was based on it so no definition is left pointing at a
     * ghost. Built-in styles are never removed.
     *
     * Content still referencing the removed style is *not* rewritten here: [StyleResolver] treats a dangling
     * style id as "no style" and renders the target's own overrides plus defaults, so nothing is corrupted.
     * Whether the content should keep its look is a *policy* decision, made by [DeleteStylePolicy] at the
     * call site rather than silently here.
     */
    fun removeStyle(id: String): Stylesheet {
        val target = style(id) ?: return this
        if (target.builtIn) return this
        val reparented = styles.filterNot { it.id == id }.map {
            if (it.basedOn == id) it.copy(basedOn = target.basedOn) else it
        }
        return copy(styles = reparented)
    }

    // ── Token editing ────────────────────────────────────────────────────────────────────────────

    fun withToken(token: DocumentToken): Stylesheet {
        val index = tokens.indexOfFirst { it.id == token.id }
        return if (index < 0) copy(tokens = tokens + token)
        else copy(tokens = tokens.toMutableList().apply { set(index, token) })
    }

    fun renameToken(id: String, name: String): Stylesheet =
        token(id)?.let { withToken(it.copy(name = name)) } ?: this

    fun removeToken(id: String): Stylesheet = copy(tokens = tokens.filterNot { it.id == id })

    /**
     * Every style that references [tokenId]. The "is this token in use?" question a delete must ask before
     * it can be safe.
     */
    fun stylesReferencing(tokenId: String): List<StyleDefinition> =
        styles.filter { tokenId in it.referencedTokenIds() }

    /**
     * Replaces every reference to [tokenId] with the token's current literal value, then removes the token.
     *
     * This is the "detach, preserving the resolved value" resolution §6 requires: no colour is lost, and
     * nothing is left pointing at a deleted id. It is deliberately explicit — a delete that did this
     * automatically would be indistinguishable from a delete that dropped the references.
     */
    fun detachAndRemoveToken(tokenId: String): Stylesheet {
        val target = token(tokenId) ?: return this
        val literal = StyleValue.Literal(target.value)
        val rewritten = styles.map { definition ->
            val touched = definition.properties.filterValues { it is StyleValue.TokenRef && it.tokenId == tokenId }
            if (touched.isEmpty()) definition
            else definition.copy(properties = definition.properties + touched.mapValues { literal })
        }
        return copy(styles = rewritten, tokens = tokens.filterNot { it.id == tokenId })
    }

    /**
     * Repoints every reference to [tokenId] at [replacementId], then removes the original.
     *
     * The other safe resolution: the user says "use Accent instead". Refuses if the replacement does not
     * exist, rather than removing the original and leaving dangling references behind.
     */
    fun replaceAndRemoveToken(tokenId: String, replacementId: String): Stylesheet {
        if (token(tokenId) == null || token(replacementId) == null) return this
        val replacement = StyleValue.TokenRef(replacementId)
        val rewritten = styles.map { definition ->
            val touched = definition.properties.filterValues { it is StyleValue.TokenRef && it.tokenId == tokenId }
            if (touched.isEmpty()) definition
            else definition.copy(properties = definition.properties + touched.mapValues { replacement })
        }
        return copy(styles = rewritten, tokens = tokens.filterNot { it.id == tokenId })
    }

    /** Colour values reachable through tokens, restricted to the domains a document may own (§3, §6). */
    fun tokenColors(): List<ColorValue> = tokens
        .filter { it.kind == TokenKind.COLOR && it.domain.tokenizable }
        .mapNotNull { it.colorValue() }

    companion object {
        /**
         * The built-in style set (§9). Sparse on purpose: Body asserts the base look, and each heading
         * asserts only what makes it *different*, so changing Body's font moves the whole document.
         *
         * `basedOn = BODY` for the headings is the mechanism. If each heading restated the family, a user
         * changing the document's base font would have to change ten styles.
         */
        const val BODY = "style.body"
        const val TITLE = "style.title"
        const val SUBTITLE = "style.subtitle"
        const val CAPTION = "style.caption"
        const val QUOTE = "style.quote"
        const val CODE = "style.code"

        fun headingId(level: Int) = "style.heading$level"

        /** Token ids of the default palette, stable so a fresh document and an old one agree. */
        const val TOKEN_TEXT_PRIMARY = "token.text.primary"
        const val TOKEN_TEXT_SECONDARY = "token.text.secondary"
        const val TOKEN_ACCENT = "token.accent"
        const val TOKEN_SURFACE = "token.surface"
        const val TOKEN_SPACING_BLOCK = "token.spacing.block"
        const val TOKEN_TYPE_BASE = "token.type.base"

        /**
         * The default stylesheet a new document starts with.
         *
         * Sizes follow a ~1.25 modular scale from an 11 pt body, which is the print convention this app's
         * page model is built around (points, not sp) — so a document looks the same on paper as on screen.
         */
        fun defaults(): Stylesheet {
            val tokens = listOf(
                DocumentToken.color("Text primary", ColorValue(0x1A, 0x1A, 0x1F), id = TOKEN_TEXT_PRIMARY),
                DocumentToken.color("Text secondary", ColorValue(0x5F, 0x5F, 0x6B), id = TOKEN_TEXT_SECONDARY),
                DocumentToken.color("Accent", ColorValue(0x6F, 0x36, 0xFF), id = TOKEN_ACCENT),
                DocumentToken.color("Surface", ColorValue(0xFF, 0xFF, 0xFF), id = TOKEN_SURFACE),
                DocumentToken.spacing("Block spacing", 8f, id = TOKEN_SPACING_BLOCK),
                DocumentToken.typeScale("Base size", 11f, id = TOKEN_TYPE_BASE),
            )
            val body = StyleDefinition(
                id = BODY,
                name = "Body",
                category = StyleCategory.TEXT,
                builtIn = true,
                semanticRole = "paragraph",
                properties = mapOf(
                    StyleProperty.FONT_FAMILY.key to StyleValue.of(FontIdentity.builtIn("inter", "Inter")),
                    StyleProperty.FONT_SIZE.key to StyleValue.token(TOKEN_TYPE_BASE),
                    StyleProperty.FONT_WEIGHT.key to StyleValue.of(400),
                    StyleProperty.TEXT_COLOR.key to StyleValue.token(TOKEN_TEXT_PRIMARY),
                    StyleProperty.LINE_HEIGHT.key to StyleValue.of(1.5f),
                    StyleProperty.SPACE_AFTER.key to StyleValue.token(TOKEN_SPACING_BLOCK),
                ),
            )
            fun derived(
                id: String,
                name: String,
                role: String?,
                vararg props: Pair<StyleProperty, StyleValue>,
            ) = StyleDefinition(
                id = id,
                name = name,
                category = StyleCategory.TEXT,
                builtIn = true,
                basedOn = BODY,
                semanticRole = role,
                properties = props.associate { it.first.key to it.second },
            )
            val styles = listOf(
                body,
                derived(
                    TITLE, "Title", "title",
                    StyleProperty.FONT_SIZE to StyleValue.of(32f),
                    StyleProperty.FONT_WEIGHT to StyleValue.of(700),
                    StyleProperty.LINE_HEIGHT to StyleValue.of(1.15f),
                    StyleProperty.SPACE_AFTER to StyleValue.of(12f),
                ),
                derived(
                    SUBTITLE, "Subtitle", "subtitle",
                    StyleProperty.FONT_SIZE to StyleValue.of(18f),
                    StyleProperty.FONT_WEIGHT to StyleValue.of(400),
                    StyleProperty.TEXT_COLOR to StyleValue.token(TOKEN_TEXT_SECONDARY),
                    StyleProperty.LINE_HEIGHT to StyleValue.of(1.3f),
                ),
                derived(
                    headingId(1), "Heading 1", "heading:1",
                    StyleProperty.FONT_SIZE to StyleValue.of(24f),
                    StyleProperty.FONT_WEIGHT to StyleValue.of(700),
                    StyleProperty.LINE_HEIGHT to StyleValue.of(1.2f),
                    StyleProperty.SPACE_BEFORE to StyleValue.of(16f),
                ),
                derived(
                    headingId(2), "Heading 2", "heading:2",
                    StyleProperty.FONT_SIZE to StyleValue.of(20f),
                    StyleProperty.FONT_WEIGHT to StyleValue.of(700),
                    StyleProperty.LINE_HEIGHT to StyleValue.of(1.25f),
                    StyleProperty.SPACE_BEFORE to StyleValue.of(14f),
                ),
                derived(
                    headingId(3), "Heading 3", "heading:3",
                    StyleProperty.FONT_SIZE to StyleValue.of(17f),
                    StyleProperty.FONT_WEIGHT to StyleValue.of(600),
                    StyleProperty.SPACE_BEFORE to StyleValue.of(12f),
                ),
                derived(
                    headingId(4), "Heading 4", "heading:4",
                    StyleProperty.FONT_SIZE to StyleValue.of(15f),
                    StyleProperty.FONT_WEIGHT to StyleValue.of(600),
                    StyleProperty.SPACE_BEFORE to StyleValue.of(10f),
                ),
                derived(
                    headingId(5), "Heading 5", "heading:5",
                    StyleProperty.FONT_SIZE to StyleValue.of(13f),
                    StyleProperty.FONT_WEIGHT to StyleValue.of(600),
                    StyleProperty.SPACE_BEFORE to StyleValue.of(8f),
                ),
                derived(
                    headingId(6), "Heading 6", "heading:6",
                    StyleProperty.FONT_SIZE to StyleValue.of(12f),
                    StyleProperty.FONT_WEIGHT to StyleValue.of(600),
                    StyleProperty.ITALIC to StyleValue.of(true),
                    StyleProperty.SPACE_BEFORE to StyleValue.of(8f),
                ),
                derived(
                    CAPTION, "Caption", "caption",
                    StyleProperty.FONT_SIZE to StyleValue.of(9f),
                    StyleProperty.TEXT_COLOR to StyleValue.token(TOKEN_TEXT_SECONDARY),
                    StyleProperty.LINE_HEIGHT to StyleValue.of(1.35f),
                ),
                derived(
                    QUOTE, "Quote", "quote",
                    StyleProperty.ITALIC to StyleValue.of(true),
                    StyleProperty.TEXT_COLOR to StyleValue.token(TOKEN_TEXT_SECONDARY),
                    StyleProperty.SPACE_BEFORE to StyleValue.of(10f),
                    StyleProperty.SPACE_AFTER to StyleValue.of(10f),
                ),
                derived(
                    CODE, "Code", "code",
                    StyleProperty.FONT_FAMILY to StyleValue.of(FontIdentity.builtIn("jetbrains-mono", "JetBrains Mono")),
                    StyleProperty.FONT_SIZE to StyleValue.of(10f),
                    StyleProperty.LINE_HEIGHT to StyleValue.of(1.45f),
                ),
                StyleDefinition(
                    id = "style.object.default",
                    name = "Default shape",
                    category = StyleCategory.OBJECT,
                    builtIn = true,
                    properties = mapOf(
                        StyleProperty.FILL.key to StyleValue.token(TOKEN_ACCENT),
                        StyleProperty.STROKE_COLOR.key to StyleValue.of(ColorValue(0xD8, 0xC8, 0xFF)),
                        StyleProperty.STROKE_WIDTH.key to StyleValue.of(2f),
                        StyleProperty.CORNER_RADIUS.key to StyleValue.of(8f),
                        StyleProperty.OPACITY.key to StyleValue.of(1f),
                    ),
                ),
            )
            return Stylesheet(styles = styles, tokens = tokens)
        }

        /** The style that is the default for a semantic block role, if any. */
        fun defaultStyleIdFor(role: String): String? = when {
            role == "paragraph" -> BODY
            role == "title" -> TITLE
            role == "subtitle" -> SUBTITLE
            role == "caption" -> CAPTION
            role == "quote" -> QUOTE
            role == "code" -> CODE
            role.startsWith("heading:") -> role.substringAfter(':').toIntOrNull()
                ?.coerceIn(1, 6)?.let { headingId(it) }
            else -> null
        }
    }
}

/** What happens to content when a style it references is deleted. Explicit, never implicit (§6, §9). */
enum class DeleteStylePolicy(val label: String) {
    /** Content keeps exactly the look it had, as local overrides. Nothing changes visually. */
    PRESERVE_APPEARANCE("Keep the current formatting"),

    /** Content falls back to the named replacement style. */
    REASSIGN("Apply a different style"),
}
