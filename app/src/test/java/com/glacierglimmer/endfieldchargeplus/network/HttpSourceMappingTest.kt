package com.glacierglimmer.endfieldchargeplus.network

import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import com.glacierglimmer.endfieldchargeplus.core.model.UnavailableReason
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure HTTP/JSON source helpers: refresh clamping, exponential backoff, environment
 * expansion and the JSON -> `MetricValue` mapping.
 */
class HttpSourceMappingTest {

    // ---- refresh cadence --------------------------------------------------------------------

    @Test
    fun `refresh cadence is clamped to the desktop 5 second floor`() {
        assertEquals(5_000L, HttpSourceMapping.refreshIntervalMs(0))
        assertEquals(5_000L, HttpSourceMapping.refreshIntervalMs(1))
        assertEquals(5_000L, HttpSourceMapping.refreshIntervalMs(5))
        assertEquals(60_000L, HttpSourceMapping.refreshIntervalMs(60))
    }

    @Test
    fun `refresh cadence is capped at one day`() {
        assertEquals(86_400_000L, HttpSourceMapping.refreshIntervalMs(99_999_999))
    }

    // ---- backoff ----------------------------------------------------------------------------

    @Test
    fun `backoff grows exponentially and stops at ten times the interval`() {
        val base = 60_000L
        assertEquals(60_000L, HttpSourceMapping.backoffDelayMs(base, 0))
        assertEquals(60_000L, HttpSourceMapping.backoffDelayMs(base, 1))
        assertEquals(120_000L, HttpSourceMapping.backoffDelayMs(base, 2))
        assertEquals(240_000L, HttpSourceMapping.backoffDelayMs(base, 3))
        assertEquals(480_000L, HttpSourceMapping.backoffDelayMs(base, 4))
        assertEquals(600_000L, HttpSourceMapping.backoffDelayMs(base, 5))
        assertEquals(600_000L, HttpSourceMapping.backoffDelayMs(base, 99))
    }

    @Test
    fun `backoff never returns a shorter delay than the source interval`() {
        val base = 5_000L
        for (failures in 0..10) {
            assertTrue(HttpSourceMapping.backoffDelayMs(base, failures) >= base)
        }
        assertTrue(HttpSourceMapping.backoffDelayMs(0L, 0) >= 1_000L)
    }

    // ---- environment substitution -----------------------------------------------------------

    @Test
    fun `header placeholders support both spellings and expand missing to empty`() {
        val environment = mapOf("ECP_TOKEN" to "s3cret")
        val resolve: (String) -> String? = { environment[it] }

        assertEquals("Bearer s3cret", HttpSourceMapping.expandEnvironment("Bearer \${ECP_TOKEN}", resolve))
        assertEquals("Bearer s3cret", HttpSourceMapping.expandEnvironment("Bearer \${env:ECP_TOKEN}", resolve))
        assertEquals("x", HttpSourceMapping.expandEnvironment("x\${MISSING}", resolve))
        assertEquals("plain", HttpSourceMapping.expandEnvironment("plain", resolve))
    }

    // ---- JSON to metric value ---------------------------------------------------------------

    @Test
    fun `numbers stay numbers and non-numeric values become text`() {
        assertNumber(12.5, HttpSourceMapping.toMetricValue(JsonPrimitive(12.5)))
        assertNumber(42.0, HttpSourceMapping.toMetricValue(JsonPrimitive(42)))
        assertNumber(12.5, HttpSourceMapping.toMetricValue(JsonPrimitive("12.50")))
        assertText("offline", HttpSourceMapping.toMetricValue(JsonPrimitive("offline")))
        assertText("true", HttpSourceMapping.toMetricValue(JsonPrimitive(true)))
        assertText("false", HttpSourceMapping.toMetricValue(JsonPrimitive(false)))
    }

    @Test
    fun `composite values are published as raw text`() {
        assertText(
            """{"a":1}""",
            HttpSourceMapping.toMetricValue(buildJsonObject { put("a", 1) }),
        )
        assertText("[1,2]", HttpSourceMapping.toMetricValue(buildJsonArray { add(JsonPrimitive(1)); add(JsonPrimitive(2)) }))
        assertText("{}", HttpSourceMapping.toMetricValue(JsonObject(emptyMap())))
        assertText("[]", HttpSourceMapping.toMetricValue(JsonArray(emptyList())))
    }

    @Test
    fun `missing path and json null are unavailable rather than fabricated`() {
        val missing = HttpSourceMapping.toMetricValue(null)
        assertTrue(missing is MetricValue.Unavailable)
        assertEquals(UnavailableReason.NO_DATA, (missing as MetricValue.Unavailable).reason)

        val explicitNull = HttpSourceMapping.toMetricValue(JsonNull)
        assertTrue(explicitNull is MetricValue.Unavailable)
        assertEquals(UnavailableReason.NO_DATA, (explicitNull as MetricValue.Unavailable).reason)
    }

    @Test
    fun `non-finite numeric text is treated as text not as infinity`() {
        assertText("NaN", HttpSourceMapping.toMetricValue(JsonPrimitive("NaN")))
        assertText("1e99999", HttpSourceMapping.toMetricValue(JsonPrimitive("1e99999")))
    }

    private fun assertNumber(expected: Double, value: MetricValue) {
        assertTrue("expected a number but was $value", value is MetricValue.Number)
        assertEquals(expected, (value as MetricValue.Number).value, 1e-9)
    }

    private fun assertText(expected: String, value: MetricValue) {
        assertTrue("expected text but was $value", value is MetricValue.Text)
        assertEquals(expected, (value as MetricValue.Text).value)
    }
}
