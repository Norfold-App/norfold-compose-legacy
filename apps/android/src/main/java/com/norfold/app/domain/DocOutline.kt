package com.norfold.app.domain

/**
 * One heading in a document's table of contents. [topLevelId]/[topLevelIndex] point at the heading's
 * top-level ancestor block so scroll targeting works even for headings nested inside quotes, callouts,
 * or container columns (the editor's LazyColumn items — and the freeform layout map — are keyed by
 * top-level blocks only).
 */
data class DocHeading(
    val blockId: String,
    val topLevelId: String,
    val topLevelIndex: Int,
    val level: Int,
    val label: String,
)

/** Pure ToC extraction over a [BlockDocument], including headings nested in containers. */
object DocOutline {

    fun extract(document: BlockDocument): List<DocHeading> {
        val out = mutableListOf<DocHeading>()
        document.blocks.forEachIndexed { index, top -> collect(top, top.id, index, out) }
        return out
    }

    private fun collect(block: DocumentBlock, topId: String, topIndex: Int, out: MutableList<DocHeading>) {
        when (block) {
            is HeadingBlock -> out += DocHeading(
                blockId = block.id,
                topLevelId = topId,
                topLevelIndex = topIndex,
                level = block.level.coerceIn(1, 6),
                label = block.content.joinToString("") { it.plainText() }.trim().ifBlank { "Untitled heading" },
            )
            is QuoteBlock -> block.children.forEach { collect(it, topId, topIndex, out) }
            is CalloutBlock -> block.children.forEach { collect(it, topId, topIndex, out) }
            is ContainerBlock -> block.children.forEach { collect(it, topId, topIndex, out) }
            else -> Unit
        }
    }
}
