package dev.omalauncher.pp.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalculatorTest {
    private fun value(q: String) = Calculator.evaluate(q)?.value

    private fun text(q: String) = Calculator.evaluate(q)?.text

    @Test fun arithmetic() {
        assertEquals("84", value("12*(3+4)"))
        assertEquals("1024", value("2^10"))
        assertEquals("1024", value("2**10"))
        assertEquals("1.414213562", value("sqrt(2)"))
        assertEquals("1.414213562", value("√2"))
        assertEquals("7", value("1 + 2 * 3"))
        assertEquals("9", value("(1 + 2) * 3"))
        assertEquals("2.5", value("10 / 4"))
        assertEquals("0.3", value("0.1 + 0.2"))
        assertEquals("-1", value("2 - 3"))
        assertEquals("12", value("3 × 4"))
        assertEquals("12", value("3x4"))
        assertEquals("12", value("3 x 4"))
        assertEquals("3", value("12 ÷ 4"))
        assertEquals("-3", value("−1 − 2"))
    }

    @Test fun precedenceAndAssociativity() {
        // Right to left, and above unary minus, as in maths and Python.
        assertEquals("512", value("2^3^2"))
        assertEquals("-4", value("-2^2"))
        assertEquals("0.25", value("2^-2"))
        assertEquals("1", value("10 - 5 - 4"))
        assertEquals("1", value("8 / 4 / 2"))
    }

    @Test fun percentages() {
        assertEquals("12", value("15% of 80"))
        assertEquals("12", value("15 % of 80"))
        assertEquals("12", value("15% de 80"))
        assertEquals("92", value("80 + 15%"))
        assertEquals("68", value("80 - 15%"))
        assertEquals("30", value("200 * 15%"))
        assertEquals("0.5", value("50%"))
        // Followed by a number, % is the remainder.
        assertEquals("1", value("10 % 3"))
        assertEquals("1", value("10 mod 3"))
    }

    @Test fun functionsConstantsAndImplicitProducts() {
        assertEquals("6.283185307", value("2pi"))
        assertEquals("6.283185307", value("2π"))
        assertEquals("14", value("2(3+4)"))
        assertEquals("21", value("(1+2)(3+4)"))
        assertEquals("120", value("5!"))
        assertEquals("3", value("log(1000)"))
        assertEquals("1", value("ln(e)"))
        assertEquals("0", value("sin(0)"))
        assertEquals("5", value("abs(-5)"))
        assertEquals("4.242640687", value("sqrt 2 * 3"))
        assertEquals("3", value("round(2.6)"))
        assertEquals("10", value("log2(1024)"))
    }

    @Test fun numbers() {
        assertEquals("2000", value("1e3 * 2"))
        assertEquals("1001", value("1,000 + 1"))
        // A comma with one or two digits after it is a decimal comma.
        assertEquals("7", value("3,5 * 2"))
        assertEquals("1", value(".5 + .5"))
        assertEquals("1.23456789e20", value("123456789 * 1e12"))
        assertEquals("1e-12", value("1 / 1e12"))
    }

    @Test fun notCalculations() {
        // Nothing done: a number or a constant alone stays a query for apps and the agent.
        assertNull(value("42"))
        assertNull(value("-5"))
        assertNull(value("pi"))
        assertNull(value("e"))
        assertNull(value("(5)"))
        // Names with digits, sentences, times.
        assertNull(value("7zip"))
        assertNull(value("1password"))
        assertNull(value("2048"))
        assertNull(value("4 pics 1 word"))
        assertNull(value("how do I add 2 and 2"))
        assertNull(value("12:30"))
        assertNull(value("firefox"))
        assertNull(value(""))
        assertNull(value("2 3"))
        assertNull(value("1,5,3"))
        // Nothing to show: no number comes out.
        assertNull(value("1/0"))
        assertNull(value("sqrt(-1)"))
        assertNull(value("(1+2"))
        assertNull(value("2 +"))
    }

    @Test fun conversions() {
        assertEquals("3.106855961 mi", text("5 km in mi"))
        assertEquals("3.106855961", value("5 km in mi"))
        assertEquals("37.77777778 °C", text("100 f to c"))
        assertEquals("212 °F", text("100 c to f"))
        assertEquals("373.15 K", text("100 °C in K"))
        assertEquals("3221.225472 MB", text("3 GiB in MB"))
        assertEquals("3221.225472 MB", text("3 gib in mb"))
        assertEquals("1024 MiB", text("1 GiB to MiB"))
        assertEquals("8 Mbit", text("1 MB in Mb"))
        assertEquals("12.7 cm", text("5 in in cm"))
        assertEquals("5000 m", text("5km in m"))
        assertEquals("6000 m", text("2*3 km to m"))
        assertEquals("2.204622622 lb", text("1 kg in lb"))
        assertEquals("100 km/h", text("27.7777777778 m/s in km/h"))
        assertEquals("3.785411784 L", text("1 gallon to liters"))
        assertEquals("90 min", text("1.5 h in min"))
        assertEquals("180 °", text("pi rad in deg"))
        // The launcher's other languages' words.
        assertEquals("5000 m", text("5 km a m"))
        assertEquals("5000 m", text("5 km em m"))
        assertEquals("5000 m", text("5 km ke m"))
    }

    @Test fun notConversions() {
        // Units that measure different things, units that aren't, and sentences with "to" in them.
        assertNull(Calculator.evaluate("5 km in kg"))
        assertNull(Calculator.evaluate("5 km in parsecs-ish"))
        assertNull(Calculator.evaluate("how to cook a pizza"))
        assertNull(Calculator.evaluate("km to mi"))
        assertNull(Calculator.evaluate("go to bed"))
    }

    @Test fun format() {
        assertEquals("0", Calculator.format(0.0))
        assertEquals("1.5", Calculator.format(1.5))
        assertEquals("100", Calculator.format(100.0))
        assertEquals("0.3333333333", Calculator.format(1.0 / 3))
        assertEquals("-2", Calculator.format(-2.0))
        assertNull(Calculator.format(Double.NaN))
        assertNull(Calculator.format(Double.POSITIVE_INFINITY))
    }
}
