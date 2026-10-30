package dev.omalauncher.pp.search

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * What the bar shows for arithmetic or a conversion: [value] is what Enter copies, [text] what the row says ("3.106856 mi"),
 * [expression] the query as it was read.
 */
data class CalcResult(
    val value: String,
    val text: String,
    val expression: String,
)

/**
 * The bar's calculator: arithmetic ("12*(3+4)", "2^10", "sqrt(2)", "15% of 80") and unit conversions ("5 km in mi",
 * "100 f to c", "3 GiB in MB"). Pure Kotlin, so it runs on every keystroke without a thread of its own.
 *
 * A query is only a calculation when it reads whole as one and does something: "42", "pi" or "7zip" aren't, so the bar
 * keeps them for the apps and the agent.
 */
object Calculator {
    /** Significant digits a result keeps: enough for 0.1 + 0.2 to be 0.3 and sqrt(2) to be useful. */
    private const val DIGITS = 10

    /** Words between a quantity and the unit to convert it to, in the launcher's languages. */
    private val INTO = setOf("in", "to", "as", "into", "=", "->", "→", "en", "a", "em", "para", "ke", "dalam")

    /** The calculation [query] asks for, or null when it isn't one. */
    fun evaluate(query: String): CalcResult? {
        val q = query.trim()
        if (q.isEmpty() || q.length > 200 || '\n' in q) return null
        convert(q)?.let { return it }
        val parsed = runCatching { Parser(q).parseAll() }.getOrNull() ?: return null
        if (parsed.ops == 0) return null
        val text = format(parsed.value) ?: return null
        return CalcResult(text, text, q)
    }

    /** "5 km in mi": a quantity with its unit, a word from [INTO], and the unit it's wanted in. */
    private fun convert(q: String): CalcResult? {
        val words = q.split(Regex("\\s+"))
        // From the right: in "5 in in cm" the last "in" is the word, the first the inches.
        for (i in words.size - 2 downTo 1) {
            if (words[i].lowercase() !in INTO) continue
            val to = Units.find(words.subList(i + 1, words.size).joinToString(" ")) ?: continue
            val (amount, from) = quantity(words.subList(0, i).joinToString(" ")) ?: continue
            if (from.dimension != to.dimension) continue
            val converted = Units.convert(amount, from, to)
            val value = format(converted) ?: continue
            return CalcResult(value, "$value ${to.symbol}", q)
        }
        return null
    }

    /** "5 km", "5km", "2*3 ft": an amount (any arithmetic) followed by a unit. */
    private fun quantity(s: String): Pair<Double, MeasureUnit>? {
        val text = s.trim()
        // The longest unit name the text ends with; what's before it is the amount.
        for (cut in text.indices) {
            val unitPart = text.substring(cut).trim()
            if (unitPart.isEmpty() || (cut > 0 && !boundary(text, cut))) continue
            val unit = Units.find(unitPart) ?: continue
            val amountPart = text.substring(0, cut).trim()
            if (amountPart.isEmpty()) return null
            val amount = runCatching { Parser(amountPart).parseAll().value }.getOrNull() ?: return null
            return amount to unit
        }
        return null
    }

    /** Where a unit may start: after a space or a digit, so "5km" splits but "5 skm" doesn't find "km". */
    private fun boundary(
        text: String,
        at: Int,
    ): Boolean {
        val before = text[at - 1]
        return before.isWhitespace() || before.isDigit() || before == ')' || before == '.'
    }

    /** [v] as the bar shows it: [DIGITS] significant digits, no trailing zeros, exponents only far from 1. */
    fun format(v: Double): String? {
        if (v.isNaN() || v.isInfinite()) return null
        if (v == 0.0) return "0"
        val rounded = BigDecimal(v).round(MathContext(DIGITS)).stripTrailingZeros()
        val magnitude = abs(rounded.toDouble())
        if (magnitude >= 1e15 || magnitude < 1e-9) {
            val exp = floor(kotlin.math.log10(magnitude)).toInt()
            val mantissa = BigDecimal(v / 10.0.pow(exp)).round(MathContext(DIGITS)).stripTrailingZeros().toPlainString()
            return "${mantissa}e$exp"
        }
        return rounded.toPlainString()
    }
}

/** A value and how many operations made it: a bare number or constant made none, and isn't worth showing. */
private class Parsed(
    val value: Double,
    val ops: Int,
)

/** The tokens the calculator reads. */
private sealed class Tok {
    data class Num(
        val v: Double,
    ) : Tok()

    data class Word(
        val w: String,
    ) : Tok()

    data class Op(
        val c: String,
    ) : Tok()
}

/**
 * Recursive descent over [text]: `+ -` below `* / mod` and implicit products ("2pi", "3(4+5)"), below unary minus,
 * below `^` (right to left), below postfix `!` and `%`. `x% of y` is a share of y, and `a + b%` adds b percent of a,
 * as a pocket calculator does.
 */
private class Parser(
    text: String,
) {
    private val toks = tokenize(text)
    private var at = 0
    private var ops = 0

    fun parseAll(): Parsed {
        val v = expr()
        if (at != toks.size) throw IllegalArgumentException("unexpected ${toks[at]}")
        return Parsed(v.v, ops)
    }

    /** A value, and whether it's a bare percentage still: `a + b%` treats those as a share of a. */
    private class V(
        val v: Double,
        val percent: Boolean = false,
    )

    private fun peek() = toks.getOrNull(at)

    private fun isOp(vararg c: String) = (peek() as? Tok.Op)?.c in c

    private fun isWord(vararg w: String) = (peek() as? Tok.Word)?.w?.lowercase() in w

    private fun expr(): V {
        var left = term()
        while (isOp("+", "-")) {
            val minus = (toks[at++] as Tok.Op).c == "-"
            val right = term()
            ops++
            val r = if (right.percent) left.v * right.v else right.v
            left = V(if (minus) left.v - r else left.v + r)
        }
        return left
    }

    private fun term(): V {
        var left = unary()
        while (true) {
            left =
                when {
                    isOp("*", "/") || isWord("mod") || (isOp("%") && startsOperand(at + 1)) -> {
                        val op = toks[at++].let { (it as? Tok.Op)?.c ?: "mod" }
                        val right = unary()
                        ops++
                        when (op) {
                            "*" -> V(left.v * right.v)
                            "/" -> V(left.v / right.v)
                            else -> V(left.v - right.v * floor(left.v / right.v))
                        }
                    }
                    // "2pi", "3(4+5)", "2 sqrt 2": a number next to what follows multiplies it.
                    startsImplicit(at) -> {
                        val right = unary()
                        ops++
                        V(left.v * right.v)
                    }
                    else -> return left
                }
        }
    }

    private fun unary(): V {
        if (isOp("-", "+")) {
            val minus = (toks[at++] as Tok.Op).c == "-"
            val v = unary()
            return if (minus) V(-v.v, v.percent) else v
        }
        return power()
    }

    private fun power(): V {
        val base = postfix()
        if (isOp("^")) {
            at++
            val exp = unary()
            ops++
            return V(base.v.pow(exp.v))
        }
        return base
    }

    private fun postfix(): V {
        var v = V(primary())
        while (true) {
            when {
                isOp("!") -> {
                    at++
                    ops++
                    v = V(factorial(v.v))
                }
                // A percent sign followed by what could be its right side is modulo, in term().
                isOp("%") && !startsOperand(at + 1) -> {
                    at++
                    ops++
                    v = V(v.v / 100, percent = true)
                    // "15% of 80", "15% de 80", "15% dari 80".
                    if (isWord("of", "de", "dari")) {
                        at++
                        v = V(v.v * unary().v)
                    }
                }
                else -> return v
            }
        }
    }

    private fun primary(): Double {
        when (val t = peek() ?: throw IllegalArgumentException("ends too soon")) {
            is Tok.Num -> {
                at++
                return t.v
            }
            is Tok.Op ->
                when (t.c) {
                    "(" -> {
                        at++
                        val v = expr().v
                        if (!isOp(")")) throw IllegalArgumentException("missing )")
                        at++
                        return v
                    }
                    "√" -> {
                        at++
                        ops++
                        return kotlin.math.sqrt(postfix().v)
                    }
                    else -> throw IllegalArgumentException("unexpected ${t.c}")
                }
            is Tok.Word -> {
                at++
                val w = t.w.lowercase()
                CONSTANTS[w]?.let { return it }
                val f = FUNCTIONS[w] ?: throw IllegalArgumentException("unknown $w")
                ops++
                // "sqrt(2)" or "sqrt 2"; the argument binds as tightly as a power, so "sqrt 2 * 3" is sqrt(2) * 3.
                return f(power().v)
            }
        }
    }

    /** Whether the token at [i] can begin an operand: what makes `%` modulo rather than percent. */
    private fun startsOperand(i: Int): Boolean =
        when (val t = toks.getOrNull(i)) {
            is Tok.Num -> true
            is Tok.Op -> t.c == "(" || t.c == "√"
            is Tok.Word -> t.w.lowercase().let { it in CONSTANTS || it in FUNCTIONS }
            null -> false
        }

    /** Whether the token at [i] starts an implicit product: not a bare number after a number, "2 3" means nothing. */
    private fun startsImplicit(i: Int): Boolean =
        when (val t = toks.getOrNull(i)) {
            is Tok.Op -> t.c == "(" || t.c == "√"
            is Tok.Word -> t.w.lowercase().let { it in CONSTANTS || it in FUNCTIONS }
            else -> false
        }

    private fun factorial(n: Double): Double {
        if (n < 0 || n != floor(n) || n > 170) throw IllegalArgumentException("no factorial of $n")
        var r = 1.0
        for (k in 2..n.toInt()) r *= k
        return r
    }

    companion object {
        val CONSTANTS = mapOf("pi" to Math.PI, "π" to Math.PI, "e" to Math.E, "tau" to 2 * Math.PI, "τ" to 2 * Math.PI)

        val FUNCTIONS: Map<String, (Double) -> Double> =
            mapOf(
                "sqrt" to { x -> kotlin.math.sqrt(x) },
                "cbrt" to { x -> kotlin.math.cbrt(x) },
                "abs" to { x -> abs(x) },
                "sin" to { x -> kotlin.math.sin(x) },
                "cos" to { x -> kotlin.math.cos(x) },
                "tan" to { x -> kotlin.math.tan(x) },
                "asin" to { x -> kotlin.math.asin(x) },
                "acos" to { x -> kotlin.math.acos(x) },
                "atan" to { x -> kotlin.math.atan(x) },
                "ln" to { x -> ln(x) },
                "log" to { x -> kotlin.math.log10(x) },
                "log2" to { x -> kotlin.math.log2(x) },
                "exp" to { x -> kotlin.math.exp(x) },
                "floor" to { x -> floor(x) },
                "ceil" to { x -> kotlin.math.ceil(x) },
                "round" to { x -> x.roundToLong().toDouble() },
            )

        /** Numbers, words, and operators; "×", "÷", "−" and "**" read as their ASCII selves. */
        fun tokenize(s: String): List<Tok> {
            val out = ArrayList<Tok>()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c.isWhitespace() -> i++
                    c.isDigit() || (c == '.' && s.getOrNull(i + 1)?.isDigit() == true) -> {
                        val (v, end) = number(s, i)
                        out += Tok.Num(v)
                        i = end
                    }
                    // "3x4", "3 x 4": an x between numbers multiplies.
                    c == 'x' && (out.lastOrNull() is Tok.Num || out.lastOrNull() == Tok.Op(")")) && s.getOrNull(i + 1)?.let { it.isWhitespace() || it.isDigit() || it == '(' } == true -> {
                        out += Tok.Op("*")
                        i++
                    }
                    c.isLetter() -> {
                        var j = i
                        while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_')) j++
                        out += Tok.Word(s.substring(i, j))
                        i = j
                    }
                    s.startsWith("**", i) -> {
                        out += Tok.Op("^")
                        i += 2
                    }
                    else -> {
                        val op =
                            when (c) {
                                '+', '-', '*', '/', '^', '%', '(', ')', '!', '√' -> c.toString()
                                '×', '·' -> "*"
                                '÷' -> "/"
                                '−', '–' -> "-"
                                else -> throw IllegalArgumentException("unexpected $c")
                            }
                        out += Tok.Op(op)
                        i++
                    }
                }
            }
            return out
        }

        /**
         * A number from [start]: digits, a decimal point, an exponent ("1e3"). A comma with one or two digits after it
         * is a decimal comma ("3,5"), with three a thousands separator ("1,000"), as the launcher's languages write them.
         */
        private fun number(
            s: String,
            start: Int,
        ): Pair<Double, Int> {
            val b = StringBuilder()
            var i = start
            var point = false
            while (i < s.length) {
                val c = s[i]
                when {
                    c.isDigit() -> b.append(c)
                    c == '.' && !point -> {
                        point = true
                        b.append('.')
                    }
                    c == ',' && s.getOrNull(i + 1)?.isDigit() == true -> {
                        var j = i + 1
                        while (j < s.length && s[j].isDigit()) j++
                        when {
                            j - i - 1 == 3 && !point -> {}
                            !point && j - i - 1 <= 2 -> {
                                point = true
                                b.append('.')
                            }
                            else -> break
                        }
                    }
                    (c == 'e' || c == 'E') && exponentAt(s, i) -> {
                        b.append('e')
                        i++
                        if (s[i] == '+' || s[i] == '-') b.append(s[i++])
                        while (i < s.length && s[i].isDigit()) b.append(s[i++])
                        return b.toString().toDouble() to i
                    }
                    else -> break
                }
                i++
            }
            return b.toString().toDouble() to i
        }

        /** "1e3" and "2e-5" are exponents; "2e" is 2 × e, and "2em" a word after a number. */
        private fun exponentAt(
            s: String,
            i: Int,
        ): Boolean {
            var j = i + 1
            if (s.getOrNull(j) == '+' || s.getOrNull(j) == '-') j++
            if (s.getOrNull(j)?.isDigit() != true) return false
            while (j < s.length && s[j].isDigit()) j++
            return j == s.length || !s[j].isLetter()
        }
    }
}
