package com.norfold.app.editor.shape

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BlockDocumentJson
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.ShapeBlock
import com.norfold.app.domain.ShapeKind
import com.norfold.app.domain.style.ColorPaint
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.domain.style.Gradient
import com.norfold.app.domain.style.GradientStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persistence contract: what a shape means on disk, and what happens to a payload this build cannot read.
 *
 * Two properties carry most of these tests. First, **additivity** — a shape authored before Shape Builder
 * existed must serialize and reload exactly as it always did, and gains the canonical field only once
 * someone actually edits it. Second, **preservation** — a payload from a newer schema is never dropped,
 * downgraded or guessed at; it comes back verbatim, so a later build finds it as this one left it.
 */
class ShapeDocumentTest {
    /**
     * One document exercising every layer kind, paint form, effect and mask at once.
     *
     * Held as a field rather than rebuilt per call because [Gradient]'s default stops mint random ids: two
     * calls would produce two documents that are equal in every way a user could see and unequal to
     * [assertEquals].
     */
    private val rich = ShapeDocument(
        layers = listOf(
            ShapeLayer(
                id = "primitive",
                name = "Ring",
                content = ShapePrimitiveLayer(ShapePrimitiveKind.STAR, ShapeParameters(pointCount = 7, innerRadius = 0.4f)),
                fill = ColorPaint.Flat(ColorValue(0xFF, 0x6F, 0x36)),
                stroke = ShapeStroke(width = 4f, cap = ShapeStrokeCap.SQUARE, join = ShapeStrokeJoin.MITER),
                effects = ShapeEffects(blurRadius = 3f, shadow = ShapeShadow(enabled = true, offsetY = 6f)),
                mask = ShapeMask(enabled = true, scope = ShapeMaskScope.BELOW),
            ),
            ShapeLayer(
                id = "path",
                content = ShapePathLayer(ShapePrimitives.generate(ShapePrimitiveKind.BLOB, ShapeParameters(seed = 4))),
                fill = ColorPaint.Ramp(
                    Gradient(
                        stops = listOf(
                            GradientStop(id = "stop-a", color = ColorValue(0x6F, 0x36, 0xFF), position = 0f),
                            GradientStop(id = "stop-b", color = ColorValue(0xD8, 0xC8, 0xFF), position = 1f),
                        ),
                    ),
                ),
                blendMode = ShapeBlendMode.MULTIPLY,
                opacity = 0.75f,
            ),
            ShapeLayer(
                id = "boolean",
                content = ShapeBooleanLayer(
                    ShapeBooleanGroup(
                        id = "bool-fixture",
                        operation = ShapeBooleanOperation.SUBTRACT,
                        children = listOf(
                            ShapeLayer(id = "child-a", content = ShapePrimitiveLayer(ShapePrimitiveKind.RECTANGLE)),
                            ShapeLayer(id = "child-b", content = ShapePrimitiveLayer(ShapePrimitiveKind.ELLIPSE)),
                        ),
                    ),
                ),
                locked = true,
            ),
        ),
        accessibility = ShapeAccessibility(alternativeText = "A notched badge"),
    )

    @Test
    fun everyLayerKindSurvivesTheRoundTripIncludingGradientsEffectsAndMasks() {
        val result = ShapeDocumentCodec.decode(ShapeDocumentCodec.encode(rich))

        assertTrue("decode failed: $result", result is ShapeDecodeResult.Success)
        val restored = (result as ShapeDecodeResult.Success).document
        assertEquals(rich.normalized(), restored)
        assertEquals(3, restored.layers.size)
        assertTrue(restored.layers[0].content is ShapePrimitiveLayer)
        assertTrue(restored.layers[1].content is ShapePathLayer)
        assertTrue("a Boolean group keeps its children, not a flattened result", restored.layers[2].content is ShapeBooleanLayer)
        assertEquals(2, (restored.layers[2].content as ShapeBooleanLayer).group.children.size)
        assertTrue("a gradient fill is not downgraded to its representative color", restored.layers[1].fill is ColorPaint.Ramp)
        assertTrue(restored.layers[0].mask.enabled)
        assertEquals(ShapeBlendMode.MULTIPLY, restored.layers[1].blendMode)
        assertTrue(restored.layers[2].locked)
    }

    @Test
    fun encodingTheSameDocumentTwiceProducesTheSameBytes() {
        // Not cosmetic: an unstable payload would make every save look like an edit to everything downstream.
        val once = ShapeDocumentCodec.encode(rich)
        assertEquals(once, ShapeDocumentCodec.encode(rich))
        assertEquals("re-encoding a decoded document is a fixed point", once, ShapeDocumentCodec.encode(decoded(once)))
    }

    @Test
    fun aFieldAddedByALaterBuildIsSkippedAndAFieldItRemovedFallsBackToItsDefault() {
        val encoded = ShapeDocumentCodec.encode(rich)

        // Forward: a payload carrying a key this build has never heard of still loads.
        val withFutureKey = encoded.replaceFirst("{", "{\"cameFromALaterBuild\":true,")
        assertEquals(decoded(encoded), decoded(withFutureKey))

        // Backward: a payload written before `blendMode` existed loads with the default rather than failing.
        val withoutBlendMode = encoded
            .replace(",\"blendMode\":\"MULTIPLY\"", "")
            .replace(",\"blendMode\":\"NORMAL\"", "")
        assertTrue("the fixture must actually contain the field", withoutBlendMode.length < encoded.length)
        assertTrue(
            "every layer falls back to NORMAL",
            decoded(withoutBlendMode).layers.all { it.blendMode == ShapeBlendMode.NORMAL },
        )
    }

    @Test
    fun aNewerSchemaVersionIsRefusedWithItsPayloadUntouched() {
        val result = ShapeDocumentCodec.decode(FUTURE_PAYLOAD)

        assertTrue("$result", result is ShapeDecodeResult.UnsupportedVersion)
        assertEquals(2, (result as ShapeDecodeResult.UnsupportedVersion).version)
        assertSame("the payload is handed back, not re-serialized", FUTURE_PAYLOAD, result.raw)
    }

    @Test
    fun aMalformedPayloadIsReportedRatherThanRepaired() {
        listOf(
            "",
            "   ",
            "not json at all",
            "7",
            "{\"schemaVersion\":1,\"document\":\"not-an-object\"}",
            "{\"document\":{\"layers\":[]}}",
        ).forEach { raw ->
            val result = ShapeDocumentCodec.decode(raw)
            assertTrue("raw=$raw gave $result", result is ShapeDecodeResult.Malformed)
            assertEquals("the original is preserved", raw, (result as ShapeDecodeResult.Malformed).raw)
        }
    }

    @Test
    fun decodingNormalizesRatherThanTrustingWhatWasStored() {
        // Sentinel values are written and then rewritten in the payload, so the hostile input arrives from
        // "disk" rather than through the constructor path that would already have clamped it.
        val document = ShapeDocument(
            layers = listOf(ShapeLayer(opacity = 0.5f)),
            accessibility = ShapeAccessibility(alternativeText = "sentinel"),
        )
        val hostile = ShapeDocumentCodec.encode(document)
            .replace("\"opacity\":0.5", "\"opacity\":4.0")
            .replace("\"alternativeText\":\"sentinel\"", "\"alternativeText\":\"${"x".repeat(9_000)}\"")

        val restored = decoded(hostile)
        assertEquals("opacity is clamped on the way in", 1f, restored.layers.single().opacity, 1e-4f)
        assertEquals(ShapeAccessibility.MAX_ALT_TEXT, restored.accessibility.alternativeText.length)
        assertEquals(ShapeDocument.CURRENT_SCHEMA_VERSION, restored.schemaVersion)
    }

    @Test
    fun theLayerStackIsCappedAndADuplicateIdIsReMintedRatherThanDropped() {
        val overfull = ShapeDocument(layers = (1..ShapeDocument.MAX_LAYERS + 8).map { ShapeLayer(name = "L$it") })
        assertEquals(ShapeDocument.MAX_LAYERS, overfull.normalized().layers.size)
        assertEquals("the cap keeps the bottom of the stack, in order", "L1", overfull.normalized().layers.first().name)

        val clashing = ShapeDocument(
            layers = listOf(ShapeLayer(id = "same", name = "first"), ShapeLayer(id = "same", name = "second")),
        ).normalized()
        assertEquals("a duplicate id is corrupt bookkeeping, not a reason to delete geometry", 2, clashing.layers.size)
        assertEquals(2, clashing.layerIds().toSet().size)
        assertEquals(listOf("first", "second"), clashing.layers.map { it.name })

        val blank = ShapeDocument(layers = listOf(ShapeLayer(id = "   "))).normalized()
        assertTrue("an empty id is replaced rather than stored", blank.layers.single().id.isNotBlank())
    }

    @Test
    fun aLegacyShapeLoadsAsTheShapeItAlreadyWas() {
        ShapeKind.entries.forEach { kind ->
            val loaded = ShapeBlockAdapter.load(ShapeBlock(kind = kind))
            assertEquals("kind=$kind", LoadedShapeDocument.Origin.LEGACY_EMPTY, loaded.origin)
            assertTrue("kind=$kind is editable", loaded.isEditable)
            assertNull("kind=$kind preserves nothing, because there was nothing", loaded.preservedPayload)
            assertEquals("kind=$kind", ShapePrimitiveKind.fromLegacy(kind), primitive(loaded.document).kind)
        }

        val star = ShapeBlockAdapter.load(ShapeBlock(kind = ShapeKind.Star))
        assertEquals("a Star opens with five points, not the generic default", 5, primitive(star.document).parameters.pointCount)
        val triangle = ShapeBlockAdapter.load(ShapeBlock(kind = ShapeKind.Triangle))
        assertEquals(0f, primitive(triangle.document).parameters.cornerRadius, 1e-4f)

        val coloured = ShapeBlock(fillColor = 0xFF102030, strokeColor = 0xFF405060, strokeWidthDp = 3f)
        val layer = ShapeBlockAdapter.load(coloured).document.layers.single()
        assertEquals(ColorValue.fromArgb(0xFF102030), layer.fill.representative())
        assertEquals(ColorValue.fromArgb(0xFF405060), layer.stroke.paint.representative())
        assertEquals(3f, layer.stroke.width, 1e-4f)
        assertTrue(layer.stroke.enabled)

        val unstroked = ShapeBlockAdapter.load(ShapeBlock(strokeWidthDp = 0f)).document.layers.single()
        assertFalse("a legacy shape with no stroke does not gain one", unstroked.stroke.enabled)
    }

    @Test
    fun aRicherPaintOutranksTheLegacyWordItWasStoredBeside() {
        val gradient = ColorPaint.Ramp(Gradient())
        val block = ShapeBlock(fillColor = 0xFF000000, fillPaint = gradient)
        assertEquals(gradient, ShapeBlockAdapter.load(block).document.layers.single().fill)
    }

    @Test
    fun savingTouchesOnlyTheCanonicalFieldAndTheWordsThatMirrorIt() {
        val original = ShapeBlock(
            id = "shape-7",
            kind = ShapeKind.Hexagon,
            text = "Badge",
            displayHeightDp = 240f,
            rotationDegrees = 15f,
        )
        val opened = ShapeBlockAdapter.load(original).document
        val layerId = opened.layers.single().id
        val edited = ShapeLayerEditor.setStrokeWidth(
            ShapeLayerEditor.setFill(opened, layerId, ColorPaint.Flat(ColorValue(0x11, 0x22, 0x33))),
            layerId,
            7f,
        )
        val saved = ShapeBlockAdapter.save(original, edited)

        assertNotNull(saved.shapeEditDocument)
        assertEquals("the legacy word is kept in step for consumers that predate the payload", 0xFF112233, saved.fillColor)
        assertEquals(7f, saved.strokeWidthDp, 1e-4f)
        assertEquals(
            "everything except the canonical field and its two mirrors is untouched",
            original,
            saved.copy(
                shapeEditDocument = null,
                fillColor = original.fillColor,
                strokeColor = original.strokeColor,
                strokeWidthDp = original.strokeWidthDp,
            ),
        )
    }

    @Test
    fun aSavedShapeReloadsAsCanonicalAndKeepsItsPayloadVerbatim() {
        val saved = ShapeBlockAdapter.save(ShapeBlock(kind = ShapeKind.Ellipse), rich)
        val loaded = ShapeBlockAdapter.load(saved)

        assertEquals(LoadedShapeDocument.Origin.CANONICAL, loaded.origin)
        assertEquals(rich.normalized(), loaded.document)
        assertEquals(saved.shapeEditDocument, loaded.preservedPayload)
        assertTrue(loaded.isEditable)
        assertEquals("saving what was loaded changes nothing", saved, ShapeBlockAdapter.save(saved, loaded.document))
    }

    @Test
    fun anUnreadablePayloadIsPreservedAndReportedAsNotEditable() {
        val unsupported = ShapeBlockAdapter.load(ShapeBlock(kind = ShapeKind.Star, shapeEditDocument = FUTURE_PAYLOAD))
        assertEquals(LoadedShapeDocument.Origin.UNSUPPORTED_CANONICAL, unsupported.origin)
        assertEquals(FUTURE_PAYLOAD, unsupported.preservedPayload)
        assertFalse("editing it would overwrite geometry this build cannot see", unsupported.isEditable)
        assertEquals(
            "something still draws: the legacy shape it was before",
            ShapePrimitiveKind.STAR,
            primitive(unsupported.document).kind,
        )

        val malformed = ShapeBlockAdapter.load(ShapeBlock(shapeEditDocument = "{ truncated"))
        assertEquals(LoadedShapeDocument.Origin.MALFORMED_CANONICAL, malformed.origin)
        assertEquals("{ truncated", malformed.preservedPayload)
        assertFalse(malformed.isEditable)
    }

    @Test
    fun aBuiltShapeSurvivesTheBlockEnvelopeAndTheWholeDocumentRoundTrip() {
        val built = ShapeBlockAdapter.save(ShapeBlock(id = "s1", text = "Ring"), rich)

        assertEquals(built, BlockDocumentJson.decodeBlock(BlockDocumentJson.encodeBlock(built)))

        val document = BlockDocument(listOf(ParagraphBlock(), built, ParagraphBlock()))
        val restored = BlockDocumentJson.decode(BlockDocumentJson.encode(document))
        val shape = restored.blocks.filterIsInstance<ShapeBlock>().single()
        assertEquals(built, shape)
        assertEquals(
            "the geometry survives the outer envelope, not just the block",
            rich.normalized(),
            ShapeBlockAdapter.load(shape).document,
        )
    }

    @Test
    fun aShapeAuthoredBeforeShapeBuilderSerializesExactlyAsItAlwaysDid() {
        val legacy = ShapeBlock(id = "old", kind = ShapeKind.SpeechBubble, text = "Note", strokeWidthDp = 2f)
        ShapeBlockAdapter.load(legacy)
        assertNull("loading is side-effect free", legacy.shapeEditDocument)

        val restored = BlockDocumentJson.decode(BlockDocumentJson.encode(BlockDocument(listOf(legacy))))
            .blocks.filterIsInstance<ShapeBlock>().single()
        assertEquals(legacy, restored)
        assertNull("the canonical field stays absent until someone edits the shape", restored.shapeEditDocument)
    }

    @Test
    fun aBooleanGroupOwnsItsChildrenSoUngroupReturnsThemWhole() {
        val base = ShapeDocument(
            layers = listOf(
                ShapeLayer(id = "back", content = ShapePrimitiveLayer(ShapePrimitiveKind.RECTANGLE)),
                ShapeLayer(id = "cut", content = ShapePrimitiveLayer(ShapePrimitiveKind.ELLIPSE, ShapeParameters(warp = 0.3f))),
                ShapeLayer(id = "front", content = ShapePrimitiveLayer(ShapePrimitiveKind.STAR)),
            ),
        )
        val grouped = ShapeLayerEditor.groupAsBoolean(base, listOf("back", "cut"), ShapeBooleanOperation.SUBTRACT)
        assertEquals("the children move into the group", 2, grouped.layers.size)
        val groupLayer = grouped.layers.first { it.content is ShapeBooleanLayer }
        val group = (groupLayer.content as ShapeBooleanLayer).group
        assertEquals(ShapeBooleanOperation.SUBTRACT, group.operation)
        assertEquals("children keep their own stack order, bottom first", listOf("back", "cut"), group.children.map { it.id })
        assertEquals("the group lands where the user was looking", "front", grouped.layers.last().id)

        val ungrouped = ShapeLayerEditor.ungroup(grouped, groupLayer.id)
        assertEquals(listOf("back", "cut", "front"), ungrouped.layerIds())
        assertEquals("parameters and all", base.normalized().layers, ungrouped.layers)

        // Flatten is the lossy sibling, and losing the children is a choice the user makes explicitly.
        assertTrue(ShapeLayerEditor.flatten(grouped, groupLayer.id).layers.first().content is ShapePathLayer)
        assertEquals(
            "a group needs two operands",
            base,
            ShapeLayerEditor.groupAsBoolean(base, listOf("back"), ShapeBooleanOperation.UNION),
        )
    }

    @Test
    fun aBooleanGroupRoundTripsThroughJsonWithItsChildrenIntact() {
        val group = ShapeBooleanGroup(
            id = "bool-1",
            operation = ShapeBooleanOperation.EXCLUDE,
            children = listOf(
                ShapeLayer(id = "a", content = ShapePrimitiveLayer(ShapePrimitiveKind.RECTANGLE)),
                ShapeLayer(id = "b", content = ShapePathLayer(ShapePrimitives.generate(ShapePrimitiveKind.ELLIPSE))),
            ),
        )
        val document = ShapeDocument(layers = listOf(ShapeLayer(content = ShapeBooleanLayer(group))))
        val back = (decoded(ShapeDocumentCodec.encode(document)).layers.single().content as ShapeBooleanLayer).group

        assertEquals(group.normalized(), back)
        assertEquals(ShapeBooleanOperation.EXCLUDE, back.operation)
        assertTrue(back.isResolvable)

        val overfull = ShapeBooleanGroup(children = (1..ShapeBooleanGroup.MAX_CHILDREN + 5).map { ShapeLayer() })
        assertEquals(ShapeBooleanGroup.MAX_CHILDREN, overfull.normalized().children.size)
    }

    @Test
    fun convertToPathGivesUpTheParametersAndNotTheOutline() {
        val base = ShapeDocument(layers = listOf(ShapeLayer(id = "l", content = ShapePrimitiveLayer(ShapePrimitiveKind.LINE))))
        val converted = ShapeLayerEditor.convertToPath(base, "l")
        val path = (converted.layers.single().content as ShapePathLayer).path

        assertEquals("the outline is taken exactly as generated", ShapePrimitives.generate(ShapePrimitiveKind.LINE), path)
        assertFalse("a Line must not come back as a closed loop", path.subpaths.single().closed)
        assertEquals("the one-way door only swings once", converted, ShapeLayerEditor.convertToPath(converted, "l"))
    }

    @Test
    fun everyPresetNormalizesToAFixedPointAndRoundTrips() {
        ShapePrimitives.presets.forEach { preset ->
            val document = ShapeDocument.fromPreset(preset).normalized()
            assertEquals("preset=${preset.id}", document, document.normalized())
            assertEquals("preset=${preset.id}", preset.kind, primitive(document).kind)
            assertEquals("preset=${preset.id} round-trips", document, decoded(ShapeDocumentCodec.encode(document)))
            assertTrue("preset=${preset.id} draws something", document.isRenderable())
        }
        assertFalse("a blank path document has nothing to render yet", ShapeDocument.emptyPathDocument().isRenderable())
    }

    @Test
    fun theLayerContentDiscriminatorIsTheFullyQualifiedNameEveryStoredShapeWasWrittenWith() {
        // `ShapeLayer.content` is polymorphic, so kotlinx-serialization writes a type discriminator for
        // it, and with no `@SerialName` on the three implementors that discriminator is each one's
        // fully-qualified name. Those exact strings are already on disk in every shape anyone has saved.
        //
        // Two things could change them, both looking like tidying up: adding `@SerialName`, or moving a
        // class to another package. Either would leave existing shapes undecodable — they would load as
        // MALFORMED_CANONICAL, which preserves the payload but makes the shape uneditable. Pinning the
        // strings here means such a change fails this test instead of orphaning a user's work.
        //
        // Minification does not threaten them: the serial name is a string constant in the generated
        // `$$serializer`, and R8 renames classes without rewriting string literals.
        val encoded = ShapeDocumentCodec.encode(rich)

        listOf(
            "com.norfold.app.editor.shape.ShapePrimitiveLayer",
            "com.norfold.app.editor.shape.ShapePathLayer",
            "com.norfold.app.editor.shape.ShapeBooleanLayer",
        ).forEach { discriminator ->
            assertTrue(
                "the wire format must keep writing \"type\":\"$discriminator\"",
                encoded.contains("\"type\":\"$discriminator\""),
            )
        }

        // And the other direction: a payload carrying those names still decodes to those types. This is
        // the upgrade path — every shape saved by any prior build arrives looking exactly like this.
        val stored = """
            {"schemaVersion":1,"document":{"schemaVersion":1,"layers":[
              {"id":"p","content":{"type":"com.norfold.app.editor.shape.ShapePrimitiveLayer","kind":"STAR"}},
              {"id":"q","content":{"type":"com.norfold.app.editor.shape.ShapePathLayer"}},
              {"id":"r","content":{"type":"com.norfold.app.editor.shape.ShapeBooleanLayer"}}
            ],"accessibility":{"alternativeText":"stored by an earlier build"}}}
        """.trimIndent()
        val restored = decoded(stored)
        assertTrue(restored.layers[0].content is ShapePrimitiveLayer)
        assertTrue(restored.layers[1].content is ShapePathLayer)
        assertTrue(restored.layers[2].content is ShapeBooleanLayer)
        assertEquals("stored by an earlier build", restored.accessibility.alternativeText)
    }

    private fun decoded(raw: String): ShapeDocument =
        (ShapeDocumentCodec.decode(raw) as ShapeDecodeResult.Success).document

    private fun primitive(document: ShapeDocument): ShapePrimitiveLayer =
        document.layers.single().content as ShapePrimitiveLayer

    private companion object {
        /** Structurally valid, so the refusal is about the version rather than about the parse failing. */
        const val FUTURE_PAYLOAD =
            "{\"schemaVersion\":2,\"document\":{\"schemaVersion\":2,\"layers\":[],\"accessibility\":{}}}"
    }
}
