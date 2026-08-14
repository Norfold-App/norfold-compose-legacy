package com.norfold.app.editor.image

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BlockDocumentJson
import com.norfold.app.domain.ImageBlock
import com.norfold.app.domain.ImageLayout
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.PageGeometry
import com.norfold.app.domain.SceneGeometry
import com.norfold.app.domain.SceneObject
import com.norfold.app.domain.SceneObjectKind
import com.norfold.app.domain.findById
import com.norfold.app.domain.pages
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.editor.document.DocumentHostRuntime
import com.norfold.app.editor.document.DocumentSceneController
import com.norfold.app.editor.flags.EditorFeatureFlag
import com.norfold.app.editor.flags.EditorFeatureFlags
import com.norfold.app.editor.flow.FlowEditController
import com.norfold.app.editor.flow.FlowHostRuntime
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.presentation.FormFactor
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageFocusPhase6Test {
    @Test
    fun markupPersistsAsNormalizedSourceCoordinatesAndParticipatesInRendering() {
        val hostile = ImageMarkup(
            strokes = listOf(ImageMarkupStroke(
                points = listOf(ImageMarkupPoint(Float.NaN, 4f)),
                width = Float.POSITIVE_INFINITY,
            )),
            labels = listOf(ImageMarkupLabel(text = "x".repeat(300), x = -1f, y = 4f, size = Float.NaN)),
        ).normalized()
        assertEquals(0.5f, hostile.strokes.single().points.single().x)
        assertEquals(1f, hostile.strokes.single().points.single().y)
        assertEquals(0.014f, hostile.strokes.single().width)
        assertEquals(ImageMarkupLabel.MAX_TEXT, hostile.labels.single().text.length)

        val runtime = ImageFocusRuntime(ImageBlock(source = "source"), ImageFocusCommitHost { _, _ -> true })
        assertTrue(runtime.edit { it.copy(markup = hostile) })
        val saved = ImageBlockAdapter.save(ImageBlock(source = "source"), runtime.document())
        assertEquals(hostile, ImageBlockAdapter.load(saved).document.markup)
        assertNotEquals(
            ImagePreviewPolicy.cacheKey("source", ImageEditDocument(), ImageDecodeTarget(200, 200, false)),
            ImagePreviewPolicy.cacheKey("source", runtime.document(), ImageDecodeTarget(200, 200, false)),
        )
    }

    @Test
    fun canonicalCodecRoundTripsDeterministicallyAndIgnoresUnknownFields() {
        val document = richDocument()
        val encoded = ImageEditDocumentCodec.encode(document)
        val decoded = assertIs<ImageEditDecodeResult.Success>(ImageEditDocumentCodec.decode(encoded)).document
        assertEquals(document.normalized(), decoded)
        assertEquals(encoded, ImageEditDocumentCodec.encode(decoded))
        assertIs<ImageEditDecodeResult.Success>(
            ImageEditDocumentCodec.decode(encoded.replaceFirst("{", "{\"future\":true,")),
        )
        assertFalse(encoded.contains("content://"))
    }

    @Test
    fun unsupportedAndMalformedPayloadsArePreservedAndReadOnly() {
        val future = """{"schemaVersion":99,"document":{"schemaVersion":99}}"""
        val futureBlock = ImageBlock(source = "content://original", imageEditDocument = future)
        val loaded = ImageBlockAdapter.load(futureBlock)
        assertEquals(LoadedImageEditDocument.Origin.UNSUPPORTED_CANONICAL, loaded.origin)
        assertEquals(future, loaded.preservedPayload)
        assertFalse(loaded.isEditable)
        val runtime = ImageFocusRuntime(futureBlock, ImageFocusCommitHost { _, _ -> true })
        assertTrue(runtime.isReadOnly())
        assertFalse(runtime.edit { it.copy(mask = ImageMask(ImageMaskKind.ELLIPSE)) })
        assertFalse(runtime.done())

        val malformed = ImageBlock(imageEditDocument = "not-json")
        assertEquals(LoadedImageEditDocument.Origin.MALFORMED_CANONICAL, ImageBlockAdapter.load(malformed).origin)
    }

    @Test
    fun legacyBlockLoadsWithoutMutationAndSavesAdditively() {
        val legacy = ImageBlock(
            id = "image-1", source = "content://photo", caption = "Visible caption",
            layout = ImageLayout.Wide, displayHeightDp = 317f,
        )
        val loaded = ImageBlockAdapter.load(legacy)
        assertEquals(LoadedImageEditDocument.Origin.LEGACY_EMPTY, loaded.origin)
        assertNull(legacy.imageEditDocument)
        val saved = ImageBlockAdapter.save(legacy, loaded.document.copy(mask = ImageMask(ImageMaskKind.ELLIPSE)))
        assertEquals(legacy.id, saved.id)
        assertEquals(legacy.source, saved.source)
        assertEquals(legacy.caption, saved.caption)
        assertEquals(legacy.layout, saved.layout)
        assertEquals(legacy.displayHeightDp, saved.displayHeightDp)
        assertEquals(ImageMaskKind.ELLIPSE, ImageBlockAdapter.load(saved).document.mask.kind)
    }

    @Test
    fun imageBlockJsonKeepsNewPayloadAndReadsOldJson() {
        val edited = ImageBlockAdapter.save(ImageBlock(id = "i", source = "s"), richDocument())
        val roundTrip = BlockDocumentJson.decode(BlockDocumentJson.encode(BlockDocument(listOf(edited))))
        assertEquals(edited, roundTrip.blocks.single())

        val oldJson = BlockDocumentJson.encode(BlockDocument(listOf(ImageBlock(id = "old", source = "legacy"))))
        assertNull((BlockDocumentJson.decode(oldJson).blocks.single() as ImageBlock).imageEditDocument)
    }

    @Test
    fun normalizationIsTotalBoundedAndIdempotentForHostileNumbers() {
        val hostile = ImageEditDocument(
            crop = ImageCrop(
                bounds = NormalizedRect(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -5f),
                straightenDegrees = Float.POSITIVE_INFINITY,
                quarterTurns = -9,
                perspectiveHorizontal = 99f,
                perspectiveVertical = -99f,
            ),
            frame = ImageFrame(focalX = -5f, focalY = 5f, scale = Float.NaN, offsetX = -5f, offsetY = 5f),
            adjustments = ImageAdjustments(exposure = 99f, contrast = -99f, blur = Float.NaN),
            effects = ImageEffects(opacity = Float.NaN, monochrome = 4f, sepia = -2f, vignette = 9f),
            border = ImageBorder(widthDp = Float.POSITIVE_INFINITY),
            shadow = ImageShadow(blurDp = -3f, offsetXDp = 500f, offsetYDp = -500f),
            accessibility = ImageAccessibility("x".repeat(4_000)),
            sourceMetadata = ImageSourceMetadata(Int.MAX_VALUE, -1, "A".repeat(500)),
        ).normalized()
        assertEquals(hostile, hostile.normalized())
        assertTrue(hostile.crop.bounds.left in 0f..0.99f)
        assertTrue(hostile.crop.bounds.width >= NormalizedRect.MIN_EXTENT)
        assertEquals(3, hostile.crop.quarterTurns)
        assertEquals(1f, hostile.crop.perspectiveHorizontal)
        assertEquals(-1f, hostile.adjustments.contrast)
        assertEquals(1f, hostile.effects.opacity)
        assertEquals(ImageAccessibility.MAX_ALT_TEXT, hostile.accessibility.alternativeText.length)
        assertNull(hostile.sourceMetadata.pixelWidth)
        assertNull(hostile.sourceMetadata.pixelHeight)
    }

    @Test
    fun localEditsUndoRedoAndBranchWithoutTouchingHost() {
        val hostCalls = AtomicInteger()
        val runtime = ImageFocusRuntime(ImageBlock(source = "a"), ImageFocusCommitHost { _, _ ->
            hostCalls.incrementAndGet(); true
        })
        assertTrue(runtime.edit { it.copy(mask = ImageMask(ImageMaskKind.ELLIPSE)) })
        assertTrue(runtime.edit { it.copy(effects = it.effects.copy(sepia = 0.4f)) })
        assertEquals(0, hostCalls.get())
        assertTrue(runtime.undo())
        assertEquals(0f, runtime.document().effects.sepia)
        assertTrue(runtime.redo())
        assertEquals(0.4f, runtime.document().effects.sepia)
        assertTrue(runtime.undo())
        assertTrue(runtime.edit { it.copy(effects = it.effects.copy(monochrome = 0.6f)) })
        assertFalse(runtime.canRedo())
        assertEquals(2, runtime.stats().undoDepth)
    }

    @Test
    fun gesturePreviewsCollapseToOneLocalUndoStepAndCanCancelExactly() {
        val runtime = ImageFocusRuntime(ImageBlock(source = "a"), ImageFocusCommitHost { _, _ -> true })
        assertTrue(runtime.beginGesture())
        (1..30).forEach { value ->
            assertTrue(runtime.previewGesture { it.copy(adjustments = it.adjustments.copy(exposure = value / 30f)) })
        }
        assertEquals(0, runtime.stats().undoDepth)
        assertEquals(30, runtime.stats().previewChanges)
        assertTrue(runtime.endGesture())
        assertEquals(1, runtime.stats().undoDepth)
        assertTrue(runtime.undo())
        assertEquals(0f, runtime.document().adjustments.exposure)

        assertTrue(runtime.beginGesture())
        assertTrue(runtime.previewGesture { it.copy(frame = it.frame.copy(scale = 3f)) })
        assertTrue(runtime.cancelGesture())
        assertEquals(1f, runtime.document().frame.scale)
        assertEquals(1, runtime.stats().cancelledGestures)
    }

    @Test
    fun doneIsExactlyOneHostTransactionAndCancelIsExact() {
        val original = ImageBlock(id = "id", source = "source", caption = "caption", displayHeightDp = 456f)
        val committed = mutableListOf<ImageBlock>()
        val runtime = ImageFocusRuntime(original, ImageFocusCommitHost { label, block ->
            assertEquals("Edit image", label); committed += block; true
        })
        runtime.edit { it.copy(mask = ImageMask(ImageMaskKind.ROUNDED_RECTANGLE)) }
        runtime.replaceSource("replacement", ImageSourceMetadata(800, 600, "image/png"))
        assertTrue(runtime.done())
        assertEquals(1, committed.size)
        assertFalse(runtime.done())
        with(committed.single()) {
            assertEquals(original.id, id)
            assertEquals(original.caption, caption)
            assertEquals(original.displayHeightDp, displayHeightDp)
            assertEquals("replacement", source)
        }

        val neverCommit = AtomicInteger()
        val cancelled = ImageFocusRuntime(original, ImageFocusCommitHost { _, _ -> neverCommit.incrementAndGet(); true })
        cancelled.edit { it.copy(mask = ImageMask(ImageMaskKind.ELLIPSE)) }
        assertEquals(original, cancelled.cancel())
        assertEquals(0, neverCommit.get())
        assertTrue(cancelled.isReadOnly())

        val noOpCalls = AtomicInteger()
        val noOp = ImageFocusRuntime(original, ImageFocusCommitHost { _, _ -> noOpCalls.incrementAndGet(); true })
        assertTrue(noOp.done())
        assertEquals(0, noOpCalls.get())
        assertNull(original.imageEditDocument)
    }

    @Test
    fun compareOriginalChangesNoHistoryAndIncludesOriginalSource() {
        val runtime = ImageFocusRuntime(ImageBlock(source = "old"), ImageFocusCommitHost { _, _ -> true })
        runtime.replaceSource("new")
        runtime.edit { it.copy(mask = ImageMask(ImageMaskKind.ELLIPSE)) }
        val depth = runtime.stats().undoDepth
        assertTrue(runtime.setCompareOriginal(true))
        assertEquals("old", runtime.source())
        assertEquals(ImageEditDocument(), runtime.document())
        assertEquals(depth, runtime.stats().undoDepth)
        assertFalse(runtime.edit { it.copy(mask = ImageMask(ImageMaskKind.RECTANGLE)) })
        assertEquals(depth, runtime.stats().undoDepth)
        assertTrue(runtime.setCompareOriginal(false))
        assertEquals("new", runtime.source())
        assertEquals(ImageMaskKind.ELLIPSE, runtime.document().mask.kind)
    }

    @Test
    fun replacingSourcePreservesContentAndRenormalizesOriginalAspectCrop() {
        val initial = richDocument().copy(crop = richDocument().crop.copy(mode = ImageCropMode.ORIGINAL))
        val block = ImageBlockAdapter.save(ImageBlock(id = "id", source = "old", caption = "Visible"), initial)
        val runtime = ImageFocusRuntime(block, ImageFocusCommitHost { _, _ -> true })
        assertTrue(runtime.replaceSource("new", ImageSourceMetadata(1_000, 2_000, "image/png")))
        assertEquals("new", runtime.source())
        assertEquals("A mountain at sunrise", runtime.document().accessibility.alternativeText)
        assertEquals(2f, runtime.document().crop.bounds.width / runtime.document().crop.bounds.height, 0.0001f)
        assertTrue(runtime.undo())
        assertEquals("old", runtime.source())
        assertEquals(initial.normalized(), runtime.document())
    }

    @Test
    fun resetsAreLocalUndoableAndPreserveAccessibilityAndMetadata() {
        val runtime = ImageFocusRuntime(
            ImageBlockAdapter.save(ImageBlock(source = "a"), richDocument()),
            ImageFocusCommitHost { _, _ -> true },
        )
        assertTrue(runtime.resetAdjustments())
        assertEquals(ImageAdjustments(), runtime.document().adjustments)
        assertTrue(runtime.undo())
        assertEquals(richDocument().adjustments, runtime.document().adjustments)
        assertTrue(runtime.resetAllEdits())
        assertEquals(ImageCrop(), runtime.document().crop)
        assertEquals("A mountain at sunrise", runtime.document().accessibility.alternativeText)
        assertEquals(4_000, runtime.document().sourceMetadata.pixelWidth)
    }

    @Test
    fun presetAndClipboardNeverCarrySourceAccessibilityCaptionOrPlacement() {
        val original = ImageBlock(source = "secret-source", caption = "caption", displayHeightDp = 333f)
        val runtime = ImageFocusRuntime(original, ImageFocusCommitHost { _, _ -> true })
        runtime.edit { richDocument() }
        val clipboard = ImageParameterClipboard()
        clipboard.copy(runtime.document())
        val parameters = clipboard.peek()!!
        val target = ImageFocusRuntime(ImageBlock(source = "target", caption = "target caption"), ImageFocusCommitHost { _, _ -> true })
        assertTrue(target.applyParameters(parameters))
        assertEquals("target", target.source())
        assertEquals("", target.document().accessibility.alternativeText)

        val store = ImagePresetStore(limit = 2)
        assertTrue(store.put(ImagePreset("one", "First", parameters)))
        assertTrue(store.put(ImagePreset("two", "Second", ImageEditParameters())))
        assertTrue(store.put(ImagePreset("three", "Third", parameters)))
        assertEquals(listOf("two", "three"), store.all().map { it.id })
        assertFalse(store.put(ImagePreset("", "Invalid", parameters)))
    }

    @Test
    fun decodeTargetsCacheAndPipelineAreBoundedAndDeterministic() {
        val target = ImagePreviewPolicy.target(100_000, 100_000, 20_000, 20_000)
        assertTrue(target.widthPx <= ImagePreviewPolicy.MAX_EDGE_PX)
        assertTrue(target.widthPx.toLong() * target.heightPx <= ImagePreviewPolicy.MAX_PIXELS)
        assertEquals(target, ImagePreviewPolicy.target(100_000, 100_000, 20_000, 20_000))
        assertEquals(
            ImagePreviewPolicy.cacheKey("source", richDocument(), target),
            ImagePreviewPolicy.cacheKey("source", richDocument(), target),
        )
        assertEquals(listOf(ImagePipelineStage.SOURCE), ImageRenderPipeline.stages(compareOriginal = true))
        assertEquals(ImagePipelineStage.SOURCE, ImageRenderPipeline.semanticOrder.first())
        assertEquals(ImagePipelineStage.SHADOW, ImageRenderPipeline.semanticOrder.last())
        assertEquals(
            listOf(
                ImagePipelineStage.SOURCE, ImagePipelineStage.CROP, ImagePipelineStage.GEOMETRY,
                ImagePipelineStage.FRAME, ImagePipelineStage.MASK, ImagePipelineStage.ADJUSTMENTS,
                ImagePipelineStage.EFFECTS, ImagePipelineStage.MARKUP, ImagePipelineStage.BORDER, ImagePipelineStage.SHADOW,
            ),
            ImageRenderPipeline.semanticOrder,
        )

        val cache = ImagePreviewCache<String>(2)
        assertNull(cache.get("missing"))
        cache.put("a", "A"); cache.put("b", "B")
        assertEquals("A", cache.get("a"))
        cache.put("c", "C")
        assertNull(cache.get("b"))
        assertEquals(2, cache.stats().size)
        assertEquals(1, cache.stats().evictions)
    }

    @Test
    fun parameterFamiliesRemainIndependentAndSemanticOrderIsStable() {
        val document = richDocument()
        assertNotEquals(document.crop, ImageCrop())
        assertNotEquals(document.frame, ImageFrame())
        assertNotEquals(document.mask, ImageMask())
        assertNotEquals(document.adjustments, ImageAdjustments())
        assertNotEquals(document.effects, ImageEffects())
        assertNotEquals(document.border, ImageBorder())
        assertNotEquals(document.shadow, ImageShadow())
        assertEquals(10, ImageRenderPipeline.semanticOrder.distinct().size)
    }

    @Test
    fun cropGeometrySupportsFreeEdgesCornersPanAndLockedRatios() {
        val original = NormalizedRect(0.2f, 0.2f, 0.6f, 0.5f)
        assertEquals(0.3f, ImageCropGeometry.drag(original, ImageCropHandle.LEFT, 0.1f, 0f).left, 0.0001f)
        val corner = ImageCropGeometry.drag(original, ImageCropHandle.BOTTOM_RIGHT, 0.1f, 0.2f)
        assertEquals(0.7f, corner.width, 0.0001f)
        assertEquals(0.7f, corner.height, 0.0001f)
        val moved = ImageCropGeometry.drag(original, ImageCropHandle.MOVE, 0.4f, -0.5f)
        assertEquals(0.4f, moved.left, 0.0001f)
        assertEquals(0f, moved.top, 0.0001f)

        val square = ImageCropGeometry.applyMode(ImageCrop(bounds = original), ImageSourceMetadata(4_000, 3_000), ImageCropMode.RATIO_1_1)
        assertEquals(1f, square.bounds.width / square.bounds.height, 0.0001f)
        val sourceRatio = ImageCropGeometry.applyMode(ImageCrop(bounds = original), ImageSourceMetadata(4_000, 3_000), ImageCropMode.ORIGINAL)
        assertEquals(4f / 3f, sourceRatio.bounds.width / sourceRatio.bounds.height, 0.0001f)
        assertEquals(3f / 4f, ImageCropGeometry.aspectRatio(ImageCropMode.ORIGINAL, ImageSourceMetadata(4_000, 3_000), 1)!!, 0.0001f)
        val locked = ImageCropGeometry.drag(square.bounds, ImageCropHandle.RIGHT, 0.1f, 0f, 1f)
        assertEquals(1f, locked.width / locked.height, 0.0001f)
        val landscape = ImageCropGeometry.applyMode(ImageCrop(bounds = original), ImageSourceMetadata(), ImageCropMode.RATIO_4_3)
        val rotated = landscape.copy(quarterTurns = 1)
        val rotatedLocked = ImageCropGeometry.applyMode(rotated, ImageSourceMetadata(), rotated.mode)
        assertEquals(3f / 4f, rotatedLocked.bounds.width / rotatedLocked.bounds.height, 0.0001f)
    }

    @Test
    fun rotationScaleKeepsAnySourceAspectInsideTheCropViewport() {
        assertEquals(1f, ImageCropGeometry.rotationScaleToFitViewport(0f, 16f / 9f), 0.0001f)
        assertEquals(9f / 16f, ImageCropGeometry.rotationScaleToFitViewport(90f, 16f / 9f), 0.0001f)
        assertEquals(9f / 16f, ImageCropGeometry.rotationScaleToFitViewport(-90f, 9f / 16f), 0.0001f)
        assertTrue(ImageCropGeometry.rotationScaleToFitViewport(45f, 4f / 3f) in 0.05f..1f)
    }

    @Test
    fun cropZoomResizesTheFrameAroundItsCentreWithoutChangingSourceCoordinates() {
        val original = NormalizedRect(0.1f, 0.2f, 0.8f, 0.6f)
        val zoomed = ImageCropGeometry.zoom(original, 2f)

        assertEquals(0.5f, zoomed.left + zoomed.width / 2f, 0.0001f)
        assertEquals(0.5f, zoomed.top + zoomed.height / 2f, 0.0001f)
        assertEquals(0.5f, zoomed.width, 0.0001f)
        assertEquals(0.375f, zoomed.height, 0.0001f)
    }

    @Test
    fun freeDistortionKeepsFourBoundedCornersAndIsFullyUndoable() {
        val hostile = ImageFreeTransform(
            topLeft = ImageFreeTransformCorner(Float.NaN, -4f),
            topRight = ImageFreeTransformCorner(4f, Float.POSITIVE_INFINITY),
            bottomRight = ImageFreeTransformCorner(2f, 2f),
            bottomLeft = ImageFreeTransformCorner(-2f, -2f),
        ).normalized()
        listOf(hostile.topLeft, hostile.topRight, hostile.bottomRight, hostile.bottomLeft).forEach { corner ->
            assertTrue(corner.x in -0.5f..1.5f)
            assertTrue(corner.y in -0.5f..1.5f)
        }
        val runtime = ImageFocusRuntime(ImageBlock(source = "source"), ImageFocusCommitHost { _, _ -> true })
        assertTrue(runtime.edit { state -> state.copy(crop = state.crop.copy(freeTransform = hostile)) })
        assertEquals(hostile, runtime.document().crop.freeTransform)
        assertTrue(runtime.undo())
        assertTrue(runtime.document().crop.freeTransform.isIdentity())
        assertTrue(runtime.redo())
        assertEquals(hostile, runtime.document().crop.freeTransform)
    }

    @Test
    fun everyAdjustmentAndEffectNormalizesAndResetsWithoutTouchingGeometry() {
        val original = richDocument()
        val runtime = ImageFocusRuntime(ImageBlockAdapter.save(ImageBlock(source = "s"), original), ImageFocusCommitHost { _, _ -> true })
        val normalized = ImageAdjustments(2f, -2f, 3f, -3f, 4f, -4f, 5f, 6f, 7f).normalized()
        assertEquals(1f, normalized.exposure)
        assertEquals(-1f, normalized.contrast)
        assertEquals(1f, normalized.highlights)
        assertEquals(-1f, normalized.shadows)
        assertEquals(1f, normalized.temperature)
        assertEquals(-1f, normalized.tint)
        assertEquals(1f, normalized.saturation)
        assertEquals(1f, normalized.sharpness)
        assertEquals(1f, normalized.blur)
        assertTrue(runtime.resetEffects())
        assertEquals(ImageEffects(), runtime.document().effects)
        assertEquals(ImageBorder(), runtime.document().border)
        assertEquals(ImageShadow(), runtime.document().shadow)
        assertEquals(original.crop, runtime.document().crop)
        assertEquals(original.frame, runtime.document().frame)
        assertEquals(original.mask, runtime.document().mask)
        assertTrue(runtime.undo())
        assertEquals(original.normalized(), runtime.document())
    }

    @Test
    fun fourModeLayersStayIndependentAndEveryNewObjectIsUndoable() {
        val runtime = ImageFocusRuntime(ImageBlock(source = "s"), ImageFocusCommitHost { _, _ -> true })
        assertTrue(runtime.edit { state ->
            state.copy(adjustments = state.adjustments.copy(brightness = .3f, vignette = .4f))
        })
        assertTrue(runtime.edit { state ->
            state.copy(effects = state.effects.copy(filter = ImageFilterKind.DUOTONE, filterIntensity = .65f))
        })
        assertEquals(.3f, runtime.document().adjustments.brightness, .0001f)
        assertEquals(.4f, runtime.document().adjustments.vignette, .0001f)
        assertEquals(ImageFilterKind.DUOTONE, runtime.document().effects.filter)
        assertTrue(runtime.undo())
        assertEquals(ImageFilterKind.NONE, runtime.document().effects.filter)
        assertEquals(.3f, runtime.document().adjustments.brightness, .0001f)
        assertTrue(runtime.redo())

        val signature = ImageMarkupLabel(
            id = "signature-1",
            kind = ImageMarkupLabelKind.SIGNATURE,
            points = listOf(ImageMarkupPoint(-1f, 2f), ImageMarkupPoint(.8f, .6f)),
            rotationDegrees = 600f,
        ).normalized()
        assertEquals(0f, signature.points.first().x, .0001f)
        assertEquals(1f, signature.points.first().y, .0001f)
        assertEquals(180f, signature.rotationDegrees, .0001f)
        assertTrue(runtime.edit { state -> state.copy(markup = ImageMarkup(labels = listOf(signature))) })
        assertEquals(ImageMarkupLabelKind.SIGNATURE, runtime.document().markup.labels.single().kind)
        assertTrue(runtime.undo())
        assertTrue(runtime.document().markup.labels.isEmpty())
    }

    @Test
    fun focusHistoryIsBoundedAndStoresOnlyEditParameters() {
        val runtime = ImageFocusRuntime(ImageBlock(source = "s"), ImageFocusCommitHost { _, _ -> true }, historyLimit = 8)
        repeat(20) { index -> runtime.edit { it.copy(adjustments = it.adjustments.copy(exposure = (index + 1) / 20f)) } }
        assertEquals(8, runtime.stats().undoDepth)
        assertTrue(runtime.snapshot().document.adjustments.exposure > 0f)
        assertEquals("s", runtime.snapshot().source)
    }

    @Test
    fun guideAndAccessibilityDoNotInvalidatePixelsButRealEditsDo() {
        val target = ImagePreviewPolicy.target(4_000, 3_000, 800, 600)
        val baseline = richDocument()
        val guideOnly = baseline.copy(crop = baseline.crop.copy(guide = ImageCompositionGuide.DIAGONALS))
        val accessibilityOnly = baseline.copy(accessibility = ImageAccessibility("Different", true))
        assertEquals(ImagePreviewPolicy.cacheKey("s", baseline, target), ImagePreviewPolicy.cacheKey("s", guideOnly, target))
        assertEquals(ImagePreviewPolicy.cacheKey("s", baseline, target), ImagePreviewPolicy.cacheKey("s", accessibilityOnly, target))
        assertNotEquals(
            ImagePreviewPolicy.cacheKey("s", baseline, target),
            ImagePreviewPolicy.cacheKey("s", baseline.copy(adjustments = baseline.adjustments.copy(exposure = .7f)), target),
        )
    }

    @Test
    fun presetLifecycleAndInstrumentationAreBounded() {
        val store = ImagePresetStore(limit = 2)
        assertTrue(store.put(ImagePreset("one", "First", richDocument().parameters())))
        assertTrue(store.rename("one", "Renamed"))
        assertEquals("Renamed", store.all().single().name)
        val encoded = store.encode()
        assertEquals(encoded, ImagePresetCodec.encode(ImagePresetCodec.decode(encoded)!!))
        val restored = ImagePresetStore(limit = 2)
        assertTrue(restored.restore(encoded))
        assertEquals(store.all(), restored.all())
        assertFalse(restored.restore("not-json"))
        assertFalse(store.rename("missing", "No"))
        assertTrue(store.remove("one"))
        store.put(ImagePreset("two", "Second", ImageEditParameters()))
        store.resetToDefaults()
        assertTrue(store.all().isEmpty())

        val metrics = ImagePreviewInstrumentation(keyCapacity = 2)
        val target = ImagePreviewPolicy.target(4_000, 3_000, 800, 600)
        metrics.recordRequest("one", target)
        metrics.recordRequest("one", target)
        metrics.recordPresented(target.widthPx, target.heightPx)
        val snapshot = metrics.snapshot()
        assertEquals(1, snapshot.sourceDecodeCount)
        assertEquals(1, snapshot.cacheHits)
        assertEquals(1, snapshot.cacheMisses)
        assertEquals(1, snapshot.previewRenders)
        assertEquals(target, snapshot.requestedTarget)
    }

    @Test
    fun flowAndDocumentHostsCommitOneUndoableImageTransactionWithoutMovingScene() {
        val original = ImageBlock(id = "image", source = "original", caption = "Caption")
        val edited = ImageBlockAdapter.save(original, richDocument())
        val flow = FlowHostRuntime(
            FlowEditController(EditTarget("image-flow"), BlockDocument(listOf(original))),
            FormFactor(400, 800),
        )
        assertTrue(flow.updateImage(original.id, edited))
        assertEquals(edited, flow.controller.document().findById(original.id))
        assertTrue(flow.undo())
        assertEquals(original, flow.controller.document().findById(original.id))
        assertTrue(flow.redo())
        assertEquals(edited, flow.controller.document().findById(original.id))

        val geometry = SceneGeometry(37f, 41f, 260f, 180f, rotation = 17f)
        val page = DocumentPageBlock(
            id = "page",
            geometry = PageGeometry.a4(),
            objects = listOf(SceneObject("object", SceneObjectKind.Image, geometry, z = 0, content = original)),
        ).normalized()
        val document = DocumentHostRuntime(
            DocumentSceneController(EditTarget("image-document"), BlockDocument(listOf(page))),
            FormFactor(800, 1_000),
        )
        assertTrue(document.updateImage(original.id, edited))
        val afterObject = document.controller.document().pages().single().objects.single()
        assertEquals(geometry.normalized(), afterObject.geometry)
        assertEquals(0, afterObject.z)
        assertEquals(edited, afterObject.content)
        assertTrue(document.undo())
        assertEquals(original, document.controller.document().findById(original.id))
        assertTrue(document.redo())
        assertEquals(edited, document.controller.document().findById(original.id))
    }

    @Test
    fun imageFocusShipsEnabledAndReadOnlySessionsRejectEveryMutation() {
        val defaults = EditorFeatureFlags.productionDefaults()
        assertTrue(defaults.isEnabled(EditorFeatureFlag.IMAGE_FOCUS))
        assertTrue(defaults.isEnabled(EditorFeatureFlag.FLOW_HOST))
        assertTrue(defaults.isEnabled(EditorFeatureFlag.DOCUMENT_HOST))
        assertTrue(defaults.isEnabled(EditorFeatureFlag.SPREADSHEET_HOST))
        assertTrue(defaults.isEnabled(EditorFeatureFlag.CHART_STUDIO))
        assertFalse(EditorFeatureFlags.withOverrides(EditorFeatureFlag.IMAGE_FOCUS to false).isEnabled(EditorFeatureFlag.IMAGE_FOCUS))

        // Reachability is not permission: a read-only session refuses every mutation regardless.
        val runtime = ImageFocusRuntime(ImageBlock(source = "s"), ImageFocusCommitHost { _, _ -> error("must not commit") }, readOnly = true)
        assertFalse(runtime.edit { it.copy(mask = ImageMask(ImageMaskKind.ELLIPSE)) })
        assertFalse(runtime.replaceSource("other"))
        assertFalse(runtime.beginGesture())
        assertFalse(runtime.done())
    }

    private fun richDocument() = ImageEditDocument(
        crop = ImageCrop(
            bounds = NormalizedRect(0.1f, 0.2f, 0.7f, 0.6f),
            mode = ImageCropMode.RATIO_4_3,
            guide = ImageCompositionGuide.THIRDS,
            straightenDegrees = 3f,
            quarterTurns = 1,
            flipHorizontal = true,
            perspectiveVertical = 0.2f,
        ),
        frame = ImageFrame(ImageFrameMode.FILL, 0.2f, 0.7f, 1.4f, 0.1f, -0.1f),
        mask = ImageMask(ImageMaskKind.ROUNDED_RECTANGLE, 0.2f),
        adjustments = ImageAdjustments(0.2f, 0.3f, -0.2f, 0.1f, 0.4f, -0.1f, 0.5f, 0.2f, 0.1f),
        effects = ImageEffects(0.9f, 0.1f, 0.2f, 0.3f),
        border = ImageBorder(true, ColorValue(10, 20, 30), 3f),
        shadow = ImageShadow(true, ColorValue(1, 2, 3, 80), 12f, 2f, 5f),
        accessibility = ImageAccessibility("A mountain at sunrise", decorative = false),
        sourceMetadata = ImageSourceMetadata(4_000, 3_000, "image/jpeg"),
    )
}

private inline fun <reified T> assertIs(value: Any?): T {
    assertTrue("Expected ${T::class.java.simpleName}, got ${value?.javaClass?.simpleName}", value is T)
    return value as T
}
