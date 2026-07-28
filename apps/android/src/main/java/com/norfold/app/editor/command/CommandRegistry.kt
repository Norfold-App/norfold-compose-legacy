package com.norfold.app.editor.command

/**
 * The single source of truth for every editor command (spec §4.1). A command is registered once and then
 * surfaced identically from the dock, slash menu, command explorer, keyboard map, context menu, inspector,
 * and accessibility tree — this registry is what makes "one semantic command, many surfaces" literal.
 *
 * Responsibilities:
 *  - hold the canonical [EditorCommand] set, keyed by id (duplicate ids are a programming error);
 *  - resolve, for any [CommandContext], which commands are visible and what state each is in (§4.2);
 *  - look commands up by id or alias for slash / command-explorer search (§4.4);
 *  - dispatch execution through the command's own eligibility guard (§4.17).
 *
 * Not thread-safe; the editor loop serializes access, matching the History Engine. Pure-JVM.
 */
class CommandRegistry {
    private val commands = LinkedHashMap<String, EditorCommand>()

    /** Registers [command]. Throws if its id is already registered (commands are defined once). */
    fun register(command: EditorCommand): CommandRegistry {
        require(command.id !in commands) { "Duplicate command id '${command.id}'" }
        commands[command.id] = command
        return this
    }

    fun registerAll(vararg toRegister: EditorCommand): CommandRegistry {
        toRegister.forEach { register(it) }
        return this
    }

    fun get(id: String): EditorCommand? = commands[id]

    fun all(): List<EditorCommand> = commands.values.toList()

    val size: Int get() = commands.size

    /**
     * Looks a command up by exact id, then by exact alias, then (if [fuzzy]) by label/alias prefix — the
     * resolution order a slash menu or command explorer uses. Returns null if nothing matches.
     */
    fun resolve(query: String, fuzzy: Boolean = false): EditorCommand? {
        val q = query.trim()
        if (q.isEmpty()) return null
        commands[q]?.let { return it }
        commands.values.firstOrNull { cmd -> cmd.aliases.any { it.equals(q, ignoreCase = true) } }?.let { return it }
        if (!fuzzy) return null
        val lower = q.lowercase()
        return commands.values.firstOrNull { cmd ->
            cmd.label.lowercase().startsWith(lower) || cmd.aliases.any { it.lowercase().startsWith(lower) }
        }
    }

    /** All commands matching [query] by id/label/alias substring, for a search field (§4.4). */
    fun search(query: String): List<EditorCommand> {
        val lower = query.trim().lowercase()
        if (lower.isEmpty()) return emptyList()
        return commands.values.filter { cmd ->
            cmd.id.lowercase().contains(lower) ||
                cmd.label.lowercase().contains(lower) ||
                cmd.aliases.any { it.lowercase().contains(lower) }
        }
    }

    /** Resolves the [CommandStatus] of every command for [context] (presentation-independent, §4.2). */
    fun statuses(context: CommandContext): Map<EditorCommand, CommandStatus> =
        commands.values.associateWith { it.status(context) }

    /** Commands that should be shown for [context] (any non-hidden state), in registration order. */
    fun visible(context: CommandContext): List<EditorCommand> =
        commands.values.filter { it.status(context).isVisible }

    /** Commands the user can actually invoke right now (§4.2 available/active/mixed). */
    fun invokable(context: CommandContext): List<EditorCommand> =
        commands.values.filter { it.status(context).isInvokable }

    /**
     * Dispatches [commandId] against [context] through the command's own eligibility guard. Returns a
     * [CommandResult.Failure] if the id is unknown, so no caller can execute a command that isn't registered.
     */
    fun execute(commandId: String, context: CommandContext): CommandResult {
        val command = commands[commandId]
            ?: return CommandResult.Failure("Unknown command '$commandId'")
        return command.execute(context)
    }
}
