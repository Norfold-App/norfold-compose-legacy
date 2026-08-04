package com.norfold.app.editor.calculation

import java.math.BigDecimal

sealed interface FormulaExpression {
    data class NumberLiteral(val value: BigDecimal) : FormulaExpression
    data class TextLiteral(val value: String) : FormulaExpression
    data class BooleanLiteral(val value: Boolean) : FormulaExpression
    data class Unary(val operator: UnaryOperator, val operand: FormulaExpression) : FormulaExpression
    data class Binary(
        val left: FormulaExpression,
        val operator: BinaryOperator,
        val right: FormulaExpression,
    ) : FormulaExpression
    data class FunctionCall(val name: String, val arguments: List<FormulaExpression>) : FormulaExpression
    data class CellReference(val reference: A1Reference) : FormulaExpression
    data class RangeReference(val start: A1Reference, val end: A1Reference) : FormulaExpression
    data class NamedReference(val name: String) : FormulaExpression
    data class StructuredReference(val tableName: String, val columnName: String) : FormulaExpression
    data class ErrorLiteral(val error: CalculationError) : FormulaExpression
}

enum class UnaryOperator { PLUS, MINUS, NOT, PERCENT }

enum class BinaryOperator {
    ADD,
    SUBTRACT,
    MULTIPLY,
    DIVIDE,
    POWER,
    CONCATENATE,
    EQUAL,
    NOT_EQUAL,
    LESS,
    LESS_OR_EQUAL,
    GREATER,
    GREATER_OR_EQUAL,
}

data class A1Reference(
    val sheetName: String? = null,
    val row: Int,
    val column: Int,
    val absoluteRow: Boolean = false,
    val absoluteColumn: Boolean = false,
) {
    init {
        require(row >= 0 && column >= 0) { "Formula coordinates cannot be negative" }
    }

    fun render(): String = buildString {
        sheetName?.let {
            append(if (it.matches(Regex("[A-Za-z_][A-Za-z0-9_.]*"))) it else "'${it.replace("'", "''")}'")
            append('!')
        }
        if (absoluteColumn) append('$')
        append(columnName(column))
        if (absoluteRow) append('$')
        append(row + 1)
    }
}

sealed interface FormulaParseResult {
    data class Success(val expression: FormulaExpression) : FormulaParseResult
    data class Failure(val error: CalculationError) : FormulaParseResult
}

object FormulaRenderer {
    fun render(expression: FormulaExpression): String = "=" + renderNode(expression, 0)

    private fun renderNode(expression: FormulaExpression, parentPrecedence: Int): String {
        val rendered = when (expression) {
            is FormulaExpression.NumberLiteral -> expression.value.stripTrailingZeros().toPlainString()
            is FormulaExpression.TextLiteral -> "\"${expression.value.replace("\"", "\"\"")}\""
            is FormulaExpression.BooleanLiteral -> if (expression.value) "TRUE" else "FALSE"
            is FormulaExpression.CellReference -> expression.reference.render()
            is FormulaExpression.RangeReference -> "${expression.start.render()}:${expression.end.render()}"
            is FormulaExpression.NamedReference -> expression.name
            is FormulaExpression.StructuredReference -> "${expression.tableName}[${expression.columnName}]"
            is FormulaExpression.ErrorLiteral -> expression.error.displayLabel
            is FormulaExpression.FunctionCall ->
                "${expression.name.uppercase()}(${expression.arguments.joinToString(",") { renderNode(it, 0) }})"
            is FormulaExpression.Unary -> {
                val token = when (expression.operator) {
                    UnaryOperator.PLUS -> "+"
                    UnaryOperator.MINUS -> "-"
                    UnaryOperator.NOT -> "NOT "
                    UnaryOperator.PERCENT -> ""
                }
                if (expression.operator == UnaryOperator.PERCENT) renderNode(expression.operand, 80) + "%" else token + renderNode(expression.operand, 80)
            }
            is FormulaExpression.Binary -> {
                val precedence = expression.operator.precedence
                val token = expression.operator.token
                val body = renderNode(expression.left, precedence) + token + renderNode(expression.right, precedence + 1)
                if (precedence < parentPrecedence) "($body)" else body
            }
        }
        return rendered
    }
}

private val BinaryOperator.precedence: Int
    get() = when (this) {
        BinaryOperator.EQUAL,
        BinaryOperator.NOT_EQUAL,
        BinaryOperator.LESS,
        BinaryOperator.LESS_OR_EQUAL,
        BinaryOperator.GREATER,
        BinaryOperator.GREATER_OR_EQUAL,
        -> 10
        BinaryOperator.CONCATENATE -> 20
        BinaryOperator.ADD, BinaryOperator.SUBTRACT -> 30
        BinaryOperator.MULTIPLY, BinaryOperator.DIVIDE -> 40
        BinaryOperator.POWER -> 50
    }

private val BinaryOperator.token: String
    get() = when (this) {
        BinaryOperator.ADD -> "+"
        BinaryOperator.SUBTRACT -> "-"
        BinaryOperator.MULTIPLY -> "*"
        BinaryOperator.DIVIDE -> "/"
        BinaryOperator.POWER -> "^"
        BinaryOperator.CONCATENATE -> "&"
        BinaryOperator.EQUAL -> "="
        BinaryOperator.NOT_EQUAL -> "<>"
        BinaryOperator.LESS -> "<"
        BinaryOperator.LESS_OR_EQUAL -> "<="
        BinaryOperator.GREATER -> ">"
        BinaryOperator.GREATER_OR_EQUAL -> ">="
    }

object FormulaReferenceTransform {
    fun map(expression: FormulaExpression, transform: (A1Reference) -> A1Reference?): FormulaExpression =
        when (expression) {
            is FormulaExpression.CellReference -> transform(expression.reference)?.let(FormulaExpression::CellReference)
                ?: FormulaExpression.ErrorLiteral(CalculationError(CalculationErrorKind.REFERENCE, "Deleted reference"))
            is FormulaExpression.RangeReference -> {
                val start = transform(expression.start)
                val end = transform(expression.end)
                if (start == null || end == null) {
                    FormulaExpression.ErrorLiteral(CalculationError(CalculationErrorKind.REFERENCE, "Deleted range"))
                } else {
                    FormulaExpression.RangeReference(start, end)
                }
            }
            is FormulaExpression.Unary -> expression.copy(operand = map(expression.operand, transform))
            is FormulaExpression.Binary -> expression.copy(
                left = map(expression.left, transform),
                right = map(expression.right, transform),
            )
            is FormulaExpression.FunctionCall -> expression.copy(arguments = expression.arguments.map { map(it, transform) })
            else -> expression
        }

    fun renameSheet(expression: FormulaExpression, oldName: String, newName: String): FormulaExpression =
        map(expression) { ref ->
            if (ref.sheetName.equals(oldName, ignoreCase = true)) ref.copy(sheetName = newName) else ref
        }

    fun renameSymbol(
        expression: FormulaExpression,
        oldName: String,
        newName: String,
        tableName: String? = null,
    ): FormulaExpression = when (expression) {
        is FormulaExpression.NamedReference ->
            if (tableName == null && expression.name.equals(oldName, true)) expression.copy(name = newName) else expression
        is FormulaExpression.StructuredReference -> when {
            tableName == null && expression.tableName.equals(oldName, true) -> expression.copy(tableName = newName)
            tableName != null && expression.tableName.equals(tableName, true) && expression.columnName.equals(oldName, true) ->
                expression.copy(columnName = newName)
            else -> expression
        }
        is FormulaExpression.Unary -> expression.copy(operand = renameSymbol(expression.operand, oldName, newName, tableName))
        is FormulaExpression.Binary -> expression.copy(
            left = renameSymbol(expression.left, oldName, newName, tableName),
            right = renameSymbol(expression.right, oldName, newName, tableName),
        )
        is FormulaExpression.FunctionCall -> expression.copy(
            arguments = expression.arguments.map { renameSymbol(it, oldName, newName, tableName) },
        )
        else -> expression
    }

    fun deleteSymbol(expression: FormulaExpression, name: String): FormulaExpression = when (expression) {
        is FormulaExpression.NamedReference -> if (expression.name.equals(name, true)) {
            FormulaExpression.ErrorLiteral(CalculationError(CalculationErrorKind.NAME, "Deleted name $name"))
        } else expression
        is FormulaExpression.Unary -> expression.copy(operand = deleteSymbol(expression.operand, name))
        is FormulaExpression.Binary -> expression.copy(
            left = deleteSymbol(expression.left, name),
            right = deleteSymbol(expression.right, name),
        )
        is FormulaExpression.FunctionCall -> expression.copy(arguments = expression.arguments.map { deleteSymbol(it, name) })
        else -> expression
    }

    fun shiftForInsert(
        expression: FormulaExpression,
        editedSheetName: String,
        ownerSheetName: String = editedSheetName,
        rowIndex: Int? = null,
        columnIndex: Int? = null,
        count: Int = 1,
    ): FormulaExpression = map(expression) { ref ->
        if (ref.sheetName == null && !ownerSheetName.equals(editedSheetName, ignoreCase = true)) return@map ref
        if (ref.sheetName != null && !ref.sheetName.equals(editedSheetName, ignoreCase = true)) return@map ref
        ref.copy(
            row = if (rowIndex != null && ref.row >= rowIndex) ref.row + count else ref.row,
            column = if (columnIndex != null && ref.column >= columnIndex) ref.column + count else ref.column,
        )
    }

    fun shiftForDelete(
        expression: FormulaExpression,
        editedSheetName: String,
        ownerSheetName: String = editedSheetName,
        rowIndex: Int? = null,
        columnIndex: Int? = null,
        count: Int = 1,
    ): FormulaExpression {
        fun applies(ref: A1Reference): Boolean = when {
            ref.sheetName == null -> ownerSheetName.equals(editedSheetName, true)
            else -> ref.sheetName.equals(editedSheetName, true)
        }
        fun shift(ref: A1Reference): A1Reference? {
            if (!applies(ref)) return ref
            return when {
                rowIndex != null && ref.row in rowIndex until rowIndex + count -> null
                columnIndex != null && ref.column in columnIndex until columnIndex + count -> null
                else -> ref.copy(
                    row = if (rowIndex != null && ref.row >= rowIndex + count) ref.row - count else ref.row,
                    column = if (columnIndex != null && ref.column >= columnIndex + count) ref.column - count else ref.column,
                )
            }
        }
        fun rangeBoundary(original: A1Reference, other: A1Reference, isStart: Boolean): A1Reference? {
            shift(original)?.let { return it }
            if (!applies(original)) return original
            return when {
                rowIndex != null -> {
                    val survivingOther = shift(other) ?: return null
                    val replacement = if (isStart) rowIndex else rowIndex - 1
                    if (replacement < 0) null else original.copy(row = replacement.coerceAtMost(survivingOther.row))
                }
                columnIndex != null -> {
                    val survivingOther = shift(other) ?: return null
                    val replacement = if (isStart) columnIndex else columnIndex - 1
                    if (replacement < 0) null else original.copy(column = replacement.coerceAtMost(survivingOther.column))
                }
                else -> null
            }
        }
        fun rewrite(node: FormulaExpression): FormulaExpression = when (node) {
            is FormulaExpression.CellReference -> shift(node.reference)?.let(FormulaExpression::CellReference)
                ?: FormulaExpression.ErrorLiteral(CalculationError(CalculationErrorKind.REFERENCE, "Deleted reference"))
            is FormulaExpression.RangeReference -> {
                val start = rangeBoundary(node.start, node.end, true)
                val end = rangeBoundary(node.end, node.start, false)
                if (start == null || end == null) FormulaExpression.ErrorLiteral(CalculationError(CalculationErrorKind.REFERENCE, "Deleted range"))
                else FormulaExpression.RangeReference(start, end)
            }
            is FormulaExpression.Unary -> node.copy(operand = rewrite(node.operand))
            is FormulaExpression.Binary -> node.copy(left = rewrite(node.left), right = rewrite(node.right))
            is FormulaExpression.FunctionCall -> node.copy(arguments = node.arguments.map(::rewrite))
            else -> node
        }
        return rewrite(expression)
    }

    fun offsetForFill(expression: FormulaExpression, rowDelta: Int, columnDelta: Int): FormulaExpression =
        map(expression) { ref ->
            val row = if (ref.absoluteRow) ref.row else ref.row + rowDelta
            val column = if (ref.absoluteColumn) ref.column else ref.column + columnDelta
            if (row < 0 || column < 0) null else ref.copy(row = row, column = column)
        }

    /**
     * Keeps a reference attached to the same stable row/column when a user reorders grid axes.
     * The maps are old positional indexes to their new positional indexes. Absolute markers are
     * presentation/fill semantics and therefore remain unchanged during structural reordering.
     */
    fun remapPositions(
        expression: FormulaExpression,
        editedSheetName: String,
        ownerSheetName: String,
        rowIndexes: Map<Int, Int> = emptyMap(),
        columnIndexes: Map<Int, Int> = emptyMap(),
    ): FormulaExpression = map(expression) { ref ->
        val applies = if (ref.sheetName == null) {
            ownerSheetName.equals(editedSheetName, ignoreCase = true)
        } else {
            ref.sheetName.equals(editedSheetName, ignoreCase = true)
        }
        if (!applies) ref else ref.copy(
            row = rowIndexes[ref.row] ?: ref.row,
            column = columnIndexes[ref.column] ?: ref.column,
        )
    }
}
