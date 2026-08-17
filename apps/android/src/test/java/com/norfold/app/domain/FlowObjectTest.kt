package com.norfold.app.domain

import com.norfold.app.domain.style.StyleAssignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one guarantee [FlowObject] has to make: adopting it changes nothing a user can see.
 *
 * `FlowObject` is a read-time derivation, not a stored format — no serializer changed and no saved note was
 * rewritten. That claim is only worth as much as the evidence that a document written by an older build
 * derives the presentation that build drew it with, so most of this file asserts against
 * [LegacyDocumentCorpus] bytes rather than against blocks the test constructed. A test that built a
 * `ChartBlock` and checked it derived `Fixed(180f)` would be asserting that a default equals itself.
 *
 * The height assertions name their expected dp as a literal on purpose. Reading `ChartBlock().editorHeightDp`
 * as the expectation would keep passing if that default silently changed, which is the exact regression the
 * suite exists to catch — the number is part of the contract, so it is written down.
 */
class FlowObjectTest {

    // ---------------------------------------------------------------------------------------------
    // Upgrade safety: stored bytes derive the presentation they already had.
    // ---------------------------------------------------------------------------------------------

    /**
     * A real stored row — three block generations in one array — derives a frame for every block without
     * losing, reordering or dropping any of them.
     */
    @Test
    fun `legacy document derives one object per stored block`() {
        val document = BlockDocumentJson.decodeDocumentPayload(LegacyDocumentCorpus.OVERLAP_DOCUMENT_JSON)
        val objects = document.blocks.map { it.deriveFlowObject() }

        assertEquals(document.blocks.size, objects.size)
        assertEquals(document.blocks.map { it.id }, objects.map { it.id })
        // The derivation wraps the block, it does not copy or rebuild it: an object's content must be the
        // very same instance, or a later editing command would write through to a detached duplicate.
        document.blocks.zip(objects).forEach { (block, obj) -> assertSame(block, obj.content) }
    }

    /**
     * The stored image in the corpus carries **no** `displayHeightDp`, so it decodes to the default — and
     * must derive that default rather than something the new model preferred.
     *
     * This is the case that would have broken a migration: a field absent from disk has to mean "what this
     * build has always used", not "unknown".
     */
    @Test
    fun `stored image without a height field derives its default height`() {
        val document = BlockDocumentJson.decodeDocumentPayload(LegacyDocumentCorpus.OVERLAP_DOCUMENT_JSON)
        val image = document.blocks.filterIsInstance<ImageBlock>().single()

        assertEquals(FlowHeight.Fixed(220f), image.deriveFlowObject().height)
    }

    /**
     * A payload this build cannot decode still derives an object.
     *
     * `UnknownBlock` exists so a note written by a *newer* build survives a round trip through this one. If
     * the derivation could not frame it, the Flow renderer would have a block it could not present, and the
     * safest outcome — showing it as opaque, intact content — would be unavailable.
     */
    @Test
    fun `undecodable block still derives a flowing object`() {
        val unknown = BlockDocumentJson.decodeBlock(LegacyDocumentCorpus.OVERLAP_OPAQUE_BLOCK_JSON)
        val obj = unknown.deriveFlowObject()

        assertTrue(unknown is UnknownBlock)
        assertEquals(FlowHeight.Intrinsic, obj.height)
        assertFalse(obj.framed)
        assertEquals(BlockAlign.Start, obj.align)
    }

    // ---------------------------------------------------------------------------------------------
    // The seven replaced height fields.
    // ---------------------------------------------------------------------------------------------

    /**
     * Each of the seven blocks that stored its own height derives that stored value exactly.
     *
     * The defaults are asserted as literals because they are the contract; and each is paired with a
     * non-default value, because a derivation hard-coding the default would pass the first half alone.
     */
    @Test
    fun `every height-bearing block derives its stored height`() {
        val defaults = listOf<Pair<DocumentBlock, Float>>(
            CodeBlock() to 180f,
            ImageBlock() to 220f,
            EmbedBlock() to 112f,
            ChartBlock() to 180f,
            MathBlock() to 140f,
            MermaidBlock() to 180f,
            ShapeBlock() to 180f,
        )
        defaults.forEach { (block, dp) ->
            assertEquals(
                "default height of ${block::class.simpleName}",
                FlowHeight.Fixed(dp),
                block.deriveFlowObject().height,
            )
        }

        val edited = listOf(
            CodeBlock(editorHeightDp = 300f),
            ImageBlock(displayHeightDp = 300f),
            EmbedBlock(displayHeightDp = 300f),
            ChartBlock(editorHeightDp = 300f),
            MathBlock(editorHeightDp = 300f),
            MermaidBlock(editorHeightDp = 300f),
            ShapeBlock(displayHeightDp = 300f),
        )
        edited.forEach { block ->
            assertEquals(
                "edited height of ${block::class.simpleName}",
                FlowHeight.Fixed(300f),
                block.deriveFlowObject().height,
            )
        }
    }

    /** Text blocks have no stored height and must not acquire one. */
    @Test
    fun `text blocks derive intrinsic height`() {
        val text = listOf(
            ParagraphBlock(), HeadingBlock(), QuoteBlock(), CalloutBlock(),
            BulletListBlock(), NumberedListBlock(), TodoListBlock(), DividerBlock(),
        )
        text.forEach { block ->
            assertEquals(
                "${block::class.simpleName} must flow",
                FlowHeight.Intrinsic,
                block.deriveFlowObject().height,
            )
        }
    }

    /**
     * A table derives [FlowHeight.Intrinsic] even though it stores `columnWidthsDp`.
     *
     * Column widths are internal table structure, not object presentation — the table's height is the sum of
     * its rows. Folding them into the object model would have made a resize command able to squash a table's
     * columns while claiming to change its height.
     */
    @Test
    fun `table height is intrinsic and its column widths are untouched`() {
        val table = TableBlock(columnWidthsDp = listOf(120f, 80f))
        val obj = table.deriveFlowObject()

        assertEquals(FlowHeight.Intrinsic, obj.height)
        assertEquals(listOf(120f, 80f), (obj.content as TableBlock).columnWidthsDp)
    }

    // ---------------------------------------------------------------------------------------------
    // Framing and selectability — the type test this replaces.
    // ---------------------------------------------------------------------------------------------

    /**
     * The defect that motivated the type: a table and a shape are selectable now, and they were not.
     *
     * `FlowHostScreen` decided selectability with `block is ImageBlock || block is ChartBlock`, so no other
     * kind could be picked up however obviously object-like it was. This asserts the two ends of that: the
     * kinds the old test allowed still qualify, and the kinds it excluded now do too.
     */
    @Test
    fun `object-like blocks are selectable and text is not`() {
        listOf(ImageBlock(), ChartBlock(), TableBlock(), ShapeBlock(), FileBlock(), EmbedBlock(), CodeBlock())
            .forEach { block ->
                assertTrue(
                    "${block::class.simpleName} must be selectable",
                    block.deriveFlowObject().selectable,
                )
            }

        listOf(ParagraphBlock(), HeadingBlock(), QuoteBlock(), BulletListBlock(), DividerBlock())
            .forEach { block ->
                assertFalse(
                    "${block::class.simpleName} must not be selectable",
                    block.deriveFlowObject().selectable,
                )
            }
    }

    /**
     * A table and a file card are framed through [FlowFrame.Framed] rather than through a height.
     *
     * They are the two kinds that are boxes on the page while having no stored height, so `Auto` alone would
     * have flowed them — which is why they carry an explicit frame and why `framed` cannot simply be
     * "has a height".
     */
    @Test
    fun `heightless boxes are framed explicitly`() {
        listOf(TableBlock(), FileBlock()).forEach { block ->
            val obj = block.deriveFlowObject()
            assertEquals("${block::class.simpleName} frame", FlowFrame.Framed, obj.frame)
            assertEquals("${block::class.simpleName} height", FlowHeight.Intrinsic, obj.height)
            assertTrue("${block::class.simpleName} framed", obj.framed)
        }
    }

    /** A user's explicit frame choice overrides what the kind would have decided. */
    @Test
    fun `an explicit frame choice beats the derivation`() {
        val chart = ChartBlock().deriveFlowObject()
        assertTrue(chart.framed)
        assertFalse(chart.copy(frame = FlowFrame.None).framed)

        val paragraph = ParagraphBlock().deriveFlowObject()
        assertFalse(paragraph.framed)
        assertTrue(paragraph.copy(frame = FlowFrame.Framed).framed)
    }

    // ---------------------------------------------------------------------------------------------
    // Alignment and style.
    // ---------------------------------------------------------------------------------------------

    /** The two blocks that had alignment keep it; every other kind reports the wire-format default. */
    @Test
    fun `alignment is carried from the blocks that store it`() {
        assertEquals(
            BlockAlign.Center,
            ParagraphBlock(align = BlockAlign.Center).deriveFlowObject().align,
        )
        assertEquals(
            BlockAlign.Justify,
            HeadingBlock(align = BlockAlign.Justify).deriveFlowObject().align,
        )
        assertEquals(BlockAlign.Start, QuoteBlock().deriveFlowObject().align)
        assertEquals(BlockAlign.Start, ImageBlock().deriveFlowObject().align)
    }

    /** Style comes through the existing accessor, so the two cannot disagree about a block's style. */
    @Test
    fun `style is read through the existing assignment accessor`() {
        val styled = ParagraphBlock(style = StyleAssignment(styleId = "body"))
        assertEquals(styled.styleAssignment(), styled.deriveFlowObject().style)
        assertEquals(StyleAssignment(styleId = "body"), styled.deriveFlowObject().style)

        // A kind with no style support reports the empty assignment rather than null.
        assertEquals(StyleAssignment.NONE, DividerBlock().deriveFlowObject().style)
    }

    // ---------------------------------------------------------------------------------------------
    // Writing a height back.
    // ---------------------------------------------------------------------------------------------

    /** The inverse of the derivation: what a resize command writes is what the next read derives. */
    @Test
    fun `a written height round trips through the block`() {
        val blocks = listOf(
            CodeBlock(), ImageBlock(), EmbedBlock(), ChartBlock(), MathBlock(), MermaidBlock(), ShapeBlock(),
        )
        blocks.forEach { block ->
            val resized = block.withFlowHeight(FlowHeight.Fixed(264f))
            assertEquals(
                "${block::class.simpleName} round trip",
                FlowHeight.Fixed(264f),
                resized.deriveFlowObject().height,
            )
            // Identity is preserved, so a resize is an edit of the block rather than a replacement of it.
            assertEquals(block.id, resized.id)
        }
    }

    /**
     * Intrinsic and Ratio write nothing.
     *
     * Neither can be expressed in the `Float` field that exists, and flattening a ratio to whatever height it
     * currently resolves to would convert a durable property into a stale number — the very defect the seven
     * stored dp fields have.
     */
    @Test
    fun `heights that a float cannot express are not written`() {
        val image = ImageBlock(displayHeightDp = 240f)
        assertSame(image, image.withFlowHeight(FlowHeight.Intrinsic))
        assertSame(image, image.withFlowHeight(FlowHeight.Ratio(1.5f)))
        assertEquals(240f, (image.withFlowHeight(FlowHeight.Ratio(1.5f)) as ImageBlock).displayHeightDp, 0f)
    }

    /** Writing a height to a kind that stores none is a no-op rather than an error. */
    @Test
    fun `blocks without a height field ignore a written height`() {
        listOf(ParagraphBlock(), HeadingBlock(), TableBlock(), FileBlock(), DividerBlock()).forEach { block ->
            assertSame(block, block.withFlowHeight(FlowHeight.Fixed(264f)))
            assertFalse("${block::class.simpleName} supportsFlowHeight", block.supportsFlowHeight())
        }
    }

    /** The capability answer agrees with what a write actually does, for every kind. */
    @Test
    fun `height support is reported for exactly the kinds that store one`() {
        listOf(CodeBlock(), ImageBlock(), EmbedBlock(), ChartBlock(), MathBlock(), MermaidBlock(), ShapeBlock())
            .forEach { block ->
                assertTrue("${block::class.simpleName} supportsFlowHeight", block.supportsFlowHeight())
            }
    }

    /**
     * A block already at the probe height still reports support.
     *
     * A capability check implemented by "does writing change anything" would answer false here, and the
     * resize control would vanish from a block that happened to be one specific height.
     */
    @Test
    fun `height support does not depend on the block's current height`() {
        assertTrue(ChartBlock(editorHeightDp = 137f).supportsFlowHeight())
        assertTrue(ImageBlock(displayHeightDp = 137f).supportsFlowHeight())
    }

    // ---------------------------------------------------------------------------------------------
    // Degenerate stored values.
    // ---------------------------------------------------------------------------------------------

    /**
     * A stored height is kept **exactly**, including values smaller than any renderer would draw.
     *
     * The domain model deliberately does not clamp: the Flow renderer already applies its own presentation
     * limits in six places (`120f..560f` for an image, `72f..560f` for a shape, `156f..360f` for a diagram),
     * and an eighth range here would be one more thing to disagree with them. More importantly, a model that
     * rounded a stored 10dp up to a floor of its own would corrupt that value the first time a resize command
     * read and wrote it back.
     */
    @Test
    fun `a small stored height is preserved rather than clamped`() {
        assertEquals(FlowHeight.Fixed(10f), ChartBlock(editorHeightDp = 10f).deriveFlowObject().height)
        assertEquals(FlowHeight.Fixed(9_000f), ChartBlock(editorHeightDp = 9_000f).deriveFlowObject().height)
    }

    /**
     * Values that are not heights at all become [FlowHeight.Intrinsic] instead of reaching the renderer.
     *
     * NaN and infinity are arithmetic accidents upstream; passing either to Compose is a crash or an
     * invisible block. Zero and negatives were never legitimately stored.
     */
    @Test
    fun `non-heights derive intrinsic`() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -40f).forEach { dp ->
            assertEquals("stored $dp", FlowHeight.Intrinsic, ChartBlock(editorHeightDp = dp).deriveFlowObject().height)
        }
    }

    /** A ratio *is* repaired, because it is computed from a loader rather than chosen by a user. */
    @Test
    fun `an absurd ratio is clamped and an unknown one is intrinsic`() {
        assertEquals(FlowHeight.Ratio(FlowHeight.MAXIMUM_RATIO), FlowHeight.ratioOrIntrinsic(500f))
        assertEquals(FlowHeight.Ratio(FlowHeight.MINIMUM_RATIO), FlowHeight.ratioOrIntrinsic(0.001f))
        assertEquals(FlowHeight.Ratio(1.5f), FlowHeight.ratioOrIntrinsic(1.5f))

        listOf(0f, -2f, Float.NaN, Float.POSITIVE_INFINITY).forEach { value ->
            assertEquals("ratio $value", FlowHeight.Intrinsic, FlowHeight.ratioOrIntrinsic(value))
        }
    }

    /** A ratio is a different answer from a fixed height, and the two must never compare equal. */
    @Test
    fun `height cases are distinct`() {
        // Written as an equality check rather than `assertNotEquals` because JUnit 4 resolves that call to a
        // primitive overload here, which would compare something other than the two heights.
        assertFalse(FlowHeight.Fixed(2f) == FlowHeight.Ratio(2f) as FlowHeight)
        assertFalse(FlowHeight.Intrinsic == FlowHeight.Fixed(0f) as FlowHeight)
        assertTrue(FlowHeight.Fixed(2f) == FlowHeight.Fixed(2f))
        assertTrue(FlowHeight.Intrinsic == FlowHeight.Intrinsic)
    }

    // ---------------------------------------------------------------------------------------------
    // Size policy — the presentation limits the model deliberately does not apply.
    // ---------------------------------------------------------------------------------------------

    /**
     * The defect the policy exists to remove: one shape, one range, whichever path draws it.
     *
     * A built shape was clamped to a 120dp floor and an unbuilt one to 72dp, so promoting a 90dp shape into a
     * shape document made it 30dp taller with nothing about its height having been edited. Both renderer paths
     * now read [FlowSizePolicy.SHAPE], and this is the assertion that keeps them reading the same one.
     */
    @Test
    fun `a shape has one height range regardless of which path draws it`() {
        val policy = FlowSizePolicy.of(ShapeBlock())
        assertEquals(FlowSizePolicy.SHAPE, policy)
        assertEquals(72f, FlowSizePolicy.SHAPE.minHeightDp, 0f)
        // The value that used to differ between the two paths survives at its stored height in both.
        assertEquals(90f, FlowSizePolicy.SHAPE.clamp(90f), 0f)
    }

    /**
     * Every range is named as a literal, because these are the numbers the renderer drew with before the
     * policy existed and the point of the type is that adopting it changed no pixel.
     */
    @Test
    fun `each kind keeps the range its renderer already used`() {
        val expected = listOf<Triple<DocumentBlock, Float, Float>>(
            Triple(ImageBlock(), 120f, 560f),
            Triple(ChartBlock(), 120f, 560f),
            Triple(ShapeBlock(), 72f, 560f),
            Triple(CodeBlock(), 180f, 560f),
            Triple(MermaidBlock(), 156f, 360f),
        )
        expected.forEach { (block, min, max) ->
            val policy = requireNotNull(FlowSizePolicy.of(block)) { "${block::class.simpleName} has no policy" }
            assertEquals("${block::class.simpleName} floor", min, policy.minHeightDp, 0f)
            assertEquals("${block::class.simpleName} ceiling", max, policy.maxHeightDp, 0f)
        }
    }

    /**
     * A kind's floor never exceeds its own stored default.
     *
     * This is the invariant that stops a policy from being a migration in disguise. `MathBlock` defaults to
     * 140dp and `EmbedBlock` to 112dp, and neither field is read by any renderer today — so giving either the
     * 180dp floor its neighbouring source panes use would silently grow every existing block of that kind the
     * day someone wired the field up. Asserted for all seven rather than just those two, because the next kind
     * to gain a policy should fail here rather than in a user's document.
     */
    @Test
    fun `no policy floor is taller than the kind's own default height`() {
        val blocks = listOf(
            CodeBlock(), ImageBlock(), EmbedBlock(), ChartBlock(), MathBlock(), MermaidBlock(), ShapeBlock(),
        )
        blocks.forEach { block ->
            val policy = requireNotNull(FlowSizePolicy.of(block)) { "${block::class.simpleName} has no policy" }
            val default = (block.deriveFlowObject().height as FlowHeight.Fixed).dp
            assertTrue(
                "${block::class.simpleName} default ${default}dp is below its ${policy.minHeightDp}dp floor",
                policy.minHeightDp <= default,
            )
            // Stated the other way too: clamping a kind's default must return it unchanged.
            assertEquals("${block::class.simpleName} default survives", default, policy.clamp(default), 0f)
        }
    }

    /** Exactly the kinds that store a height have a policy — the same seven, from a second direction. */
    @Test
    fun `a policy exists for exactly the kinds that store a height`() {
        listOf(CodeBlock(), ImageBlock(), EmbedBlock(), ChartBlock(), MathBlock(), MermaidBlock(), ShapeBlock())
            .forEach { block ->
                assertTrue(
                    "${block::class.simpleName} stores a height so it needs a policy",
                    block.supportsFlowHeight() && FlowSizePolicy.of(block) != null,
                )
            }

        listOf(ParagraphBlock(), HeadingBlock(), TableBlock(), FileBlock(), DividerBlock(), QuoteBlock())
            .forEach { block ->
                assertFalse(
                    "${block::class.simpleName} has no height so it must have no policy",
                    block.supportsFlowHeight() || FlowSizePolicy.of(block) != null,
                )
            }
    }

    /**
     * Clamping is total, including for the values `coerceIn` alone cannot handle.
     *
     * Every comparison against NaN is false, so `NaN.coerceIn(120f, 560f)` returns NaN — and a NaN height
     * reaching Compose is a crash or an invisible block. The floor is the answer, because content drawn small
     * is recoverable and content not drawn at all is not.
     */
    @Test
    fun `clamping repairs values that are not heights`() {
        val policy = FlowSizePolicy.MEDIA
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { dp ->
            assertEquals("clamped $dp", policy.minHeightDp, policy.clamp(dp), 0f)
        }
        assertEquals(policy.minHeightDp, policy.clamp(0f), 0f)
        assertEquals(policy.minHeightDp, policy.clamp(-40f), 0f)
        assertEquals(policy.maxHeightDp, policy.clamp(9_000f), 0f)
        assertEquals(300f, policy.clamp(300f), 0f)
    }

    /**
     * A height with no dp resolves to null rather than to a number.
     *
     * `Intrinsic` and `Ratio` both mean "this content does not tell you a dp", and the caller is the only
     * thing that knows what to do about it — a text block flows, and an image whose aspect has not resolved
     * must not be pinned to an invented default. Returning the floor here would have been that invention.
     */
    @Test
    fun `only a fixed height resolves to a drawable dp`() {
        val policy = FlowSizePolicy.MEDIA
        // `requireNotNull` before the float comparison so this asserts the *value* through the delta overload
        // rather than boxing both sides into an `Any` equality check.
        assertEquals(220f, requireNotNull(policy.resolve(FlowHeight.Fixed(220f))), 0f)
        assertEquals(policy.minHeightDp, requireNotNull(policy.resolve(FlowHeight.Fixed(10f))), 0f)
        assertNull(policy.resolve(FlowHeight.Intrinsic))
        assertNull(policy.resolve(FlowHeight.Ratio(1.5f)))
    }

    /**
     * The model still does not clamp, and the policy still does — the two answer different questions.
     *
     * A stored 10dp survives the derivation intact and is only brought up to a floor at the moment something
     * draws it. That separation is what lets a resize command round-trip a value through
     * [DocumentBlock.withFlowHeight] without the model rewriting what the user chose.
     */
    @Test
    fun `the derivation preserves a height the policy would clamp`() {
        val tiny = ChartBlock(editorHeightDp = 10f)
        assertEquals(FlowHeight.Fixed(10f), tiny.deriveFlowObject().height)
        assertEquals(120f, requireNotNull(FlowSizePolicy.of(tiny)).clamp(10f), 0f)
        // And the stored value is untouched by having been drawn.
        assertEquals(10f, tiny.editorHeightDp, 0f)
    }
}
