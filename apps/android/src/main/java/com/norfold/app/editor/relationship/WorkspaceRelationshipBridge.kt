package com.norfold.app.editor.relationship

import com.norfold.app.domain.WorkspaceComment
import com.norfold.app.domain.WorkspaceLinkType
import com.norfold.app.domain.WorkspaceObjectLink
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Additive adapter: legacy Object Detail/Graph records become canonical records without a second identity. */
object WorkspaceRelationshipBridge {
    fun relationship(link: WorkspaceObjectLink, artifactId: String = "workspace:${link.workspaceId}"): Relationship {
        val kind = when (link.linkType) {
            WorkspaceLinkType.Reference -> RelationshipKinds.REFERENCES
            WorkspaceLinkType.Related -> RelationshipKinds.RELATED
            WorkspaceLinkType.Embed -> RelationshipKind("embed", "Embeds", RelationshipDirection.DIRECTED, "Embedded by", "reference")
            WorkspaceLinkType.Mention -> RelationshipKind("mentions", "Mentions", RelationshipDirection.DIRECTED, "Mentioned by", "reference")
            WorkspaceLinkType.Attachment -> RelationshipKind("attachment", "Attaches", RelationshipDirection.DIRECTED, "Attached to", "reference")
        }
        return Relationship(
            id = RelationshipId(deterministic("workspace-link:${link.workspaceId}:${link.id}")),
            source = RelationshipEndpoint(RelationshipAnchor.WorkspaceObject(artifactId, link.fromObjectId)),
            target = RelationshipEndpoint(RelationshipAnchor.WorkspaceObject(artifactId, link.toObjectId)),
            kind = kind,
            label = link.label.takeIf(String::isNotBlank),
            metadata = RelationshipMetadata(link.createdAt, legacyWorkspaceLinkId = link.id),
        )
    }

    fun workspaceLink(relationship: Relationship, workspaceId: Long): WorkspaceObjectLink? {
        val source = relationship.source.anchor as? RelationshipAnchor.WorkspaceObject ?: return null
        val target = relationship.target.anchor as? RelationshipAnchor.WorkspaceObject ?: return null
        val type = when (relationship.kind.id) {
            RelationshipKinds.REFERENCES.id -> WorkspaceLinkType.Reference
            RelationshipKinds.RELATED.id -> WorkspaceLinkType.Related
            "embed" -> WorkspaceLinkType.Embed
            "mentions" -> WorkspaceLinkType.Mention
            "attachment" -> WorkspaceLinkType.Attachment
            else -> WorkspaceLinkType.Related
        }
        return WorkspaceObjectLink(
            id = relationship.metadata.legacyWorkspaceLinkId ?: 0,
            fromObjectId = source.objectId,
            toObjectId = target.objectId,
            linkType = type,
            label = relationship.label ?: relationship.kind.displayName,
            createdAt = relationship.metadata.createdAt,
            workspaceId = workspaceId,
        )
    }

    fun thread(comment: WorkspaceComment, artifactId: String = "workspace:${comment.workspaceId}"): DiscussionThread {
        val threadId = ThreadId(deterministic("workspace-comment-thread:${comment.workspaceId}:${comment.id}"))
        val author = CommentAuthorRef(comment.authorUsername, comment.authorDisplayName)
        return DiscussionThread(
            id = threadId,
            anchor = CommentAnchor.WorkspaceObject(artifactId, comment.objectId),
            status = if (comment.resolved) CommentStatus.RESOLVED else CommentStatus.OPEN,
            comments = listOf(
                Comment(
                    id = CommentId(deterministic("workspace-comment:${comment.workspaceId}:${comment.id}")),
                    threadId = threadId,
                    author = author,
                    createdAt = comment.createdAt,
                    editedAt = comment.updatedAt.takeIf { it != comment.createdAt },
                    body = comment.body,
                    legacyWorkspaceCommentId = comment.id,
                ),
            ),
            resolvedAt = comment.updatedAt.takeIf { comment.resolved },
            resolvedBy = author.takeIf { comment.resolved },
        )
    }

    fun workspaceComment(thread: DiscussionThread, workspaceId: Long): WorkspaceComment? {
        val anchor = thread.anchor as? CommentAnchor.WorkspaceObject ?: return null
        val root = thread.comments.firstOrNull() ?: return null
        return WorkspaceComment(
            id = root.legacyWorkspaceCommentId ?: 0,
            objectId = anchor.objectId,
            authorUsername = root.author.stableId,
            authorDisplayName = root.author.displayName,
            body = root.body,
            resolved = thread.status == CommentStatus.RESOLVED,
            createdAt = root.createdAt,
            updatedAt = root.editedAt ?: root.createdAt,
            workspaceId = workspaceId,
        )
    }

    private fun deterministic(value: String): String = UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8)).toString()
}
