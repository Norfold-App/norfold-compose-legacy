package com.norfold.app.domain

data class BlockCursor(val blockId: String, val offset: Int, val itemId: String? = null)
data class EditTextOutcome(val cursor: BlockCursor, val structuredPaste: Boolean)

enum class InlineMark { Bold, Italic, Underline, Strikethrough, Code }

/**
 * Transient typing attributes. This deliberately lives outside [BlockDocument]: a toolbar toggle
 * at a collapsed caret affects future input only and must reset when the editor session ends.
 */
data class InlineTypingStyle(
    val marks: Set<InlineMark> = emptySet(),
    val highlight: String? = null,
    val fontFamily: String? = null,
    val fontSizeSp: Float? = null,
) {
    fun toggled(mark: InlineMark): InlineTypingStyle = when {
        mark == InlineMark.Code && mark in marks -> copy(marks = marks - mark)
        mark == InlineMark.Code -> copy(marks = setOf(InlineMark.Code), highlight = null)
        mark in marks -> copy(marks = marks - mark)
        InlineMark.Code in marks -> copy(marks = setOf(mark), highlight = null)
        else -> copy(marks = marks + mark)
    }
}

/** Edge of a target block a dragged block is dropped against, forming a new [ContainerBlock]. */
enum class Edge { Left, Right, Top, Bottom }

/** Where a dragged block should land. */
sealed interface DropTarget {
    /** Insert between siblings of [parentId] (top level if null) at [index]. */
    data class Into(val parentId: String?, val index: Int) : DropTarget
    /** Wrap [targetId] and the dragged block in a fresh container split along [edge]. */
    data class Split(val targetId: String, val edge: Edge) : DropTarget
}

/**
 * Editing session over a nested block tree. Public ops stay **id-based** (the editor edits blocks in
 * place by id); internally every structural op resolves the block's own **sibling list** by path
 * ([BlockTree]) and mutates that, so operations work identically at the top level or nested inside a
 * [ContainerBlock]. Persistence is one Room row per **top-level** block, so nested edits mark the
 * top-level ancestor dirty and only genuinely top-level removals go to [deletedBlockIds].
 */
class BlockEditorSession(initial: BlockDocument) {
    var document: BlockDocument = initial.normalized()
        private set

    val dirtyBlockIds = linkedSetOf<String>()
    val deletedBlockIds = linkedSetOf<String>()

    private val undo = ArrayDeque<BlockDocument>()
    private val redo = ArrayDeque<BlockDocument>()
    private var lastTypingKey: String? = null
    private var lastTypingAtNanos: Long = 0L

    fun canUndo() = undo.isNotEmpty()
    fun canRedo() = redo.isNotEmpty()

    fun breakTypingGroup() {
        lastTypingKey = null
        lastTypingAtNanos = 0L
    }

    fun replaceBlock(block: DocumentBlock) = mutate(setOf(block.id)) { blocks ->
        blocks.updateBlock(block.id) { block }
    }

    fun editText(
        blockId: String,
        oldText: String,
        newText: String,
        typingStyle: InlineTypingStyle? = null,
    ): BlockCursor {
        if (oldText == newText) return BlockCursor(blockId, newText.length)
        val prefix = oldText.commonPrefixWith(newText).length
        val suffixLimit = minOf(oldText.length - prefix, newText.length - prefix)
        var suffix = 0
        while (suffix < suffixLimit && oldText[oldText.lastIndex - suffix] == newText[newText.lastIndex - suffix]) suffix++
        val block = document.findById(blockId) ?: return BlockCursor(blockId, newText.length)
        val content = block.editableInline() ?: return BlockCursor(blockId, newText.length)
        val replacement = newText.substring(prefix, newText.length - suffix)
        val style = typingStyle ?: content.typingStyleAt(prefix)
        val before = splitInline(content, prefix).first
        val after = splitInline(content, oldText.length - suffix).second
        replaceBlock(block.withInline((before + replacement.asStyledInline(style) + after).mergeAdjacentText()), "block:$blockId")
        return BlockCursor(blockId, prefix + replacement.length)
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

    fun editListItem(
        blockId: String,
        itemId: String,
        oldText: String,
        newText: String,
        typingStyle: InlineTypingStyle? = null,
    ): BlockCursor {
        val block = document.findById(blockId) ?: return BlockCursor(blockId, 0, itemId)
        val items = block.listItems() ?: return BlockCursor(blockId, 0, itemId)
        val index = items.indexOfFirst { it.id == itemId }
        if (index < 0 || oldText == newText) return BlockCursor(blockId, newText.length, itemId)
        val updated = items.toMutableList()
        updated[index] = updated[index].copy(content = editInline(updated[index].content, oldText, newText, typingStyle))
        replaceListItems(block, updated, "list:$blockId:$itemId")
        return BlockCursor(blockId, changedCursorOffset(oldText, newText), itemId)
    }

    fun editTodoItem(
        blockId: String,
        itemId: String,
        oldText: String,
        newText: String,
        typingStyle: InlineTypingStyle? = null,
    ): BlockCursor {
        val block = document.findById(blockId) as? TodoListBlock
            ?: return BlockCursor(blockId, 0, itemId)
        val index = block.items.indexOfFirst { it.id == itemId }
        if (index < 0 || oldText == newText) return BlockCursor(blockId, newText.length, itemId)
        val updated = block.items.toMutableList()
        updated[index] = updated[index].copy(content = editInline(updated[index].content, oldText, newText, typingStyle))
        replaceBlock(block.copy(items = updated), "todo:$blockId:$itemId")
        return BlockCursor(blockId, changedCursorOffset(oldText, newText), itemId)
    }

    fun splitListItem(blockId: String, itemId: String, offset: Int): BlockCursor {
        val block = document.findById(blockId) ?: return BlockCursor(blockId, offset, itemId)
        if (block is TodoListBlock) return splitTodoItem(block, itemId, offset)
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
        val block = document.findById(blockId) ?: return firstCursor()
        if (block is TodoListBlock) return exitEmptyTodoItem(block, itemId)
        val items = block.listItems() ?: return BlockCursor(blockId, 0, itemId)
        val itemIndex = items.indexOfFirst { it.id == itemId }
        if (itemIndex < 0 || items[itemIndex].content.plainText().isNotBlank()) return BlockCursor(blockId, 0, itemId)
        val paragraph = ParagraphBlock(id = if (items.size == 1) block.id else ParagraphBlock().id)
        mutateSiblings(blockId, setOf(block.id, paragraph.id)) { siblings, index, _ ->
            siblings.toMutableList().apply {
                if (items.size == 1) {
                    this[index] = paragraph
                } else {
                    this[index] = block.withListItems(items.toMutableList().apply { removeAt(itemIndex) })
                    add(index + 1, paragraph)
                }
            }
        }
        return BlockCursor(paragraph.id, 0)
    }

    fun mergeListItemWithPrevious(blockId: String, itemId: String): BlockCursor {
        val block = document.findById(blockId) ?: return BlockCursor(blockId, 0, itemId)
        if (block is TodoListBlock) return mergeTodoItemWithPrevious(block, itemId)
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

    /**
     * A to-do list holds [TodoItem]s, not [ListItem]s, so it is invisible to [listItems]. These three mirror
     * the bullet/numbered behavior for it — without them Enter and Backspace inside a to-do would resolve to a
     * no-op, which reads as a frozen editor rather than as an unsupported block.
     */
    private fun splitTodoItem(block: TodoListBlock, itemId: String, offset: Int): BlockCursor {
        val index = block.items.indexOfFirst { it.id == itemId }
        if (index < 0) return BlockCursor(block.id, offset, itemId)
        val item = block.items[index]
        val (before, after) = splitInline(item.content, offset.coerceIn(0, item.content.plainText().length))
        // The new item starts unchecked: it is new work, and inheriting "done" from the item it was split off
        // would silently mark something complete that the user has not done.
        val next = TodoItem(content = after)
        val updated = block.items.toMutableList().apply {
            this[index] = item.copy(content = before)
            add(index + 1, next)
        }
        replaceBlock(block.copy(items = updated))
        return BlockCursor(block.id, 0, next.id)
    }

    private fun exitEmptyTodoItem(block: TodoListBlock, itemId: String): BlockCursor {
        val index = block.items.indexOfFirst { it.id == itemId }
        if (index < 0 || block.items[index].content.plainText().isNotBlank()) {
            return BlockCursor(block.id, 0, itemId)
        }
        val paragraph = ParagraphBlock(id = if (block.items.size == 1) block.id else ParagraphBlock().id)
        mutateSiblings(block.id, setOf(block.id, paragraph.id)) { siblings, position, _ ->
            siblings.toMutableList().apply {
                if (block.items.size == 1) {
                    this[position] = paragraph
                } else {
                    this[position] = block.copy(
                        items = block.items.toMutableList().apply { removeAt(index) },
                    )
                    add(position + 1, paragraph)
                }
            }
        }
        return BlockCursor(paragraph.id, 0)
    }

    private fun mergeTodoItemWithPrevious(block: TodoListBlock, itemId: String): BlockCursor {
        val index = block.items.indexOfFirst { it.id == itemId }
        if (index <= 0) return BlockCursor(block.id, 0, itemId)
        val previous = block.items[index - 1]
        val current = block.items[index]
        val join = previous.content.plainText().length
        val updated = block.items.toMutableList().apply {
            this[index - 1] = previous.copy(content = previous.content + current.content)
            removeAt(index)
        }
        replaceBlock(block.copy(items = updated))
        return BlockCursor(block.id, join, previous.id)
    }

    fun insertAfter(anchorId: String?, block: DocumentBlock): BlockCursor {
        val located = anchorId?.let { document.blocks.locate(it) }
        mutate(setOf(block.id)) { blocks ->
            if (located == null) blocks.toMutableList().apply { add(0, block) }
            else blocks.insertInto(located.first, located.second + 1, listOf(block))
        }
        return BlockCursor(block.id, 0)
    }

    fun appendBlocks(blocks: List<DocumentBlock>): BlockCursor {
        if (blocks.isEmpty()) return firstCursor()
        mutate(blocks.mapTo(linkedSetOf()) { it.id }) { current -> current + blocks }
        return BlockCursor(blocks.first().id, 0)
    }

    fun deleteBlocks(blockIds: Set<String>): BlockCursor {
        if (blockIds.isEmpty()) return firstCursor()
        val existing = document.blocks.filter { it.id in blockIds }.map { it.id }
        if (existing.isEmpty()) return firstCursor()
        markRemoved(existing)
        val remaining = document.blocks.filterNot { it.id in blockIds }.ifEmpty { listOf(ParagraphBlock()) }
        mutate(remaining.mapTo(linkedSetOf()) { it.id }) { remaining }
        return firstCursor()
    }

    fun delete(blockId: String): BlockCursor {
        val located = document.blocks.locate(blockId) ?: return firstCursor()
        val (parentId, index, siblings) = located
        val neighbor = siblings.getOrNull(index + 1) ?: siblings.getOrNull(index - 1)
        markRemoved(listOf(blockId))
        mutate(emptySet()) { blocks ->
            blocks.updateChildList(parentId) { list -> list.filterNot { it.id == blockId } }
        }
        val target = neighbor?.id?.let { document.findById(it) } ?: document.blocks.first()
        return BlockCursor(target.id, target.plainText().length)
    }

    /** Legacy top-level reorder by index; retained until the drag layer is fully generalized. */
    fun move(blockId: String, targetIndex: Int) = moveInto(blockId, parentId = null, index = targetIndex)

    /** Generalized move: into a sibling slot, or edge-drop that forms a new container. */
    fun move(blockId: String, target: DropTarget) = when (target) {
        is DropTarget.Into -> moveInto(blockId, target.parentId, target.index)
        is DropTarget.Split -> splitDrop(blockId, target.targetId, target.edge)
    }

    private fun moveInto(blockId: String, parentId: String?, index: Int) {
        if (blockId == parentId) return
        if (parentId != null && isDescendant(container = blockId, maybeChild = parentId)) return
        val located = document.blocks.locate(blockId) ?: return
        val moved = document.findById(blockId) ?: return
        val wasTopLevel = document.blocks.any { it.id == blockId }
        mutate(document.blocks.mapTo(linkedSetOf()) { it.id }) { blocks ->
            val (removed, _) = blocks.removeBlock(blockId)
            val adjusted = if (located.first == parentId && located.second < index) index - 1 else index
            removed.insertInto(parentId, adjusted, listOf(moved))
        }
        finishMove(blockId, wasTopLevel)
    }

    private fun splitDrop(blockId: String, targetId: String, edge: Edge) {
        if (blockId == targetId) return
        if (isDescendant(container = blockId, maybeChild = targetId)) return
        val moved = document.findById(blockId) ?: return
        document.findById(targetId) ?: return
        val wasTopLevel = document.blocks.any { it.id == blockId }
        val axis = if (edge == Edge.Left || edge == Edge.Right) ContainerAxis.Row else ContainerAxis.Column
        mutate(document.blocks.mapTo(linkedSetOf()) { it.id }) { blocks ->
            val (removedMoved, _) = blocks.removeBlock(blockId)
            removedMoved.updateBlock(targetId) { target ->
                val ordered = when (edge) {
                    Edge.Left, Edge.Top -> listOf(moved, target)
                    Edge.Right, Edge.Bottom -> listOf(target, moved)
                }
                ContainerBlock(axis = axis, children = ordered, weights = List(ordered.size) { 1f })
            }
        }
        finishMove(blockId, wasTopLevel)
    }

    /** Shared dirty/deleted bookkeeping after a cross-tree move. */
    private fun finishMove(blockId: String, wasTopLevel: Boolean) {
        if (wasTopLevel && document.blocks.none { it.id == blockId }) deletedBlockIds += blockId
        dirtyBlockIds += document.blocks.map { it.id }
        document.rootAncestorId(blockId)?.let { dirtyBlockIds += it }
    }

    fun split(blockId: String, offset: Int): BlockCursor {
        val located = document.blocks.locate(blockId) ?: return firstCursor()
        val block = located.third[located.second]
        val content = block.editableInline() ?: return BlockCursor(blockId, offset)
        val (before, after) = splitInline(content, offset.coerceIn(0, content.plainText().length))
        val left = block.withInline(before)
        val right = when (block) {
            is HeadingBlock -> ParagraphBlock(content = after)
            else -> block.withInline(after, newId = true)
        }
        mutateSiblings(blockId, setOf(left.id, right.id)) { siblings, index, _ ->
            siblings.toMutableList().apply { this[index] = left; add(index + 1, right) }
        }
        return BlockCursor(right.id, 0)
    }

    fun mergeWithPrevious(blockId: String): BlockCursor {
        val located = document.blocks.locate(blockId) ?: return BlockCursor(blockId, 0)
        val (_, index, siblings) = located
        if (index <= 0) return BlockCursor(blockId, 0)
        val previous = siblings[index - 1]
        val current = siblings[index]
        val previousInline = previous.editableInline() ?: return BlockCursor(blockId, 0)
        val currentInline = current.editableInline() ?: return BlockCursor(blockId, 0)
        val join = previousInline.plainText().length
        val merged = previous.withInline(previousInline + currentInline)
        markRemoved(listOf(current.id))
        mutateSiblings(blockId, setOf(previous.id)) { list, idx, _ ->
            list.toMutableList().apply { this[idx - 1] = merged; removeAt(idx) }
        }
        return BlockCursor(previous.id, join)
    }

    fun replaceSelection(start: BlockCursor, end: BlockCursor, replacement: String): BlockCursor {
        val s = document.blocks.locate(start.blockId) ?: return firstCursor()
        val e = document.blocks.locate(end.blockId) ?: return firstCursor()
        if (s.first != e.first || e.second < s.second) return firstCursor()
        val parentId = s.first
        val siblings = s.third
        val startIndex = s.second
        val endIndex = e.second
        val first = siblings[startIndex]
        val last = siblings[endIndex]
        val firstInline = first.editableInline() ?: return firstCursor()
        val lastInline = last.editableInline() ?: return firstCursor()
        val before = splitInline(firstInline, start.offset.coerceIn(0, firstInline.plainText().length)).first
        val after = splitInline(lastInline, end.offset.coerceIn(0, lastInline.plainText().length)).second
        val merged = first.withInline((before + InlineText(replacement) + after).mergeAdjacentText())
        val removed = siblings.subList(startIndex + 1, endIndex + 1).map { it.id }
        markRemoved(removed)
        mutate(setOf(merged.id)) { blocks ->
            blocks.updateChildList(parentId) { list ->
                list.toMutableList().apply {
                    this[startIndex] = merged
                    repeat(endIndex - startIndex) { removeAt(startIndex + 1) }
                }
            }
        }
        return BlockCursor(merged.id, before.plainText().length + replacement.length)
    }

    fun replaceSelectionWithInline(start: BlockCursor, end: BlockCursor, replacement: InlineNode): BlockCursor {
        if (start.blockId != end.blockId) return firstCursor()
        val block = document.findById(start.blockId) ?: return firstCursor()
        val selectionStart = minOf(start.offset, end.offset)
        val selectionEnd = maxOf(start.offset, end.offset)
        if (start.itemId != null || end.itemId != null) {
            if (start.itemId == null || start.itemId != end.itemId) return firstCursor()
            val itemId = start.itemId
            when (block) {
                is BulletListBlock, is NumberedListBlock -> {
                    val items = block.listItems() ?: return firstCursor()
                    val index = items.indexOfFirst { it.id == itemId }
                    if (index < 0) return firstCursor()
                    val item = items[index]
                    val before = splitInline(item.content, selectionStart.coerceIn(0, item.content.plainText().length)).first
                    val after = splitInline(item.content, selectionEnd.coerceIn(0, item.content.plainText().length)).second
                    val updated = items.toMutableList()
                    updated[index] = item.copy(content = (before + replacement + after).mergeAdjacentText())
                    replaceListItems(block, updated)
                    return BlockCursor(block.id, before.plainText().length + replacement.plainText().length, itemId)
                }
                is TodoListBlock -> {
                    val index = block.items.indexOfFirst { it.id == itemId }
                    if (index < 0) return firstCursor()
                    val item = block.items[index]
                    val before = splitInline(item.content, selectionStart.coerceIn(0, item.content.plainText().length)).first
                    val after = splitInline(item.content, selectionEnd.coerceIn(0, item.content.plainText().length)).second
                    val updated = block.items.toMutableList()
                    updated[index] = item.copy(content = (before + replacement + after).mergeAdjacentText())
                    replaceBlock(block.copy(items = updated))
                    return BlockCursor(block.id, before.plainText().length + replacement.plainText().length, itemId)
                }
                else -> return firstCursor()
            }
        }
        val inline = block.editableInline() ?: return firstCursor()
        val before = splitInline(inline, selectionStart.coerceIn(0, inline.plainText().length)).first
        val after = splitInline(inline, selectionEnd.coerceIn(0, inline.plainText().length)).second
        val updated = block.withInline((before + replacement + after).mergeAdjacentText())
        replaceBlock(updated)
        return BlockCursor(block.id, before.plainText().length + replacement.plainText().length)
    }

    fun inlineStyle(start: BlockCursor, end: BlockCursor = start): InlineTypingStyle {
        if (start.blockId != end.blockId || start.itemId != end.itemId) return InlineTypingStyle()
        val content = document.findById(start.blockId)?.editableInline(start.itemId) ?: return InlineTypingStyle()
        return content.styleForRange(start.offset, end.offset)
    }

    fun toggleInlineMark(start: BlockCursor, end: BlockCursor, mark: InlineMark): BlockCursor {
        if (start.blockId != end.blockId || start.itemId != end.itemId || start.offset == end.offset) return end
        val active = mark in inlineStyle(start, end).marks
        return transformInlineSelection(start, end) { selected ->
            when {
                active -> selected.removeMark(mark)
                mark == InlineMark.Code -> selected.applyMark(mark)
                else -> selected.removeMark(InlineMark.Code).applyMark(mark)
            }
        }
    }

    fun setInlineHighlight(start: BlockCursor, end: BlockCursor, color: String?): BlockCursor {
        if (start.blockId != end.blockId || start.itemId != end.itemId || start.offset == end.offset) return end
        val active = inlineStyle(start, end).highlight
        val next = color.takeUnless { it == active }
        return transformInlineSelection(start, end) { selected ->
            selected.removeMark(InlineMark.Code).withHighlight(next)
        }
    }

    /** Removes visual character formatting while preserving semantic inline content such as links. */
    fun clearInlineFormatting(start: BlockCursor, end: BlockCursor): BlockCursor {
        if (start.blockId != end.blockId || start.itemId != end.itemId || start.offset == end.offset) return end
        return transformInlineSelection(start, end) { selected -> selected.clearVisualFormatting() }
    }

    fun setInlineFontFamily(start: BlockCursor, end: BlockCursor, family: String?): BlockCursor {
        if (start.blockId != end.blockId || start.itemId != end.itemId || start.offset == end.offset) return end
        return transformInlineSelection(start, end) { selected -> selected.withFontFamily(family) }
    }

    fun setInlineFontSize(start: BlockCursor, end: BlockCursor, sizeSp: Float?): BlockCursor {
        if (start.blockId != end.blockId || start.itemId != end.itemId || start.offset == end.offset) return end
        val normalized = sizeSp?.coerceIn(6f, 144f)
        return transformInlineSelection(start, end) { selected -> selected.withFontSize(normalized) }
    }

    private fun transformInlineSelection(
        start: BlockCursor,
        end: BlockCursor,
        transform: (List<InlineNode>) -> List<InlineNode>,
    ): BlockCursor {
        val block = document.findById(start.blockId) ?: return end
        val selectionStart = minOf(start.offset, end.offset)
        val selectionEnd = maxOf(start.offset, end.offset)
        val itemId = start.itemId

        fun updated(content: List<InlineNode>): List<InlineNode> {
            val before = splitInline(content, selectionStart.coerceIn(0, content.plainText().length)).first
            val selected = splitInline(content, selectionStart.coerceIn(0, content.plainText().length)).second
                .let { splitInline(it, (selectionEnd - selectionStart).coerceAtLeast(0)).first }
            val after = splitInline(content, selectionEnd.coerceIn(0, content.plainText().length)).second
            return (before + transform(selected) + after).mergeAdjacentText()
        }

        when {
            itemId == null -> {
                val content = block.editableInline() ?: return end
                replaceBlock(block.withInline(updated(content)))
            }
            block is BulletListBlock || block is NumberedListBlock -> {
                val items = block.listItems() ?: return end
                val index = items.indexOfFirst { it.id == itemId }
                if (index < 0) return end
                val next = items.toMutableList()
                next[index] = next[index].copy(content = updated(next[index].content))
                replaceListItems(block, next)
            }
            block is TodoListBlock -> {
                val index = block.items.indexOfFirst { it.id == itemId }
                if (index < 0) return end
                val next = block.items.toMutableList()
                next[index] = next[index].copy(content = updated(next[index].content))
                replaceBlock(block.copy(items = next))
            }
            else -> return end
        }
        return end.copy(offset = selectionEnd)
    }

    fun replaceSelectionWithBlocks(start: BlockCursor, end: BlockCursor, inserted: BlockDocument): BlockCursor {
        val s = document.blocks.locate(start.blockId) ?: return firstCursor()
        val e = document.blocks.locate(end.blockId) ?: return firstCursor()
        if (s.first != e.first || e.second < s.second) return firstCursor()
        val parentId = s.first
        val siblings = s.third
        val startIndex = s.second
        val endIndex = e.second
        val first = siblings[startIndex]
        val last = siblings[endIndex]
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

        val removedIds = siblings.subList(startIndex, endIndex + 1).mapTo(linkedSetOf()) { it.id } -
            replacement.mapTo(hashSetOf()) { it.id }
        markRemoved(removedIds)
        mutate(replacement.mapTo(linkedSetOf()) { it.id }) { blocks ->
            blocks.updateChildList(parentId) { list ->
                list.toMutableList().apply {
                    repeat(endIndex - startIndex + 1) { removeAt(startIndex) }
                    addAll(startIndex, replacement)
                }
            }
        }
        return when {
            continuation != null -> BlockCursor(continuation.id, 0)
            imported.isNotEmpty() -> BlockCursor(imported.last().id, imported.last().plainText().length)
            else -> BlockCursor(replacement.last().id, replacement.last().plainText().length)
        }
    }

    // --- Explicit container structuring (toolbar / block-actions menu, non-drag paths) ---

    /** Wraps [blockId] in a new single-child container of [axis]. */
    fun wrapInContainer(blockId: String, axis: ContainerAxis) {
        val block = document.findById(blockId) ?: return
        val wasTopLevel = document.blocks.any { it.id == blockId }
        val container = ContainerBlock(axis = axis, children = listOf(block), weights = listOf(1f))
        mutate(setOf(container.id)) { blocks -> blocks.updateBlock(blockId) { container } }
        if (wasTopLevel) deletedBlockIds += blockId
        document.rootAncestorId(container.id)?.let { dirtyBlockIds += it }
    }

    /** Replaces a container with its children inline in the container's parent list. */
    fun unwrap(containerId: String) {
        val container = document.findById(containerId) as? ContainerBlock ?: return
        val located = document.blocks.locate(containerId) ?: return
        val (parentId, index, _) = located
        val wasTopLevel = parentId == null
        mutate(container.children.mapTo(linkedSetOf()) { it.id }) { blocks ->
            blocks.updateChildList(parentId) { list ->
                list.toMutableList().apply {
                    removeAt(index)
                    addAll(index, container.children)
                }
            }
        }
        if (wasTopLevel) {
            deletedBlockIds += containerId
            container.children.forEach { child -> document.rootAncestorId(child.id)?.let { dirtyBlockIds += it } }
        } else {
            parentId?.let { document.rootAncestorId(it) }?.let { dirtyBlockIds += it }
        }
    }

    fun setAxis(containerId: String, axis: ContainerAxis) {
        val container = document.findById(containerId) as? ContainerBlock ?: return
        replaceBlock(container.copy(axis = axis))
    }

    fun setWeights(containerId: String, weights: List<Float>) {
        val container = document.findById(containerId) as? ContainerBlock ?: return
        replaceBlock(container.copy(weights = weights))
    }

    fun addChild(containerId: String, block: DocumentBlock): BlockCursor {
        val container = document.findById(containerId) as? ContainerBlock ?: return firstCursor()
        replaceBlock(container.copy(children = container.children + block, weights = container.weights + 1f))
        return BlockCursor(block.id, 0)
    }

    /** "Clip / cut out as a separate block" — hoists [blockId] to top level after its owning row. */
    fun extractToTopLevel(blockId: String) {
        val root = document.rootAncestorId(blockId) ?: return
        if (root == blockId) return
        val rootIndex = document.blocks.indexOfFirst { it.id == root }
        moveInto(blockId, parentId = null, index = rootIndex + 1)
    }

    // --- Sidebar ToC section operations (top-level heading + its following blocks) ---

    /** Removes a whole section. No-op if [headingId] is not a top-level heading. */
    fun deleteSection(headingId: String): Boolean {
        val range = DocSections.rangeFor(document, headingId) ?: return false
        val removed = document.blocks.slice(range)
        markRemoved(removed.map { it.id })
        mutate(emptySet()) { blocks -> blocks.filterIndexed { i, _ -> i !in range } }
        return true
    }

    /** Duplicates a section (fresh ids) directly after itself. */
    fun duplicateSection(headingId: String): Boolean {
        val range = DocSections.rangeFor(document, headingId) ?: return false
        val copies = document.blocks.slice(range).map { it.withFreshIds() }
        mutate(copies.mapTo(linkedSetOf()) { it.id }) { blocks ->
            blocks.subList(0, range.last + 1) + copies + blocks.subList(range.last + 1, blocks.size)
        }
        return true
    }

    /**
     * Moves a whole section so it starts where [targetHeadingId]'s section starts (or to the end of
     * the document when null). Both ids must be top-level headings; no-op otherwise or when the
     * target sits inside the moving section.
     */
    fun moveSectionBefore(headingId: String, targetHeadingId: String?): Boolean {
        val range = DocSections.rangeFor(document, headingId) ?: return false
        val insertAt = if (targetHeadingId == null) document.blocks.size else {
            val targetRange = DocSections.rangeFor(document, targetHeadingId) ?: return false
            if (targetRange.first in range) return false
            targetRange.first
        }
        if (insertAt in range.first..(range.last + 1)) return false
        val section = document.blocks.slice(range)
        mutate(document.blocks.mapTo(linkedSetOf()) { it.id }) { blocks ->
            val without = blocks.filterIndexed { i, _ -> i !in range }
            val adjusted = if (insertAt > range.last) insertAt - section.size else insertAt
            without.subList(0, adjusted) + section + without.subList(adjusted, without.size)
        }
        return true
    }

    fun undo(): Boolean {
        val previous = undo.removeLastOrNull() ?: return false
        breakTypingGroup()
        redo += document
        document = previous
        dirtyBlockIds += document.blocks.map { it.id }
        return true
    }

    fun redo(): Boolean {
        val next = redo.removeLastOrNull() ?: return false
        breakTypingGroup()
        undo += document
        document = next
        dirtyBlockIds += document.blocks.map { it.id }
        return true
    }

    fun markSaved() {
        dirtyBlockIds.clear()
        deletedBlockIds.clear()
    }

    /**
     * Applies a server snapshot without overwriting locally dirty top-level blocks. Clean remote
     * order is authoritative; locally inserted/edited blocks are retained and appended when the
     * remote snapshot does not yet know them. This is block-safe convergence, not character-level
     * CRDT merging: simultaneous edits to the same block remain last-saved-writer wins.
     */
    fun mergeRemote(remote: BlockDocument): Boolean {
        val normalizedRemote = remote.normalized()
        val localById = document.blocks.associateBy(DocumentBlock::id)
        val dirty = dirtyBlockIds.toSet()
        val merged = buildList {
            normalizedRemote.blocks.forEach { remoteBlock ->
                when {
                    remoteBlock.id in deletedBlockIds -> Unit
                    remoteBlock.id in dirty -> add(localById[remoteBlock.id] ?: remoteBlock)
                    else -> add(remoteBlock)
                }
            }
            document.blocks.forEach { localBlock ->
                if (localBlock.id in dirty && none { it.id == localBlock.id }) add(localBlock)
            }
        }
        val next = BlockDocument(merged).normalized()
        if (next == document) return false
        document = next
        undo.clear()
        redo.clear()
        breakTypingGroup()
        return true
    }

    private fun mutate(
        dirty: Set<String>,
        typingKey: String? = null,
        transform: (List<DocumentBlock>) -> List<DocumentBlock>,
    ) {
        val next = document.copy(blocks = transform(document.blocks)).normalized()
        if (next == document) return
        val now = System.nanoTime()
        val coalesce = typingKey != null && typingKey == lastTypingKey && now - lastTypingAtNanos <= 1_200_000_000L
        if (!coalesce) undo += document
        redo.clear()
        lastTypingKey = typingKey
        lastTypingAtNanos = if (typingKey == null) 0L else now
        val roots = dirty.mapNotNullTo(linkedSetOf()) { next.rootAncestorId(it) }
        document = next
        dirtyBlockIds += roots
    }

    /** Mutates the sibling list that directly contains [anchorId] (top level or a container's children). */
    private fun mutateSiblings(
        anchorId: String,
        dirty: Set<String>,
        transform: (siblings: List<DocumentBlock>, index: Int, parentId: String?) -> List<DocumentBlock>,
    ) {
        val (parentId, index, siblings) = document.blocks.locate(anchorId) ?: return
        val updated = transform(siblings, index, parentId)
        mutate(dirty) { blocks -> blocks.updateChildList(parentId) { updated } }
    }

    /**
     * Records removed blocks against persistence: a top-level block deletes its Room row; a nested block
     * just dirties its top-level ancestor. Must be called while [document] still contains the ids.
     */
    private fun markRemoved(ids: Collection<String>) {
        val topLevel = document.blocks.mapTo(hashSetOf()) { it.id }
        ids.forEach { id ->
            if (id in topLevel) deletedBlockIds += id
            else document.rootAncestorId(id)?.let { dirtyBlockIds += it }
        }
    }

    /** True if [maybeChild] is a descendant of the container [container] (guards moving a node into itself). */
    private fun isDescendant(container: String, maybeChild: String): Boolean {
        val path = document.pathOf(maybeChild)?.ids ?: return false
        return container in path.dropLast(1)
    }

    private fun replaceBlock(block: DocumentBlock, typingKey: String) = mutate(setOf(block.id), typingKey) { blocks ->
        blocks.updateBlock(block.id) { block }
    }

    private fun replaceListItems(block: DocumentBlock, items: List<ListItem>, typingKey: String? = null) {
        if (typingKey == null) replaceBlock(block.withListItems(items))
        else replaceBlock(block.withListItems(items), typingKey)
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

private fun editInline(
    content: List<InlineNode>,
    oldText: String,
    newText: String,
    typingStyle: InlineTypingStyle? = null,
): List<InlineNode> {
    val prefix = oldText.commonPrefixWith(newText).length
    val suffixLimit = minOf(oldText.length - prefix, newText.length - prefix)
    var suffix = 0
    while (suffix < suffixLimit && oldText[oldText.lastIndex - suffix] == newText[newText.lastIndex - suffix]) suffix++
    val before = splitInline(content, prefix).first
    val after = splitInline(content, oldText.length - suffix).second
    val replacement = newText.substring(prefix, newText.length - suffix)
    val style = typingStyle ?: content.typingStyleAt(prefix)
    return buildList {
        addAll(before)
        addAll(replacement.asStyledInline(style))
        addAll(after)
    }.mergeAdjacentText()
}

private fun String.asStyledInline(style: InlineTypingStyle): List<InlineNode> {
    if (isEmpty()) return emptyList()
    var node: InlineNode = if (InlineMark.Code in style.marks) CodeInline(this) else InlineText(this)
    if (InlineMark.Bold in style.marks) node = BoldInline(listOf(node))
    if (InlineMark.Italic in style.marks) node = ItalicInline(listOf(node))
    if (InlineMark.Underline in style.marks) node = UnderlineInline(listOf(node))
    if (InlineMark.Strikethrough in style.marks) node = StrikethroughInline(listOf(node))
    style.highlight?.let { node = HighlightInline(it, listOf(node)) }
    style.fontFamily?.takeIf(String::isNotBlank)?.let { node = FontFamilyInline(it, listOf(node)) }
    style.fontSizeSp?.let { node = FontSizeInline(it.coerceIn(6f, 144f), listOf(node)) }
    return listOf(node)
}

private data class InlineStyleRun(val start: Int, val end: Int, val style: InlineTypingStyle)

private fun List<InlineNode>.styleRuns(
    inherited: InlineTypingStyle = InlineTypingStyle(),
    startOffset: Int = 0,
    output: MutableList<InlineStyleRun> = mutableListOf(),
): List<InlineStyleRun> {
    var offset = startOffset
    forEach { node ->
        when (node) {
            is BoldInline -> node.children.styleRuns(inherited.copy(marks = inherited.marks + InlineMark.Bold), offset, output)
            is ItalicInline -> node.children.styleRuns(inherited.copy(marks = inherited.marks + InlineMark.Italic), offset, output)
            is UnderlineInline -> node.children.styleRuns(inherited.copy(marks = inherited.marks + InlineMark.Underline), offset, output)
            is StrikethroughInline -> node.children.styleRuns(inherited.copy(marks = inherited.marks + InlineMark.Strikethrough), offset, output)
            is HighlightInline -> node.children.styleRuns(inherited.copy(highlight = node.color), offset, output)
            is FontFamilyInline -> node.children.styleRuns(inherited.copy(fontFamily = node.family), offset, output)
            is FontSizeInline -> node.children.styleRuns(inherited.copy(fontSizeSp = node.sizeSp), offset, output)
            is CodeInline -> output += InlineStyleRun(offset, offset + node.plainText().length, inherited.copy(marks = inherited.marks + InlineMark.Code))
            is LinkInline -> node.children.styleRuns(inherited, offset, output)
            else -> output += InlineStyleRun(offset, offset + node.plainText().length, inherited)
        }
        offset += node.plainText().length
    }
    return output
}

private fun List<InlineNode>.typingStyleAt(offset: Int): InlineTypingStyle {
    val length = plainText().length
    if (length == 0) return InlineTypingStyle()
    val probe = if (offset > 0) (offset - 1).coerceAtMost(length - 1) else 0
    return styleRuns().firstOrNull { probe in it.start until it.end }?.style ?: InlineTypingStyle()
}

private fun List<InlineNode>.styleForRange(rawStart: Int, rawEnd: Int): InlineTypingStyle {
    val length = plainText().length
    val start = minOf(rawStart, rawEnd).coerceIn(0, length)
    val end = maxOf(rawStart, rawEnd).coerceIn(0, length)
    if (start == end) return typingStyleAt(start)
    val styles = styleRuns().filter { it.end > start && it.start < end }.map { it.style }
    if (styles.isEmpty()) return InlineTypingStyle()
    return InlineTypingStyle(
        marks = styles.drop(1).fold(styles.first().marks) { common, style -> common intersect style.marks },
        highlight = styles.first().highlight.takeIf { color -> styles.all { it.highlight == color } },
        fontFamily = styles.first().fontFamily.takeIf { family -> styles.all { it.fontFamily == family } },
        fontSizeSp = styles.first().fontSizeSp.takeIf { size -> styles.all { it.fontSizeSp == size } },
    )
}

private fun List<InlineNode>.applyMark(mark: InlineMark): List<InlineNode> {
    if (isEmpty()) return this
    return when (mark) {
        InlineMark.Bold -> listOf(BoldInline(this))
        InlineMark.Italic -> listOf(ItalicInline(this))
        InlineMark.Underline -> listOf(UnderlineInline(this))
        InlineMark.Strikethrough -> listOf(StrikethroughInline(this))
        InlineMark.Code -> listOf(CodeInline(plainText()))
    }
}

private fun List<InlineNode>.removeMark(mark: InlineMark): List<InlineNode> = flatMap { node ->
    when (node) {
        is BoldInline -> if (mark == InlineMark.Bold) node.children.removeMark(mark) else listOf(node.copy(children = node.children.removeMark(mark)))
        is ItalicInline -> if (mark == InlineMark.Italic) node.children.removeMark(mark) else listOf(node.copy(children = node.children.removeMark(mark)))
        is UnderlineInline -> if (mark == InlineMark.Underline) node.children.removeMark(mark) else listOf(node.copy(children = node.children.removeMark(mark)))
        is StrikethroughInline -> if (mark == InlineMark.Strikethrough) node.children.removeMark(mark) else listOf(node.copy(children = node.children.removeMark(mark)))
        is HighlightInline -> listOf(node.copy(children = node.children.removeMark(mark)))
        is FontFamilyInline -> listOf(node.copy(children = node.children.removeMark(mark)))
        is FontSizeInline -> listOf(node.copy(children = node.children.removeMark(mark)))
        is LinkInline -> listOf(node.copy(children = node.children.removeMark(mark)))
        is CodeInline -> if (mark == InlineMark.Code) listOf(InlineText(node.value)) else listOf(node)
        else -> listOf(node)
    }
}.mergeAdjacentText()

private fun List<InlineNode>.withHighlight(color: String?): List<InlineNode> {
    fun strip(nodes: List<InlineNode>): List<InlineNode> = nodes.flatMap { node ->
        when (node) {
            is HighlightInline -> strip(node.children)
            is BoldInline -> listOf(node.copy(children = strip(node.children)))
            is ItalicInline -> listOf(node.copy(children = strip(node.children)))
            is UnderlineInline -> listOf(node.copy(children = strip(node.children)))
            is StrikethroughInline -> listOf(node.copy(children = strip(node.children)))
            is FontFamilyInline -> listOf(node.copy(children = strip(node.children)))
            is FontSizeInline -> listOf(node.copy(children = strip(node.children)))
            is LinkInline -> listOf(node.copy(children = strip(node.children)))
            else -> listOf(node)
        }
    }
    val stripped = strip(this).mergeAdjacentText()
    return if (color == null || stripped.isEmpty()) stripped else listOf(HighlightInline(color, stripped))
}

private fun List<InlineNode>.withFontFamily(family: String?): List<InlineNode> {
    fun strip(nodes: List<InlineNode>): List<InlineNode> = nodes.flatMap { node ->
        when (node) {
            is FontFamilyInline -> strip(node.children)
            is BoldInline -> listOf(node.copy(children = strip(node.children)))
            is ItalicInline -> listOf(node.copy(children = strip(node.children)))
            is UnderlineInline -> listOf(node.copy(children = strip(node.children)))
            is StrikethroughInline -> listOf(node.copy(children = strip(node.children)))
            is HighlightInline -> listOf(node.copy(children = strip(node.children)))
            is FontSizeInline -> listOf(node.copy(children = strip(node.children)))
            is LinkInline -> listOf(node.copy(children = strip(node.children)))
            else -> listOf(node)
        }
    }
    val stripped = strip(this).mergeAdjacentText()
    return if (family.isNullOrBlank() || stripped.isEmpty()) stripped else listOf(FontFamilyInline(family, stripped))
}

private fun List<InlineNode>.withFontSize(sizeSp: Float?): List<InlineNode> {
    fun strip(nodes: List<InlineNode>): List<InlineNode> = nodes.flatMap { node ->
        when (node) {
            is FontSizeInline -> strip(node.children)
            is BoldInline -> listOf(node.copy(children = strip(node.children)))
            is ItalicInline -> listOf(node.copy(children = strip(node.children)))
            is UnderlineInline -> listOf(node.copy(children = strip(node.children)))
            is StrikethroughInline -> listOf(node.copy(children = strip(node.children)))
            is HighlightInline -> listOf(node.copy(children = strip(node.children)))
            is FontFamilyInline -> listOf(node.copy(children = strip(node.children)))
            is LinkInline -> listOf(node.copy(children = strip(node.children)))
            else -> listOf(node)
        }
    }
    val stripped = strip(this).mergeAdjacentText()
    return if (sizeSp == null || stripped.isEmpty()) stripped else listOf(FontSizeInline(sizeSp.coerceIn(6f, 144f), stripped))
}

private fun List<InlineNode>.clearVisualFormatting(): List<InlineNode> = flatMap { node ->
    when (node) {
        is BoldInline -> node.children.clearVisualFormatting()
        is ItalicInline -> node.children.clearVisualFormatting()
        is UnderlineInline -> node.children.clearVisualFormatting()
        is StrikethroughInline -> node.children.clearVisualFormatting()
        is HighlightInline -> node.children.clearVisualFormatting()
        is FontFamilyInline -> node.children.clearVisualFormatting()
        is FontSizeInline -> node.children.clearVisualFormatting()
        is CodeInline -> listOf(InlineText(node.value))
        is LinkInline -> listOf(node.copy(children = node.children.clearVisualFormatting()))
        else -> listOf(node)
    }
}.mergeAdjacentText()

private fun List<InlineNode>.mergeAdjacentText(): List<InlineNode> {
    fun normalized(node: InlineNode): InlineNode? = when (node) {
        is InlineText -> node.takeUnless { it.value.isEmpty() }
        is CodeInline -> node.takeUnless { it.value.isEmpty() }
        is BoldInline -> node.children.mergeAdjacentText().let { children ->
            when {
                children.isEmpty() -> null
                children.size == 1 && children.first() is BoldInline -> children.first()
                else -> node.copy(children = children)
            }
        }
        is ItalicInline -> node.children.mergeAdjacentText().let { children ->
            when {
                children.isEmpty() -> null
                children.size == 1 && children.first() is ItalicInline -> children.first()
                else -> node.copy(children = children)
            }
        }
        is UnderlineInline -> node.children.mergeAdjacentText().let { children ->
            when {
                children.isEmpty() -> null
                children.size == 1 && children.first() is UnderlineInline -> children.first()
                else -> node.copy(children = children)
            }
        }
        is StrikethroughInline -> node.children.mergeAdjacentText().let { children ->
            when {
                children.isEmpty() -> null
                children.size == 1 && children.first() is StrikethroughInline -> children.first()
                else -> node.copy(children = children)
            }
        }
        is HighlightInline -> node.children.mergeAdjacentText().let { children ->
            when {
                children.isEmpty() -> null
                children.size == 1 && (children.first() as? HighlightInline)?.color == node.color -> children.first()
                else -> node.copy(children = children)
            }
        }
        is FontFamilyInline -> node.children.mergeAdjacentText().let { children ->
            when {
                children.isEmpty() -> null
                children.size == 1 && (children.first() as? FontFamilyInline)?.family == node.family -> children.first()
                else -> node.copy(children = children)
            }
        }
        is FontSizeInline -> node.children.mergeAdjacentText().let { children ->
            when {
                children.isEmpty() -> null
                children.size == 1 && (children.first() as? FontSizeInline)?.sizeSp == node.sizeSp -> children.first()
                else -> node.copy(children = children)
            }
        }
        is LinkInline -> node.children.mergeAdjacentText().takeIf { it.isNotEmpty() }?.let { node.copy(children = it) }
        else -> node
    }

    val result = mutableListOf<InlineNode>()
    for (raw in this) {
        val node = normalized(raw) ?: continue
        val previous = result.lastOrNull()
        val merged: InlineNode? = when {
            previous is InlineText && node is InlineText -> InlineText(previous.value + node.value)
            previous is CodeInline && node is CodeInline -> CodeInline(previous.value + node.value)
            previous is BoldInline && node is BoldInline -> BoldInline((previous.children + node.children).mergeAdjacentText())
            previous is ItalicInline && node is ItalicInline -> ItalicInline((previous.children + node.children).mergeAdjacentText())
            previous is UnderlineInline && node is UnderlineInline -> UnderlineInline((previous.children + node.children).mergeAdjacentText())
            previous is StrikethroughInline && node is StrikethroughInline -> StrikethroughInline((previous.children + node.children).mergeAdjacentText())
            previous is HighlightInline && node is HighlightInline && previous.color == node.color -> HighlightInline(previous.color, (previous.children + node.children).mergeAdjacentText())
            previous is FontFamilyInline && node is FontFamilyInline && previous.family == node.family -> FontFamilyInline(previous.family, (previous.children + node.children).mergeAdjacentText())
            previous is FontSizeInline && node is FontSizeInline && previous.sizeSp == node.sizeSp -> FontSizeInline(previous.sizeSp, (previous.children + node.children).mergeAdjacentText())
            previous is LinkInline && node is LinkInline && previous.url == node.url -> LinkInline(previous.url, (previous.children + node.children).mergeAdjacentText())
            else -> null
        }
        if (merged == null) result += node else result[result.lastIndex] = merged
    }
    return result
}

private fun DocumentBlock.editableInline(): List<InlineNode>? = when (this) {
    is ParagraphBlock -> content
    is HeadingBlock -> content
    is QuoteBlock -> children.firstOrNull()?.let { child ->
        when (child) {
            is ParagraphBlock -> child.content
            is HeadingBlock -> child.content
            else -> null
        }
    } ?: emptyList()
    else -> null
}

private fun DocumentBlock.editableInline(itemId: String?): List<InlineNode>? = when {
    itemId == null -> editableInline()
    this is BulletListBlock -> items.firstOrNull { it.id == itemId }?.content
    this is NumberedListBlock -> items.firstOrNull { it.id == itemId }?.content
    this is TodoListBlock -> items.firstOrNull { it.id == itemId }?.content
    else -> null
}

private fun DocumentBlock.withInline(content: List<InlineNode>, newId: Boolean = false): DocumentBlock = when (this) {
    is ParagraphBlock -> copy(id = if (newId) ParagraphBlock().id else id, content = content)
    is HeadingBlock -> copy(id = if (newId) HeadingBlock().id else id, content = content)
    is QuoteBlock -> copy(
        id = if (newId) QuoteBlock().id else id,
        children = listOf(ParagraphBlock(content = content)) + children.drop(1),
    )
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
    is ContainerBlock -> copy(id = nextId)
    is DividerBlock -> copy(id = nextId)
    is CodeBlock -> copy(id = nextId)
    is TableBlock -> copy(id = nextId)
    is ImageBlock -> copy(id = nextId)
    is FileBlock -> copy(id = nextId)
    is EmbedBlock -> copy(id = nextId)
    is ChartBlock -> copy(id = nextId)
    is MathBlock -> copy(id = nextId)
    is MermaidBlock -> copy(id = nextId)
    is ShapeBlock -> copy(id = nextId)
    is DocumentPageBlock -> copy(id = nextId)
    is DocumentSettingsBlock -> copy(id = nextId)
    is StylesheetBlock -> copy(id = nextId)
    is RelationshipDataBlock -> this
    is UnknownBlock -> BlockDocumentJson.reidentify(this, nextId)
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
    is UnderlineInline -> splitInline(node.children, offset).let { (a, b) -> a.takeIf(List<InlineNode>::isNotEmpty)?.let(::UnderlineInline) to b.takeIf(List<InlineNode>::isNotEmpty)?.let(::UnderlineInline) }
    is HighlightInline -> splitInline(node.children, offset).let { (a, b) -> a.takeIf(List<InlineNode>::isNotEmpty)?.let { HighlightInline(node.color, it) } to b.takeIf(List<InlineNode>::isNotEmpty)?.let { HighlightInline(node.color, it) } }
    is FontFamilyInline -> splitInline(node.children, offset).let { (a, b) -> a.takeIf(List<InlineNode>::isNotEmpty)?.let { FontFamilyInline(node.family, it) } to b.takeIf(List<InlineNode>::isNotEmpty)?.let { FontFamilyInline(node.family, it) } }
    is FontSizeInline -> splitInline(node.children, offset).let { (a, b) -> a.takeIf(List<InlineNode>::isNotEmpty)?.let { FontSizeInline(node.sizeSp, it) } to b.takeIf(List<InlineNode>::isNotEmpty)?.let { FontSizeInline(node.sizeSp, it) } }
    is LinkInline -> splitInline(node.children, offset).let { (a, b) -> a.takeIf(List<InlineNode>::isNotEmpty)?.let { LinkInline(node.url, it) } to b.takeIf(List<InlineNode>::isNotEmpty)?.let { LinkInline(node.url, it) } }
    is EmojiInline, is TagInline, is MentionInline -> if (offset <= 0) null to node else node to null
}
