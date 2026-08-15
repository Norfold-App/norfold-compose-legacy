package com.norfold.app.editor.flow

import com.norfold.app.domain.CodeBlock
import com.norfold.app.domain.DocumentBlock
import com.norfold.app.domain.findById
import com.norfold.app.domain.MathBlock
import com.norfold.app.domain.MermaidBlock
import com.norfold.app.domain.TodoListBlock
import com.norfold.app.editor.command.CommandRegistry
import com.norfold.app.editor.command.CommandResult
import com.norfold.app.editor.command.CommandStatus
import com.norfold.app.editor.command.CommandSurface
import com.norfold.app.editor.command.EditorCommand

/**
 * Where the caret is typing, for slash suppression (roadmap Phase 1 deliverable 3).
 *
 * In [CODE], [EQUATION] and [DIAGRAM] a `/` is content — a division operator, a closing tag, a LaTeX
 * command, a Mermaid link — so the resolver never opens there. Everything else is [PROSE].
 */
enum class FlowSlashContext {
    PROSE,
    CODE,
    EQUATION,
    DIAGRAM,
    ;

    companion object {
        /** The context implied by the block being edited. */
        fun of(block: DocumentBlock): FlowSlashContext = when (block) {
            is CodeBlock -> CODE
            is MathBlock -> EQUATION
            is MermaidBlock -> DIAGRAM
            else -> PROSE
        }
    }
}

/**
 * What the host knows about the text being typed when it asks the resolver whether a slash menu is open.
 *
 * @param text the full plain text of the edited block or list item.
 * @param caret caret offset within [text].
 * @param context block-level suppression context.
 * @param literalInline true when the caret sits inside an inline span where `/` is content — inline code or
 *   inline math. The host computes this from the inline style at the caret; the resolver cannot see inline
 *   nodes through a plain string, and guessing from the text would be wrong.
 * @param itemId list/todo item the text belongs to, when the caret is inside one.
 */
data class FlowSlashInput(
    val text: String,
    val caret: Int,
    val context: FlowSlashContext = FlowSlashContext.PROSE,
    val literalInline: Boolean = false,
    val itemId: String? = null,
)

/** An open slash query: the trigger `/` at [triggerOffset], and the text typed after it up to the caret. */
data class FlowSlashQuery(val triggerOffset: Int, val query: String) {
    /** Offset just past the query — `text.removeRange(triggerOffset, endOffset)` erases the whole trigger. */
    val endOffset: Int get() = triggerOffset + 1 + query.length
}

/** One ranked row in the slash menu. [status] drives presentation exactly as it does in the dock (§4.2). */
data class FlowSlashResult(val command: EditorCommand, val status: CommandStatus)

/** An open slash menu: the query that produced it and its ranked, context-filtered results. */
data class FlowSlashSession(
    val query: FlowSlashQuery,
    val results: List<FlowSlashResult>,
    /** Index used by hardware arrows / Enter as well as the visibly highlighted touch row. */
    val selectedIndex: Int = 0,
) {
    val selected: FlowSlashResult? get() = results.getOrNull(selectedIndex)
}

/**
 * The Slash Local Command Resolver (roadmap Phase 1 deliverable 3).
 *
 * Two locked properties shape this design:
 *
 *  1. **The typed `/query` *is* the search — there is no second search field.** So the resolver has no
 *     state of its own beyond what the block text already says: [resolve] is a pure function of the text
 *     and caret. Nothing can drift out of sync with what the user typed, and the host never has to mirror
 *     the query into a separate input.
 *  2. **Slash is suppressed where `/` is content.** URLs, filesystem paths, dates, fractions, code blocks,
 *     equations and diagrams all contain slashes that must stay literal. The rules are in [detect].
 *
 * Results come from the same [CommandRegistry] the touch `+` surface and the keyboard map use, so a command
 * reached by slash has identical eligibility, state, execution and undo to the same command reached any
 * other way (§4.1). Rows that the current context hides are not listed; rows that are merely disabled are
 * listed with their reason, because "why can't I do this here" is more useful than a silently missing entry.
 *
 * Pure-JVM. No Android, no persistence.
 */
class FlowSlashResolver(
    private val scope: FlowCommandScope,
    private val registry: CommandRegistry,
) {

    /**
     * The open slash query at the caret, or null when slash is not active. Suppression rules, in order:
     *
     *  - the block is code / an equation / a diagram, or the caret is inside an inline literal span;
     *  - there is no `/` before the caret on the current line;
     *  - the `/` is not the first non-whitespace character on its line. Commands belong to an empty block
     *    (or an indented list line), not after ordinary prose; that one rule also covers URLs, paths, dates
     *    and fractions without an ever-growing exception list;
     *  - the trigger is doubled (`//`), which is how a user types a literal slash-comment;
     *  - the query runs past [MAX_QUERY_LENGTH] or crosses a line break — the user is writing prose.
     */
    fun detect(input: FlowSlashInput): FlowSlashQuery? {
        if (input.context != FlowSlashContext.PROSE) return null
        if (input.literalInline) return null
        val caret = input.caret.coerceIn(0, input.text.length)
        val trigger = input.text.lastIndexOf('/', caret - 1)
        if (trigger < 0) return null
        val lineStart = input.text.lastIndexOf('\n', trigger - 1).let { if (it < 0) 0 else it + 1 }
        if (input.text.substring(lineStart, trigger).any { !it.isWhitespace() }) return null
        if (input.text.getOrNull(trigger + 1) == '/') return null
        val query = input.text.substring(trigger + 1, caret)
        if (query.length > MAX_QUERY_LENGTH) return null
        if (query.any { it == '\n' || it == '\r' }) return null
        return FlowSlashQuery(trigger, query)
    }

    /**
     * The open slash menu at the caret, or null when slash is not active.
     *
     * A query containing whitespace stays open only while it still matches something — that is what lets
     * `/turn into h1` work while `/ and then we left` quietly stops being a command as soon as it stops
     * meaning one, with no arbitrary word limit to tune.
     */
    fun resolve(input: FlowSlashInput, limit: Int = DEFAULT_LIMIT): FlowSlashSession? {
        val query = detect(input) ?: return null
        val results = results(query, limit)
        if (results.isEmpty() && query.query.any { it.isWhitespace() }) return null
        return FlowSlashSession(query, results)
    }

    /**
     * Ranked, context-filtered results for [query]. An empty query lists the catalogue in registration
     * order — insertion first — which is the same compact palette the touch `+` surface shows.
     */
    fun results(query: FlowSlashQuery, limit: Int = DEFAULT_LIMIT): List<FlowSlashResult> {
        val context = scope.commandContext(CommandSurface.SLASH)
        val visible = registry.all()
            .map { it to it.status(context) }
            .filter { (_, status) -> status.isVisible }

        if (query.query.isBlank()) {
            return visible.take(limit).map { (command, status) -> FlowSlashResult(command, status) }
        }
        val needle = query.query.trim().lowercase()
        return visible
            .mapNotNull { (command, status) ->
                score(command, needle)?.let { Triple(command, status, it) }
            }
            .sortedBy { (_, _, score) -> score }
            .take(limit)
            .map { (command, status, _) -> FlowSlashResult(command, status) }
    }

    /**
     * Erases the typed `/query` and runs [command], as **one** undo unit.
     *
     * Composing the erase and the command into a single transaction is the whole reason
     * [FlowEditController.transaction] exists: undoing a slash invocation must restore the text the user
     * typed, not leave the document with the trigger silently eaten.
     *
     * The erase dispatches on the addressed block, not on whether an item id happens to be present. A to-do
     * list holds `TodoItem` and a bullet/numbered list holds `ListItem`; they share no supertype, so
     * `editListItem` finds nothing in a to-do and returns without editing. The typed `/query` was then left in
     * the text while the command still ran — and being inside the composite, the undo step recorded a document
     * the user never saw. Asking the block which vocabulary it speaks is the only thing that gets both right.
     *
     * @param blockId the block whose text carries the query.
     */
    fun accept(blockId: String, input: FlowSlashInput, command: EditorCommand): CommandResult {
        val query = detect(input) ?: return CommandResult.Failure("No slash query at the caret")
        val block = scope.controller.document().findById(blockId)
            ?: return CommandResult.Failure("The block being edited no longer exists")
        if (input.itemId != null && FlowInlineText.itemsOf(block)?.any { it.id == input.itemId } != true) {
            // The item is gone (a remote merge, an undo). Running the command would leave the trigger typed
            // into text that no longer exists, so fail before opening a transaction.
            return CommandResult.Failure("The item being edited no longer exists")
        }
        val status = command.status(scope.commandContext(CommandSurface.SLASH))
        if (!status.isInvokable) {
            return CommandResult.Failure(status.reason ?: "'${command.label}' is not available here")
        }
        val cleaned = input.text.removeRange(query.triggerOffset, query.endOffset.coerceAtMost(input.text.length))
        return scope.controller.transaction(command.label) {
            scope.controller.mutate("Remove slash query") { session ->
                when {
                    input.itemId == null -> session.editText(blockId, input.text, cleaned)
                    block is TodoListBlock -> session.editTodoItem(blockId, input.itemId, input.text, cleaned)
                    else -> session.editListItem(blockId, input.itemId, input.text, cleaned)
                }
            }
            registry.execute(command.id, scope.commandContext(CommandSurface.SLASH))
        }
    }

    /**
     * Match rank — lower is better. Null means "no match", which is how a command is dropped from the list.
     * The order is the one a command palette needs: exact identity first, then prefixes, then substrings,
     * with ids and labels ahead of aliases so the canonical name always wins a tie.
     */
    private fun score(command: EditorCommand, needle: String): Int? {
        val id = command.id.lowercase()
        val label = command.label.lowercase()
        val aliases = command.aliases.map { it.lowercase() }
        return when {
            id == needle -> 0
            aliases.any { it == needle } -> 1
            label == needle -> 2
            label.startsWith(needle) -> 3
            aliases.any { it.startsWith(needle) } -> 4
            id.substringAfterLast('.').startsWith(needle) -> 5
            label.contains(needle) -> 6
            aliases.any { it.contains(needle) } -> 7
            id.contains(needle) -> 8
            else -> null
        }
    }

    companion object {
        /** Past this many characters the user is writing prose, not searching commands. */
        const val MAX_QUERY_LENGTH = 32

        /** Slash results stay compact; the host shows this many rows and the user keeps typing to narrow. */
        const val DEFAULT_LIMIT = 8
    }
}
