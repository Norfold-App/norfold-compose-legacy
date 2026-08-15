package com.norfold.app.editor.flow

import com.norfold.app.domain.BlockCursor
import com.norfold.app.domain.ContainerAxis
import com.norfold.app.domain.ContainerBlock
import com.norfold.app.domain.DropTarget
import com.norfold.app.domain.ParagraphBlock
import com.norfold.app.domain.findById
import com.norfold.app.domain.locate
import com.norfold.app.domain.withFreshIds
import com.norfold.app.editor.command.ApplicabilityPolicy
import com.norfold.app.editor.command.Capability
import com.norfold.app.editor.command.CommandResult
import com.norfold.app.editor.command.CommandStatus
import com.norfold.app.editor.selection.ApplicabilityResolution

// ── Insertion (roadmap deliverables 2 & 4) ────────────────────────────────────────────────────────────

/**
 * Inserts an empty block of [kind] after the anchor (the primary selection, else the caret's block, else
 * the end of the document). This is the single command behind the touch `+` surface, the slash resolver,
 * and the insert menu — §4.1's "one semantic command, many surfaces", made literal.
 *
 * Context-free by design: insertion needs no selection, so it is never hidden.
 */
class FlowInsertCommand(
    scope: FlowCommandScope,
    private val kind: FlowBlockKind,
) : FlowCommand(scope) {
    override val id = "flow.insert.${kind.commandKey()}"
    override val label = kind.label
    override val description = "Insert ${kind.label}"
    override val category = "Insert"
    override val aliases = kind.aliases + kind.aliases.map { "insert $it" }
    override val requiredCapabilities = emptySet<Capability>()
    override val applicability = ApplicabilityPolicy.SELECTION_AS_UNIT

    override fun runOn(targetIds: List<String>): CommandResult {
        val block = kind.create()
        val anchor = scope.anchorBlockId() ?: scope.document().blocks.lastOrNull()?.id
        edit { session -> session.insertAfter(anchor, block) } ?: return CommandResult.NoOp
        scope.applyOutcome(scope.caretInside(block.id))
        return CommandResult.Success(affectedCount = 1)
    }
}

/**
 * Inserts an empty paragraph *before* the anchor block. The mirror-image "insert below" is
 * `flow.insert.paragraph`, so the two directions exist once each rather than as a duplicated pair.
 */
class FlowInsertAboveCommand(scope: FlowCommandScope) : FlowCommand(scope) {
    override val id = "flow.insert.above"
    override val label = "Insert block above"
    override val category = "Insert"
    override val aliases = listOf("insert above", "above", "before")
    override val requiredCapabilities = emptySet<Capability>()
    override val applicability = ApplicabilityPolicy.PRIMARY_TARGET

    override fun runOn(targetIds: List<String>): CommandResult {
        val document = scope.document()
        val anchor = scope.anchorBlockId()
        val block = ParagraphBlock()
        // insertAfter(null, …) prepends; otherwise insert after the anchor's predecessor in its own
        // sibling list, which is what "above" means inside a container as well as at top level.
        val predecessor = anchor
            ?.let { document.blocks.locate(it) }
            ?.let { (_, index, siblings) -> siblings.getOrNull(index - 1)?.id }
        edit { session -> session.insertAfter(predecessor, block) } ?: return CommandResult.NoOp
        scope.applyOutcome(scope.caretInside(block.id))
        return CommandResult.Success(affectedCount = 1)
    }
}

/**
 * A discoverable picker-backed insertion entry.
 *
 * The command registry owns its label, aliases and availability so slash, the insert palette and accessibility
 * discover the same action. The Android host intercepts this id to obtain a persistable image URI before it
 * asks [FlowHostRuntime] to perform the actual document mutation.
 */
class FlowImagePickerCommand(scope: FlowCommandScope) : FlowCommand(scope) {
    override val id = FlowIds.INSERT_IMAGE
    override val label = "Photo"
    override val description = "Choose a photo from this device"
    override val category = "Insert"
    override val aliases = listOf("image", "photo", "picture", "media")
    override val requiredCapabilities = emptySet<Capability>()
    override val applicability = ApplicabilityPolicy.SELECTION_AS_UNIT

    override fun runOn(targetIds: List<String>): CommandResult = CommandResult.NoOp
}

// ── Conversion (roadmap deliverable 4) ────────────────────────────────────────────────────────────────

/**
 * Turns every convertible selected block into [kind], preserving block ids so History inverses and the
 * one-row-per-top-level-block persistence mapping stay stable.
 *
 * State resolution is the §4.2/§4.11 contract done properly: ACTIVE when every eligible block already is
 * this kind, MIXED when only some are.
 */
class FlowConvertCommand(
    scope: FlowCommandScope,
    private val kind: FlowBlockKind,
) : FlowCommand(scope) {
    override val id = "flow.convert.${kind.commandKey()}"
    override val label = "Turn into ${kind.label.lowercase()}"
    override val category = "Convert"
    // Deliberately *not* the bare kind aliases: those belong to the insert command, and a slash query of
    // "quote" must resolve to one command, not race two.
    override val aliases = kind.aliases.flatMap { listOf("turn into $it", "convert to $it") }
    override val requiredCapabilities = setOf(Capability.CONVERSION)
    override val applicability = ApplicabilityPolicy.ELIGIBLE_SUBSET

    override fun unavailableReason() = "This block cannot become a ${kind.label.lowercase()}"

    override fun refine(resolution: ApplicabilityResolution?): CommandStatus {
        val blocks = eligibleBlockIds().mapNotNull { scope.document().findById(it) }
        if (blocks.isEmpty()) return CommandStatus.Available
        val matching = blocks.count { kind.matches(it) }
        return when (matching) {
            blocks.size -> CommandStatus.Active
            0 -> CommandStatus.Available
            else -> CommandStatus.Mixed
        }
    }

    override fun runOn(targetIds: List<String>): CommandResult {
        val document = scope.document()
        val changed = targetIds.filter { id -> document.findById(id)?.let { !kind.matches(it) } == true }
        if (changed.isEmpty()) return CommandResult.NoOp
        edit { session ->
            changed.forEach { id -> session.document.findById(id)?.let { session.replaceBlock(kind.convert(it)) } }
            BlockCursor(changed.first(), 0)
        } ?: return CommandResult.NoOp
        scope.applyOutcome(scope.selectResult(changed))
        return CommandResult.Success(affectedCount = changed.size)
    }
}

// ── Duplicate / delete ────────────────────────────────────────────────────────────────────────────────

/** Duplicates each selected block immediately below itself, with fresh ids throughout the copied subtree. */
class FlowDuplicateCommand(scope: FlowCommandScope) : FlowCommand(scope) {
    override val id = "flow.duplicate"
    override val label = "Duplicate"
    override val aliases = listOf("duplicate", "copy block", "clone")
    override val requiredCapabilities = setOf(Capability.DUPLICATION)
    override val applicability = ApplicabilityPolicy.ELIGIBLE_SUBSET

    override fun runOn(targetIds: List<String>): CommandResult {
        if (targetIds.isEmpty()) return CommandResult.NoOp
        val copies = mutableListOf<String>()
        edit { session ->
            targetIds.forEach { id ->
                val original = session.document.findById(id) ?: return@forEach
                val copy = original.withFreshIds()
                session.insertAfter(id, copy)
                copies += copy.id
            }
            BlockCursor(copies.firstOrNull() ?: targetIds.first(), 0)
        } ?: return CommandResult.NoOp
        if (copies.isEmpty()) return CommandResult.NoOp
        scope.applyOutcome(scope.selectResult(copies))
        return CommandResult.Success(affectedCount = copies.size)
    }
}

/** Deletes every selected block as one transaction; the nearest surviving block becomes selected (§4.14). */
class FlowDeleteCommand(scope: FlowCommandScope) : FlowCommand(scope) {
    override val id = "flow.delete"
    override val label = "Delete"
    override val aliases = listOf("delete", "remove", "delete block")
    override val requiredCapabilities = setOf(Capability.SELECTION)
    override val applicability = ApplicabilityPolicy.ELIGIBLE_SUBSET

    override fun runOn(targetIds: List<String>): CommandResult {
        if (targetIds.isEmpty()) return CommandResult.NoOp
        val before = scope.document()
        edit { session ->
            targetIds.forEach { session.delete(it) }
            BlockCursor(session.document.blocks.first().id, 0)
        } ?: return CommandResult.NoOp
        scope.applyOutcome(scope.nearestSiblingAfterDelete(before, targetIds.first()))
        return CommandResult.Success(affectedCount = targetIds.size)
    }
}

// ── Reordering (roadmap deliverable 4: move + structural drag/drop) ───────────────────────────────────

/**
 * Moves the primary block one slot up or down **within its own sibling list**, so a block nested in a
 * container reorders inside that container rather than silently escaping it.
 */
class FlowMoveCommand(
    scope: FlowCommandScope,
    private val up: Boolean,
) : FlowCommand(scope) {
    override val id = if (up) "flow.move.up" else "flow.move.down"
    override val label = if (up) "Move up" else "Move down"
    override val aliases = if (up) listOf("move up", "up") else listOf("move down", "down")
    override val requiredCapabilities = setOf(Capability.MOVEMENT)
    override val applicability = ApplicabilityPolicy.PRIMARY_TARGET

    override fun refine(resolution: ApplicabilityResolution?): CommandStatus {
        val id = scope.primaryBlockId() ?: return CommandStatus.Hidden
        val (_, index, siblings) = scope.document().blocks.locate(id) ?: return CommandStatus.Hidden
        val blocked = if (up) index == 0 else index == siblings.lastIndex
        return if (blocked) CommandStatus.disabled(if (up) "Already first" else "Already last") else CommandStatus.Available
    }

    override fun runOn(targetIds: List<String>): CommandResult {
        val id = targetIds.firstOrNull() ?: return CommandResult.NoOp
        val (parentId, index, siblings) = scope.document().blocks.locate(id) ?: return CommandResult.NoOp
        if (up && index == 0) return CommandResult.NoOp
        if (!up && index == siblings.lastIndex) return CommandResult.NoOp
        // `moveInto` compensates for the removal when moving forward, so "one slot down" is index + 2.
        val destination = if (up) index - 1 else index + 2
        edit { session ->
            session.move(id, DropTarget.Into(parentId, destination))
            BlockCursor(id, 0)
        } ?: return CommandResult.NoOp
        scope.applyOutcome(scope.selectResult(listOf(id)))
        return CommandResult.Success(affectedCount = 1)
    }
}

/**
 * Commits a structural drag: the host resolves the gesture into a [DropTarget] on
 * [FlowCommandScope.pendingDrop], and this command applies it. Routing drag/drop through the registry —
 * rather than a private gesture path — is what makes drop placement share undo, eligibility, and
 * accessibility with every other move (§4.1, deliverable 6).
 *
 * Hidden while no drop is pending: an inert "Drop" entry in a menu would be noise.
 */
class FlowDropCommand(scope: FlowCommandScope) : FlowCommand(scope) {
    override val id = "flow.move.drop"
    override val label = "Move to drop target"
    override val aliases = listOf("drop", "move to")
    override val requiredCapabilities = setOf(Capability.MOVEMENT)
    override val applicability = ApplicabilityPolicy.PRIMARY_TARGET

    override fun refine(resolution: ApplicabilityResolution?): CommandStatus =
        if (scope.pendingDrop == null) CommandStatus.Hidden else CommandStatus.Available

    override fun runOn(targetIds: List<String>): CommandResult {
        val id = targetIds.firstOrNull() ?: return CommandResult.NoOp
        val drop = scope.pendingDrop ?: return CommandResult.Failure("No drop target resolved")
        val before = scope.document()
        edit { session ->
            session.move(id, drop)
            BlockCursor(id, 0)
        }
        scope.pendingDrop = null
        if (scope.document() == before) return CommandResult.NoOp
        scope.applyOutcome(scope.selectResult(listOf(id)))
        return CommandResult.Success(affectedCount = 1)
    }
}

// ── Nesting & containers (roadmap deliverable 4: nest/unnest, wrap/unwrap) ────────────────────────────

/** Moves the primary block into the container immediately above it, appending it as the last child. */
class FlowNestCommand(scope: FlowCommandScope) : FlowCommand(scope) {
    override val id = "flow.nest"
    override val label = "Nest into previous container"
    override val aliases = listOf("nest", "indent")
    override val requiredCapabilities = setOf(Capability.MOVEMENT)
    override val applicability = ApplicabilityPolicy.PRIMARY_TARGET

    private fun precedingContainer(): ContainerBlock? {
        val id = scope.primaryBlockId() ?: return null
        val (_, index, siblings) = scope.document().blocks.locate(id) ?: return null
        return siblings.getOrNull(index - 1) as? ContainerBlock
    }

    override fun refine(resolution: ApplicabilityResolution?): CommandStatus =
        if (precedingContainer() == null) CommandStatus.disabled("No container directly above") else CommandStatus.Available

    override fun runOn(targetIds: List<String>): CommandResult {
        val id = targetIds.firstOrNull() ?: return CommandResult.NoOp
        val container = precedingContainer() ?: return CommandResult.NoOp
        edit { session ->
            session.move(id, DropTarget.Into(container.id, container.children.size))
            BlockCursor(id, 0)
        } ?: return CommandResult.NoOp
        scope.applyOutcome(scope.selectResult(listOf(id)))
        return CommandResult.Success(affectedCount = 1)
    }
}

/** Hoists a nested block back to top level, directly after the top-level ancestor that contained it. */
class FlowUnnestCommand(scope: FlowCommandScope) : FlowCommand(scope) {
    override val id = "flow.unnest"
    override val label = "Move out of container"
    override val aliases = listOf("unnest", "outdent", "extract")
    override val requiredCapabilities = setOf(Capability.FRAMING)
    override val applicability = ApplicabilityPolicy.PRIMARY_TARGET

    override fun runOn(targetIds: List<String>): CommandResult {
        val id = targetIds.firstOrNull() ?: return CommandResult.NoOp
        val before = scope.document()
        edit { session ->
            session.extractToTopLevel(id)
            BlockCursor(id, 0)
        }
        if (scope.document() == before) return CommandResult.NoOp
        scope.applyOutcome(scope.selectResult(listOf(id)))
        return CommandResult.Success(affectedCount = 1)
    }
}

/** Wraps the primary block in a new single-child container laid out along [axis]. */
class FlowWrapCommand(
    scope: FlowCommandScope,
    private val axis: ContainerAxis,
) : FlowCommand(scope) {
    override val id = if (axis == ContainerAxis.Row) "flow.wrap.row" else "flow.wrap.column"
    override val label = if (axis == ContainerAxis.Row) "Wrap in row" else "Wrap in column"
    override val aliases =
        if (axis == ContainerAxis.Row) listOf("wrap row", "columns", "side by side") else listOf("wrap column", "stack")
    override val requiredCapabilities = setOf(Capability.GROUPING)
    override val applicability = ApplicabilityPolicy.PRIMARY_TARGET

    override fun runOn(targetIds: List<String>): CommandResult {
        val id = targetIds.firstOrNull() ?: return CommandResult.NoOp
        edit { session ->
            session.wrapInContainer(id, axis)
            BlockCursor(id, 0)
        } ?: return CommandResult.NoOp
        val container = scope.document().blocks.locate(id)?.first
        scope.applyOutcome(scope.selectResult(listOfNotNull(container ?: id)))
        return CommandResult.Success(affectedCount = 1)
    }
}

/** Replaces the selected container with its children, inline in the container's own parent list. */
class FlowUnwrapCommand(scope: FlowCommandScope) : FlowCommand(scope) {
    override val id = "flow.unwrap"
    override val label = "Unwrap container"
    override val aliases = listOf("unwrap", "ungroup", "flatten")
    override val requiredCapabilities = setOf(Capability.GROUPING)
    override val applicability = ApplicabilityPolicy.PRIMARY_TARGET

    override fun refine(resolution: ApplicabilityResolution?): CommandStatus {
        val block = scope.primaryBlock()
        return if (block is ContainerBlock) CommandStatus.Available else CommandStatus.disabled("Not a container")
    }

    override fun runOn(targetIds: List<String>): CommandResult {
        val id = targetIds.firstOrNull() ?: return CommandResult.NoOp
        val container = scope.document().findById(id) as? ContainerBlock ?: return CommandResult.NoOp
        val childIds = container.children.map { it.id }
        edit { session ->
            session.unwrap(id)
            BlockCursor(childIds.firstOrNull() ?: id, 0)
        } ?: return CommandResult.NoOp
        scope.applyOutcome(scope.selectResult(childIds))
        return CommandResult.Success(affectedCount = childIds.size)
    }
}

// ── Split / merge (roadmap deliverable 4) ─────────────────────────────────────────────────────────────

/** Splits the primary block at the caret into two blocks; the caret lands at the start of the new one. */
class FlowSplitCommand(scope: FlowCommandScope) : FlowCommand(scope) {
    override val id = "flow.split"
    override val label = "Split block"
    override val aliases = listOf("split", "break")
    override val requiredCapabilities = setOf(Capability.TEXT_EDITING)
    override val applicability = ApplicabilityPolicy.PRIMARY_TARGET

    private fun caretOffset(): Int? =
        scope.caret()?.takeIf { it.targetId == scope.primaryBlockId() }?.offset

    override fun refine(resolution: ApplicabilityResolution?): CommandStatus =
        if (caretOffset() == null) CommandStatus.disabled("Place the caret where the block should split")
        else CommandStatus.Available

    override fun runOn(targetIds: List<String>): CommandResult {
        val id = targetIds.firstOrNull() ?: return CommandResult.NoOp
        val offset = caretOffset() ?: return CommandResult.Failure("No caret in the selected block")
        val cursor = edit { session -> session.split(id, offset) } ?: return CommandResult.NoOp
        scope.applyOutcome(scope.caretInside(cursor.blockId, cursor.offset))
        return CommandResult.Success(affectedCount = 1)
    }
}

/** Merges the primary block into its previous sibling; the caret lands at the join point. */
class FlowMergePreviousCommand(scope: FlowCommandScope) : FlowCommand(scope) {
    override val id = "flow.merge.previous"
    override val label = "Merge with previous block"
    override val aliases = listOf("merge", "join", "merge up")
    override val requiredCapabilities = setOf(Capability.TEXT_EDITING)
    override val applicability = ApplicabilityPolicy.PRIMARY_TARGET

    override fun refine(resolution: ApplicabilityResolution?): CommandStatus {
        val id = scope.primaryBlockId() ?: return CommandStatus.Hidden
        val (_, index, _) = scope.document().blocks.locate(id) ?: return CommandStatus.Hidden
        return if (index == 0) CommandStatus.disabled("Nothing above to merge into") else CommandStatus.Available
    }

    override fun runOn(targetIds: List<String>): CommandResult {
        val id = targetIds.firstOrNull() ?: return CommandResult.NoOp
        val before = scope.document()
        val cursor = edit { session -> session.mergeWithPrevious(id) } ?: return CommandResult.NoOp
        if (scope.document() == before) return CommandResult.NoOp
        scope.applyOutcome(scope.caretInside(cursor.blockId, cursor.offset))
        return CommandResult.Success(affectedCount = 1)
    }
}

/** Stable, lowercase command-id fragment for a kind (`HEADING_1` → `heading1`). */
internal fun FlowBlockKind.commandKey(): String = name.lowercase().split('_').let { parts ->
    parts.first() + parts.drop(1).joinToString("") { part ->
        if (part.first().isDigit()) part else part.replaceFirstChar { it.uppercase() }
    }
}
