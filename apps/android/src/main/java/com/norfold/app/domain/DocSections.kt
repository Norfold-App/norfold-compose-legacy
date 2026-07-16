package com.norfold.app.domain

import java.util.UUID

/**
 * A "section" is a top-level [HeadingBlock] plus every following top-level block up to (not
 * including) the next top-level heading of the same or shallower level. Sections are the unit the
 * sidebar ToC manipulates (delete / duplicate / reorder). Headings nested inside quotes, callouts,
 * or containers do not start sections — they belong to their top-level ancestor's section.
 */
object DocSections {

    /** Ids of the top-level headings that start sections, in document order. */
    fun sectionHeadingIds(document: BlockDocument): List<String> =
        document.blocks.filterIsInstance<HeadingBlock>().map { it.id }

    /**
     * The contiguous top-level index range owned by [headingId]'s section, or null when
     * [headingId] is not a top-level heading.
     */
    fun rangeFor(document: BlockDocument, headingId: String): IntRange? {
        val start = document.blocks.indexOfFirst { it.id == headingId }
        val heading = document.blocks.getOrNull(start) as? HeadingBlock ?: return null
        var end = start
        while (end + 1 < document.blocks.size) {
            val next = document.blocks[end + 1]
            if (next is HeadingBlock && next.level <= heading.level) break
            end++
        }
        return start..end
    }
}

/** One-shot sidebar ToC → editor section mutation, applied through the open [BlockEditorSession]. */
sealed interface DocSectionAction {
    data object Delete : DocSectionAction
    data object Duplicate : DocSectionAction
    data object MoveUp : DocSectionAction
    data object MoveDown : DocSectionAction

    /** Reorder: insert the section before [targetHeadingId]'s section, or at the end when null. */
    data class MoveBefore(val targetHeadingId: String?) : DocSectionAction
}

/** Deep copy of a block tree with fresh ids everywhere (blocks, list items, todo items). */
fun DocumentBlock.withFreshIds(): DocumentBlock = when (this) {
    is UnknownBlock -> BlockDocumentJson.reidentify(this, freshId())
    is ParagraphBlock -> copy(id = freshId())
    is HeadingBlock -> copy(id = freshId())
    is BulletListBlock -> copy(id = freshId(), items = items.map { it.withFreshIds() })
    is NumberedListBlock -> copy(id = freshId(), items = items.map { it.withFreshIds() })
    is TodoListBlock -> copy(id = freshId(), items = items.map { it.copy(id = freshId()) })
    is QuoteBlock -> copy(id = freshId(), children = children.map { it.withFreshIds() })
    is CalloutBlock -> copy(id = freshId(), children = children.map { it.withFreshIds() })
    is ContainerBlock -> copy(id = freshId(), children = children.map { it.withFreshIds() })
    is DividerBlock -> copy(id = freshId())
    is CodeBlock -> copy(id = freshId())
    is TableBlock -> copy(id = freshId())
    is ImageBlock -> copy(id = freshId())
    is FileBlock -> copy(id = freshId())
    is EmbedBlock -> copy(id = freshId())
    is ChartBlock -> copy(id = freshId())
    is MathBlock -> copy(id = freshId())
    is MermaidBlock -> copy(id = freshId())
}

private fun ListItem.withFreshIds(): ListItem =
    copy(id = freshId(), children = children.map { it.withFreshIds() })

private fun freshId() = UUID.randomUUID().toString()
