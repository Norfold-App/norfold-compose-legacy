package com.norfold.app.editor.flow

import com.norfold.app.domain.BlockCursor
import com.norfold.app.domain.TableBlock
import com.norfold.app.editor.command.ApplicabilityPolicy
import com.norfold.app.editor.command.Capability
import com.norfold.app.editor.command.CommandResult
import com.norfold.app.editor.command.CommandStatus
import com.norfold.app.editor.selection.ApplicabilityResolution

/** Which way a table structure command inserts, relative to the addressed cell (§4.15, §7.4–§7.5). */
enum class FlowTableDirection(val label: String, val aliases: List<String>) {
    ROW_ABOVE("Insert row above", listOf("row above", "insert row above")),
    ROW_BELOW("Insert row below", listOf("row below", "insert row below")),
    COLUMN_LEFT("Insert column left", listOf("column left", "insert column left")),
    COLUMN_RIGHT("Insert column right", listOf("column right", "insert column right")),
    ;

    val isRow: Boolean get() = this == ROW_ABOVE || this == ROW_BELOW
}

/**
 * The directional table insertion hooks required by roadmap Phase 1 deliverable 8.
 *
 * Four separate commands, each with an unambiguous direction — never one `addRow()` whose meaning depends
 * on hidden state (§4.15). They are registered in the shared [com.norfold.app.editor.command.CommandRegistry]
 * now so Phase 4 can attach full table/calculation semantics (formula reference rewriting, spreadsheet
 * behavior) to the *same* commands rather than introducing a parallel surface.
 *
 * A direction only exists relative to a cell, so these are disabled — with a reason the user can act on —
 * whenever no cell is addressed by [FlowCommandScope.tableCursor]. Guessing "the end of the table" is
 * exactly the ambiguity the spec rejects.
 */
class FlowTableInsertCommand(
    scope: FlowCommandScope,
    private val direction: FlowTableDirection,
) : FlowCommand(scope) {
    override val id = "flow.table.insert${direction.name.toPascal()}"
    override val label = direction.label
    override val category = "Table"
    override val aliases = direction.aliases
    override val requiredCapabilities = setOf(Capability.TABLE_STRUCTURE)
    override val applicability = ApplicabilityPolicy.PRIMARY_TARGET

    /** The addressed cell, but only when it really points at the selected table. */
    private fun cursor(): FlowTableCursor? =
        scope.tableCursor?.takeIf { it.blockId == scope.primaryBlockId() }

    private fun table(): TableBlock? = scope.primaryBlock() as? TableBlock

    override fun refine(resolution: ApplicabilityResolution?): CommandStatus {
        val cursor = cursor() ?: return CommandStatus.disabled("Place the cursor in a table cell first")
        if (direction == FlowTableDirection.ROW_ABOVE && cursor.row < 0) {
            return CommandStatus.disabled("The header row is always first")
        }
        return CommandStatus.Available
    }

    override fun runOn(targetIds: List<String>): CommandResult {
        val id = targetIds.firstOrNull() ?: return CommandResult.NoOp
        val table = table() ?: return CommandResult.NoOp
        val cursor = cursor() ?: return CommandResult.Failure("No table cell is addressed")

        val insertIndex = when (direction) {
            // The header row is not a data row, so a cursor on it (row -1) means "before the first data row".
            FlowTableDirection.ROW_ABOVE -> cursor.row
            FlowTableDirection.ROW_BELOW -> cursor.row + 1
            FlowTableDirection.COLUMN_LEFT -> cursor.column
            FlowTableDirection.COLUMN_RIGHT -> cursor.column + 1
        }
        if (direction == FlowTableDirection.ROW_ABOVE && insertIndex < 0) {
            return CommandResult.Failure("The header row is always first")
        }
        if (table.calculationPayload == null) {
            scope.controller.snapshotBeforeMigration("Upgrade table ${table.id} for ${direction.label.lowercase()}")
        }
        val updated = if (direction.isRow) {
            FlowTableOps.insertRow(table, insertIndex)
        } else {
            FlowTableOps.insertColumn(table, insertIndex)
        }
        edit { session ->
            session.replaceBlock(updated)
            BlockCursor(id, 0)
        } ?: return CommandResult.NoOp

        // The caret follows the structure the user just created — the table analogue of §4.14's
        // "caret inside the inserted thing".
        scope.tableCursor = if (direction.isRow) {
            cursor.copy(row = insertIndex.coerceAtLeast(0))
        } else {
            cursor.copy(column = insertIndex)
        }
        scope.applyOutcome(scope.selectResult(listOf(id)))
        return CommandResult.Success(affectedCount = 1)
    }
}

/** `ROW_ABOVE` → `RowAbove`, so the command id reads `flow.table.insertRowAbove`. */
private fun String.toPascal(): String =
    split('_').joinToString("") { part -> part.lowercase().replaceFirstChar { it.uppercase() } }
