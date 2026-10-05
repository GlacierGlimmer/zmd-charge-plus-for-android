package com.glacierglimmer.endfieldchargeplus.core.template

import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import com.glacierglimmer.endfieldchargeplus.core.model.UnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [TemplateEngine]: token grammar, expression tokens, render order, alias
 * canonicalisation and the `--` sentinel.
 */
class TemplateEngineTest {

    private val values = mapOf(
        "cpu.usage" to MetricValue.Number(42.7),
        "cpu.frequency_ghz" to MetricValue.Number(3.456),
        "memory.used_bytes" to MetricValue.Number(8589934592.0),
        "state" to MetricValue.Text("Charging"),
        "probe.latency_ms" to MetricValue.Number(20.0),
    )

    private val resolver: (String) -> MetricValue? = { values[it] }

    @Test
    fun `variable tokens use the format pipeline`() {
        assertEquals("43", TemplateEngine.render("{cpu.usage|0}", resolver))
        assertEquals("42.7", TemplateEngine.render("{cpu.usage}", resolver))
        assertEquals("3.46", TemplateEngine.render("{cpu.frequency_ghz|0.00}", resolver))
        assertEquals("8.0", TemplateEngine.render("{memory.used_bytes|gb:1}", resolver))
        assertEquals("8.0 GB", TemplateEngine.render("{memory.used_bytes|gb:1} GB", resolver))
        assertEquals("Charging", TemplateEngine.render("{state}", resolver))
        assertEquals("CPU 43%", TemplateEngine.render("CPU {cpu.usage|0}%", resolver))
    }

    @Test
    fun `an unavailable value renders the sentinel never zero`() {
        assertEquals("--", TemplateEngine.render("{missing.key}", resolver))
        assertEquals("--", TemplateEngine.render("{missing.key|0}", resolver))
        assertEquals("--%", TemplateEngine.render("{probe.loss_percent|0}%", resolver))
        assertEquals("Loss --", TemplateEngine.render("Loss {probe.loss_percent|0}", resolver))
        assertEquals("--", TemplateEngine.render("{= cpu.usage}", { null }))
        assertEquals("--", TemplateEngine.render("{= 1 +}", resolver))
        assertEquals(
            "--",
            TemplateEngine.render("{cpu.usage}", { MetricValue.Unavailable(UnavailableReason.NO_DATA) }),
        )
        assertEquals("N/A", TemplateEngine.render("{missing.key}", resolver, unavailableText = "N/A"))
    }

    @Test
    fun `expression tokens are evaluated before variable tokens`() {
        assertEquals("HOT", TemplateEngine.render("{= cpu.usage > 40 ? 'HOT' : 'OK'}", resolver))
        assertEquals("OK", TemplateEngine.render("{= cpu.usage > 50 ? 'HOT' : 'OK'}", resolver))
        assertEquals("43.7", TemplateEngine.render("{= cpu.usage + 1}", resolver))
        assertEquals("44", TemplateEngine.render("{= cpu.usage + 1 | 0}", resolver))
        assertEquals("0.5", TemplateEngine.render("{= memory.used_bytes / 17179869184}", resolver))
        // The desktop engine substitutes expression output first and then rescans the result for
        // {key} tokens, so an expression that returns a template is substituted twice.
        val withTemplateText = { name: String ->
            when (name) {
                "tpl" -> MetricValue.Text("{cpu.usage|0}")
                "cpu.usage" -> MetricValue.Number(42.7)
                else -> null
            }
        }
        assertEquals("43", TemplateEngine.render("{= tpl}", withTemplateText))
    }

    @Test
    fun `expressions that cannot be evaluated render the sentinel`() {
        assertEquals("--", TemplateEngine.render("{= min()}", resolver))
        assertEquals("--", TemplateEngine.render("{= cpu.usage / 0}", resolver))
        assertEquals("--", TemplateEngine.render("{= unknown.fn(1)}", resolver))
        assertEquals("prefix--suffix", TemplateEngine.render("prefix{= 1 +}suffix", resolver))
    }

    @Test
    fun `legacy aliases resolve to the canonical key`() {
        assertEquals("20", TemplateEngine.render("{ping.latency_ms|0}", resolver))
        assertEquals("20", TemplateEngine.render("{probe.latency_ms|0}", resolver))
    }

    @Test
    fun `plain text and malformed tokens are copied literally`() {
        assertEquals("", TemplateEngine.render("", resolver))
        assertEquals("/// SYSTEM MONITOR", TemplateEngine.render("/// SYSTEM MONITOR", resolver))
        // There is no escape sequence in the desktop engine.
        assertEquals("{43}", TemplateEngine.render("{{cpu.usage|0}}", resolver))
        // A token that does not match the grammar stays untouched.
        assertEquals("{cpu usage}", TemplateEngine.render("{cpu usage}", resolver))
        assertEquals("{cpu.usage|}", TemplateEngine.render("{cpu.usage|}", resolver))
        assertEquals("}", TemplateEngine.render("}", resolver))
    }

    @Test
    fun `extractKeys covers key tokens and expression tokens`() {
        assertEquals(setOf("cpu.usage"), TemplateEngine.extractKeys("{cpu.usage|0}"))
        assertEquals(setOf("a", "b"), TemplateEngine.extractKeys("{a} and {= b + 1|0}"))
        assertEquals(setOf("probe.latency_ms"), TemplateEngine.extractKeys("{ping.latency_ms|0}"))
        assertEquals(emptySet<String>(), TemplateEngine.extractKeys(null, ""))
        assertEquals(setOf("cpu.usage"), TemplateEngine.extractKeys("plain {cpu.usage} {= 1 + 2}"))
    }

    @Test
    fun `extractExpressionKeys understands bare keys and expressions`() {
        assertEquals(setOf("cpu.usage"), TemplateEngine.extractExpressionKeys("cpu.usage"))
        assertEquals(setOf("cpu.usage"), TemplateEngine.extractExpressionKeys("= cpu.usage"))
        assertEquals(setOf("cpu.usage"), TemplateEngine.extractExpressionKeys("{= cpu.usage | 0}"))
        assertEquals(setOf("a", "b"), TemplateEngine.extractExpressionKeys("= (a + b) / 2"))
        assertEquals(emptySet<String>(), TemplateEngine.extractExpressionKeys(null))
        assertEquals(setOf("probe.latency_ms"), TemplateEngine.extractExpressionKeys("ping.latency_ms"))
    }

    @Test
    fun `evaluateNumber bridges progress variables`() {
        assertEquals(42.7, TemplateEngine.evaluateNumber("cpu.usage", resolver, 0.0), 0.0)
        assertEquals(43.7, TemplateEngine.evaluateNumber("= cpu.usage + 1", resolver, 0.0), 1e-9)
        assertEquals(43.7, TemplateEngine.evaluateNumber("{= cpu.usage + 1 }", resolver, 0.0), 1e-9)
        assertEquals(21.35, TemplateEngine.evaluateNumber("{= cpu.usage / 2 | 0.00}", resolver, 0.0), 1e-9)
        assertEquals(7.0, TemplateEngine.evaluateNumber("missing.key", resolver, 7.0), 0.0)
        assertEquals(7.0, TemplateEngine.evaluateNumber("= boom(", resolver, 7.0), 0.0)
        assertEquals(7.0, TemplateEngine.evaluateNumber("", resolver, 7.0), 0.0)
        assertEquals(7.0, TemplateEngine.evaluateNumber(null, resolver, 7.0), 0.0)
    }

    @Test
    fun `splitExpressionAndFormat honours strings parentheses and the or operator`() {
        assertEquals("a + b" to "0", TemplateEngine.splitExpressionAndFormat("a + b | 0"))
        assertEquals("a || b" to "", TemplateEngine.splitExpressionAndFormat("a || b"))
        assertEquals("'a|b'" to "0", TemplateEngine.splitExpressionAndFormat("'a|b' | 0"))
        assertEquals("max(a, b)" to "gb:1", TemplateEngine.splitExpressionAndFormat("max(a, b) | gb:1"))
        assertEquals("if(a || b, 1, 2)" to "0", TemplateEngine.splitExpressionAndFormat("if(a || b, 1, 2)|0"))
        assertEquals("a" to "", TemplateEngine.splitExpressionAndFormat("a"))
    }

    @Test
    fun `sentinel constant is the desktop value`() {
        assertEquals("--", TemplateEngine.UNKNOWN_SENTINEL)
    }

    @Test
    fun `extractKeys returns null safe results`() {
        assertNull(values["unknown"])
        assertTrue(TemplateEngine.extractKeys("{= if(a > b, 'x', 'y')}").containsAll(setOf("a", "b")))
    }
}
