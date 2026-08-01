package com.norfold.app.editor.style

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BlockEditorSession
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.ShapeBlock
import com.norfold.app.domain.StylesheetBlock
import com.norfold.app.domain.effectiveStyleAssignment
import com.norfold.app.domain.findById
import com.norfold.app.domain.semanticStyleRole
import com.norfold.app.domain.stylesheet
import com.norfold.app.domain.style.ColorPaint
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.domain.style.DeleteStylePolicy
import com.norfold.app.domain.style.DocumentToken
import com.norfold.app.domain.style.FontIdentity
import com.norfold.app.domain.style.MixedState
import com.norfold.app.domain.style.MultiPropertyState
import com.norfold.app.domain.style.PropertyState
import com.norfold.app.domain.style.SharedStyle
import com.norfold.app.domain.style.StyleAssignment
import com.norfold.app.domain.style.StyleCategory
import com.norfold.app.domain.style.StyleDefinition
import com.norfold.app.domain.style.StyleProperty
import com.norfold.app.domain.style.StyleResolver
import com.norfold.app.domain.style.StyleValue
import com.norfold.app.domain.style.Stylesheet
import com.norfold.app.domain.style.TokenKind
import com.norfold.app.domain.supportsStyle
import com.norfold.app.domain.updateBlock
import com.norfold.app.domain.walk
import com.norfold.app.domain.withStyleAssignment

/**
 * Applies styles, tokens, colors and fonts through **one** transaction each (roadmap Phase 3 §14, §29).
 *
 * The controller exists because §14's requirement is easy to state and easy to get wrong: changing the Body
 * style must be *one* transaction over *one* definition, not 200 transactions over the 200 paragraphs that
 * reference it. Both halves matter —
 *
 *  - **one transaction** is a History requirement, and
 *  - **over one definition** is a *data model* requirement. If the edit rewrote every paragraph, undo would
 *    have to restore 200 blocks, the save layer would mark 200 rows dirty, and the whole point of a named
 *    style (indirection) would have been discarded at the moment it was used.
 *
 * The controller therefore only ever writes the stylesheet block for a definition change, and only ever
 * writes content blocks when the *content's own assignment* changes (applying a style, overriding a
 * property, detaching). It is generic over the host: it takes a mutation function with exactly the shape
 * both `FlowEditController.mutate` and the document controller already expose, so neither host needs its own
 * copy of this logic — which is what §1's "styling truth must not" differ actually requires.
 */
class StyleController(
    /**
     * Runs a mutation as one transaction and reports whether anything changed.
     *
     * The host supplies this: Flow passes `FlowEditController.mutate`, the document host passes its own
     * equivalent. Both already diff before/after into canonical operations, so the controller gets correct
     * History semantics without knowing which engine it is talking to.
     */
    private val mutate: (label: String, mutation: (BlockEditorSession) -> Unit) -> Boolean,
    private val documentProvider: () -> BlockDocument,
) {
    fun document(): BlockDocument = documentProvider()

    fun stylesheet(): Stylesheet = document().stylesheet()

    // ── Reading state ────────────────────────────────────────────────────────────────────────────

    private fun assignmentsOf(blockIds: List<String>): List<StyleAssignment> {
        val doc = document()
        return blockIds.mapNotNull { doc.findById(it)?.effectiveStyleAssignment() }
    }

    /** One property's state for one block — inherited / local / default, and what provided it. */
    fun propertyState(blockId: String, property: StyleProperty): PropertyState? {
        val block = document().findById(blockId) ?: return null
        return StyleResolver.describe(stylesheet(), block.effectiveStyleAssignment(), property)
    }

    /**
     * One property's state across a selection — the mixed-state answer the inspector shows (§11).
     *
     * Never "the first target's value": [StyleResolver.describeAcross] reports MIXED unless every target
     * agrees on both value *and* origin.
     */
    fun propertyStateAcross(blockIds: List<String>, property: StyleProperty): MultiPropertyState =
        StyleResolver.describeAcross(stylesheet(), assignmentsOf(blockIds), property)

    /** The style shared by the selection, or MIXED. */
    fun sharedStyle(blockIds: List<String>): SharedStyle =
        StyleResolver.sharedStyleId(assignmentsOf(blockIds))

    /** Every resolved property for one block — the renderer's input. */
    fun resolved(blockId: String, category: StyleCategory = StyleCategory.TEXT): Map<StyleProperty, String> {
        val block = document().findById(blockId) ?: return emptyMap()
        return StyleResolver.resolveAll(stylesheet(), block.effectiveStyleAssignment(), category)
    }

    fun resolvedFont(blockId: String): FontIdentity? {
        val block = document().findById(blockId) ?: return null
        return StyleResolver.resolveFont(stylesheet(), block.effectiveStyleAssignment())
    }

    // ── Applying a style to content ──────────────────────────────────────────────────────────────

    /**
     * Applies [styleId] to every eligible block in [blockIds], as **one** transaction (§11, §14).
     *
     * Local overrides are deliberately **kept**. A user applying "Heading 2" to a paragraph that is
     * locally red is asking for Heading 2's structure, not for their red to be discarded — and if they did
     * want that, [clearOverrides] says so explicitly. Silently dropping overrides here would make "apply
     * style" a destructive operation that no undo-less preview could warn about.
     */
    fun applyStyle(blockIds: List<String>, styleId: String, label: String = "Apply style"): Int {
        val sheet = stylesheet()
        val definition = sheet.style(styleId) ?: return 0
        val doc = document()
        val status = styleApplicationStatus(blockIds, styleId)
        if (!status.enabled) return 0
        val eligible = blockIds.filter { id -> doc.findById(id)?.effectiveStyleAssignment()?.styleId != styleId }
        if (eligible.isEmpty()) return 0
        val changed = mutate(label) { session ->
            session.replaceBlocksInTree(eligible) { block ->
                block.withStyleAssignment(block.effectiveStyleAssignment().copy(styleId = styleId))
            }
        }
        return if (changed) eligible.size else 0
    }

    /** Semantic built-ins are never allowed to contradict the selected block structure. */
    fun styleApplicationStatus(blockIds: List<String>, styleId: String): StyleApplicationStatus {
        val definition = stylesheet().style(styleId)
            ?: return StyleApplicationStatus(false, "Style is unavailable")
        if (blockIds.isEmpty()) return StyleApplicationStatus(false, "Select compatible content first")
        val blocks = blockIds.mapNotNull(document()::findById)
        if (blocks.size != blockIds.size || blocks.any { !it.supportsStyle() || categoryOf(it) != definition.category }) {
            return StyleApplicationStatus(false, "Selection contains incompatible content")
        }
        definition.semanticRole?.let { required ->
            val incompatible = blocks.filter { it.semanticStyleRole() != required }
            if (incompatible.isNotEmpty()) {
                return StyleApplicationStatus(
                    false,
                    "Requires ${semanticRoleLabel(required)} structure",
                )
            }
        }
        if (blocks.all { it.effectiveStyleAssignment().styleId == styleId }) {
            return StyleApplicationStatus(false, "Already applied")
        }
        return StyleApplicationStatus(true, null)
    }

    /**
     * Sets a local override on every block in [blockIds], as one transaction.
     *
     * This is direct formatting, which §10 explicitly keeps allowed. The override is recorded *as* an
     * override, so the inspector can show it as one and offer to clear it — as opposed to writing the value
     * into a private style, which would make "reset to Body" impossible to express.
     */
    fun setOverride(
        blockIds: List<String>,
        property: StyleProperty,
        value: StyleValue,
        label: String = "Format ${property.label.lowercase()}",
    ): Int {
        val doc = document()
        val eligible = blockIds.filter { id ->
            val block = doc.findById(id) ?: return@filter false
            block.supportsStyle() && property.category == categoryOf(block) &&
                block.effectiveStyleAssignment().overrides[property.key] != value
        }
        if (eligible.isEmpty()) return 0
        val changed = mutate(label) { session ->
            session.replaceBlocksInTree(eligible) { block ->
                block.withStyleAssignment(block.effectiveStyleAssignment().override(property, value))
            }
        }
        return if (changed) eligible.size else 0
    }

    /**
     * Clears local overrides so the properties return to what they inherit (§37.19).
     *
     * Passing an empty [properties] clears them all. Only blocks that actually *have* the override are
     * touched, so the count is honest and a selection where nothing was overridden is a no-op rather than
     * an empty undo step.
     */
    fun clearOverrides(
        blockIds: List<String>,
        properties: Set<StyleProperty> = emptySet(),
        label: String = "Clear formatting",
    ): Int {
        val doc = document()
        val eligible = blockIds.filter { id ->
            val assignment = doc.findById(id)?.effectiveStyleAssignment() ?: return@filter false
            if (properties.isEmpty()) assignment.hasOverrides
            else properties.any { assignment.overrides(it) }
        }
        if (eligible.isEmpty()) return 0
        val changed = mutate(label) { session ->
            session.replaceBlocksInTree(eligible) { block ->
                val assignment = block.effectiveStyleAssignment()
                val next = if (properties.isEmpty()) {
                    assignment.clearAllOverrides()
                } else {
                    properties.fold(assignment) { acc, property -> acc.clearOverride(property) }
                }
                block.withStyleAssignment(next)
            }
        }
        return if (changed) eligible.size else 0
    }

    /**
     * Detaches the selection from its named style while keeping its exact appearance (§14, §27).
     *
     * The flattening is what makes this safe: every property the style was providing becomes an explicit
     * local value first, so "detach" means "stop following this style", not "lose your formatting".
     */
    fun detachStyle(blockIds: List<String>, label: String = "Detach style"): Int {
        val sheet = stylesheet()
        val doc = document()
        val eligible = blockIds.filter { doc.findById(it)?.effectiveStyleAssignment()?.styleId != null }
        if (eligible.isEmpty()) return 0
        val changed = mutate(label) { session ->
            session.replaceBlocksInTree(eligible) { block ->
                val assignment = block.effectiveStyleAssignment()
                val resolved = StyleResolver.rawAll(sheet, assignment, categoryOf(block))
                block.withStyleAssignment(assignment.detached(resolved))
            }
        }
        return if (changed) eligible.size else 0
    }

    // ── Editing style definitions ────────────────────────────────────────────────────────────────

    /**
     * Changes one property of a style definition — **one** transaction touching **one** block (§14).
     *
     * Content is not rewritten. Every consumer re-resolves on the next render, which is why 200 paragraphs
     * can change appearance from a single-block edit and why undoing it restores all 200 at once.
     */
    fun updateStyleProperty(
        styleId: String,
        property: StyleProperty,
        value: StyleValue,
        label: String? = null,
    ): Boolean {
        val sheet = stylesheet()
        val definition = sheet.style(styleId) ?: return false
        if (definition.get(property) == value) return false
        return writeStylesheet(
            sheet.withStyle(definition.with(property, value)),
            label ?: "Update ${definition.name}",
        )
    }

    /** Removes a property from a definition, so it inherits from its base again. */
    fun clearStyleProperty(styleId: String, property: StyleProperty, label: String? = null): Boolean {
        val sheet = stylesheet()
        val definition = sheet.style(styleId) ?: return false
        if (!definition.asserts(property)) return false
        return writeStylesheet(
            sheet.withStyle(definition.without(property)),
            label ?: "Clear ${property.label.lowercase()} in ${definition.name}",
        )
    }

    /** Renames a style. References survive because they are ids, not names (§37.21). */
    fun renameStyle(styleId: String, name: String): Boolean {
        val sheet = stylesheet()
        val definition = sheet.style(styleId) ?: return false
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == definition.name) return false
        return writeStylesheet(sheet.renameStyle(styleId, trimmed), "Rename style")
    }

    /**
     * Creates a new style from a block's current resolved formatting (§27).
     *
     * The new definition captures the *resolved* values, so the style looks like what the user was pointing
     * at — and the source block is then reassigned to it with its overrides cleared, because those overrides
     * are now expressed by the style itself. Leaving them would double-apply them the moment the style
     * changed.
     */
    fun createStyleFromBlock(
        blockId: String,
        name: String,
        label: String = "Create style",
    ): String? {
        val sheet = stylesheet()
        val block = document().findById(blockId) ?: return null
        if (!block.supportsStyle()) return null
        val trimmed = name.trim().ifEmpty { return null }
        val category = categoryOf(block)
        val assignment = block.effectiveStyleAssignment()
        val definition = StyleDefinition(
            id = StyleDefinition.newId(),
            name = trimmed,
            category = category,
            basedOn = assignment.styleId,
            // Only the *overrides* become the new style's assertions: what was inherited stays inherited
            // via basedOn, so the new style remains a delta rather than a frozen copy of its parent.
            properties = assignment.overrides,
        )
        val committed = mutate(label) { session ->
            session.writeStylesheet(sheet.withStyle(definition))
            session.replaceBlocksInTree(listOf(blockId)) {
                it.withStyleAssignment(StyleAssignment(styleId = definition.id))
            }
        }
        return if (committed) definition.id else null
    }

    /**
     * Updates a style definition to match a block's current local overrides — "update style from
     * selection" (§27). The block's overrides are then cleared, since the style now expresses them.
     */
    fun updateStyleFromBlock(blockId: String, styleId: String, label: String = "Update style"): Boolean {
        val sheet = stylesheet()
        val definition = sheet.style(styleId) ?: return false
        val block = document().findById(blockId) ?: return false
        val assignment = block.effectiveStyleAssignment()
        if (assignment.overrides.isEmpty()) return false
        val updated = definition.copy(properties = definition.properties + assignment.overrides)
        return mutate(label) { session ->
            session.writeStylesheet(sheet.withStyle(updated))
            session.replaceBlocksInTree(listOf(blockId)) {
                it.withStyleAssignment(assignment.clearAllOverrides())
            }
        }
    }

    /**
     * Deletes a style under an **explicit** policy (§9, §37.22).
     *
     * There is no default. A delete that silently picked a policy would be exactly the "never silently lose
     * a color/format" failure the spec forbids — one policy loses the appearance, the other loses the
     * association, and only the user knows which they meant.
     *
     * Both branches are one transaction, even though [DeleteStylePolicy.PRESERVE_APPEARANCE] must also
     * rewrite the referencing content: the stylesheet edit and the content flattening are one user action.
     */
    fun deleteStyle(
        styleId: String,
        policy: DeleteStylePolicy,
        replacementStyleId: String? = null,
        label: String = "Delete style",
    ): Boolean {
        val sheet = stylesheet()
        val definition = sheet.style(styleId) ?: return false
        if (definition.builtIn) return false
        if (policy == DeleteStylePolicy.REASSIGN && sheet.style(replacementStyleId) == null) return false

        val doc = document()
        val consumers = buildList {
            doc.walk { block ->
                if (StyleResolver.chainOf(sheet, block.effectiveStyleAssignment().styleId)
                        .any { it.id == styleId }
                ) {
                    add(block.id)
                }
            }
        }
        return mutate(label) { session ->
            when (policy) {
                DeleteStylePolicy.PRESERVE_APPEARANCE -> session.replaceBlocksInTree(consumers) { block ->
                    val assignment = block.effectiveStyleAssignment()
                    block.withStyleAssignment(
                        assignment.detached(StyleResolver.rawAll(sheet, assignment, categoryOf(block))),
                    )
                }
                DeleteStylePolicy.REASSIGN -> session.replaceBlocksInTree(consumers) { block ->
                    block.withStyleAssignment(
                        block.effectiveStyleAssignment().copy(styleId = replacementStyleId),
                    )
                }
            }
            session.writeStylesheet(sheet.removeStyle(styleId))
        }
    }

    // ── Tokens ───────────────────────────────────────────────────────────────────────────────────

    fun createToken(token: DocumentToken, label: String = "Create variable"): Boolean =
        writeStylesheet(stylesheet().withToken(token), label)

    /** Renames a token. References are ids, so nothing breaks (§37.25). */
    fun renameToken(tokenId: String, name: String): Boolean {
        val sheet = stylesheet()
        val token = sheet.token(tokenId) ?: return false
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == token.name) return false
        return writeStylesheet(sheet.renameToken(tokenId, trimmed), "Rename variable")
    }

    /**
     * Changes a token's value. Every consumer updates without being rewritten (§37.26) — the same
     * indirection property style definitions have, for the same reason.
     */
    fun setTokenValue(tokenId: String, value: String, label: String = "Update variable"): Boolean {
        val sheet = stylesheet()
        val token = sheet.token(tokenId) ?: return false
        if (token.value == value) return false
        return writeStylesheet(sheet.withToken(token.copy(value = value)), label)
    }

    fun setTokenColor(tokenId: String, color: ColorValue, label: String = "Update variable"): Boolean =
        setTokenValue(tokenId, color.toHex(), label)

    /**
     * Deletes a token under an explicit resolution (§6, §25, §37.27–§37.28).
     *
     * [detach] true flattens every reference to the token's current literal value first, so no colour is
     * lost; false repoints them at [replacementId]. A token with references and neither resolution is
     * refused rather than deleted — that is the "cannot silently disappear" guarantee.
     */
    fun deleteToken(
        tokenId: String,
        detach: Boolean,
        replacementId: String? = null,
        label: String = "Delete variable",
    ): Boolean {
        val sheet = stylesheet()
        sheet.token(tokenId) ?: return false
        val referenced = sheet.stylesReferencing(tokenId).isNotEmpty() || contentReferences(tokenId)
        val next = when {
            detach -> sheet.detachAndRemoveToken(tokenId)
            replacementId != null -> sheet.replaceAndRemoveToken(tokenId, replacementId)
            referenced -> return false
            else -> sheet.removeToken(tokenId)
        }
        if (next == sheet) return false
        // Content-level references must be flattened too, or a paragraph bound to the token would render
        // from a dangling id after the stylesheet forgot it.
        val flattenTo: String? = sheet.token(tokenId)?.value?.takeIf { detach }
        val consumers = contentReferencing(tokenId)
        return mutate(label) { session ->
            if (consumers.isNotEmpty()) {
                session.replaceBlocksInTree(consumers) { block ->
                    if (block is DocumentPageBlock && block.geometry.backgroundTokenId == tokenId) {
                        val geometry = when {
                            flattenTo != null -> block.geometry.copy(
                                backgroundTokenId = null,
                                backgroundPaint = ColorValue.parse(flattenTo)?.let(ColorPaint::Flat)
                                    ?: block.geometry.backgroundPaint,
                                background = ColorValue.parse(flattenTo)?.toArgb() ?: block.geometry.background,
                            )
                            replacementId != null -> block.geometry.copy(backgroundTokenId = replacementId)
                            else -> block.geometry
                        }
                        block.copy(geometry = geometry)
                    } else {
                        val assignment = block.effectiveStyleAssignment()
                        val detachedColor = flattenTo?.let(ColorValue::parse)
                        val fillWasBound = assignment.overrides[StyleProperty.FILL.key]
                            ?.let { it is StyleValue.TokenRef && it.tokenId == tokenId } == true
                        val strokeWasBound = assignment.overrides[StyleProperty.STROKE_COLOR.key]
                            ?.let { it is StyleValue.TokenRef && it.tokenId == tokenId } == true
                        val rewritten = assignment.overrides.mapValues { (_, value) ->
                            if (value is StyleValue.TokenRef && value.tokenId == tokenId) {
                                if (flattenTo != null) StyleValue.Literal(flattenTo)
                                else StyleValue.TokenRef(replacementId!!)
                            } else {
                                value
                            }
                        }
                        val rewrittenAssignment = assignment.copy(overrides = rewritten)
                        if (block is ShapeBlock && detachedColor != null && (fillWasBound || strokeWasBound)) {
                            block.copy(
                                fillPaint = if (fillWasBound) ColorPaint.Flat(detachedColor) else block.fillPaint,
                                fillColor = if (fillWasBound) detachedColor.toArgb() else block.fillColor,
                                strokePaint = if (strokeWasBound) ColorPaint.Flat(detachedColor) else block.strokePaint,
                                strokeColor = if (strokeWasBound) detachedColor.toArgb() else block.strokeColor,
                                style = rewrittenAssignment,
                            )
                        } else {
                            block.withStyleAssignment(rewrittenAssignment)
                        }
                    }
                }
            }
            session.writeStylesheet(next)
        }
    }

    private fun contentReferencing(tokenId: String): List<String> = buildList {
        document().walk { block ->
            val binds = (block is DocumentPageBlock && block.geometry.backgroundTokenId == tokenId) ||
                block.effectiveStyleAssignment().overrides.values
                    .any { it is StyleValue.TokenRef && it.tokenId == tokenId }
            if (binds) add(block.id)
        }
    }

    private fun contentReferences(tokenId: String): Boolean = contentReferencing(tokenId).isNotEmpty()

    /** Binds a property to a token instead of a literal — the "use a variable" action. */
    fun bindToToken(
        blockIds: List<String>,
        property: StyleProperty,
        tokenId: String,
        label: String = "Use variable",
    ): Int {
        if (stylesheet().token(tokenId) == null) return 0
        return setOverride(blockIds, property, StyleValue.token(tokenId), label)
    }

    fun bindPageBackgroundToToken(
        pageIds: List<String>,
        tokenId: String,
        label: String = "Use page background variable",
    ): Int {
        val token = stylesheet().token(tokenId)?.takeIf { it.kind == TokenKind.COLOR } ?: return 0
        if (!token.domain.tokenizable) return 0
        val doc = document()
        val eligible = pageIds.filter { (doc.findById(it) as? DocumentPageBlock)?.geometry?.backgroundTokenId != tokenId }
        if (eligible.isEmpty()) return 0
        val changed = mutate(label) { session ->
            session.replaceBlocksInTree(eligible) { block ->
                (block as? DocumentPageBlock)?.copy(
                    geometry = block.geometry.copy(backgroundTokenId = tokenId),
                ) ?: block
            }
        }
        return if (changed) eligible.size else 0
    }

    // ── Colour and font application ──────────────────────────────────────────────────────────────

    /** Applies a text colour as a local override, one transaction across the selection. */
    fun applyTextColor(blockIds: List<String>, color: ColorValue): Int =
        setOverride(blockIds, StyleProperty.TEXT_COLOR, StyleValue.of(color), "Change text color")

    /** Applies a text highlight as a local override, one transaction across the selection. */
    fun applyHighlight(blockIds: List<String>, color: ColorValue): Int =
        setOverride(blockIds, StyleProperty.HIGHLIGHT, StyleValue.of(color), "Change highlight")

    /**
     * Applies a fill paint to shape blocks, one transaction.
     *
     * Writes [ShapeBlock.fillPaint] and keeps the legacy [ShapeBlock.fillColor] word in sync with the
     * paint's representative colour, so a build that predates Phase 3 still renders something sane rather
     * than the shape's original colour with a new gradient it cannot see.
     */
    fun applyShapeFill(blockIds: List<String>, paint: ColorPaint, label: String = "Change fill"): Int {
        val doc = document()
        val eligible = blockIds.filter { id ->
            val shape = doc.findById(id) as? ShapeBlock ?: return@filter false
            shape.fillPaint != paint ||
                (shape.effectiveStyleAssignment().overrides[StyleProperty.FILL.key] !is StyleValue.Literal)
        }
            .filter { doc.findById(it) is ShapeBlock }
        if (eligible.isEmpty()) return 0
        val changed = mutate(label) { session ->
            session.replaceBlocksInTree(eligible) { block ->
                (block as? ShapeBlock)?.let { shape ->
                    shape.copy(
                        fillPaint = paint,
                        fillColor = paint.representative().toArgb(),
                        style = shape.effectiveStyleAssignment().override(
                            StyleProperty.FILL,
                            StyleValue.of(paint.representative()),
                        ),
                    )
                } ?: block
            }
        }
        return if (changed) eligible.size else 0
    }

    /** Applies a stroke colour to shape blocks. Strokes stay flat in Phase 3. */
    fun applyShapeStroke(blockIds: List<String>, color: ColorValue, label: String = "Change stroke"): Int {
        val doc = document()
        val paint = ColorPaint.Flat(color)
        val eligible = blockIds.filter { id ->
            val shape = doc.findById(id) as? ShapeBlock ?: return@filter false
            shape.strokePaint != paint ||
                (shape.effectiveStyleAssignment().overrides[StyleProperty.STROKE_COLOR.key] !is StyleValue.Literal)
        }
        if (eligible.isEmpty()) return 0
        val changed = mutate(label) { session ->
            session.replaceBlocksInTree(eligible) { block ->
                (block as? ShapeBlock)?.let { shape ->
                    shape.copy(
                        strokePaint = paint,
                        strokeColor = color.toArgb(),
                        style = shape.effectiveStyleAssignment().override(
                            StyleProperty.STROKE_COLOR,
                            StyleValue.of(color),
                        ),
                    )
                } ?: block
            }
        }
        return if (changed) eligible.size else 0
    }

    /** Applies a flat or gradient page background without changing page geometry. */
    fun applyPageBackground(pageIds: List<String>, paint: ColorPaint, label: String = "Change page background"): Int {
        val doc = document()
        val eligible = pageIds.filter { id ->
            val page = doc.findById(id) as? DocumentPageBlock
            page != null && (page.geometry.backgroundPaint != paint || page.geometry.backgroundTokenId != null)
        }
        if (eligible.isEmpty()) return 0
        val changed = mutate(label) { session ->
            session.replaceBlocksInTree(eligible) { block ->
                (block as? DocumentPageBlock)?.copy(
                    geometry = block.geometry.copy(
                        backgroundPaint = paint,
                        background = paint.representative().toArgb(),
                        backgroundTokenId = null,
                    ),
                ) ?: block
            }
        }
        return if (changed) eligible.size else 0
    }

    /** Applies a font to the selection as a local override, one transaction (§23). */
    fun applyFont(blockIds: List<String>, identity: FontIdentity): Int =
        setOverride(blockIds, StyleProperty.FONT_FAMILY, StyleValue.of(identity), "Change font")

    /**
     * Changes a *style's* font, so every inheriting consumer follows without being rewritten (§23).
     *
     * This is the distinction §23 makes explicit: applying a font to a selection is direct formatting, and
     * changing a named style's font is a definition edit. Conflating them would make one of the two
     * impossible to express.
     */
    fun applyFontToStyle(styleId: String, identity: FontIdentity): Boolean =
        updateStyleProperty(styleId, StyleProperty.FONT_FAMILY, StyleValue.of(identity), "Change style font")

    // ── Internals ────────────────────────────────────────────────────────────────────────────────

    private fun writeStylesheet(next: Stylesheet, label: String): Boolean {
        if (next == stylesheet() && document().blocks.any { it is StylesheetBlock }) return false
        return mutate(label) { session -> session.writeStylesheet(next) }
    }

    private fun categoryOf(block: DocumentBlock): StyleCategory =
        if (block is ShapeBlock) StyleCategory.OBJECT else StyleCategory.TEXT

    private fun semanticRoleLabel(role: String): String = when {
        role == "paragraph" -> "paragraph"
        role == "quote" -> "quote"
        role == "code" -> "code block"
        role.startsWith("heading:") -> "heading ${role.substringAfter(':')}"
        else -> role
    }
}

data class StyleApplicationStatus(val enabled: Boolean, val reason: String?)

/**
 * Writes [sheet] into the session's document, creating the stylesheet block if it is missing.
 *
 * An extension on the session rather than a controller method, because it must run *inside* the host's
 * mutation lambda — that is what makes the stylesheet write part of the same transaction as any content
 * change accompanying it (which `deleteStyle` and `createStyleFromBlock` both need).
 *
 * The block is appended after authored content. Hosts explicitly exclude metadata blocks from rendering;
 * appending also preserves every existing content index, so adding the first style cannot perturb a drag,
 * caret restoration, or a command that is already addressing content by top-level position.
 */
fun BlockEditorSession.writeStylesheet(sheet: Stylesheet) {
    val existing = document.blocks.filterIsInstance<StylesheetBlock>().firstOrNull()
    // Reuse the existing block's id so the edit is a replace (one dirty row) rather than a
    // delete-plus-insert, and so a document that somehow stored a differently-named stylesheet keeps it.
    val block = StylesheetBlock.of(sheet).copy(id = existing?.id ?: StylesheetBlock.STYLESHEET_BLOCK_ID)
    if (existing != null) replaceBlock(block) else insertAfter(document.blocks.lastOrNull()?.id, block)
}

/**
 * Replaces each of [blockIds] anywhere in the tree via [transform], as part of the current mutation.
 *
 * Addresses through the nested tree rather than the top level, because a styled paragraph can live inside a
 * quote, a callout, a container, or a page's scene object — and a top-level-only rewrite would silently do
 * nothing to exactly the nested content the document host places on pages.
 */
fun BlockEditorSession.replaceBlocksInTree(
    blockIds: Collection<String>,
    transform: (DocumentBlock) -> DocumentBlock,
) {
    for (id in blockIds) {
        val current = document.blocks.findById(id) ?: continue
        val next = transform(current)
        // replaceBlock resolves through BlockTree.updateBlock, so it already reaches nested blocks and
        // dirties the correct top-level ancestor for persistence.
        if (next != current) replaceBlock(next)
    }
}
