package com.norfold.app.editor.style

import com.norfold.app.editor.command.ApplicabilityPolicy
import com.norfold.app.editor.command.Capability
import com.norfold.app.editor.command.CommandContext
import com.norfold.app.editor.command.CommandRegistry
import com.norfold.app.editor.command.CommandResult
import com.norfold.app.editor.command.CommandStatus
import com.norfold.app.editor.command.EditorCommand

/** Stable command ids shared by Flow, Document, command search and accessibility. */
object EditorStyleCommandIds {
    const val TEXT_COLOR = "editor.style.textColor"
    const val HIGHLIGHT = "editor.style.highlight"
    const val FILL = "editor.style.fill"
    const val STROKE = "editor.style.stroke"
    const val PAGE_BACKGROUND = "editor.style.pageBackground"
    const val FONT_BROWSER = "editor.style.fontBrowser"
    const val NAMED_STYLES = "editor.style.namedStyles"
    const val CLEAR_FORMATTING = "editor.style.clearFormatting"
}

/** Registers Phase-3 commands once; hosts provide only their target-opening callbacks. */
object EditorStyleCommands {
    fun register(
        registry: CommandRegistry,
        textColor: () -> CommandResult,
        highlight: () -> CommandResult,
        fill: () -> CommandResult,
        stroke: () -> CommandResult,
        pageBackground: () -> CommandResult,
        fontBrowser: () -> CommandResult,
        namedStyles: () -> CommandResult,
        clearFormatting: () -> CommandResult,
    ) {
        listOf(
            command(EditorStyleCommandIds.TEXT_COLOR, "Text color", "Choose text color", "Color", textColor),
            command(EditorStyleCommandIds.HIGHLIGHT, "Highlight", "Choose text highlight", "Color", highlight),
            command(EditorStyleCommandIds.FILL, "Fill", "Choose object fill", "Color", fill),
            command(EditorStyleCommandIds.STROKE, "Stroke", "Choose object stroke", "Color", stroke),
            command(EditorStyleCommandIds.PAGE_BACKGROUND, "Page color", "Choose page background", "Color", pageBackground),
            command(EditorStyleCommandIds.FONT_BROWSER, "Font", "Browse installed and imported fonts", "Typography", fontBrowser),
            command(EditorStyleCommandIds.NAMED_STYLES, "Styles", "Apply or manage named styles", "Typography", namedStyles),
            command(EditorStyleCommandIds.CLEAR_FORMATTING, "Clear formatting", "Remove local style overrides", "Typography", clearFormatting),
        ).forEach(registry::register)
    }

    private fun command(
        id: String,
        label: String,
        description: String,
        category: String,
        action: () -> CommandResult,
    ): EditorCommand = object : EditorCommand {
        override val id = id
        override val label = label
        override val description = description
        override val icon: String? = null
        override val category = category
        override val aliases = listOf(label.lowercase(), description.lowercase())
        override val requiredCapabilities = emptySet<Capability>()
        override val applicability = ApplicabilityPolicy.ELIGIBLE_SUBSET
        override fun status(context: CommandContext): CommandStatus =
            if (context.hasSelection) CommandStatus.Available else CommandStatus.Hidden
        override fun perform(context: CommandContext): CommandResult = action()
    }
}
