package com.norfold.app.experiments.c2

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.Selection
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
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
 * FLOW-03 Stage-1 C2 spike: EditText / Spannable.
 *
 * The whole document lives in **one** native `Editable`, so there is one native text selection,
 * one `InputConnection` and one composing region. Atomic objects are represented by a single
 * object-replacement character (`U+FFFC`) carrying a drawing-only [AtomSpan]. The spike asks
 * whether that native selection genuinely spans text → atom → text and whether native handles /
 * clipboard behave, versus C4a's per-block regions.
 *
 * Debug-only, non-product. Launch:
 * ```
 * adb shell am start -n com.norfold.app/com.norfold.app.experiments.c2.C2SpannableActivity
 * ```
 */
class C2SpannableActivity : FragmentActivity() {

    private data class Seg(val id: String, val start: Int, val end: Int, val atom: Boolean)

    private lateinit var editor: EditText
    private lateinit var status: TextView
    private lateinit var text: SpannableStringBuilder
    private lateinit var segs: List<Seg>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRoot())
        rebuild()
    }

    private fun buildRoot(): android.view.View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { setPadding(16, 16, 16, 4) }
        bar.addView(button("sel p2→p3") { scriptSelect() })
        bar.addView(button("copy") { scriptCopy() })
        bar.addView(button("cut") { scriptCut() })
        bar.addView(button("roundtrip") { scriptRoundTrip() })
        bar.addView(button("marks") { scriptMarks() })
        bar.addView(button("reset") { rebuild() })
        root.addView(bar)

        status = TextView(this).apply { setPadding(16, 4, 16, 12); textSize = 12f }
        root.addView(status)

        editor = EditText(this).apply {
            setText(text, TextView.BufferType.EDITABLE)
            textSize = 18f
            gravity = Gravity.TOP or Gravity.START
            setPadding(24, 24, 24, 200)
            isSingleLine = false
            setTextIsSelectable(true)
        }
        root.addView(editor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    // ---- one Editable, built from the frozen fixture ----------------------

    private val blocks: List<DocumentBlock> = buildDocument()

    private fun rebuild() {
        val sb = SpannableStringBuilder()
        val built = ArrayList<Seg>()
        blocks.forEachIndexed { i, block ->
            if (i > 0) sb.append('\n')
            when (block) {
                is ParagraphBlock -> {
                    val start = sb.length
                    appendInline(sb, block.content)
                    built.add(Seg(block.id, start, sb.length, atom = false))
                }
                else -> {
                    val start = sb.length
                    sb.append('\uFFFC')
                    sb.setSpan(AtomSpan(atomLabel(block)), start, start + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    built.add(Seg(block.id, start, start + 1, atom = true))
                }
            }
        }
        segs = built
        text = sb
        if (::editor.isInitialized) {
            editor.setText(sb, TextView.BufferType.EDITABLE)
        }
        status("C2 single-Editable spike: ${blocks.size} blocks, ${built.count { it.atom }} atom placeholders (U+FFFC). Native selection spans everything.")
    }

    private fun appendInline(sb: SpannableStringBuilder, nodes: List<InlineNode>) {
        for (node in nodes) when (node) {
            is InlineText -> sb.append(node.value)
            is BoldInline -> { val s = sb.length; appendInline(sb, node.children); sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
            is LinkInline -> { val s = sb.length; appendInline(sb, node.children); sb.setSpan(UnderlineSpan(), s, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE); sb.setSpan(ForegroundColorSpan(Color.parseColor("#1A73E8")), s, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
            is TagInline -> { val s = sb.length; sb.append("#").append(node.value); sb.setSpan(ForegroundColorSpan(Color.parseColor("#00796B")), s, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
            is MentionInline -> { val s = sb.length; sb.append("@").append(node.value); sb.setSpan(ForegroundColorSpan(Color.parseColor("#6F36FF")), s, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
            else -> sb.append(node.plainText())
        }
    }

    private fun atomLabel(block: DocumentBlock): String = when (block) {
        is ImageBlock -> "Image: ${block.caption.ifBlank { block.id }}"
        is TableBlock -> "Table ${block.id} ${block.headers.size}×${block.rows.size}"
        is ChartBlock -> "Chart ${block.id}"
        else -> "Object ${block.id}"
    }

    private fun seg(id: String) = segs.firstOrNull { it.id == id }
    private fun offsetOf(id: String, inner: Int): Int = (seg(id)?.start ?: 0) + inner

    // ---- scripted rows -----------------------------------------------------

    private fun scriptSelect() {
        val s = offsetOf("p2", 5)
        val e = offsetOf("p3", 5)
        editor.requestFocus()
        Selection.setSelection(text, s, e)
        val selected = text.subSequence(Selection.getSelectionStart(text), Selection.getSelectionEnd(text))
        status("B0.2 native selection p2@5→p3@5: sel=[${Selection.getSelectionStart(text)},${Selection.getSelectionEnd(text)}] " +
            "len=${selected.length} atomsInRange=${selected.count { it == '\uFFFC' }}")
    }

    private fun scriptCopy() {
        val s = Selection.getSelectionStart(text)
        val e = Selection.getSelectionEnd(text)
        if (s < 0 || e <= s) { status("Copy: no native selection"); return }
        val selected = text.subSequence(s, e).toString()
        status("B0.6 native Copy clipboard=${selected.replace("\n", "\\n").replace("\uFFFC", "⟦ATOM⟧")}")
    }

    private fun scriptCut() {
        val s = Selection.getSelectionStart(text)
        val e = Selection.getSelectionEnd(text)
        if (s < 0 || e <= s) { status("Cut: no native selection"); return }
        val removedAtoms = text.subSequence(s, e).count { it == '\uFFFC' }
        text.delete(s, e)
        status("Native Cut deleted [${e - s}] chars incl $removedAtoms atom(s); one native edit; " +
            "canonical re-projection now needs rebuild (spike).")
    }

    private fun scriptRoundTrip() {
        val sb = StringBuilder()
        for (sg in segs) {
            val current = text.subSequence(sg.start.coerceAtMost(text.length), sg.end.coerceAtMost(text.length)).toString()
            sb.append(sg.id).append(if (sg.atom) "[atom]" else "").append("=").append(current.replace("\n", "\\n")).append(" | ")
        }
        status("Projection: $sb")
    }

    private fun scriptMarks() {
        val bold = text.getSpans(0, text.length, StyleSpan::class.java).size
        val under = text.getSpans(0, text.length, UnderlineSpan::class.java).size
        status("Rich inline spans preserved: bold=$bold underline/link=$under (native Spannable)")
    }

    private fun status(message: String) {
        status.text = message
        android.util.Log.i("C2Spannable", message)
    }

    /** Drawing-only atom span. The atom is a single U+FFFC character semantically, not pixels. */
    private class AtomSpan(private val label: String) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
            (paint.measureText(label) + 40f).toInt()

        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val w = paint.measureText(label) + 32f
            val bg = Paint().apply { color = Color.parseColor("#EEEAFE"); isAntiAlias = true }
            val stroke = Paint().apply { color = Color.parseColor("#6F36FF"); style = Paint.Style.STROKE; strokeWidth = 3f; isAntiAlias = true }
            val h = (bottom - top).toFloat()
            canvas.drawRoundRect(x, top.toFloat(), x + w, top + h, 16f, 16f, bg)
            canvas.drawRoundRect(x, top.toFloat(), x + w, top + h, 16f, 16f, stroke)
            val old = paint.color
            paint.color = Color.parseColor("#4A2FB0")
            canvas.drawText(label, x + 16f, y.toFloat(), paint)
            paint.color = old
        }
    }

    // ---- frozen fixture (same stable IDs as doc-flow-stage1-v1) -----------

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
        ChartBlock(id = "chart1", vegaLiteSpec = "{}", chartDocument = "{\"schemaVersion\":1,\"document\":{\"id\":\"chart1\",\"type\":\"BAR\"}}"),
        ParagraphBlock(
            id = "p5",
            content = listOf(InlineText("See "), TagInline("stage1"), InlineText(" and "), MentionInline("qa"), InlineText(" for context.")),
        ),
        ParagraphBlock(id = "p6", content = listOf(InlineText("Final paragraph."))),
    )
}
