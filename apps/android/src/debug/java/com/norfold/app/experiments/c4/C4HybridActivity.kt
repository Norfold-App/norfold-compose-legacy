package com.norfold.app.experiments.c4

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.InputType
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.norfold.app.domain.BoldInline
import com.norfold.app.domain.ChartBlock
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.ImageBlock
import com.norfold.app.domain.InlineNode
import com.norfold.app.domain.InlineText
import com.norfold.app.domain.LinkInline
import com.norfold.app.domain.MentionInline
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.TableCell
import com.norfold.app.domain.TagInline
import com.norfold.app.domain.plainText

/**
 * FLOW-03 Stage-1 C4 spike: "single-active-native-editor hybrid".
 *
 * Reuses the shipped `com.norfold.app.domain` document model and layers per-block native
 * `EditText` regions on top, then measures how much Norfold-owned machinery is required to
 * present **one logical document caret/selection model across text and atomic objects**
 * (FLOW-02B B0.2). This is a debug-only, non-product spike; it exists to expose the cost of the
 * document-global layer, not to become the editor.
 *
 * Launch:
 * ```
 * adb shell am start -n com.norfold.app/com.norfold.app.experiments.c4.C4HybridActivity
 * ```
 */
class C4HybridActivity : FragmentActivity() {

    private val blocks: List<DocumentBlock> = buildDocument()
    private val textEditors = LinkedHashMap<Int, EditText>()
    private val atomViews = LinkedHashMap<Int, View>()
    private val atomLabels = LinkedHashMap<Int, String>()
    private val baseText = HashMap<Int, String>()

    private var anchor: Pos? = null
    private var head: Pos? = null
    private var selectedAtom: Int? = null

    private lateinit var status: TextView
    private var atomLine = 0

    data class Pos(val block: Int, val offset: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRoot())
        applyCaret(C4HybridActivity::class.java)
        status("C4 hybrid spike. Document: ${blocks.size} blocks. Use hardware Shift+arrow; buttons run scripted B0.2 rows.")
    }

    // ---- view construction -------------------------------------------------

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 4)
        }
        bar.addView(button("sel p2→p3") { scriptSelectCrossImage() })
        bar.addView(button("copy") { scriptCopy() })
        bar.addView(button("cut") { scriptCut() })
        bar.addView(button("reset") { resetSelection() })
        root.addView(bar)

        status = TextView(this).apply {
            setPadding(16, 4, 16, 12)
            textSize = 12f
        }
        root.addView(status)

        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 120)
        }
        blocks.forEachIndexed { index, block -> column.addView(viewFor(index, block)) }
        scroll.addView(column)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun viewFor(index: Int, block: DocumentBlock): View = when (block) {
        is ParagraphBlock -> makeTextEditor(index, block.plainText())
        else -> makeAtomView(index, block)
    }

    private fun makeTextEditor(index: Int, text: String): EditText {
        baseText[index] = text
        val editor = EditText(this).apply {
            setText(text)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            gravity = Gravity.TOP or Gravity.START
            background = null
            textSize = 18f
            setPadding(0, 24, 0, 24)
            tag = blocks[index].id
            setOnKeyListener { v, keyCode, event -> onEditorKey(v as EditText, keyCode, event) }
        }
        textEditors[index] = editor
        return editor
    }

    private fun makeAtomView(index: Int, block: DocumentBlock): View {
        val label = when (block) {
            is ImageBlock -> "Image: ${block.caption.ifBlank { block.id }}"
            is TableBlock -> "Table ${block.id}: ${block.headers.size}×${block.rows.size}"
            is ChartBlock -> "Chart ${block.id}"
            else -> "Object: ${block.id}"
        }
        atomLabels[index] = label
        val frame = FrameLayout(this).apply {
            setPadding(0, 24, 0, 24)
            contentDescription = label
        }
        val inner = TextView(this).apply {
            text = label
            textSize = 16f
            gravity = Gravity.CENTER
            contentDescription = label
            setPadding(32, 48, 32, 48)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EEEAFE"))
                setStroke(2, Color.parseColor("#6F36FF"))
                cornerRadius = 16f
            }
        }
        frame.addView(
            inner,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        atomViews[index] = frame
        return frame
    }

    // ---- document position model ------------------------------------------

    private fun cmp(a: Pos, b: Pos): Int =
        if (a.block != b.block) a.block.compareTo(b.block) else a.offset.compareTo(b.offset)

    private fun ordered(): Pair<Pos, Pos>? {
        val a = anchor ?: return null
        val h = head ?: return null
        return if (cmp(a, h) <= 0) a to h else h to a
    }

    private fun nextTextIndex(from: Int): Int? = (from + 1 until blocks.size).firstOrNull { textEditors.containsKey(it) }
    private fun prevTextIndex(from: Int): Int? = (from - 1 downTo 0).firstOrNull { textEditors.containsKey(it) }

    private fun caretPos(editor: EditText, blockIndex: Int) = Pos(blockIndex, editor.selectionStart.coerceAtLeast(0))

    private fun atStart(editor: EditText) = editor.selectionStart <= 0 && editor.selectionEnd <= 0
    private fun atEnd(editor: EditText) = editor.selectionStart >= editor.text.length && editor.selectionEnd >= editor.text.length

    private fun indexOfEditor(editor: EditText): Int? =
        textEditors.entries.firstOrNull { it.value === editor }?.key

    // ---- key handling (the cross-region machinery) -------------------------

    private fun onEditorKey(editor: EditText, keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null || event.action != KeyEvent.ACTION_DOWN) return false
        val index = indexOfEditor(editor) ?: return false
        val shift = event.isShiftPressed
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (shift) scriptedExtend(index, 1) else moveCaret(index, 1)
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (shift) scriptedExtend(index, -1) else moveCaret(index, -1)
                true
            }
            KeyEvent.KEYCODE_DEL -> {
                if (atStart(editor)) { backspaceAtBoundary(index); true } else false
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                if (atEnd(editor)) { deleteAtBoundary(index); true } else false
            }
            else -> false
        }
    }

    /** Plain Down/Up: cross text-block boundaries, skipping an atom as one logical step. */
    private fun moveCaret(index: Int, dir: Int) {
        clearSelectionHighlight()
        when (dir) {
            1 -> {
                val next = nextTextIndex(index)
                if (next != null) { focusCaret(next, 0); status("Down -> ${blocks[next].id}@0 (crossed ${crossedSummary(index, next)})") }
                else status("Down at document end")
            }
            else -> {
                val prev = prevTextIndex(index)
                if (prev != null) { focusCaret(prev, baseText[prev]?.length ?: 0); status("Up -> ${blocks[prev].id}@end") }
                else status("Up at document start")
            }
        }
    }

    private fun crossedSummary(from: Int, to: Int): String =
        (from + 1 until to).joinToString(",") { blocks[it].id }.ifEmpty { "no object" }

    private fun focusCaret(blockIndex: Int, offset: Int) {
        val editor = textEditors[blockIndex] ?: return
        editor.requestFocus()
        val max = editor.text.length
        editor.setSelection(offset.coerceIn(0, max))
        selectedAtom = null
    }

    /** Shift+Down/Up: extend one logical step in reading order, including atoms atomically. */
    private fun scriptedExtend(index: Int, dir: Int) {
        val editor = textEditors[index] ?: return
        if (anchor == null) anchor = caretPos(editor, index)
        val nextBlock = index + dir
        head = when {
            nextBlock < 0 -> Pos(index, 0)
            nextBlock >= blocks.size -> Pos(index, editor.text.length)
            textEditors.containsKey(nextBlock) -> Pos(nextBlock, if (dir > 0) 0 else (baseText[nextBlock]?.length ?: 0))
            else -> Pos(nextBlock, 0)
        }
        applySelectionHighlight()
        status("Shift ${if (dir > 0) "Down" else "Up"}: ${describeSelection()}")
    }

    private fun backspaceAtBoundary(index: Int) {
        val prev = index - 1
        if (prev < 0) { status("Backspace at document start (no-op)"); return }
        if (atomViews.containsKey(prev)) {
            if (selectedAtom == prev) { removeBlock(prev); status("Deleted selected atom ${blocks[prev].id}") }
            else { selectedAtom = prev; paintAtomSelection(); status("Select-before-delete: ${atomLabels[prev]} selected (press again to delete)") }
        } else {
            status("Backspace at ${blocks[index].id}@0: previous text block ${blocks[prev].id} (merge is out of spike scope)")
        }
    }

    private fun deleteAtBoundary(index: Int) {
        val next = index + 1
        if (next >= blocks.size) { status("Delete at document end (no-op)"); return }
        if (atomViews.containsKey(next)) {
            if (selectedAtom == next) { removeBlock(next); status("Deleted selected atom ${blocks[next].id}") }
            else { selectedAtom = next; paintAtomSelection(); status("Select-before-delete: ${atomLabels[next]} selected (press again to delete)") }
        } else {
            status("Forward Delete at ${blocks[index].id}@end: next text block ${blocks[next].id}")
        }
    }

    // ---- selection painting and scripted B0.2 rows -------------------------

    private fun applySelectionHighlight() {
        val sel = ordered()
        for ((i, editor) in textEditors) {
            val base = baseText[i] ?: ""
            val ss = SpannableString(base)
            if (sel != null) {
                val (s, e) = sel
                val lo: Int
                val hi: Int
                when {
                    i == s.block && i == e.block -> { lo = s.offset; hi = e.offset }
                    i == s.block -> { lo = s.offset; hi = base.length }
                    i == e.block -> { lo = 0; hi = e.offset }
                    i > s.block && i < e.block -> { lo = 0; hi = base.length }
                    else -> { lo = 0; hi = 0 }
                }
                if (hi > lo) ss.setSpan(BackgroundColorSpan(HILITE), lo, hi, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            editor.setText(ss, TextView.BufferType.SPANNABLE)
            if (sel != null && sel.first.block == i) editor.setSelection(sel.first.offset.coerceAtMost(base.length))
        }
        paintAtomSelection(sel)
    }

    private fun paintAtomSelection(sel: Pair<Pos, Pos>? = ordered()) {
        for ((i, view) in atomViews) {
            val inRange = selectedAtom == i ||
                (sel != null && i > sel.first.block && i < sel.second.block) ||
                (sel != null && (i == sel.first.block || i == sel.second.block) && sel.first.block != sel.second.block)
            val stroke = if (inRange) Color.parseColor("#FF3B30") else Color.parseColor("#6F36FF")
            (((view as? ViewGroup)?.getChildAt(0))?.background as? GradientDrawable)?.setStroke(if (inRange) 6 else 2, stroke)
        }
    }

    private fun clearSelectionHighlight() {
        anchor = null
        head = null
        selectedAtom = null
        for ((i, editor) in textEditors) editor.setText(baseText[i] ?: "", TextView.BufferType.SPANNABLE)
        paintAtomSelection(null)
    }

    private fun resetSelection() { clearSelectionHighlight(); status("Selection cleared") }

    private fun scriptSelectCrossImage() {
        val p2 = blocks.indexOfFirst { it.id == "p2" }
        val p3 = blocks.indexOfFirst { it.id == "p3" }
        anchor = Pos(p2, 5)
        head = Pos(p3, 5)
        applySelectionHighlight()
        status("B0.2 scripted: p2@5 -> p3@5; ${describeSelection()}")
    }

    private fun describeSelection(): String {
        val sel = ordered() ?: return "no selection"
        val atoms = (sel.first.block + 1 until sel.second.block).filter { atomViews.containsKey(it) }
        return "range ${blocks[sel.first.block].id}@${sel.first.offset} → ${blocks[sel.second.block].id}@${sel.second.offset}" +
            ", atoms included whole: " + (atoms.joinToString(",") { blocks[it].id }.ifEmpty { "none" })
    }

    private fun selectedText(): String {
        val sel = ordered() ?: return ""
        val sb = StringBuilder()
        for (i in sel.first.block..sel.second.block) {
            when {
                textEditors.containsKey(i) -> {
                    val base = baseText[i] ?: ""
                    val lo = if (i == sel.first.block) sel.first.offset.coerceIn(0, base.length) else 0
                    val hi = if (i == sel.second.block) sel.second.offset.coerceIn(0, base.length) else base.length
                    if (hi > lo) sb.append(base.substring(lo, hi))
                    if (i < sel.second.block) sb.append('\n')
                }
                atomViews.containsKey(i) -> {
                    sb.append("⟦").append(blocks[i].id).append(": ").append(atomLabels[i] ?: "object").append("⟧")
                    if (i < sel.second.block) sb.append('\n')
                }
            }
        }
        return sb.toString()
    }

    private fun scriptCopy() {
        val text = selectedText()
        status("B0.6 Copy clipboard=${text.replace("\n", "\\n")}")
    }

    private fun scriptCut() {
        val sel = ordered() ?: run { status("Cut with no selection"); return }
        val before = describeSelection()
        // Spike-grade cut: clear the selection range, then remove atoms strictly inside it.
        for (i in sel.first.block..sel.second.block) {
            if (textEditors.containsKey(i)) {
                val base = baseText[i] ?: ""
                val lo = if (i == sel.first.block) sel.first.offset.coerceIn(0, base.length) else 0
                val hi = if (i == sel.second.block) sel.second.offset.coerceIn(0, base.length) else base.length
                val next = base.removeRange(lo, hi)
                baseText[i] = next
                textEditors[i]?.setText(next)
            }
        }
        val removeAtoms = ((sel.first.block + 1)..(sel.second.block - 1)).filter { atomViews.containsKey(it) }.sortedDescending()
        removeAtoms.forEach { removeBlock(it) }
        anchor = Pos(sel.first.block, sel.first.offset)
        head = Pos(sel.first.block, sel.first.offset)
        status("Cut ($before) -> text spans trimmed; ${removeAtoms.size} atom(s) removed. (No block merge in spike.)")
    }

    private fun removeBlock(index: Int) {
        val view = atomViews.remove(index) ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        if (selectedAtom == index) selectedAtom = null
    }

    private fun status(message: String) {
        status.text = message
        android.util.Log.i("C4Hybrid", message)
    }

    private fun applyCaret(clazz: Class<*>) { title = "C4 Hybrid (${clazz.simpleName})" }

    // ---- fixture document (same stable IDs as doc-flow-stage1-v1) ----------

    private fun buildDocument(): List<DocumentBlock> = listOf(
        ParagraphBlock(
            id = "p1",
            content = listOf(
                InlineText("Alpha paragraph with "),
                BoldInline(listOf(InlineText("bold"))),
                InlineText(" and "),
                LinkInline("https://example.com/flow-stage1", listOf(InlineText("link"))),
                InlineText("."),
            ),
        ),
        ParagraphBlock(id = "p2", content = listOf(InlineText("Text before image."))),
        ImageBlock(id = "img1", source = "asset:fixture-img1", caption = "Stage-1 fixture image"),
        ParagraphBlock(id = "p3", content = listOf(InlineText("Text after image."))),
        TableBlock(
            id = "tbl1",
            headers = listOf(TableCell(listOf(InlineText("H1"))), TableCell(listOf(InlineText("H2")))),
            rows = listOf(
                listOf(TableCell(listOf(InlineText("A1"))), TableCell(listOf(InlineText("B1")))),
                listOf(TableCell(listOf(InlineText("A2"))), TableCell(listOf(InlineText("B2")))),
            ),
            columnWidthsDp = listOf(160f, 160f),
        ),
        ParagraphBlock(id = "p4", content = listOf(InlineText("Paragraph after table."))),
        ChartBlock(id = "chart1", vegaLiteSpec = "{}", chartDocument = CHART_JSON),
        ParagraphBlock(
            id = "p5",
            content = listOf(
                InlineText("See "),
                TagInline("stage1"),
                InlineText(" and "),
                MentionInline("qa"),
                InlineText(" for context."),
            ),
        ),
        ParagraphBlock(id = "p6", content = listOf(InlineText("Final paragraph."))),
    )

    private companion object {
        const val HILITE = 0x666F36FF
        const val CHART_JSON =
            "{\"schemaVersion\":1,\"document\":{\"id\":\"chart1\",\"schemaVersion\":1,\"type\":\"BAR\",\"title\":\"Stage-1 chart\"}}"
    }
}
