package com.norfold.app.ui.editor.flow

import com.norfold.app.domain.SemanticActionId
import com.norfold.app.editor.flow.FlowBlockKind
import com.norfold.app.editor.flow.FlowIds
import com.norfold.app.editor.relationship.RelationshipCommandIds
import com.norfold.app.editor.style.EditorStyleCommandIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The dock's icon resolver — the one pure decision the rebuilt [FlowContextDock] delegates to.
 *
 * As [FlowObjectFrameTest] records, there is no Compose harness in this source set, so the composable's
 * layout is only observable on a device. What *is* testable is the decision that replaced the defect: which
 * glyph stands for which command.
 *
 * The dock this replaced chose glyphs by reading the command's human label — `label.contains("bold")`,
 * `label.contains("link")`, and an `else` that drew a generic text-fields icon for everything else. A caption
 * is not an identifier: it is localised, it is edited for tone, and unrelated commands share words. So every
 * assertion here is about *identity*, and the file exists so that a renamed label can never again silently
 * repaint or blank a control.
 */
class FlowDockSemanticsTest {

    // ── Structural commands ──────────────────────────────────────────────────────────────────────────

    /**
     * Every command that holds a permanent dock slot draws something that means *that* command.
     *
     * `More` is the resolver's neutral fallback, so a structural command resolving to it is precisely the old
     * `else -> Icons.Filled.TextFields` failure wearing a new name: a control the user cannot tell from its
     * neighbour. Adding an id to `DOCK_ORDER` without giving it a glyph fails here rather than on a device.
     */
    @Test
    fun `every docked structural command has a glyph of its own`() {
        val resolved = FlowIds.DOCK_ORDER.associateWith { flowSemanticAction(it) }

        val fellBack = resolved.filterValues { it == SemanticActionId.More }.keys
        assertEquals("structural commands with no glyph of their own", emptySet<String>(), fellBack)
        assertEquals(
            "two structural commands share one glyph",
            FlowIds.DOCK_ORDER.size,
            resolved.values.toSet().size,
        )
    }

    /**
     * The recorded defect, pinned.
     *
     * The old dock captioned a button "Move" and looked the command up by that caption. Because the dock is
     * built in `DOCK_ORDER`, whose first entry is `MOVE_UP`, the drag-to-reposition button ran *move up*.
     * Reposition and move-up are different commands and must therefore be different glyphs.
     */
    @Test
    fun `reposition and move up are not the same control`() {
        assertNotEquals(
            flowSemanticAction(FlowIds.MOVE_UP),
            flowSemanticAction(FlowIds.DROP),
        )
    }

    /** Indent and outdent are mirror actions; drawing one for both would make the pair unusable. */
    @Test
    fun `nesting directions are distinct`() {
        assertEquals(SemanticActionId.NestIn, flowSemanticAction(FlowIds.NEST))
        assertEquals(SemanticActionId.NestOut, flowSemanticAction(FlowIds.UNNEST))
    }

    // ── Inline text commands ─────────────────────────────────────────────────────────────────────────

    /**
     * The five inline marks are the commands the old label-matching actually recognised, and the ones a user
     * scans fastest. Each keeps a distinct glyph tied to its id rather than to the word in its caption.
     */
    @Test
    fun `every inline text command has a distinct glyph`() {
        val resolved = FlowIds.TEXT_DOCK_ORDER.associateWith { flowSemanticAction(it) }

        assertEquals(
            "inline marks with no glyph of their own",
            emptySet<String>(),
            resolved.filterValues { it == SemanticActionId.More }.keys,
        )
        assertEquals(
            "two inline marks share one glyph",
            FlowIds.TEXT_DOCK_ORDER.size,
            resolved.values.toSet().size,
        )
        assertEquals(SemanticActionId.Bold, resolved[FlowIds.BOLD])
        assertEquals(SemanticActionId.Italic, resolved[FlowIds.ITALIC])
        assertEquals(SemanticActionId.Strikethrough, resolved[FlowIds.STRIKETHROUGH])
        assertEquals(SemanticActionId.Code, resolved[FlowIds.INLINE_CODE])
    }

    // ── Command families ─────────────────────────────────────────────────────────────────────────────

    /**
     * Insert and convert are open families — a block kind added tomorrow produces ids nobody listed here — so
     * they resolve by prefix. That is what keeps the resolver total without a per-kind table to forget.
     */
    @Test
    fun `insert and convert families resolve for every block kind`() {
        FlowBlockKind.entries.forEach { kind ->
            assertEquals(
                "insert glyph for $kind",
                SemanticActionId.Insert,
                flowSemanticAction(FlowIds.insert(kind)),
            )
            assertEquals(
                "convert glyph for $kind",
                SemanticActionId.Paragraph,
                flowSemanticAction(FlowIds.convert(kind)),
            )
        }
    }

    /**
     * Picking an image is an insertion by id, but not by meaning: it opens a file picker. It is listed
     * explicitly *before* the prefix fallback, and this asserts the ordering still holds — a prefix rule that
     * shadowed the specific case would quietly turn it back into a generic plus.
     */
    @Test
    fun `image insertion outranks the insert prefix`() {
        assertEquals(SemanticActionId.Image, flowSemanticAction(FlowIds.INSERT_IMAGE))
    }

    /** Table structure hooks arrive through the same dock and are a family of their own. */
    @Test
    fun `table hooks resolve to the table glyph`() {
        assertEquals(SemanticActionId.Table, flowSemanticAction(FlowIds.TABLE_PREFIX + "insertRow"))
    }

    // ── Cross-host command sets ──────────────────────────────────────────────────────────────────────

    /** The relationship set reaches the dock through `dockCommands()`, so it needs glyphs like any other. */
    @Test
    fun `every contextual relationship command has a glyph`() {
        val fellBack = RelationshipCommandIds.contextual
            .filter { flowSemanticAction(it) == SemanticActionId.More }

        assertEquals("relationship commands with no glyph", emptyList<String>(), fellBack)
    }

    /** The two quick-tools group by *target*, so every colour id shares one glyph and every type id the other. */
    @Test
    fun `style commands group by their quick tool`() {
        listOf(
            EditorStyleCommandIds.TEXT_COLOR,
            EditorStyleCommandIds.HIGHLIGHT,
            EditorStyleCommandIds.FILL,
            EditorStyleCommandIds.STROKE,
            EditorStyleCommandIds.PAGE_BACKGROUND,
        ).forEach { assertEquals(it, SemanticActionId.Color, flowSemanticAction(it)) }

        listOf(
            EditorStyleCommandIds.FONT_BROWSER,
            EditorStyleCommandIds.NAMED_STYLES,
            EditorStyleCommandIds.CLEAR_FORMATTING,
        ).forEach { assertEquals(it, SemanticActionId.Style, flowSemanticAction(it)) }
    }

    // ── Totality ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * The resolver never guesses. An id it does not know draws the neutral overflow glyph, which is honest
     * about being unrecognised, rather than the old generic text-fields icon that impersonated a real control.
     */
    @Test
    fun `an unknown command falls back to the neutral glyph`() {
        assertEquals(SemanticActionId.More, flowSemanticAction("flow.not.a.real.command"))
        assertEquals(SemanticActionId.More, flowSemanticAction(""))
    }
}
