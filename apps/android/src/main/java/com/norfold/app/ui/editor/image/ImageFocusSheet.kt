package com.norfold.app.ui.editor.image

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Rotate90DegreesCcw
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.norfold.app.domain.ImageBlock
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.domain.style.ColorDomain
import com.norfold.app.domain.style.ColorPaint
import com.norfold.app.domain.style.ColorRole
import com.norfold.app.domain.style.ColorTarget
import com.norfold.app.editor.image.ImageAccessibility
import com.norfold.app.editor.image.ImageAdjustments
import com.norfold.app.editor.image.ImageCompositionGuide
import com.norfold.app.editor.image.ImageCrop
import com.norfold.app.editor.image.ImageCropGeometry
import com.norfold.app.editor.image.ImageCropHandle
import com.norfold.app.editor.image.ImageCropMode
import com.norfold.app.editor.image.ImageEditDocument
import com.norfold.app.editor.image.ImageEffects
import com.norfold.app.editor.image.ImageFocusCommitHost
import com.norfold.app.editor.image.ImageFocusRuntime
import com.norfold.app.editor.image.ImageFrame
import com.norfold.app.editor.image.ImageFrameMode
import com.norfold.app.editor.image.ImageMask
import com.norfold.app.editor.image.ImageMaskKind
import com.norfold.app.editor.image.ImageParameterClipboard
import com.norfold.app.editor.image.ImagePreset
import com.norfold.app.editor.image.ImagePresetStore
import com.norfold.app.editor.image.ImageSourceMetadata
import com.norfold.app.editor.memory.InteractionMemory
import com.norfold.app.editor.memory.MemoryScope
import com.norfold.app.editor.style.ColorStudioEngine
import com.norfold.app.editor.style.ColorStudioHost
import com.norfold.app.editor.style.ColorStudioInteractionController
import com.norfold.app.editor.style.EditorStyleUiState
import com.norfold.app.ui.components.NorfoldBottomSheet
import com.norfold.app.ui.editor.style.EditorStyleSurface
import com.norfold.app.ui.editor.style.EditorStyleSurfaceActions
import java.util.UUID

private enum class ImageFocusSection(val label: String) {
    CROP("Crop"), FRAME("Frame"), MASK("Mask"), ADJUST("Adjust"), EFFECTS("Effects"),
    ARRANGE("Arrange"), ACCESSIBILITY("Accessibility"), PRESETS("Presets"),
}

private object ImageFocusSharedState {
    private const val PRESETS = "image.focus.presets"
    private const val CLIPBOARD = "image.focus.clipboard"
    private val memory = InteractionMemory()
    val presets: ImagePresetStore
        get() = memory.get<ImagePresetStore>(MemoryScope.USER_PREFERENCE, PRESETS)
            ?: ImagePresetStore().also { memory.put(MemoryScope.USER_PREFERENCE, PRESETS, it) }
    val clipboard: ImageParameterClipboard
        get() = memory.get<ImageParameterClipboard>(MemoryScope.CONTEXT_PREFERENCE, CLIPBOARD)
            ?: ImageParameterClipboard().also { memory.put(MemoryScope.CONTEXT_PREFERENCE, CLIPBOARD, it) }
}

/** One local Image Focus session. Done is the only route to the host's canonical history. */
@Composable
fun ImageFocusSheet(
    block: ImageBlock,
    readOnly: Boolean,
    onCommit: (ImageBlock) -> Boolean,
    onDismiss: () -> Unit,
    onDone: () -> Unit = onDismiss,
    onOpenGuide: (() -> Unit)? = null,
    onCommitted: (() -> Unit)? = null,
) {
    val latestCommit by rememberUpdatedState(onCommit)
    val runtime = remember(block.id, block.source, block.imageEditDocument, readOnly) {
        ImageFocusRuntime(block, ImageFocusCommitHost { _, edited -> latestCommit(edited) }, readOnly)
    }
    var revision by remember { mutableIntStateOf(0) }
    var sectionIndex by remember { mutableIntStateOf(0) }
    var replacementError by remember { mutableStateOf<String?>(null) }
    val colorStudio = remember(runtime) { ImageColorStudioController(runtime) { revision++ } }
    val document = remember(runtime, revision) { runtime.document() }
    val source = remember(runtime, revision) { runtime.source() }
    val controlsDisabled = runtime.isReadOnly() || runtime.isComparingOriginal()
    val section = ImageFocusSection.entries[sectionIndex]
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val inspected = runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            bounds.outWidth > 0 && bounds.outHeight > 0
        }.getOrDefault(false)
        if (!inspected) {
            replacementError = "That image could not be read. The current source was kept."
            return@rememberLauncherForActivityResult
        }
        if (runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isFailure) {
            replacementError = "Access to that image could not be retained. The current source was kept."
            return@rememberLauncherForActivityResult
        }
        if (runtime.replaceSource(
                uri.toString(),
                ImageSourceMetadata(
                    pixelWidth = bounds.outWidth.takeIf { it > 0 },
                    pixelHeight = bounds.outHeight.takeIf { it > 0 },
                    mimeType = context.contentResolver.getType(uri),
                ),
            )
        ) {
            replacementError = null
            revision++
        } else replacementError = "The image source could not be replaced. The current source was kept."
    }

    fun edit(transform: (ImageEditDocument) -> ImageEditDocument) {
        if (runtime.edit(transform)) revision++
    }
    fun undo() { if (runtime.undo()) revision++ }
    fun redo() { if (runtime.redo()) revision++ }

    NorfoldBottomSheet(onDismissRequest = { runtime.cancel(); onDismiss() }) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Image Focus", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        when {
                            runtime.isReadOnly() && readOnly -> "Read mode · editing unavailable"
                            runtime.isReadOnly() -> "This image uses a newer or unreadable edit format"
                            else -> "Non-destructive image editing"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                onOpenGuide?.let { com.norfold.app.ui.editor.guide.GuideEntryButton("Image Focus", it) }
                TextButton(onClick = { runtime.cancel(); onDismiss() }) { Text("Cancel") }
                Button(onClick = {
                    if (colorStudio.engine.isOpen) colorStudio.close(commit = true)
                    if (runtime.done()) { onCommitted?.invoke(); onDone() }
                }, enabled = !runtime.isReadOnly()) { Text("Done") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::undo, enabled = runtime.canUndo()) { Icon(Icons.AutoMirrored.Outlined.Undo, "Undo image edit") }
                IconButton(onClick = ::redo, enabled = runtime.canRedo()) { Icon(Icons.AutoMirrored.Outlined.Redo, "Redo image edit") }
                OutlinedButton(
                    onClick = {
                        if (runtime.setCompareOriginal(!runtime.isComparingOriginal())) revision++
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(if (runtime.isComparingOriginal()) "Show edited" else "Compare original") }
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("image/*")) },
                    enabled = !controlsDisabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Icon(Icons.Outlined.Image, null); Text("Replace") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { if (runtime.resetAllEdits()) revision++ },
                    enabled = !controlsDisabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Reset entire edit") }
                replacementError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f)) }
            }
            Box(Modifier.fillMaxWidth().height(280.dp)) {
                ImageFocusRenderer(
                    block = block,
                    documentOverride = document,
                    sourceOverride = source,
                    modifier = Modifier.fillMaxWidth().height(280.dp),
                )
                if (section == ImageFocusSection.CROP) {
                    ImageCropGuides(document, Modifier.fillMaxWidth().height(280.dp))
                    ImageCropOverlay(document, runtime, controlsDisabled, { revision++ }, Modifier.fillMaxWidth().height(280.dp))
                } else if (section == ImageFocusSection.FRAME) {
                    ImageFrameGestureOverlay(document, runtime, controlsDisabled, { revision++ }, Modifier.fillMaxWidth().height(280.dp))
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ImageFocusSection.entries.forEachIndexed { index, item ->
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
                        documentColors = listOf(document.border.color, document.shadow.color).distinct(),
                        recentColors = colorStudio.engine.recentColors(),
                    ),
                    fontRows = emptyList(),
                    actions = imageColorActions(colorStudio),
                    onOpenGuide = onOpenGuide,
                )
            } else when (section) {
                ImageFocusSection.CROP -> CropSection(document, controlsDisabled, runtime, { revision++ }, ::edit)
                ImageFocusSection.FRAME -> FrameSection(document, controlsDisabled, runtime, { revision++ }, ::edit)
                ImageFocusSection.MASK -> MaskSection(document, controlsDisabled, runtime, { revision++ }, ::edit)
                ImageFocusSection.ADJUST -> AdjustSection(document, controlsDisabled, runtime, { revision++ })
                ImageFocusSection.EFFECTS -> EffectsSection(document, controlsDisabled, runtime, colorStudio, { revision++ }, ::edit)
                ImageFocusSection.ARRANGE -> ArrangeSection()
                ImageFocusSection.ACCESSIBILITY -> AccessibilitySection(document, controlsDisabled, ::edit)
                ImageFocusSection.PRESETS -> PresetsSection(document, controlsDisabled, runtime) { revision++ }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun CropSection(
    document: ImageEditDocument,
    disabled: Boolean,
    runtime: ImageFocusRuntime,
    changed: () -> Unit,
    edit: ((ImageEditDocument) -> ImageEditDocument) -> Unit,
) {
    EnumChips("Crop ratio", ImageCropMode.entries, document.crop.mode, disabled) { mode ->
        edit { it.copy(crop = ImageCropGeometry.applyMode(it.crop, it.sourceMetadata, mode)) }
    }
    EnumChips("Composition guide", ImageCompositionGuide.entries, document.crop.guide, disabled) { guide ->
        edit { it.copy(crop = it.crop.copy(guide = guide)) }
    }
    ImageSlider("Left edge", document.crop.bounds.left, 0f..0.99f, 0f, disabled, runtime, changed) { value, current ->
        val ratio = ImageCropGeometry.aspectRatio(current.crop.mode, current.sourceMetadata, current.crop.quarterTurns)
        current.copy(crop = current.crop.copy(bounds = ImageCropGeometry.drag(current.crop.bounds, ImageCropHandle.LEFT, value - current.crop.bounds.left, 0f, ratio)))
    }
    ImageSlider("Top edge", document.crop.bounds.top, 0f..0.99f, 0f, disabled, runtime, changed) { value, current ->
        val ratio = ImageCropGeometry.aspectRatio(current.crop.mode, current.sourceMetadata, current.crop.quarterTurns)
        current.copy(crop = current.crop.copy(bounds = ImageCropGeometry.drag(current.crop.bounds, ImageCropHandle.TOP, 0f, value - current.crop.bounds.top, ratio)))
    }
    ImageSlider("Crop width", document.crop.bounds.width, 0.01f..1f, 1f, disabled, runtime, changed) { value, current ->
        val ratio = ImageCropGeometry.aspectRatio(current.crop.mode, current.sourceMetadata, current.crop.quarterTurns)
        current.copy(crop = current.crop.copy(bounds = ImageCropGeometry.drag(current.crop.bounds, ImageCropHandle.RIGHT, value - current.crop.bounds.width, 0f, ratio)))
    }
    ImageSlider("Crop height", document.crop.bounds.height, 0.01f..1f, 1f, disabled, runtime, changed) { value, current ->
        val ratio = ImageCropGeometry.aspectRatio(current.crop.mode, current.sourceMetadata, current.crop.quarterTurns)
        current.copy(crop = current.crop.copy(bounds = ImageCropGeometry.drag(current.crop.bounds, ImageCropHandle.BOTTOM, 0f, value - current.crop.bounds.height, ratio)))
    }
    ImageSlider("Straighten", document.crop.straightenDegrees, -45f..45f, 0f, disabled, runtime, changed) { value, current ->
        current.copy(crop = current.crop.copy(straightenDegrees = value))
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToolButton("Rotate 90°", Icons.Outlined.Rotate90DegreesCcw, disabled) {
            edit {
                val rotated = it.crop.copy(quarterTurns = it.crop.quarterTurns + 1).normalized()
                it.copy(crop = ImageCropGeometry.applyMode(rotated, it.sourceMetadata, rotated.mode))
            }
        }
        ToolButton("Flip horizontally", Icons.Outlined.Flip, disabled) {
            edit { it.copy(crop = it.crop.copy(flipHorizontal = !it.crop.flipHorizontal)) }
        }
        ToolButton("Flip vertically", Icons.Outlined.FlipToBack, disabled) {
            edit { it.copy(crop = it.crop.copy(flipVertical = !it.crop.flipVertical)) }
        }
        OutlinedButton(onClick = { if (runtime.resetCrop()) changed() }, enabled = !disabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reset crop") }
    }
    ImageSlider("Horizontal perspective", document.crop.perspectiveHorizontal, -1f..1f, 0f, disabled, runtime, changed) { value, current ->
        current.copy(crop = current.crop.copy(perspectiveHorizontal = value))
    }
    ImageSlider("Vertical perspective", document.crop.perspectiveVertical, -1f..1f, 0f, disabled, runtime, changed) { value, current ->
        current.copy(crop = current.crop.copy(perspectiveVertical = value))
    }
}

@Composable
private fun FrameSection(document: ImageEditDocument, disabled: Boolean, runtime: ImageFocusRuntime, changed: () -> Unit, edit: ((ImageEditDocument) -> ImageEditDocument) -> Unit) {
    EnumChips("Frame mode", ImageFrameMode.entries, document.frame.mode, disabled) { mode -> edit { it.copy(frame = it.frame.copy(mode = mode)) } }
    listOf(
        Triple("Scale", document.frame.scale, 0.1f..4f), Triple("Focal point X", document.frame.focalX, 0f..1f),
        Triple("Focal point Y", document.frame.focalY, 0f..1f), Triple("Horizontal position", document.frame.offsetX, -1f..1f),
        Triple("Vertical position", document.frame.offsetY, -1f..1f),
    ).forEach { (label, value, range) ->
        val neutral = when (label) { "Scale" -> 1f; "Focal point X", "Focal point Y" -> 0.5f; else -> 0f }
        ImageSlider(label, value, range, neutral, disabled, runtime, changed) { next, current ->
            val frame = when (label) {
                "Scale" -> current.frame.copy(scale = next)
                "Focal point X" -> current.frame.copy(focalX = next)
                "Focal point Y" -> current.frame.copy(focalY = next)
                "Horizontal position" -> current.frame.copy(offsetX = next)
                else -> current.frame.copy(offsetY = next)
            }
            current.copy(frame = frame)
        }
    }
    OutlinedButton(onClick = { if (runtime.resetFrame()) changed() }, enabled = !disabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reset frame") }
}

@Composable
private fun MaskSection(document: ImageEditDocument, disabled: Boolean, runtime: ImageFocusRuntime, changed: () -> Unit, edit: ((ImageEditDocument) -> ImageEditDocument) -> Unit) {
    EnumChips("Mask", ImageMaskKind.entries, document.mask.kind, disabled) { kind -> edit { it.copy(mask = it.mask.copy(kind = kind)) } }
    if (document.mask.kind == ImageMaskKind.ROUNDED_RECTANGLE) {
        ImageSlider("Corner radius", document.mask.cornerRadius, 0f..0.5f, 0.12f, disabled, runtime, changed) { value, current ->
            current.copy(mask = current.mask.copy(cornerRadius = value))
        }
    }
    OutlinedButton(onClick = { if (runtime.resetMask()) changed() }, enabled = !disabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reset mask") }
}

@Composable
private fun AdjustSection(document: ImageEditDocument, disabled: Boolean, runtime: ImageFocusRuntime, changed: () -> Unit) {
    val values = listOf(
        "Exposure" to document.adjustments.exposure, "Contrast" to document.adjustments.contrast,
        "Highlights" to document.adjustments.highlights, "Shadows" to document.adjustments.shadows,
        "Temperature" to document.adjustments.temperature, "Tint" to document.adjustments.tint,
        "Saturation" to document.adjustments.saturation, "Sharpness" to document.adjustments.sharpness,
        "Blur" to document.adjustments.blur,
    )
    values.forEach { (label, value) ->
        val range = if (label == "Blur" || label == "Sharpness") 0f..1f else -1f..1f
        ImageSlider(label, value, range, 0f, disabled, runtime, changed) { next, current ->
            val a = current.adjustments
            val adjusted = when (label) {
                "Exposure" -> a.copy(exposure = next); "Contrast" -> a.copy(contrast = next)
                "Highlights" -> a.copy(highlights = next); "Shadows" -> a.copy(shadows = next)
                "Temperature" -> a.copy(temperature = next); "Tint" -> a.copy(tint = next)
                "Saturation" -> a.copy(saturation = next); "Sharpness" -> a.copy(sharpness = next)
                else -> a.copy(blur = next)
            }
            current.copy(adjustments = adjusted)
        }
    }
    OutlinedButton(onClick = { if (runtime.resetAdjustments()) changed() }, enabled = !disabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reset adjustments") }
}

@Composable
private fun EffectsSection(document: ImageEditDocument, disabled: Boolean, runtime: ImageFocusRuntime, colorStudio: ImageColorStudioController, changed: () -> Unit, edit: ((ImageEditDocument) -> ImageEditDocument) -> Unit) {
    listOf("Opacity" to document.effects.opacity, "Monochrome" to document.effects.monochrome, "Sepia" to document.effects.sepia, "Vignette" to document.effects.vignette).forEach { (label, value) ->
        ImageSlider(label, value, 0f..1f, if (label == "Opacity") 1f else 0f, disabled, runtime, changed) { next, current ->
            val effects = when (label) {
                "Opacity" -> current.effects.copy(opacity = next); "Monochrome" -> current.effects.copy(monochrome = next)
                "Sepia" -> current.effects.copy(sepia = next); else -> current.effects.copy(vignette = next)
            }
            current.copy(effects = effects)
        }
    }
    ToggleRow("Border", document.border.enabled, disabled) { value -> edit { it.copy(border = it.border.copy(enabled = value)) } }
    if (document.border.enabled) {
        ImageSlider("Border width", document.border.widthDp, 0f..32f, 1f, disabled, runtime, changed) { next, current -> current.copy(border = current.border.copy(widthDp = next)) }
        OutlinedButton(onClick = { colorStudio.openBorder(document.border.color) }, enabled = !disabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Choose border color") }
    }
    ToggleRow("Shadow", document.shadow.enabled, disabled) { value -> edit { it.copy(shadow = it.shadow.copy(enabled = value)) } }
    if (document.shadow.enabled) {
        ImageSlider("Shadow blur", document.shadow.blurDp, 0f..48f, 8f, disabled, runtime, changed) { next, current -> current.copy(shadow = current.shadow.copy(blurDp = next)) }
        ImageSlider("Shadow horizontal offset", document.shadow.offsetXDp, -48f..48f, 0f, disabled, runtime, changed) { next, current -> current.copy(shadow = current.shadow.copy(offsetXDp = next)) }
        ImageSlider("Shadow vertical offset", document.shadow.offsetYDp, -48f..48f, 4f, disabled, runtime, changed) { next, current -> current.copy(shadow = current.shadow.copy(offsetYDp = next)) }
        OutlinedButton(onClick = { colorStudio.openShadow(document.shadow.color) }, enabled = !disabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Choose shadow color") }
    }
    OutlinedButton(onClick = { if (runtime.resetEffects()) changed() }, enabled = !disabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reset effects") }
}

@Composable
private fun ArrangeSection() {
    Text("Image Focus never changes page, z-order, scene geometry, or Flow block placement.", style = MaterialTheme.typography.titleSmall)
    Text("Use the host's Arrange controls for placement. Frame controls change how the image fits inside that placement.", color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AccessibilitySection(document: ImageEditDocument, disabled: Boolean, edit: ((ImageEditDocument) -> ImageEditDocument) -> Unit) {
    ToggleRow("Decorative image", document.accessibility.decorative, disabled) { decorative ->
        edit { it.copy(accessibility = it.accessibility.copy(decorative = decorative)) }
    }
    OutlinedTextField(
        value = document.accessibility.alternativeText,
        onValueChange = { value -> edit { it.copy(accessibility = ImageAccessibility(value, it.accessibility.decorative)) } },
        enabled = !disabled && !document.accessibility.decorative,
        label = { Text("Alternative text") },
        supportingText = { Text("Describe the image's purpose. This is separate from the visible caption.") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
    )
    if (!document.accessibility.decorative && document.accessibility.alternativeText.isBlank()) {
        Text("Alternative text is empty. Screen readers will announce this only as an image.", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun PresetsSection(document: ImageEditDocument, disabled: Boolean, runtime: ImageFocusRuntime, changed: () -> Unit) {
    var presetName by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { ImageFocusSharedState.clipboard.copy(document) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.ContentCopy, null); Text("Copy edits")
        }
        OutlinedButton(
            onClick = { ImageFocusSharedState.clipboard.peek()?.let { if (runtime.applyParameters(it)) changed() } },
            enabled = !disabled && ImageFocusSharedState.clipboard.peek() != null,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Icon(Icons.Outlined.ContentPaste, null); Text("Paste edits") }
    }
    OutlinedTextField(value = presetName, onValueChange = { presetName = it.take(128) }, label = { Text("Preset name") }, enabled = !disabled, modifier = Modifier.fillMaxWidth())
    Button(
        onClick = {
            if (ImageFocusSharedState.presets.put(ImagePreset(UUID.randomUUID().toString(), presetName, document.parameters()))) presetName = ""
        },
        enabled = !disabled && presetName.isNotBlank(),
        modifier = Modifier.heightIn(min = 48.dp),
    ) { Text("Save current edits") }
    ImageFocusSharedState.presets.all().forEach { preset ->
        var renameValue by remember(preset.id, preset.name) { mutableStateOf(preset.name) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = renameValue,
                onValueChange = { renameValue = it.take(128) },
                label = { Text("Preset name") },
                singleLine = true,
                enabled = !disabled,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { if (runtime.applyParameters(preset.parameters)) changed() }, enabled = !disabled) { Text("Apply") }
            TextButton(onClick = { if (ImageFocusSharedState.presets.rename(preset.id, renameValue)) changed() }, enabled = !disabled && renameValue.isNotBlank()) { Text("Rename") }
            TextButton(onClick = { ImageFocusSharedState.presets.remove(preset.id); changed() }, enabled = !disabled) { Text("Delete") }
        }
    }
    OutlinedButton(
        onClick = { ImageFocusSharedState.presets.resetToDefaults(); changed() },
        enabled = !disabled && ImageFocusSharedState.presets.all().isNotEmpty(),
        modifier = Modifier.heightIn(min = 48.dp),
    ) { Text("Reset preset list") }
    Text("Presets and copied edits never include the image source, caption, alternative text, block identity, or placement.", color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun <T : Enum<T>> EnumChips(label: String, values: List<T>, selected: T, disabled: Boolean, onSelect: (T) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, enabled = !disabled, label = { Text(value.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)) }, modifier = Modifier.heightIn(min = 48.dp))
        }
    }
}

@Composable
private fun ImageSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    neutralValue: Float,
    disabled: Boolean,
    runtime: ImageFocusRuntime,
    changed: () -> Unit,
    transform: (Float, ImageEditDocument) -> ImageEditDocument,
) {
    var exactValue by remember(label, value) { mutableStateOf(formatImageValue(value)) }
    val parsed = exactValue.toFloatOrNull()
    val exactValid = parsed != null && parsed.isFinite() && parsed in range
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            TextButton(
                onClick = { if (runtime.edit { current -> transform(neutralValue, current) }) changed() },
                enabled = !disabled && value != neutralValue,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Reset") }
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { next ->
                if (!runtime.hasGesture()) runtime.beginGesture()
                if (runtime.previewGesture { current -> transform(next, current) }) changed()
            },
            onValueChangeFinished = { if (runtime.endGesture()) changed() },
            valueRange = range,
            enabled = !disabled,
            modifier = Modifier.semantics { contentDescription = label },
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = exactValue,
                onValueChange = { exactValue = it.take(12) },
                label = { Text("Value") },
                isError = exactValue.isNotBlank() && !exactValid,
                supportingText = if (exactValue.isNotBlank() && !exactValid) {
                    { Text("${formatImageValue(range.start)} to ${formatImageValue(range.endInclusive)}") }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                enabled = !disabled,
                modifier = Modifier.width(144.dp),
            )
            TextButton(
                onClick = {
                    val next = parsed ?: return@TextButton
                    if (runtime.edit { current -> transform(next, current) }) changed()
                },
                enabled = !disabled && exactValid && parsed != value,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Apply") }
            Text("Current ${formatImageValue(value)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatImageValue(value: Float): String = "%.3f".format(java.util.Locale.ROOT, value).trimEnd('0').trimEnd('.')

@Composable
private fun ToggleRow(label: String, checked: Boolean, disabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked = checked, onCheckedChange = onChange, enabled = !disabled)
    }
}

@Composable
private fun ToolButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, disabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = !disabled, modifier = Modifier.heightIn(min = 48.dp)) { Icon(icon, null); Text(label) }
}

private class ImageColorStudioController(
    private val runtime: ImageFocusRuntime,
    private val onChanged: () -> Unit,
) {
    val engine = ColorStudioEngine(object : ColorStudioHost {
        override fun preview(target: ColorTarget, paint: ColorPaint) {
            val targetId = target.targetIds.singleOrNull()
            val next = apply(runtime.document(), targetId, paint.representative())
            if (next == runtime.document()) return
            if (!runtime.hasGesture()) runtime.beginGesture()
            runtime.previewGesture { current -> apply(current, targetId, paint.representative()) }
            onChanged()
        }

        override fun commit(target: ColorTarget, paint: ColorPaint, label: String): Boolean {
            if (!runtime.hasGesture()) {
                runtime.beginGesture()
                runtime.previewGesture { current -> apply(current, target.targetIds.singleOrNull(), paint.representative()) }
            }
            return runtime.endGesture().also { onChanged() }
        }
    })
    val interactions = ColorStudioInteractionController(engine)

    fun openBorder(color: ColorValue) = open("border", color)
    fun openShadow(color: ColorValue) = open("shadow", color)

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

    private fun open(targetId: String, color: ColorValue) {
        engine.open(ColorTarget(ColorDomain.CONTENT, ColorRole.STROKE, listOf(targetId), host = "image"), ColorPaint.Flat(color))
        onChanged()
    }

    private fun apply(document: ImageEditDocument, targetId: String?, color: ColorValue): ImageEditDocument = when (targetId) {
        "border" -> document.copy(border = document.border.copy(color = color))
        "shadow" -> document.copy(shadow = document.shadow.copy(color = color))
        else -> document
    }
}

private fun imageColorActions(controller: ImageColorStudioController): EditorStyleSurfaceActions =
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
        toggleGradient = {}, addGradientStop = {}, selectGradientStop = {}, removeGradientStop = {},
        setGradientKind = {}, moveGradientStop = {}, setGradientStopOpacity = {}, setGradientAngle = {},
        reverseGradient = {}, reorderGradientStop = {}, setFontQuery = {}, setFontTab = {},
        setFontCategory = {}, setFontRole = {}, setFontScript = {}, setVariableOnly = {}, selectFont = {},
        toggleFavorite = {}, importFont = {}, removeImportedFont = {}, applyStyle = {},
        setStyleNameDraft = {}, createStyle = {}, updateStyle = {}, detachStyle = {}, clearFormatting = {},
        setTokenNameDraft = {}, createColorToken = {}, updateColorToken = {}, applyColorToken = {},
    )
