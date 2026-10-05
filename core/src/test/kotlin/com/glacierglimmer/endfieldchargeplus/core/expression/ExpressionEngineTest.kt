package com.glacierglimmer.endfieldchargeplus.core.expression

import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural tests for [ExpressionEngine], pinned against `Customization/ExpressionEngine.cs`.
 *
 * The expectations are the desktop results (verified against the C# semantics described in
 * docs/audit/01-windows-core.md §3), plus the documented Android rule that an unavailable variable
 * propagates instead of being coerced to 0.
 */
class ExpressionEngineTest {

    private fun resolver(values: Map<String, MetricValue>): (String) -> MetricValue? = { values[it] }

    private fun number(expression: String, values: Map<String, MetricValue> = emptyMap()): Double {
        val result = ExpressionEngine.evaluate(expression, resolver(values))
        assertTrue("expected a Value but was $result", result is ExpressionResult.Value)
        return (result as ExpressionResult.Value).number
    }

    private fun text(expression: String, values: Map<String, MetricValue> = emptyMap()): String {
        val result = ExpressionEngine.evaluate(expression, resolver(values))
        assertTrue("expected Text but was $result", result is ExpressionResult.Text)
        return (result as ExpressionResult.Text).text
    }

    private fun failure(expression: String, values: Map<String, MetricValue> = emptyMap()): String {
        val result = ExpressionEngine.evaluate(expression, resolver(values))
        assertTrue("expected Failure but was $result", result is ExpressionResult.Failure)
        return (result as ExpressionResult.Failure).message
    }

    @Test
    fun `arithmetic follows the desktop precedence chain`() {
        assertEquals(14.0, number("2 + 3 * 4"), 0.0)
        assertEquals(20.0, number("(2 + 3) * 4"), 0.0)
        assertEquals(5.0, number("1 + 2 * 3 - 4 / 2"), 0.0)
        assertEquals(2.5, number("10 / 4"), 0.0)
        assertEquals(1.0, number("10 % 3"), 0.0)
        assertEquals(0.5, number("1 / 2"), 0.0)
        assertEquals(7.0, number("3 + 4"), 0.0)
        assertEquals(1.0E-7, number("1e-7"), 0.0)
        assertEquals(1500.0, number("1.5e3"), 0.0)
        assertEquals(0.5, number(".5"), 0.0)
    }

    @Test
    fun `power is Math Pow and right associative`() {
        assertEquals(8.0, number("2 ^ 3"), 0.0)
        assertEquals(512.0, number("2 ^ 3 ^ 2"), 0.0)
        // The desktop engine turns -2 into a number before applying '^', so (-2)^2 = 4.
        assertEquals(4.0, number("-2 ^ 2"), 0.0)
        assertEquals(-8.0, number("-(2 ^ 3)"), 0.0)
    }

    @Test
    fun `unary minus plus and not`() {
        assertEquals(-5.0, number("-5"), 0.0)
        assertEquals(5.0, number("+5"), 0.0)
        assertEquals(-5.0, number("-(2 + 3)"), 0.0)
        assertEquals(5.0, number("--5"), 0.0)
        assertEquals("true", text("!false"))
        assertEquals("true", text("not false"))
        assertEquals("false", text("!1"))
        assertEquals("true", text("!'0'"))
    }

    @Test
    fun `comparisons use numeric or ignore case text ordering`() {
        assertEquals("true", text("5 > 3"))
        assertEquals("false", text("3 > 5"))
        assertEquals("true", text("5 >= 5"))
        assertEquals("true", text("3 <= 4"))
        assertEquals("true", text("2 == 2.0"))
        assertEquals("true", text("'ABC' == 'abc'"))
        assertEquals("false", text("'a' == 'b'"))
        assertEquals("true", text("1 != 2"))
        // Case-insensitive ordinal comparison: 'b' sorts after 'A'.
        assertEquals("true", text("'b' > 'A'"))
        assertEquals("true", text("null == null"))
        assertEquals("false", text("null == 0"))
    }

    @Test
    fun `logical operators`() {
        assertEquals("true", text("true && true"))
        assertEquals("false", text("true && false"))
        assertEquals("true", text("false || true"))
        assertEquals("false", text("false || false"))
        assertEquals("true", text("true and true"))
        assertEquals("false", text("true and false"))
        assertEquals("true", text("false or true"))
        assertEquals("true", text("1 && 1"))
        assertEquals("false", text("0 || 0"))
        assertEquals("true", text("5 && 'x'"))
    }

    @Test
    fun `ternary is eager and right associative`() {
        assertEquals(1.0, number("true ? 1 : 2"), 0.0)
        assertEquals(2.0, number("false ? 1 : 2"), 0.0)
        assertEquals(1.0, number("true ? 1 : false ? 2 : 3"), 0.0)
        assertEquals(2.0, number("false ? 1 : true ? 2 : 3"), 0.0)
        assertEquals(3.0, number("false ? 1 : false ? 2 : 3"), 0.0)
        assertEquals("a", text("1 > 0 ? 'a' : 'b'"))
    }

    @Test
    fun `coalesce replaces null and empty strings`() {
        assertEquals(5.0, number("null ?? 5"), 0.0)
        assertEquals(7.0, number("'' ?? 7"), 0.0)
        assertEquals(0.0, number("0 ?? 3"), 0.0)
        assertEquals(9.0, number("null ?? null ?? 9"), 0.0)
        assertEquals("x", text("'x' ?? 'y'"))
    }

    @Test
    fun `string literals and concatenation`() {
        assertEquals("it's", text("'it\\'s'"))
        assertEquals("a\nb", text("\"a\\nb\""))
        assertEquals("a\tb", text("'a\\tb'"))
        assertEquals("back\\slash", text("'back\\\\slash'"))
        assertEquals("a1", text("'a' + 1"))
        assertEquals("1.5x", text("1.5 + 'x'"))
        assertEquals("ab", text("'a' + 'b'"))
    }

    @Test
    fun `if min max avg sum clamp round`() {
        assertEquals(1.0, number("if(true, 1, 2)"), 0.0)
        assertEquals(2.0, number("if(false, 1, 2)"), 0.0)
        assertEquals("yes", text("if('x' == 'X', 'yes', 'no')"))
        assertEquals(1.0, number("min(3, 1, 2)"), 0.0)
        assertEquals(3.0, number("max(3, 1, 2)"), 0.0)
        assertEquals(2.0, number("avg(1, 2, 3)"), 0.0)
        assertEquals(6.0, number("sum(1, 2, 3)"), 0.0)
        assertEquals(0.0, number("sum()"), 0.0)
        assertEquals(3.0, number("clamp(5, 1, 3)"), 0.0)
        assertEquals(1.0, number("clamp(0, 1, 3)"), 0.0)
        assertEquals(2.0, number("clamp(2, 1, 3)"), 0.0)
        // round() is MidpointRounding.AwayFromZero on the exact binary value.
        assertEquals(3.0, number("round(2.5)"), 0.0)
        assertEquals(-3.0, number("round(-2.5)"), 0.0)
        assertEquals(1.0, number("round(1.005, 2)"), 0.0)
        assertEquals(2.68, number("round(2.675, 2)"), 0.0)
        assertEquals(1.3, number("round(1.25, 1)"), 0.0)
        assertEquals(2.0, number("round(1.5, 0)"), 0.0)
    }

    @Test
    fun `mathematical functions`() {
        assertEquals(-2.0, number("floor(-1.5)"), 0.0)
        assertEquals(-1.0, number("ceil(-1.5)"), 0.0)
        assertEquals(3.0, number("abs(-3)"), 0.0)
        assertEquals(3.0, number("sqrt(9)"), 0.0)
        assertEquals(0.0, number("sqrt(-4)"), 0.0)
        assertEquals(1024.0, number("pow(2, 10)"), 0.0)
        assertEquals(1.0, number("log(exp(1))"), 0.0000001)
        assertEquals(3.0, number("log10(1000)"), 0.0)
        assertEquals(1.0, number("exp(0)"), 0.0)
        assertEquals(0.0, number("sin(0)"), 0.0)
        assertEquals(1.0, number("cos(0)"), 0.0)
        assertEquals(0.0, number("tan(0)"), 0.0)
        assertEquals(-1.0, number("sign(-5)"), 0.0)
        assertEquals(0.0, number("sign(0)"), 0.0)
        assertEquals("true", text("isnan(log(-1))"))
        assertEquals("false", text("isnan(1)"))
    }

    @Test
    fun `text functions`() {
        assertEquals(3.0, number("len('abc')"), 0.0)
        assertEquals("true", text("contains('Hello', 'ell')"))
        assertEquals("false", text("contains('Hello', 'zzz')"))
        assertEquals("true", text("startswith('Hello', 'he')"))
        assertEquals("true", text("endswith('Hello', 'LO')"))
        assertEquals("AB", text("upper('aB')"))
        assertEquals("ab", text("lower('aB')"))
        assertEquals("a1b", text("concat('a', 1, 'b')"))
        assertEquals("42", text("string(42)"))
        assertEquals(42.0, number("number('42')"), 0.0)
        assertEquals("true", text("bool('yes')"))
        assertEquals("false", text("bool('0')"))
        assertEquals("true", text("bool(2)"))
        assertEquals(12.5, number("percent(25, 200)"), 0.0)
        assertEquals(0.0, number("percent(25, 0)"), 0.0)
        assertEquals("true", text("between(5, 1, 10)"))
        assertEquals("false", text("between(11, 1, 10)"))
    }

    @Test
    fun `variables come from the resolver and aliases are canonicalised`() {
        val values = mapOf(
            "cpu.usage" to MetricValue.Number(42.5),
            "probe.latency_ms" to MetricValue.Number(20.0),
            "battery.status_text" to MetricValue.Text("Charging"),
        )
        assertEquals(42.5, number("cpu.usage", values), 0.0)
        assertEquals(43.5, number("cpu.usage + 1", values), 0.0)
        assertEquals(20.0, number("ping.latency_ms", values), 0.0)
        assertEquals("Charging", text("battery.status_text", values))
        assertEquals("true", text("battery.status_text == 'charging'", values))
    }

    @Test
    fun `an unavailable variable propagates instead of becoming zero`() {
        val values = mapOf("cpu.usage" to MetricValue.NoData)
        assertTrue(ExpressionEngine.evaluate("cpu.usage", resolver(values)) is ExpressionResult.Failure)
        assertTrue(ExpressionEngine.evaluate("cpu.usage + 1", resolver(values)) is ExpressionResult.Failure)
        assertTrue(ExpressionEngine.evaluate("cpu.usage > 50", resolver(values)) is ExpressionResult.Failure)
        // ... but the explicit null handling of the desktop engine still works.
        assertEquals(0.0, number("cpu.usage ?? 0", values), 0.0)
        assertEquals("N/A", text("if(isnull(cpu.usage), 'N/A', 'ok')", values))
        assertEquals("true", text("isnull(cpu.usage)", values))
        assertEquals("true", text("isempty(cpu.usage)", values))
        assertEquals("", text("string(cpu.usage)", values))
        assertEquals("false", text("bool(cpu.usage)", values))
    }

    @Test
    fun `missing variables behave exactly like unavailable ones`() {
        assertTrue(ExpressionEngine.evaluate("nothing.here", resolver(emptyMap())) is ExpressionResult.Failure)
        assertTrue(ExpressionEngine.evaluate("nothing.here * 2", resolver(emptyMap())) is ExpressionResult.Failure)
        assertEquals(3.0, number("nothing.here ?? 3"), 0.0)
        assertEquals("false", text("isnull(1)"))
    }

    @Test
    fun `errors are reported as failures and never throw`() {
        assertTrue(failure("1 +").isNotBlank())
        assertTrue(failure("1 + 2)").isNotBlank())
        assertTrue(failure("").isNotBlank())
        assertTrue(failure("if(1, 2)").contains("3"))
        assertTrue(failure("min()").isNotBlank())
        assertTrue(failure("unsupported(1)").contains("unsupported"))
        assertTrue(failure("1 / 0").contains("0"))
        assertTrue(failure("1 % 0").isNotBlank())
        assertTrue(failure("'abc' - 1").contains("abc"))
        assertTrue(failure("clamp(1, 5, 3)").isNotBlank())
        assertTrue(failure("round(1, 2, 3)").isNotBlank())
        assertTrue(failure("1 1").isNotBlank())
        assertTrue(failure("'unterminated").isNotBlank())
    }

    @Test
    fun `extractVariables skips strings keywords and function names`() {
        assertEquals(setOf("cpu.usage"), ExpressionEngine.extractVariables("if(cpu.usage > 80, '高', 'ok')"))
        assertEquals(setOf("a", "b"), ExpressionEngine.extractVariables("a + b"))
        assertEquals(emptySet<String>(), ExpressionEngine.extractVariables("1 + 2"))
        assertEquals(setOf("y"), ExpressionEngine.extractVariables("'x' + y"))
        assertEquals(setOf("flag"), ExpressionEngine.extractVariables("not flag"))
        assertEquals(setOf("a", "b"), ExpressionEngine.extractVariables("max(a, b)"))
        assertEquals(emptySet<String>(), ExpressionEngine.extractVariables("true"))
        assertEquals(setOf("memory.usage"), ExpressionEngine.extractVariables("memory.usage >= 80 ? 'High' : 'OK'"))
        assertEquals(emptySet<String>(), ExpressionEngine.extractVariables("   "))
    }
}
