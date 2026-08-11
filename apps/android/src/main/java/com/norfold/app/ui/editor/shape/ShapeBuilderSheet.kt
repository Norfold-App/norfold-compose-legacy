package com.norfold.app.ui.editor.shape

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.norfold.app.domain.ShapeBlock
import com.norfold.app.domain.style.ColorDomain
import com.norfold.app.domain.style.ColorPaint
import com.norfold.app.domain.style.ColorRole
import com.norfold.app.domain.style.ColorTarget
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.domain.style.GradientKind
import com.norfold.app.editor.history.FocusHistoryOwner
import com.norfold.app.editor.shape.ShapeBlendMode
import com.norfold.app.editor.shape.ShapeBooleanLayer
import com.norfold.app.editor.shape.ShapeBooleanOperation
import com.norfold.app.editor.shape.ShapeBooleanStatus
import com.norfold.app.editor.shape.ShapeCanvas
import com.norfold.app.editor.shape.ShapeDocument
import com.norfold.app.editor.shape.ShapeEffects
import com.norfold.app.editor.shape.ShapeFocusCommitHost
import com.norfold.app.editor.shape.ShapeFocusRuntime
import com.norfold.app.editor.shape.ShapeGlow
import com.norfold.app.editor.shape.ShapeLayer
import com.norfold.app.editor.shape.ShapeLayerEditor
import com.norfold.app.editor.shape.ShapeNode
import com.norfold.app.editor.shape.ShapeNodeRef
import com.norfold.app.editor.shape.ShapeNodeType
import com.norfold.app.editor.shape.ShapeParameterKey
import com.norfold.app.editor.shape.ShapeParameters
import com.norfold.app.editor.shape.ShapePath
import com.norfold.app.editor.shape.ShapePathLayer
import com.norfold.app.editor.shape.ShapePoint
import com.norfold.app.editor.shape.ShapePrimitiveCategory
import com.norfold.app.editor.shape.ShapePrimitiveKind
import com.norfold.app.editor.shape.ShapePrimitiveLayer
import com.norfold.app.editor.shape.ShapePrimitives
import com.norfold.app.editor.shape.ShapeSelection
import com.norfold.app.editor.shape.ShapeShadow
import com.norfold.app.editor.shape.ShapeStrokeCap
import com.norfold.app.editor.shape.ShapeStrokeJoin
import com.norfold.app.editor.shape.parametersUsedBy
import com.norfold.app.editor.style.ColorStudioEngine
import com.norfold.app.editor.style.ColorStudioHost
import com.norfold.app.editor.style.ColorStudioInteractionController
import com.norfold.app.editor.style.EditorStyleUiState
import com.norfold.app.ui.components.NorfoldBottomSheet
import com.norfold.app.ui.editor.guide.GuideEntryButton
import com.norfold.app.ui.editor.style.EditorStyleSurface
import com.norfold.app.ui.editor.style.EditorStyleSurfaceActions
import kotlin.math.roundToInt

private enum class ShapeBuilderSection(val label: String) {
    PRESETS("Presets"), EDIT("Edit"), LAYERS("Layers"),
}

/** Namespaces the focus id so it can never collide with a surface id or be mistaken for one. */
private const val FOCUS_ID_PREFIX = "shape.focus."

/**
 * One local Shape Builder session. Done is the only route to the host's canonical history.
 *
 * Every control in this sheet goes through [ShapeFocusRuntime], which owns what becomes a step; a control
 * that previews does so inside a gesture so a slider drag is one undo entry, and a tap is its own step.
 * All of it — the canvas above, the toolbar, the sliders, the layer list, the Boolean picker — stays local
 * until Done, at which point the runtime closes exactly one host transaction.
 */
@Composable
fun ShapeBuilderSheet(
    block: ShapeBlock,
    readOnly: Boolean,
    onCommit: (ShapeBlock) -> Boolean,
    onDismiss: () -> Unit,
    onDone: () -> Unit = onDismiss,
    onOpenGuide: (() -> Unit)? = null,
    onCommitted: (() -> Unit)? = null,
    onFocusHistoryOwner: ((FocusHistoryOwner?) -> Unit)? = null,
) {
    val latestCommit by rememberUpdatedState(onCommit)
    val runtime = remember(block.id, block.shapeEditDocument, readOnly) {
        ShapeFocusRuntime(block, ShapeFocusCommitHost { _, edited -> latestCommit(edited) }, readOnly)
    }

    // Registration is tied to composition rather than to Done/Cancel: the sheet can also leave by
    // configuration change or by the host dropping it, and a session whose ownership outlived it would
    // swallow every later undo in the document.
    val latestOwnerSink by rememberUpdatedState(onFocusHistoryOwner)
    DisposableEffect(runtime) {
        latestOwnerSink?.invoke(runtime.historyOwner(FOCUS_ID_PREFIX + block.id))
        onDispose { latestOwnerSink?.invoke(null) }
    }
    var revision by remember { mutableIntStateOf(0) }
    var sectionIndex by remember { mutableIntStateOf(0) }
    var tool by remember { mutableStateOf(ShapeCanvasTool.SELECT) }
    var settings by remember { mutableStateOf(ShapeCanvasSettings()) }
    val colorStudio = remember(runtime) { ShapeColorStudioController(runtime) { revision++ } }
    val document = remember(runtime, revision) { runtime.document() }
    val controlsDisabled = runtime.isReadOnly() || runtime.isComparingOriginal()
    val section = ShapeBuilderSection.entries[sectionIndex]
    val selection = runtime.selection()

    fun edit(transform: (ShapeDocument) -> ShapeDocument) {
        if (runtime.edit(transform)) revision++
    }

    fun undo() {
        if (runtime.undo()) revision++
    }

    fun redo() {
        if (runtime.redo()) revision++
    }

    NorfoldBottomSheet(onDismissRequest = { runtime.cancel(); onDismiss() }) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Shape Builder", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        when {
                            runtime.isReadOnly() && readOnly -> "Read mode · editing unavailable"
                            runtime.isReadOnly() -> "This shape uses a newer or unreadable edit format"
                            else -> "Custom vector shapes, compound shapes included"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                onOpenGuide?.let { GuideEntryButton("Shape Builder", it) }
                TextButton(onClick = { runtime.cancel(); onDismiss() }) { Text("Cancel") }
                Button(
                    onClick = {
                        if (colorStudio.engine.isOpen) colorStudio.close(commit = true)
                        if (runtime.done()) {
                            onCommitted?.invoke()
                            onDone()
                        }
                    },
                    enabled = !runtime.isReadOnly(),
                ) { Text("Done") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::undo, enabled = runtime.canUndo()) {
                    Icon(Icons.AutoMirrored.Outlined.Undo, "Undo shape edit")
                }
                IconButton(onClick = ::redo, enabled = runtime.canRedo()) {
                    Icon(Icons.AutoMirrored.Outlined.Redo, "Redo shape edit")
                }
                OutlinedButton(
                    onClick = { if (runtime.setCompareOriginal(!runtime.isComparingOriginal())) revision++ },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(if (runtime.isComparingOriginal()) "Show edited" else "Compare original") }
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = { if (runtime.resetToLegacy()) revision++ },
                    enabled = !controlsDisabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Reset entire edit") }
            }
            Box(Modifier.fillMaxWidth().height(300.dp)) {
                ShapeDocumentPreview(document, Modifier.fillMaxWidth().height(300.dp))
                if (section == ShapeBuilderSection.EDIT) {
                    ShapeCanvasOverlay(
                        runtime = runtime,
                        path = runtime.activePath(),
                        tool = tool,
                        settings = settings,
                        disabled = controlsDisabled,
                        onChanged = { revision++ },
                        modifier = Modifier.fillMaxWidth().height(300.dp),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ShapeBuilderSection.entries.forEachIndexed { index, item ->
                    FilterChip(
                        selected = sectionIndex == index,
                        onClick = { sectionIndex = index },
                        label = { Text(item.label) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            if (colorStudio.engine.isOpen) {
                EditorStyleSurface(
                    surfaceId = "editor.surface.colorStudio",
                    state = EditorStyleUiState(
                        colorStudio = colorStudio.engine.state(),
                        documentColors = document.layers
                            .flatMap { layer -> listOf(layer.fill, layer.stroke.paint) }
                            .flatMap { it.colors() }
                            .distinct(),
                        recentColors = colorStudio.engine.recentColors(),
                    ),
                    fontRows = emptyList(),
                    actions = shapeColorActions(colorStudio),
                    onOpenGuide = onOpenGuide,
                )
            } else when (section) {
                ShapeBuilderSection.PRESETS -> PresetsSection(document, controlsDisabled, runtime, { revision++ })
                ShapeBuilderSection.EDIT -> EditSection(
                    document,
                    controlsDisabled,
                    runtime,
                    selection,
                    tool,
                    { tool = it },
                    settings,
                    { settings = it },
                    { revision++ },
                    ::edit,
                    colorStudio,
                )
                ShapeBuilderSection.LAYERS -> LayersSection(document, controlsDisabled, runtime, selection, { revision++ })
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

// ── Presets ────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun PresetsSection(
    document: ShapeDocument,
    disabled: Boolean,
    runtime: ShapeFocusRuntime,
    changed: () -> Unit,
) {
    val hasLegacyLayer = document.layers.any { it.content is ShapePrimitiveLayer }
    if (document.layers.isEmpty()) {
        Text("No shapes yet. Add one below to begin building.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if (hasLegacyLayer) {
        Text(
            "Every preset below adds a parametric layer. Move or convert a layer to a path to make it editable node by node.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    ShapePrimitiveCategory.entries.forEach { category ->
        val presets = ShapePrimitives.presets.filter { it.category == category }
        Text(category.displayName, style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { preset ->
                OutlinedButton(
                    onClick = { if (runtime.addPreset(preset)) changed() },
                    enabled = !disabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    ShapeDocumentPreview(
                        ShapeDocument(
                            layers = listOf(
                                ShapeLayer(
                                    name = preset.displayName,
                                    content = ShapePrimitiveLayer(preset.kind, preset.parameters),
                                    fill = document.layers.lastOrNull()?.fill ?: ColorPaint.Flat(ColorValue(0xFF, 0x6F, 0x36)),
                                ),
                            ),
                        ),
                        Modifier.width(44.dp).height(36.dp),
                    )
                    Text(preset.displayName)
                }
            }
        }
    }
}

// ── Edit ───────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun EditSection(
    document: ShapeDocument,
    disabled: Boolean,
    runtime: ShapeFocusRuntime,
    selection: ShapeSelection,
    tool: ShapeCanvasTool,
    onTool: (ShapeCanvasTool) -> Unit,
    settings: ShapeCanvasSettings,
    onSettings: (ShapeCanvasSettings) -> Unit,
    changed: () -> Unit,
    edit: ((ShapeDocument) -> ShapeDocument) -> Unit,
    colorStudio: ShapeColorStudioController,
) {
    val layer = runtime.activeLayer()
    if (layer == null) {
        Text(
            "Add a shape from the Presets tab, or select a layer in the Layers tab.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolChip(ShapeCanvasTool.SELECT.label, tool == ShapeCanvasTool.SELECT) { onTool(ShapeCanvasTool.SELECT) }
        ToolChip(ShapeCanvasTool.ADD_POINT.label, tool == ShapeCanvasTool.ADD_POINT) { onTool(ShapeCanvasTool.ADD_POINT) }
        ToggleChip("Snap", settings.snap, disabled) { onSettings(settings.copy(snap = it)) }
        ToggleChip("Grid", settings.grid, disabled) { onSettings(settings.copy(grid = it)) }
        ToggleChip("Axis lock", settings.axisLock, disabled) { onSettings(settings.copy(axisLock = it)) }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToggleChip("Show handles", settings.showHandles, disabled) { onSettings(settings.copy(showHandles = it)) }
        ToggleChip("Magnifier", settings.magnifier, disabled) { onSettings(settings.copy(magnifier = it)) }
    }
    Spacer(Modifier.height(4.dp))
    when (val content = layer.content) {
        is ShapePrimitiveLayer -> PrimitiveControls(content, disabled, runtime, layer, changed, edit)
        is ShapePathLayer -> PathControls(runtime, selection, disabled, changed, edit)
        is ShapeBooleanLayer -> BooleanControls(document, content, disabled, runtime, changed)
    }
    Spacer(Modifier.height(4.dp))
    StyleControls(disabled, runtime, colorStudio, changed)
}

@Composable
private fun PrimitiveControls(
    content: ShapePrimitiveLayer,
    disabled: Boolean,
    runtime: ShapeFocusRuntime,
    layer: ShapeLayer,
    changed: () -> Unit,
    edit: ((ShapeDocument) -> ShapeDocument) -> Unit,
) {
    val kind = content.kind
    val parameters = content.parameters
    Text(kind.displayName, style = MaterialTheme.typography.titleSmall)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ShapePrimitiveKind.entries.forEach { candidate ->
            FilterChip(
                selected = kind == candidate,
                onClick = { edit { ShapeLayerEditor.setKind(it, layer.id, candidate) } },
                enabled = !disabled,
                label = { Text(candidate.displayName) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
    fun param(key: ShapeParameterKey): Float = when (key) {
        ShapeParameterKey.CORNER_RADIUS -> parameters.cornerRadius
        ShapeParameterKey.SMOOTHNESS -> parameters.smoothness
        ShapeParameterKey.POINT_COUNT -> parameters.pointCount.toFloat()
        ShapeParameterKey.INNER_RADIUS -> parameters.innerRadius
        ShapeParameterKey.WARP -> parameters.warp
        ShapeParameterKey.TENSION -> parameters.tension
        ShapeParameterKey.ROTATION -> parameters.rotation
        ShapeParameterKey.SEED -> parameters.seed.toFloat()
    }
    fun setParameter(key: ShapeParameterKey, value: Float): ShapeParameters = when (key) {
        ShapeParameterKey.CORNER_RADIUS -> parameters.copy(cornerRadius = value)
        ShapeParameterKey.SMOOTHNESS -> parameters.copy(smoothness = value)
        ShapeParameterKey.POINT_COUNT -> parameters.copy(pointCount = value.roundToInt())
        ShapeParameterKey.INNER_RADIUS -> parameters.copy(innerRadius = value)
        ShapeParameterKey.WARP -> parameters.copy(warp = value)
        ShapeParameterKey.TENSION -> parameters.copy(tension = value)
        ShapeParameterKey.ROTATION -> parameters.copy(rotation = value)
        ShapeParameterKey.SEED -> parameters.copy(seed = value.roundToInt())
    }
    ShapeParameterKey.entries.forEach { key ->
        if (key in kind.parametersUsedBy) {
            val range = when (key) {
                ShapeParameterKey.CORNER_RADIUS -> 0f..0.5f
                ShapeParameterKey.SMOOTHNESS -> 0f..1f
                ShapeParameterKey.POINT_COUNT -> 3f..64f
                ShapeParameterKey.INNER_RADIUS -> 0.05f..1f
                ShapeParameterKey.WARP -> -1f..1f
                ShapeParameterKey.TENSION -> 0f..2f
                ShapeParameterKey.ROTATION -> 0f..360f
                ShapeParameterKey.SEED -> 1f..100f
            }
            val neutral = when (key) {
                ShapeParameterKey.ROTATION -> 0f
                ShapeParameterKey.TENSION -> 1f
                ShapeParameterKey.SEED -> 1f
                else -> 0f
            }
            GestureSlider(key.displayName, param(key), range, neutral, disabled, runtime, changed) { value, current ->
                val layerId = runtime.activeLayerId() ?: return@GestureSlider current
                ShapeLayerEditor.setParameters(current, layerId, setParameter(key, value))
            }
        }
    }
    if (kind == ShapePrimitiveKind.BLOB) {
        OutlinedButton(
            onClick = { edit { document ->
                val layerId = runtime.activeLayerId() ?: return@edit document
                ShapeLayerEditor.setParameters(document, layerId, ShapeParameters(seed = nextSeed(parameters.seed)))
            } },
            enabled = !disabled,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text("New variation") }
    }
}

private fun nextSeed(seed: Int): Int = if (seed >= 100) 1 else seed + 1

@Composable
private fun PathControls(
    runtime: ShapeFocusRuntime,
    selection: ShapeSelection,
    disabled: Boolean,
    changed: () -> Unit,
    edit: ((ShapeDocument) -> ShapeDocument) -> Unit,
) {
    val path = runtime.activePath()
    if (path == null) {
        Text("This layer has no outline to edit.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val single = selection.single
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolButton("Add point", Icons.Outlined.Add, disabled) { if (runtime.addPointAt(ShapePointCenter())) changed() }
        ToolButton("Delete point", Icons.Outlined.Delete, disabled || selection.nodes.isEmpty()) {
            if (runtime.deleteSelectedNodes()) changed()
        }
        ToolButton("Mirror", Icons.Outlined.Layers, disabled || selection.nodes.isEmpty()) {
            if (runtime.mirrorSelectedNodes()) changed()
        }
        ToolButton("Smooth", Icons.Outlined.BlurOn, disabled || selection.nodes.isEmpty()) {
            if (runtime.smoothSelectedNodes()) changed()
        }
        ToolButton("Corner", Icons.Outlined.RadioButtonUnchecked, disabled || selection.nodes.isEmpty()) {
            if (runtime.sharpenSelectedNodes()) changed()
        }
        ToolButton("Round", Icons.Outlined.Edit, disabled || selection.nodes.isEmpty()) {
            if (runtime.roundSelectedNodes()) changed()
        }
    }
    if (single != null) {
        val node = path.nodeFor(single)
        if (node != null) {
            Text(
                "Node ${single.nodeId.substringAfterLast('-')} · ${node.type.name.lowercase().replaceFirstChar(Char::uppercase)}",
                style = MaterialTheme.typography.labelLarge,
            )
            EnumChips("Node type", ShapeNodeType.entries, node.type, disabled) { type ->
                if (runtime.setSelectedNodeType(type)) changed()
            }
        }
    }
    Text(
        "Select nodes on the canvas to move them; drag a handle to bend a curve.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (path.subpaths.any { !it.closed }) {
        Text("This layer has open outlines. Open lines stroke but do not fill.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun ShapePointCenter() = ShapePoint(ShapeCanvas.SIDE / 2f, ShapeCanvas.SIDE / 2f)

@Composable
private fun BooleanControls(
    document: ShapeDocument,
    content: ShapeBooleanLayer,
    disabled: Boolean,
    runtime: ShapeFocusRuntime,
    changed: () -> Unit,
) {
    val group = content.group
    val layerId = runtime.activeLayerId() ?: return
    val resolved = remember(document, group) { com.norfold.app.editor.shape.ShapeBooleans.compute(group) }
    val status = resolved.status
    EnumChips("Boolean", ShapeBooleanOperation.entries, group.operation, disabled) { operation ->
        if (runtime.setBooleanOperation(layerId, operation)) changed()
    }
    when (status) {
        ShapeBooleanStatus.RESOLVED -> Text("The combined outline is drawn below.")
        ShapeBooleanStatus.PASSTHROUGH -> Text(
            "Fewer than two children can take part, so the single remaining outline is passed through unchanged.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ShapeBooleanStatus.CANCELLED -> Text(
            "These outlines cancel out. The result is genuinely empty.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ShapeBooleanStatus.TOO_COMPLEX -> Text(
            "These outlines are too complex to resolve; the first child is shown instead of a wrong region.",
            color = MaterialTheme.colorScheme.error,
        )
        ShapeBooleanStatus.PARTIAL -> Text(
            "Part of the outline could not be closed, so part of it was dropped.",
            color = MaterialTheme.colorScheme.error,
        )
    }
    Text("Children (${group.children.size})", style = MaterialTheme.typography.titleSmall)
    group.children.forEach { child ->
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            ShapeDocumentPreview(
                ShapeDocument(layers = listOf(child)),
                Modifier.width(44.dp).height(36.dp),
            )
            Text(child.displayName(), Modifier.weight(1f))
        }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolButton("Ungroup", Icons.Outlined.Layers, disabled) {
            if (runtime.ungroup(layerId)) changed()
        }
        ToolButton("Flatten to path", Icons.Outlined.ContentCopy, disabled) {
            if (runtime.flattenGroup(layerId)) changed()
        }
    }
    Text(
        "Ungroup returns the original layers. Flatten keeps the result as a plain, node-editable outline — the children and the operation are gone.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ── Layers ─────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun LayersSection(
    document: ShapeDocument,
    disabled: Boolean,
    runtime: ShapeFocusRuntime,
    selection: ShapeSelection,
    changed: () -> Unit,
) {
    val activeLayerId = selection.layerId
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolButton("Add shape", Icons.Outlined.Add, disabled) { if (runtime.addLayer()) changed() }
        ToolButton("Add line", Icons.Outlined.Edit, disabled) { if (runtime.addPrimitive(ShapePrimitiveKind.LINE)) changed() }
        ToolButton("Add speech bubble", Icons.Outlined.BlurOn, disabled) { if (runtime.addPrimitive(ShapePrimitiveKind.SPEECH_BUBBLE)) changed() }
    }
    if (document.layers.isEmpty()) {
        Text("No layers yet. Add a shape above, or pick one from the Presets tab.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolButton("Merge last two", Icons.Outlined.Layers, disabled) {
            if (runtime.groupAsBoolean(document.layers.map { it.id }.takeLast(2), ShapeBooleanOperation.UNION)) changed()
        }
        val booleanSelected = document.layerById(activeLayerId ?: "")?.content is ShapeBooleanLayer
        ToolButton("Ungroup", Icons.Outlined.Layers, disabled || !booleanSelected) {
            if (runtime.ungroup(activeLayerId ?: "")) changed()
        }
        ToolButton("Flatten", Icons.Outlined.ContentCopy, disabled || !booleanSelected) {
            if (runtime.flattenGroup(activeLayerId ?: "")) changed()
        }
    }
    Text(
        "Merge combines the bottom two layers with a Union. For Subtract, Intersect or Exclude, edit the merged layer's Boolean setting.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    document.layers.asReversed().forEach { layer ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val selected = layer.id == activeLayerId
            FilterChip(
                selected = selected,
                onClick = { if (runtime.selectLayer(if (selected) null else layer.id)) changed() },
                label = {
                    Text(
                        layer.displayName(),
                        maxLines = 1,
                        style = if (selected) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                    )
                },
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                IconButton(onClick = { if (runtime.moveLayerUp(layer.id)) changed() }) { Icon(Icons.Outlined.Layers, "Move layer forward") }
                IconButton(onClick = { if (runtime.moveLayerDown(layer.id)) changed() }) { Icon(Icons.Outlined.Layers, "Move layer backward") }
                IconButton(onClick = { if (runtime.duplicateLayer(layer.id)) changed() }) { Icon(Icons.Outlined.ContentCopy, "Duplicate layer") }
                IconButton(onClick = { if (runtime.setLayerVisible(layer.id, !layer.visible)) changed() }) {
                    Icon(
                        if (layer.visible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                        if (layer.visible) "Hide layer" else "Show layer",
                    )
                }
                IconButton(onClick = { if (runtime.setLayerLocked(layer.id, !layer.locked)) changed() }) {
                    Icon(
                        if (layer.locked) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                        if (layer.locked) "Unlock layer" else "Lock layer",
                    )
                }
                IconButton(onClick = { if (runtime.removeLayer(layer.id)) changed() }) { Icon(Icons.Outlined.Delete, "Delete layer") }
            }
        }
    }
    Text("Bottom of the list is the back of the stack.", color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ── Style ──────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun StyleControls(
    disabled: Boolean,
    runtime: ShapeFocusRuntime,
    colorStudio: ShapeColorStudioController,
    changed: () -> Unit,
) {
    val layer = runtime.activeLayer() ?: return
    val layerId = layer.id
    Text("Style", style = MaterialTheme.typography.titleSmall)
    val fillable = layer.content.toPath().subpaths.any { it.closed }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (fillable) {
            OutlinedButton(
                onClick = { colorStudio.openFill(layer.fill) { changed() } },
                enabled = !disabled,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Fill") }
        }
        OutlinedButton(
            onClick = { colorStudio.openStroke(layer.stroke.paint) { changed() } },
            enabled = !disabled,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text("Stroke color") }
    }
    ToggleRow("Stroke", layer.stroke.enabled, disabled) { value ->
        if (runtime.setStrokeEnabled(layerId, value)) changed()
    }
    if (layer.stroke.enabled) {
        GestureSlider("Stroke width", layer.stroke.width, 0f..100f, 1.5f, disabled, runtime, { changed() }) { value, current ->
            runtime.activeLayerId()?.let { ShapeLayerEditor.setStrokeWidth(current, it, value) } ?: current
        }
        EnumChips("Line cap", ShapeStrokeCap.entries, layer.stroke.cap, disabled) { cap ->
            if (runtime.setStrokeCap(layerId, cap)) changed()
        }
        EnumChips("Line join", ShapeStrokeJoin.entries, layer.stroke.join, disabled) { join ->
            if (runtime.setStrokeJoin(layerId, join)) changed()
        }
    }
    ToggleRow("Opacity", layer.opacity < 1f, disabled) { value ->
        if (runtime.setOpacity(layerId, if (value) 0.6f else 1f)) changed()
    }
    if (layer.opacity < 1f) {
        GestureSlider("Opacity", layer.opacity, 0f..1f, 1f, disabled, runtime, { changed() }) { value, current ->
            runtime.activeLayerId()?.let { ShapeLayerEditor.setOpacity(current, it, value) } ?: current
        }
    }
    EnumChips("Blend", ShapeBlendMode.entries, layer.blendMode, disabled) { mode ->
        if (runtime.setBlendMode(layerId, mode)) changed()
    }
    Text("Effects", style = MaterialTheme.typography.titleSmall)
    ToggleRow("Blur", layer.effects.blurRadius > 0f, disabled) { value ->
        if (runtime.setEffects(layerId, layer.effects.copy(blurRadius = if (value) 8f else 0f))) changed()
    }
    if (layer.effects.blurRadius > 0f) {
        EffectSlider("Blur radius", layer.effects.blurRadius, 0f..64f, 0f, disabled, runtime, changed) { effects, value ->
            effects.copy(blurRadius = value)
        }
    }
    ToggleRow("Shadow", layer.effects.shadow.enabled, disabled) { value ->
        if (runtime.setEffects(layerId, layer.effects.copy(shadow = layer.effects.shadow.copy(enabled = value)))) changed()
    }
    if (layer.effects.shadow.enabled) {
        EffectSlider("Shadow blur", layer.effects.shadow.blurRadius, 0f..64f, 8f, disabled, runtime, changed) { effects, value ->
            effects.copy(shadow = effects.shadow.copy(blurRadius = value))
        }
        EffectSlider("Shadow horizontal offset", layer.effects.shadow.offsetX, -48f..48f, 0f, disabled, runtime, changed) { effects, value ->
            effects.copy(shadow = effects.shadow.copy(offsetX = value))
        }
        EffectSlider("Shadow vertical offset", layer.effects.shadow.offsetY, -48f..48f, 4f, disabled, runtime, changed) { effects, value ->
            effects.copy(shadow = effects.shadow.copy(offsetY = value))
        }
    }
    ToggleRow("Glow", layer.effects.glow.enabled, disabled) { value ->
        if (runtime.setEffects(layerId, layer.effects.copy(glow = layer.effects.glow.copy(enabled = value)))) changed()
    }
    if (layer.effects.glow.enabled) {
        EffectSlider("Glow blur", layer.effects.glow.blurRadius, 0f..64f, 12f, disabled, runtime, changed) { effects, value ->
            effects.copy(glow = effects.glow.copy(blurRadius = value))
        }
        EffectSlider("Glow strength", layer.effects.glow.strength, 0f..1f, 1f, disabled, runtime, changed) { effects, value ->
            effects.copy(glow = effects.glow.copy(strength = value))
        }
    }
}

/**
 * A [GestureSlider] for effects. The transform receives the current effects and returns the next ones,
 * so a missing layer refuses (by keeping the document unchanged) instead of fabricating default effects
 * that would wipe what is actually stored.
 */
@Composable
private fun EffectSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    neutralValue: Float,
    disabled: Boolean,
    runtime: ShapeFocusRuntime,
    changed: () -> Unit,
    transform: (ShapeEffects, Float) -> ShapeEffects,
) {
    GestureSlider(label, value, range, neutralValue, disabled, runtime, changed) { next, document ->
        val layerId = runtime.activeLayerId() ?: return@GestureSlider document
        val effects = document.layerById(layerId)?.effects ?: return@GestureSlider document
        ShapeLayerEditor.setEffects(document, layerId, transform(effects, next))
    }
}

// ── Shared controls ────────────────────────────────────────────────────────────────────────────────

/**
 * A labelled slider that opens a gesture on the first move and closes it on release, so a drag is one
 * undo step. [transform] receives the live document and must return the next one, or the same document
 * to refuse; re-deriving the active layer inside the transform is what keeps the layer identity honest
 * while a gesture is in flight.
 */
@Composable
private fun GestureSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    neutralValue: Float,
    disabled: Boolean,
    runtime: ShapeFocusRuntime,
    changed: () -> Unit,
    transform: (Float, ShapeDocument) -> ShapeDocument,
) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            TextButton(
                onClick = { if (runtime.edit { transform(neutralValue, it) }) changed() },
                enabled = !disabled && value != neutralValue,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Reset") }
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { next ->
                if (!runtime.hasGesture()) runtime.beginGesture()
                if (runtime.previewGesture { transform(next, it) }) changed()
            },
            onValueChangeFinished = { if (runtime.endGesture()) changed() },
            valueRange = range,
            enabled = !disabled,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

@Composable
private fun <T : Enum<T>> EnumChips(label: String, values: List<T>, selected: T, disabled: Boolean, onSelect: (T) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                enabled = !disabled,
                label = { Text(value.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, disabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = !disabled)
    }
}

@Composable
private fun ToolButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, disabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = !disabled, modifier = Modifier.heightIn(min = 48.dp)) {
        Icon(icon, null)
        Text(label)
    }
}

@Composable
private fun ToolChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
}

@Composable
private fun ToggleChip(label: String, checked: Boolean, disabled: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(selected = checked, onClick = { onChange(!checked) }, enabled = !disabled, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
}

// ── Naming helpers ─────────────────────────────────────────────────────────────────────────────────

private fun ShapeLayer.displayName(): String = when (val content = content) {
    is ShapePrimitiveLayer -> name.ifBlank { content.kind.displayName }
    is ShapePathLayer -> name.ifBlank { "Path" }
    is ShapeBooleanLayer -> name.ifBlank { "Combined" }
}

private fun ShapePath.nodeFor(ref: ShapeNodeRef): ShapeNode? = subpathById(ref.subpathId)?.nodeById(ref.nodeId)

/**
 * The active layer id, asked of the runtime rather than the document.
 *
 * Selection is session state and deliberately absent from [ShapeDocument], so a transform cannot re-derive
 * it from the document it is handed. Going through [ShapeFocusRuntime.activeLayer] also drops an id whose
 * layer no longer exists, which is what makes a transform safe to run against a document that undo has
 * moved out from under it.
 */
private fun ShapeFocusRuntime.activeLayerId(): String? = activeLayer()?.id

// ── Color Studio ───────────────────────────────────────────────────────────────────────────────────

/**
 * Routes the shared Color Studio into shape layers. Every preview and commit goes through the runtime's
 * gesture machinery, so picking a color is one undo step per choice — the same lifecycle Image Focus uses
 * for its border and shadow colors.
 */
private class ShapeColorStudioController(
    private val runtime: ShapeFocusRuntime,
    private val onChanged: () -> Unit,
) {
    val engine = ColorStudioEngine(object : ColorStudioHost {
        override fun preview(target: ColorTarget, paint: ColorPaint) {
            val targetId = target.targetIds.singleOrNull() ?: return
            val next = apply(runtime.document(), targetId, paint)
            if (next == runtime.document()) return
            if (!runtime.hasGesture()) runtime.beginGesture()
            runtime.previewGesture { current -> apply(current, targetId, paint) }
            onChanged()
        }

        override fun commit(target: ColorTarget, paint: ColorPaint, label: String): Boolean {
            val targetId = target.targetIds.singleOrNull() ?: return false
            if (!runtime.hasGesture()) {
                runtime.beginGesture()
                runtime.previewGesture { current -> apply(current, targetId, paint) }
            }
            return runtime.endGesture().also { onChanged() }
        }
    })
    val interactions = ColorStudioInteractionController(engine)

    fun openFill(fill: ColorPaint, changed: () -> Unit) {
        open("fill", fill, changed)
    }

    fun openStroke(stroke: ColorPaint, changed: () -> Unit) {
        open("stroke", stroke, changed)
    }

    fun close(commit: Boolean) {
        if (commit) interactions.done() else {
            interactions.cancel()
            runtime.cancelGesture()
        }
        engine.close()
        onChanged()
    }

    fun cancelAdjustment() {
        interactions.cancelContinuousAdjustment()
        runtime.cancelGesture()
        onChanged()
    }

    private fun open(targetId: String, paint: ColorPaint, changed: () -> Unit) {
        engine.open(
            ColorTarget(
                domain = ColorDomain.CONTENT,
                role = if (targetId == "fill") ColorRole.FILL else ColorRole.STROKE,
                targetIds = listOf(targetId),
                host = "shape",
                supportsGradient = true,
                gradientKinds = GRADIENT_KINDS,
            ),
            paint,
        )
        changed()
    }

    private fun apply(document: ShapeDocument, targetId: String, paint: ColorPaint): ShapeDocument = when (targetId) {
        "fill" -> {
            val layerId = runtime.activeLayerId() ?: return document
            ShapeLayerEditor.setFill(document, layerId, paint)
        }
        "stroke" -> {
            val layerId = runtime.activeLayerId() ?: return document
            ShapeLayerEditor.setStrokePaint(document, layerId, paint)
        }
        else -> document
    }
}

private fun shapeColorActions(controller: ShapeColorStudioController): EditorStyleSurfaceActions =
    EditorStyleSurfaceActions(
        dismiss = controller::close,
        setColorView = { controller.cancelAdjustment(); controller.engine.setView(it) },
        draftColor = controller.engine::draft,
        commitColorDraft = { controller.engine.commitDraft(it) },
        discardColorDraft = { controller.engine.discardDraft(it) },
        beginColorAdjustment = { controller.interactions.beginContinuousAdjustment() },
        previewColor = { controller.engine.previewColor(it) },
        previewAlpha = { controller.engine.previewAlpha(it) },
        finishColorAdjustment = { controller.interactions.finishContinuousAdjustment(it) },
        cancelColorAdjustment = controller::cancelAdjustment,
        chooseColor = { controller.interactions.chooseExplicitColor(it) },
        toggleGradient = { enabled -> controller.interactions.oneShot("Toggle gradient") { setGradientEnabled(enabled) } },
        addGradientStop = { controller.interactions.oneShot("Add gradient stop") { addStopAt(0.5f) } },
        selectGradientStop = { controller.engine.selectStop(it) },
        removeGradientStop = { controller.interactions.oneShot("Remove gradient stop") { removeActiveStop() } },
        setGradientKind = { kind -> controller.interactions.oneShot("Change gradient kind") { setGradientKind(kind) } },
        moveGradientStop = { controller.engine.moveActiveStop(it) },
        setGradientStopOpacity = { controller.engine.previewAlpha(it) },
        setGradientAngle = { controller.engine.setGradientAngle(it) },
        reverseGradient = { controller.interactions.oneShot("Reverse gradient") { reverseGradient() } },
        reorderGradientStop = { delta -> controller.interactions.oneShot("Reorder gradient stop") { reorderActiveStop(delta) } },
        setFontQuery = {}, setFontTab = {}, setFontCategory = {}, setFontRole = {}, setFontScript = {},
        setVariableOnly = {}, selectFont = {}, toggleFavorite = {}, importFont = {}, removeImportedFont = {},
        applyStyle = {}, setStyleNameDraft = {}, createStyle = {}, updateStyle = {}, detachStyle = {},
        clearFormatting = {}, setTokenNameDraft = {}, createColorToken = {}, updateColorToken = {},
        applyColorToken = {},
    )

private val GRADIENT_KINDS = setOf(
    GradientKind.LINEAR,
    GradientKind.RADIAL,
    GradientKind.CONIC,
)
