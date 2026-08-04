package com.norfold.app.editor.calculation

import java.math.BigDecimal

class FormulaParser(
    private val maxTokens: Int = 4_096,
    private val maxDepth: Int = 128,
) {
    fun parse(source: String): FormulaParseResult {
        if (!source.startsWith('=')) return failure("Formula must start with =")
        return try {
            val tokens = Lexer(source.drop(1), maxTokens).lex()
            val parser = Parser(tokens, maxDepth)
            val expression = parser.expression()
            parser.requireEnd()
            FormulaParseResult.Success(expression)
        } catch (e: FormulaSyntaxException) {
            failure(e.message ?: "Invalid formula")
        } catch (_: StackOverflowError) {
            FormulaParseResult.Failure(CalculationError(CalculationErrorKind.RESOURCE_LIMIT, "Formula nesting is too deep"))
        }
    }

    private fun failure(detail: String) = FormulaParseResult.Failure(CalculationError(CalculationErrorKind.PARSE, detail))
}

private enum class TokenType {
    NUMBER, STRING, IDENTIFIER, QUOTED_IDENTIFIER, ERROR,
    PLUS, MINUS, STAR, SLASH, CARET, AMPERSAND,
    EQUAL, NOT_EQUAL, LESS, LESS_EQUAL, GREATER, GREATER_EQUAL,
    LPAREN, RPAREN, COMMA, COLON, BANG, LBRACKET, RBRACKET, DOLLAR, PERCENT,
    END,
}

private data class Token(val type: TokenType, val text: String, val position: Int)

private class Lexer(private val source: String, private val maxTokens: Int) {
    private var index = 0
    private val result = mutableListOf<Token>()

    fun lex(): List<Token> {
        while (index < source.length) {
            if (result.size >= maxTokens) throw FormulaSyntaxException("Formula exceeds token budget")
            val start = index
            when (val ch = source[index]) {
                ' ', '\t', '\r', '\n' -> index++
                '+' -> single(TokenType.PLUS)
                '-' -> single(TokenType.MINUS)
                '*' -> single(TokenType.STAR)
                '/' -> single(TokenType.SLASH)
                '^' -> single(TokenType.CARET)
                '&' -> single(TokenType.AMPERSAND)
                '=' -> single(TokenType.EQUAL)
                '(' -> single(TokenType.LPAREN)
                ')' -> single(TokenType.RPAREN)
                ',' -> single(TokenType.COMMA)
                ':' -> single(TokenType.COLON)
                '!' -> single(TokenType.BANG)
                '[' -> single(TokenType.LBRACKET)
                ']' -> single(TokenType.RBRACKET)
                '$' -> single(TokenType.DOLLAR)
                '%' -> single(TokenType.PERCENT)
                '<' -> {
                    index++
                    when {
                        match('=') -> add(TokenType.LESS_EQUAL, start)
                        match('>') -> add(TokenType.NOT_EQUAL, start)
                        else -> add(TokenType.LESS, start)
                    }
                }
                '>' -> {
                    index++
                    if (match('=')) add(TokenType.GREATER_EQUAL, start) else add(TokenType.GREATER, start)
                }
                '"' -> string()
                '\'' -> quotedIdentifier()
                '#' -> errorToken()
                else -> when {
                    ch.isDigit() || (ch == '.' && source.getOrNull(index + 1)?.isDigit() == true) -> number()
                    ch.isLetter() || ch == '_' -> identifier()
                    else -> throw FormulaSyntaxException("Unexpected '$ch' at ${index + 1}")
                }
            }
        }
        result += Token(TokenType.END, "", index)
        return result
    }

    private fun single(type: TokenType) { val start = index++; result += Token(type, source.substring(start, index), start) }
    private fun add(type: TokenType, start: Int) { result += Token(type, source.substring(start, index), start) }
    private fun match(ch: Char): Boolean = if (source.getOrNull(index) == ch) { index++; true } else false

    private fun number() {
        val start = index
        while (source.getOrNull(index)?.isDigit() == true) index++
        if (source.getOrNull(index) == '.') { index++; while (source.getOrNull(index)?.isDigit() == true) index++ }
        if (source.getOrNull(index)?.lowercaseChar() == 'e') {
            index++
            if (source.getOrNull(index) in listOf('+', '-')) index++
            val exponentStart = index
            while (source.getOrNull(index)?.isDigit() == true) index++
            if (index == exponentStart) throw FormulaSyntaxException("Malformed exponent at ${start + 1}")
        }
        add(TokenType.NUMBER, start)
    }

    private fun identifier() {
        val start = index++
        while (source.getOrNull(index)?.let { it.isLetterOrDigit() || it == '_' || it == '.' } == true) index++
        add(TokenType.IDENTIFIER, start)
    }

    private fun string() {
        val start = index++
        val value = StringBuilder()
        while (index < source.length) {
            val ch = source[index++]
            if (ch == '"') {
                if (source.getOrNull(index) == '"') { index++; value.append('"'); continue }
                result += Token(TokenType.STRING, value.toString(), start)
                return
            }
            value.append(ch)
        }
        throw FormulaSyntaxException("Unterminated string at ${start + 1}")
    }

    private fun quotedIdentifier() {
        val start = index++
        val value = StringBuilder()
        while (index < source.length) {
            val ch = source[index++]
            if (ch == '\'') {
                if (source.getOrNull(index) == '\'') { index++; value.append('\''); continue }
                result += Token(TokenType.QUOTED_IDENTIFIER, value.toString(), start)
                return
            }
            value.append(ch)
        }
        throw FormulaSyntaxException("Unterminated sheet name at ${start + 1}")
    }

    private fun errorToken() {
        val start = index++
        while (source.getOrNull(index)?.let { !it.isWhitespace() && it !in ",)" } == true) index++
        add(TokenType.ERROR, start)
    }
}

private class Parser(private val tokens: List<Token>, private val maxDepth: Int) {
    private var index = 0
    private var depth = 0

    fun expression(minimumPrecedence: Int = 0): FormulaExpression = guarded {
        var left = prefix()
        while (true) {
            if (match(TokenType.PERCENT)) {
                left = FormulaExpression.Unary(UnaryOperator.PERCENT, left)
                continue
            }
            val operator = binaryOperator(peek().type) ?: break
            val precedence = operator.precedenceValue
            if (precedence < minimumPrecedence) break
            advance()
            val nextMinimum = if (operator == BinaryOperator.POWER) precedence else precedence + 1
            left = FormulaExpression.Binary(left, operator, expression(nextMinimum))
        }
        left
    }

    fun requireEnd() {
        if (peek().type != TokenType.END) throw syntax("Unexpected '${peek().text}'")
    }

    private fun prefix(): FormulaExpression = when (peek().type) {
        TokenType.PLUS -> { advance(); FormulaExpression.Unary(UnaryOperator.PLUS, expression(70)) }
        TokenType.MINUS -> { advance(); FormulaExpression.Unary(UnaryOperator.MINUS, expression(70)) }
        TokenType.LPAREN -> {
            advance()
            val value = expression()
            consume(TokenType.RPAREN, "Expected )")
            value
        }
        TokenType.NUMBER -> {
            val token = advance()
            // The same bound the durable input uses. A literal reaches the evaluator without passing through
            // CellInputParser, so `=1e2147483000` would otherwise arrive as a value whose plain rendering is
            // billions of digits long — an out-of-memory crash on display, not a calculation error.
            FormulaExpression.NumberLiteral(parseRenderableDecimal(token.text) ?: throw syntax("Number is out of range"))
        }
        TokenType.STRING -> FormulaExpression.TextLiteral(advance().text)
        TokenType.ERROR -> errorLiteral(advance().text)
        TokenType.IDENTIFIER, TokenType.QUOTED_IDENTIFIER, TokenType.DOLLAR -> referenceOrIdentifier()
        else -> throw syntax("Expected a value")
    }

    private fun referenceOrIdentifier(): FormulaExpression {
        val explicitSheet = if (peek().type in setOf(TokenType.IDENTIFIER, TokenType.QUOTED_IDENTIFIER) && peek(1).type == TokenType.BANG) {
            val sheet = advance().text
            advance()
            sheet
        } else null

        val mark = index
        parseA1(explicitSheet)?.let { first ->
            if (match(TokenType.COLON)) {
                val second = parseA1(explicitSheet) ?: throw syntax("Expected a cell after :")
                return FormulaExpression.RangeReference(first, second)
            }
            return FormulaExpression.CellReference(first)
        }
        index = mark
        if (explicitSheet != null) throw syntax("Expected a cell after !")

        val token = consume(TokenType.IDENTIFIER, "Expected a name")
        val name = token.text
        if (match(TokenType.LPAREN)) {
            val args = mutableListOf<FormulaExpression>()
            if (!match(TokenType.RPAREN)) {
                do { args += expression() } while (match(TokenType.COMMA))
                consume(TokenType.RPAREN, "Expected ) after function arguments")
            }
            return FormulaExpression.FunctionCall(name.uppercase(), args)
        }
        if (match(TokenType.LBRACKET)) {
            val column = consume(TokenType.IDENTIFIER, "Expected a table column").text
            consume(TokenType.RBRACKET, "Expected ]")
            return FormulaExpression.StructuredReference(name, column)
        }
        return when (name.uppercase()) {
            "TRUE" -> FormulaExpression.BooleanLiteral(true)
            "FALSE" -> FormulaExpression.BooleanLiteral(false)
            "NOT" -> FormulaExpression.Unary(UnaryOperator.NOT, expression(70))
            else -> FormulaExpression.NamedReference(name)
        }
    }

    /**
     * A cell address, or `null` when the tokens are a name instead.
     *
     * The bound is the addressable grid, not "letters then digits". Without it `Table1` and `Q1_2024` read as
     * cell addresses in columns `TABLE`/`Q` — a table named `Table1` would resolve to whatever is in the
     * local cell, which is worse than an error because it looks like a number.
     */
    private fun parseA1(sheetName: String?): A1Reference? {
        val start = index
        val absoluteColumn = match(TokenType.DOLLAR)
        val columnToken = peek().takeIf { it.type == TokenType.IDENTIFIER } ?: run { index = start; return null }
        val parts = Regex("([A-Za-z]+)([1-9][0-9]*)").matchEntire(columnToken.text)
        if (parts != null) {
            val column = columnIndex(parts.groupValues[1])?.takeIf { it < MAX_ADDRESSABLE_COLUMNS }
                ?: run { index = start; return null }
            val row = parts.groupValues[2].toIntOrNull()?.minus(1)?.takeIf { it < MAX_ADDRESSABLE_ROWS }
                ?: run { index = start; return null }
            advance()
            return A1Reference(sheetName, row, column, absoluteRow = false, absoluteColumn = absoluteColumn)
        }
        if (!columnToken.text.all(Char::isLetter)) { index = start; return null }
        val column = columnIndex(columnToken.text)?.takeIf { it < MAX_ADDRESSABLE_COLUMNS }
            ?: run { index = start; return null }
        advance()
        val absoluteRow = match(TokenType.DOLLAR)
        val rowToken = peek().takeIf { it.type == TokenType.NUMBER && !it.text.contains('.') } ?: run { index = start; return null }
        val row = rowToken.text.toIntOrNull()?.minus(1)?.takeIf { it in 0 until MAX_ADDRESSABLE_ROWS }
            ?: run { index = start; return null }
        advance()
        return A1Reference(sheetName, row, column, absoluteRow, absoluteColumn)
    }

    private fun errorLiteral(raw: String): FormulaExpression = FormulaExpression.ErrorLiteral(
        CalculationError(
            kind = when (raw.uppercase()) {
                "#REF!" -> CalculationErrorKind.REFERENCE
                "#DIV/0!" -> CalculationErrorKind.DIVIDE_BY_ZERO
                "#NAME?" -> CalculationErrorKind.NAME
                "#N/A" -> CalculationErrorKind.UNAVAILABLE
                "#CYCLE!" -> CalculationErrorKind.CYCLE
                "#LIMIT!" -> CalculationErrorKind.RESOURCE_LIMIT
                else -> CalculationErrorKind.VALUE
            },
            detail = raw,
        ),
    )

    private fun binaryOperator(type: TokenType): BinaryOperator? = when (type) {
        TokenType.PLUS -> BinaryOperator.ADD
        TokenType.MINUS -> BinaryOperator.SUBTRACT
        TokenType.STAR -> BinaryOperator.MULTIPLY
        TokenType.SLASH -> BinaryOperator.DIVIDE
        TokenType.CARET -> BinaryOperator.POWER
        TokenType.AMPERSAND -> BinaryOperator.CONCATENATE
        TokenType.EQUAL -> BinaryOperator.EQUAL
        TokenType.NOT_EQUAL -> BinaryOperator.NOT_EQUAL
        TokenType.LESS -> BinaryOperator.LESS
        TokenType.LESS_EQUAL -> BinaryOperator.LESS_OR_EQUAL
        TokenType.GREATER -> BinaryOperator.GREATER
        TokenType.GREATER_EQUAL -> BinaryOperator.GREATER_OR_EQUAL
        else -> null
    }

    private fun peek(offset: Int = 0): Token = tokens[(index + offset).coerceAtMost(tokens.lastIndex)]
    private fun advance(): Token = tokens[index++]
    private fun match(type: TokenType): Boolean = if (peek().type == type) { index++; true } else false
    private fun consume(type: TokenType, message: String): Token = if (peek().type == type) advance() else throw syntax(message)
    private fun syntax(message: String) = FormulaSyntaxException("$message at ${peek().position + 1}")
    private fun <T> guarded(block: () -> T): T {
        if (++depth > maxDepth) throw FormulaSyntaxException("Formula nesting exceeds $maxDepth")
        return try { block() } finally { depth-- }
    }
}

private val BinaryOperator.precedenceValue: Int
    get() = when (this) {
        BinaryOperator.EQUAL, BinaryOperator.NOT_EQUAL, BinaryOperator.LESS, BinaryOperator.LESS_OR_EQUAL,
        BinaryOperator.GREATER, BinaryOperator.GREATER_OR_EQUAL -> 10
        BinaryOperator.CONCATENATE -> 20
        BinaryOperator.ADD, BinaryOperator.SUBTRACT -> 30
        BinaryOperator.MULTIPLY, BinaryOperator.DIVIDE -> 40
        BinaryOperator.POWER -> 50
    }

private class FormulaSyntaxException(message: String) : IllegalArgumentException(message)
