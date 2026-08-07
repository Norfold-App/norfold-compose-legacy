package com.norfold.app.editor.calculation

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.pow

data class CalculationLimits(
    val maximumFormulaDepth: Int = 128,
    val maximumFormulaTokens: Int = 4_096,
    val maximumRangeCells: Int = 100_000,
    val maximumEvaluationSteps: Int = 250_000,
)

data class CalculationInstrumentation(
    val parsedFormulaCount: Int = 0,
    val evaluatedCellCount: Int = 0,
    val dependencyEdgeCount: Int = 0,
    val invalidatedCellCount: Int = 0,
    val batchCount: Int = 0,
    val viewportCellsRequested: Long = 0,
    val viewportCellsComposed: Long = 0,
    val dependencyEdgesAdded: Int = 0,
    val dependencyEdgesRemoved: Int = 0,
    val formulasRecalculated: Int = 0,
    val cacheHitCount: Int = 0,
)

data class CellMutation(val row: Int, val column: Int, val input: CellInput)

data class CellCommitResult(
    val accepted: Boolean,
    val warning: String? = null,
    val error: CalculationError? = null,
    val requiresConfirmation: Boolean = false,
)

data class ConditionalFormatResult(
    val format: CellFormat,
    val descriptions: List<String>,
)

class CalculationEngine(
    initialWorkbook: CalculationWorkbook,
    private val limits: CalculationLimits = CalculationLimits(),
    /**
     * The wall clock the volatile functions read.
     *
     * Injected rather than called inline so `TODAY`/`NOW` are deterministic under test and so the whole
     * engine keeps a single point of contact with the system clock. Production passes the default.
     */
    private val clock: () -> LocalDateTime = LocalDateTime::now,
) {
    private val parser = FormulaParser(limits.maximumFormulaTokens, limits.maximumFormulaDepth)
    private var workbookState = initialWorkbook
    private val parsed = mutableMapOf<CellId, FormulaParseResult>()
    private val calculated = mutableMapOf<CellId, CalculatedValue>()
    private val dependencies = mutableMapOf<CellId, Set<CellId>>()
    private val dependents = mutableMapOf<CellId, MutableSet<CellId>>()
    private var metrics = CalculationInstrumentation()

    /**
     * The instant every volatile cell in the current recalculation pass observes.
     *
     * Read once per pass, never per call. If `TODAY`/`NOW` each hit the clock directly, two volatile cells
     * evaluated microseconds apart could straddle midnight and disagree about the date, and `=NOW()-NOW()`
     * could return non-zero. One pass, one instant.
     */
    private var evaluationInstant: LocalDateTime = clock()

    /**
     * Cells whose formula reads the evaluation instant.
     *
     * Ordinary cells are cached until a dependency changes, which is exactly wrong for a volatile cell: its
     * input is the clock, and the clock is not in the dependency graph. These are dropped from the cache at
     * the start of every pass so they recompute against the new instant.
     */
    private val volatileCells = linkedSetOf<CellId>()

    init {
        rebuildGraphAndCalculate()
    }

    fun workbook(): CalculationWorkbook = workbookState
    fun instrumentation(): CalculationInstrumentation = metrics.copy()

    /** The instant the most recent recalculation pass observed. Volatile results are consistent with it. */
    fun evaluationInstant(): LocalDateTime = evaluationInstant

    /**
     * Opens a recalculation pass: samples the clock once and invalidates everything the previous instant fed.
     *
     * Invalidating the *closure* matters — a cell reading `=A1+1` where `A1` is `=TODAY()` is just as stale as
     * `A1` itself, and nothing in the dependency graph would otherwise say so.
     */
    private fun beginPass() {
        evaluationInstant = clock()
        if (volatileCells.isEmpty()) return
        dependencyClosure(volatileCells.toSet()).forEach(calculated::remove)
    }

    fun recordViewport(requestedCells: Long, composedCells: Int) {
        metrics = metrics.copy(
            viewportCellsRequested = metrics.viewportCellsRequested + requestedCells.coerceAtLeast(0),
            viewportCellsComposed = metrics.viewportCellsComposed + composedCells.coerceAtLeast(0),
        )
    }

    fun valueAt(sheetId: SheetId, row: Int, column: Int): CalculatedValue {
        val sheet = workbookState.sheet(sheetId)
            ?: return CalculatedValue.ErrorValue(CalculationError(CalculationErrorKind.REFERENCE, "Missing sheet"))
        val cell = sheet.cellAt(row, column) ?: return CalculatedValue.Blank
        return valueOf(cell.id)
    }

    fun displayAt(sheetId: SheetId, row: Int, column: Int): String {
        val value = valueAt(sheetId, row, column)
        val sheet = workbookState.sheet(sheetId)
        val format = sheet?.coordinateAt(row, column)?.let { coordinate ->
            effectiveBaseFormat(sheet, coordinate, row, column).numberFormat
        } ?: CellNumberFormat()
        return formatCalculatedValue(value, format)
    }

    fun valueOf(cellId: CellId): CalculatedValue = calculated[cellId]?.also {
        metrics = metrics.copy(cacheHitCount = metrics.cacheHitCount + 1)
    } ?: run {
        val located = locateCell(cellId) ?: return CalculatedValue.Blank
        calculateCell(located.first, located.second, linkedSetOf(), EvaluationBudget(limits.maximumEvaluationSteps))
    }

    /**
     * Replaces the authoritative workbook and invalidates only the dependency closure of [changedCells]
     * when identities remain compatible. Structural mutations pass null and rebuild the graph exactly once.
     */
    fun replaceWorkbook(next: CalculationWorkbook, changedCells: Set<CellId>? = null) {
        workbookState = next
        metrics = metrics.copy(batchCount = metrics.batchCount + 1)
        if (changedCells == null) {
            rebuildGraphAndCalculate()
            return
        }
        beginPass()
        val impacted = dependencyClosure(changedCells)
        metrics = metrics.copy(invalidatedCellCount = metrics.invalidatedCellCount + impacted.size)
        impacted.forEach {
            calculated.remove(it)
            parsed.remove(it)
            removeDependencyEdges(it)
        }
        impacted.sortedBy(CellId::value).forEach { cellId ->
            locateCell(cellId)?.let { (sheet, cell) ->
                calculateCell(sheet, cell, linkedSetOf(), EvaluationBudget(limits.maximumEvaluationSteps))
            }
        }
    }

    fun commitInput(sheetId: SheetId, row: Int, column: Int, input: CellInput, confirmWarning: Boolean = false): CellCommitResult {
        val sheet = workbookState.sheet(sheetId)
            ?: return CellCommitResult(false, error = CalculationError(CalculationErrorKind.REFERENCE, "Missing sheet"))
        val coordinate = sheet.coordinateAt(row, column)
            ?: return CellCommitResult(false, error = CalculationError(CalculationErrorKind.REFERENCE, "Cell outside sheet"))
        val existing = sheet.cell(coordinate.rowId, coordinate.columnId)
        val candidate = existing?.copy(input = input) ?: CalculationCell(rowId = coordinate.rowId, columnId = coordinate.columnId, input = input)
        val validation = validate(sheet, coordinate, input)
        if (validation.reject != null) return CellCommitResult(false, error = validation.reject)
        if (validation.warning != null && !confirmWarning) {
            return CellCommitResult(false, warning = validation.warning, requiresConfirmation = true)
        }
        val nextSheet = sheet.upsertCell(candidate)
        // A previously sparse/blank coordinate had no CellId for dependency edges to target. Its first
        // durable value therefore rebuilds the graph once; subsequent edits use the narrow closure.
        replaceWorkbook(workbookState.withSheet(nextSheet), if (existing == null) null else setOf(candidate.id))
        return CellCommitResult(true, warning = validation.warning)
    }

    /** Applies a paste/fill-sized mutation set and rebuilds or invalidates the graph exactly once. */
    fun commitBatch(sheetId: SheetId, mutations: List<CellMutation>, confirmWarnings: Boolean = true): List<CellCommitResult> {
        if (mutations.isEmpty()) return emptyList()
        var sheet = workbookState.sheet(sheetId)
            ?: return mutations.map { CellCommitResult(false, error = CalculationError(CalculationErrorKind.REFERENCE, "Missing sheet")) }
        val changed = linkedSetOf<CellId>()
        var introducedSparseCell = false
        val results = mutations.map { mutation ->
            val coordinate = sheet.coordinateAt(mutation.row, mutation.column)
                ?: return@map CellCommitResult(false, error = CalculationError(CalculationErrorKind.REFERENCE, "Cell outside sheet"))
            val validation = validate(sheet, coordinate, mutation.input)
            if (validation.reject != null) return@map CellCommitResult(false, error = validation.reject)
            if (validation.warning != null && !confirmWarnings) {
                return@map CellCommitResult(false, warning = validation.warning, requiresConfirmation = true)
            }
            val existing = sheet.cell(coordinate.rowId, coordinate.columnId)
            val candidate = existing?.copy(input = mutation.input)
                ?: CalculationCell(rowId = coordinate.rowId, columnId = coordinate.columnId, input = mutation.input)
            if (existing == null) introducedSparseCell = true else changed += candidate.id
            sheet = sheet.upsertCell(candidate)
            CellCommitResult(true, warning = validation.warning)
        }
        if (results.any(CellCommitResult::accepted)) {
            replaceWorkbook(workbookState.withSheet(sheet), if (introducedSparseCell) null else changed)
        }
        return results
    }

    fun conditionalFormat(sheetId: SheetId, row: Int, column: Int): ConditionalFormatResult {
        val sheet = workbookState.sheet(sheetId) ?: return ConditionalFormatResult(CellFormat(), emptyList())
        val coordinate = sheet.coordinateAt(row, column) ?: return ConditionalFormatResult(CellFormat(), emptyList())
        val value = valueAt(sheetId, row, column)
        var accumulated = effectiveBaseFormat(sheet, coordinate, row, column)
        val descriptions = mutableListOf<String>()
        val rules = sheet.conditionalFormats.asSequence()
            .filter(ConditionalFormatRule::enabled)
            .filter { rangeContains(sheet, coordinate, it.target) }
            .sortedBy(ConditionalFormatRule::priority)
            .toList()
        for (rule in rules) {
            if (conditionMatches(rule.condition, rule.target, sheet, coordinate, value)) {
                val derived = if (rule.condition is ConditionalCondition.ColorScale) {
                    colorScaleFormat(rule.condition, rule.target, sheet, value)
                } else {
                    CellFormat()
                }
                accumulated = accumulated.overlay(rule.formatDelta).overlay(derived)
                // Author label *and* machine-stated reason. A rule whose author called it "Rule 3" still
                // announces why it applied, so the meaning never rests on the fill colour alone.
                descriptions += if (rule.condition is ConditionalCondition.ColorScale) {
                    "${rule.accessibleDescription}; ${rule.condition.describe()} (${value.displayText()})"
                } else {
                    "${rule.accessibleDescription}; ${rule.condition.describe()}"
                }
                if (rule.stopIfTrue) break
            }
        }
        return ConditionalFormatResult(accumulated, descriptions)
    }

    private fun effectiveBaseFormat(
        sheet: CalculationSheet,
        coordinate: StableCellCoordinate,
        row: Int,
        column: Int,
    ): CellFormat {
        val direct = sheet.cellAt(row, column)?.format ?: CellFormat()
        val table = workbookState.embeddedTables.firstOrNull { definition -> rangeContains(sheet, coordinate, definition.range) }
            ?: return direct
        val startRow = minOf(sheet.rowIndex(table.range.start.rowId), sheet.rowIndex(table.range.end.rowId))
        val startColumn = minOf(sheet.columnIndex(table.range.start.columnId), sheet.columnIndex(table.range.end.columnId))
        return TableStyleResolver.effectiveCellFormat(workbookState, table, row - startRow, column - startColumn, direct)
    }

    private fun colorScaleFormat(
        condition: ConditionalCondition.ColorScale,
        range: StableCellRange,
        sheet: CalculationSheet,
        value: CalculatedValue,
    ): CellFormat {
        val current = value.coerceNumber() ?: return CellFormat()
        val numbers = stableCells(range).asSequence().mapNotNull { valueOf(it).coerceNumber() }.toList()
        val minimum = numbers.minOrNull() ?: current
        val maximum = numbers.maxOrNull() ?: current
        val fraction = if (maximum == minimum) 0.5f else {
            current.subtract(minimum).divide(maximum.subtract(minimum), 8, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)
        }
        val color = condition.midpoint?.let { midpoint ->
            if (fraction <= 0.5f) interpolateColor(condition.minimum, midpoint, fraction * 2f)
            else interpolateColor(midpoint, condition.maximum, (fraction - 0.5f) * 2f)
        } ?: interpolateColor(condition.minimum, condition.maximum, fraction)
        return CellFormat(fillColor = color)
    }

    private fun rebuildGraphAndCalculate() {
        parsed.clear()
        calculated.clear()
        dependencies.clear()
        dependents.clear()
        volatileCells.clear()
        beginPass()
        var formulaCount = 0
        workbookState.sheets.forEach { sheet ->
            sheet.cells.forEach { cell ->
                if (cell.input is CellInput.Formula) {
                    formulaCount++
                    val parse = parser.parse(cell.input.source)
                    parsed[cell.id] = parse
                    val refs = (parse as? FormulaParseResult.Success)
                        ?.let { referencesOf(it.expression, sheet) }
                        .orEmpty()
                    setDependencies(cell.id, refs)
                    (parse as? FormulaParseResult.Success)?.let { recordVolatility(cell.id, it.expression) }
                }
            }
        }
        val budget = EvaluationBudget(limits.maximumEvaluationSteps)
        workbookState.sheets.forEach { sheet ->
            sheet.cells.sortedBy { it.id.value }.forEach { calculateCell(sheet, it, linkedSetOf(), budget) }
        }
        metrics = metrics.copy(
            parsedFormulaCount = metrics.parsedFormulaCount + formulaCount,
            dependencyEdgeCount = dependencies.values.sumOf(Set<CellId>::size),
        )
    }

    private fun calculateCell(
        sheet: CalculationSheet,
        cell: CalculationCell,
        stack: LinkedHashSet<CellId>,
        budget: EvaluationBudget,
    ): CalculatedValue {
        calculated[cell.id]?.let { return it }
        if (!stack.add(cell.id)) {
            val error = CalculatedValue.ErrorValue(CalculationError(CalculationErrorKind.CYCLE, "Circular reference"))
            stack.forEach { calculated[it] = error }
            return error
        }
        val value = try {
            budget.step()
            when (val input = cell.input) {
                CellInput.Blank -> CalculatedValue.Blank
                is CellInput.Text -> CalculatedValue.Text(input.value)
                is CellInput.Number -> CalculatedValue.Number(input.canonical.toBigDecimal())
                is CellInput.BooleanValue -> CalculatedValue.BooleanValue(input.value)
                is CellInput.DateValue -> CalculatedValue.DateValue(LocalDate.parse(input.isoDate))
                is CellInput.DateTimeValue -> CalculatedValue.DateTimeValue(LocalDateTime.parse(input.isoDateTime))
                is CellInput.ErrorValue -> CalculatedValue.ErrorValue(input.error)
                is CellInput.Formula -> when (val parse = parsed.getOrPut(cell.id) { parser.parse(input.source) }) {
                    is FormulaParseResult.Failure -> CalculatedValue.ErrorValue(parse.error)
                    is FormulaParseResult.Success -> {
                        metrics = metrics.copy(formulasRecalculated = metrics.formulasRecalculated + 1)
                        val refs = referencesOf(parse.expression, sheet)
                        setDependencies(cell.id, refs)
                        recordVolatility(cell.id, parse.expression)
                        evaluate(parse.expression, sheet, cell, stack, budget).scalarOrError()
                    }
                }
            }
        } catch (e: EvaluationLimitException) {
            CalculatedValue.ErrorValue(CalculationError(CalculationErrorKind.RESOURCE_LIMIT, e.message.orEmpty()))
        } catch (e: Throwable) {
            CalculatedValue.ErrorValue(CalculationError(CalculationErrorKind.INTERNAL, e.message.orEmpty()))
        }
        stack.remove(cell.id)
        calculated[cell.id] = value
        metrics = metrics.copy(evaluatedCellCount = metrics.evaluatedCellCount + 1)
        return value
    }

    private fun evaluate(
        expression: FormulaExpression,
        sheet: CalculationSheet,
        owner: CalculationCell,
        stack: LinkedHashSet<CellId>,
        budget: EvaluationBudget,
    ): RuntimeValue {
        budget.step()
        return when (expression) {
            is FormulaExpression.NumberLiteral -> RuntimeValue.Scalar(CalculatedValue.Number(expression.value))
            is FormulaExpression.TextLiteral -> RuntimeValue.Scalar(CalculatedValue.Text(expression.value))
            is FormulaExpression.BooleanLiteral -> RuntimeValue.Scalar(CalculatedValue.BooleanValue(expression.value))
            is FormulaExpression.ErrorLiteral -> RuntimeValue.Scalar(CalculatedValue.ErrorValue(expression.error))
            is FormulaExpression.CellReference -> RuntimeValue.Scalar(resolveReference(expression.reference, sheet, stack, budget))
            is FormulaExpression.RangeReference -> rangeValues(expression.start, expression.end, sheet, stack, budget)
            is FormulaExpression.NamedReference -> namedRangeValues(expression.name, sheet, stack, budget)
            is FormulaExpression.StructuredReference -> structuredValues(expression, sheet, stack, budget)
            is FormulaExpression.Unary -> unary(expression, sheet, owner, stack, budget)
            is FormulaExpression.Binary -> binary(expression, sheet, owner, stack, budget)
            is FormulaExpression.FunctionCall -> function(expression, sheet, owner, stack, budget)
        }
    }

    private fun unary(
        expression: FormulaExpression.Unary,
        sheet: CalculationSheet,
        owner: CalculationCell,
        stack: LinkedHashSet<CellId>,
        budget: EvaluationBudget,
    ): RuntimeValue {
        val value = evaluate(expression.operand, sheet, owner, stack, budget).scalarOrError()
        if (value is CalculatedValue.ErrorValue) return RuntimeValue.Scalar(value)
        return when (expression.operator) {
            UnaryOperator.NOT -> RuntimeValue.Scalar(CalculatedValue.BooleanValue(!value.truthy()))
            UnaryOperator.PERCENT -> when (val number = value.numberOrError()) {
                is CalculatedValue.Number -> RuntimeValue.Scalar(calculatedNumber(number.value.divide(BigDecimal(100))))
                else -> RuntimeValue.Scalar(number)
            }
            UnaryOperator.PLUS -> value.numberOrError().runtime()
            UnaryOperator.MINUS -> when (val number = value.numberOrError()) {
                is CalculatedValue.Number -> RuntimeValue.Scalar(calculatedNumber(number.value.negate()))
                else -> RuntimeValue.Scalar(number)
            }
        }
    }

    private fun binary(
        expression: FormulaExpression.Binary,
        sheet: CalculationSheet,
        owner: CalculationCell,
        stack: LinkedHashSet<CellId>,
        budget: EvaluationBudget,
    ): RuntimeValue {
        val left = evaluate(expression.left, sheet, owner, stack, budget).scalarOrError()
        if (left is CalculatedValue.ErrorValue) return RuntimeValue.Scalar(left)
        val right = evaluate(expression.right, sheet, owner, stack, budget).scalarOrError()
        if (right is CalculatedValue.ErrorValue) return RuntimeValue.Scalar(right)
        val value = when (expression.operator) {
            BinaryOperator.CONCATENATE -> CalculatedValue.Text(left.displayText() + right.displayText())
            BinaryOperator.EQUAL -> CalculatedValue.BooleanValue(compare(left, right) == 0)
            BinaryOperator.NOT_EQUAL -> CalculatedValue.BooleanValue(compare(left, right) != 0)
            BinaryOperator.LESS -> CalculatedValue.BooleanValue(compare(left, right) < 0)
            BinaryOperator.LESS_OR_EQUAL -> CalculatedValue.BooleanValue(compare(left, right) <= 0)
            BinaryOperator.GREATER -> CalculatedValue.BooleanValue(compare(left, right) > 0)
            BinaryOperator.GREATER_OR_EQUAL -> CalculatedValue.BooleanValue(compare(left, right) >= 0)
            else -> {
                val l = left.numberOrError()
                if (l is CalculatedValue.ErrorValue) l else {
                    val r = right.numberOrError()
                    if (r is CalculatedValue.ErrorValue) r else {
                        val ln = (l as CalculatedValue.Number).value
                        val rn = (r as CalculatedValue.Number).value
                        when (expression.operator) {
                            BinaryOperator.ADD -> calculatedNumber(ln + rn)
                            BinaryOperator.SUBTRACT -> calculatedNumber(ln - rn)
                            BinaryOperator.MULTIPLY -> calculatedNumber(ln * rn)
                            BinaryOperator.DIVIDE -> if (rn.compareTo(BigDecimal.ZERO) == 0) {
                                CalculatedValue.ErrorValue(CalculationError(CalculationErrorKind.DIVIDE_BY_ZERO))
                            } else calculatedNumber(ln.divide(rn, 15, RoundingMode.HALF_UP))
                            BinaryOperator.POWER -> {
                                val powered = ln.toDouble().pow(rn.toDouble())
                                if (!powered.isFinite()) {
                                    CalculatedValue.ErrorValue(CalculationError(CalculationErrorKind.VALUE, "Non-finite power result"))
                                } else {
                                    calculatedNumber(BigDecimal(powered.toString()))
                                }
                            }
                            // No `else`: the comparison and concatenation operators are handled by the outer
                            // `when`, so the compiler can prove this inner one is exhaustive. A catch-all
                            // here would only hide a genuinely unhandled operator behind a runtime throw.
                        }
                    }
                }
            }
        }
        return RuntimeValue.Scalar(value)
    }

    private fun function(
        call: FormulaExpression.FunctionCall,
        sheet: CalculationSheet,
        owner: CalculationCell,
        stack: LinkedHashSet<CellId>,
        budget: EvaluationBudget,
    ): RuntimeValue {
        val name = call.name.uppercase()
        fun argumentSequence(): Sequence<CalculatedValue> = call.arguments.asSequence().flatMap {
            evaluate(it, sheet, owner, stack, budget).asSequence()
        }
        fun arguments(): List<CalculatedValue> = call.arguments.flatMap {
            evaluate(it, sheet, owner, stack, budget).flatten()
        }
        fun exact(count: Int): List<CalculatedValue>? = if (call.arguments.size == count) arguments() else null
        fun wrongCount() = RuntimeValue.Scalar(CalculatedValue.ErrorValue(CalculationError(CalculationErrorKind.VALUE, "$name has the wrong argument count")))
        fun number(value: BigDecimal) = RuntimeValue.Scalar(calculatedNumber(value))
        fun bool(value: Boolean) = RuntimeValue.Scalar(CalculatedValue.BooleanValue(value))
        fun text(value: String) = RuntimeValue.Scalar(CalculatedValue.Text(value))
        fun error(detail: String) = RuntimeValue.Scalar(CalculatedValue.ErrorValue(CalculationError(CalculationErrorKind.VALUE, detail)))

        return when (name) {
            "SUM", "AVERAGE", "MIN", "MAX" -> {
                var total = BigDecimal.ZERO
                var count = 0
                var minimum: BigDecimal? = null
                var maximum: BigDecimal? = null
                var failure: CalculatedValue.ErrorValue? = null
                val iterator = argumentSequence().iterator()
                while (iterator.hasNext() && failure == null) {
                    val value = iterator.next()
                    if (value is CalculatedValue.ErrorValue) failure = value else value.aggregateNumber()?.let { candidate ->
                        total += candidate
                        count++
                        minimum = minimum?.min(candidate) ?: candidate
                        maximum = maximum?.max(candidate) ?: candidate
                    }
                }
                failure?.runtime() ?: if (count == 0) number(BigDecimal.ZERO) else when (name) {
                    "SUM" -> number(total)
                    "AVERAGE" -> number(total.divide(BigDecimal(count), 15, RoundingMode.HALF_UP))
                    "MIN" -> number(minimum!!)
                    else -> number(maximum!!)
                }
            }
            // COUNT/COUNTA still have to *propagate* errors even though neither counts one.
            //
            // `count { it.aggregateNumber() != null }` quietly skipped error values, so a range that was
            // refused for exceeding the resource limit produced `0` — a plausible number where a
            // `#RESOURCE!` was owed. "Zero matching cells" and "the range could not be read" are different
            // answers, and only one of them tells the user their formula did not run.
            "COUNT", "COUNTA" -> {
                var counted = 0
                var failure: CalculatedValue.ErrorValue? = null
                val iterator = argumentSequence().iterator()
                while (iterator.hasNext() && failure == null) {
                    val value = iterator.next()
                    when {
                        value is CalculatedValue.ErrorValue -> failure = value
                        name == "COUNT" && value.aggregateNumber() != null -> counted++
                        name == "COUNTA" && value !is CalculatedValue.Blank -> counted++
                    }
                }
                failure?.runtime() ?: number(BigDecimal(counted))
            }
            "IF" -> {
                if (call.arguments.size !in 2..3) wrongCount() else {
                    val condition = evaluate(call.arguments[0], sheet, owner, stack, budget).scalarOrError()
                    val selected = if (condition.truthy()) call.arguments[1] else call.arguments.getOrNull(2)
                    selected?.let { evaluate(it, sheet, owner, stack, budget) } ?: RuntimeValue.Scalar(CalculatedValue.Blank)
                }
            }
            "AND", "OR" -> {
                if (call.arguments.isEmpty()) wrongCount() else {
                    val iterator = argumentSequence().iterator()
                    var result = name == "AND"
                    var failure: CalculatedValue.ErrorValue? = null
                    while (iterator.hasNext() && failure == null) {
                        val value = iterator.next()
                        if (value is CalculatedValue.ErrorValue) failure = value else if (name == "AND") result = result && value.truthy() else result = result || value.truthy()
                    }
                    failure?.runtime() ?: bool(result)
                }
            }
            "NOT" -> exact(1)?.let { bool(!it.first().truthy()) } ?: wrongCount()
            "IFERROR" -> if (call.arguments.size != 2) wrongCount() else {
                val first = evaluate(call.arguments[0], sheet, owner, stack, budget)
                if (first.scalarOrError() is CalculatedValue.ErrorValue) evaluate(call.arguments[1], sheet, owner, stack, budget) else first
            }
            "ABS" -> exact(1)?.first()?.numberOrError()?.let {
                if (it is CalculatedValue.Number) number(it.value.abs()) else it.runtime()
            } ?: wrongCount()
            "ROUND", "ROUNDUP", "ROUNDDOWN" -> {
                if (call.arguments.size !in 1..2) wrongCount() else {
                    val values = arguments()
                    val source = values.getOrNull(0)?.numberOrError()
                    val digits = values.getOrNull(1)?.coerceNumber()?.toInt() ?: 0
                    if (source !is CalculatedValue.Number) (source ?: CalculatedValue.ErrorValue(CalculationError(CalculationErrorKind.VALUE))).runtime()
                    else number(source.value.setScale(digits, when (name) {
                        "ROUNDUP" -> RoundingMode.UP
                        "ROUNDDOWN" -> RoundingMode.DOWN
                        else -> RoundingMode.HALF_UP
                    }))
                }
            }
            "SUMIF", "COUNTIF" -> aggregateIf(
                call, sheet, owner, stack, budget,
                if (name == "SUMIF") AggregateIfMode.SUM else AggregateIfMode.COUNT,
            )
            "CONCAT" -> text(buildString { argumentSequence().forEach { append(it.displayText()) } })
            "LEN" -> exact(1)?.let { number(BigDecimal(it.first().displayText().length)) } ?: wrongCount()
            "LEFT", "RIGHT" -> {
                if (call.arguments.size !in 1..2) wrongCount() else {
                    val values = arguments()
                    val source = values.firstOrNull()?.displayText().orEmpty()
                    val count = values.getOrNull(1)?.coerceNumber()?.toInt() ?: 1
                    if (count < 0) error("$name count cannot be negative")
                    else text(if (name == "LEFT") source.take(count) else source.takeLast(count))
                }
            }
            "MID" -> exact(3)?.let { values ->
                val source = values[0].displayText()
                val start = values[1].coerceNumber()?.toInt()?.minus(1)
                val count = values[2].coerceNumber()?.toInt()
                if (start == null || count == null || start < 0 || count < 0) error("MID expects positive positions")
                else text(source.drop(start).take(count))
            } ?: wrongCount()
            "TRIM" -> exact(1)?.let { text(it.first().displayText().trim().replace(Regex("\\s+"), " ")) } ?: wrongCount()
            "UPPER" -> exact(1)?.let { text(it.first().displayText().uppercase()) } ?: wrongCount()
            "LOWER" -> exact(1)?.let { text(it.first().displayText().lowercase()) } ?: wrongCount()
            "INDEX" -> index(call, sheet, owner, stack, budget)
            "MATCH" -> match(call, sheet, owner, stack, budget)
            "XLOOKUP" -> xlookup(call, sheet, owner, stack, budget)
            "DATE" -> exact(3)?.let { values ->
                val year = values[0].coerceNumber()?.toInt()
                val month = values[1].coerceNumber()?.toInt()
                val day = values[2].coerceNumber()?.toInt()
                runCatching { LocalDate.of(year!!, month!!, day!!) }
                    .fold({ RuntimeValue.Scalar(CalculatedValue.DateValue(it)) }, { error("Invalid DATE") })
            } ?: wrongCount()
            "YEAR", "MONTH", "DAY" -> exact(1)?.let { values ->
                values.first().asDate()?.let { date -> number(BigDecimal(when (name) { "YEAR" -> date.year; "MONTH" -> date.monthValue; else -> date.dayOfMonth })) }
                    ?: error("$name expects a date")
            } ?: wrongCount()

            // ---- Math and numeric ----------------------------------------------------------------
            "SIGN" -> exact(1)?.first()?.numberOrError()?.let {
                if (it is CalculatedValue.Number) number(BigDecimal(it.value.signum())) else it.runtime()
            } ?: wrongCount()
            "INT" -> exact(1)?.first()?.numberOrError()?.let {
                // Excel's INT floors toward negative infinity; -1.5 is -2, not -1. FLOOR, not truncation.
                if (it is CalculatedValue.Number) number(it.value.setScale(0, RoundingMode.FLOOR)) else it.runtime()
            } ?: wrongCount()
            "MOD" -> exact(2)?.let { values ->
                val dividend = values[0].coerceNumber()
                val divisor = values[1].coerceNumber()
                when {
                    dividend == null || divisor == null -> error("MOD expects numbers")
                    divisor.compareTo(BigDecimal.ZERO) == 0 ->
                        CalculatedValue.ErrorValue(CalculationError(CalculationErrorKind.DIVIDE_BY_ZERO)).runtime()
                    // Excel's MOD takes the sign of the divisor, so MOD(-3,2) is 1 rather than BigDecimal's -1.
                    else -> number(dividend.subtract(divisor.multiply(dividend.divide(divisor, 0, RoundingMode.FLOOR))))
                }
            } ?: wrongCount()
            "POWER" -> exact(2)?.let { values ->
                val base = values[0].coerceNumber()
                val exponent = values[1].coerceNumber()
                if (base == null || exponent == null) error("POWER expects numbers") else {
                    val powered = base.toDouble().pow(exponent.toDouble())
                    if (!powered.isFinite()) error("Non-finite power result") else number(BigDecimal(powered.toString()))
                }
            } ?: wrongCount()
            "SQRT" -> exact(1)?.let { values ->
                val source = values.first().coerceNumber()
                when {
                    source == null -> error("SQRT expects a number")
                    source.signum() < 0 -> error("SQRT cannot take a negative number")
                    else -> {
                        val root = kotlin.math.sqrt(source.toDouble())
                        if (!root.isFinite()) error("Non-finite square root") else number(BigDecimal(root.toString()))
                    }
                }
            } ?: wrongCount()
            "PRODUCT" -> {
                var total = BigDecimal.ONE
                var count = 0
                var failure: CalculatedValue.ErrorValue? = null
                val iterator = argumentSequence().iterator()
                while (iterator.hasNext() && failure == null) {
                    val value = iterator.next()
                    if (value is CalculatedValue.ErrorValue) failure = value else value.aggregateNumber()?.let {
                        total = total.multiply(it)
                        count++
                    }
                }
                failure?.runtime() ?: number(if (count == 0) BigDecimal.ZERO else total)
            }

            // ---- Logical and error ---------------------------------------------------------------
            "IFS" -> {
                // Lazy like IF: only the branch whose condition wins is evaluated.
                if (call.arguments.size < 2 || call.arguments.size % 2 != 0) wrongCount() else {
                    var chosen: RuntimeValue? = null
                    var index = 0
                    while (chosen == null && index < call.arguments.size) {
                        val condition = evaluate(call.arguments[index], sheet, owner, stack, budget).scalarOrError()
                        if (condition is CalculatedValue.ErrorValue) chosen = condition.runtime()
                        else if (condition.truthy()) chosen = evaluate(call.arguments[index + 1], sheet, owner, stack, budget)
                        index += 2
                    }
                    chosen ?: CalculatedValue.ErrorValue(CalculationError(CalculationErrorKind.UNAVAILABLE, "No IFS condition matched")).runtime()
                }
            }
            "IFNA" -> if (call.arguments.size != 2) wrongCount() else {
                // Narrower than IFERROR by design: only #N/A is caught, so a genuine #DIV/0! still surfaces.
                val first = evaluate(call.arguments[0], sheet, owner, stack, budget)
                val value = first.scalarOrError()
                if (value is CalculatedValue.ErrorValue && value.error.kind == CalculationErrorKind.UNAVAILABLE) {
                    evaluate(call.arguments[1], sheet, owner, stack, budget)
                } else first
            }

            // ---- Conditional aggregation ---------------------------------------------------------
            "AVERAGEIF" -> aggregateIf(call, sheet, owner, stack, budget, AggregateIfMode.AVERAGE)
            "SUMIFS", "COUNTIFS", "AVERAGEIFS" -> multiCriteriaAggregate(call, sheet, owner, stack, budget, name)

            // ---- Text -----------------------------------------------------------------------------
            "CONCATENATE" -> text(buildString { argumentSequence().forEach { append(it.displayText()) } })
            "TEXTJOIN" -> {
                if (call.arguments.size < 3) wrongCount() else {
                    val delimiter = evaluate(call.arguments[0], sheet, owner, stack, budget).scalarOrError().displayText()
                    val ignoreEmpty = evaluate(call.arguments[1], sheet, owner, stack, budget).scalarOrError().truthy()
                    val parts = mutableListOf<String>()
                    var failure: CalculatedValue.ErrorValue? = null
                    val iterator = call.arguments.drop(2).asSequence()
                        .flatMap { evaluate(it, sheet, owner, stack, budget).asSequence() }
                        .iterator()
                    while (iterator.hasNext() && failure == null) {
                        val value = iterator.next()
                        if (value is CalculatedValue.ErrorValue) failure = value else {
                            val rendered = value.displayText()
                            if (!ignoreEmpty || rendered.isNotEmpty()) parts += rendered
                        }
                    }
                    failure?.runtime() ?: text(parts.joinToString(delimiter))
                }
            }
            "FIND", "SEARCH" -> {
                // FIND is case-sensitive; SEARCH is not. Neither supports wildcards here, and §3.2 requires
                // that narrowing be stated rather than implied.
                if (call.arguments.size !in 2..3) wrongCount() else {
                    val values = arguments()
                    val needle = values.getOrNull(0)?.displayText().orEmpty()
                    val haystack = values.getOrNull(1)?.displayText().orEmpty()
                    val start = values.getOrNull(2)?.coerceNumber()?.toInt() ?: 1
                    if (start < 1 || start > haystack.length + 1) error("$name start position is outside the text") else {
                        val found = haystack.indexOf(needle, startIndex = start - 1, ignoreCase = name == "SEARCH")
                        if (found < 0) CalculatedValue.ErrorValue(CalculationError(CalculationErrorKind.VALUE, "$name did not find the text")).runtime()
                        else number(BigDecimal(found + 1))
                    }
                }
            }
            "SUBSTITUTE" -> {
                if (call.arguments.size !in 3..4) wrongCount() else {
                    val values = arguments()
                    val source = values.getOrNull(0)?.displayText().orEmpty()
                    val target = values.getOrNull(1)?.displayText().orEmpty()
                    val replacement = values.getOrNull(2)?.displayText().orEmpty()
                    val occurrence = values.getOrNull(3)?.coerceNumber()?.toInt()
                    when {
                        target.isEmpty() -> text(source)
                        occurrence == null -> text(source.replace(target, replacement))
                        occurrence < 1 -> error("SUBSTITUTE occurrence must be positive")
                        else -> {
                            // Replace only the nth occurrence; anything else stays as typed.
                            var index = -1
                            var seen = 0
                            var cursor = 0
                            while (seen < occurrence) {
                                index = source.indexOf(target, cursor)
                                if (index < 0) break
                                seen++
                                cursor = index + target.length
                            }
                            text(if (seen < occurrence || index < 0) source else source.replaceRange(index, index + target.length, replacement))
                        }
                    }
                }
            }
            "VALUE" -> exact(1)?.let { values ->
                val source = values.first()
                if (source is CalculatedValue.ErrorValue) source.runtime()
                else parseRenderableDecimal(source.displayText().trim())?.let(::number) ?: error("VALUE expects numeric text")
            } ?: wrongCount()

            // ---- Date and time ---------------------------------------------------------------------
            "DATEVALUE" -> exact(1)?.let { values ->
                val source = values.first()
                if (source is CalculatedValue.ErrorValue) source.runtime()
                else runCatching { LocalDate.parse(source.displayText().trim()) }
                    .fold({ RuntimeValue.Scalar(CalculatedValue.DateValue(it)) }, { error("DATEVALUE expects an ISO-8601 date") })
            } ?: wrongCount()
            "TIME" -> exact(3)?.let { values ->
                val hour = values[0].coerceNumber()?.toInt()
                val minute = values[1].coerceNumber()?.toInt()
                val second = values[2].coerceNumber()?.toInt()
                // A time alone has no date, so it is carried on the epoch day; HOUR/MINUTE/SECOND read it back.
                // Written out rather than via `LocalDate.EPOCH`, which is API 34 and would crash on minSdk 26.
                runCatching { LocalDateTime.of(LocalDate.of(1970, 1, 1), java.time.LocalTime.of(hour!!, minute!!, second!!)) }
                    .fold({ RuntimeValue.Scalar(CalculatedValue.DateTimeValue(it)) }, { error("Invalid TIME") })
            } ?: wrongCount()
            "HOUR", "MINUTE", "SECOND" -> exact(1)?.let { values ->
                values.first().asTime()?.let { time ->
                    number(BigDecimal(when (name) { "HOUR" -> time.hour; "MINUTE" -> time.minute; else -> time.second }))
                } ?: error("$name expects a time")
            } ?: wrongCount()
            "TODAY" -> if (call.arguments.isNotEmpty()) wrongCount() else {
                RuntimeValue.Scalar(CalculatedValue.DateValue(evaluationInstant.toLocalDate()))
            }
            "NOW" -> if (call.arguments.isNotEmpty()) wrongCount() else {
                RuntimeValue.Scalar(CalculatedValue.DateTimeValue(evaluationInstant))
            }

            // ---- Lookup and reference --------------------------------------------------------------
            "VLOOKUP", "HLOOKUP" -> vlookup(call, sheet, owner, stack, budget, horizontal = name == "HLOOKUP")
            "ROW", "COLUMN" -> {
                if (call.arguments.size > 1) wrongCount() else {
                    val reference = call.arguments.firstOrNull()
                    val address = referenceAddress(reference, sheet, owner)
                        ?: return valueError("$name expects a reference")
                    number(BigDecimal(if (name == "ROW") address.row + 1 else address.column + 1))
                }
            }
            "ROWS", "COLUMNS" -> exact(1).let {
                if (call.arguments.size != 1) wrongCount() else {
                    // Shape, not contents: measured from the reference itself so a huge range costs nothing.
                    val extent = referenceExtent(call.arguments[0], sheet)
                        ?: return valueError("$name expects a reference")
                    number(BigDecimal(if (name == "ROWS") extent.first else extent.second))
                }
            }
            else -> RuntimeValue.Scalar(CalculatedValue.ErrorValue(CalculationError(CalculationErrorKind.NAME, "Unknown function $name")))
        }
    }

    private fun aggregateIf(
        call: FormulaExpression.FunctionCall,
        sheet: CalculationSheet,
        owner: CalculationCell,
        stack: LinkedHashSet<CellId>,
        budget: EvaluationBudget,
        mode: AggregateIfMode,
    ): RuntimeValue {
        if (call.arguments.size !in 2..3) return CalculatedValue.ErrorValue(CalculationError(CalculationErrorKind.VALUE, "Wrong argument count")).runtime()
        val candidates = evaluate(call.arguments[0], sheet, owner, stack, budget).asSequence().iterator()
        val criterion = evaluate(call.arguments[1], sheet, owner, stack, budget).scalarOrError().displayText()
        val sumValues = call.arguments.getOrNull(2)?.let { evaluate(it, sheet, owner, stack, budget).asSequence().iterator() }
        var total = BigDecimal.ZERO
        var count = 0
        var numericCount = 0
        while (candidates.hasNext()) {
            val value = candidates.next()
            val sumValue = if (sumValues == null) value else if (sumValues.hasNext()) sumValues.next() else CalculatedValue.Blank
            if (criterionMatches(value, criterion)) {
                count++
                sumValue.coerceNumber()?.let { total += it; numericCount++ }
            }
        }
        return when (mode) {
            AggregateIfMode.SUM -> calculatedNumber(total).runtime()
            AggregateIfMode.COUNT -> calculatedNumber(BigDecimal(count)).runtime()
            // An average over nothing is not zero. #DIV/0! is the honest answer and the one Excel gives.
            AggregateIfMode.AVERAGE -> if (numericCount == 0) {
                CalculatedValue.ErrorValue(CalculationError(CalculationErrorKind.DIVIDE_BY_ZERO, "AVERAGEIF matched no numbers")).runtime()
            } else {
                calculatedNumber(total.divide(BigDecimal(numericCount), 15, RoundingMode.HALF_UP)).runtime()
            }
        }
    }

    /**
     * `SUMIFS` / `COUNTIFS` / `AVERAGEIFS` — every criteria pair must hold for a position to count.
     *
     * `COUNTIFS` takes pairs from argument 0; the other two take an aggregate range first and then pairs. All
     * ranges are walked in lockstep as sequences, so a wide criteria set costs one pass, not one pass per pair.
     */
    private fun multiCriteriaAggregate(
        call: FormulaExpression.FunctionCall,
        sheet: CalculationSheet,
        owner: CalculationCell,
        stack: LinkedHashSet<CellId>,
        budget: EvaluationBudget,
        name: String,
    ): RuntimeValue {
        val counting = name == "COUNTIFS"
        val pairStart = if (counting) 0 else 1
        val pairCount = call.arguments.size - pairStart
        if (pairCount < 2 || pairCount % 2 != 0) {
            return valueError("$name has the wrong argument count")
        }
        val aggregate = if (counting) null else evaluate(call.arguments[0], sheet, owner, stack, budget).asSequence().iterator()
        val criteriaRanges = (pairStart until call.arguments.size step 2).map {
            evaluate(call.arguments[it], sheet, owner, stack, budget).asSequence().iterator()
        }
        val criteria = (pairStart + 1 until call.arguments.size step 2).map {
            evaluate(call.arguments[it], sheet, owner, stack, budget).scalarOrError().displayText()
        }
        var total = BigDecimal.ZERO
        var matched = 0
        var numericCount = 0
        var failure: CalculatedValue.ErrorValue? = null
        while (failure == null && criteriaRanges.all(Iterator<CalculatedValue>::hasNext)) {
            val candidates = criteriaRanges.map(Iterator<CalculatedValue>::next)
            val aggregateValue = aggregate?.let { if (it.hasNext()) it.next() else CalculatedValue.Blank }
            val errored = candidates.firstOrNull { it is CalculatedValue.ErrorValue } as? CalculatedValue.ErrorValue
                ?: aggregateValue as? CalculatedValue.ErrorValue
            if (errored != null) {
                failure = errored
            } else if (candidates.indices.all { criterionMatches(candidates[it], criteria[it]) }) {
                matched++
                (aggregateValue ?: candidates.first()).coerceNumber()
                    ?.takeIf { !counting }
                    ?.let { total += it; numericCount++ }
            }
        }
        failure?.let { return it.runtime() }
        return when {
            counting -> calculatedNumber(BigDecimal(matched)).runtime()
            name == "SUMIFS" -> calculatedNumber(total).runtime()
            numericCount == 0 ->
                CalculatedValue.ErrorValue(CalculationError(CalculationErrorKind.DIVIDE_BY_ZERO, "AVERAGEIFS matched no numbers")).runtime()
            else -> calculatedNumber(total.divide(BigDecimal(numericCount), 15, RoundingMode.HALF_UP)).runtime()
        }
    }

    /**
     * `VLOOKUP` / `HLOOKUP` over the two-dimensional shape of a range.
     *
     * Only exact match is supported: the optional fourth argument must be absent or FALSE. Approximate match
     * needs a sorted range and silently returns a neighbouring row when that assumption is wrong, which is a
     * worse answer than saying so.
     */
    private fun vlookup(
        call: FormulaExpression.FunctionCall,
        sheet: CalculationSheet,
        owner: CalculationCell,
        stack: LinkedHashSet<CellId>,
        budget: EvaluationBudget,
        horizontal: Boolean,
    ): RuntimeValue {
        val name = if (horizontal) "HLOOKUP" else "VLOOKUP"
        if (call.arguments.size !in 3..4) return valueError("$name has the wrong argument count")
        if (call.arguments.size == 4) {
            val approximate = evaluate(call.arguments[3], sheet, owner, stack, budget).scalarOrError()
            if (approximate.truthy()) return valueError("Only exact $name match is supported")
        }
        val needle = evaluate(call.arguments[0], sheet, owner, stack, budget).scalarOrError()
        val table = evaluate(call.arguments[1], sheet, owner, stack, budget)
        val offset = evaluate(call.arguments[2], sheet, owner, stack, budget).scalarOrError().coerceNumber()?.toInt()
            ?: return valueError("$name index must be numeric")
        if (offset < 1) return valueError("$name index must be positive")
        val grid = table as? RuntimeValue.Range ?: return valueError("$name expects a range")
        val lines = if (horizontal) grid.rowCount else grid.columnCount
        if (offset > lines) return CalculatedValue.ErrorValue(CalculationError(CalculationErrorKind.REFERENCE, "$name index is outside the range")).runtime()
        val search = if (horizontal) grid.columnCount else grid.rowCount
        for (position in 0 until search) {
            val key = if (horizontal) grid.indexed(0, position) else grid.indexed(position, 0)
            if (compare(key, needle) == 0) {
                return (if (horizontal) grid.indexed(offset - 1, position) else grid.indexed(position, offset - 1)).runtime()
            }
        }
        return CalculatedValue.ErrorValue(CalculationError(CalculationErrorKind.UNAVAILABLE)).runtime()
    }

    /** The cell a `ROW`/`COLUMN` argument addresses, defaulting to the calling cell when omitted. */
    private fun referenceAddress(
        reference: FormulaExpression?,
        sheet: CalculationSheet,
        owner: CalculationCell,
    ): GridAddress? = when (reference) {
        null -> sheet.addressOf(owner)
        is FormulaExpression.CellReference -> GridAddress(reference.reference.row, reference.reference.column)
        is FormulaExpression.RangeReference -> GridAddress(
            minOf(reference.start.row, reference.end.row),
            minOf(reference.start.column, reference.end.column),
        )
        is FormulaExpression.NamedReference -> workbookState.namedRange(reference.name)?.target?.let { stableAddress(it) }
        else -> null
    }

    /** The (rows, columns) shape of a reference, measured without reading a single cell value. */
    private fun referenceExtent(reference: FormulaExpression, sheet: CalculationSheet): Pair<Int, Int>? = when (reference) {
        is FormulaExpression.CellReference -> 1 to 1
        is FormulaExpression.RangeReference -> {
            val rows = kotlin.math.abs(reference.end.row - reference.start.row) + 1
            val columns = kotlin.math.abs(reference.end.column - reference.start.column) + 1
            rows to columns
        }
        is FormulaExpression.NamedReference -> workbookState.namedRange(reference.name)?.target?.let { stableExtent(it) }
        is FormulaExpression.StructuredReference -> workbookState.embeddedTable(reference.tableName)?.range?.let { stableExtent(it) }
        else -> null
    }

    private fun stableAddress(range: StableCellRange): GridAddress? {
        val sheet = workbookState.sheet(range.start.sheetId) ?: return null
        val row = minOf(sheet.rowIndex(range.start.rowId), sheet.rowIndex(range.end.rowId))
        val column = minOf(sheet.columnIndex(range.start.columnId), sheet.columnIndex(range.end.columnId))
        return if (row < 0 || column < 0) null else GridAddress(row, column)
    }

    private fun stableExtent(range: StableCellRange): Pair<Int, Int>? {
        val sheet = workbookState.sheet(range.start.sheetId) ?: return null
        val startRow = sheet.rowIndex(range.start.rowId)
        val endRow = sheet.rowIndex(range.end.rowId)
        val startColumn = sheet.columnIndex(range.start.columnId)
        val endColumn = sheet.columnIndex(range.end.columnId)
        if (minOf(startRow, endRow, startColumn, endColumn) < 0) return null
        return (kotlin.math.abs(endRow - startRow) + 1) to (kotlin.math.abs(endColumn - startColumn) + 1)
    }

    private fun index(call: FormulaExpression.FunctionCall, sheet: CalculationSheet, owner: CalculationCell, stack: LinkedHashSet<CellId>, budget: EvaluationBudget): RuntimeValue {
        if (call.arguments.size !in 2..3) return valueError("INDEX has the wrong argument count")
        val values = evaluate(call.arguments[0], sheet, owner, stack, budget)
        val row = evaluate(call.arguments[1], sheet, owner, stack, budget).scalarOrError().coerceNumber()?.toInt()?.minus(1)
        val column = call.arguments.getOrNull(2)?.let { evaluate(it, sheet, owner, stack, budget).scalarOrError().coerceNumber()?.toInt()?.minus(1) } ?: 0
        if (row == null || column < 0 || row < 0) return valueError("INDEX position is invalid")
        return values.indexed(row, column).runtime()
    }

    private fun match(call: FormulaExpression.FunctionCall, sheet: CalculationSheet, owner: CalculationCell, stack: LinkedHashSet<CellId>, budget: EvaluationBudget): RuntimeValue {
        if (call.arguments.size !in 2..3) return valueError("MATCH has the wrong argument count")
        val mode = call.arguments.getOrNull(2)?.let {
            evaluate(it, sheet, owner, stack, budget).scalarOrError().coerceNumber()?.toInt()
                ?: return valueError("MATCH mode must be numeric")
        } ?: 0
        if (mode != 0) return valueError("Only exact MATCH mode 0 is supported")
        val needle = evaluate(call.arguments[0], sheet, owner, stack, budget).scalarOrError()
        val values = evaluate(call.arguments[1], sheet, owner, stack, budget).asSequence()
        val found = values.indexOfFirst { compare(it, needle) == 0 }
        return if (found < 0) CalculatedValue.ErrorValue(CalculationError(CalculationErrorKind.UNAVAILABLE)).runtime()
        else CalculatedValue.Number(BigDecimal(found + 1)).runtime()
    }

    private fun xlookup(call: FormulaExpression.FunctionCall, sheet: CalculationSheet, owner: CalculationCell, stack: LinkedHashSet<CellId>, budget: EvaluationBudget): RuntimeValue {
        if (call.arguments.size !in 3..4) return valueError("XLOOKUP has the wrong argument count")
        val needle = evaluate(call.arguments[0], sheet, owner, stack, budget).scalarOrError()
        val lookup = evaluate(call.arguments[1], sheet, owner, stack, budget).asSequence().iterator()
        val result = evaluate(call.arguments[2], sheet, owner, stack, budget).asSequence().iterator()
        while (lookup.hasNext()) {
            val candidate = lookup.next()
            val resultValue = if (result.hasNext()) result.next() else CalculatedValue.ErrorValue(CalculationError(CalculationErrorKind.REFERENCE))
            if (compare(candidate, needle) == 0) return resultValue.runtime()
        }
        return call.arguments.getOrNull(3)?.let { evaluate(it, sheet, owner, stack, budget) }
            ?: CalculatedValue.ErrorValue(CalculationError(CalculationErrorKind.UNAVAILABLE)).runtime()
    }

    /**
     * The sheet a reference addresses, or `null` when it names a sheet the workbook does not have.
     *
     * A *named* sheet that does not exist must never resolve to [currentSheet]. That fallback is the defect
     * this function exists to prevent: `=Missing!A1` would silently read the local `A1` and return a
     * plausible number where a `#REF!` was owed — and a rename typo would look like it worked.
     */
    private fun sheetFor(sheetName: String?, currentSheet: CalculationSheet): CalculationSheet? =
        if (sheetName == null) currentSheet else workbookState.sheetNamed(sheetName)

    private fun resolveReference(reference: A1Reference, currentSheet: CalculationSheet, stack: LinkedHashSet<CellId>, budget: EvaluationBudget): CalculatedValue {
        val sheet = sheetFor(reference.sheetName, currentSheet)
            ?: return CalculatedValue.ErrorValue(CalculationError(CalculationErrorKind.REFERENCE, "Missing sheet ${reference.sheetName}"))
        val cell = sheet.cellAt(reference.row, reference.column) ?: return CalculatedValue.Blank
        return calculateCell(sheet, cell, stack, budget)
    }

    private fun rangeValues(start: A1Reference, end: A1Reference, currentSheet: CalculationSheet, stack: LinkedHashSet<CellId>, budget: EvaluationBudget): RuntimeValue {
        val sheetName = start.sheetName ?: end.sheetName
        if (start.sheetName != null && end.sheetName != null && !start.sheetName.equals(end.sheetName, true)) {
            return valueError("Invalid range sheet")
        }
        val sheet = sheetFor(sheetName, currentSheet)
            ?: return CalculatedValue.ErrorValue(CalculationError(CalculationErrorKind.REFERENCE, "Missing sheet $sheetName")).runtime()
        val rows = minOf(start.row, end.row)..maxOf(start.row, end.row)
        val columns = minOf(start.column, end.column)..maxOf(start.column, end.column)
        val count = rows.count().toLong() * columns.count().toLong()
        if (count > limits.maximumRangeCells) return CalculatedValue.ErrorValue(CalculationError(CalculationErrorKind.RESOURCE_LIMIT, "Range exceeds ${limits.maximumRangeCells} cells")).runtime()
        return RuntimeValue.Range(rows.count(), columns.count(), sequence {
            for (row in rows) for (column in columns) {
                budget.step()
                val cell = sheet.cellAt(row, column)
                yield(if (cell == null) CalculatedValue.Blank else calculateCell(sheet, cell, stack, budget))
            }
        })
    }

    private fun namedRangeValues(name: String, currentSheet: CalculationSheet, stack: LinkedHashSet<CellId>, budget: EvaluationBudget): RuntimeValue {
        val named = workbookState.namedRange(name) ?: return CalculatedValue.ErrorValue(CalculationError(CalculationErrorKind.NAME, "Unknown name $name")).runtime()
        return stableRangeValues(named.target, currentSheet, stack, budget)
    }

    private fun structuredValues(reference: FormulaExpression.StructuredReference, currentSheet: CalculationSheet, stack: LinkedHashSet<CellId>, budget: EvaluationBudget): RuntimeValue {
        val table = workbookState.embeddedTable(reference.tableName)
            ?: return CalculatedValue.ErrorValue(CalculationError(CalculationErrorKind.NAME, "Unknown table ${reference.tableName}")).runtime()
        val column = table.columns.firstOrNull { it.name.equals(reference.columnName, true) }
            ?: return CalculatedValue.ErrorValue(CalculationError(CalculationErrorKind.NAME, "Unknown column ${reference.columnName}")).runtime()
        val sheet = workbookState.sheet(table.range.start.sheetId) ?: return valueError("Missing table sheet")
        val startRow = sheet.rowIndex(table.range.start.rowId) + if (table.headerRow) 1 else 0
        val endRow = sheet.rowIndex(table.range.end.rowId) - if (table.totalRow) 1 else 0
        val columnIndex = sheet.columnIndex(column.id)
        if (startRow < 0 || columnIndex < 0) return valueError("Invalid structured reference")
        // A header-only (or header-plus-total-only) table has no data rows. That is an empty column, not a
        // malformed reference: SUM over it is 0, exactly as it is the moment before the first row is typed.
        if (endRow < startRow) return RuntimeValue.Range(0, 1, emptySequence())
        return RuntimeValue.Range(endRow - startRow + 1, 1, sequence {
            for (row in startRow..endRow) {
                budget.step()
                yield(sheet.cellAt(row, columnIndex)?.let { calculateCell(sheet, it, stack, budget) } ?: CalculatedValue.Blank)
            }
        })
    }

    private fun stableRangeValues(range: StableCellRange, currentSheet: CalculationSheet, stack: LinkedHashSet<CellId>, budget: EvaluationBudget): RuntimeValue {
        val sheet = workbookState.sheet(range.start.sheetId) ?: return valueError("Missing named-range sheet")
        val startRow = sheet.rowIndex(range.start.rowId)
        val endRow = sheet.rowIndex(range.end.rowId)
        val startColumn = sheet.columnIndex(range.start.columnId)
        val endColumn = sheet.columnIndex(range.end.columnId)
        if (minOf(startRow, endRow, startColumn, endColumn) < 0) return valueError("Stale named range")
        return rangeValues(A1Reference(sheet.name, startRow, startColumn), A1Reference(sheet.name, endRow, endColumn), currentSheet, stack, budget)
    }

    private fun referencesOf(expression: FormulaExpression, currentSheet: CalculationSheet): Set<CellId> {
        val result = linkedSetOf<CellId>()
        fun collect(node: FormulaExpression) {
            when (node) {
                is FormulaExpression.CellReference -> resolveCellId(node.reference, currentSheet)?.let(result::add)
                is FormulaExpression.RangeReference -> {
                    // A range naming a sheet that does not exist has no dependencies to record; the
                    // evaluator reports the #REF! separately.
                    val sheet = sheetFor(node.start.sheetName ?: node.end.sheetName, currentSheet)
                    if (sheet == null) return@collect
                    val rowStart = minOf(node.start.row, node.end.row)
                    val rowEnd = maxOf(node.start.row, node.end.row)
                    val columnStart = minOf(node.start.column, node.end.column)
                    val columnEnd = maxOf(node.start.column, node.end.column)
                    val count = (rowEnd - rowStart + 1L) * (columnEnd - columnStart + 1L)
                    if (count <= limits.maximumRangeCells) {
                        for (row in rowStart..rowEnd) for (column in columnStart..columnEnd) sheet.cellAt(row, column)?.id?.let(result::add)
                    }
                }
                is FormulaExpression.NamedReference -> workbookState.namedRange(node.name)?.target?.let { stableCells(it).forEach(result::add) }
                is FormulaExpression.StructuredReference -> workbookState.embeddedTable(node.tableName)?.range?.let { stableCells(it).forEach(result::add) }
                is FormulaExpression.Unary -> collect(node.operand)
                is FormulaExpression.Binary -> { collect(node.left); collect(node.right) }
                is FormulaExpression.FunctionCall -> node.arguments.forEach(::collect)
                else -> Unit
            }
        }
        collect(expression)
        return result
    }

    private fun stableCells(range: StableCellRange): List<CellId> {
        val sheet = workbookState.sheet(range.start.sheetId) ?: return emptyList()
        val rows = sortedRange(sheet.rowIndex(range.start.rowId), sheet.rowIndex(range.end.rowId)) ?: return emptyList()
        val columns = sortedRange(sheet.columnIndex(range.start.columnId), sheet.columnIndex(range.end.columnId)) ?: return emptyList()
        if (rows.count().toLong() * columns.count().toLong() > limits.maximumRangeCells) return emptyList()
        return buildList { rows.forEach { row -> columns.forEach { column -> sheet.cellAt(row, column)?.id?.let(::add) } } }
    }

    private fun resolveCellId(reference: A1Reference, currentSheet: CalculationSheet): CellId? =
        sheetFor(reference.sheetName, currentSheet)?.cellAt(reference.row, reference.column)?.id

    /** Notes whether [expression] reads the evaluation instant, so the pass opener can invalidate it. */
    private fun recordVolatility(cellId: CellId, expression: FormulaExpression) {
        if (isVolatile(expression)) volatileCells.add(cellId) else volatileCells.remove(cellId)
    }

    private fun isVolatile(expression: FormulaExpression): Boolean = when (expression) {
        is FormulaExpression.FunctionCall ->
            expression.name.uppercase() in VOLATILE_FUNCTIONS || expression.arguments.any(::isVolatile)
        is FormulaExpression.Unary -> isVolatile(expression.operand)
        is FormulaExpression.Binary -> isVolatile(expression.left) || isVolatile(expression.right)
        else -> false
    }

    private fun setDependencies(cellId: CellId, refs: Set<CellId>) {
        removeDependencyEdges(cellId)
        dependencies[cellId] = refs
        refs.forEach { dependents.getOrPut(it) { linkedSetOf() }.add(cellId) }
        metrics = metrics.copy(dependencyEdgesAdded = metrics.dependencyEdgesAdded + refs.size)
    }

    private fun removeDependencyEdges(cellId: CellId) {
        val removed = dependencies.remove(cellId).orEmpty()
        removed.forEach { ref ->
            dependents[ref]?.remove(cellId)
            if (dependents[ref].isNullOrEmpty()) dependents.remove(ref)
        }
        if (removed.isNotEmpty()) metrics = metrics.copy(dependencyEdgesRemoved = metrics.dependencyEdgesRemoved + removed.size)
    }

    private fun dependencyClosure(changed: Set<CellId>): Set<CellId> {
        val result = linkedSetOf<CellId>()
        val pending = ArrayDeque(changed)
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (result.add(next)) dependents[next].orEmpty().sortedBy(CellId::value).forEach(pending::addLast)
        }
        return result
    }

    private fun locateCell(id: CellId): Pair<CalculationSheet, CalculationCell>? = workbookState.sheets.firstNotNullOfOrNull { sheet ->
        sheet.cell(id)?.let { sheet to it }
    }

    private data class ValidationOutcome(val reject: CalculationError? = null, val warning: String? = null)

    private fun validate(sheet: CalculationSheet, coordinate: StableCellCoordinate, input: CellInput): ValidationOutcome {
        val rules = sheet.validations.filter { rangeContains(sheet, coordinate, it.target) }
        for (rule in rules) {
            if (input == CellInput.Blank && rule.allowBlank) continue
            val okay = validationMatches(rule.condition, input, sheet, coordinate)
            if (!okay) {
                if (rule.policy == ValidationPolicy.REJECT) {
                    return ValidationOutcome(reject = CalculationError(CalculationErrorKind.VALIDATION, rule.errorMessage))
                }
                return ValidationOutcome(warning = rule.errorMessage)
            }
        }
        return ValidationOutcome()
    }

    private fun validationMatches(condition: ValidationCondition, input: CellInput, sheet: CalculationSheet, coordinate: StableCellCoordinate): Boolean {
        val value = input.toCalculatedValue()
        return when (condition) {
            is ValidationCondition.AllowedValues -> condition.values.any { it.equals(value.displayText(), true) }
            is ValidationCondition.NumberRange -> value.coerceNumber()?.let { number ->
                (condition.minimum?.toBigDecimalOrNull()?.let { number >= it } ?: true) &&
                    (condition.maximum?.toBigDecimalOrNull()?.let { number <= it } ?: true)
            } ?: false
            is ValidationCondition.IntegerRange -> value.coerceNumber()?.takeIf { it.stripTrailingZeros().scale() <= 0 }?.toLong()?.let { number ->
                (condition.minimum?.let { number >= it } ?: true) && (condition.maximum?.let { number <= it } ?: true)
            } ?: false
            is ValidationCondition.DateRange -> value.asDate()?.let { date ->
                (condition.minimumIso?.let { date >= LocalDate.parse(it) } ?: true) &&
                    (condition.maximumIso?.let { date <= LocalDate.parse(it) } ?: true)
            } ?: false
            is ValidationCondition.TextLength -> value.displayText().length.let { length ->
                (condition.minimum?.let { length >= it } ?: true) && (condition.maximum?.let { length <= it } ?: true)
            }
            is ValidationCondition.CustomFormula -> {
                val candidate = CalculationCell(rowId = coordinate.rowId, columnId = coordinate.columnId, input = CellInput.Formula(condition.source))
                val parse = parser.parse(condition.source) as? FormulaParseResult.Success
                parse != null && evaluate(parse.expression, sheet, candidate, linkedSetOf(), EvaluationBudget(limits.maximumEvaluationSteps)).scalarOrError().truthy()
            }
        }
    }

    private fun conditionMatches(
        condition: ConditionalCondition,
        target: StableCellRange,
        sheet: CalculationSheet,
        coordinate: StableCellCoordinate,
        value: CalculatedValue,
    ): Boolean = when (condition) {
        is ConditionalCondition.Comparison -> compare(value, CellInput.Text(condition.source).toCalculatedValue()).let { comparison ->
            when (condition.operator) {
                ComparisonOperator.EQUAL -> comparison == 0
                ComparisonOperator.NOT_EQUAL -> comparison != 0
                ComparisonOperator.LESS -> comparison < 0
                ComparisonOperator.LESS_OR_EQUAL -> comparison <= 0
                ComparisonOperator.GREATER -> comparison > 0
                ComparisonOperator.GREATER_OR_EQUAL -> comparison >= 0
            }
        }
        is ConditionalCondition.Between -> {
            val raw = value.coerceNumber()
            val lower = condition.lower.toBigDecimalOrNull()
            val upper = condition.upper.toBigDecimalOrNull()
            if (raw == null || lower == null || upper == null) false else {
                val match = raw >= lower && raw <= upper
                if (condition.negated) !match else match
            }
        }
        is ConditionalCondition.TextContains -> value.displayText().contains(condition.text, true)
        is ConditionalCondition.CustomFormula -> {
            val cell = CalculationCell(rowId = coordinate.rowId, columnId = coordinate.columnId, input = CellInput.Formula(condition.source))
            val parsed = parser.parse(condition.source) as? FormulaParseResult.Success
            parsed != null && evaluate(parsed.expression, sheet, cell, linkedSetOf(), EvaluationBudget(limits.maximumEvaluationSteps)).scalarOrError().truthy()
        }
        ConditionalCondition.Duplicate, ConditionalCondition.Unique -> {
            val count = stableCells(target).count { candidate -> valueOf(candidate).displayText() == value.displayText() }
            if (condition == ConditionalCondition.Duplicate) count > 1 else count == 1
        }
        is ConditionalCondition.ColorScale -> value.coerceNumber() != null
    }
}

/** How a criteria aggregate reduces the values it matched. */
private enum class AggregateIfMode { SUM, COUNT, AVERAGE }

/**
 * Functions whose value depends on the evaluation instant rather than on any cell.
 *
 * A formula naming one of these is dropped from the value cache at the start of every recalculation pass,
 * because the dependency graph cannot express "depends on the clock".
 */
private val VOLATILE_FUNCTIONS = setOf("TODAY", "NOW")

private sealed interface RuntimeValue {    data class Scalar(val value: CalculatedValue) : RuntimeValue
    data class Range(val rowCount: Int, val columnCount: Int, val values: Sequence<CalculatedValue>) : RuntimeValue
    fun asSequence(): Sequence<CalculatedValue> = when (this) { is Scalar -> sequenceOf(value); is Range -> values }
    fun flatten(): List<CalculatedValue> = asSequence().toList()
    fun indexed(row: Int, column: Int): CalculatedValue = when (this) {
        is Scalar -> if (row == 0 && column == 0) {
            value
        } else {
            CalculatedValue.ErrorValue(CalculationError(CalculationErrorKind.REFERENCE, "INDEX is outside the value"))
        }
        is Range -> if (row in 0 until rowCount && column in 0 until columnCount) {
            values.drop(row * columnCount + column).first()
        } else {
            CalculatedValue.ErrorValue(CalculationError(CalculationErrorKind.REFERENCE, "INDEX is outside the range"))
        }
    }
    fun scalarOrError(): CalculatedValue = when (this) {
        is Scalar -> value
        is Range -> values.firstOrNull() ?: CalculatedValue.Blank
    }
}

private fun CalculatedValue.runtime(): RuntimeValue = RuntimeValue.Scalar(this)
private fun valueError(detail: String): RuntimeValue = CalculatedValue.ErrorValue(CalculationError(CalculationErrorKind.VALUE, detail)).runtime()

private class EvaluationBudget(private var remaining: Int) {
    fun step() {
        if (--remaining < 0) throw EvaluationLimitException("Formula evaluation exceeded the operation budget")
    }
}

private class EvaluationLimitException(message: String) : IllegalStateException(message)

private fun interpolateColor(start: com.norfold.app.domain.style.ColorValue, end: com.norfold.app.domain.style.ColorValue, fraction: Float): com.norfold.app.domain.style.ColorValue {
    fun channel(a: Int, b: Int): Int = (a + (b - a) * fraction).toInt().coerceIn(0, 255)
    return com.norfold.app.domain.style.ColorValue(
        channel(start.red, end.red),
        channel(start.green, end.green),
        channel(start.blue, end.blue),
        channel(start.alpha, end.alpha),
    )
}

private fun CellInput.toCalculatedValue(): CalculatedValue = when (this) {
    CellInput.Blank -> CalculatedValue.Blank
    is CellInput.Text -> CalculatedValue.Text(value)
    is CellInput.Number -> CalculatedValue.Number(canonical.toBigDecimal())
    is CellInput.BooleanValue -> CalculatedValue.BooleanValue(value)
    is CellInput.DateValue -> CalculatedValue.DateValue(LocalDate.parse(isoDate))
    is CellInput.DateTimeValue -> CalculatedValue.DateTimeValue(LocalDateTime.parse(isoDateTime))
    is CellInput.Formula -> CalculatedValue.Text(source)
    is CellInput.ErrorValue -> CalculatedValue.ErrorValue(error)
}

fun CalculatedValue.displayText(): String = when (this) {
    CalculatedValue.Blank -> ""
    is CalculatedValue.Text -> value
    is CalculatedValue.Number -> value.stripTrailingZeros().toPlainString()
    is CalculatedValue.BooleanValue -> if (value) "TRUE" else "FALSE"
    is CalculatedValue.DateValue -> value.toString()
    is CalculatedValue.DateTimeValue -> value.toString()
    is CalculatedValue.ErrorValue -> error.displayLabel
}

fun formatCalculatedValue(value: CalculatedValue, format: CellNumberFormat): String {
    val number = (value as? CalculatedValue.Number)?.value
    return when (format.kind) {
        NumberFormatKind.GENERAL -> value.displayText()
        NumberFormatKind.NUMBER -> number?.setScale(2, RoundingMode.HALF_UP)?.toPlainString() ?: value.displayText()
        NumberFormatKind.INTEGER -> number?.setScale(0, RoundingMode.HALF_UP)?.toPlainString() ?: value.displayText()
        NumberFormatKind.PERCENTAGE -> number?.multiply(BigDecimal(100))?.setScale(2, RoundingMode.HALF_UP)?.stripTrailingZeros()?.toPlainString()?.plus("%") ?: value.displayText()
        NumberFormatKind.CURRENCY -> number?.setScale(2, RoundingMode.HALF_UP)?.toPlainString()?.let { "${format.currencyId.orEmpty()} $it".trim() } ?: value.displayText()
        NumberFormatKind.DATE -> when (value) {
            is CalculatedValue.DateValue -> value.value.toString()
            is CalculatedValue.DateTimeValue -> value.value.toLocalDate().toString()
            else -> value.displayText()
        }
        NumberFormatKind.DATE_TIME -> value.displayText()
        NumberFormatKind.TIME -> (value as? CalculatedValue.DateTimeValue)?.value?.toLocalTime()?.toString() ?: value.displayText()
        NumberFormatKind.TEXT -> value.displayText()
    }
}

fun CellInput.editorText(): String = when (this) {
    CellInput.Blank -> ""
    is CellInput.Text -> value
    is CellInput.Number -> canonical
    is CellInput.BooleanValue -> if (value) "TRUE" else "FALSE"
    is CellInput.DateValue -> isoDate
    is CellInput.DateTimeValue -> isoDateTime
    is CellInput.Formula -> source
    is CellInput.ErrorValue -> error.displayLabel
}

private fun CalculatedValue.coerceNumber(): BigDecimal? = when (this) {
    is CalculatedValue.Number -> value
    is CalculatedValue.BooleanValue -> if (value) BigDecimal.ONE else BigDecimal.ZERO
    is CalculatedValue.DateValue -> BigDecimal(value.toEpochDay())
    is CalculatedValue.DateTimeValue -> BigDecimal(value.toEpochSecond(ZoneOffset.UTC))
    is CalculatedValue.Text -> value.toBigDecimalOrNull()
    CalculatedValue.Blank -> BigDecimal.ZERO
    is CalculatedValue.ErrorValue -> null
}

/** Numeric participation for range aggregates; arithmetic coercion still treats blank as zero. */
private fun CalculatedValue.aggregateNumber(): BigDecimal? = when (this) {
    is CalculatedValue.Number -> value
    is CalculatedValue.DateValue -> BigDecimal(value.toEpochDay())
    is CalculatedValue.DateTimeValue -> BigDecimal(value.toEpochSecond(ZoneOffset.UTC))
    else -> null
}

private fun CalculatedValue.numberOrError(): CalculatedValue = when {
    // An incoming error keeps its own kind. Collapsing it into #VALUE! here would report "expected a number"
    // for a `#DIV/0!` the caller never produced, hiding the real cause one level down the expression.
    this is CalculatedValue.ErrorValue -> this
    else -> coerceNumber()?.let(CalculatedValue::Number)
        ?: CalculatedValue.ErrorValue(CalculationError(CalculationErrorKind.VALUE, "Expected a number"))
}

private fun CalculatedValue.truthy(): Boolean = when (this) {
    CalculatedValue.Blank -> false
    is CalculatedValue.BooleanValue -> value
    is CalculatedValue.Number -> value.compareTo(BigDecimal.ZERO) != 0
    is CalculatedValue.Text -> value.isNotEmpty() && !value.equals("false", true)
    is CalculatedValue.DateValue, is CalculatedValue.DateTimeValue -> true
    is CalculatedValue.ErrorValue -> false
}

private fun CalculatedValue.asDate(): LocalDate? = when (this) {
    is CalculatedValue.DateValue -> value
    is CalculatedValue.DateTimeValue -> value.toLocalDate()
    is CalculatedValue.Text -> runCatching { LocalDate.parse(value) }.getOrNull()
    else -> null
}

/** The clock component of a value, for HOUR/MINUTE/SECOND. A plain date has no time and yields null. */
private fun CalculatedValue.asTime(): java.time.LocalTime? = when (this) {
    is CalculatedValue.DateTimeValue -> value.toLocalTime()
    is CalculatedValue.Text -> runCatching { java.time.LocalTime.parse(value) }.getOrNull()
        ?: runCatching { LocalDateTime.parse(value).toLocalTime() }.getOrNull()
    else -> null
}

private fun compare(left: CalculatedValue, right: CalculatedValue): Int {
    val leftNumber = left.coerceNumber()
    val rightNumber = right.coerceNumber()
    return if (leftNumber != null && rightNumber != null) leftNumber.compareTo(rightNumber)
    else left.displayText().compareTo(right.displayText(), ignoreCase = true)
}

private fun criterionMatches(value: CalculatedValue, criterion: String): Boolean {
    val match = Regex("^(<=|>=|<>|=|<|>)(.*)$").matchEntire(criterion)
    if (match == null) return value.displayText().equals(criterion, true)
    val comparison = compare(value, CalculatedValue.Text(match.groupValues[2]))
    return when (match.groupValues[1]) {
        "<=" -> comparison <= 0
        ">=" -> comparison >= 0
        "<>" -> comparison != 0
        "=" -> comparison == 0
        "<" -> comparison < 0
        else -> comparison > 0
    }
}

private fun rangeContains(sheet: CalculationSheet, coordinate: StableCellCoordinate, range: StableCellRange): Boolean {
    if (coordinate.sheetId != range.start.sheetId || coordinate.sheetId != range.end.sheetId || sheet.id != coordinate.sheetId) return false
    val row = sheet.rowIndex(coordinate.rowId)
    val column = sheet.columnIndex(coordinate.columnId)
    val startRow = sheet.rowIndex(range.start.rowId)
    val endRow = sheet.rowIndex(range.end.rowId)
    val startColumn = sheet.columnIndex(range.start.columnId)
    val endColumn = sheet.columnIndex(range.end.columnId)
    return row in minOf(startRow, endRow)..maxOf(startRow, endRow) &&
        column in minOf(startColumn, endColumn)..maxOf(startColumn, endColumn)
}

private fun CellFormat.overlay(delta: CellFormat): CellFormat = copy(
    font = delta.font ?: font,
    fontSizePoints = delta.fontSizePoints ?: fontSizePoints,
    bold = delta.bold ?: bold,
    italic = delta.italic ?: italic,
    textColor = delta.textColor ?: textColor,
    fillColor = delta.fillColor ?: fillColor,
    borderTop = delta.borderTop ?: borderTop,
    borderRight = delta.borderRight ?: borderRight,
    borderBottom = delta.borderBottom ?: borderBottom,
    borderLeft = delta.borderLeft ?: borderLeft,
    horizontalAlignment = delta.horizontalAlignment ?: horizontalAlignment,
    verticalAlignment = delta.verticalAlignment ?: verticalAlignment,
    wrap = delta.wrap ?: wrap,
    paddingPoints = delta.paddingPoints ?: paddingPoints,
    numberFormat = if (delta.numberFormat == CellNumberFormat()) numberFormat else delta.numberFormat,
)

private fun sortedRange(a: Int, b: Int): IntRange? = if (a < 0 || b < 0) null else minOf(a, b)..maxOf(a, b)
