package com.norfold.app.domain

/**
 * Recursion utilities for the nested block tree. The document is a `List<DocumentBlock>` where any
 * [ContainerBlock] may hold further children (including other containers), so structural editing must
 * address blocks by a **path of ids** (container ancestors then the target id) rather than a flat index.
 *
 * These are the single source of tree recursion, reused by [BlockEditorSession] and the editor UI.
 * All operations are immutable — they return new lists/blocks and never mutate in place.
 */

/** Address of a block within the tree: container-ancestor ids followed by the block's own id. */
data class BlockPath(val ids: List<String>) {
    val targetId: String get() = ids.last()
    val parentId: String? get() = ids.getOrNull(ids.size - 2)
    val rootId: String? get() = ids.firstOrNull()
}

/** This block's children if it is a container, else null. */
fun DocumentBlock.containerChildren(): List<DocumentBlock>? = (this as? ContainerBlock)?.children

/** Copy of this block with replaced children (only [ContainerBlock] carries children; others unchanged). */
fun DocumentBlock.withChildren(children: List<DocumentBlock>): DocumentBlock =
    if (this is ContainerBlock) copy(children = children) else this

/** Depth-first path (ancestor container ids + target id) to [id], or null if absent. */
fun BlockDocument.pathOf(id: String): BlockPath? = blocks.pathOf(id, emptyList())

private fun List<DocumentBlock>.pathOf(id: String, prefix: List<String>): BlockPath? {
    for (block in this) {
        val here = prefix + block.id
        if (block.id == id) return BlockPath(here)
        val nested = block.containerChildren()?.pathOf(id, here)
        if (nested != null) return nested
    }
    return null
}

/** The top-level block id that owns [id] (itself if [id] is top-level), or null if not found. */
fun BlockDocument.rootAncestorId(id: String): String? = pathOf(id)?.rootId

/** Every block id in document order, including nested children. */
fun BlockDocument.allIds(): List<String> = buildList { blocks.forEach { it.collectIds(this) } }

private fun DocumentBlock.collectIds(sink: MutableList<String>) {
    sink += id
    containerChildren()?.forEach { it.collectIds(sink) }
}

/** Walks every block depth-first (parents before their children). */
fun BlockDocument.walk(visit: (DocumentBlock) -> Unit) = blocks.forEach { it.walkTree(visit) }

private fun DocumentBlock.walkTree(visit: (DocumentBlock) -> Unit) {
    visit(this)
    containerChildren()?.forEach { it.walkTree(visit) }
}

/** The block with [id] anywhere in the tree, or null. */
fun BlockDocument.findById(id: String): DocumentBlock? = blocks.findById(id)

fun List<DocumentBlock>.findById(id: String): DocumentBlock? {
    for (block in this) {
        if (block.id == id) return block
        val nested = block.containerChildren()?.findById(id)
        if (nested != null) return nested
    }
    return null
}

/** Replaces the block with [id] anywhere in the tree via [transform]. Returns a new list. */
fun List<DocumentBlock>.updateBlock(id: String, transform: (DocumentBlock) -> DocumentBlock): List<DocumentBlock> =
    map { block ->
        when {
            block.id == id -> transform(block)
            else -> block.containerChildren()
                ?.let { block.withChildren(it.updateBlock(id, transform)) }
                ?: block
        }
    }

/** Removes the block with [id] anywhere. Returns the new list paired with the removed block (or null). */
fun List<DocumentBlock>.removeBlock(id: String): Pair<List<DocumentBlock>, DocumentBlock?> {
    var removed: DocumentBlock? = null
    fun recurse(list: List<DocumentBlock>): List<DocumentBlock> = buildList {
        for (block in list) {
            if (block.id == id) { removed = block; continue }
            val kids = block.containerChildren()
            add(if (kids != null) block.withChildren(recurse(kids)) else block)
        }
    }
    return recurse(this) to removed
}

/**
 * Updates the sibling list that is either the top level ([parentId] == null) or a container's children.
 * No-op if [parentId] names a non-container or missing block.
 */
fun List<DocumentBlock>.updateChildList(
    parentId: String?,
    transform: (List<DocumentBlock>) -> List<DocumentBlock>,
): List<DocumentBlock> =
    if (parentId == null) transform(this)
    else updateBlock(parentId) { block ->
        block.containerChildren()?.let { block.withChildren(transform(it)) } ?: block
    }

/** Inserts [blocks] into [parentId]'s child list (top level if null) at [index] (clamped). */
fun List<DocumentBlock>.insertInto(parentId: String?, index: Int, blocks: List<DocumentBlock>): List<DocumentBlock> =
    updateChildList(parentId) { siblings ->
        val at = index.coerceIn(0, siblings.size)
        siblings.subList(0, at) + blocks + siblings.subList(at, siblings.size)
    }

/**
 * Locates the sibling list directly containing [id]: its parent id (null = top level), the index of
 * [id] within that list, and the list itself. Null if [id] is absent.
 */
fun List<DocumentBlock>.locate(id: String): Triple<String?, Int, List<DocumentBlock>>? {
    val topIndex = indexOfFirst { it.id == id }
    if (topIndex >= 0) return Triple(null, topIndex, this)
    for (block in this) {
        val kids = block.containerChildren() ?: continue
        val idx = kids.indexOfFirst { it.id == id }
        if (idx >= 0) return Triple(block.id, idx, kids)
        val nested = kids.locate(id)
        if (nested != null) return nested
    }
    return null
}
