package com.norfold.app.editor.certification

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.SceneGeometry
import com.norfold.app.domain.SceneLock
import com.norfold.app.domain.SceneObject
import com.norfold.app.domain.SceneObjectKind
import com.norfold.app.domain.style.ColorPaint
import com.norfold.app.domain.style.ColorTarget
import com.norfold.app.domain.style.ColorValue
import com.norfold.app.domain.style.Gradient
import com.norfold.app.domain.style.GradientKind
import com.norfold.app.domain.style.FontAvailability
import com.norfold.app.domain.style.FontCategory
import com.norfold.app.domain.style.FontFamilyRecord
import com.norfold.app.domain.style.FontPortability
import com.norfold.app.domain.style.FontScript
import com.norfold.app.domain.style.FontSource
import com.norfold.app.editor.calculation.CalculationEngine
import com.norfold.app.editor.calculation.CalculationSheet
import com.norfold.app.editor.calculation.CalculationWorkbook
import com.norfold.app.editor.calculation.CellInput
import com.norfold.app.editor.calculation.GridAddress
import com.norfold.app.editor.calculation.TableSelectionController
import com.norfold.app.editor.command.CommandSurface
import com.norfold.app.editor.document.DocumentCommands
import com.norfold.app.editor.document.DocumentHostRuntime
import com.norfold.app.editor.document.DocumentSceneController
import com.norfold.app.editor.history.EditTarget
import com.norfold.app.editor.presentation.FormFactor
import com.norfold.app.editor.style.ColorStudioEngine
import com.norfold.app.editor.style.ColorStudioHost
import com.norfold.app.editor.style.ColorTargets
import com.norfold.app.editor.style.FontBrowserEngine
import com.norfold.app.domain.style.FontRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the editor's accessibility claims are true, rather than whether the code looks like they are.
 *
 * The reference baseline asks for names, roles, states, keyboard traversal and — the clause that is easiest
 * to fail silently — an **exact alternative to every drag**. A drag-only control is not a partly-accessible
 * control; it is one a screen-reader user can find, hear described, and then not operate. So the assertions
 * here are deliberately about reachability and equivalence: for each pointer-only manipulation, a non-pointer
 * route exists, it is a *registered command* rather than a parallel implementation, it produces the same
 * model change as the drag, and it disappears when the object forbids that manipulation.
 *
 * Everything asserted is a pure function. Nothing here renders Compose, so nothing here can pass because a
 * preview happened to look right — and equally, nothing here can stand in for the TalkBack pass on a real
 * device, which remains a separate gate.
 */
class AccessibilityContractTest {

    // ── Fixtures ────────────────────────────────────────────────────────────────────────────────────

    private fun obj(
        id: String,
        kind: SceneObjectKind = SceneObjectKind.Shape,
        x: Float = 0f,
        y: Float = 0f,
        width: Float = 100f,
        height: Float = 50f,
        lock: SceneLock = SceneLock.None,
    ) = SceneObject(
        id = id,
        kind = kind,
        geometry = SceneGeometry(x, y, width, height),
        lock = lock,
        content = ParagraphBlock(id = "c-$id", content = listOf(InlineText(id))),
    )

    private fun runtime(vararg objects: SceneObject): DocumentHostRuntime = DocumentHostRuntime(
        controller = DocumentSceneController(
            EditTarget("note:1"),
            BlockDocument(listOf(DocumentPageBlock(id = "p1", objects = objects.toList()).normalized())),
        ),
        initialFormFactor = FormFactor(400, 800),
    )

    private fun DocumentHostRuntime.actionIds(objectId: String): List<String> =
        accessibilityActions(controller.findObject(objectId)!!.second).map { it.first }

    private fun DocumentHostRuntime.geometryOf(objectId: String): SceneGeometry =
        controller.findObject(objectId)!!.second.geometry

    // ── Every announced drag has a non-pointer equivalent ───────────────────────────────────────────

    /**
     * The resize handles announce themselves ("Resize bottom right"), so a screen-reader user can reach them.
     * Before this contract existed they could be reached and not operated: no registered command claimed
     * [com.norfold.app.editor.command.Capability.RESIZE], so the handle was a described control with nothing
     * behind it.
     */
    @Test
    fun anObjectThatCanBeResizedByDragOffersResizeWithoutAPointer() {
        val rt = runtime(obj("a"))
        val actions = rt.actionIds("a")

        assertTrue(
            "an announced resize handle needs a non-pointer route: $actions",
            actions.any { it.contains("Width", ignoreCase = true) || it.contains("Height", ignoreCase = true) },
        )
        // Asserted against the *raw* producer rather than the runtime's filtered list, because the runtime
        // drops unbacked ids silently — so checking the filtered list would prove only that filtering works.
        DocumentCommands.accessibilityActionsFor(rt.controller.findObject("a")!!.second).forEach { id ->
            assertNotNull(
                "the action list offers '$id', which no registered command backs, so it is silently dropped",
                rt.commands.get(id),
            )
        }
    }

    @Test
    fun anObjectThatCanBeRotatedByDragOffersRotationWithoutAPointer() {
        val rt = runtime(obj("a"))

        assertTrue(
            "an announced rotation handle needs a non-pointer route",
            rt.actionIds("a").any { it.contains("rotate", ignoreCase = true) },
        )
    }

    /**
     * The point of routing the action through the controller rather than through a second code path: the
     * geometry after "resized without a pointer" is the geometry a drag of the same magnitude would leave.
     */
    @Test
    fun theNonPointerResizeChangesTheSameGeometryTheDragWould() {
        val rt = runtime(obj("a", width = 100f, height = 50f))
        rt.selectObjects(listOf("a"))

        rt.invoke("document.object.growWidth", CommandSurface.ACCESSIBILITY)

        val after = rt.geometryOf("a")
        assertEquals("width grows by one step", 110f, after.width, 0.001f)
        assertEquals("and height is untouched", 50f, after.height, 0.001f)
        assertEquals("and the object does not move", 0f, after.x, 0.001f)
    }

    @Test
    fun theNonPointerRotationChangesTheSameAngleTheDragWould() {
        val rt = runtime(obj("a"))
        rt.selectObjects(listOf("a"))

        rt.invoke("document.object.rotateClockwise", CommandSurface.ACCESSIBILITY)

        assertEquals(15f, rt.geometryOf("a").rotation, 0.001f)
    }

    /**
     * A keyboard resize is one History step, exactly as the drag is. If it were one step per invocation of an
     * internal helper, undoing a resize a screen-reader user just performed would take several Undos to reach
     * the state a pointer user reaches in one.
     */
    @Test
    fun aNonPointerResizeIsOneUndoStepJustAsADragIs() {
        val rt = runtime(obj("a"))
        rt.selectObjects(listOf("a"))
        val before = rt.controller.undoDepth()

        rt.invoke("document.object.growHeight", CommandSurface.ACCESSIBILITY)

        assertEquals("one invocation, one undo step", before + 1, rt.controller.undoDepth())
    }

    @Test
    fun rotatingPastAFullTurnDoesNotAccumulateAnUnboundedAngle() {
        val rt = runtime(obj("a"))
        rt.selectObjects(listOf("a"))

        repeat(25) { rt.invoke("document.object.rotateClockwise", CommandSurface.ACCESSIBILITY) }

        val rotation = rt.geometryOf("a").rotation
        assertTrue("rotation stays inside [0, 360): was $rotation", rotation >= 0f && rotation < 360f)
        assertEquals("25 × 15° = 375°, which is 15°", 15f, rotation, 0.001f)
    }

    /**
     * Shrinking is the direction with a trap in it: an object driven to zero extent cannot be hit, so it
     * cannot be re-selected to undo the shrink by hand.
     */
    @Test
    fun repeatedShrinkingStopsAtAUsableSizeRatherThanCollapsingTheObject() {
        val rt = runtime(obj("a", width = 100f, height = 50f))
        rt.selectObjects(listOf("a"))

        repeat(40) { rt.invoke("document.object.shrinkWidth", CommandSurface.ACCESSIBILITY) }

        val after = rt.geometryOf("a")
        assertTrue("width stays positive and selectable: was ${after.width}", after.width > 0f)
    }

    // ── The alternative disappears exactly when the manipulation does ───────────────────────────────

    @Test
    fun aSizeLockedObjectOffersNoResizeActionEither() {
        val rt = runtime(obj("a", lock = SceneLock.Size))
        val actions = rt.actionIds("a")

        assertFalse(
            "a size lock must remove the non-pointer route too: $actions",
            actions.any { it.contains("Width", ignoreCase = true) || it.contains("Height", ignoreCase = true) },
        )
        assertTrue("but moving is still offered", actions.any { it.contains("move", ignoreCase = true) })
    }

    @Test
    fun aSizeLockedObjectOffersNoRotationEither() {
        val rt = runtime(obj("a", lock = SceneLock.Size))

        assertFalse(rt.actionIds("a").any { it.contains("rotate", ignoreCase = true) })
    }

    /**
     * A table is resizable but not rotatable — a kind restriction rather than a lock. Offering "Rotate
     * clockwise" on it would be an action that announces itself and then does nothing, which is worse than
     * not offering it: the user cannot tell the difference between "it did nothing" and "I did it wrong".
     */
    @Test
    fun aKindThatCannotRotateOffersResizeButNotRotation() {
        val rt = runtime(obj("t", kind = SceneObjectKind.Table))
        val actions = rt.actionIds("t")

        assertTrue("a table resizes", actions.any { it.contains("Width", ignoreCase = true) })
        assertFalse("a table does not rotate", actions.any { it.contains("rotate", ignoreCase = true) })
    }

    @Test
    fun aFullyLockedObjectOffersNoManipulationButStillOffersUnlock() {
        val rt = runtime(obj("a", lock = SceneLock.Full))
        val actions = rt.actionIds("a")

        assertFalse(
            "no manipulation survives a full lock: $actions",
            actions.any {
                it.contains("move", ignoreCase = true) ||
                    it.contains("Width", ignoreCase = true) ||
                    it.contains("Height", ignoreCase = true) ||
                    it.contains("rotate", ignoreCase = true)
            },
        )
        assertTrue("but the way out is offered", actions.contains("document.object.unlock"))
    }

    /**
     * The invariant behind all of the above, stated once so a future action added to the list cannot quietly
     * break it: an offered action is an action that actually runs.
     */
    @Test
    fun everyOfferedActionIsARegisteredCommandForEveryLockState() {
        SceneLock.entries.forEach { lock ->
            val rt = runtime(obj("a", lock = lock))
            val obj = rt.controller.findObject("a")!!.second
            val offered = DocumentCommands.accessibilityActionsFor(obj)
            val reachable = rt.actionIds("a")

            assertTrue("$lock leaves an object with no actions at all", reachable.isNotEmpty())
            offered.forEach { id ->
                assertNotNull("under $lock, offered action '$id' is not a registered command", rt.commands.get(id))
            }
            assertTrue(
                "under $lock these offered actions were dropped: ${offered - reachable.toSet()}",
                reachable.containsAll(offered),
            )
        }
    }

    // ── Names, states and reasons are text, not colour or position ──────────────────────────────────

    /**
     * §16.6's colour-independence clause, at the only place it can be checked without a device: the state a
     * row reports must be a word, so it survives a user who cannot see the tint that duplicates it.
     */
    @Test
    fun fontRowsReportAvailabilityAsWordsRatherThanAsAnIconAlone() {
        val record = FontFamilyRecord(
            id = "remote",
            name = "Remote Sans",
            source = FontSource.DOWNLOADABLE,
            category = FontCategory.SANS,
            packagedScripts = setOf(FontScript.LATIN),
            portability = FontPortability.DOWNLOADABLE_SHAREABLE,
        )
        val browser = FontBrowserEngine(FontRegistry(listOf(record)))

        // A downloadable family is the only one whose availability is not fixed at BUILT_IN/SYSTEM's
        // "guaranteed offline", so it is the only one whose state label can be observed changing.
        browser.setAvailability(record.identity, FontAvailability.DOWNLOADABLE)
        assertEquals("Download", browser.rowState(record).stateLabel())
        browser.setAvailability(record.identity, FontAvailability.DOWNLOADING)
        assertEquals("Downloading…", browser.rowState(record).stateLabel())

        val label = browser.rowState(record).accessibleLabel()
        assertTrue("the row name carries the state: '$label'", label.contains("Downloading…"))
        assertTrue("and identifies the family", label.contains("Remote Sans"))
    }

    @Test
    fun aSelectedFontRowSaysSoInItsNameRatherThanOnlyInItsTint() {
        val record = FontFamilyRecord(
            id = "sans",
            name = "Packaged Sans",
            source = FontSource.BUILT_IN,
            category = FontCategory.SANS,
            packagedScripts = setOf(FontScript.LATIN),
            portability = FontPortability.BUILT_IN,
        )
        val browser = FontBrowserEngine(FontRegistry(listOf(record)))
        val unselected = browser.rowState(record).accessibleLabel()

        browser.select(record.identity)
        val selected = browser.rowState(record).accessibleLabel()

        assertNotEquals("selection must change what is announced", unselected, selected)
        assertTrue("and say so in words: '$selected'", selected.contains("selected"))
    }

    // ── Colour is announced as a word, at the surface that is entirely about colour ─────────────────

    /** The studio needs a host to preview and commit through; nothing here inspects what it recorded. */
    private object SilentColorHost : ColorStudioHost {
        override fun preview(target: ColorTarget, paint: ColorPaint) = Unit
        override fun commit(target: ColorTarget, paint: ColorPaint, label: String) = true
    }

    /**
     * The Color Studio is the surface where §16.6's colour-independence clause is easiest to violate and
     * hardest to notice: a swatch *is* the value, so showing it feels like naming it. A user who cannot see
     * the swatch needs the family and the hex in words, and needs to know which property they are editing —
     * "red #DC2828" alone does not say whether it is about to become the fill or the stroke.
     */
    @Test
    fun theColorStudioNamesTheColourInWordsRatherThanShowingOnlyASwatch() {
        val engine = ColorStudioEngine(SilentColorHost)

        engine.open(ColorTargets.documentShapeFill(listOf("s1")), ColorPaint.Flat(ColorValue(0xDC, 0x28, 0x28)))
        val label = engine.state().accessibleLabel()

        assertTrue("the hue family is a word: '$label'", label.contains("red"))
        assertTrue("and the exact value is readable: '$label'", label.contains("#DC2828"))
        assertTrue("and it says which property is being edited: '$label'", label.contains("Fill"))
    }

    /**
     * With targets that disagree, showing any one of their colours would be a lie a sighted user can check
     * against the canvas and a screen-reader user cannot. The word has to be there.
     */
    @Test
    fun disagreeingTargetsAnnounceMixedRatherThanOneTargetsColour() {
        val engine = ColorStudioEngine(SilentColorHost)

        engine.open(
            ColorTargets.documentShapeFill(listOf("s1", "s2")),
            ColorPaint.Flat(ColorValue(0xDC, 0x28, 0x28)),
            mixed = true,
        )
        val label = engine.state().accessibleLabel()

        assertTrue("a mixed selection says so: '$label'", label.contains("Mixed"))
        assertFalse("and does not name one target's colour as if it were shared: '$label'", label.contains("#DC2828"))
        assertTrue("while still naming the property and its extent: '$label'", label.contains("Fill · 2 items"))
    }

    /**
     * A gradient has no single colour to name, so the swatch is the *only* signal of its shape. Announcing
     * just the midpoint colour would hide the direction and the stop count entirely.
     */
    @Test
    fun aGradientAnnouncesItsShapeRatherThanOneColourFromTheRamp() {
        val engine = ColorStudioEngine(SilentColorHost)

        engine.open(
            ColorTargets.documentShapeFill(listOf("s1")),
            ColorPaint.Ramp(Gradient(kind = GradientKind.LINEAR, angle = 45f)),
        )
        val label = engine.state().accessibleLabel()

        assertTrue("the kind is named: '$label'", label.contains("Linear gradient"))
        assertTrue("with how many stops it has: '$label'", label.contains("2 stops"))
        assertTrue("and its direction, which the swatch alone conveys: '$label'", label.contains("45°"))
    }

    // ── Spreadsheet selection is announced as extent, not as a tint ─────────────────────────────────

    /**
     * A range's tint is the only signal a sighted user gets. Without a textual description a screen-reader
     * user cannot learn that a block rather than a cell is selected — so the description must distinguish
     * them, and must name the extent rather than just repeating the anchor.
     */
    @Test
    fun aSelectedRangeIsAnnouncedAsARangeRatherThanAsItsAnchorCell() {
        val sheet = CalculationSheet(rowCount = 20, columnCount = 6)
        val engine = CalculationEngine(CalculationWorkbook(sheets = listOf(sheet), activeSheetId = sheet.id))
        val selection = TableSelectionController()

        selection.selectCell(GridAddress(0, 0))
        val single = selection.accessibilityDescription(sheet, engine)

        selection.extendTo(GridAddress(2, 3))
        val range = selection.accessibilityDescription(sheet, engine)

        assertNotEquals("a range must not announce identically to a cell", single, range)
        assertTrue("the range names both ends: '$range'", range.contains("A1") && range.contains("D3"))
        assertTrue("and its extent, so the tint is not the only size cue: '$range'", range.contains("3 rows by 4 columns"))
    }

    @Test
    fun aSingleRowRangeSaysRowRatherThanRows() {
        val sheet = CalculationSheet(rowCount = 20, columnCount = 6)
        val engine = CalculationEngine(CalculationWorkbook(sheets = listOf(sheet), activeSheetId = sheet.id))
        val selection = TableSelectionController()

        selection.selectCell(GridAddress(0, 0))
        selection.extendTo(GridAddress(0, 2))

        assertTrue(
            "a one-row range must not announce '1 rows'",
            selection.accessibilityDescription(sheet, engine).contains("1 row by 3 columns"),
        )
    }

    /**
     * The reason the description lives on the controller rather than in each host: an announcement is the one
     * output nothing else reads, so a host that formatted its own would diverge with no failing test and no
     * visible symptom — which is exactly what had happened. The host's delegation itself is a Compose
     * `stateDescription`, so it is verified by reading the host rather than here.
     */
    @Test
    fun everySelectionShapeAnnouncesSomethingRatherThanFallingThrough() {
        val sheet = CalculationSheet(rowCount = 10, columnCount = 4)
        val engine = CalculationEngine(CalculationWorkbook(sheets = listOf(sheet), activeSheetId = sheet.id))
        val selection = TableSelectionController()

        val announced = buildList {
            add(selection.accessibilityDescription(sheet, engine))
            selection.selectCell(GridAddress(1, 1)); add(selection.accessibilityDescription(sheet, engine))
            selection.edit(GridAddress(1, 1), "x"); add(selection.accessibilityDescription(sheet, engine))
            selection.selectCell(GridAddress(0, 0)); selection.extendTo(GridAddress(1, 1))
            add(selection.accessibilityDescription(sheet, engine))
            selection.selectRow(0); add(selection.accessibilityDescription(sheet, engine))
            selection.selectColumn(0); add(selection.accessibilityDescription(sheet, engine))
            selection.selectWholeTable(); add(selection.accessibilityDescription(sheet, engine))
        }

        announced.forEach { assertTrue("no selection may announce an empty string", it.isNotBlank()) }
        assertEquals("each selection shape announces distinctly", announced.size, announced.toSet().size)
    }

    @Test
    fun aSelectedCellAnnouncesItsValueAndABlankCellSaysItIsBlank() {
        val sheet = CalculationSheet(rowCount = 10, columnCount = 4)
        val engine = CalculationEngine(CalculationWorkbook(sheets = listOf(sheet), activeSheetId = sheet.id))
        engine.commitInput(sheet.id, 0, 0, CellInput.Number("42"))
        val selection = TableSelectionController()

        selection.selectCell(GridAddress(0, 0))
        assertTrue(
            "a filled cell announces what is in it",
            selection.accessibilityDescription(sheet, engine).contains("42"),
        )

        selection.selectCell(GridAddress(5, 2))
        assertTrue(
            "an empty cell says so rather than trailing off",
            selection.accessibilityDescription(sheet, engine).contains("blank"),
        )
    }

    @Test
    fun rowColumnAndWholeSheetSelectionsEachAnnounceDistinctly() {
        val sheet = CalculationSheet(rowCount = 10, columnCount = 4)
        val engine = CalculationEngine(CalculationWorkbook(sheets = listOf(sheet), activeSheetId = sheet.id))
        val selection = TableSelectionController()

        selection.selectRow(2)
        val row = selection.accessibilityDescription(sheet, engine)
        selection.selectColumn(2)
        val column = selection.accessibilityDescription(sheet, engine)
        selection.selectWholeTable()
        val all = selection.accessibilityDescription(sheet, engine)

        assertEquals("rows are announced by their 1-based number", "Selected row 3", row)
        assertEquals("columns by their letter, not their index", "Selected column C", column)
        assertEquals("Selected entire table", all)
        assertEquals("all three are distinct", 3, setOf(row, column, all).size)
    }
}
