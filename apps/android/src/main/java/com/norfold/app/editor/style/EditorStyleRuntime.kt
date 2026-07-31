package com.norfold.app.editor.style

import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.ShapeBlock
import com.norfold.app.domain.documentFontIds
import com.norfold.app.domain.documentPalette
import com.norfold.app.domain.effectiveBackgroundPaint
import com.norfold.app.domain.effectiveFill
import com.norfold.app.domain.effectiveStroke
import com.norfold.app.domain.findById
import com.norfold.app.domain.style.ColorPaint
import com.norfold.app.domain.style.ColorRole
import com.norfold.app.domain.style.ColorTarget
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.domain.style.FontIdentity
import com.norfold.app.domain.style.FontRegistry
import com.norfold.app.domain.style.FontSource
import com.norfold.app.domain.style.StyleCategory
import com.norfold.app.domain.style.StyleDefinition
import com.norfold.app.domain.style.StyleProperty
import com.norfold.app.domain.style.DocumentToken
import com.norfold.app.domain.style.TokenKind

/** Selection facts the shared styling runtime needs; host geometry and presentation remain outside it. */
data class EditorStyleSelection(
    val textBlockIds: List<String> = emptyList(),
    val shapeBlockIds: List<String> = emptyList(),
    val pageIds: List<String> = emptyList(),
    val selectedText: String = "",
)

/** Immutable Phase-3 state included in both host snapshots. */
data class EditorStyleUiState(
    val colorStudio: ColorStudioState = ColorStudioState.CLOSED,
    val fontBrowser: FontBrowserState = FontBrowserState(),
    val previewPaint: ColorPaint? = null,
    val styleNames: List<Pair<String, String>> = emptyList(),
    val documentColors: List<ColorValue> = emptyList(),
    val documentFontIds: List<String> = emptyList(),
    val recentColors: List<ColorValue> = emptyList(),
    val textTargetCount: Int = 0,
    val shapeTargetCount: Int = 0,
    val pageTargetCount: Int = 0,
    /** Explicit inspector facts; never infer the whole selection from its first item. */
    val sharedStyleLabel: String = "No selection",
    val textColorStateLabel: String = "—",
    val fontStateLabel: String = "—",
    val colorTokens: List<ColorTokenUiState> = emptyList(),
    val styleNameDraft: String = "",
    val tokenNameDraft: String = "",
)

data class ColorTokenUiState(val id: String, val name: String, val color: ColorValue, val description: String)

/**
 * One presentation-independent styling session mounted by both editor hosts.
 *
 * It owns Color Studio and Font Browser state, while [StyleController] remains the only document mutation
 * authority. Consequently a phone sheet becoming a tablet pane does not recreate a draft, and Flow and
 * Document cannot acquire different colour/font semantics.
 */
class EditorStyleRuntime(
    private val host: String,
    val controller: StyleController,
    registry: FontRegistry,
    private val selection: () -> EditorStyleSelection,
    private val onChanged: () -> Unit,
) {
    private var previewPaint: ColorPaint? = null
    private var styleNameDraft: String = ""
    private var tokenNameDraft: String = ""
    private var derivedFor: BlockDocument? = null
    private var cachedPalette: List<ColorValue> = emptyList()
    private var cachedFontIds: List<String> = emptyList()

    val colorStudio = ColorStudioEngine(object : ColorStudioHost {
        override fun preview(target: ColorTarget, paint: ColorPaint) {
            previewPaint = paint
            onChanged()
        }

        override fun commit(target: ColorTarget, paint: ColorPaint, label: String): Boolean {
            val changed = when (target.role) {
                ColorRole.TEXT -> controller.applyTextColor(target.targetIds, paint.representative()) > 0
                ColorRole.HIGHLIGHT -> controller.applyHighlight(target.targetIds, paint.representative()) > 0
                ColorRole.FILL -> controller.applyShapeFill(target.targetIds, paint, label) > 0
                ColorRole.STROKE -> controller.applyShapeStroke(target.targetIds, paint.representative(), label) > 0
                ColorRole.PAGE_BACKGROUND -> controller.applyPageBackground(target.targetIds, paint, label) > 0
                else -> false
            }
            if (changed) previewPaint = null
            onChanged()
            return changed
        }
    })

    var fontBrowser = FontBrowserEngine(registry)
        private set

    fun openColor(role: ColorRole): Boolean {
        val selected = selection()
        val ids = when (role) {
            ColorRole.TEXT, ColorRole.HIGHLIGHT -> selected.textBlockIds
            ColorRole.FILL, ColorRole.STROKE -> selected.shapeBlockIds
            ColorRole.PAGE_BACKGROUND -> selected.pageIds
            else -> emptyList()
        }
        if (ids.isEmpty()) return false
        val target = target(role, ids)
        val initial = initialPaint(role, ids)
        previewPaint = null
        colorStudio.open(target, initial)
        onChanged()
        return true
    }

    fun closeColor(cancelAdjustment: Boolean = true) {
        if (cancelAdjustment) colorStudio.cancel()
        colorStudio.close()
        previewPaint = null
        onChanged()
    }

    fun openFont(): Boolean {
        val selected = selection()
        val first = selected.textBlockIds.firstOrNull() ?: return false
        fontBrowser.open(controller.resolvedFont(first), selected.selectedText)
        onChanged()
        return true
    }

    fun closeFont() {
        onChanged()
    }

    fun applySelectedFont(): Int {
        val identity = fontBrowser.state().selected ?: return 0
        val changed = controller.applyFont(selection().textBlockIds, identity)
        if (changed > 0) fontBrowser.rememberRecent(identity)
        onChanged()
        return changed
    }

    fun applyFont(identity: FontIdentity): Int {
        fontBrowser.select(identity)
        return applySelectedFont()
    }

    /** Rebinds the browser after an import/download while preserving every user-facing browser value. */
    fun updateFontRegistry(registry: FontRegistry) {
        val previous = fontBrowser
        val state = previous.state()
        fontBrowser = FontBrowserEngine(registry).also { next ->
            next.restoreFavorites(previous.favoriteKeys())
            next.restoreRecent(previous.recentKeys())
            next.open(state.selected, state.previewText.takeIf { state.usingSelectedText }.orEmpty())
            next.setTab(state.tab)
            next.setQuery(state.query)
            next.setCategory(state.category)
            next.setRole(state.role)
            next.setScript(state.script)
            next.setVariableOnly(state.variableOnly)
            next.setPreviewText(state.previewText, state.usingSelectedText)
        }
        onChanged()
    }

    fun applyNamedStyle(styleId: String): Int {
        val chosen = controller.stylesheet().style(styleId) ?: return 0
        val ids = when (chosen.category) {
            StyleCategory.TEXT -> selection().textBlockIds
            StyleCategory.OBJECT -> selection().shapeBlockIds
        }
        return controller.applyStyle(ids, styleId).also { onChanged() }
    }

    fun clearFormatting(): Int =
        controller.clearOverrides(selection().textBlockIds + selection().shapeBlockIds).also { onChanged() }

    fun createStyle(name: String): String? {
        val selected = selection()
        val first = selected.textBlockIds.firstOrNull() ?: selected.shapeBlockIds.firstOrNull() ?: return null
        return controller.createStyleFromBlock(first, name).also { onChanged() }
    }

    fun setStyleNameDraft(value: String) { styleNameDraft = value.take(80); onChanged() }

    fun setTokenNameDraft(value: String) { tokenNameDraft = value.take(80); onChanged() }

    fun createStyleFromDraft(): String? = createStyle(styleNameDraft).also {
        if (it != null) styleNameDraft = ""
        onChanged()
    }

    fun updateStyleFromSelection(styleId: String): Boolean {
        val selected = selection()
        val first = selected.textBlockIds.firstOrNull() ?: selected.shapeBlockIds.firstOrNull() ?: return false
        return controller.updateStyleFromBlock(first, styleId).also { onChanged() }
    }

    fun detachStyle(): Int =
        controller.detachStyle(selection().textBlockIds + selection().shapeBlockIds).also { onChanged() }

    fun createColorToken(name: String): String? {
        val target = colorStudio.state().target ?: return null
        val token = DocumentToken.color(name.trim(), colorStudio.state().color(), target.domain)
        if (token.name.isBlank() || !controller.createToken(token)) return null
        onChanged()
        return token.id
    }

    fun createColorTokenFromDraft(): String? = createColorToken(tokenNameDraft).also {
        if (it != null) tokenNameDraft = ""
        onChanged()
    }

    fun updateColorToken(tokenId: String): Boolean =
        controller.setTokenColor(tokenId, colorStudio.state().color()).also { onChanged() }

    fun applyColorToken(tokenId: String): Int {
        val selected = selection()
        val (ids, property) = when (colorStudio.state().target?.role) {
            ColorRole.TEXT -> selected.textBlockIds to StyleProperty.TEXT_COLOR
            ColorRole.HIGHLIGHT -> selected.textBlockIds to StyleProperty.HIGHLIGHT
            ColorRole.FILL -> selected.shapeBlockIds to StyleProperty.FILL
            ColorRole.STROKE -> selected.shapeBlockIds to StyleProperty.STROKE_COLOR
            else -> return 0
        }
        return controller.bindToToken(ids, property, tokenId).also { onChanged() }
    }

    fun uiState(): EditorStyleUiState {
        val selected = selection()
        val document = controller.document()
        if (derivedFor !== document) {
            derivedFor = document
            cachedPalette = document.documentPalette()
            cachedFontIds = document.documentFontIds()
        }
        val styleTargets = selected.textBlockIds + selected.shapeBlockIds
        val shared = controller.sharedStyle(styleTargets)
        val sharedName = shared.styleId?.let { id -> controller.stylesheet().style(id)?.name }
        return EditorStyleUiState(
            colorStudio = colorStudio.state(),
            fontBrowser = fontBrowser.state(),
            previewPaint = previewPaint,
            styleNames = controller.stylesheet().styles.map(StyleDefinition::id).zip(
                controller.stylesheet().styles.map(StyleDefinition::name),
            ),
            documentColors = cachedPalette,
            documentFontIds = cachedFontIds,
            recentColors = colorStudio.recentColors(),
            textTargetCount = selected.textBlockIds.size,
            shapeTargetCount = selected.shapeBlockIds.size,
            pageTargetCount = selected.pageIds.size,
            sharedStyleLabel = when {
                styleTargets.isEmpty() -> "No selection"
                shared.isMixed -> "Mixed styles"
                sharedName != null -> "Style: $sharedName"
                else -> "No named style"
            },
            textColorStateLabel = controller
                .propertyStateAcross(selected.textBlockIds, StyleProperty.TEXT_COLOR)
                .accessibleLabel(),
            fontStateLabel = controller
                .propertyStateAcross(selected.textBlockIds, StyleProperty.FONT_FAMILY)
                .accessibleLabel(),
            colorTokens = controller.stylesheet().tokensOf(TokenKind.COLOR).mapNotNull { token ->
                token.colorValue()?.let { color ->
                    ColorTokenUiState(token.id, token.name, color, token.accessibleLabel())
                }
            },
            styleNameDraft = styleNameDraft,
            tokenNameDraft = tokenNameDraft,
        )
    }

    private fun target(role: ColorRole, ids: List<String>): ColorTarget = when (role) {
        ColorRole.TEXT -> ColorTargets.flowTextColor(ids).copy(host = host)
        ColorRole.HIGHLIGHT -> ColorTargets.flowHighlight(ids).copy(host = host)
        ColorRole.FILL -> ColorTargets.documentShapeFill(ids).copy(host = host)
        ColorRole.STROKE -> ColorTargets.documentShapeStroke(ids).copy(host = host)
        ColorRole.PAGE_BACKGROUND -> ColorTargets.documentPageBackground(ids).copy(host = host)
        else -> error("Unsupported editor color role $role")
    }

    private fun initialPaint(role: ColorRole, ids: List<String>): ColorPaint {
        val document = controller.document()
        return when (role) {
            ColorRole.TEXT -> controller.propertyStateAcross(ids, StyleProperty.TEXT_COLOR).value
                ?.let(ColorValue::parse)?.let(ColorPaint::Flat) ?: ColorPaint.Flat(ColorValue.BLACK)
            ColorRole.HIGHLIGHT -> controller.propertyStateAcross(ids, StyleProperty.HIGHLIGHT).value
                ?.let(ColorValue::parse)?.let(ColorPaint::Flat) ?: ColorPaint.Flat(ColorValue.TRANSPARENT)
            ColorRole.FILL -> (document.findById(ids.first()) as? ShapeBlock)?.effectiveFill()
                ?: ColorPaint.Flat(ColorValue.BLACK)
            ColorRole.STROKE -> (document.findById(ids.first()) as? ShapeBlock)?.effectiveStroke()
                ?: ColorPaint.Flat(ColorValue.BLACK)
            ColorRole.PAGE_BACKGROUND -> (document.findById(ids.first()) as? DocumentPageBlock)
                ?.geometry?.effectiveBackgroundPaint() ?: ColorPaint.Flat(ColorValue.WHITE)
            else -> ColorPaint.Flat(ColorValue.BLACK)
        }
    }

    companion object {
        val EMPTY_REGISTRY = FontRegistry(
            listOf(
                com.norfold.app.domain.style.FontFamilyRecord(
                    id = "sans-serif",
                    name = "System Sans",
                    source = FontSource.SYSTEM,
                    category = com.norfold.app.domain.style.FontCategory.SANS,
                ),
            ),
        )
    }
}
