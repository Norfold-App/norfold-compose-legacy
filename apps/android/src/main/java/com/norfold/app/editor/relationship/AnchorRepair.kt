package com.norfold.app.editor.relationship

data class TextEdit(val start: Int, val end: Int, val replacement: String) {
    init { require(start >= 0 && end >= start) }
}

data class AnchorRepairMetrics(val attempted: Int = 0, val detached: Int = 0)

object TextAnchorRepair {
    fun apply(anchor: CommentAnchor.Text, edit: TextEdit, textAfter: String): CommentAnchor.Text {
        if (anchor.state == AnchorState.DETACHED) return repairByContext(anchor, textAfter)
        val removed = edit.end - edit.start
        val inserted = edit.replacement.length
        val delta = inserted - removed
        val next = when {
            edit.end <= anchor.start -> anchor.copy(start = anchor.start + delta, end = anchor.end + delta)
            edit.start >= anchor.end -> anchor
            edit.start <= anchor.start && edit.end >= anchor.end && inserted == 0 -> anchor.copy(state = AnchorState.DETACHED)
            else -> {
                val start = minOf(anchor.start, edit.start).coerceAtMost(textAfter.length)
                val end = (anchor.end + delta).coerceIn(start, textAfter.length)
                if (start == end) anchor.copy(start = start, end = end, state = AnchorState.DETACHED)
                else anchor.copy(start = start, end = end, quote = textAfter.substring(start, end))
            }
        }
        return if (next.state == AnchorState.ATTACHED && next.end <= textAfter.length) {
            next.copy(quote = textAfter.substring(next.start, next.end))
        } else next
    }

    fun repairByContext(anchor: CommentAnchor.Text, text: String): CommentAnchor.Text {
        if (anchor.quote.isBlank()) return anchor.copy(state = AnchorState.DETACHED)
        val hits = Regex(Regex.escape(anchor.quote)).findAll(text).map { it.range.first }.toList()
        val chosen = hits.singleOrNull() ?: hits.minByOrNull { kotlin.math.abs(it - anchor.start) }
            ?.takeIf { candidate -> hits.count { kotlin.math.abs(it - anchor.start) == kotlin.math.abs(candidate - anchor.start) } == 1 }
        return chosen?.let { anchor.copy(start = it, end = it + anchor.quote.length, state = AnchorState.ATTACHED) }
            ?: anchor.copy(state = AnchorState.DETACHED)
    }
}
