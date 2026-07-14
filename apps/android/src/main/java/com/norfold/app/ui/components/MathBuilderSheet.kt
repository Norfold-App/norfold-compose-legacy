package com.norfold.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import com.norfold.app.ui.components.NorfoldBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class MathInsertionKind(val label: String) {
    Block("Block equation"),
    Inline("Inline math"),
}

data class MathBuilderResult(
    val tex: String,
    val kind: MathInsertionKind,
)

internal const val MathSlot = "\\norfoldslot{}"
private const val MathSelectionMarker = "{{norfold-selection}}"
private const val MaxMathHistory = 64

private enum class MathPaletteCategory(val label: String) {
    Structures("Structures"),
    Symbols("Symbols"),
    Greek("Greek"),
    Relations("Relations"),
    Matrices("Matrices"),
}

private data class MathPaletteItem(
    val label: String,
    val value: String,
    val isTemplate: Boolean,
)

private val StructureItems = listOf(
    MathPaletteItem("a⁄b", "\\frac{$MathSelectionMarker}{$MathSlot}", true),
    MathPaletteItem("xʸ", "{$MathSelectionMarker}^{$MathSlot}", true),
    MathPaletteItem("xᵢ", "{$MathSelectionMarker}_{$MathSlot}", true),
    MathPaletteItem("√x", "\\sqrt{$MathSelectionMarker}", true),
    MathPaletteItem("ⁿ√x", "\\sqrt[$MathSlot]{$MathSelectionMarker}", true),
    MathPaletteItem("∫", "\\int_{$MathSlot}^{$MathSlot} $MathSelectionMarker\\,d$MathSlot", true),
    MathPaletteItem("∑", "\\sum_{$MathSlot}^{$MathSlot} $MathSelectionMarker", true),
    MathPaletteItem("∏", "\\prod_{$MathSlot}^{$MathSlot} $MathSelectionMarker", true),
    MathPaletteItem("lim", "\\lim_{$MathSlot \\to $MathSlot} $MathSelectionMarker", true),
    MathPaletteItem("|x|", "\\left|$MathSelectionMarker\\right|", true),
    MathPaletteItem("(x)", "\\left($MathSelectionMarker\\right)", true),
    MathPaletteItem("{x}", "\\left\\{$MathSelectionMarker\\right\\}", true),
    MathPaletteItem("n choose r", "\\binom{$MathSelectionMarker}{$MathSlot}", true),
    MathPaletteItem("x̄", "\\overline{$MathSelectionMarker}", true),
    MathPaletteItem("x⃗", "\\vec{$MathSelectionMarker}", true),
)

private val SymbolItems = buildList {
    (0..9).forEach { add(MathPaletteItem(it.toString(), it.toString(), false)) }
    listOf("x", "y", "z", "n", "e", "i").forEach { add(MathPaletteItem(it, it, false)) }
    addAll(
        listOf(
            MathPaletteItem("+", "+", false),
            MathPaletteItem("−", "-", false),
            MathPaletteItem("×", "\\times ", false),
            MathPaletteItem("÷", "\\div ", false),
            MathPaletteItem("·", "\\cdot ", false),
            MathPaletteItem("±", "\\pm ", false),
            MathPaletteItem("∞", "\\infty ", false),
            MathPaletteItem("∂", "\\partial ", false),
            MathPaletteItem("∇", "\\nabla ", false),
            MathPaletteItem("sin", "\\sin ", false),
            MathPaletteItem("cos", "\\cos ", false),
            MathPaletteItem("tan", "\\tan ", false),
            MathPaletteItem("log", "\\log ", false),
            MathPaletteItem("ln", "\\ln ", false),
        ),
    )
}

private val GreekItems = listOf(
    "α" to "\\alpha ",
    "β" to "\\beta ",
    "γ" to "\\gamma ",
    "δ" to "\\delta ",
    "ε" to "\\epsilon ",
    "ζ" to "\\zeta ",
    "η" to "\\eta ",
    "θ" to "\\theta ",
    "κ" to "\\kappa ",
    "λ" to "\\lambda ",
    "μ" to "\\mu ",
    "ν" to "\\nu ",
    "ξ" to "\\xi ",
    "π" to "\\pi ",
    "ρ" to "\\rho ",
    "σ" to "\\sigma ",
    "τ" to "\\tau ",
    "φ" to "\\phi ",
    "χ" to "\\chi ",
    "ψ" to "\\psi ",
    "ω" to "\\omega ",
    "Γ" to "\\Gamma ",
    "Δ" to "\\Delta ",
    "Θ" to "\\Theta ",
    "Λ" to "\\Lambda ",
    "Ξ" to "\\Xi ",
    "Π" to "\\Pi ",
    "Σ" to "\\Sigma ",
    "Φ" to "\\Phi ",
    "Ψ" to "\\Psi ",
    "Ω" to "\\Omega ",
).map { (label, value) -> MathPaletteItem(label, value, false) }

private val RelationItems = listOf(
    "=" to "=",
    "≠" to "\\neq ",
    "<" to "<",
    "≤" to "\\le ",
    ">" to ">",
    "≥" to "\\ge ",
    "≈" to "\\approx ",
    "≡" to "\\equiv ",
    "∝" to "\\propto ",
    "∈" to "\\in ",
    "∉" to "\\notin ",
    "⊂" to "\\subset ",
    "⊆" to "\\subseteq ",
    "∪" to "\\cup ",
    "∩" to "\\cap ",
    "→" to "\\to ",
    "⇒" to "\\Rightarrow ",
    "↔" to "\\leftrightarrow ",
    "⇔" to "\\Leftrightarrow ",
    "⊥" to "\\perp ",
    "∥" to "\\parallel ",
).map { (label, value) -> MathPaletteItem(label, value, false) }

private val MatrixItems = listOf(
    MathPaletteItem(
        "2 × 2",
        "\\begin{bmatrix} $MathSelectionMarker & $MathSlot \\\\ $MathSlot & $MathSlot \\end{bmatrix}",
        true,
    ),
    MathPaletteItem(
        "3 × 3",
        "\\begin{bmatrix} $MathSelectionMarker & $MathSlot & $MathSlot \\\\ " +
            "$MathSlot & $MathSlot & $MathSlot \\\\ $MathSlot & $MathSlot & $MathSlot \\end{bmatrix}",
        true,
    ),
    MathPaletteItem(
        "(2 × 2)",
        "\\begin{pmatrix} $MathSelectionMarker & $MathSlot \\\\ $MathSlot & $MathSlot \\end{pmatrix}",
        true,
    ),
    MathPaletteItem(
        "det",
        "\\begin{vmatrix} $MathSelectionMarker & $MathSlot \\\\ $MathSlot & $MathSlot \\end{vmatrix}",
        true,
    ),
    MathPaletteItem(
        "cases",
        "\\begin{cases} $MathSelectionMarker & \\text{if } $MathSlot \\\\ $MathSlot & \\text{otherwise} \\end{cases}",
        true,
    ),
)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MathBuilderSheet(
    initialTex: String = "",
    initialKind: MathInsertionKind = MathInsertionKind.Block,
    allowKindSelection: Boolean = true,
    onDismiss: () -> Unit,
    onInsert: (MathBuilderResult) -> Unit,
) {
    val initialEquation = remember(initialTex) {
        initialTex.trim().takeIf(String::isNotEmpty) ?: MathSlot
    }
    val initialSelection = remember(initialEquation) {
        if (initialEquation == MathSlot) TextRange(0, MathSlot.length)
        else TextRange(initialEquation.length)
    }
    var equation by rememberSaveable(initialTex, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialEquation, initialSelection))
    }
    var insertionKindName by rememberSaveable(initialKind) { mutableStateOf(initialKind.name) }
    var categoryName by rememberSaveable { mutableStateOf(MathPaletteCategory.Structures.name) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    val undoStack = remember(initialTex) { mutableStateListOf<TextFieldValue>() }
    val redoStack = remember(initialTex) { mutableStateListOf<TextFieldValue>() }
    val activeInputFocus = remember { FocusRequester() }
    val insertionKind = MathInsertionKind.valueOf(insertionKindName)
    val category = MathPaletteCategory.valueOf(categoryName)
    val pendingSlots = pendingMathSlotCount(equation.text)
    val activeValue = activeMathSelectionText(equation)
    val canInsert = equation.text.isNotBlank() && pendingSlots == 0
    val colorScheme = MaterialTheme.colorScheme
    val dark = colorScheme.background.luminance() < 0.5f
    val accentHex = colorScheme.primary.toMathCssHex()

    fun rememberEdit(next: TextFieldValue) {
        if (next.text != equation.text) {
            if (undoStack.size >= MaxMathHistory) undoStack.removeAt(0)
            undoStack += equation.copy(composition = null)
            redoStack.clear()
        }
        equation = next
    }

    fun applyPaletteItem(item: MathPaletteItem) {
        val next = if (item.isTemplate) {
            applyMathTemplate(equation, item.value)
        } else {
            insertMathToken(equation, item.value)
        }
        rememberEdit(next)
        if (item.isTemplate && !showAdvanced) {
            runCatching { activeInputFocus.requestFocus() }
        }
    }

    NorfoldBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (initialTex.isBlank()) "Create equation" else "Edit equation",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Build with the visual palette. Raw LaTeX stays available under Advanced.",
                color = colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )

            if (allowKindSelection) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MathInsertionKind.entries.forEach { candidate ->
                        FilterChip(
                            selected = insertionKind == candidate,
                            onClick = { insertionKindName = candidate.name },
                            label = { Text(candidate.label) },
                        )
                    }
                }
            } else {
                Text(
                    insertionKind.label,
                    color = colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Live preview", fontWeight = FontWeight.SemiBold)
                Text(
                    text = when (pendingSlots) {
                        0 -> "Ready"
                        1 -> "1 blank slot"
                        else -> "$pendingSlots blank slots"
                    },
                    color = if (pendingSlots == 0) colorScheme.primary else colorScheme.error,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("math-live-preview"),
                shape = RoundedCornerShape(16.dp),
                color = colorScheme.surfaceVariant.copy(alpha = 0.42f),
                border = BorderStroke(1.dp, colorScheme.outlineVariant),
            ) {
                MarkdownPreview(
                    markdown = mathPreviewMarkdown(equation.text, insertionKind),
                    dark = dark,
                    accentHex = accentHex,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 88.dp, max = 220.dp)
                        .padding(14.dp),
                )
            }

            if (!showAdvanced) {
                OutlinedTextField(
                    value = activeValue,
                    onValueChange = { rememberEdit(replaceMathActiveSelection(equation, it)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(activeInputFocus)
                        .testTag("math-active-slot"),
                    label = { Text("Current slot") },
                    placeholder = { Text("Type a number, variable, or short expression") },
                    supportingText = {
                        Text(
                            if (equation.selection.collapsed) {
                                "Input is inserted at the current visual position."
                            } else {
                                "Palette structures wrap this value; Next blank advances to another slot."
                            },
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        keyboardType = KeyboardType.Ascii,
                    ),
                    singleLine = true,
                )

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(
                        onClick = { equation = selectMathSlot(equation, forward = false) },
                        enabled = pendingSlots > 0,
                    ) { Text("Previous blank") }
                    TextButton(
                        onClick = { equation = selectMathSlot(equation, forward = true) },
                        enabled = pendingSlots > 0,
                    ) { Text("Next blank") }
                    TextButton(
                        onClick = { equation = equation.copy(selection = TextRange(equation.text.length)) },
                    ) { Text("Append") }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(
                        onClick = {
                            if (undoStack.isNotEmpty()) {
                                redoStack += equation.copy(composition = null)
                                equation = undoStack.removeAt(undoStack.lastIndex)
                            }
                        },
                        enabled = undoStack.isNotEmpty(),
                    ) { Text("Undo") }
                    TextButton(
                        onClick = {
                            if (redoStack.isNotEmpty()) {
                                undoStack += equation.copy(composition = null)
                                equation = redoStack.removeAt(redoStack.lastIndex)
                            }
                        },
                        enabled = redoStack.isNotEmpty(),
                    ) { Text("Redo") }
                    TextButton(
                        onClick = {
                            rememberEdit(TextFieldValue(MathSlot, TextRange(0, MathSlot.length)))
                            runCatching { activeInputFocus.requestFocus() }
                        },
                        enabled = equation.text != MathSlot,
                    ) { Text("Start over") }
                }

                HorizontalDivider(color = colorScheme.outlineVariant)
                Text("Equation palette", fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    MathPaletteCategory.entries.forEach { candidate ->
                        FilterChip(
                            selected = category == candidate,
                            onClick = { categoryName = candidate.name },
                            label = { Text(candidate.label) },
                        )
                    }
                }
                MathPalette(
                    items = when (category) {
                        MathPaletteCategory.Structures -> StructureItems
                        MathPaletteCategory.Symbols -> SymbolItems
                        MathPaletteCategory.Greek -> GreekItems
                        MathPaletteCategory.Relations -> RelationItems
                        MathPaletteCategory.Matrices -> MatrixItems
                    },
                    onClick = ::applyPaletteItem,
                )
            }

            HorizontalDivider(color = colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Advanced", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Edit raw LaTeX; changes stay synchronized with the visual preview.",
                        color = colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = showAdvanced, onCheckedChange = { showAdvanced = it })
            }
            if (showAdvanced) {
                OutlinedTextField(
                    value = equation,
                    onValueChange = ::rememberEdit,
                    modifier = Modifier.fillMaxWidth().testTag("math-advanced-source"),
                    label = { Text("Raw LaTeX") },
                    supportingText = {
                        Text("The live preview above updates as you type.")
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        keyboardType = KeyboardType.Ascii,
                    ),
                    minLines = 4,
                    maxLines = 8,
                )
            }

            if (!canInsert) {
                Text(
                    text = if (equation.text.isBlank()) {
                        "Add an equation before inserting."
                    } else {
                        "Fill every visible blank slot before inserting."
                    },
                    color = colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        onInsert(MathBuilderResult(equation.text.trim(), insertionKind))
                    },
                    modifier = Modifier.weight(1f).testTag("math-insert"),
                    enabled = canInsert,
                ) {
                    Text(
                        if (insertionKind == MathInsertionKind.Block) "Insert block" else "Insert inline",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MathPalette(
    items: List<MathPaletteItem>,
    onClick: (MathPaletteItem) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { item ->
            OutlinedButton(
                onClick = { onClick(item) },
                modifier = Modifier.widthIn(min = 48.dp).heightIn(min = 40.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(item.label, maxLines = 1)
            }
        }
    }
}

internal fun applyMathTemplate(value: TextFieldValue, pattern: String): TextFieldValue {
    val range = value.selection.coerceTo(value.text.length)
    val start = minOf(range.start, range.end)
    val end = maxOf(range.start, range.end)
    val selected = value.text.substring(start, end)
        .takeUnless { it.isBlank() || it == MathSlot }
        ?: MathSlot
    val insertion = pattern.replace(MathSelectionMarker, selected)
    val updated = value.text.replaceRange(start, end, insertion)
    val nextSlot = insertion.indexOf(MathSlot)
    val nextSelection = if (nextSlot >= 0) {
        TextRange(start + nextSlot, start + nextSlot + MathSlot.length)
    } else {
        TextRange(start + insertion.length)
    }
    return TextFieldValue(updated, nextSelection)
}

internal fun insertMathToken(value: TextFieldValue, token: String): TextFieldValue {
    val range = value.selection.coerceTo(value.text.length)
    val start = minOf(range.start, range.end)
    val end = maxOf(range.start, range.end)
    val replacedSlot = value.text.substring(start, end) == MathSlot
    val updated = value.text.replaceRange(start, end, token)
    val caret = start + token.length
    val nextSlot = if (replacedSlot) updated.indexOf(MathSlot, startIndex = caret) else -1
    val nextSelection = if (nextSlot >= 0) {
        TextRange(nextSlot, nextSlot + MathSlot.length)
    } else {
        TextRange(caret)
    }
    return TextFieldValue(updated, nextSelection)
}

internal fun replaceMathActiveSelection(value: TextFieldValue, input: String): TextFieldValue {
    val range = value.selection.coerceTo(value.text.length)
    val start = minOf(range.start, range.end)
    val end = maxOf(range.start, range.end)
    val replacement = input.ifEmpty { MathSlot }
    val updated = value.text.replaceRange(start, end, replacement)
    return TextFieldValue(updated, TextRange(start, start + replacement.length))
}

internal fun activeMathSelectionText(value: TextFieldValue): String {
    val range = value.selection.coerceTo(value.text.length)
    val start = minOf(range.start, range.end)
    val end = maxOf(range.start, range.end)
    if (start == end) return ""
    return value.text.substring(start, end).takeUnless { it == MathSlot }.orEmpty()
}

internal fun selectMathSlot(value: TextFieldValue, forward: Boolean): TextFieldValue {
    if (!value.text.contains(MathSlot)) return value.copy(selection = TextRange(value.text.length))
    val range = value.selection.coerceTo(value.text.length)
    val start = minOf(range.start, range.end)
    val end = maxOf(range.start, range.end)
    val index = if (forward) {
        value.text.indexOf(MathSlot, startIndex = end).takeIf { it >= 0 }
            ?: value.text.indexOf(MathSlot)
    } else {
        val before = if (start > 0) value.text.lastIndexOf(MathSlot, startIndex = start - 1) else -1
        before.takeIf { it >= 0 } ?: value.text.lastIndexOf(MathSlot)
    }
    return value.copy(selection = TextRange(index, index + MathSlot.length))
}

internal fun pendingMathSlotCount(tex: String): Int {
    var count = 0
    var cursor = 0
    while (cursor < tex.length) {
        val match = tex.indexOf(MathSlot, startIndex = cursor)
        if (match < 0) break
        count += 1
        cursor = match + MathSlot.length
    }
    return count
}

internal fun mathPreviewMarkdown(tex: String, kind: MathInsertionKind): String {
    val previewTex = tex.ifBlank { MathSlot }
        .replace(MathSlot, "\\boxed{\\vphantom{0}\\phantom{0}}")
    return when (kind) {
        MathInsertionKind.Block -> "$$\n$previewTex\n$$"
        MathInsertionKind.Inline -> "Inline preview: \\($previewTex\\)"
    }
}

private fun TextRange.coerceTo(textLength: Int): TextRange = TextRange(
    start = start.coerceIn(0, textLength),
    end = end.coerceIn(0, textLength),
)

private fun Color.toMathCssHex(): String = "#%06X".format(toArgb() and 0x00FFFFFF)
