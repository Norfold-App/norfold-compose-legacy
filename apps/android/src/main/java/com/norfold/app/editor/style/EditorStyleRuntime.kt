package com.norfold.app.editor.style

import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.ShapeBlock
import com.norfold.app.domain.documentFontIds
import com.norfold.app.domain.documentPalette
import com.norfold.app.domain.findById
import com.norfold.app.domain.style.ColorPaint
import com.norfold.app.domain.style.ColorRole
import com.norfold.app.domain.style.ColorTarget
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.domain.style.FontIdentity
import com.norfold.app.domain.style.FontRegistry
import com.norfold.app.domain.style.FontSource
import com.norfold.app.domain.style.MixedState
import com.norfold.app.domain.style.StyleCategory
import com.norfold.app.domain.style.StyleDefinition
import com.norfold.app.domain.style.StyleProperty
import com.norfold.app.domain.style.DocumentToken
import com.norfold.app.domain.style.TokenKind
import com.norfold.app.domain.style.resolvedPageBackground
import com.norfold.app.domain.style.resolvedShapeFill
import com.norfold.app.domain.style.resolvedShapeStroke

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
    val styleNames: List<NamedStyleUiState> = emptyList(),
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
data class NamedStyleUiState(val id: String, val name: String, val enabled: Boolean, val reason: String? = null)

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

    /** Shared action-boundary adapter used by Flow and Document presentation code. */
    val colorInteractions = ColorStudioInteractionController(colorStudio)

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
        colorStudio.open(target, initial.paint, mixed = initial.mixed)
        onChanged()
        return true
    }

    fun closeColor(cancelAdjustment: Boolean = true) {
        if (cancelAdjustment) colorInteractions.cancel()
        colorStudio.close()
        previewPaint = null
        onChanged()
    }

    /**
     * A sheet-to-pane re-presentation cancels only an unfinished preview and preserves the Studio's
     * selected view, target, active stop, and every action already committed to canonical History.
     */
    fun onFormFactorChanged() {
        colorInteractions.onFormFactorChanged()
    }

    fun openFont(): Boolean {
        val selected = selection()
        if (selected.textBlockIds.isEmpty()) return false
        val fonts = selected.textBlockIds.map(controller::resolvedFont)
        val mixed = fonts.distinct().size > 1
        fontBrowser.open(fonts.distinct().singleOrNull(), selected.selectedText, mixed)
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
            next.open(state.selected, state.previewText.takeIf { state.usingSelectedText }.orEmpty(), state.mixedFonts)
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
        val target = colorStudio.state().target ?: return 0
        val token = controller.stylesheet().token(tokenId) ?: return 0
        val color = token.colorValue() ?: return 0
        val changed = when (target.role) {
            ColorRole.TEXT -> controller.bindToToken(target.targetIds, StyleProperty.TEXT_COLOR, tokenId)
            ColorRole.HIGHLIGHT -> controller.bindToToken(target.targetIds, StyleProperty.HIGHLIGHT, tokenId)
            ColorRole.FILL -> controller.bindToToken(target.targetIds, StyleProperty.FILL, tokenId)
            ColorRole.STROKE -> controller.bindToToken(target.targetIds, StyleProperty.STROKE_COLOR, tokenId)
            ColorRole.PAGE_BACKGROUND -> controller.bindPageBackgroundToToken(target.targetIds, tokenId)
            else -> 0
        }
        if (changed > 0) colorStudio.adoptCommittedPaint(ColorPaint.Flat(color))
        onChanged()
        return changed
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
            styleNames = controller.stylesheet().styles.map { style ->
                val ids = if (style.category == StyleCategory.TEXT) selected.textBlockIds else selected.shapeBlockIds
                val status = controller.styleApplicationStatus(ids, style.id)
                NamedStyleUiState(style.id, style.name, status.enabled, status.reason)
            },
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

    private fun initialPaint(role: ColorRole, ids: List<String>): PaintSelection {
        val document = controller.document()
        val sheet = controller.stylesheet()
        return when (role) {
            ColorRole.TEXT, ColorRole.HIGHLIGHT -> {
                val property = if (role == ColorRole.TEXT) StyleProperty.TEXT_COLOR else StyleProperty.HIGHLIGHT
                val state = controller.propertyStateAcross(ids, property)
                val fallback = if (role == ColorRole.TEXT) ColorValue.BLACK else ColorValue.TRANSPARENT
                PaintSelection(
                    state.value?.let(ColorValue::parse)?.let(ColorPaint::Flat) ?: ColorPaint.Flat(fallback),
                    mixed = state.state == MixedState.MIXED,
                )
            }
            ColorRole.FILL -> paintSelection(ids.mapNotNull { (document.findById(it) as? ShapeBlock)?.let { shape -> resolvedShapeFill(shape, sheet) } })
            ColorRole.STROKE -> paintSelection(ids.mapNotNull { (document.findById(it) as? ShapeBlock)?.let { shape -> resolvedShapeStroke(shape, sheet) } })
            ColorRole.PAGE_BACKGROUND -> paintSelection(ids.mapNotNull { (document.findById(it) as? DocumentPageBlock)?.let { page -> resolvedPageBackground(page, sheet) } })
            else -> PaintSelection(ColorPaint.Flat(ColorValue.BLACK), mixed = false)
        }
    }

    private fun paintSelection(paints: List<ColorPaint>): PaintSelection {
        val distinct = paints.distinct()
        return PaintSelection(
            paint = distinct.singleOrNull() ?: ColorPaint.Flat(ColorValue.TRANSPARENT),
            mixed = distinct.size > 1,
        )
    }

    private data class PaintSelection(val paint: ColorPaint, val mixed: Boolean)

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
