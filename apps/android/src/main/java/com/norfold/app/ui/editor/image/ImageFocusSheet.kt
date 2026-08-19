package com.norfold.app.ui.editor.image

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Rotate90DegreesCcw
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.PhotoSizeSelectLarge
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewQuilt
import androidx.compose.material.icons.outlined.Title
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
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.norfold.app.domain.ImageBlock
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.domain.style.ColorDomain
import com.norfold.app.domain.style.ColorPaint
import com.norfold.app.domain.style.ColorRole
import com.norfold.app.domain.style.ColorTarget
import com.norfold.app.editor.history.FocusHistoryOwner
import com.norfold.app.editor.image.ImageAccessibility
import com.norfold.app.editor.image.ImageAdjustments
import com.norfold.app.editor.image.ImageCompositionGuide
import com.norfold.app.editor.image.ImageCrop
import com.norfold.app.editor.image.ImageCropGeometry
import com.norfold.app.editor.image.ImageCropHandle
import com.norfold.app.editor.image.ImageCropMode
import com.norfold.app.editor.image.ImageEditDocument
import com.norfold.app.editor.image.ImageEffects
import com.norfold.app.editor.image.ImageFilterKind
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
import com.norfold.app.editor.image.ImageMarkupLabel
import com.norfold.app.editor.image.ImageMarkupLabelKind
import com.norfold.app.editor.image.ImageMarkupPoint
import com.norfold.app.editor.sticker.Sticker
import com.norfold.app.editor.sticker.StickerCatalog
import com.norfold.app.editor.sticker.StickerCategory
import com.norfold.app.editor.memory.InteractionMemory
import com.norfold.app.editor.memory.MemoryScope
import com.norfold.app.editor.style.ColorStudioEngine
import com.norfold.app.editor.style.ColorStudioHost
import com.norfold.app.editor.style.ColorStudioInteractionController
import com.norfold.app.editor.style.EditorStyleUiState
import com.norfold.app.ui.components.NorfoldColorSwatch
import com.norfold.app.ui.editor.style.EditorStyleSurface
import com.norfold.app.ui.editor.style.EditorStyleSurfaceActions
import java.util.UUID
import com.norfold.app.ui.rememberChromeFocus
import com.norfold.app.domain.style.ChromeGeometry

private enum class ImageFocusSection(val label: String) { CROP("Crop"), ADJUST("Adjust"), EFFECTS("Filters"), MARKUP("Markup") }

/** The image-first rails deliberately mirror the shared workflow, not a per-mode settings form. */
private enum class ImageStudioMode(val label: String, val icon: ImageVector, val section: ImageFocusSection) {
    CROP("Crop", Icons.Outlined.Crop, ImageFocusSection.CROP),
    ADJUST("Adjust", Icons.Outlined.Tune, ImageFocusSection.ADJUST),
    FILTER("Filters", Icons.Outlined.FilterAlt, ImageFocusSection.EFFECTS),
    MARKUP("Markup", Icons.Outlined.Title, ImageFocusSection.MARKUP),
}

@Composable
private fun ImagePrimaryTab(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val fill = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val focus = rememberChromeFocus()
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = fill,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        interactionSource = focus.source,
        modifier = modifier.then(focus.ringOnShape(12f, fill)),
    ) {
        Row(
            Modifier.heightIn(min = 48.dp).padding(horizontal = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ImageFocusCropCard(
    document: ImageEditDocument,
    disabled: Boolean,
    runtime: ImageFocusRuntime,
    changed: () -> Unit,
    edit: ((ImageEditDocument) -> ImageEditDocument) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Crop", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text("Frame the image to guide attention to what matters.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { if (runtime.resetCrop()) changed() }, enabled = !disabled) { Text("Reset") }
            }
            Row(Modifier.fillMaxWidth()) {
                listOf(
                    ImageCropMode.FREE,
                    ImageCropMode.ORIGINAL,
                    ImageCropMode.RATIO_16_9,
                    ImageCropMode.RATIO_4_3,
                    ImageCropMode.RATIO_1_1,
                ).forEach { mode ->
                    val label = when (mode) {
                        ImageCropMode.FREE -> "Free\nCustom"
                        ImageCropMode.ORIGINAL -> "Original\n16:9"
                        ImageCropMode.RATIO_16_9 -> "Landscape\n16:10"
                        ImageCropMode.RATIO_4_3 -> "Portrait\n4:5"
                        ImageCropMode.RATIO_1_1 -> "Square\n1:1"
                        else -> mode.name.lowercase().replace('_', ':')
                    }
                    val modeSelected = document.crop.mode == mode
                    val modeFill = if (modeSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                    val modeFocus = rememberChromeFocus()
                    Surface(
                        onClick = { edit { it.copy(crop = ImageCropGeometry.applyMode(it.crop, it.sourceMetadata, mode)) } },
                        enabled = !disabled,
                        color = modeFill,
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (modeSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        interactionSource = modeFocus.source,
                        // Radius 0: this `Surface` passes no `shape`, so it draws `RectangleShape`. A ring
                        // with a rounded radius on square chrome would leave four visible gaps at the corners.
                        modifier = Modifier.weight(1f).height(72.dp).then(modeFocus.ringOnShape(0f, modeFill)),
                    ) {
                        Box(contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
            ImageCompactSlider("Zoom", document.frame.scale, 1f..4f, disabled, runtime, changed) { value, current ->
                current.copy(frame = current.frame.copy(scale = value))
            }
            ImageCompactSlider("Rotate", document.crop.quarterTurns * 90f, -180f..180f, disabled, runtime, changed) { value, current ->
                current.copy(crop = current.crop.copy(quarterTurns = (value / 90f).toInt()))
            }
            ImageCompactSlider("Straighten", document.crop.straightenDegrees, -45f..45f, disabled, runtime, changed) { value, current ->
                current.copy(crop = current.crop.copy(straightenDegrees = value))
            }
        }
    }
}

@Composable
private fun ImageCompactSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    disabled: Boolean,
    runtime: ImageFocusRuntime,
    changed: () -> Unit,
    transform: (Float, ImageEditDocument) -> ImageEditDocument,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(86.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { next ->
                if (!runtime.hasGesture()) runtime.beginGesture()
                if (runtime.previewGesture { transform(next, it) }) changed()
            },
            onValueChangeFinished = { if (runtime.endGesture()) changed() },
            valueRange = range,
            enabled = !disabled,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (label == "Zoom") "${(value * 100).toInt()}%" else "${value.toInt()}°",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(58.dp),
        )
    }
}

@Composable
private fun ImageFocusCaptionCard(caption: String, disabled: Boolean, onCaptionChange: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Caption", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text("Add helpful context for your readers.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = caption,
                onValueChange = onCaptionChange,
                enabled = !disabled,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("${caption.length}/${ImageFocusRuntime.MAX_CAPTION_LENGTH}", modifier = Modifier.align(Alignment.End), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ImageReplaceCard(enabled: Boolean, onReplace: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Replace image", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text("Swap the image while keeping your edits.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onReplace, enabled = enabled) { Text("Replace…") }
        }
    }
}

/** Namespaces the focus id so it can never collide with a surface id or be mistaken for one. */
private const val FOCUS_ID_PREFIX = "image.focus."

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

/**
 * Legacy images did not persist source dimensions. Crop interaction still has to share the exact
 * fitted photo bounds with Coil, so read only the headers here rather than decoding a bitmap.
 */
private fun inspectImageAspectRatio(context: android.content.Context, source: String): Float? {
    if (source.isBlank()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val readable = runCatching {
        context.contentResolver.openInputStream(Uri.parse(source))?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        bounds.outWidth > 0 && bounds.outHeight > 0
    }.getOrDefault(false)
    return if (readable) {
        (bounds.outWidth.toFloat() / bounds.outHeight.toFloat()).takeIf { it.isFinite() && it > 0f }
    } else null
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
    /**
     * Hands the host this session's undo ownership while the sheet is open, and null when it closes
     * (spec §13.5). Hosts that do not route undo may leave it null.
     */
    onFocusHistoryOwner: ((FocusHistoryOwner?) -> Unit)? = null,
) {
    val latestCommit by rememberUpdatedState(onCommit)
    val runtime = remember(block.id, block.source, block.imageEditDocument, readOnly) {
        ImageFocusRuntime(block, ImageFocusCommitHost { _, edited -> latestCommit(edited) }, readOnly)
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
    var sectionIndex by remember { mutableIntStateOf(ImageFocusSection.CROP.ordinal) }
    var adjustmentKey by remember { mutableStateOf(ImageAdjustmentKey.EXPOSURE) }
    var markupColor by remember { mutableStateOf(ColorValue(244, 67, 54)) }
    var markupStrokeWidth by remember { mutableStateOf(0.014f) }
    var markupTool by remember { mutableStateOf(ImageMarkupTool.SELECT) }
    var selectedMarkupIndex by remember { mutableStateOf<Int?>(null) }
    var freeDistortionEnabled by remember { mutableStateOf(false) }
    var stickerPickerOpen by remember { mutableStateOf(false) }
    var signatureSheetOpen by remember { mutableStateOf(false) }
    var replacementError by remember { mutableStateOf<String?>(null) }
    val colorStudio = remember(runtime) { ImageColorStudioController(runtime) { revision++ } }
    val document = remember(runtime, revision) { runtime.document() }
    val source = remember(runtime, revision) { runtime.source() }
    val controlsDisabled = runtime.isReadOnly() || runtime.isComparingOriginal()
    val section = ImageFocusSection.entries[sectionIndex]
    val context = LocalContext.current
    val sourceAspectRatio = remember(source, document.sourceMetadata) {
        document.sourceMetadata.pixelWidth
            ?.toFloat()
            ?.div(document.sourceMetadata.pixelHeight?.toFloat()?.coerceAtLeast(1f) ?: 1f)
            ?.takeIf { it.isFinite() && it > 0f }
            ?: inspectImageAspectRatio(context, source)
            ?: 1f
    }
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

    // Image Focus is a canvas, not a colored card. The theme supplies the surrounding page in
    // light and dark mode; this editor does not introduce a third backdrop of its own.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { runtime.cancel(); onDismiss() }) {
                    Icon(Icons.Outlined.Close, "Discard image edits")
                }
                VerticalDivider(Modifier.height(28.dp).padding(horizontal = 6.dp))
                IconButton(onClick = ::undo, enabled = runtime.canUndo()) {
                    Icon(Icons.AutoMirrored.Outlined.Undo, "Undo image edit")
                }
                IconButton(onClick = ::redo, enabled = runtime.canRedo()) {
                    Icon(Icons.AutoMirrored.Outlined.Redo, "Redo image edit")
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        if (colorStudio.engine.isOpen) colorStudio.close(commit = true)
                        if (runtime.done()) { onCommitted?.invoke(); onDone() }
                    },
                    enabled = !runtime.isReadOnly(),
                ) { Text("Save", style = MaterialTheme.typography.titleMedium) }
                IconButton(onClick = { if (runtime.resetAllEdits()) revision++ }) {
                    Icon(Icons.Outlined.MoreVert, "Reset all image edits")
                }
            }

            // The source is centred in a stage bounded by the command bar and the tool deck. Safe-gesture
            // padding belongs to the image interaction surface, not to the editor shell.
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .navigationBarsPadding(),
                contentAlignment = Alignment.Center,
            ) {
                // Crop interaction must use the same bounds as the rendered source. A full-stage
                // overlay made the handles include the Fit letterbox around a landscape image.
                // The outer Box deliberately stays large: it is the editor canvas, not the crop frame.
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    // The crop viewport rotates with the source. Keeping a landscape viewport after a
                    // 90-degree turn technically kept the source visible, but reduced it to a tiny
                    // thumbnail at the centre. A portrait source deserves a portrait editor frame.
                    val isQuarterTurnSideways = document.crop.quarterTurns % 2 != 0
                    val viewportAspectRatio = if (isQuarterTurnSideways) {
                        1f / sourceAspectRatio.coerceAtLeast(0.01f)
                    } else sourceAspectRatio
                    val imageWidth = minOf(maxWidth, maxHeight * viewportAspectRatio)
                    val imageHeight = imageWidth / viewportAspectRatio
                    Box(Modifier.width(imageWidth).height(imageHeight)) {
                        ImageFocusRenderer(
                            block = block,
                            documentOverride = document,
                            sourceOverride = source,
                            // Crop mode intentionally keeps the whole source visible while the crop frame is
                            // manipulated, including during a rotation. The saved view fills the frame only
                            // after leaving Crop mode or saving.
                            cropPreview = section == ImageFocusSection.CROP,
                            modifier = Modifier.fillMaxSize(),
                        )
                        when (section) {
                            ImageFocusSection.CROP -> ImageCropOverlay(
                                document, runtime, controlsDisabled, { revision++ },
                                freeTransformEnabled = freeDistortionEnabled,
                            )
                            ImageFocusSection.MARKUP -> ImageMarkupOverlay(
                                document = document,
                                runtime = runtime,
                                disabled = controlsDisabled,
                                strokeColor = markupColor,
                                strokeWidth = markupStrokeWidth,
                                tool = markupTool,
                                selectedLabelIndex = selectedMarkupIndex,
                                onSelectedLabelChange = { selectedMarkupIndex = it },
                                onChanged = { revision++ },
                            )
                            else -> Unit
                        }
                    }
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                tonalElevation = 3.dp,
                // A tall tool (notably Markup at larger font scales) must never displace the
                // command bar or image stage. Only the detail panel scrolls; the four-mode rail
                // remains anchored above the gesture inset.
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .navigationBarsPadding(),
            ) {
                Column(Modifier.padding(top = 8.dp)) {
                    val detailScroll = rememberScrollState()
                    Column(
                        Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(detailScroll),
                    ) {
                        ImageFocusStudioControls(
                            block = block,
                            source = source,
                            section = section,
                            adjustmentKey = adjustmentKey,
                            onAdjustmentKeyChange = { adjustmentKey = it },
                            markupColor = markupColor,
                            markupStrokeWidth = markupStrokeWidth,
                            onMarkupColorChange = { markupColor = it },
                            onMarkupStrokeWidthChange = { markupStrokeWidth = it },
                            markupTool = markupTool,
                            onMarkupToolChange = { markupTool = it },
                            selectedMarkupIndex = selectedMarkupIndex,
                            onSelectedMarkupIndexChange = { selectedMarkupIndex = it },
                            onOpenStickerPicker = { stickerPickerOpen = true },
                            onOpenSignature = { signatureSheetOpen = true },
                            freeDistortionEnabled = freeDistortionEnabled,
                            onFreeDistortionEnabledChange = { freeDistortionEnabled = it },
                            document = document,
                            runtime = runtime,
                            disabled = controlsDisabled,
                            changed = { revision++ },
                            onEdit = ::edit,
                            onReplace = { picker.launch(arrayOf("image/*")) },
                        )
                        replacementError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ImageStudioMode.entries.forEach { mode ->
                            ImageStudioModeButton(
                                mode = mode,
                                selected = section == mode.section,
                                onClick = { sectionIndex = mode.section.ordinal },
                            )
                        }
                    }
                }
            }
        }
    }

    if (stickerPickerOpen) {
        ImageStickerPicker(
            onDismiss = { stickerPickerOpen = false },
            onPick = { sticker ->
                edit { current ->
                    current.copy(markup = current.markup.copy(labels = current.markup.labels + ImageMarkupLabel(
                        id = sticker.id,
                        kind = ImageMarkupLabelKind.STICKER,
                        text = sticker.label,
                        x = 0.5f,
                        y = 0.5f,
                        size = 0.14f,
                    )))
                }
                selectedMarkupIndex = document.markup.labels.size
                markupTool = ImageMarkupTool.SELECT
                stickerPickerOpen = false
            },
        )
    }
    if (signatureSheetOpen) {
        ImageSignatureSheet(
            onDismiss = { signatureSheetOpen = false },
            onConfirm = { signature ->
                edit { current ->
                    current.copy(markup = current.markup.copy(labels = current.markup.labels + ImageMarkupLabel(
                        id = "signature-${UUID.randomUUID()}",
                        kind = ImageMarkupLabelKind.SIGNATURE,
                        text = "Signature",
                        x = 0.5f,
                        y = 0.6f,
                        size = 0.09f,
                        points = signature,
                    )))
                }
                selectedMarkupIndex = document.markup.labels.size
                markupTool = ImageMarkupTool.SELECT
                signatureSheetOpen = false
            },
        )
    }
}

private enum class ImageAdjustmentKey(val label: String) {
    EXPOSURE("Exposure"),
    BRIGHTNESS("Brightness"),
    CONTRAST("Contrast"),
    HIGHLIGHTS("Highlights"),
    SHADOWS("Shadows"),
    TEMPERATURE("Warmth"),
    TINT("Tint"),
    SATURATION("Saturation"),
    SHARPNESS("Sharpness"),
    VIGNETTE("Vignette"),
}

@Composable
private fun ImageStudioModeButton(mode: ImageStudioMode, selected: Boolean, onClick: () -> Unit) {
    val focus = rememberChromeFocus()
    Surface(
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(18.dp),
        interactionSource = focus.source,
        // The selected button is filled with `primary` — the ring's own default colour — so measuring it
        // against the page would put an accent ring on an accent fill at 1.00:1. An unselected button is
        // transparent over the rail's `surfaceContainer`, which is what it actually shows.
        modifier = Modifier.then(
            focus.ringOnShape(18f, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer),
        ),
    ) {
        Row(
            Modifier.heightIn(min = 44.dp).padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) Icon(mode.icon, null, Modifier.size(18.dp))
            Text(mode.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ImageFocusStudioControls(
    block: ImageBlock,
    source: String,
    section: ImageFocusSection,
    adjustmentKey: ImageAdjustmentKey,
    onAdjustmentKeyChange: (ImageAdjustmentKey) -> Unit,
    markupColor: ColorValue,
    markupStrokeWidth: Float,
    onMarkupColorChange: (ColorValue) -> Unit,
    onMarkupStrokeWidthChange: (Float) -> Unit,
    markupTool: ImageMarkupTool,
    onMarkupToolChange: (ImageMarkupTool) -> Unit,
    selectedMarkupIndex: Int?,
    onSelectedMarkupIndexChange: (Int?) -> Unit,
    onOpenStickerPicker: () -> Unit,
    onOpenSignature: () -> Unit,
    freeDistortionEnabled: Boolean,
    onFreeDistortionEnabledChange: (Boolean) -> Unit,
    document: ImageEditDocument,
    runtime: ImageFocusRuntime,
    disabled: Boolean,
    changed: () -> Unit,
    onEdit: ((ImageEditDocument) -> ImageEditDocument) -> Unit,
    onReplace: () -> Unit,
) {
    when (section) {
        ImageFocusSection.CROP -> ImageStudioCropControls(document, runtime, disabled, changed, onEdit, freeDistortionEnabled, onFreeDistortionEnabledChange)
        ImageFocusSection.ADJUST -> ImageStudioAdjustControls(document, adjustmentKey, onAdjustmentKeyChange, runtime, disabled, changed)
        ImageFocusSection.EFFECTS -> ImageStudioExpandedFilterControls(block, source, document, runtime, disabled, changed, onEdit)
        ImageFocusSection.MARKUP -> ImageStudioMarkupControls(
            document, runtime, disabled, changed, onEdit,
            markupColor, markupStrokeWidth, onMarkupColorChange, onMarkupStrokeWidthChange,
            markupTool, onMarkupToolChange, selectedMarkupIndex, onSelectedMarkupIndexChange,
            onOpenStickerPicker, onOpenSignature,
        )
    }
}

@Composable
private fun ImageStudioCropControls(
    document: ImageEditDocument,
    runtime: ImageFocusRuntime,
    disabled: Boolean,
    changed: () -> Unit,
    edit: ((ImageEditDocument) -> ImageEditDocument) -> Unit,
    freeDistortionEnabled: Boolean,
    onFreeDistortionEnabledChange: (Boolean) -> Unit,
) {
    var longPressLabel by remember { mutableStateOf<String?>(null) }
    var aspectPickerOpen by remember { mutableStateOf(false) }
    var straightenOpen by remember { mutableStateOf(false) }
    // A compact editor deck deliberately replaces the old form-like stack. It follows the reference:
    // a single horizontal action rail, with the detailed aspect/straighten control revealed only when
    // the corresponding tool is selected.
    if (aspectPickerOpen) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                ImageCropMode.FREE to "Free",
                ImageCropMode.ORIGINAL to "Original",
                ImageCropMode.RATIO_1_1 to "1:1",
                ImageCropMode.RATIO_4_3 to "4:3",
                ImageCropMode.RATIO_16_9 to "16:9",
            ).forEach { (mode, label) ->
                FilterChip(
                    selected = document.crop.mode == mode,
                    onClick = {
                        edit { it.copy(crop = ImageCropGeometry.applyMode(it.crop, it.sourceMetadata, mode)) }
                        aspectPickerOpen = false
                    },
                    enabled = !disabled,
                    label = { Text(label) },
                )
            }
        }
        ImageEditorValueRail(
            label = "Crop frame zoom",
            value = 1f / maxOf(document.crop.bounds.width, document.crop.bounds.height).coerceAtLeast(0.01f),
            range = 1f..12f,
            disabled = disabled,
            runtime = runtime,
            changed = changed,
            display = { "${it.toInt()}×" },
        ) { value, current ->
            current.copy(crop = current.crop.copy(bounds = ImageCropGeometry.zoom(current.crop.bounds, value)))
        }
    }
    if (straightenOpen) {
        ImageEditorValueRail(
            label = "Straighten",
            value = document.crop.straightenDegrees,
            range = -45f..45f,
            disabled = disabled,
            runtime = runtime,
            changed = changed,
            display = { "${it.toInt()}°" },
        ) { value, current -> current.copy(crop = current.crop.copy(straightenDegrees = value)) }
    }
    if (freeDistortionEnabled) {
        ImageEditorValueRail(
            label = "Horizontal distortion",
            value = document.crop.perspectiveHorizontal,
            range = -1f..1f,
            disabled = disabled,
            runtime = runtime,
            changed = changed,
            display = { "${(it * 100).toInt()}" },
        ) { value, current ->
            current.copy(crop = current.crop.copy(perspectiveHorizontal = value))
        }
        ImageEditorValueRail(
            label = "Vertical distortion",
            value = document.crop.perspectiveVertical,
            range = -1f..1f,
            disabled = disabled,
            runtime = runtime,
            changed = changed,
            display = { "${(it * 100).toInt()}" },
        ) { value, current ->
            current.copy(crop = current.crop.copy(perspectiveVertical = value))
        }
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ImageTransformAction(Icons.Outlined.PhotoSizeSelectLarge, "Aspect ratio", "Aspect", disabled, onLongPress = { longPressLabel = it }) {
            aspectPickerOpen = !aspectPickerOpen
        }
        ImageTransformAction(Icons.Outlined.Rotate90DegreesCcw, "Rotate 90 degrees", "Rotate", disabled, onLongPress = { longPressLabel = it }) {
            edit {
                val rotated = it.crop.copy(quarterTurns = it.crop.quarterTurns + 1).normalized()
                it.copy(crop = ImageCropGeometry.applyMode(rotated, it.sourceMetadata, rotated.mode))
            }
        }
        // Reset stays among the first visible controls. It must not be hidden beyond a horizontal
        // rail just when the user needs a safe way out of a crop experiment.
        ImageTransformAction(Icons.Outlined.Close, "Reset crop", "Reset", disabled, onLongPress = { longPressLabel = it }) {
            if (runtime.resetCrop()) changed()
        }
        ImageTransformAction(Icons.Outlined.Flip, "Flip horizontally", "Flip H", disabled, onLongPress = { longPressLabel = it }) {
            edit { it.copy(crop = it.crop.copy(flipHorizontal = !it.crop.flipHorizontal)) }
        }
        ImageTransformAction(Icons.Outlined.FlipToBack, "Flip vertically", "Flip V", disabled, onLongPress = { longPressLabel = it }) {
            edit { it.copy(crop = it.crop.copy(flipVertical = !it.crop.flipVertical)) }
        }
        ImageTransformAction(Icons.Outlined.Crop, "Free crop frame", "Free", disabled, onLongPress = { longPressLabel = it }) {
            edit { it.copy(crop = ImageCropGeometry.applyMode(it.crop, it.sourceMetadata, ImageCropMode.FREE)) }
        }
        ImageTransformAction(Icons.Outlined.Tune, "Straighten", "Straighten", disabled, onLongPress = { longPressLabel = it }) {
            straightenOpen = !straightenOpen
        }
        ImageTransformAction(Icons.Outlined.AutoFixHigh, "Free distortion", "Distort", disabled, onLongPress = { longPressLabel = it }) {
            onFreeDistortionEnabledChange(!freeDistortionEnabled)
        }
    }
    longPressLabel?.let { label ->
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.padding(start = 20.dp, top = 2.dp),
        ) { Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) }
    }
}

@Composable
private fun ImageTransformAction(
    icon: ImageVector,
    label: String,
    displayLabel: String = label,
    disabled: Boolean,
    onLongPress: (String) -> Unit,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val fill = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val focus = rememberChromeFocus()
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = fill,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .size(52.dp)
            // `combinedClickable`, not `Surface(onClick =)`, so the ring watches the source the gesture
            // reports on. Measured on whichever fill the selected state paints.
            .then(focus.ringOnShape(16f, fill))
            .combinedClickable(
                enabled = !disabled,
                interactionSource = focus.source,
                indication = androidx.compose.material3.ripple(),
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
                // No `onLongClickLabel`, deliberately: this long-press exists to *reveal the button's name*
                // to a sighted user, and the icon below already carries that name as its content
                // description. A screen reader has therefore already read what the gesture would show, so
                // labelling it would announce a second route to information the user just heard.
                onLongClick = { onLongPress(label) },
            ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, Modifier.size(24.dp))
        }
    }
}

/** Single compact value rail used by every value-based Image Focus mode. */
@Composable
private fun ImageEditorValueRail(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    disabled: Boolean,
    runtime: ImageFocusRuntime,
    changed: () -> Unit,
    display: (Float) -> String,
    transform: (Float, ImageEditDocument) -> ImageEditDocument,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(display(value), style = MaterialTheme.typography.labelLarge)
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
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ImageStudioAdjustControls(
    document: ImageEditDocument,
    key: ImageAdjustmentKey,
    onKeyChange: (ImageAdjustmentKey) -> Unit,
    runtime: ImageFocusRuntime,
    disabled: Boolean,
    changed: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ImageAdjustmentKey.entries.forEach { candidate ->
            FilterChip(selected = key == candidate, onClick = { onKeyChange(candidate) }, label = { Text(candidate.label) })
        }
    }
    val value = document.adjustmentValue(key)
    val range = if (key == ImageAdjustmentKey.VIGNETTE) 0f..1f else -1f..1f
    ImageCompactSlider(key.label, value, range, disabled, runtime, changed) { next, current ->
        current.copy(adjustments = current.adjustments.withValue(key, next))
    }
}

/** Creative effects are an independent layer. They never reset numeric Adjust state. */
@Composable
private fun ImageStudioExpandedFilterControls(
    block: ImageBlock,
    source: String,
    document: ImageEditDocument,
    runtime: ImageFocusRuntime,
    disabled: Boolean,
    changed: () -> Unit,
    edit: ((ImageEditDocument) -> ImageEditDocument) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ImageCreativeFilter.entries.forEachIndexed { index, filter ->
            ImageVisualChoice(
                label = filter.label,
                selected = document.effects.filter == filter.kind,
                swatch = filterSwatch(index),
                enabled = !disabled,
                onClick = { edit { current -> current.copy(effects = current.effects.copy(filter = filter.kind, filterIntensity = 1f)) } },
                preview = {
                    ImageFocusRenderer(
                        block = block,
                        documentOverride = document.copy(effects = document.effects.copy(filter = filter.kind, filterIntensity = 1f)),
                        sourceOverride = source,
                        modifier = Modifier.fillMaxSize(),
                    )
                },
            )
        }
    }
    val current = document.effects.filter
    if (current != ImageFilterKind.NONE) {
        ImageEditorValueRail(
            label = "${ImageCreativeFilter.from(current).label} strength",
            value = document.effects.filterIntensity,
            range = 0f..1f,
            disabled = disabled,
            runtime = runtime,
            changed = changed,
            display = { "${(it * 100).toInt()}%" },
        ) { value, state -> state.copy(effects = state.effects.copy(filterIntensity = value)) }
    }
}

private enum class ImageCreativeFilter(val label: String, val kind: ImageFilterKind) {
    ORIGINAL("Original", ImageFilterKind.NONE),
    MONO("Mono", ImageFilterKind.MONOCHROME),
    SEPIA("Sepia", ImageFilterKind.SEPIA),
    INVERT("Invert", ImageFilterKind.INVERT),
    DUOTONE("Duotone", ImageFilterKind.DUOTONE),
    BLUR("Blur", ImageFilterKind.BLUR),
    GRAIN("Grain", ImageFilterKind.GRAIN),
    PIXELATE("Pixel", ImageFilterKind.PIXELATE),
    POSTERIZE("Poster", ImageFilterKind.POSTERIZE),
    EMBOSS("Emboss", ImageFilterKind.EMBOSS);

    companion object {
        fun from(kind: ImageFilterKind): ImageCreativeFilter = entries.firstOrNull { it.kind == kind } ?: ORIGINAL
    }
}

/**
 * The markup pen palette: a curated seven, named.
 *
 * The colours were an anonymous inline `listOf(...)` inside the control row, which is how seven pens shipped
 * with no accessible name between them — a reader announced seven identical buttons and the pen row was
 * operable without sight only by trial and error. Hoisting it makes the name part of the data rather than
 * something the row has to invent.
 *
 * Named rather than derived. [ColorValue.accessibleLabel] is the right answer for a colour nobody chose — a
 * value pulled from a document, a recents list or a harmony calculation — and it is what the colour studio
 * announces. These seven were chosen, so they have names: "Ink" is what `#202124` is *for* here, and
 * `accessibleLabel` would call it "grey #202124", which is accurate and says nothing about it being the
 * default drawing colour.
 */
private val MarkupPens = listOf(
    MarkupPen("White", ColorValue(255, 255, 255)),
    MarkupPen("Ink", ColorValue(32, 33, 36)),
    MarkupPen("Red", ColorValue(244, 67, 54)),
    MarkupPen("Amber", ColorValue(255, 193, 7)),
    MarkupPen("Green", ColorValue(76, 175, 80)),
    MarkupPen("Blue", ColorValue(33, 150, 243)),
    MarkupPen("Purple", ColorValue(156, 39, 176)),
)

private data class MarkupPen(val name: String, val color: ColorValue)

/**
 * The pen swatch's diameter: the size this row was built at, kept so the markup rail's rhythm is unchanged.
 *
 * Smaller than the colour studio's 44 dp because seven pens share a rail with the brush widths and the tool
 * buttons. It is under the 48 dp Material asks of a target, which is why the pens go through
 * [NorfoldColorSwatch] — it reserves 48 dp of *layout* around whatever diameter it draws, so the row stays as
 * dense as it looks while the target stops being undersized.
 */
private const val PenSwatchSize = 32f

@Composable
private fun ImageStudioMarkupControls(
    document: ImageEditDocument,
    runtime: ImageFocusRuntime,
    disabled: Boolean,
    changed: () -> Unit,
    edit: ((ImageEditDocument) -> ImageEditDocument) -> Unit,
    selectedColor: ColorValue,
    strokeWidth: Float,
    onColorChange: (ColorValue) -> Unit,
    onStrokeWidthChange: (Float) -> Unit,
    tool: ImageMarkupTool,
    onToolChange: (ImageMarkupTool) -> Unit,
    selectedIndex: Int?,
    onSelectedIndexChange: (Int?) -> Unit,
    onOpenStickerPicker: () -> Unit,
    onOpenSignature: () -> Unit,
) {
    var longPressLabel by remember { mutableStateOf<String?>(null) }
    // The active pen is editor-session state: selecting colour or width must not add a history
    // entry. The chosen values are persisted on every resulting stroke, so each annotation retains
    // its own appearance after reopen.
    Row(
        // `selectableGroup`, so the pens announce as one exclusive choice. Before the collapse this row had no
        // semantics at all: seven unnamed circles, and no statement of which pen was active.
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MarkupPens.forEach { pen ->
            NorfoldColorSwatch(
                color = Color(pen.color.toArgb()),
                selected = pen.color == selectedColor,
                label = pen.name,
                onClick = { onColorChange(pen.color) },
                enabled = !disabled,
                size = PenSwatchSize,
            )
        }
    }
    ImageMarkupBrushRail(strokeWidth, disabled, onStrokeWidthChange)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ImageTransformAction(Icons.Outlined.Edit, "Select and move objects", disabled = disabled, onLongPress = { longPressLabel = it }, selected = tool == ImageMarkupTool.SELECT) { onToolChange(ImageMarkupTool.SELECT) }
        ImageTransformAction(Icons.Outlined.AutoFixHigh, "Draw on the image", disabled = disabled, onLongPress = { longPressLabel = it }, selected = tool == ImageMarkupTool.DRAW) { onToolChange(ImageMarkupTool.DRAW) }
        ImageTransformAction(Icons.Outlined.Crop, "Draw a line shape", disabled = disabled, onLongPress = { longPressLabel = it }, selected = tool == ImageMarkupTool.SHAPE) { onToolChange(ImageMarkupTool.SHAPE) }
        ImageTransformAction(Icons.Outlined.Close, "Remove an object", disabled = disabled, onLongPress = { longPressLabel = it }, selected = tool == ImageMarkupTool.ERASER) { onToolChange(ImageMarkupTool.ERASER) }
        ImageTransformAction(Icons.Outlined.ContentCopy, "Remove latest stroke", disabled = disabled, onLongPress = { longPressLabel = it }) {
            edit { current -> current.copy(markup = current.markup.copy(strokes = current.markup.strokes.dropLast(1))) }
        }
        ImageTransformAction(Icons.Outlined.Title, "Add text", disabled = disabled, onLongPress = { longPressLabel = it }) {
            edit { current ->
                current.copy(markup = current.markup.copy(labels = current.markup.labels + ImageMarkupLabel(
                    id = "text-${UUID.randomUUID()}", kind = ImageMarkupLabelKind.TEXT,
                )))
            }
            onSelectedIndexChange(document.markup.labels.size)
            onToolChange(ImageMarkupTool.SELECT)
        }
        ImageTransformAction(Icons.Outlined.Edit, "Add signature", disabled = disabled, onLongPress = { longPressLabel = it }) {
            onOpenSignature()
        }
        ImageTransformAction(Icons.Outlined.AutoFixHigh, "Add sticker", disabled = disabled, onLongPress = { longPressLabel = it }) {
            onOpenStickerPicker()
        }
        ImageTransformAction(Icons.Outlined.Close, "Clear annotations", disabled = disabled, onLongPress = { longPressLabel = it }) {
            edit { current -> current.copy(markup = com.norfold.app.editor.image.ImageMarkup()) }
        }
    }
    longPressLabel?.let { label ->
        Surface(
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.padding(start = 20.dp, top = 2.dp),
        ) { Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) }
    }
    val current = selectedIndex?.let(document.markup.labels::getOrNull)
    if (current != null && current.kind == ImageMarkupLabelKind.TEXT) {
        OutlinedTextField(
            value = current.text,
            onValueChange = { next -> edit { state -> state.copy(markup = state.markup.copy(labels = state.markup.labels.mapIndexed { index, label -> if (index == selectedIndex) label.copy(text = next) else label })) } },
            label = { Text("Selected text") },
            singleLine = true,
            enabled = !disabled,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        )
        ImageEditorValueRail(
            label = "Text size",
            value = current.size,
            range = 0.018f..0.18f,
            disabled = disabled,
            runtime = runtime,
            changed = changed,
            display = { "${(it * 100).toInt()}%" },
        ) { value, state ->
            state.copy(markup = state.markup.copy(labels = state.markup.labels.mapIndexed { index, label ->
                if (index == selectedIndex) label.copy(size = value) else label
            }))
        }
        ImageEditorValueRail(
            label = "Text rotation",
            value = current.rotationDegrees,
            range = -180f..180f,
            disabled = disabled,
            runtime = runtime,
            changed = changed,
            display = { "${it.toInt()}°" },
        ) { value, state ->
            state.copy(markup = state.markup.copy(labels = state.markup.labels.mapIndexed { index, label ->
                if (index == selectedIndex) label.copy(rotationDegrees = value) else label
            }))
        }
    }
}

@Composable
private fun ImageMarkupBrushRail(value: Float, disabled: Boolean, onValueChange: (Float) -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Brush size", style = MaterialTheme.typography.labelLarge)
                    Text("${(value * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        drawCircle(ink, radius = (value * size.minDimension * 2.8f).coerceIn(3.dp.toPx(), 20.dp.toPx()))
                    }
                }
            }
            Slider(value = value, onValueChange = onValueChange, valueRange = 0.004f..0.08f, enabled = !disabled)
        }
    }
}

/** A local, family-safe picker shared with chat. It deliberately has no GIF or network source. */
@Composable
private fun ImageStickerPicker(
    onDismiss: () -> Unit,
    onPick: (Sticker) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(StickerCategory.POPULAR) }
    val stickers = remember(category, query) { StickerCatalog.items(category, query) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 1f),
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Stickers", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Family-safe local stickers",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close sticker picker")
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Search stickers") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StickerCategory.entries.forEach { candidate ->
                        FilterChip(
                            selected = category == candidate,
                            onClick = { category = candidate },
                            label = { Text(candidate.label) },
                        )
                    }
                }
                if (stickers.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        Text("No matching stickers", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(72.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    ) {
                        items(stickers, key = { it.id }) { sticker ->
                            val stickerFocus = rememberChromeFocus()
                            Surface(
                                onClick = { onPick(sticker) },
                                shape = RoundedCornerShape(18.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                interactionSource = stickerFocus.source,
                                modifier = Modifier.size(72.dp)
                                    .then(stickerFocus.ringOnShape(18f, MaterialTheme.colorScheme.surfaceVariant)),
                            ) {
                                AsyncImage(
                                    model = sticker.assetUri,
                                    contentDescription = sticker.label,
                                    modifier = Modifier.padding(10.dp).fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A focused signing board: ink only commits when the user explicitly confirms the drawing. */
@Composable
private fun ImageSignatureSheet(
    onDismiss: () -> Unit,
    onConfirm: (List<ImageMarkupPoint>) -> Unit,
) {
    var points by remember { mutableStateOf(emptyList<ImageMarkupPoint>()) }
    var boardSize by remember { mutableStateOf(IntSize.Zero) }
    val ink = MaterialTheme.colorScheme.onSurface
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Add signature", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Draw with one finger. Your signature stays editable after it is placed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                        .onSizeChanged { boardSize = it }
                        .pointerInput(boardSize) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val width = boardSize.width.coerceAtLeast(1)
                                    val height = boardSize.height.coerceAtLeast(1)
                                    points = points + ImageMarkupPoint(offset.x / width, offset.y / height).normalized()
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val width = boardSize.width.coerceAtLeast(1)
                                    val height = boardSize.height.coerceAtLeast(1)
                                    points = points + ImageMarkupPoint(
                                        change.position.x / width,
                                        change.position.y / height,
                                    ).normalized()
                                },
                            )
                        },
                ) {
                    val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                    points.zipWithNext().forEach { (from, to) ->
                        drawLine(
                            color = ink,
                            start = Offset(from.x * size.width, from.y * size.height),
                            end = Offset(to.x * size.width, to.y * size.height),
                            strokeWidth = stroke.width,
                            cap = StrokeCap.Round,
                        )
                    }
                    if (points.size == 1) {
                        drawCircle(
                            color = ink,
                            radius = stroke.width / 2f,
                            center = Offset(points.single().x * size.width, points.single().y * size.height),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(
                        onClick = { points = emptyList() },
                        enabled = points.isNotEmpty(),
                        modifier = Modifier.size(52.dp).semantics { contentDescription = "Clear signature" },
                    ) { Icon(Icons.Outlined.Delete, null) }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(52.dp).semantics { contentDescription = "Cancel signature" },
                    ) { Icon(Icons.Outlined.Close, null) }
                    IconButton(
                        onClick = { onConfirm(points) },
                        enabled = points.size >= 2,
                        modifier = Modifier.size(52.dp).semantics { contentDescription = "Insert signature" },
                    ) { Icon(Icons.Outlined.Check, null) }
                }
            }
        }
    }
}

/** Visual thumbnail rails mirror the compact media-editor pattern while all edits remain non-destructive. */
@Composable
private fun ImageVisualChoice(
    label: String,
    selected: Boolean,
    swatch: List<Color>,
    enabled: Boolean,
    onClick: () -> Unit,
    preview: (@Composable BoxScope.() -> Unit)? = null,
) {
    val fill = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val focus = rememberChromeFocus()
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = fill,
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        interactionSource = focus.source,
        // The selected state carries a 2 dp border, which the substrate's default 3 dp inset does not clear
        // — the ring would land half a dp from it and the two strokes would merge into one heavier edge,
        // saying "selected" where it should say "focused".
        modifier = Modifier.size(width = 88.dp, height = 76.dp).then(
            focus.ringOnShape(16f, fill, boundaryWidthDp = if (selected) 2f else ChromeGeometry.RESTING_EDGE_WIDTH),
        ),
    ) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(9.dp))
                    .background(Brush.linearGradient(swatch)),
                contentAlignment = Alignment.Center,
            ) { preview?.invoke(this) }
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

private fun filterSwatch(index: Int): List<Color> = when (index % 5) {
    0 -> listOf(Color(0xFF42506A), Color(0xFFB8D8B8))
    1 -> listOf(Color(0xFF182035), Color(0xFFB06F4E))
    2 -> listOf(Color(0xFF7FC7E3), Color(0xFFEFCE8A))
    3 -> listOf(Color(0xFF563E7A), Color(0xFFD98E79))
    else -> listOf(Color(0xFF3E4F45), Color(0xFFCDD8B0))
}

@Composable
private fun ImageStudioFrameControls(
    document: ImageEditDocument,
    runtime: ImageFocusRuntime,
    disabled: Boolean,
    changed: () -> Unit,
    edit: ((ImageEditDocument) -> ImageEditDocument) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ImageFrameMode.entries.forEach { mode ->
            FilterChip(
                selected = document.frame.mode == mode,
                onClick = { edit { it.copy(frame = it.frame.copy(mode = mode)) } },
                enabled = !disabled,
                label = { Text(if (mode == ImageFrameMode.FILL) "Fill frame" else "Fit image") },
            )
        }
    }
    ImageCompactSlider("Zoom", document.frame.scale, 1f..4f, disabled, runtime, changed) { value, current ->
        current.copy(frame = current.frame.copy(scale = value))
    }
}

@Composable
private fun ImageStudioDetailsControls(
    document: ImageEditDocument,
    runtime: ImageFocusRuntime,
    disabled: Boolean,
    changed: () -> Unit,
    onReplace: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onReplace, enabled = !disabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.Image, null); Spacer(Modifier.width(6.dp)); Text("Replace")
        }
        OutlinedButton(onClick = { if (runtime.resetAllEdits()) changed() }, enabled = !disabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
            Text("Reset edits")
        }
    }
    OutlinedTextField(
        value = runtime.caption(),
        onValueChange = { if (runtime.editCaption(it)) changed() },
        enabled = !disabled,
        label = { Text("Caption") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
    // Read this field so Details always exposes a visible, intentional setting for image semantics.
    Text(
        if (document.accessibility.decorative) "This image is decorative." else "Alternative text is managed in More tools.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

@Composable
private fun ImageStudioMoreControls(document: ImageEditDocument, runtime: ImageFocusRuntime, disabled: Boolean, changed: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { if (runtime.resetAllEdits()) changed() }, enabled = !disabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Reset all") }
        OutlinedButton(onClick = { ImageFocusSharedState.clipboard.copy(document) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Copy edits") }
    }
    Text(
        "Crop, transform, adjustment, filters, caption, and accessibility settings are non-destructive until you save.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

private fun ImageEditDocument.adjustmentValue(key: ImageAdjustmentKey): Float = when (key) {
    ImageAdjustmentKey.EXPOSURE -> adjustments.exposure
    ImageAdjustmentKey.BRIGHTNESS -> adjustments.brightness
    ImageAdjustmentKey.CONTRAST -> adjustments.contrast
    ImageAdjustmentKey.HIGHLIGHTS -> adjustments.highlights
    ImageAdjustmentKey.SHADOWS -> adjustments.shadows
    ImageAdjustmentKey.TEMPERATURE -> adjustments.temperature
    ImageAdjustmentKey.TINT -> adjustments.tint
    ImageAdjustmentKey.SATURATION -> adjustments.saturation
    ImageAdjustmentKey.SHARPNESS -> adjustments.sharpness
    ImageAdjustmentKey.VIGNETTE -> adjustments.vignette
}

private fun ImageAdjustments.withValue(key: ImageAdjustmentKey, value: Float): ImageAdjustments = when (key) {
    ImageAdjustmentKey.EXPOSURE -> copy(exposure = value)
    ImageAdjustmentKey.BRIGHTNESS -> copy(brightness = value)
    ImageAdjustmentKey.CONTRAST -> copy(contrast = value)
    ImageAdjustmentKey.HIGHLIGHTS -> copy(highlights = value)
    ImageAdjustmentKey.SHADOWS -> copy(shadows = value)
    ImageAdjustmentKey.TEMPERATURE -> copy(temperature = value)
    ImageAdjustmentKey.TINT -> copy(tint = value)
    ImageAdjustmentKey.SATURATION -> copy(saturation = value)
    ImageAdjustmentKey.SHARPNESS -> copy(sharpness = value)
    ImageAdjustmentKey.VIGNETTE -> copy(vignette = value.coerceAtLeast(0f))
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
