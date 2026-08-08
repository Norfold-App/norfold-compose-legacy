package com.norfold.app.editor.relationship

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.UUID

@JvmInline @Serializable value class RelationshipId(val value: String)
@JvmInline @Serializable value class ThreadId(val value: String)
@JvmInline @Serializable value class CommentId(val value: String)
@JvmInline @Serializable value class ReviewSnapshotId(val value: String)

@Serializable enum class RelationshipDirection { DIRECTED, UNDIRECTED }

@Serializable
data class RelationshipKind(
    val id: String,
    val displayName: String,
    val direction: RelationshipDirection,
    val inverseLabel: String? = null,
    val category: String = "general",
    val custom: Boolean = false,
) {
    init { require(id.isNotBlank() && displayName.isNotBlank()) }
}

object RelationshipKinds {
    val RELATED = RelationshipKind("related", "Related to", RelationshipDirection.UNDIRECTED)
    val DEPENDS_ON = RelationshipKind("depends_on", "Depends on", RelationshipDirection.DIRECTED, "Required by", "dependency")
    val BLOCKS = RelationshipKind("blocks", "Blocks", RelationshipDirection.DIRECTED, "Blocked by", "dependency")
    val REFERENCES = RelationshipKind("references", "References", RelationshipDirection.DIRECTED, "Referenced by", "reference")
    val CONTAINS = RelationshipKind("contains", "Contains", RelationshipDirection.DIRECTED, "Contained by", "structure")
    val baseline = listOf(RELATED, DEPENDS_ON, BLOCKS, REFERENCES, CONTAINS)
    fun custom(name: String, directed: Boolean = false) = RelationshipKind(
        id = "custom:${name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')}",
        displayName = name.trim(),
        direction = if (directed) RelationshipDirection.DIRECTED else RelationshipDirection.UNDIRECTED,
        custom = true,
    )
}

@Serializable
sealed interface RelationshipAnchor {
    val artifactId: String

    @Serializable @SerialName("artifact")
    data class Artifact(override val artifactId: String) : RelationshipAnchor

    @Serializable @SerialName("block")
    data class Block(override val artifactId: String, val blockId: String) : RelationshipAnchor

    @Serializable @SerialName("scene_object")
    data class SceneObject(override val artifactId: String, val pageId: String, val objectId: String) : RelationshipAnchor

    @Serializable @SerialName("table_range")
    data class TableRange(override val artifactId: String, val tableId: String, val rangeId: String) : RelationshipAnchor

    @Serializable @SerialName("chart")
    data class Chart(override val artifactId: String, val chartId: String, val seriesId: String? = null) : RelationshipAnchor

    @Serializable @SerialName("image")
    data class Image(override val artifactId: String, val imageId: String) : RelationshipAnchor

    @Serializable @SerialName("workspace_object")
    data class WorkspaceObject(override val artifactId: String, val objectId: Long) : RelationshipAnchor
}

fun RelationshipAnchor.stableKey(): String = when (this) {
    is RelationshipAnchor.Artifact -> "artifact:$artifactId"
    is RelationshipAnchor.Block -> "artifact:$artifactId/block:$blockId"
    is RelationshipAnchor.SceneObject -> "artifact:$artifactId/page:$pageId/object:$objectId"
    is RelationshipAnchor.TableRange -> "artifact:$artifactId/table:$tableId/range:$rangeId"
    is RelationshipAnchor.Chart -> "artifact:$artifactId/chart:$chartId/series:${seriesId.orEmpty()}"
    is RelationshipAnchor.Image -> "artifact:$artifactId/image:$imageId"
    is RelationshipAnchor.WorkspaceObject -> "artifact:$artifactId/workspace-object:$objectId"
}

@Serializable
data class RelationshipEndpoint(
    val anchor: RelationshipAnchor,
    val broken: Boolean = false,
    val brokenReason: String? = null,
) {
    val stableId: String get() = anchor.stableKey()
}

@Serializable
data class RelationshipMetadata(
    val createdAt: Long,
    val createdBy: String? = null,
    val legacyWorkspaceLinkId: Long? = null,
    val extensions: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class Relationship(
    val id: RelationshipId,
    val source: RelationshipEndpoint,
    val target: RelationshipEndpoint,
    val kind: RelationshipKind = RelationshipKinds.RELATED,
    val label: String? = null,
    val description: String? = null,
    val metadata: RelationshipMetadata,
) {
    val isBroken: Boolean get() = source.broken || target.broken
    fun textualDescription(): String = buildString {
        append(kind.displayName).append(' ').append(target.stableId)
        if (isBroken) append(", broken relationship")
    }
}

@Serializable enum class AnchorAffinity { BEFORE, AFTER }
@Serializable enum class AnchorState { ATTACHED, DETACHED }

@Serializable
sealed interface CommentAnchor {
    val artifactId: String
    val state: AnchorState

    @Serializable @SerialName("artifact")
    data class Artifact(override val artifactId: String, override val state: AnchorState = AnchorState.ATTACHED) : CommentAnchor

    @Serializable @SerialName("block")
    data class Block(override val artifactId: String, val blockId: String, override val state: AnchorState = AnchorState.ATTACHED) : CommentAnchor

    @Serializable @SerialName("text")
    data class Text(
        override val artifactId: String,
        val blockId: String,
        val start: Int,
        val end: Int,
        val quote: String,
        val prefix: String = "",
        val suffix: String = "",
        val affinity: AnchorAffinity = AnchorAffinity.AFTER,
        override val state: AnchorState = AnchorState.ATTACHED,
    ) : CommentAnchor {
        init { require(start >= 0 && end >= start) }
    }

    @Serializable @SerialName("scene_object")
    data class SceneObject(override val artifactId: String, val pageId: String, val objectId: String, override val state: AnchorState = AnchorState.ATTACHED) : CommentAnchor

    @Serializable @SerialName("table_range")
    data class TableRange(override val artifactId: String, val tableId: String, val rangeId: String, override val state: AnchorState = AnchorState.ATTACHED) : CommentAnchor

    @Serializable @SerialName("chart")
    data class Chart(override val artifactId: String, val chartId: String, val seriesId: String? = null, override val state: AnchorState = AnchorState.ATTACHED) : CommentAnchor

    @Serializable @SerialName("image")
    data class Image(override val artifactId: String, val imageId: String, override val state: AnchorState = AnchorState.ATTACHED) : CommentAnchor

    @Serializable @SerialName("workspace_object")
    data class WorkspaceObject(override val artifactId: String, val objectId: Long, override val state: AnchorState = AnchorState.ATTACHED) : CommentAnchor
}

@Serializable data class CommentAuthorRef(val stableId: String, val displayName: String)
@Serializable data class CommentMention(val stableUserId: String, val displayText: String)

@Serializable
data class Comment(
    val id: CommentId,
    val threadId: ThreadId,
    val author: CommentAuthorRef,
    val createdAt: Long,
    val editedAt: Long? = null,
    val body: String,
    val mentions: List<CommentMention> = emptyList(),
    val replyTo: CommentId? = null,
    val legacyWorkspaceCommentId: Long? = null,
) { init { require(body.isNotBlank()) } }

@Serializable enum class CommentStatus { OPEN, RESOLVED }

@Serializable
data class DiscussionThread(
    val id: ThreadId,
    val anchor: CommentAnchor,
    val status: CommentStatus = CommentStatus.OPEN,
    val comments: List<Comment>,
    val resolvedAt: Long? = null,
    val resolvedBy: CommentAuthorRef? = null,
) { init { require(comments.isNotEmpty()); require(comments.all { it.threadId == id }) } }

@Serializable
data class ReviewSnapshot(
    val id: ReviewSnapshotId,
    val artifactId: String,
    val createdAt: Long,
    val label: String? = null,
    val contentHash: String,
    val documentPayload: String,
    val author: CommentAuthorRef? = null,
)

@Serializable
data class Phase7ArtifactState(
    val relationships: List<Relationship> = emptyList(),
    val threads: List<DiscussionThread> = emptyList(),
    val snapshots: List<ReviewSnapshot> = emptyList(),
    val unknownTopLevel: JsonObject = JsonObject(emptyMap()),
    val opaqueFuturePayload: String? = null,
) {
    val isReadOnlyFuture: Boolean get() = opaqueFuturePayload != null
}

internal fun newStableId(prefix: String): String = "$prefix-${UUID.randomUUID()}"
