package com.norfold.app.domain

/**
 * Merges a server snapshot with the blocks changed in the current editor save. Remote clean blocks
 * win; locally dirty blocks win. A locally dirty id absent from [local] is a deletion. This avoids
 * whole-document loss when collaborators edit different blocks, while same-block concurrent edits
 * intentionally remain last-saved-writer wins until a character CRDT is introduced.
 */
fun mergeSharedDocumentBlocks(
    remote: BlockDocument,
    local: BlockDocument,
    locallyDirtyBlockIds: Set<String>,
): BlockDocument {
    val normalizedRemote = remote.normalized()
    val normalizedLocal = local.normalized()
    if (locallyDirtyBlockIds.isEmpty()) return normalizedRemote
    val remoteById = normalizedRemote.blocks.associateBy(DocumentBlock::id)
    val localById = normalizedLocal.blocks.associateBy(DocumentBlock::id)
    val merged = buildList {
        normalizedLocal.blocks.forEach { localBlock ->
            when {
                localBlock.id in locallyDirtyBlockIds -> add(localBlock)
                remoteById[localBlock.id] != null -> add(requireNotNull(remoteById[localBlock.id]))
                // The server removed this clean local block.
            }
        }
        normalizedRemote.blocks.forEach { remoteBlock ->
            if (remoteBlock.id !in localById && remoteBlock.id !in locallyDirtyBlockIds) add(remoteBlock)
        }
    }
    return BlockDocument(merged).normalized()
}
