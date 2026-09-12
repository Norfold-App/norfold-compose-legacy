package com.norfold.app.experiments.c2

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.Base64
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.BlockDocumentJson
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

/**
 * FLOW-03 C2 Stage-2A. One native Editable holds the whole document; atomic
 * blocks are U+FFFC chars carrying a drawing [AtomSpan] that renders real image /
 * table / chart content and exposes a per-object content description. The object
 * "side map" is span-based (positions move with edits; no static offsets).
 * Projection rebuilds the real BlockDocument. Norfold history is canonical.
 */
class C2Stage2Activity : ComponentActivity() {

    private lateinit var edit: EditText
    private lateinit var status: TextView

    private val history = NorfoldHistory { doc, sel ->
        renderDocument(doc)
        edit.setSelection(
            sel.first.coerceIn(0, edit.text.length),
            sel.second.coerceIn(0, edit.text.length),
        )
    }
    private var internalClipboard: List<DocumentBlock>? = null
    private var pendingDeleteBlockId: String? = null
    private var suppressCommit = false

    private val evalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getStringExtra("op")) {
                "report" -> status.text = report()
                "project" -> status.text = projectSummary()
                "selectAcrossAtom" -> selectAcross("img1")
                "selectAtom" -> selectAtom("img1")
                "copy" -> { copyInternal(); status.text = "internal copy ok" }
                "paste" -> { pasteInternal(); status.text = "internal paste ok" }
                "androidCopy" -> { androidCopy(); status.text = "android copy ok" }
                "androidPaste" -> { androidPaste(); status.text = "android paste ok" }
                "undo" -> { history.undo(); status.text = "undo" }
                "redo" -> { history.redo(); status.text = "redo" }
                "del" -> status.text = "delete intent ignored (use key)"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        edit = EditText(this).apply {
            setBackgroundColor(Color.WHITE)
            setTextColor(Color.BLACK)
            textSize = 16f
            setPadding(24, 24, 24, 24)
            isSingleLine = false
        }
        status = TextView(this).apply {
            setBackgroundColor(Color.rgb(17, 17, 17))
            setTextColor(Color.rgb(0, 255, 0))
            textSize = 11f
            setPadding(16, 16, 16, 16)
        }

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun bar(label: String, op: String) {
            buttons.addView(Button(this).apply {
                text = label
                textSize = 11f
                setOnClickListener { evalReceiver.onReceive(this@C2Stage2Activity, Intent().putExtra("op", op)) }
            })
        }
        bar("Project", "project")
        bar("Report", "report")
        bar("Across", "selectAcrossAtom")
        bar("Atom", "selectAtom")
        bar("Copy", "copy")
        bar("Paste", "paste")
        bar("Undo", "undo")
        bar("Redo", "redo")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(buttons)
            addView(edit, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(status)
        }
        setContentView(root)

        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressCommit) return
                pendingDeleteBlockId = null
                history.commit(BlockDocumentJson.encode(project()), selectionOf())
            }
        })
        edit.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DEL) {
                handleBackspace()
            } else {
                false
            }
        }

        val filter = IntentFilter(ACTION_EVAL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(evalReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(evalReceiver, filter)
        }

        renderDocument(Fixture.document())
        history.reset(BlockDocumentJson.encode(Fixture.document()), selectionOf())
        Log.i(TAG, "mounted blocks=${Fixture.document().blocks.size}")
    }

    override fun onDestroy() {
        try { unregisterReceiver(evalReceiver) } catch (_: IllegalArgumentException) {}
        super.onDestroy()
    }

    // ---- flatten ----

    private fun renderDocument(document: BlockDocument) {
        suppressCommit = true
        val sb = SpannableStringBuilder()
        document.blocks.forEachIndexed { index, block ->
            val start = sb.length
            when (block) {
                is ImageBlock -> sb.append(ATOM).also { sb.setSpan(atomSpan(block), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
                is TableBlock -> sb.append(ATOM).also { sb.setSpan(atomSpan(block), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
                is ChartBlock -> sb.append(ATOM).also { sb.setSpan(atomSpan(block), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
                else -> {
                    appendInline(sb, block.contentOrEmpty())
                    sb.setSpan(BlockMarker(block.id, block::class.java.simpleName), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            if (index != document.blocks.lastIndex) sb.append("\n")
        }
        edit.setText(sb)
        suppressCommit = false
    }

    private fun appendInline(sb: SpannableStringBuilder, nodes: List<InlineNode>) {
        nodes.forEach { node ->
            val start = sb.length
            when (node) {
                is InlineText -> sb.append(node.value)
                is BoldInline -> { appendInline(sb, node.children); sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
                is LinkInline -> { appendInline(sb, node.children); sb.setSpan(UnderlineSpan(), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE); sb.setSpan(ForegroundColorSpan(Color.rgb(21, 101, 192)), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE); sb.setSpan(MarkSpan("link", node.url), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
                is TagInline -> { sb.append("#${node.value}"); sb.setSpan(ForegroundColorSpan(Color.rgb(106, 27, 154)), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE); sb.setSpan(BackgroundColorSpan(Color.argb(0x22, 0x6a, 0x1b, 0x9a)), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE); sb.setSpan(MarkSpan("tag", node.value), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
                is MentionInline -> { sb.append("@${node.value}"); sb.setSpan(ForegroundColorSpan(Color.rgb(0, 121, 107)), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE); sb.setSpan(BackgroundColorSpan(Color.argb(0x22, 0x00, 0x79, 0x6b)), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE); sb.setSpan(MarkSpan("mention", node.value), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
                else -> sb.append(node.plainText())
            }
        }
    }

    private fun atomSpan(block: DocumentBlock): AtomSpan {
        val name = when (block) {
            is ImageBlock -> block.caption.ifBlank { "Image" }
            is TableBlock -> "Table ${block.headers.size} by ${block.rows.size}"
            is ChartBlock -> "Chart"
            else -> "Object"
        }
        return AtomSpan(block, name)
    }

    // ---- projection ----

    private fun project(): BlockDocument {
        val ed = edit.text
        data class Marker(val marker: Any, val start: Int, val end: Int)
        val spans = ed.getSpans(0, ed.length, Any::class.java)
        val markers = spans.mapNotNull { span ->
            when (span) {
                is AtomSpan -> Marker(span, ed.getSpanStart(span), ed.getSpanEnd(span))
                is BlockMarker -> Marker(span, ed.getSpanStart(span), ed.getSpanEnd(span))
                else -> null
            }
        }.sortedBy { it.start }

        val blocks = markers.mapNotNull { m ->
            when (val marker = m.marker) {
                is AtomSpan -> marker.block
                is BlockMarker -> {
                    val text = ed.subSequence(m.start, m.end).toString()
                    ParagraphBlock(
                        id = marker.blockId,
                        content = inlineFrom(text, ed, m.start, m.end),
                    )
                }
                else -> null
            }
        }
        return BlockDocument(blocks.ifEmpty { listOf(ParagraphBlock()) }).normalized()
    }

    private fun inlineFrom(text: String, ed: Spanned, start: Int, end: Int): List<InlineNode> {
        val result = mutableListOf<InlineNode>()
        var cursor = 0
        val marks = ed.getSpans(start, end, MarkSpan::class.java)
            .map { Triple(it, ed.getSpanStart(it), ed.getSpanEnd(it)) }
            .sortedBy { it.second }
        for ((span, ms, me) in marks) {
            val relStart = (ms - start).coerceIn(0, text.length)
            val relEnd = (me - start).coerceIn(0, text.length)
            if (relStart > cursor) result.add(InlineText(text.substring(cursor, relStart)))
            val inner = text.substring(relStart, relEnd)
            result.add(
                when (span.kind) {
                    "link" -> LinkInline(span.value, listOf(InlineText(inner)))
                    "tag" -> TagInline(span.value)
                    "mention" -> MentionInline(span.value)
                    else -> InlineText(inner)
                },
            )
            cursor = maxOf(cursor, relEnd)
        }
        if (cursor < text.length) result.add(InlineText(text.substring(cursor)))
        return result.ifEmpty { listOf(InlineText(text)) }
    }

    // ---- select-before-delete (B12.3) ----

    private fun handleBackspace(): Boolean {
        val ed = edit.text
        val sel = selectionOf()
        if (sel.first != sel.second) {
            if (pendingDeleteBlockId != null) {
                val removed = pendingDeleteBlockId
                val start = sel.first
                suppressCommit = true
                ed.delete(start, sel.second)
                suppressCommit = false
                history.commit(BlockDocumentJson.encode(project()), selectionOf())
                pendingDeleteBlockId = null
                status.text = "atom deleted blockId=$removed"
                return true
            }
            return false
        }
        if (sel.first > 0) {
            val spans = ed.getSpans(sel.first - 1, sel.first, AtomSpan::class.java)
            if (spans.isNotEmpty()) {
                val span = spans[0]
                edit.setSelection(ed.getSpanStart(span), ed.getSpanEnd(span))
                pendingDeleteBlockId = (span.block as DocumentBlock).id
                status.text = "select-before-delete: atom selected (${pendingDeleteBlockId}); DEL again to remove"
                return true
            }
        }
        return false
    }

    // ---- clipboard ----

    private fun copyInternal() {
        val blocks = selectedBlocks()
        internalClipboard = blocks
        Log.i(TAG, "internal copy blocks=${blocks.size} ids=${blocks.map { it.id }}")
    }

    private fun pasteInternal() {
        val blocks = internalClipboard ?: return
        val ed = edit.text
        suppressCommit = true
        val sb = SpannableStringBuilder()
        blocks.forEachIndexed { i, b ->
            val start = sb.length
            when (b) {
                is ImageBlock, is TableBlock, is ChartBlock -> {
                    sb.append(ATOM)
                    sb.setSpan(atomSpan(b), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> {
                    appendInline(sb, b.contentOrEmpty())
                    sb.setSpan(BlockMarker(b.id, b::class.java.simpleName), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            if (i != blocks.lastIndex) sb.append("\n")
        }
        val at = selectionOf().first
        ed.insert(at, sb)
        suppressCommit = false
        history.commit(BlockDocumentJson.encode(project()), selectionOf())
        Log.i(TAG, "internal paste blocks=${blocks.size}")
    }

    private fun selectedBlocks(): List<DocumentBlock> {
        val ed = edit.text
        val sel = selectionOf()
        val spans = ed.getSpans(sel.first, sel.second, AtomSpan::class.java)
        if (spans.size == 1) return listOf(spans[0].block as DocumentBlock)
        val doc = project()
        return doc.blocks.filter { it.id in spanIdsIn(sel) }.ifEmpty { listOf(ParagraphBlock(content = listOf(InlineText(ed.subSequence(sel.first, sel.second).toString())))) }
    }

    private fun spanIdsIn(sel: Pair<Int, Int>): Set<String> {
        val ed = edit.text
        return ed.getSpans(sel.first, sel.second, BlockMarker::class.java)
            .map { it.blockId }.toSet()
    }

    private fun androidCopy() {
        val blocks = selectedBlocks()
        internalClipboard = blocks
        val payload = BlockDocumentJson.format.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(DocumentBlock.serializer()),
            blocks,
        )
        val plain = blocks.joinToString("\n") { it.plainText() }
        val clip = ClipData.newPlainText("Norfold", plain)
        clip.addItem(ClipData.Item(payload))
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(clip)
        Log.i(TAG, "android copy plain='${plain.replace("\n", "\\n")}' items=${clip.itemCount} payloadLen=${payload.length}")
        val readBack = cm.primaryClip
        Log.i(TAG, "android copy read-back items=${readBack?.itemCount} desc=${readBack?.description?.mimeTypeCount} mimes=${readBack?.description?.let { d -> (0 until d.mimeTypeCount).map { d.getMimeType(it) } }}")
    }

    private fun androidPaste() {
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = cm.primaryClip ?: return
        if (clip.itemCount == 0) return
        var payload: String? = null
        for (i in 0 until clip.itemCount) {
            val text = clip.getItemAt(i).coerceToText(this)?.toString() ?: continue
            Log.i(TAG, "android paste item[$i] len=${text.length} head='${text.take(24).replace("\n", "\\n")}'")
            if (text.trimStart().startsWith("[{")) { payload = text; break }
        }
        if (payload != null) {
            internalClipboard = decodeBlocks(payload)
            pasteInternal()
            Log.i(TAG, "android paste from Norfold payload blocks=${internalClipboard?.size}")
        } else {
            val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
            val at = selectionOf().first
            suppressCommit = true
            edit.text.insert(at, text)
            suppressCommit = false
            history.commit(BlockDocumentJson.encode(project()), selectionOf())
            Log.i(TAG, "android paste plain text len=${text.length}")
        }
    }

    private fun decodeBlocks(json: String): List<DocumentBlock> =
        runCatching {
            BlockDocumentJson.format.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(DocumentBlock.serializer()),
                json,
            )
        }.getOrDefault(emptyList())

    // ---- helpers ----

    private fun selectAcross(blockId: String) {
        val ed = edit.text
        val atom = ed.getSpans(0, ed.length, AtomSpan::class.java).firstOrNull { (it.block as DocumentBlock).id == blockId } ?: return
        val a = (ed.getSpanStart(atom) - 6).coerceAtLeast(0)
        val b = (ed.getSpanEnd(atom) + 6).coerceAtMost(ed.length)
        edit.requestFocus()
        edit.setSelection(a, b)
    }

    private fun selectAtom(blockId: String) {
        val ed = edit.text
        val atom = ed.getSpans(0, ed.length, AtomSpan::class.java).firstOrNull { (it.block as DocumentBlock).id == blockId } ?: return
        edit.requestFocus()
        edit.setSelection(ed.getSpanStart(atom), ed.getSpanEnd(atom))
    }

    private fun selectionOf() = edit.selectionStart to edit.selectionEnd

    private fun report(): String {
        val doc = project()
        val json = BlockDocumentJson.encode(doc)
        val ed = edit.text
        val sel = selectionOf()
        val atoms = ed.getSpans(0, ed.length, AtomSpan::class.java).map { (it.block as DocumentBlock).id }
        return "selection=$sel\ntextLen=${ed.length}\natoms=$atoms\nblocks=${doc.blocks.size} kinds=${doc.blocks.map { it::class.java.simpleName }}\njson=${json.take(240)}"
    }

    private fun projectSummary(): String {
        val doc = project()
        val back = BlockDocumentJson.decode(BlockDocumentJson.encode(doc))
        val stable = doc.blocks.map { it.id } == back.blocks.map { it.id }
        return "projected blocks=${doc.blocks.map { it.id + ":" + it::class.java.simpleName }}\nidsStableAfterRoundTrip=$stable\nbytes=${BlockDocumentJson.encode(doc).length}"
    }

    private class BlockMarker(val blockId: String, val kind: String) : CharacterStyle() {
        override fun updateDrawState(tp: android.text.TextPaint) {}
    }

    private class MarkSpan(val kind: String, val value: String) : CharacterStyle() {
        override fun updateDrawState(tp: android.text.TextPaint) {}
    }

    private inner class AtomSpan(val block: DocumentBlock, name: String) : ReplacementSpan() {
        private val label = name
        private var bitmap: android.graphics.Bitmap? = null

        init {
            if (block is ImageBlock) {
                bitmap = runCatching {
                    BitmapFactory.decodeByteArray(Base64.decode(FIXTURE_PNG, Base64.DEFAULT), 0, Base64.decode(FIXTURE_PNG, Base64.DEFAULT).size)
                }.getOrNull()
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                setContentDescription(label)
            }
        }

        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int = 540

        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val width = 520f
            val height = 150f
            val rect = RectF(x, top.toFloat(), x + width, top + height)
            val border = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.rgb(230, 81, 0) }
            val fill = Paint().apply { style = Paint.Style.FILL; color = Color.rgb(255, 224, 178) }
            canvas.drawRoundRect(rect, 8f, 8f, fill)
            when (block) {
                is ImageBlock -> {
                    val bmp = bitmap
                    if (bmp != null) {
                        val dst = RectF(rect.left + 8, rect.top + 8, rect.left + 8 + height, rect.bottom - 8)
                        canvas.drawBitmap(bmp, null, dst, Paint().apply { isFilterBitmap = false })
                    }
                    val tp = Paint().apply { color = Color.rgb(120, 0, 0); textSize = 30f; isAntiAlias = true }
                    canvas.drawText("image ${block.id}", rect.left + height + 20, rect.centerY(), tp)
                }
                is TableBlock -> {
                    val cols = maxOf(1, block.headers.size)
                    val rows = block.rows.size + 1
                    val cw = (rect.width() - 16) / cols
                    val rh = (rect.height() - 16) / rows
                    for (r in 0 until rows) {
                        for (c in 0 until cols) {
                            val cell = if (r == 0) block.headers.getOrNull(c) else block.rows.getOrNull(r - 1)?.getOrNull(c)
                            val txt = cell?.content?.joinToString("") { it.plainText() } ?: ""
                            canvas.drawRect(rect.left + 8 + c * cw, rect.top + 8 + r * rh, rect.left + 8 + (c + 1) * cw, rect.top + 8 + (r + 1) * rh, border)
                            canvas.drawText(txt, rect.left + 14 + c * cw, rect.top + 8 + r * rh + rh * 0.7f, Paint().apply { color = Color.BLACK; textSize = 28f })
                        }
                    }
                }
                is ChartBlock -> {
                    val bars = listOf(0.35f, 0.7f)
                    val bw = rect.width() / (bars.size * 2 + 1)
                    bars.forEachIndexed { i, v ->
                        val left = rect.left + bw * (i * 2 + 1)
                        canvas.drawRect(left, rect.bottom - 12 - (rect.height() - 24) * v, left + bw, rect.bottom - 12, Paint().apply { color = Color.rgb(111, 54, 255) })
                    }
                    canvas.drawLine(rect.left + 8, rect.bottom - 12, rect.right - 8, rect.bottom - 12, border)
                    canvas.drawText("chart ${block.id}", rect.left + 12, rect.top + 34, Paint().apply { color = Color.BLACK; textSize = 28f })
                }
                else -> canvas.drawText(label, rect.left + 12, rect.centerY(), Paint().apply { color = Color.BLACK; textSize = 30f })
            }
        }
    }

    private class NorfoldHistory(private val render: (BlockDocument, Pair<Int, Int>) -> Unit) {
        private data class Snap(val json: String, val sel: Pair<Int, Int>)
        private val undoStack = ArrayDeque<Snap>()
        private val redoStack = ArrayDeque<Snap>()
        private var current: Snap? = null

        fun reset(text: String, sel: Pair<Int, Int>) {
            undoStack.clear(); redoStack.clear()
            current = Snap(text, sel)
        }

        fun commit(text: String, sel: Pair<Int, Int>) {
            val c = current
            if (c != null && c.json == text) return
            c?.let { undoStack.addLast(it) }
            redoStack.clear()
            current = Snap(text, sel)
            Log.i(C2Stage2Activity.TAG, "history commit undo=${undoStack.size} redo=${redoStack.size}")
        }

        fun undo() {
            val prev = undoStack.removeLastOrNull() ?: return
            current?.let { redoStack.addLast(it) }
            current = prev
            apply(prev)
            Log.i(C2Stage2Activity.TAG, "history undo -> undo=${undoStack.size} redo=${redoStack.size} sel=${prev.sel}")
        }

        fun redo() {
            val next = redoStack.removeLastOrNull() ?: return
            current?.let { undoStack.addLast(it) }
            current = next
            apply(next)
            Log.i(C2Stage2Activity.TAG, "history redo -> undo=${undoStack.size} redo=${redoStack.size}")
        }

        private fun apply(s: Snap) {
            render(BlockDocumentJson.decode(s.json), s.sel)
        }
    }

    companion object {
        const val TAG = "C2STAGE2"
        const val ACTION_EVAL = "com.norfold.app.C2_EVAL"
        const val ATOM = '\uFFFC'
        const val FIXTURE_PNG = "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEElEQVR42mPIN/sPRAwQCgArvgaR9mDBOwAAAABJRU5ErkJggg=="
    }
}

private fun DocumentBlock.contentOrEmpty(): List<InlineNode> = when (this) {
    is ParagraphBlock -> content
    else -> listOf(InlineText(plainText()))
}

object Fixture {
    fun document(): BlockDocument = BlockDocument(
        blocks = listOf(
            ParagraphBlock(id = "p1", content = listOf(InlineText("Alpha paragraph with "), BoldInline(listOf(InlineText("bold"))), InlineText(" and "), LinkInline("https://example.com/flow-stage1", listOf(InlineText("link"))), InlineText("."))),
            ParagraphBlock(id = "p2", content = listOf(InlineText("Text before image."))),
            ImageBlock(id = "img1", source = "asset:fixture-img1", caption = "Stage-1 fixture image", displayHeightDp = 220f),
            ParagraphBlock(id = "p3", content = listOf(InlineText("Text after image."))),
            TableBlock(id = "tbl1", headers = listOf(TableCell(listOf(InlineText("H1"))), TableCell(listOf(InlineText("H2")))), rows = listOf(listOf(TableCell(listOf(InlineText("A1"))), TableCell(listOf(InlineText("B1")))), listOf(TableCell(listOf(InlineText("A2"))), TableCell(listOf(InlineText("B2")))))),
            ParagraphBlock(id = "p4", content = listOf(InlineText("Paragraph after table."))),
            ChartBlock(id = "chart1"),
            ParagraphBlock(id = "p5", content = listOf(InlineText("See "), TagInline("stage1"), InlineText(" and "), MentionInline("qa"), InlineText(" for context."))),
            ParagraphBlock(id = "p6", content = listOf(InlineText("Final paragraph."))),
        ),
    )
}
