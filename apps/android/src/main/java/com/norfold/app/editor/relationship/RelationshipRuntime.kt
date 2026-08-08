package com.norfold.app.editor.relationship

import com.norfold.app.domain.BlockDocument
import com.norfold.app.domain.ChartBlock
import com.norfold.app.domain.DocumentPageBlock
import com.norfold.app.domain.ImageBlock
import com.norfold.app.domain.TableBlock
import com.norfold.app.domain.findById
import com.norfold.app.domain.pages
import com.norfold.app.domain.relationshipDataBlock
import com.norfold.app.domain.withRelationshipData
import java.security.MessageDigest

enum class ConnectModePolicy { EXIT_AFTER_COMMIT, REMAIN_FOR_MULTIPLE }

data class ConnectModeState(
    val source: RelationshipEndpoint,
    val kind: RelationshipKind = RelationshipKinds.RELATED,
    val policy: ConnectModePolicy = ConnectModePolicy.EXIT_AFTER_COMMIT,
    val previewTarget: RelationshipEndpoint? = null,
    val rebindRelationshipId: RelationshipId? = null,
    val rebindStableId: String? = null,
)

data class RelationshipRuntimeMetrics(
    val relationshipsLoaded: Int = 0,
    val affectedConnectorsRecalculated: Int = 0,
    val commentThreadsLoaded: Int = 0,
    val anchorRepairsAttempted: Int = 0,
    val anchorRepairsDetached: Int = 0,
    val snapshotLoads: Int = 0,
    val indexLookups: Int = 0,
)

data class ReviewModeState(val enabled: Boolean = false, val includeResolved: Boolean = false)

data class RelationshipReviewUiState(
    val enabled: Boolean,
    val connectMode: ConnectModeState?,
    val reviewMode: ReviewModeState,
    val relationships: List<Relationship>,
    val threads: List<DiscussionThread>,
    val snapshots: List<ReviewSnapshot>,
    val unresolvedCount: Int,
    val metrics: RelationshipRuntimeMetrics,
    val focusedThreadId: ThreadId? = null,
    val failure: String? = null,
)

/** Indexed read model rebuilt only when the durable payload changes. */
private class RelationshipIndex(state: Phase7ArtifactState) {
    val bySource = state.relationships.groupBy { it.source.stableId }
    val byTarget = state.relationships.groupBy { it.target.stableId }
    val byArtifact = state.relationships
        .flatMap { relationship ->
            setOf(relationship.source.anchor.artifactId, relationship.target.anchor.artifactId)
                .map { artifact -> artifact to relationship }
        }
        .groupBy({ it.first }, { it.second })
    val threadsByAnchor = state.threads.groupBy { it.anchor.stableKey() }
    val unresolved = state.threads.filter { it.status == CommentStatus.OPEN }
}

fun CommentAnchor.stableKey(): String = when (this) {
    is CommentAnchor.Artifact -> "artifact:$artifactId"
    is CommentAnchor.Block -> "artifact:$artifactId/block:$blockId"
    is CommentAnchor.Text -> "artifact:$artifactId/block:$blockId/text:$start-$end"
    is CommentAnchor.SceneObject -> "artifact:$artifactId/page:$pageId/object:$objectId"
    is CommentAnchor.TableRange -> "artifact:$artifactId/table:$tableId/range:$rangeId"
    is CommentAnchor.Chart -> "artifact:$artifactId/chart:$chartId/series:${seriesId.orEmpty()}"
    is CommentAnchor.Image -> "artifact:$artifactId/image:$imageId"
    is CommentAnchor.WorkspaceObject -> "artifact:$artifactId/workspace-object:$objectId"
}

/** Offline Phase-7 owner. All durable writes are supplied by a host's canonical History controller. */
class RelationshipReviewRuntime(
    val artifactId: String,
    private val enabled: Boolean,
    private val documentProvider: () -> BlockDocument,
    private val mutateDocument: (String, (BlockDocument) -> BlockDocument) -> Boolean,
    private val protectBeforeRestore: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: (String) -> String = ::newStableId,
) {
    var connectMode: ConnectModeState? = null
        private set
    var reviewMode = ReviewModeState()
        private set
    var metrics = RelationshipRuntimeMetrics()
        private set
    var lastFailure: String? = null
        private set
    var focusedThreadId: ThreadId? = null
        private set

    private var cachedPayload: String? = null
    private var cachedState = Phase7ArtifactState()
    private var index = RelationshipIndex(cachedState)

    fun state(): Phase7ArtifactState {
        val payload = documentProvider().relationshipDataBlock()?.payload
        if (payload != cachedPayload) {
            cachedPayload = payload
            cachedState = Phase7ArtifactCodec.decode(payload)
            index = RelationshipIndex(cachedState)
            metrics = metrics.copy(
                relationshipsLoaded = cachedState.relationships.size,
                commentThreadsLoaded = cachedState.threads.size,
            )
        }
        return cachedState
    }

    fun uiState(anchor: RelationshipAnchor? = null): RelationshipReviewUiState {
        val durable = state()
        val relationships = if (anchor == null) durable.relationships else relationshipsFor(anchor)
        val threads = if (reviewMode.includeResolved) durable.threads else durable.threads.filter { it.status == CommentStatus.OPEN }
        return RelationshipReviewUiState(
            enabled = enabled,
            connectMode = connectMode,
            reviewMode = reviewMode,
            relationships = relationships,
            threads = threads,
            snapshots = durable.snapshots.sortedByDescending { it.createdAt },
            unresolvedCount = index.unresolved.size,
            metrics = metrics,
            focusedThreadId = focusedThreadId,
            failure = lastFailure,
        )
    }

    private fun update(label: String, transform: (Phase7ArtifactState) -> Phase7ArtifactState): Boolean {
        if (!enabled) return fail("Relationships and review are disabled")
        val before = state()
        if (before.isReadOnlyFuture) return fail("This relationship payload was saved by a newer version")
        val after = transform(before)
        if (after == before) return false
        val changed = mutateDocument(label) { it.withRelationshipData(Phase7ArtifactCodec.encode(after)) }
        if (changed) {
            cachedPayload = null
            state()
            lastFailure = null
        }
        return changed
    }

    private fun fail(message: String): Boolean { lastFailure = message; return false }

    fun enterConnectMode(source: RelationshipEndpoint, kind: RelationshipKind = RelationshipKinds.RELATED, policy: ConnectModePolicy = ConnectModePolicy.EXIT_AFTER_COMMIT): Boolean {
        if (!enabled || source.broken || !isEligible(source.anchor)) return fail("Choose an eligible relationship source")
        connectMode = ConnectModeState(source, kind, policy)
        lastFailure = null
        return true
    }

    fun previewConnectTarget(target: RelationshipEndpoint?): Boolean {
        val mode = connectMode ?: return false
        connectMode = mode.copy(previewTarget = target?.takeIf { !it.broken && isEligible(it.anchor) })
        return connectMode?.previewTarget != null
    }

    fun commitConnectTarget(target: RelationshipEndpoint): Relationship? {
        val mode = connectMode ?: return null
        if (target.stableId == mode.source.stableId || target.broken || !isEligible(target.anchor)) {
            fail("Choose a different eligible target")
            return null
        }
        val created = if (mode.rebindRelationshipId != null && mode.rebindStableId != null) {
            val changed = rebind(mode.rebindRelationshipId, mode.rebindStableId, target)
            if (!changed) return null
            state().relationships.firstOrNull { it.id == mode.rebindRelationshipId }
        } else {
            createRelationship(mode.source, target, mode.kind)
        } ?: return null
        connectMode = if (mode.policy == ConnectModePolicy.REMAIN_FOR_MULTIPLE) mode.copy(previewTarget = null) else null
        return created
    }

    fun exitConnectMode() { connectMode = null; lastFailure = null }

    fun beginRebind(relationshipId: RelationshipId, brokenStableId: String): Boolean {
        val relationship = state().relationships.firstOrNull { it.id == relationshipId }
            ?: return fail("Relationship is unavailable")
        val brokenEndpoint = listOf(relationship.source, relationship.target)
            .firstOrNull { it.stableId == brokenStableId && it.broken }
            ?: return fail("Choose a broken endpoint to repair")
        connectMode = ConnectModeState(
            source = brokenEndpoint,
            kind = relationship.kind,
            rebindRelationshipId = relationshipId,
            rebindStableId = brokenStableId,
        )
        lastFailure = null
        return true
    }

    fun createRelationship(source: RelationshipEndpoint, target: RelationshipEndpoint, kind: RelationshipKind = RelationshipKinds.RELATED, label: String? = null): Relationship? {
        if (!enabled) { fail("Relationships and review are disabled"); return null }
        if (source.stableId == target.stableId) { fail("Choose a different relationship target"); return null }
        if (source.broken || target.broken || !isEligible(source.anchor) || !isEligible(target.anchor)) {
            fail("Choose available, eligible relationship endpoints")
            return null
        }
        val current = state()
        val duplicate = current.relationships.any {
            it.source.stableId == source.stableId && it.target.stableId == target.stableId && it.kind.id == kind.id
        }
        if (duplicate) { fail("That relationship already exists"); return null }
        val relationship = Relationship(
            RelationshipId(idGenerator("relationship")), source, target, kind, label,
            metadata = RelationshipMetadata(clock()),
        )
        return relationship.takeIf { update("Create relationship") { it.copy(relationships = it.relationships + relationship) } }
    }

    fun relationshipsFor(anchor: RelationshipAnchor): List<Relationship> {
        metrics = metrics.copy(indexLookups = metrics.indexLookups + 2)
        val key = anchor.stableKey()
        return (index.bySource[key].orEmpty() + index.byTarget[key].orEmpty()).distinctBy { it.id }
    }

    fun relationshipsForArtifact(id: String = artifactId): List<Relationship> {
        metrics = metrics.copy(indexLookups = metrics.indexLookups + 1)
        return index.byArtifact[id].orEmpty()
    }

    fun changeType(id: RelationshipId, kind: RelationshipKind): Boolean = update("Change relationship type") { state ->
        state.copy(relationships = state.relationships.map { if (it.id == id) it.copy(kind = kind) else it })
    }

    fun rename(id: RelationshipId, label: String?): Boolean = update("Rename relationship") { state ->
        state.copy(relationships = state.relationships.map { if (it.id == id) it.copy(label = label?.trim()?.takeIf(String::isNotBlank)) else it })
    }

    fun reverse(id: RelationshipId): Boolean = update("Reverse relationship") { state ->
        state.copy(relationships = state.relationships.map {
            if (it.id == id && it.kind.direction == RelationshipDirection.DIRECTED) it.copy(source = it.target, target = it.source) else it
        })
    }

    fun removeRelationship(id: RelationshipId): Boolean = update("Delete relationship") { state ->
        state.copy(relationships = state.relationships.filterNot { it.id == id })
    }

    fun rebind(id: RelationshipId, brokenStableId: String, replacement: RelationshipEndpoint): Boolean = update("Repair relationship") { state ->
        state.copy(relationships = state.relationships.map { relationship ->
            if (relationship.id != id) relationship else relationship.copy(
                source = if (relationship.source.stableId == brokenStableId) replacement.copy(broken = false, brokenReason = null) else relationship.source,
                target = if (relationship.target.stableId == brokenStableId) replacement.copy(broken = false, brokenReason = null) else relationship.target,
            )
        })
    }

    /** Persists missing endpoints explicitly; undo restores them by stable identity on the next reconciliation. */
    fun reconcileEndpoints(): Boolean = update("Update relationship availability") { state ->
        reconcileState(state, documentProvider())
    }

    /** Pure invariant transform so content and endpoint availability share one History action. */
    fun reconcileEndpointsIn(document: BlockDocument): BlockDocument {
        if (!enabled) return document
        val block = document.relationshipDataBlock() ?: return document
        val current = Phase7ArtifactCodec.decode(block.payload)
        if (current.isReadOnlyFuture) return document
        val next = reconcileState(current, document)
        return if (next == current) document else document.withRelationshipData(Phase7ArtifactCodec.encode(next))
    }

    private fun reconcileState(state: Phase7ArtifactState, document: BlockDocument): Phase7ArtifactState =
        state.copy(relationships = state.relationships.map { relationship ->
            relationship.copy(
                source = relationship.source.availability(document),
                target = relationship.target.availability(document),
            )
        })

    private fun RelationshipEndpoint.availability(document: BlockDocument): RelationshipEndpoint {
        val present = anchorExists(anchor, document)
        return copy(broken = !present, brokenReason = if (present) null else "Target is unavailable")
    }

    fun addThread(anchor: CommentAnchor, author: CommentAuthorRef, body: String, mentions: List<CommentMention> = emptyList()): DiscussionThread? {
        if (body.isBlank()) { fail("Comment cannot be empty"); return null }
        val threadId = ThreadId(idGenerator("thread"))
        val comment = Comment(CommentId(idGenerator("comment")), threadId, author, clock(), body = body.trim(), mentions = mentions)
        val thread = DiscussionThread(threadId, anchor, comments = listOf(comment))
        return thread.takeIf { update("Create comment") { it.copy(threads = it.threads + thread) } }
    }

    fun reply(threadId: ThreadId, author: CommentAuthorRef, body: String, mentions: List<CommentMention> = emptyList(), replyTo: CommentId? = null): Comment? {
        if (body.isBlank()) { fail("Reply cannot be empty"); return null }
        val comment = Comment(CommentId(idGenerator("comment")), threadId, author, clock(), body = body.trim(), mentions = mentions, replyTo = replyTo)
        return comment.takeIf { update("Reply to comment") { state ->
            state.copy(threads = state.threads.map { if (it.id == threadId) it.copy(comments = it.comments + comment) else it })
        } }
    }

    fun editComment(id: CommentId, authorId: String, body: String, mentions: List<CommentMention> = emptyList()): Boolean {
        if (body.isBlank()) return fail("Comment cannot be empty")
        return update("Edit comment") { state ->
            state.copy(threads = state.threads.map { thread -> thread.copy(comments = thread.comments.map { comment ->
                if (comment.id == id && comment.author.stableId == authorId) comment.copy(body = body.trim(), mentions = mentions, editedAt = clock()) else comment
            }) })
        }
    }

    fun deleteComment(id: CommentId, authorId: String): Boolean = update("Delete comment") { state ->
        state.copy(threads = state.threads.mapNotNull { thread ->
            val comments = thread.comments.filterNot { it.id == id && it.author.stableId == authorId }
            if (comments.isEmpty()) null else thread.copy(comments = comments)
        })
    }

    fun resolve(threadId: ThreadId, author: CommentAuthorRef): Boolean = update("Resolve comment thread") { state ->
        state.copy(threads = state.threads.map { if (it.id == threadId) it.copy(status = CommentStatus.RESOLVED, resolvedAt = clock(), resolvedBy = author) else it })
    }

    fun reopen(threadId: ThreadId): Boolean = update("Reopen comment thread") { state ->
        state.copy(threads = state.threads.map { if (it.id == threadId) it.copy(status = CommentStatus.OPEN, resolvedAt = null, resolvedBy = null) else it })
    }

    fun threadsAt(anchor: CommentAnchor): List<DiscussionThread> {
        metrics = metrics.copy(indexLookups = metrics.indexLookups + 1)
        return index.threadsByAnchor[anchor.stableKey()].orEmpty()
    }

    fun focusThread(id: ThreadId?): DiscussionThread? {
        val thread = id?.let { requested -> state().threads.firstOrNull { it.id == requested } }
        focusedThreadId = thread?.id
        return thread
    }

    fun nextThread(forward: Boolean = true): DiscussionThread? {
        val visible = state().threads
            .filter { reviewMode.includeResolved || it.status == CommentStatus.OPEN }
            .sortedWith(compareBy<DiscussionThread> { it.comments.first().createdAt }.thenBy { it.id.value })
        if (visible.isEmpty()) { focusedThreadId = null; return null }
        val current = visible.indexOfFirst { it.id == focusedThreadId }
        val index = when {
            current < 0 -> if (forward) 0 else visible.lastIndex
            forward -> (current + 1) % visible.size
            else -> (current - 1 + visible.size) % visible.size
        }
        return visible[index].also { focusedThreadId = it.id }
    }

    fun applyTextEdit(blockId: String, edit: TextEdit, textAfter: String): Boolean {
        var attempted = 0
        var detached = 0
        val changed = update("Repair comment anchors") { state ->
            state.copy(threads = state.threads.map { thread ->
                val anchor = thread.anchor as? CommentAnchor.Text
                if (anchor == null || anchor.blockId != blockId) thread else {
                    attempted++
                    val repaired = TextAnchorRepair.apply(anchor, edit, textAfter)
                    if (repaired.state == AnchorState.DETACHED) detached++
                    thread.copy(anchor = repaired)
                }
            })
        }
        metrics = metrics.copy(
            anchorRepairsAttempted = metrics.anchorRepairsAttempted + attempted,
            anchorRepairsDetached = metrics.anchorRepairsDetached + detached,
        )
        return changed
    }

    /** Pure companion to [applyTextEdit] for a host that folds repair into the same typing transaction. */
    fun repairTextAnchors(document: BlockDocument, blockId: String, edit: TextEdit, textAfter: String): BlockDocument {
        if (!enabled) return document
        val block = document.relationshipDataBlock() ?: return document
        val current = Phase7ArtifactCodec.decode(block.payload)
        if (current.isReadOnlyFuture) return document
        var attempted = 0
        var detached = 0
        val next = current.copy(threads = current.threads.map { thread ->
            val anchor = thread.anchor as? CommentAnchor.Text
            if (anchor == null || anchor.blockId != blockId) thread else {
                attempted++
                val repaired = TextAnchorRepair.apply(anchor, edit, textAfter)
                if (repaired.state == AnchorState.DETACHED) detached++
                thread.copy(anchor = repaired)
            }
        })
        metrics = metrics.copy(
            anchorRepairsAttempted = metrics.anchorRepairsAttempted + attempted,
            anchorRepairsDetached = metrics.anchorRepairsDetached + detached,
        )
        return if (next == current) document else document.withRelationshipData(Phase7ArtifactCodec.encode(next))
    }

    fun openReviewMode(includeResolved: Boolean = false) { if (enabled) reviewMode = ReviewModeState(true, includeResolved) }
    fun closeReviewMode() { reviewMode = ReviewModeState() }
    fun setIncludeResolved(include: Boolean) { reviewMode = reviewMode.copy(includeResolved = include) }

    fun createReviewSnapshot(label: String? = null, author: CommentAuthorRef? = null): ReviewSnapshot? {
        val current = state()
        val payloadState = current.copy(snapshots = emptyList())
        val documentPayload = com.norfold.app.domain.BlockDocumentJson.encodeDocumentPayload(
            documentProvider().withRelationshipData(Phase7ArtifactCodec.encode(payloadState)),
        )
        val snapshot = ReviewSnapshot(
            ReviewSnapshotId(idGenerator("review")), artifactId, clock(), label?.trim()?.takeIf(String::isNotBlank),
            sha256(documentPayload), documentPayload, author,
        )
        return snapshot.takeIf { update("Create review snapshot") { it.copy(snapshots = it.snapshots + snapshot) } }
    }

    fun restoreReviewSnapshot(id: ReviewSnapshotId): Boolean {
        if (!enabled) return false
        val currentState = state()
        val snapshot = currentState.snapshots.firstOrNull { it.id == id } ?: return fail("Review snapshot is unavailable")
        val restored = runCatching { com.norfold.app.domain.BlockDocumentJson.decodeDocumentPayload(snapshot.documentPayload) }
            .getOrElse { return fail("Review snapshot is corrupt") }
        if (sha256(snapshot.documentPayload) != snapshot.contentHash) return fail("Review snapshot failed integrity validation")
        protectBeforeRestore("Before restoring review snapshot ${snapshot.label ?: snapshot.id.value}")
        metrics = metrics.copy(snapshotLoads = metrics.snapshotLoads + 1)
        val changed = mutateDocument("Restore review snapshot") { current ->
            val snapshotState = Phase7ArtifactCodec.decode(restored.relationshipDataBlock()?.payload)
            val repairedThreads = currentState.threads.map { it.copy(anchor = it.anchor.availabilityIn(restored)) }
            val restoredRelationships = if (snapshotState.isReadOnlyFuture) {
                currentState.relationships
            } else {
                snapshotState.relationships
            }
            val coherent = currentState.copy(
                relationships = restoredRelationships,
                threads = repairedThreads,
            )
            reconcileEndpointsIn(restored.withRelationshipData(Phase7ArtifactCodec.encode(coherent)))
        }
        if (changed) { cachedPayload = null; state(); lastFailure = null }
        return changed
    }

    fun snapshotSummary(id: ReviewSnapshotId): String? = state().snapshots.firstOrNull { it.id == id }?.let {
        "${it.label ?: "Review snapshot"} · ${it.contentHash.take(12)} · ${it.createdAt}"
    }

    fun connectorGeometryInvalidated(movedEndpointIds: Set<String>): List<RelationshipId> {
        val affected = movedEndpointIds.flatMap { id -> index.bySource[id].orEmpty() + index.byTarget[id].orEmpty() }
            .distinctBy { it.id }.map { it.id }
        metrics = metrics.copy(affectedConnectorsRecalculated = metrics.affectedConnectorsRecalculated + affected.size)
        return affected
    }

    private fun isEligible(anchor: RelationshipAnchor): Boolean = when (anchor) {
        is RelationshipAnchor.Artifact, is RelationshipAnchor.Block, is RelationshipAnchor.SceneObject,
        is RelationshipAnchor.TableRange, is RelationshipAnchor.Chart, is RelationshipAnchor.Image,
        is RelationshipAnchor.WorkspaceObject -> anchor.artifactId.isNotBlank()
    }

    private fun anchorExists(anchor: RelationshipAnchor, document: BlockDocument = documentProvider()): Boolean {
        // An artifact-local runtime can prove local absence. It cannot declare another offline artifact
        // broken merely because that artifact is not loaded in this editor session.
        if (anchor.artifactId != artifactId) return anchor.artifactId.isNotBlank()
        return when (anchor) {
            is RelationshipAnchor.Artifact -> true
            is RelationshipAnchor.Block -> document.findById(anchor.blockId) != null
            is RelationshipAnchor.SceneObject -> document.pages().any { page -> page.id == anchor.pageId && page.objects.any { it.id == anchor.objectId } }
            is RelationshipAnchor.TableRange -> document.findById(anchor.tableId) is TableBlock
            is RelationshipAnchor.Chart -> document.findById(anchor.chartId) is ChartBlock
            is RelationshipAnchor.Image -> document.findById(anchor.imageId) is ImageBlock
            is RelationshipAnchor.WorkspaceObject -> true
        }
    }

    private fun CommentAnchor.availabilityIn(document: BlockDocument): CommentAnchor = when (this) {
        is CommentAnchor.Artifact, is CommentAnchor.WorkspaceObject -> this
        is CommentAnchor.Block -> copy(state = if (document.findById(blockId) != null) AnchorState.ATTACHED else AnchorState.DETACHED)
        is CommentAnchor.Text -> if (document.findById(blockId) == null) copy(state = AnchorState.DETACHED) else TextAnchorRepair.repairByContext(this, document.findById(blockId)?.plainText().orEmpty())
        is CommentAnchor.SceneObject -> copy(state = if (document.pages().any { it.id == pageId && it.objects.any { obj -> obj.id == objectId } }) AnchorState.ATTACHED else AnchorState.DETACHED)
        is CommentAnchor.TableRange -> copy(state = if (document.findById(tableId) is TableBlock) AnchorState.ATTACHED else AnchorState.DETACHED)
        is CommentAnchor.Chart -> copy(state = if (document.findById(chartId) is ChartBlock) AnchorState.ATTACHED else AnchorState.DETACHED)
        is CommentAnchor.Image -> copy(state = if (document.findById(imageId) is ImageBlock) AnchorState.ATTACHED else AnchorState.DETACHED)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
