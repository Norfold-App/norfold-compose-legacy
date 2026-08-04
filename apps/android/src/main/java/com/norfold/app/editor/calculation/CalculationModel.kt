package com.norfold.app.editor.calculation

import com.norfold.app.domain.style.ColorValue
import com.norfold.app.domain.style.FontIdentity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

private fun newCalculationId(): String = UUID.randomUUID().toString()

@Serializable @JvmInline value class WorkbookId(val value: String)
@Serializable @JvmInline value class SheetId(val value: String)
@Serializable @JvmInline value class EmbeddedTableId(val value: String)
@Serializable @JvmInline value class RowId(val value: String)
@Serializable @JvmInline value class ColumnId(val value: String)
@Serializable @JvmInline value class CellId(val value: String)
@Serializable @JvmInline value class NamedRangeId(val value: String)
@Serializable @JvmInline value class ValidationRuleId(val value: String)
@Serializable @JvmInline value class ConditionalRuleId(val value: String)
@Serializable @JvmInline value class MergedRegionId(val value: String)
@Serializable @JvmInline value class FilterDefinitionId(val value: String)
@Serializable @JvmInline value class SortDefinitionId(val value: String)
@Serializable @JvmInline value class TableStyleId(val value: String)

@Serializable
data class CalculationWorkbook(
    val id: WorkbookId = WorkbookId(newCalculationId()),
    val displayName: String = "Workbook",
    val sheets: List<CalculationSheet> = listOf(CalculationSheet()),
    val activeSheetId: SheetId = sheets.first().id,
    val namedRanges: List<NamedRange> = emptyList(),
    val embeddedTables: List<EmbeddedTableDefinition> = emptyList(),
    val tableStyles: List<TableStyleDefinition> = emptyList(),
    val revision: Long = 0,
) {
    init {
        require(sheets.isNotEmpty()) { "A workbook requires at least one sheet" }
        require(sheets.map { it.id }.distinct().size == sheets.size) { "Sheet ids must be unique" }
        require(sheets.map { it.canonicalName }.distinct().size == sheets.size) { "Sheet names must be unique ignoring case" }
        require(activeSheetId in sheets.map { it.id }) { "The active sheet must exist" }
    }

    fun sheet(id: SheetId): CalculationSheet? = sheets.firstOrNull { it.id == id }
    fun sheetNamed(name: String): CalculationSheet? = sheets.firstOrNull { it.name.equals(name, ignoreCase = true) }
    fun activeSheet(): CalculationSheet = sheet(activeSheetId) ?: sheets.first()
    fun namedRange(name: String): NamedRange? = namedRanges.firstOrNull { it.canonicalName == canonicalName(name) }
    fun embeddedTable(name: String): EmbeddedTableDefinition? =
        embeddedTables.firstOrNull { it.canonicalName == canonicalName(name) }

    fun withSheet(sheet: CalculationSheet): CalculationWorkbook = copy(
        sheets = sheets.map { if (it.id == sheet.id) sheet else it },
        revision = revision + 1,
    )
}

@Serializable
data class CalculationSheet(
    val id: SheetId = SheetId(newCalculationId()),
    val name: String = "Sheet1",
    val rowCount: Int = DEFAULT_ROWS,
    val columnCount: Int = DEFAULT_COLUMNS,
    val rows: List<CalculationRow> = List(rowCount) { CalculationRow() },
    val columns: List<CalculationColumn> = List(columnCount) { CalculationColumn() },
    /** Sparse durable storage: only cells with input or durable presentation metadata exist here. */
    val cells: List<CalculationCell> = emptyList(),
    val mergedRegions: List<MergedRegion> = emptyList(),
    val validations: List<ValidationRule> = emptyList(),
    val conditionalFormats: List<ConditionalFormatRule> = emptyList(),
    val filters: List<FilterDefinition> = emptyList(),
    val sorts: List<SortDefinition> = emptyList(),
    val hidden: Boolean = false,
    val frozenRows: Int = 0,
    val frozenColumns: Int = 0,
) {
    init {
        require(name.isNotBlank()) { "Sheet name cannot be blank" }
        require(rowCount > 0 && columnCount > 0) { "Sheet dimensions must be positive" }
        require(rows.size == rowCount) { "Row identity list must match rowCount" }
        require(columns.size == columnCount) { "Column identity list must match columnCount" }
        require(rows.map { it.id }.distinct().size == rows.size) { "Row ids must be unique" }
        require(columns.map { it.id }.distinct().size == columns.size) { "Column ids must be unique" }
        require(frozenRows in 0..rowCount && frozenColumns in 0..columnCount) { "Freeze boundaries are outside the sheet" }
    }

    val canonicalName: String get() = canonicalName(name)

    fun row(index: Int): CalculationRow? = rows.getOrNull(index)
    fun column(index: Int): CalculationColumn? = columns.getOrNull(index)
    fun rowIndex(id: RowId): Int = rows.indexOfFirst { it.id == id }
    fun columnIndex(id: ColumnId): Int = columns.indexOfFirst { it.id == id }
    fun cell(id: CellId): CalculationCell? = cells.firstOrNull { it.id == id }
    fun cell(rowId: RowId, columnId: ColumnId): CalculationCell? =
        cells.firstOrNull { it.rowId == rowId && it.columnId == columnId }

    fun cellAt(row: Int, column: Int): CalculationCell? {
        val rowId = this.row(row)?.id ?: return null
        val columnId = this.column(column)?.id ?: return null
        return cell(rowId, columnId)
    }

    fun addressOf(cell: CalculationCell): GridAddress? {
        val row = rowIndex(cell.rowId)
        val column = columnIndex(cell.columnId)
        return if (row >= 0 && column >= 0) GridAddress(row, column) else null
    }

    fun coordinateAt(row: Int, column: Int): StableCellCoordinate? {
        val rowId = this.row(row)?.id ?: return null
        val columnId = this.column(column)?.id ?: return null
        return StableCellCoordinate(id, rowId, columnId)
    }

    fun upsertCell(cell: CalculationCell): CalculationSheet {
        require(rowIndex(cell.rowId) >= 0 && columnIndex(cell.columnId) >= 0) { "Cell identity is outside this sheet" }
        val index = cells.indexOfFirst { it.id == cell.id || (it.rowId == cell.rowId && it.columnId == cell.columnId) }
        val next = when {
            cell.isDurablyEmpty() && index >= 0 -> cells.toMutableList().also { it.removeAt(index) }
            cell.isDurablyEmpty() -> cells
            index >= 0 -> cells.toMutableList().also { it[index] = cell }
            else -> cells + cell
        }
        return copy(cells = next)
    }

    companion object {
        const val DEFAULT_ROWS = 100
        const val DEFAULT_COLUMNS = 26
    }
}

@Serializable
data class CalculationRow(
    val id: RowId = RowId(newCalculationId()),
    val heightPoints: Float? = null,
    val hidden: Boolean = false,
)

@Serializable
data class CalculationColumn(
    val id: ColumnId = ColumnId(newCalculationId()),
    val name: String = "",
    val widthPoints: Float? = null,
    val hidden: Boolean = false,
)

@Serializable
data class CalculationCell(
    val id: CellId = CellId(newCalculationId()),
    val rowId: RowId,
    val columnId: ColumnId,
    val input: CellInput = CellInput.Blank,
    val format: CellFormat = CellFormat(),
    val validationRuleIds: List<ValidationRuleId> = emptyList(),
) {
    fun isDurablyEmpty(): Boolean =
        input == CellInput.Blank && format == CellFormat() && validationRuleIds.isEmpty()
}

@Serializable
sealed interface CellInput {
    @Serializable @SerialName("blank") data object Blank : CellInput
    @Serializable @SerialName("text") data class Text(val value: String) : CellInput
    @Serializable @SerialName("number") data class Number(val canonical: String) : CellInput {
        init { require(parseFiniteDecimal(canonical) != null) { "Number must use a finite locale-neutral decimal" } }
    }
    @Serializable @SerialName("boolean") data class BooleanValue(val value: Boolean) : CellInput
    @Serializable @SerialName("date") data class DateValue(val isoDate: String) : CellInput {
        init { require(runCatching { LocalDate.parse(isoDate) }.isSuccess) { "Malformed durable date" } }
    }
    @Serializable @SerialName("date_time") data class DateTimeValue(val isoDateTime: String) : CellInput {
        init { require(runCatching { LocalDateTime.parse(isoDateTime) }.isSuccess) { "Malformed durable date-time" } }
    }
    @Serializable @SerialName("formula") data class Formula(val source: String) : CellInput {
        init { require(source.startsWith('=')) { "Formula source must start with =" } }
    }
    @Serializable @SerialName("error") data class ErrorValue(val error: CalculationError) : CellInput
}

@Serializable
enum class CalculationErrorKind {
    PARSE,
    VALUE,
    DIVIDE_BY_ZERO,
    REFERENCE,
    NAME,
    CYCLE,
    UNAVAILABLE,
    VALIDATION,
    INTERNAL,
    RESOURCE_LIMIT,
}

@Serializable
data class CalculationError(
    val kind: CalculationErrorKind,
    val detail: String = "",
) {
    val displayLabel: String
        get() = when (kind) {
            CalculationErrorKind.PARSE -> "#PARSE!"
            CalculationErrorKind.VALUE, CalculationErrorKind.VALIDATION -> "#VALUE!"
            CalculationErrorKind.DIVIDE_BY_ZERO -> "#DIV/0!"
            CalculationErrorKind.REFERENCE -> "#REF!"
            CalculationErrorKind.NAME -> "#NAME?"
            CalculationErrorKind.CYCLE -> "#CYCLE!"
            CalculationErrorKind.UNAVAILABLE -> "#N/A"
            CalculationErrorKind.INTERNAL -> "#ERROR!"
            CalculationErrorKind.RESOURCE_LIMIT -> "#LIMIT!"
        }
}

sealed interface CalculatedValue {
    data object Blank : CalculatedValue
    data class Text(val value: String) : CalculatedValue
    data class Number(val value: BigDecimal) : CalculatedValue
    data class BooleanValue(val value: Boolean) : CalculatedValue
    data class DateValue(val value: LocalDate) : CalculatedValue
    data class DateTimeValue(val value: LocalDateTime) : CalculatedValue
    data class ErrorValue(val error: CalculationError) : CalculatedValue
}

@Serializable
data class StableCellCoordinate(
    val sheetId: SheetId,
    val rowId: RowId,
    val columnId: ColumnId,
)

@Serializable
data class StableCellRange(
    val start: StableCellCoordinate,
    val end: StableCellCoordinate,
) {
    init { require(start.sheetId == end.sheetId) { "A range cannot span sheets" } }
}

data class GridAddress(val row: Int, val column: Int) {
    init { require(row >= 0 && column >= 0) { "Grid address cannot be negative" } }
    fun a1(): String = "${columnName(column)}${row + 1}"
}

@Serializable
data class NamedRange(
    val id: NamedRangeId = NamedRangeId(newCalculationId()),
    val name: String,
    val target: StableCellRange,
) {
    init { require(isValidCanonicalName(name)) { "Invalid named range '$name'" } }
    val canonicalName: String get() = canonicalName(name)
}

@Serializable
data class EmbeddedTableColumn(
    val id: ColumnId,
    val name: String,
) {
    init { require(name.isNotBlank()) { "Table column name cannot be blank" } }
    val canonicalName: String get() = canonicalName(name)
}

@Serializable
data class EmbeddedTableDefinition(
    val id: EmbeddedTableId = EmbeddedTableId(newCalculationId()),
    val name: String,
    val range: StableCellRange,
    val columns: List<EmbeddedTableColumn>,
    val styleId: TableStyleId? = null,
    val headerRow: Boolean = true,
    val totalRow: Boolean = false,
) {
    init {
        require(isValidCanonicalName(name)) { "Invalid table name '$name'" }
        require(columns.map { it.canonicalName }.distinct().size == columns.size) { "Table column names must be unique" }
    }
    val canonicalName: String get() = canonicalName(name)
}

@Serializable enum class HorizontalCellAlignment { START, CENTER, END }
@Serializable enum class VerticalCellAlignment { TOP, CENTER, BOTTOM }
@Serializable enum class CellBorderStyle { NONE, SOLID, DASHED, DOTTED, DOUBLE }

@Serializable
data class CellBorder(
    val color: ColorValue = ColorValue(0, 0, 0),
    val widthPoints: Float = 1f,
    val style: CellBorderStyle = CellBorderStyle.SOLID,
) {
    init { require(widthPoints.isFinite() && widthPoints >= 0f) { "Border width must be finite and non-negative" } }
}

@Serializable enum class NumberFormatKind { GENERAL, NUMBER, INTEGER, PERCENTAGE, CURRENCY, DATE, DATE_TIME, TIME, TEXT }

@Serializable
data class CellNumberFormat(
    val kind: NumberFormatKind = NumberFormatKind.GENERAL,
    val currencyId: String? = null,
) {
    init { require(kind == NumberFormatKind.CURRENCY || currencyId == null) { "Currency id only applies to Currency format" } }
}

@Serializable
data class CellFormat(
    val font: FontIdentity? = null,
    val fontSizePoints: Float? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val textColor: ColorValue? = null,
    val fillColor: ColorValue? = null,
    val borderTop: CellBorder? = null,
    val borderRight: CellBorder? = null,
    val borderBottom: CellBorder? = null,
    val borderLeft: CellBorder? = null,
    val horizontalAlignment: HorizontalCellAlignment? = null,
    val verticalAlignment: VerticalCellAlignment? = null,
    val wrap: Boolean? = null,
    val paddingPoints: Float? = null,
    val numberFormat: CellNumberFormat = CellNumberFormat(),
) {
    init {
        require(fontSizePoints == null || (fontSizePoints.isFinite() && fontSizePoints in 1f..512f)) { "Invalid font size" }
        require(paddingPoints == null || (paddingPoints.isFinite() && paddingPoints >= 0f)) { "Invalid padding" }
    }
}

@Serializable
data class TableStyleDefinition(
    val id: TableStyleId = TableStyleId(newCalculationId()),
    val name: String,
    val parentId: TableStyleId? = null,
    val header: CellFormat = CellFormat(),
    val body: CellFormat = CellFormat(),
    val total: CellFormat = CellFormat(),
    val firstColumn: CellFormat = CellFormat(),
    val lastColumn: CellFormat = CellFormat(),
    val bandedRowFormat: CellFormat = CellFormat(),
    val bandedColumnFormat: CellFormat = CellFormat(),
    val bandedRows: Boolean = false,
    val bandedColumns: Boolean = false,
)

@Serializable enum class ValidationPolicy { REJECT, WARN }

@Serializable
sealed interface ValidationCondition {
    @Serializable @SerialName("list") data class AllowedValues(val values: List<String>) : ValidationCondition
    @Serializable @SerialName("number_range") data class NumberRange(val minimum: String?, val maximum: String?) : ValidationCondition
    @Serializable @SerialName("integer_range") data class IntegerRange(val minimum: Long?, val maximum: Long?) : ValidationCondition
    @Serializable @SerialName("date_range") data class DateRange(val minimumIso: String?, val maximumIso: String?) : ValidationCondition
    @Serializable @SerialName("text_length") data class TextLength(val minimum: Int?, val maximum: Int?) : ValidationCondition
    @Serializable @SerialName("custom_formula") data class CustomFormula(val source: String) : ValidationCondition
}

@Serializable
data class ValidationRule(
    val id: ValidationRuleId = ValidationRuleId(newCalculationId()),
    val target: StableCellRange,
    val condition: ValidationCondition,
    val allowBlank: Boolean = true,
    val inputMessage: String = "",
    val policy: ValidationPolicy = ValidationPolicy.REJECT,
    val errorMessage: String = "Value does not satisfy this cell's validation rule.",
)

@Serializable
sealed interface ConditionalCondition {
    @Serializable @SerialName("comparison") data class Comparison(val operator: ComparisonOperator, val source: String) : ConditionalCondition
    @Serializable @SerialName("between") data class Between(val lower: String, val upper: String, val negated: Boolean = false) : ConditionalCondition
    @Serializable @SerialName("text_contains") data class TextContains(val text: String) : ConditionalCondition
    @Serializable @SerialName("custom_formula") data class CustomFormula(val source: String) : ConditionalCondition
    @Serializable @SerialName("duplicate") data object Duplicate : ConditionalCondition
    @Serializable @SerialName("unique") data object Unique : ConditionalCondition
    @Serializable @SerialName("color_scale") data class ColorScale(
        val minimum: ColorValue,
        val midpoint: ColorValue? = null,
        val maximum: ColorValue,
    ) : ConditionalCondition

    /**
     * Why a rule fired, in words.
     *
     * The author's own `accessibleDescription` may be anything ("Conditional rule"), so relying on it alone
     * leaves the *reason* conveyed by fill colour and nothing else. This is the non-colour half of the
     * announcement, and it comes from the condition rather than from what someone remembered to type.
     */
    fun describe(): String = when (this) {
        is Comparison -> "value is ${operator.describe()} $source"
        is Between -> if (negated) "value is not between $lower and $upper" else "value is between $lower and $upper"
        is TextContains -> "text contains \"$text\""
        is CustomFormula -> "custom formula $source is true"
        Duplicate -> "Duplicate value in the rule range"
        Unique -> "Unique value in the rule range"
        is ColorScale -> "value is positioned on the range colour scale"
    }
}

internal fun ComparisonOperator.describe(): String = when (this) {
    ComparisonOperator.EQUAL -> "equal to"
    ComparisonOperator.NOT_EQUAL -> "not equal to"
    ComparisonOperator.LESS -> "less than"
    ComparisonOperator.LESS_OR_EQUAL -> "less than or equal to"
    ComparisonOperator.GREATER -> "greater than"
    ComparisonOperator.GREATER_OR_EQUAL -> "greater than or equal to"
}

@Serializable enum class ComparisonOperator { EQUAL, NOT_EQUAL, LESS, LESS_OR_EQUAL, GREATER, GREATER_OR_EQUAL }

@Serializable
data class ConditionalFormatRule(
    val id: ConditionalRuleId = ConditionalRuleId(newCalculationId()),
    val target: StableCellRange,
    val condition: ConditionalCondition,
    val priority: Int,
    val enabled: Boolean = true,
    val stopIfTrue: Boolean = false,
    val formatDelta: CellFormat = CellFormat(),
    /** Textual meaning is mandatory when the rule materially changes presentation; color is never the only signal. */
    val accessibleDescription: String,
)

@Serializable enum class SortDirection { ASCENDING, DESCENDING }
@Serializable enum class BlankSortPolicy { FIRST, LAST }
@Serializable enum class ErrorSortPolicy { FIRST, LAST }

@Serializable
data class SortKey(
    val columnId: ColumnId,
    val direction: SortDirection,
)

@Serializable
data class SortDefinition(
    val id: SortDefinitionId = SortDefinitionId(newCalculationId()),
    val target: StableCellRange,
    val keys: List<SortKey>,
    val headerAware: Boolean = true,
    val blanks: BlankSortPolicy = BlankSortPolicy.LAST,
    val errors: ErrorSortPolicy = ErrorSortPolicy.LAST,
)

@Serializable
sealed interface FilterCondition {
    @Serializable @SerialName("values") data class Values(val allowed: List<String>) : FilterCondition
    @Serializable @SerialName("text") data class Text(val operator: TextFilterOperator, val value: String) : FilterCondition
    @Serializable @SerialName("number") data class Number(val operator: ComparisonOperator, val value: String) : FilterCondition
    @Serializable @SerialName("date") data class Date(val operator: ComparisonOperator, val isoDate: String) : FilterCondition
}

@Serializable enum class TextFilterOperator { CONTAINS, NOT_CONTAINS, STARTS_WITH, ENDS_WITH, EQUALS }

@Serializable
data class FilterDefinition(
    val id: FilterDefinitionId = FilterDefinitionId(newCalculationId()),
    val target: StableCellRange,
    val columnId: ColumnId,
    val condition: FilterCondition,
    val enabled: Boolean = true,
)

@Serializable
data class MergedRegion(
    val id: MergedRegionId = MergedRegionId(newCalculationId()),
    val range: StableCellRange,
)

data class MixedValue<T>(val value: T?, val mixed: Boolean) {
    companion object {
        fun <T> of(values: List<T>): MixedValue<T> = when {
            values.isEmpty() -> MixedValue(null, false)
            values.distinct().size == 1 -> MixedValue(values.first(), false)
            else -> MixedValue(null, true)
        }
    }
}

object CalculationWorkbookJson {
    val format = Json {
        classDiscriminator = "kind"
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun encode(workbook: CalculationWorkbook): String = format.encodeToString(workbook)
    fun decode(payload: String): CalculationWorkbook = format.decodeFromString(payload)
}

/**
 * The widest exponent a durable number may carry, in either direction.
 *
 * `BigDecimal` accepts an exponent up to `Integer.MAX_VALUE` without complaint, but rendering such a value
 * is what breaks: `1e2147483000` has a scale of about −2.1 billion, so `toPlainString()` tries to build a
 * two-billion-character string and throws `OutOfMemoryError` — a crash, not a cell error, from one
 * keystroke. The bound is generous next to any real spreadsheet number (a googol is 1e100) and every
 * durable number passes through here, so no path reaches a value it cannot print.
 */
private const val MAX_DECIMAL_EXPONENT = 100_000

internal fun parseFiniteDecimal(raw: String): BigDecimal? = runCatching { BigDecimal(raw) }
    .getOrNull()
    ?.takeIf { raw.lowercase() !in setOf("nan", "infinity", "+infinity", "-infinity") }
    ?.takeIf { it.isRenderableDecimal() }

/** [parseFiniteDecimal] under its plain-decimal name, for callers outside the durable-input path. */
internal fun parseRenderableDecimal(raw: String): BigDecimal? = parseFiniteDecimal(raw)

/**
 * Whether this value can be rendered as plain decimal text without exhausting memory.
 *
 * Both directions matter: a huge negative scale means a colossal integer, and a huge positive scale means
 * that many digits after the point. `precision` is included because the digit count adds to the length of
 * the string that has to be produced.
 */
internal fun BigDecimal.isRenderableDecimal(): Boolean {
    val exponent = if (scale() < 0) -scale().toLong() + precision() else scale().toLong()
    return exponent <= MAX_DECIMAL_EXPONENT
}

/**
 * A calculated number, or a resource-limit error when the result is too wide to render.
 *
 * Guarding input alone is not enough: `=A1*A1` doubles the scale every time, so a chain of ordinary-looking
 * squarings starting from an accepted value reaches a scale of hundreds of millions within a dozen rows.
 * The value itself computes fine; it is *displaying* it that allocates a string of that many characters and
 * takes the app down. A structured error is the honest result — the user sees a cell error they can act on
 * instead of a freeze.
 */
internal fun calculatedNumber(value: BigDecimal): CalculatedValue {
    val stripped = value.stripTrailingZeros()
    return if (stripped.isRenderableDecimal()) {
        CalculatedValue.Number(stripped)
    } else {
        CalculatedValue.ErrorValue(
            CalculationError(CalculationErrorKind.RESOURCE_LIMIT, "The result has too many digits to display"),
        )
    }
}

internal fun canonicalName(raw: String): String = raw.trim().lowercase()

/**
 * The addressable A1 grid. This is one bound with two consumers — the parser, which decides whether
 * `Table1` is a cell or a name, and [isValidCanonicalName], which decides whether it may be *used* as a
 * name. Deriving both from the same constants is what stops them disagreeing: a looser name rule would
 * accept `Q1` and then watch the parser silently read cell Q1 instead, and a looser parser rule would
 * reject `Table1` as a table name because "TABLE" looks like a column.
 *
 * These are grid bounds, not owner-approved capacity limits; sparse storage means an empty grid this size
 * costs nothing.
 */
const val MAX_ADDRESSABLE_COLUMNS: Int = 16_384

const val MAX_ADDRESSABLE_ROWS: Int = 1_048_576

/** Whether `raw` would parse as an A1 cell address inside the addressable grid — `Q1` yes, `Table1` no. */
internal fun looksLikeCellAddress(raw: String): Boolean {
    val parts = Regex("\\$?([A-Za-z]+)\\$?([1-9][0-9]*)").matchEntire(raw) ?: return false
    val column = columnIndex(parts.groupValues[1]) ?: return false
    val row = parts.groupValues[2].toIntOrNull()?.minus(1) ?: return false
    return column < MAX_ADDRESSABLE_COLUMNS && row < MAX_ADDRESSABLE_ROWS
}

internal fun isValidCanonicalName(raw: String): Boolean =
    raw.matches(Regex("[A-Za-z_][A-Za-z0-9_.]*")) && !looksLikeCellAddress(raw)

internal fun columnName(index: Int): String {
    require(index >= 0)
    var value = index + 1
    val out = StringBuilder()
    while (value > 0) {
        value--
        out.append(('A'.code + value % 26).toChar())
        value /= 26
    }
    return out.reverse().toString()
}

internal fun columnIndex(name: String): Int? {
    if (name.isEmpty() || name.any { !it.isLetter() }) return null
    var value = 0L
    name.uppercase().forEach { ch ->
        value = value * 26 + (ch.code - 'A'.code + 1)
        if (value > Int.MAX_VALUE) return null
    }
    return (value - 1).toInt().takeIf { it >= 0 }
}
