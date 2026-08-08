@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.norfold.app.ui.editor.style

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.norfold.app.domain.style.ColorHarmony
import com.norfold.app.domain.style.ColorPaint
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.domain.style.ColorVisionDeficiency
import com.norfold.app.domain.style.CvdCheck
import com.norfold.app.domain.style.GradientKind
import com.norfold.app.domain.style.FontCategory
import com.norfold.app.domain.style.FontIdentity
import com.norfold.app.domain.style.FontRole
import com.norfold.app.domain.style.FontScript
import com.norfold.app.editor.style.ColorField
import com.norfold.app.editor.style.ColorStudioState
import com.norfold.app.editor.style.ColorStudioView
import com.norfold.app.editor.style.EditorStyleUiState
import com.norfold.app.editor.style.FontBrowserState
import com.norfold.app.editor.style.FontBrowserTab
import com.norfold.app.editor.style.FontRowState
import com.norfold.app.editor.style.FontBrowserSection
import com.norfold.app.ui.fonts.OfflineFontLibrary

/** Actions are host-funnelled; this shared surface never mutates a document directly. */
class EditorStyleSurfaceActions(
    val dismiss: (commit: Boolean) -> Unit,
    val setColorView: (ColorStudioView) -> Unit,
    val draftColor: (ColorField, String) -> Unit,
    val commitColorDraft: (ColorField) -> Unit,
    val discardColorDraft: (ColorField) -> Unit,
    val beginColorAdjustment: () -> Unit,
    val previewColor: (ColorValue) -> Unit,
    val previewAlpha: (Float) -> Unit,
    val finishColorAdjustment: (String) -> Unit,
    val cancelColorAdjustment: () -> Unit,
    val chooseColor: (ColorValue) -> Unit,
    val toggleGradient: (Boolean) -> Unit,
    val addGradientStop: () -> Unit,
    val selectGradientStop: (String) -> Unit,
    val removeGradientStop: () -> Unit,
    val setGradientKind: (GradientKind) -> Unit,
    val moveGradientStop: (Float) -> Unit,
    val setGradientStopOpacity: (Float) -> Unit,
    val setGradientAngle: (Float) -> Unit,
    val reverseGradient: () -> Unit,
    val reorderGradientStop: (Int) -> Unit,
    val setFontQuery: (String) -> Unit,
    val setFontTab: (FontBrowserTab) -> Unit,
    val setFontCategory: (FontCategory?) -> Unit,
    val setFontRole: (FontRole?) -> Unit,
    val setFontScript: (FontScript?) -> Unit,
    val setVariableOnly: (Boolean) -> Unit,
    val selectFont: (FontIdentity) -> Unit,
    val toggleFavorite: (FontIdentity) -> Unit,
    val importFont: () -> Unit,
    val removeImportedFont: (FontIdentity) -> Unit,
    val applyStyle: (String) -> Unit,
    val setStyleNameDraft: (String) -> Unit,
    val createStyle: () -> Unit,
    val updateStyle: (String) -> Unit,
    val detachStyle: () -> Unit,
    val clearFormatting: () -> Unit,
    val setTokenNameDraft: (String) -> Unit,
    val createColorToken: () -> Unit,
    val updateColorToken: (String) -> Unit,
    val applyColorToken: (String) -> Unit,
)

@Composable
fun EditorStyleSurface(
    surfaceId: String,
    state: EditorStyleUiState,
    fontRows: List<FontRowState>,
    fontSections: List<FontBrowserSection> = emptyList(),
    actions: EditorStyleSurfaceActions,
    modifier: Modifier = Modifier,
    onOpenGuide: (() -> Unit)? = null,
) {
    Surface(modifier, shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp) {
        when (surfaceId) {
            "editor.surface.colorStudio" -> ColorStudioPanel(state, actions, onOpenGuide)
            "editor.surface.fontBrowser" -> FontBrowserPanel(state.fontBrowser, fontRows, fontSections, actions, onOpenGuide)
            "editor.surface.namedStyles" -> NamedStylesPanel(state, actions, onOpenGuide)
        }
    }
}

@Composable
private fun ColorStudioPanel(state: EditorStyleUiState, actions: EditorStyleSurfaceActions, onOpenGuide: (() -> Unit)?) {
    val studio = state.colorStudio
    val requiresCommonPaint = setOf(
        ColorStudioView.CURRENT,
        ColorStudioView.SPECTRUM,
        ColorStudioView.WHEEL,
        ColorStudioView.SLIDERS,
        ColorStudioView.GRADIENT,
        ColorStudioView.HARMONIES,
        ColorStudioView.CONTRAST,
        ColorStudioView.VISION,
    )
    val scheme = MaterialTheme.colorScheme
    val themeColors = listOf(
        scheme.primary, scheme.secondary, scheme.tertiary, scheme.error,
        scheme.surface, scheme.surfaceVariant, scheme.onSurface, scheme.outline,
    ).map { color -> ColorValue.fromArgb(color.toArgb().toLong() and 0xFFFFFFFFL) }
    Column(
        Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ToolHeader("Color Studio", studio.accessibleLabel(), { actions.dismiss(false) }, onOpenGuide)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ColorStudioView.entries.filter { view -> studio.target?.let(view::isAvailableFor) != false }.forEach { view ->
                FilterChip(
                    selected = studio.view == view,
                    onClick = { actions.setColorView(view) },
                    label = { Text(view.label) },
                    enabled = !studio.isMixed || view !in requiresCommonPaint,
                )
            }
        }
        if (studio.isMixed) {
            Surface(Modifier.fillMaxWidth(), color = scheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Mixed", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Choose a swatch, document color, theme color, variable, or enter a complete HEX value to establish one common color.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else {
            Surface(
                Modifier.fillMaxWidth(),
                color = Color(studio.color().toArgb()),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    studio.color().accessibleLabel(),
                    modifier = Modifier.padding(18.dp),
                    color = if (studio.color().relativeLuminance() > 0.45) Color.Black else Color.White,
                )
            }
        }

        if (studio.isMixed && studio.view in requiresCommonPaint) {
            Text(
                "This relative control is unavailable while the selection is mixed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ColorFields(studio, actions)
        } else when (studio.view) {
            ColorStudioView.SWATCHES, ColorStudioView.RECENT, ColorStudioView.DOCUMENT,
            ColorStudioView.THEME, ColorStudioView.CURRENT -> {
                val colors = when (studio.view) {
                    ColorStudioView.RECENT -> state.recentColors
                    ColorStudioView.DOCUMENT -> state.documentColors
                    ColorStudioView.THEME -> themeColors
                    ColorStudioView.CURRENT -> listOf(studio.color())
                    else -> DEFAULT_SWATCHES
                }.ifEmpty { DEFAULT_SWATCHES }
                Swatches(colors, actions.chooseColor, studio.color().takeUnless { studio.isMixed })
            }
            ColorStudioView.SLIDERS -> ColorFields(studio, actions)
            ColorStudioView.SPECTRUM -> {
                SpectrumPicker(
                    current = studio.color(),
                    onTap = actions.chooseColor,
                    onBegin = actions.beginColorAdjustment,
                    onPreview = actions.previewColor,
                    onFinish = { actions.finishColorAdjustment("Change color") },
                    onCancel = actions.cancelColorAdjustment,
                )
                ColorFields(studio, actions)
            }
            ColorStudioView.WHEEL -> {
                HuePicker(
                    current = studio.color(),
                    onTap = actions.chooseColor,
                    onBegin = actions.beginColorAdjustment,
                    onPreview = actions.previewColor,
                    onFinish = { actions.finishColorAdjustment("Change hue") },
                    onCancel = actions.cancelColorAdjustment,
                )
                ColorFields(studio, actions)
            }
            ColorStudioView.GRADIENT -> GradientEditor(studio, actions)
            ColorStudioView.HARMONIES -> {
                Text("Color harmonies", style = MaterialTheme.typography.titleSmall)
                Swatches(ColorHarmony.entries.flatMap { it.generate(studio.color()).take(3) }.distinct(), actions.chooseColor)
            }
            ColorStudioView.CONTRAST -> {
                Text("Contrast", style = MaterialTheme.typography.titleSmall)
                val onWhite = com.norfold.app.domain.style.ContrastAnalysis.analyze(studio.color(), ColorValue.WHITE)
                val onBlack = com.norfold.app.domain.style.ContrastAnalysis.analyze(studio.color(), ColorValue.BLACK)
                Text("On white: ${onWhite.summary()}")
                Text("On black: ${onBlack.summary()}")
                ColorFields(studio, actions)
            }
            ColorStudioView.VISION -> {
                Text("Color-vision preview", style = MaterialTheme.typography.titleSmall)
                ColorVisionDeficiency.entries.forEach { deficiency ->
                    val simulated = deficiency.simulate(studio.color())
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Swatch(simulated, selected = false) {}
                        Column {
                            Text(deficiency.label, fontWeight = FontWeight.SemiBold)
                            Text(simulated.accessibleLabel(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                val collisions = CvdCheck.report((listOf(studio.color()) + state.documentColors).distinct())
                if (collisions.isEmpty()) {
                    Text("No simulated palette collisions found.", style = MaterialTheme.typography.bodySmall)
                } else {
                    collisions.take(4).forEach { Text(it.summary(), style = MaterialTheme.typography.bodySmall) }
                }
            }
            ColorStudioView.TOKENS -> {
                Text("Document variables", style = MaterialTheme.typography.titleSmall)
                state.colorTokens.forEach { token ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Swatch(token.color, selected = !studio.isMixed && token.color == studio.color()) { actions.chooseColor(token.color) }
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            Text(token.name, fontWeight = FontWeight.SemiBold)
                            Text(token.description, style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { actions.applyColorToken(token.id) }) { Text("Use") }
                        TextButton(onClick = { actions.updateColorToken(token.id) }, enabled = !studio.isMixed) { Text("Update") }
                    }
                }
                OutlinedTextField(
                    value = state.tokenNameDraft,
                    onValueChange = actions.setTokenNameDraft,
                    label = { Text("New variable name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = {
                    if (state.tokenNameDraft.isNotBlank()) actions.createColorToken()
                }, enabled = !studio.isMixed) { Text("Create from current color") }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { actions.dismiss(false) }) { Text("Cancel") }
            Button(onClick = { actions.dismiss(true) }) { Text("Done") }
        }
    }
}

@Composable
private fun ColorFields(studio: ColorStudioState, actions: EditorStyleSurfaceActions) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ColorField.entries.forEach { field ->
            var wasFocused by remember(field) { mutableStateOf(false) }
            OutlinedTextField(
                value = studio.fieldText(field),
                onValueChange = { actions.draftColor(field, it) },
                label = { Text(if (studio.isMixed && field == ColorField.HEX) "Common HEX" else field.label) },
                singleLine = true,
                enabled = !studio.isMixed || field == ColorField.HEX,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { actions.commitColorDraft(field) }),
                modifier = Modifier
                    .size(width = if (field == ColorField.HEX) 152.dp else 92.dp, height = 64.dp)
                    .onFocusChanged { focus ->
                        if (wasFocused && !focus.isFocused) actions.commitColorDraft(field)
                        wasFocused = focus.isFocused
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.Escape && event.type == KeyEventType.KeyUp) {
                            actions.discardColorDraft(field)
                            true
                        } else {
                            false
                        }
                    },
            )
        }
    }
    if (!studio.isGradient && !studio.isMixed) {
        Text("Opacity ${kotlin.math.round(studio.color().alphaFraction * 100)}%")
        androidx.compose.material3.Slider(
            value = studio.color().alphaFraction,
            onValueChange = actions.previewAlpha,
            onValueChangeFinished = { actions.finishColorAdjustment("Change opacity") },
            valueRange = 0f..1f,
            modifier = Modifier.semantics { contentDescription = "Color opacity" },
        )
    }
    TextButton(
        onClick = { studio.draft?.field?.let(actions.commitColorDraft) },
        enabled = studio.draft != null,
    ) { Text("Use entered value") }
}

@Composable
private fun GradientEditor(studio: ColorStudioState, actions: EditorStyleSurfaceActions) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(studio.isGradient, { actions.toggleGradient(!studio.isGradient) }, label = { Text("Gradient") })
        Text(studio.paint.describe(), style = MaterialTheme.typography.bodySmall)
    }
    val gradient = (studio.paint as? ColorPaint.Ramp)?.gradient
    gradient?.let { ramp ->
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            studio.target?.gradientKinds.orEmpty().forEach { kind ->
                FilterChip(ramp.kind == kind, { actions.setGradientKind(kind) }, label = { Text(kind.label) })
            }
        }
        Text("${ramp.kind.name.lowercase().replaceFirstChar(Char::titlecase)} · ${ramp.stops.size} stops")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ramp.sortedStops.forEach { stop ->
                val selected = studio.activeStopId == stop.id
                Box(
                    Modifier
                        .size(if (selected) 44.dp else 36.dp)
                        .clip(CircleShape)
                        .background(Color(stop.color.toArgb()))
                        .clickable { actions.selectGradientStop(stop.id) }
                        .semantics { contentDescription = "Gradient stop ${stop.color.accessibleLabel()}" },
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(actions.addGradientStop, label = { Text("Add stop") })
            AssistChip(actions.removeGradientStop, label = { Text("Remove stop") })
            AssistChip(actions.reverseGradient, label = { Text("Reverse") })
            AssistChip({ actions.reorderGradientStop(-1) }, label = { Text("Move left") })
            AssistChip({ actions.reorderGradientStop(1) }, label = { Text("Move right") })
        }
        val active = studio.activeStopId?.let(ramp::stopById)
        active?.let { stop ->
            Text("Stop position ${kotlin.math.round(stop.position * 100)}% · opacity ${kotlin.math.round(stop.opacity * 100)}%")
            androidx.compose.material3.Slider(
                value = stop.position,
                onValueChange = actions.moveGradientStop,
                onValueChangeFinished = { actions.finishColorAdjustment("Move gradient stop") },
                valueRange = 0f..1f,
            )
            androidx.compose.material3.Slider(
                value = stop.opacity,
                onValueChange = actions.setGradientStopOpacity,
                onValueChangeFinished = { actions.finishColorAdjustment("Change gradient stop opacity") },
                valueRange = 0f..1f,
                modifier = Modifier.semantics { contentDescription = "Gradient stop opacity" },
            )
        }
        if (ramp.kind == GradientKind.LINEAR || ramp.kind == GradientKind.CONIC) {
            Text("Angle ${kotlin.math.round(ramp.angle)}°")
            androidx.compose.material3.Slider(
                value = ramp.angle,
                onValueChange = actions.setGradientAngle,
                onValueChangeFinished = { actions.finishColorAdjustment("Change gradient angle") },
                valueRange = 0f..360f,
            )
        }
        ColorFields(studio, actions)
    }
}

@Composable
private fun Swatches(colors: List<ColorValue>, onChoose: (ColorValue) -> Unit, selected: ColorValue? = null) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEach { color ->
            Swatch(color, selected == color) { onChoose(color) }
        }
    }
}

@Composable
private fun Swatch(color: ColorValue, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color(color.toArgb()))
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
            .clickable(onClick = onClick)
            .semantics { contentDescription = color.accessibleLabel() + if (selected) ", selected" else "" },
    )
}

@Composable
private fun SpectrumPicker(
    current: ColorValue,
    onTap: (ColorValue) -> Unit,
    onBegin: () -> Unit,
    onPreview: (ColorValue) -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
) {
    val hue = current.toHsv().hue
    Canvas(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 180.dp, max = 220.dp)
            .clip(RoundedCornerShape(16.dp))
            .pointerInput(hue, current.alpha) {
                detectTapGestures { point ->
                    onTap(ColorValue.fromHsv(hue, point.x / size.width, 1f - point.y / size.height, current.alphaFraction))
                }
            }
            .pointerInput(hue) {
                fun pick(point: Offset) {
                    onPreview(ColorValue.fromHsv(hue, point.x / size.width, 1f - point.y / size.height, current.alphaFraction))
                }
                detectDragGestures(
                    onDragStart = { point -> onBegin(); pick(point) },
                    onDragEnd = onFinish,
                    onDragCancel = onCancel,
                ) { change, _ -> pick(change.position) }
            },
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color(ColorValue.fromHsv(hue, 1f, 1f).toArgb()))))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
    }
}

@Composable
private fun HuePicker(
    current: ColorValue,
    onTap: (ColorValue) -> Unit,
    onBegin: () -> Unit,
    onPreview: (ColorValue) -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
) {
    val hsl = current.toHsl()
    val hueColors = (0..6).map { Color(ColorValue.fromHsl(it * 60f, 1f, .5f).toArgb()) }
    Canvas(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp, max = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .pointerInput(current) {
                detectTapGestures { point ->
                    onTap(ColorValue.fromHsl(point.x / size.width * 360f, hsl.saturation, hsl.lightness, current.alphaFraction))
                }
            }
            .pointerInput(current) {
                fun pick(point: Offset) {
                    onPreview(ColorValue.fromHsl(point.x / size.width * 360f, hsl.saturation, hsl.lightness, current.alphaFraction))
                }
                detectDragGestures(
                    onDragStart = { point -> onBegin(); pick(point) },
                    onDragEnd = onFinish,
                    onDragCancel = onCancel,
                ) { change, _ -> pick(change.position) }
            },
    ) { drawRect(Brush.horizontalGradient(hueColors)) }
}

@Composable
private fun FontBrowserPanel(
    state: FontBrowserState,
    rows: List<FontRowState>,
    sections: List<FontBrowserSection>,
    actions: EditorStyleSurfaceActions,
    onOpenGuide: (() -> Unit)?,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ToolHeader(
            "Font Browser",
            if (state.mixedFonts) "Mixed fonts · ${rows.size} matching families" else "${rows.size} matching families",
            { actions.dismiss(false) },
            onOpenGuide,
        )
        sections.forEach { section ->
            Text(section.title, style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                section.families.forEach { family ->
                    AssistChip(
                        onClick = { actions.selectFont(family.identity) },
                        label = { Text(family.name, fontFamily = OfflineFontLibrary.fontFor(family.identity)) },
                    )
                }
            }
        }
        Text("More fonts", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = state.query,
            onValueChange = actions.setFontQuery,
            label = { Text("Search fonts") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FontBrowserTab.entries.forEach { tab ->
                FilterChip(state.tab == tab, { actions.setFontTab(tab) }, label = { Text(tab.label) })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(state.category == null, { actions.setFontCategory(null) }, label = { Text("All") })
            FontCategory.entries.forEach { category ->
                FilterChip(state.category == category, { actions.setFontCategory(category) }, label = { Text(category.label) })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(!state.variableOnly, { actions.setVariableOnly(false) }, label = { Text("Static + variable") })
            FilterChip(state.variableOnly, { actions.setVariableOnly(true) }, label = { Text("Variable") })
            FilterChip(
                state.script == FontScript.BENGALI,
                { actions.setFontScript(if (state.script == FontScript.BENGALI) null else FontScript.BENGALI) },
                label = { Text("Bangla") },
            )
            FontRole.entries.forEach { role ->
                FilterChip(state.role == role, { actions.setFontRole(if (state.role == role) null else role) }, label = { Text(role.label) })
            }
        }
        Text("Preview: ${state.previewText}", style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
            items(rows, key = { it.record.identity.encode() }) { row ->
                val family = OfflineFontLibrary.resolve(
                    com.norfold.app.domain.style.FontRequest(row.record.identity),
                    row.previewText,
                ).family
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (row.selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable { actions.selectFont(row.record.identity) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(row.record.name, fontFamily = family, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${row.stateLabel()}${if (row.selected) " · Selected" else ""} · ${row.record.category.label} · ${row.record.license.spdx}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(row.previewText, fontFamily = family, style = MaterialTheme.typography.bodyMedium)
                        row.substitutionLabel?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(
                        onClick = { actions.toggleFavorite(row.record.identity) },
                        modifier = Modifier.semantics {
                            contentDescription = if (row.favorite) "Remove ${row.record.name} from favorites" else "Add ${row.record.name} to favorites"
                        },
                    ) {
                        Text(if (row.favorite) "★" else "☆")
                    }
                    if (row.record.source == com.norfold.app.domain.style.FontSource.IMPORTED) {
                        TextButton(onClick = { actions.removeImportedFont(row.record.identity) }) { Text("Remove") }
                    }
                }
                HorizontalDivider()
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = actions.importFont) { Text("Import TTF/OTF") }
            TextButton(onClick = { actions.dismiss(false) }) { Text("Done") }
        }
    }
}

@Composable
private fun NamedStylesPanel(state: EditorStyleUiState, actions: EditorStyleSurfaceActions, onOpenGuide: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ToolHeader("Named Styles", "Styles inherit; local overrides remain explicit", { actions.dismiss(false) }, onOpenGuide)
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(state.sharedStyleLabel, fontWeight = FontWeight.SemiBold)
                Text("Text color: ${state.textColorStateLabel}", style = MaterialTheme.typography.bodySmall)
                Text("Font: ${state.fontStateLabel}", style = MaterialTheme.typography.bodySmall)
            }
        }
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(state.styleNames, key = { it.id }) { item ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.bodyLarge)
                        item.reason?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    }
                    TextButton(onClick = { actions.applyStyle(item.id) }, enabled = item.enabled) { Text("Apply") }
                    TextButton(onClick = { actions.updateStyle(item.id) }) { Text("Update") }
                }
            }
        }
        OutlinedTextField(
            state.styleNameDraft,
            actions.setStyleNameDraft,
            label = { Text("New style name") },
            singleLine = true,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { if (state.styleNameDraft.isNotBlank()) actions.createStyle() },
                label = { Text("Create from selection") },
            )
            AssistChip(actions.detachStyle, label = { Text("Detach style") })
            AssistChip(actions.clearFormatting, label = { Text("Clear overrides") })
        }
        TextButton(onClick = { actions.dismiss(false) }, modifier = Modifier.align(Alignment.End)) { Text("Done") }
    }
}

@Composable
private fun ToolHeader(title: String, detail: String, onClose: () -> Unit, onOpenGuide: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        onOpenGuide?.let { com.norfold.app.ui.editor.guide.GuideEntryButton(title, it) }
        TextButton(onClick = onClose) { Text("Close") }
    }
}

private val DEFAULT_SWATCHES = listOf(
    ColorValue.parseHex("#000000")!!,
    ColorValue.parseHex("#FFFFFF")!!,
    ColorValue.parseHex("#6750A4")!!,
    ColorValue.parseHex("#7D5260")!!,
    ColorValue.parseHex("#B3261E")!!,
    ColorValue.parseHex("#2E7D32")!!,
    ColorValue.parseHex("#1565C0")!!,
    ColorValue.parseHex("#F9A825")!!,
    ColorValue.parseHex("#00838F")!!,
    ColorValue.parseHex("#6A1B9A")!!,
)
