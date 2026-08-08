package com.norfold.app.editor.relationship

import com.norfold.app.editor.command.ApplicabilityPolicy
import com.norfold.app.editor.command.Capability
import com.norfold.app.editor.command.CommandContext
import com.norfold.app.editor.command.CommandRegistry
import com.norfold.app.editor.command.CommandResult
import com.norfold.app.editor.command.EditorCommand

object RelationshipCommandIds {
    const val ENTER_CONNECT = "relationship.connect.enter"
    const val EXIT_CONNECT = "relationship.connect.exit"
    const val CREATE = "relationship.create"
    const val REMOVE = "relationship.remove"
    const val OPEN_MANAGER = "relationship.manager.open"
    const val ADD_COMMENT = "relationship.comment.add"
    const val REPLY = "relationship.comment.reply"
    const val RESOLVE = "relationship.comment.resolve"
    const val REOPEN = "relationship.comment.reopen"
    const val NEXT_COMMENT = "relationship.comment.next"
    const val PREVIOUS_COMMENT = "relationship.comment.previous"
    const val OPEN_REVIEW = "relationship.review.open"
    const val CREATE_SNAPSHOT = "relationship.review.snapshot.create"
    const val RESTORE_SNAPSHOT = "relationship.review.snapshot.restore"

    val contextual = listOf(ENTER_CONNECT, OPEN_MANAGER, ADD_COMMENT, OPEN_REVIEW)
}

object RelationshipSurfaces {
    const val MANAGER = "relationship.surface.manager"
    const val COMMENTS = "relationship.surface.comments"
    const val REVIEW = "relationship.surface.review"
}

class RelationshipCommand(
    override val id: String,
    override val label: String,
    override val description: String,
    override val requiredCapabilities: Set<Capability>,
    private val action: () -> CommandResult,
) : EditorCommand {
    override val icon: String? = null
    override val category: String = "Relationships & review"
    override val aliases: List<String> = listOf(label.lowercase())
    override val applicability: ApplicabilityPolicy = ApplicabilityPolicy.PRIMARY_TARGET
    override fun perform(context: CommandContext): CommandResult = action()
}

object RelationshipCommands {
    fun register(
        registry: CommandRegistry,
        enterConnect: () -> CommandResult,
        exitConnect: () -> CommandResult,
        openManager: () -> CommandResult,
        addComment: () -> CommandResult,
        nextComment: () -> CommandResult,
        previousComment: () -> CommandResult,
        openReview: () -> CommandResult,
        createSnapshot: () -> CommandResult,
    ) {
        registry.registerAll(
            RelationshipCommand(RelationshipCommandIds.ENTER_CONNECT, "Connect", "Choose a target to relate to the selected item.", setOf(Capability.LINKING), enterConnect),
            RelationshipCommand(RelationshipCommandIds.EXIT_CONNECT, "Exit Connect mode", "Return to ordinary selection and editing.", emptySet(), exitConnect),
            RelationshipCommand(RelationshipCommandIds.CREATE, "Create relationship", "Enter Connect mode and choose a relationship target.", setOf(Capability.LINKING), enterConnect),
            RelationshipCommand(RelationshipCommandIds.REMOVE, "Remove relationship", "Open the Relationship Manager to choose a relationship to remove.", setOf(Capability.LINKING), openManager),
            RelationshipCommand(RelationshipCommandIds.OPEN_MANAGER, "Relationships", "Inspect and manage relationships for this selection.", setOf(Capability.LINKING), openManager),
            RelationshipCommand(RelationshipCommandIds.ADD_COMMENT, "Comment", "Start an anchored discussion on this selection.", setOf(Capability.COMMENTS), addComment),
            RelationshipCommand(RelationshipCommandIds.REPLY, "Reply to comment", "Open the discussion and reply to a thread.", setOf(Capability.COMMENTS), addComment),
            RelationshipCommand(RelationshipCommandIds.RESOLVE, "Resolve comment", "Open the discussion and resolve a thread.", setOf(Capability.COMMENTS), addComment),
            RelationshipCommand(RelationshipCommandIds.REOPEN, "Reopen comment", "Open resolved discussions and reopen a thread.", setOf(Capability.COMMENTS), addComment),
            RelationshipCommand(RelationshipCommandIds.NEXT_COMMENT, "Next comment", "Navigate to the next anchored discussion.", setOf(Capability.COMMENTS), nextComment),
            RelationshipCommand(RelationshipCommandIds.PREVIOUS_COMMENT, "Previous comment", "Navigate to the previous anchored discussion.", setOf(Capability.COMMENTS), previousComment),
            RelationshipCommand(RelationshipCommandIds.OPEN_REVIEW, "Review", "Inspect discussions, relationships, and review snapshots.", emptySet(), openReview),
            RelationshipCommand(RelationshipCommandIds.CREATE_SNAPSHOT, "Create review snapshot", "Capture a restorable review checkpoint.", emptySet(), createSnapshot),
            RelationshipCommand(RelationshipCommandIds.RESTORE_SNAPSHOT, "Restore review snapshot", "Open Review and choose a protected snapshot to restore.", emptySet(), openReview),
        )
    }
}
