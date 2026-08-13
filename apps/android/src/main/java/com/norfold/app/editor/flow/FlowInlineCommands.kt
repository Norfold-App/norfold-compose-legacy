package com.norfold.app.editor.flow

import com.norfold.app.domain.BlockCursor
import com.norfold.app.domain.BlockEditorSession
import com.norfold.app.domain.InlineMark
import com.norfold.app.domain.InlineTypingStyle
import com.norfold.app.editor.command.ApplicabilityPolicy
import com.norfold.app.editor.command.Capability
import com.norfold.app.editor.command.CommandResult
import com.norfold.app.editor.command.CommandStatus
import com.norfold.app.editor.selection.ApplicabilityResolution

/** A character-formatting command shared by dock, keyboard, menus, and accessibility. */
class FlowInlineMarkCommand(
    scope: FlowCommandScope,
    private val mark: InlineMark,
    override val id: String,
    override val label: String,
    override val aliases: List<String>,
) : FlowCommand(scope) {
    override val description: String = "Toggle ${label.lowercase()} formatting"
    override val category: String = "Text"
    override val requiredCapabilities = setOf(Capability.TEXT_EDITING)
    override val applicability = ApplicabilityPolicy.PRIMARY_TARGET

    override fun refine(resolution: ApplicabilityResolution?): CommandStatus {
        val style = currentStyle() ?: return CommandStatus.disabled("Place the caret in editable text")
        return if (mark in style.marks) CommandStatus.Active else CommandStatus.Available
    }

    override fun runOn(targetIds: List<String>): CommandResult {
        val range = scope.selection.textRange()
        if (range != null && !range.isCollapsed) {
            val start = BlockCursor(range.targetId, range.start, scope.caretItemId)
            val end = BlockCursor(range.targetId, range.end, scope.caretItemId)
            val before = scope.controller.document()
            scope.controller.mutate(label, coalescingKey = null) { session ->
                session.toggleInlineMark(start, end, mark)
            } ?: return CommandResult.NoOp
            return if (scope.controller.document() != before) CommandResult.Success() else CommandResult.NoOp
        }

        val caret = scope.caret() ?: return CommandResult.Failure("Place the caret in editable text")
        val inherited = BlockEditorSession(scope.document()).inlineStyle(
            BlockCursor(caret.targetId, caret.offset, scope.caretItemId),
        )
        val baseline = scope.typingStyle ?: inherited
        scope.typingStyle = baseline.toggled(mark)
        return CommandResult.Success(affectedCount = 0)
    }

    override fun isEligible(target: com.norfold.app.editor.selection.SelectionTarget): Boolean =
        super.isEligible(target) &&
            (scope.caret()?.targetId == target.id || scope.selection.textRange()?.targetId == target.id)

    private fun currentStyle(): InlineTypingStyle? {
        val range = scope.selection.textRange()
        if (range != null) {
            return BlockEditorSession(scope.document()).inlineStyle(
                BlockCursor(range.targetId, range.start, scope.caretItemId),
                BlockCursor(range.targetId, range.end, scope.caretItemId),
            )
        }
        val caret = scope.caret() ?: return null
        return scope.typingStyle ?: BlockEditorSession(scope.document()).inlineStyle(
                BlockCursor(caret.targetId, caret.offset, scope.caretItemId),
            )
    }
}

object FlowInlineCommands {
    fun catalogue(scope: FlowCommandScope) = listOf(
        FlowInlineMarkCommand(scope, InlineMark.Bold, FlowIds.BOLD, "Bold", listOf("bold", "strong")),
        FlowInlineMarkCommand(scope, InlineMark.Italic, FlowIds.ITALIC, "Italic", listOf("italic", "emphasis")),
        FlowInlineMarkCommand(scope, InlineMark.Underline, FlowIds.UNDERLINE, "Underline", listOf("underline")),
        FlowInlineMarkCommand(scope, InlineMark.Strikethrough, FlowIds.STRIKETHROUGH, "Strikethrough", listOf("strike", "strikethrough")),
        FlowInlineMarkCommand(scope, InlineMark.Code, FlowIds.INLINE_CODE, "Inline code", listOf("inline code", "code span")),
    )
}
