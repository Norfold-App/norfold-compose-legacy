package com.norfold.app.domain

data class TextInsertion(
    val oldStart: Int,
    val oldEnd: Int,
    val insertedText: String,
)

object SmartPasteCodec {
    fun insertion(oldText: String, newText: String): TextInsertion? {
        if (oldText == newText) return null
        val prefix = oldText.commonPrefixWith(newText).length
        val suffixLimit = minOf(oldText.length - prefix, newText.length - prefix)
        var suffix = 0
        while (suffix < suffixLimit && oldText[oldText.lastIndex - suffix] == newText[newText.lastIndex - suffix]) suffix++
        return TextInsertion(
            oldStart = prefix,
            oldEnd = oldText.length - suffix,
            insertedText = newText.substring(prefix, newText.length - suffix),
        )
    }

    fun shouldImport(insertion: TextInsertion): Boolean {
        val value = insertion.insertedText.trim()
        if (value.isEmpty()) return false
        return insertion.insertedText.contains('\n') ||
            value.matches(Regex("https?://\\S+")) ||
            value.matches(Regex("!\\[[^]]*]\\([^)]*\\)"))
    }

    fun import(insertion: TextInsertion): BlockDocument = MarkdownBlockCodec.import(insertion.insertedText)
}
