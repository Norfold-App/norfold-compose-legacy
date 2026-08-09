package com.norfold.app.editor.image

import com.norfold.app.domain.ImageBlock
import com.norfold.app.editor.history.FocusHistoryOwner
import kotlinx.serialization.Serializable

fun interface ImageFocusCommitHost {
    /** The host must record this as one canonical undo transaction. */
    fun commit(label: String, block: ImageBlock): Boolean
}

data class ImageFocusSnapshot(val source: String, val document: ImageEditDocument)

data class ImageFocusRuntimeStats(
    val undoDepth: Int,
    val redoDepth: Int,
    val committedHostTransactions: Long,
    val cancelledGestures: Long,
    val previewChanges: Long,
)

/** Focus-local history and previews. Nothing reaches host history until [done]. */
class ImageFocusRuntime(
    private val baseline: ImageBlock,
    private val host: ImageFocusCommitHost,
    readOnly: Boolean = false,
    private val historyLimit: Int = DEFAULT_HISTORY_LIMIT,
) {
    private val loaded = ImageBlockAdapter.load(baseline)
    private val locked = readOnly || !loaded.isEditable
    private val undo = ArrayDeque<ImageFocusSnapshot>()
    private val redo = ArrayDeque<ImageFocusSnapshot>()
    private val initial = ImageFocusSnapshot(baseline.source, loaded.document)
    private var working = initial
    private var gestureStart: ImageFocusSnapshot? = null
    private var comparingOriginal = false
    private var closed = false
    private var commits = 0L
    private var cancelledGestures = 0L
    private var previewChanges = 0L

    fun snapshot(): ImageFocusSnapshot = working
    fun document(): ImageEditDocument = if (comparingOriginal) ImageEditDocument() else working.document
    fun source(): String = if (comparingOriginal) baseline.source else working.source
    fun isReadOnly(): Boolean = locked || closed
    fun canUndo(): Boolean = !isReadOnly() && !comparingOriginal && gestureStart == null && undo.isNotEmpty()
    fun canRedo(): Boolean = !isReadOnly() && !comparingOriginal && gestureStart == null && redo.isNotEmpty()
    fun isComparingOriginal(): Boolean = comparingOriginal
    fun hasGesture(): Boolean = gestureStart != null

    fun stats() = ImageFocusRuntimeStats(undo.size, redo.size, commits, cancelledGestures, previewChanges)

    /** True until [done] or [cancel] closes the session. */
    fun isOpen(): Boolean = !closed

    /**
     * This session as the owner of the undo chord while it is open (spec §13.5).
     *
     * The predicates are the session's own, not a host's copy of them: a host that re-derived "can undo
     * here" would eventually disagree with [canUndo] about a gesture in progress or a compare-original
     * preview, and the chord would either run a step the tool refuses or refuse one it would have run.
     *
     * A read-only session is not an owner. It can never accumulate a local step, so claiming undo would
     * create a dead zone where the chord silently does nothing for as long as the image is open.
     */
    fun historyOwner(focusId: String): FocusHistoryOwner? {
        if (locked) return null
        return FocusHistoryOwner.of(
            focusId = focusId,
            canUndo = ::canUndo,
            canRedo = ::canRedo,
            undo = ::undo,
            redo = ::redo,
            isActive = ::isOpen,
        )
    }

    fun edit(transform: (ImageEditDocument) -> ImageEditDocument): Boolean {
        if (isReadOnly() || comparingOriginal || gestureStart != null) return false
        return replaceWorking(working.copy(document = transform(working.document).normalized()))
    }

    fun replaceSource(source: String, metadata: ImageSourceMetadata = ImageSourceMetadata()): Boolean {
        if (isReadOnly() || comparingOriginal || gestureStart != null || source.isBlank()) return false
        val cleanMetadata = metadata.normalized()
        return replaceWorking(working.copy(
            source = source,
            document = working.document.copy(
                crop = ImageCropGeometry.applyMode(working.document.crop, cleanMetadata, working.document.crop.mode),
                sourceMetadata = cleanMetadata,
            ).normalized(),
        ))
    }

    fun beginGesture(): Boolean {
        if (isReadOnly() || comparingOriginal || gestureStart != null) return false
        gestureStart = working
        return true
    }

    fun previewGesture(transform: (ImageEditDocument) -> ImageEditDocument): Boolean {
        if (isReadOnly() || gestureStart == null) return false
        val next = working.copy(document = transform(working.document).normalized())
        if (next == working) return false
        working = next
        previewChanges++
        return true
    }

    fun endGesture(): Boolean {
        val start = gestureStart ?: return false
        gestureStart = null
        if (start == working) return false
        pushBounded(undo, start)
        redo.clear()
        return true
    }

    fun cancelGesture(): Boolean {
        val start = gestureStart ?: return false
        working = start
        gestureStart = null
        cancelledGestures++
        return true
    }

    fun undo(): Boolean {
        if (!canUndo()) return false
        pushBounded(redo, working)
        working = undo.removeLast()
        return true
    }

    fun redo(): Boolean {
        if (!canRedo()) return false
        pushBounded(undo, working)
        working = redo.removeLast()
        return true
    }

    fun setCompareOriginal(active: Boolean): Boolean {
        if (closed || gestureStart != null) return false
        if (comparingOriginal == active) return false
        comparingOriginal = active
        return true
    }

    fun resetAdjustments(): Boolean = edit { it.copy(adjustments = ImageAdjustments()) }
    fun resetCrop(): Boolean = edit { it.copy(crop = ImageCrop()) }
    fun resetFrame(): Boolean = edit { it.copy(frame = ImageFrame()) }
    fun resetMask(): Boolean = edit { it.copy(mask = ImageMask()) }
    fun resetEffects(): Boolean = edit { it.copy(effects = ImageEffects(), border = ImageBorder(), shadow = ImageShadow()) }

    /** Reset keeps source metadata and accessibility, which are content rather than visual effects. */
    fun resetAllEdits(): Boolean = edit {
        ImageEditDocument(accessibility = it.accessibility, sourceMetadata = it.sourceMetadata)
    }

    fun applyParameters(parameters: ImageEditParameters): Boolean = edit { it.withParameters(parameters) }

    /** Exactly one host transaction, even when the focus session contains many local steps. */
    fun done(): Boolean {
        if (isReadOnly() || gestureStart != null) return false
        if (working == initial) {
            closed = true
            return true
        }
        val next = ImageBlockAdapter.save(baseline.copy(source = working.source), working.document)
        if (next == baseline) {
            closed = true
            return true
        }
        if (!host.commit("Edit image", next)) return false
        commits++
        closed = true
        return true
    }

    /** Cancelling never calls the host and exposes the exact original block for verification. */
    fun cancel(): ImageBlock {
        comparingOriginal = false
        gestureStart = null
        closed = true
        working = initial
        undo.clear()
        redo.clear()
        return baseline
    }

    private fun replaceWorking(next: ImageFocusSnapshot): Boolean {
        if (next == working) return false
        pushBounded(undo, working)
        working = next
        redo.clear()
        return true
    }

    private fun pushBounded(stack: ArrayDeque<ImageFocusSnapshot>, value: ImageFocusSnapshot) {
        if (historyLimit <= 0) return
        while (stack.size >= historyLimit) stack.removeFirst()
        stack.addLast(value)
    }

    companion object { const val DEFAULT_HISTORY_LIMIT = 100 }
}

@Serializable
data class ImagePreset(val id: String, val name: String, val parameters: ImageEditParameters)

/** Process-local store for user presets. Persistence can be added later without changing preset semantics. */
class ImagePresetStore(private val limit: Int = 64) {
    private val presets = LinkedHashMap<String, ImagePreset>()
    fun all(): List<ImagePreset> = presets.values.toList()
    fun put(preset: ImagePreset): Boolean {
        val id = preset.id.trim().take(128)
        val name = preset.name.trim().take(128)
        if (limit <= 0 || id.isEmpty() || name.isEmpty()) return false
        if (id !in presets && presets.size >= limit) presets.remove(presets.keys.first())
        presets[id] = ImagePreset(id, name, preset.parameters.normalized())
        return true
    }
    fun rename(id: String, name: String): Boolean {
        val preset = presets[id] ?: return false
        val clean = name.trim().take(128)
        if (clean.isEmpty()) return false
        presets[id] = preset.copy(name = clean)
        return true
    }
    fun remove(id: String): Boolean = presets.remove(id) != null
    fun resetToDefaults() { presets.clear() }
    fun encode(): String = ImagePresetCodec.encode(all())
    fun restore(raw: String): Boolean {
        val decoded = ImagePresetCodec.decode(raw) ?: return false
        presets.clear()
        decoded.takeLast(limit.coerceAtLeast(0)).forEach(::put)
        return true
    }
}

class ImageParameterClipboard {
    private var value: ImageEditParameters? = null
    fun copy(document: ImageEditDocument) { value = document.parameters() }
    fun peek(): ImageEditParameters? = value
    fun clear() { value = null }
}
