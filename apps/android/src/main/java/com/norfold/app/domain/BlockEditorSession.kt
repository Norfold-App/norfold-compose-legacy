package com.norfold.app.domain

data class BlockCursor(val blockId: String, val offset: Int, val itemId: String? = null)
data class EditTextOutcome(val cursor: BlockCursor, val structuredPaste: Boolean)

class BlockEditorSession(initial: BlockDocument) {
    var document: BlockDocument = initial.normalized()
        private set

    val dirtyBlockIds = linkedSetOf<String>()
    val deletedBlockIds = linkedSetOf<String>()

    private val undo = ArrayDeque<BlockDocument>()
    private val redo = ArrayDeque<BlockDocument>()

    fun canUndo() = undo.isNotEmpty()
    fun canRedo() = redo.isNotEmpty()

    fun replaceBlock(block: DocumentBlock) = mutate(setOf(block.id)) { blocks ->
        blocks.map { if (it.id == block.id) block else it }
    }

    fun editText(blockId: String, oldText: String, newText: String): BlockCursor {
        if (oldText == newText) return BlockCursor(blockId, newText.length)
        val prefix = oldText.commonPrefixWith(newText).length
        val suffixLimit = minOf(oldText.length - prefix, newText.length - prefix)
        var suffix = 0
        while (suffix < suffixLimit && oldText[oldText.lastIndex - suffix] == newText[newText.lastIndex - suffix]) suffix++
        return replaceSelection(
            BlockCursor(blockId, prefix),
            BlockCursor(blockId, oldText.length - suffix),
            newText.substring(prefix, newText.length - suffix),
        )
    }

    fun editTextOrSmartPaste(blockId: String, oldText: String, newText: String): EditTextOutcome {
        val insertion = SmartPasteCodec.insertion(oldText, newText)
        if (insertion == null || !SmartPasteCodec.shouldImport(insertion)) {
            return EditTextOutcome(editText(blockId, oldText, newText), structuredPaste = false)
        }
        val imported = SmartPasteCodec.import(insertion)
        val cursor = replaceSelectionWithBlocks(
            start = BlockCursor(blockId, insertion.oldStart),
            end = BlockCursor(blockId, insertion.oldEnd),
            inserted = imported,
        )
        return EditTextOutcome(cursor, structuredPaste = true)
    }

    fun editListItem(blockId: String, itemId: String, oldText: String, newText: String): BlockCursor {
        val block = document.blocks.firstOrNull { it.id == blockId } ?: return BlockCursor(blockId, 0, itemId)
        val items = block.listItems() ?: return BlockCursor(blockId, 0, itemId)
        val index = items.indexOfFirst { it.id == itemId }
        if (index < 0 || oldText == newText) return BlockCursor(blockId, newText.length, itemId)
        val updated = items.toMutableList()
        updated[index] = updated[index].copy(content = editInline(updated[index].content, oldText, newText))
        replaceListItems(block, updated)
        return BlockCursor(blockId, changedCursorOffset(oldText, newText), itemId)
    }

    fun splitListItem(blockId: String, itemId: String, offset: Int): BlockCursor {
        val block = document.blocks.firstOrNull { it.id == blockId } ?: return BlockCursor(blockId, offset, itemId)
        val items = block.listItems() ?: return BlockCursor(blockId, offset, itemId)
        val index = items.indexOfFirst { it.id == itemId }
        if (index < 0) return BlockCursor(blockId, offset, itemId)
        val item = items[index]
        val (before, after) = splitInline(item.content, offset.coerceIn(0, item.content.plainText().length))
        val next = ListItem(content = after)
        val updated = items.toMutableList().apply {
            this[index] = item.copy(content = before)
            add(index + 1, next)
        }
        replaceListItems(block, updated)
        return BlockCursor(blockId, 0, next.id)
    }

    fun exitEmptyListItem(blockId: String, itemId: String): BlockCursor {
        val blockIndex = document.blocks.indexOfFirst { it.id == blockId }
        val block = document.blocks.getOrNull(blockIndex) ?: return firstCursor()
        val items = block.listItems() ?: return BlockCursor(blockId, 0, itemId)
        val itemIndex = items.indexOfFirst { it.id == itemId }
        if (itemIndex < 0 || items[itemIndex].content.plainText().isNotBlank()) return BlockCursor(blockId, 0, itemId)
        val paragraph = ParagraphBlock(id = if (items.size == 1) block.id else ParagraphBlock().id)
        mutate(setOf(block.id, paragraph.id)) { blocks ->
            blocks.toMutableList().apply {
                if (items.size == 1) {
                    this[blockIndex] = paragraph
                } else {
                    this[blockIndex] = block.withListItems(items.toMutableList().apply { removeAt(itemIndex) })
                    add(blockIndex + 1, paragraph)
                }
            }
        }
        return BlockCursor(paragraph.id, 0)
    }

    fun mergeListItemWithPrevious(blockId: String, itemId: String): BlockCursor {
        val block = document.blocks.firstOrNull { it.id == blockId } ?: return BlockCursor(blockId, 0, itemId)
        val items = block.listItems() ?: return BlockCursor(blockId, 0, itemId)
        val index = items.indexOfFirst { it.id == itemId }
        if (index <= 0) return BlockCursor(blockId, 0, itemId)
        val previous = items[index - 1]
        val current = items[index]
        val join = previous.content.plainText().length
        val updated = items.toMutableList().apply {
            this[index - 1] = previous.copy(content = previous.content + current.content)
            removeAt(index)
        }
        replaceListItems(block, updated)
        return BlockCursor(blockId, join, previous.id)
    }

    fun insertAfter(anchorId: String?, block: DocumentBlock): BlockCursor {
        mutate(setOf(block.id)) { blocks ->
            val index = anchorId?.let { id -> blocks.indexOfFirst { it.id == id } } ?: -1
            blocks.toMutableList().apply { add((index + 1).coerceIn(0, size), block) }
        }
        return BlockCursor(block.id, 0)
    }

    fun delete(blockId: String): BlockCursor {
        val index = document.blocks.indexOfFirst { it.id == blockId }
        if (index < 0) return firstCursor()
        deletedBlockIds += blockId
        mutate(emptySet()) { blocks -> blocks.filterNot { it.id == blockId }.ifEmpty { listOf(ParagraphBlock()) } }
        val target = document.blocks.getOrNull(index.coerceAtMost(document.blocks.lastIndex)) ?: document.blocks.first()
        return BlockCursor(target.id, target.plainText().length)
    }

    fun move(blockId: String, targetIndex: Int) {
        val from = document.blocks.indexOfFirst { it.id == blockId }
        if (from < 0) return
        mutate(document.blocks.mapTo(linkedSetOf()) { it.id }) { source ->
            source.toMutableList().apply {
                val moved = removeAt(from)
                add(targetIndex.coerceIn(0, size), moved)
            }
        }
    }

    fun split(blockId: String, offset: Int): BlockCursor {
        val index = document.blocks.indexOfFirst { it.id == blockId }
        val block = document.blocks.getOrNull(index) ?: return firstCursor()
        val content = block.editableInline() ?: return BlockCursor(blockId, offset)
        val (before, after) = splitInline(content, offset.coerceIn(0, content.plainText().length))
        val left = block.withInline(before)
        val right = when (block) {
            is HeadingBlock -> ParagraphBlock(content = after)
            else -> block.withInline(after, newId = true)
        }
        mutate(setOf(left.id, right.id)) { blocks ->
            blocks.toMutableList().apply { this[index] = left; add(index + 1, right) }
        }
        return BlockCursor(right.id, 0)
    }

    fun mergeWithPrevious(blockId: String): BlockCursor {
        val index = document.blocks.indexOfFirst { it.id == blockId }
        if (index <= 0) return BlockCursor(blockId, 0)
        val previous = document.blocks[index - 1]
        val current = document.blocks[index]
        val previousInline = previous.editableInline() ?: return BlockCursor(blockId, 0)
        val currentInline = current.editableInline() ?: return BlockCursor(blockId, 0)
        val join = previousInline.plainText().length
        val merged = previous.withInline(previousInline + currentInline)
        deletedBlockIds += current.id
        mutate(setOf(previous.id)) { blocks ->
            blocks.toMutableList().apply { this[index - 1] = merged; removeAt(index) }
        }
        return BlockCursor(previous.id, join)
    }

    fun replaceSelection(start: BlockCursor, end: BlockCursor, replacement: String): BlockCursor {
        val startIndex = document.blocks.indexOfFirst { it.id == start.blockId }
        val endIndex = document.blocks.indexOfFirst { it.id == end.blockId }
        if (startIndex < 0 || endIndex < startIndex) return firstCursor()
        val first = document.blocks[startIndex]
        val last = document.blocks[endIndex]
        val firstInline = first.editableInline() ?: return firstCursor()
        val lastInline = last.editableInline() ?: return firstCursor()
        val before = splitInline(firstInline, start.offset.coerceIn(0, firstInline.plainText().length)).first
        val after = splitInline(lastInline, end.offset.coerceIn(0, lastInline.plainText().length)).second
        val merged = first.withInline((before + InlineText(replacement) + after).mergeAdjacentText())
        val removed = document.blocks.subList(startIndex + 1, endIndex + 1).map { it.id }
        deletedBlockIds += removed
        mutate(setOf(merged.id)) { blocks ->
            blocks.toMutableList().apply {
                this[startIndex] = merged
                repeat(endIndex - startIndex) { removeAt(startIndex + 1) }
            }
        }
        return BlockCursor(merged.id, before.plainText().length + replacement.length)
    }

    fun replaceSelectionWithInline(start: BlockCursor, end: BlockCursor, replacement: InlineNode): BlockCursor {
        if (start.blockId != end.blockId) return firstCursor()
        val block = document.blocks.firstOrNull { it.id == start.blockId } ?: return firstCursor()
        val inline = block.editableInline() ?: return firstCursor()
        val before = splitInline(inline, start.offset.coerceIn(0, inline.plainText().length)).first
        val after = splitInline(inline, end.offset.coerceIn(start.offset, inline.plainText().length)).second
        val updated = block.withInline((before + replacement + after).mergeAdjacentText())
        replaceBlock(updated)
        return BlockCursor(block.id, before.plainText().length + replacement.plainText().length)
    }

    fun replaceSelectionWithBlocks(start: BlockCursor, end: BlockCursor, inserted: BlockDocument): BlockCursor {
        val startIndex = document.blocks.indexOfFirst { it.id == start.blockId }
        val endIndex = document.blocks.indexOfFirst { it.id == end.blockId }
        if (startIndex < 0 || endIndex < startIndex) return firstCursor()
        val first = document.blocks[startIndex]
        val last = document.blocks[endIndex]
        val firstInline = first.editableInline() ?: return firstCursor()
        val lastInline = last.editableInline() ?: return firstCursor()
        val before = splitInline(firstInline, start.offset.coerceIn(0, firstInline.plainText().length)).first
        val after = splitInline(lastInline, end.offset.coerceIn(0, lastInline.plainText().length)).second
        val replacement = mutableListOf<DocumentBlock>()
        if (before.isNotEmpty()) replacement += first.withInline(before)
        val imported = inserted.normalized().blocks.toMutableList()
        if (before.isEmpty() && imported.isNotEmpty()) imported[0] = imported[0].withId(first.id)
        replacement += imported
        val continuation = when {
            after.isNotEmpty() -> ParagraphBlock(content = after)
            imported.lastOrNull()?.editableInline() == null -> ParagraphBlock()
            else -> null
        }
        if (continuation != null) replacement += continuation
        if (replacement.isEmpty()) replacement += ParagraphBlock(id = first.id)

        val removedIds = document.blocks.subList(startIndex, endIndex + 1).mapTo(linkedSetOf()) { it.id } - replacement.mapTo(hashSetOf()) { it.id }
        deletedBlockIds += removedIds
        mutate(replacement.mapTo(linkedSetOf()) { it.id }) { blocks ->
            blocks.toMutableList().apply {
                repeat(endIndex - startIndex + 1) { removeAt(startIndex) }
                addAll(startIndex, replacement)
            }
        }
        return when {
            continuation != null -> BlockCursor(continuation.id, 0)
            imported.isNotEmpty() -> BlockCursor(imported.last().id, imported.last().plainText().length)
            else -> BlockCursor(replacement.last().id, replacement.last().plainText().length)
        }
    }

    fun undo(): Boolean {
        val previous = undo.removeLastOrNull() ?: return false
        redo += document
        document = previous
        dirtyBlockIds += document.blocks.map { it.id }
        return true
    }

    fun redo(): Boolean {
        val next = redo.removeLastOrNull() ?: return false
        undo += document
        document = next
        dirtyBlockIds += document.blocks.map { it.id }
        return true
    }

    fun markSaved() {
        dirtyBlockIds.clear()
        deletedBlockIds.clear()
    }

    private fun mutate(dirty: Set<String>, transform: (List<DocumentBlock>) -> List<DocumentBlock>) {
        val next = BlockDocument(transform(document.blocks)).normalized()
        if (next == document) return
        undo += document
        redo.clear()
        document = next
        dirtyBlockIds += dirty
    }

    private fun replaceListItems(block: DocumentBlock, items: List<ListItem>) {
        replaceBlock(block.withListItems(items))
    }

    private fun firstCursor() = BlockCursor(document.blocks.first().id, 0)
}

private fun DocumentBlock.listItems(): List<ListItem>? = when (this) {
    is BulletListBlock -> items
    is NumberedListBlock -> items
    else -> null
}

private fun DocumentBlock.withListItems(items: List<ListItem>): DocumentBlock = when (this) {
    is BulletListBlock -> copy(items = items)
    is NumberedListBlock -> copy(items = items)
    else -> this
}

private fun changedCursorOffset(oldText: String, newText: String): Int {
    val prefix = oldText.commonPrefixWith(newText).length
    val suffixLimit = minOf(oldText.length - prefix, newText.length - prefix)
    var suffix = 0
    while (suffix < suffixLimit && oldText[oldText.lastIndex - suffix] == newText[newText.lastIndex - suffix]) suffix++
    return prefix + (newText.length - prefix - suffix)
}

private fun editInline(content: List<InlineNode>, oldText: String, newText: String): List<InlineNode> {
    val prefix = oldText.commonPrefixWith(newText).length
    val suffixLimit = minOf(oldText.length - prefix, newText.length - prefix)
    var suffix = 0
    while (suffix < suffixLimit && oldText[oldText.lastIndex - suffix] == newText[newText.lastIndex - suffix]) suffix++
    val before = splitInline(content, prefix).first
    val after = splitInline(content, oldText.length - suffix).second
    val replacement = newText.substring(prefix, newText.length - suffix)
    return buildList {
        addAll(before)
        if (replacement.isNotEmpty()) add(InlineText(replacement))
        addAll(after)
    }.mergeAdjacentText()
}

private fun List<InlineNode>.mergeAdjacentText(): List<InlineNode> = fold(mutableListOf()) { result, node ->
    val normalized = when (node) {
        is BoldInline -> node.copy(children = node.children.mergeAdjacentText())
        is ItalicInline -> node.copy(children = node.children.mergeAdjacentText())
        is StrikethroughInline -> node.copy(children = node.children.mergeAdjacentText())
        is LinkInline -> node.copy(children = node.children.mergeAdjacentText())
        else -> node
    }
    if (normalized is InlineText && normalized.value.isEmpty()) return@fold result
    val previous = result.lastOrNull()
    if (previous is InlineText && normalized is InlineText) {
        result[result.lastIndex] = InlineText(previous.value + normalized.value)
    } else {
        result += normalized
    }
    result
}

private fun DocumentBlock.editableInline(): List<InlineNode>? = when (this) {
    is ParagraphBlock -> content
    is HeadingBlock -> content
    else -> null
}

private fun DocumentBlock.withInline(content: List<InlineNode>, newId: Boolean = false): DocumentBlock = when (this) {
    is ParagraphBlock -> copy(id = if (newId) ParagraphBlock().id else id, content = content)
    is HeadingBlock -> copy(id = if (newId) HeadingBlock().id else id, content = content)
    else -> this
}

private fun DocumentBlock.withId(nextId: String): DocumentBlock = when (this) {
    is ParagraphBlock -> copy(id = nextId)
    is HeadingBlock -> copy(id = nextId)
    is BulletListBlock -> copy(id = nextId)
    is NumberedListBlock -> copy(id = nextId)
    is TodoListBlock -> copy(id = nextId)
    is QuoteBlock -> copy(id = nextId)
    is CalloutBlock -> copy(id = nextId)
    is DividerBlock -> copy(id = nextId)
    is CodeBlock -> copy(id = nextId)
    is TableBlock -> copy(id = nextId)
    is ImageBlock -> copy(id = nextId)
    is FileBlock -> copy(id = nextId)
    is EmbedBlock -> copy(id = nextId)
    is ChartBlock -> copy(id = nextId)
    is MathBlock -> copy(id = nextId)
    is MermaidBlock -> copy(id = nextId)
}

private fun splitInline(nodes: List<InlineNode>, offset: Int): Pair<List<InlineNode>, List<InlineNode>> {
    val left = mutableListOf<InlineNode>()
    val right = mutableListOf<InlineNode>()
    var consumed = 0
    nodes.forEach { node ->
        val length = node.plainText().length
        when {
            consumed + length <= offset -> left += node
            consumed >= offset -> right += node
            else -> {
                val local = offset - consumed
                val split = splitInlineNode(node, local)
                split.first?.let(left::add)
                split.second?.let(right::add)
            }
        }
        consumed += length
    }
    return left to right
}

private fun splitInlineNode(node: InlineNode, offset: Int): Pair<InlineNode?, InlineNode?> = when (node) {
    is InlineText -> node.value.substring(0, offset).takeIf(String::isNotEmpty)?.let(::InlineText) to
        node.value.substring(offset).takeIf(String::isNotEmpty)?.let(::InlineText)
    is CodeInline -> node.value.substring(0, offset).takeIf(String::isNotEmpty)?.let(::CodeInline) to
        node.value.substring(offset).takeIf(String::isNotEmpty)?.let(::CodeInline)
    is MathInline -> node.tex.substring(0, offset).takeIf(String::isNotEmpty)?.let(::MathInline) to
        node.tex.substring(offset).takeIf(String::isNotEmpty)?.let(::MathInline)
    is BoldInline -> splitInline(node.children, offset).let { (a, b) -> a.takeIf(List<InlineNode>::isNotEmpty)?.let(::BoldInline) to b.takeIf(List<InlineNode>::isNotEmpty)?.let(::BoldInline) }
    is ItalicInline -> splitInline(node.children, offset).let { (a, b) -> a.takeIf(List<InlineNode>::isNotEmpty)?.let(::ItalicInline) to b.takeIf(List<InlineNode>::isNotEmpty)?.let(::ItalicInline) }
    is StrikethroughInline -> splitInline(node.children, offset).let { (a, b) -> a.takeIf(List<InlineNode>::isNotEmpty)?.let(::StrikethroughInline) to b.takeIf(List<InlineNode>::isNotEmpty)?.let(::StrikethroughInline) }
    is LinkInline -> splitInline(node.children, offset).let { (a, b) -> a.takeIf(List<InlineNode>::isNotEmpty)?.let { LinkInline(node.url, it) } to b.takeIf(List<InlineNode>::isNotEmpty)?.let { LinkInline(node.url, it) } }
    is EmojiInline, is TagInline, is MentionInline -> if (offset <= 0) null to node else node to null
}
