package com.norfold.app.domain

/**
 * A deterministic corpus of the document shapes this app has actually persisted.
 *
 * **Why fixtures rather than round-tripped objects.** A test that encodes a block with today's writer
 * and decodes it with today's reader proves the two agree with each other, which they will keep doing
 * even if both drift away from what is on disk. These fixtures are the stored side of the contract,
 * written as literal strings, so a change to the model is measured against bytes an install may
 * genuinely hold rather than against the model's own current opinion of itself.
 *
 * **What is and is not in here.** [OVERLAP_DOCUMENT_JSON] and [OVERLAP_LAYOUT_JSON] are one
 * pre-cutover row as the database holds it: the `document_json` array of per-block payloads and the
 * pre-versioned `layout_json` placement map that positioned them. The mode literals are the three
 * families `docOverlapModeOf` still decodes, across two renames. Nothing here is an invented obsolete
 * format — a fixture for a format that never shipped would certify compatibility with fiction.
 *
 * There is no user content in this file. The text is synthetic, and the non-Latin sample is here
 * because encoding bugs surface at the boundaries of the character set, not in the middle of it.
 */
object LegacyDocumentCorpus {

    /**
     * The `document_json` of an Overlap-era note, with block payloads from **three generations in one
     * array** — `o1` is a pre-envelope bare block, the rest carry the `{"v":1,…}` envelope, and `o5` is
     * a kind this build has no decoder for. That mixture is what a real row looks like: a block is only
     * rewritten in the generation that last edited it, so one document accumulates several.
     *
     * Fields absent from a payload take their defaults, which is exactly how a row written before a
     * field existed still reads. The literal is compact because the stored form is.
     */
    /**
     * The one payload in [OVERLAP_DOCUMENT_JSON] this build has no decoder for, kept separately so a
     * test can assert against the exact stored bytes rather than against its own re-decode of them.
     */
    const val OVERLAP_OPAQUE_BLOCK_JSON =
        """{"v":1,"block":{"kind":"hologram","id":"o5","beam":42,"nested":{"deep":true}}}"""

    const val OVERLAP_DOCUMENT_JSON =
        """[{"kind":"paragraph","id":"o1","content":[{"kind":"text","value":"back"}]},""" +
            """{"v":1,"block":{"kind":"paragraph","id":"o2","content":[{"kind":"text","value":"front"}]}},""" +
            """{"v":1,"block":{"kind":"table","id":"o3","headers":[{"content":[{"kind":"text","value":"A"}]},""" +
            """{"content":[{"kind":"text","value":"B"}]}],"rows":[[{"content":[{"kind":"text","value":"1"}]},""" +
            """{"content":[{"kind":"text","value":"2"}]}]]}},""" +
            """{"v":1,"block":{"kind":"image","id":"o4","source":"content://legacy/image-1"}},""" +
            OVERLAP_OPAQUE_BLOCK_JSON + "]"

    /**
     * The matching `layout_json`: the pre-versioned bare block-id → placement map, with no `placements`
     * wrapper and no canvas. Values are in dp, as that editor stored them.
     *
     * Three deliberate shapes, all of which the freeform editor could produce and none of which may be
     * silently regularized away: z is sparse (0, 3, 7, 41), `o1` and `o2` overlap each other, and `o5`
     * has no entry at all because the legacy editor drew unpositioned blocks at the origin.
     */
    const val OVERLAP_LAYOUT_JSON =
        """{"o1":{"x":10.0,"y":20.0,"width":300.0,"height":150.0,"z":7},""" +
            """"o2":{"x":60.0,"y":80.0,"width":240.0,"height":120.0,"z":3},""" +
            """"o3":{"x":12.0,"y":340.0,"width":200.0,"height":90.0,"z":41},""" +
            """"o4":{"x":200.0,"y":500.0,"width":160.0,"height":160.0,"z":0}}"""

    /** Block ids in [OVERLAP_DOCUMENT_JSON], in stored order. */
    val OVERLAP_BLOCK_IDS = listOf("o1", "o2", "o3", "o4", "o5")

    /** The one block in [OVERLAP_DOCUMENT_JSON] this build cannot natively read. */
    const val OVERLAP_OPAQUE_BLOCK_ID = "o5"

    /** Stored `layout_mode` literals, grouped by the mode each family must keep decoding to. */
    val REFLOW_LITERALS = listOf("flow", "page", "reflow")
    val BOUNDED_LITERALS = listOf("bounded", "document", "document_canvas")
    val OVERLAP_LITERALS = listOf("infinite", "infinite_canvas", "overlap")

    /** Non-Latin and combining text. Bidi, CJK, an emoji with a modifier, and a combining accent. */
    const val UNICODE_SAMPLE = "日本語 · العربية · Ελληνικά · 🇯🇵 · é · 👩‍💻"

    fun paragraph(id: String, text: String) = ParagraphBlock(id = id, content = listOf(InlineText(text)))

    /** A Reflow-era note: content with no meaningful geometry, because that editor had none. */
    fun reflowDocument(): BlockDocument = BlockDocument(
        listOf(paragraph("r1", "reflow one"), paragraph("r2", "reflow two"), paragraph("r3", UNICODE_SAMPLE)),
    )
}
