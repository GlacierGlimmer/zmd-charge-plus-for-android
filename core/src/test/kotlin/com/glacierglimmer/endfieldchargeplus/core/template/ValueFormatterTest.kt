package com.glacierglimmer.endfieldchargeplus.core.template

import com.glacierglimmer.endfieldchargeplus.core.i18n.Strings
import com.glacierglimmer.endfieldchargeplus.core.i18n.UiLanguage
import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import com.glacierglimmer.endfieldchargeplus.core.model.UnavailableReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Exact-output tests for [ValueFormatter].
 *
 * Every expectation in this file was produced by running the corresponding .NET call
 * (`double.ToString(spec, InvariantCulture)` and the verbatim `HumanBytes`/`Duration` helpers of
 * `TemplateEngine.cs`) with `dotnet` 10 on the audit machine, so a failure here is a real parity
 * regression rather than a preference.
 */
class ValueFormatterTest {

    @After
    fun resetLanguage() {
        Strings.setLanguage(UiLanguage.EN)
    }

    // ---- plain numbers -----------------------------------------------------------------------

    @Test
    fun `empty format uses the dotnet custom pattern 0_##`() {
        assertEquals("0", ValueFormatter.formatNumber(0.0, null))
        assertEquals("1", ValueFormatter.formatNumber(1.0, ""))
        assertEquals("1.5", ValueFormatter.formatNumber(1.5, ""))
        assertEquals("0.13", ValueFormatter.formatNumber(0.125, ""))
        assertEquals("1.01", ValueFormatter.formatNumber(1.005, ""))
        assertEquals("2.68", ValueFormatter.formatNumber(2.675, ""))
        assertEquals("1234567.89", ValueFormatter.formatNumber(1234567.891, null))
        assertEquals("1000000000000000000000", ValueFormatter.formatNumber(1.0E21, null))
        assertEquals("0", ValueFormatter.formatNumber(1.0E-7, null))
        assertEquals("NaN", ValueFormatter.formatNumber(Double.NaN, null))
        assertEquals("Infinity", ValueFormatter.formatNumber(Double.POSITIVE_INFINITY, null))
        assertEquals("-Infinity", ValueFormatter.formatNumber(Double.NEGATIVE_INFINITY, null))
    }

    @Test
    fun `custom numeric patterns round the 15 digit decimal half away from zero`() {
        assertEquals("0", ValueFormatter.formatNumber(0.125, "0"))
        assertEquals("0.1", ValueFormatter.formatNumber(0.125, "0.0"))
        assertEquals("0.13", ValueFormatter.formatNumber(0.125, "0.00"))
        assertEquals("0.125", ValueFormatter.formatNumber(0.125, "0.000"))
        assertEquals("1.01", ValueFormatter.formatNumber(1.005, "0.00"))
        assertEquals("2.68", ValueFormatter.formatNumber(2.675, "0.00"))
        assertEquals("3", ValueFormatter.formatNumber(2.5, "0"))
        assertEquals("-3", ValueFormatter.formatNumber(-2.5, "0"))
        assertEquals("1.25", ValueFormatter.formatNumber(1.25, "0.##"))
        assertEquals("1.2", ValueFormatter.formatNumber(1.20, "0.##"))
        assertEquals("1", ValueFormatter.formatNumber(1.0, "0.##"))
        assertEquals("1,234,567.89", ValueFormatter.formatNumber(1234567.891, "#,##0.00"))
        assertEquals("1,024", ValueFormatter.formatNumber(1024.0, "#,##0"))
        assertEquals("1234567890123460", ValueFormatter.formatNumber(1234567890123456.0, "0"))
        assertEquals("1000000000000000000000.00", ValueFormatter.formatNumber(1.0E21, "0.00"))
        assertEquals("zzz", ValueFormatter.formatNumber(1.0, "zzz"))
        // Custom E patterns keep .NET's minimum of three exponent digits.
        assertEquals("2.50E+000", ValueFormatter.formatNumber(2.5, "0.00E+00"))
        assertEquals("5.00E-001", ValueFormatter.formatNumber(0.5, "0.00E+00"))
        // Section lists: positive;negative;zero.
        assertEquals("-1.5", ValueFormatter.formatNumber(-1.5, "0.0"))
        assertEquals("(1.5)", ValueFormatter.formatNumber(-1.5, "0.0;(0.0)"))
        assertEquals("1.5", ValueFormatter.formatNumber(1.5, "0.0;(0.0);'zero'"))
        assertEquals("zero", ValueFormatter.formatNumber(0.0, "0.0;(0.0);'zero'"))
        assertEquals("NaN", ValueFormatter.formatNumber(Double.NaN, "0.00"))
        assertEquals("Infinity", ValueFormatter.formatNumber(Double.POSITIVE_INFINITY, "0.00"))
    }

    @Test
    fun `standard specifiers round the exact value half to even`() {
        assertEquals("0.12", ValueFormatter.formatNumber(0.125, "F2"))
        assertEquals("1.00", ValueFormatter.formatNumber(1.005, "F2"))
        assertEquals("2.67", ValueFormatter.formatNumber(2.675, "F2"))
        assertEquals("2", ValueFormatter.formatNumber(2.5, "F0"))
        assertEquals("2", ValueFormatter.formatNumber(1.5, "F0"))
        assertEquals("0", ValueFormatter.formatNumber(0.5, "F0"))
        assertEquals("-2", ValueFormatter.formatNumber(-2.5, "F0"))
        assertEquals("1,024.00", ValueFormatter.formatNumber(1024.0, "N2"))
        assertEquals("250.0 %", ValueFormatter.formatNumber(2.5, "P1"))
        assertEquals("12.5 %", ValueFormatter.formatNumber(0.125, "P1"))
        assertEquals("-250.0 %", ValueFormatter.formatNumber(-2.5, "P1"))
        assertEquals("2.50E+000", ValueFormatter.formatNumber(2.5, "E2"))
        assertEquals("5.00E-001", ValueFormatter.formatNumber(0.5, "E2"))
        assertEquals("1E+21", ValueFormatter.formatNumber(1.0E21, "G"))
        assertEquals("1E-07", ValueFormatter.formatNumber(1.0E-7, "G"))
        assertEquals("1234567.891", ValueFormatter.formatNumber(1234567.891, "G"))
        // D/X are not valid for Double; the desktop catch falls back to 0.##.
        assertEquals("1", ValueFormatter.formatNumber(1.0, "D"))
        assertEquals("1", ValueFormatter.formatNumber(1.0, "X"))
    }

    // ---- units -------------------------------------------------------------------------------

    @Test
    fun `binary byte units`() {
        assertEquals("0.0", ValueFormatter.formatNumber(1024.0, "gb"))
        assertEquals("1.0", ValueFormatter.formatNumber(1073741824.0, "gb"))
        assertEquals("1.00", ValueFormatter.formatNumber(1073741824.0, "gb:2"))
        assertEquals("1", ValueFormatter.formatNumber(1048576.0, "mb:0"))
        assertEquals("1.0", ValueFormatter.formatNumber(1024.0, "kb"))
        assertEquals("1.00", ValueFormatter.formatNumber(1099511627776.0, "tb"))
        assertEquals("1.00", ValueFormatter.formatNumber(1099511627776.0, "tb:2"))
        assertEquals("8.0", ValueFormatter.formatNumber(8589934592.0, "gb:1"))
        assertEquals("1.5 KB", ValueFormatter.formatNumber(1536.0, "bytes"))
        assertEquals("1.0 MB", ValueFormatter.formatNumber(1048576.0, "bytes"))
        assertEquals("999.6 B", ValueFormatter.formatNumber(999.6, "bytes"))
        assertEquals("0.0 B", ValueFormatter.formatNumber(0.0, "bytes"))
        assertEquals("-2.0 KB", ValueFormatter.formatNumber(-2048.0, "bytes"))
        assertEquals("909.5 TB", ValueFormatter.formatNumber(1.0E15, "bytes"))
        assertEquals("NaN B", ValueFormatter.formatNumber(Double.NaN, "bytes"))
        assertEquals("Infinity PB", ValueFormatter.formatNumber(Double.POSITIVE_INFINITY, "bytes"))
    }

    @Test
    fun `speed uses binary scaling with a per second suffix`() {
        assertEquals("1.0 KB/s", ValueFormatter.formatNumber(1024.0, "speed"))
        assertEquals("1.5 MB/s", ValueFormatter.formatNumber(1048576.0 * 1.5, "speed"))
        assertEquals("0.0 B/s", ValueFormatter.formatNumber(0.0, "speed"))
    }

    @Test
    fun `mbps and kbps are shadowed by the binary mb and kb prefix checks`() {
        // Faithful quirk: TemplateEngine.BaseFormat tests the "mb"/"kb" prefixes before
        // "mbps"/"kbps", so those decimal branches are unreachable and the binary unit wins.
        assertEquals("1.0", ValueFormatter.formatNumber(1048576.0, "mbps"))
        assertEquals("1.2", ValueFormatter.formatNumber(1258291.2, "mbps:1"))
        assertEquals("1.0", ValueFormatter.formatNumber(1024.0, "kbps"))
        assertEquals("1228.8", ValueFormatter.formatNumber(1258291.2, "kbps"))
    }

    @Test
    fun `percent appends the sign after F rounding`() {
        assertEquals("1000%", ValueFormatter.formatNumber(999.6, "percent"))
        assertEquals("999.6%", ValueFormatter.formatNumber(999.6, "percent:1"))
        assertEquals("0%", ValueFormatter.formatNumber(0.0, "percent"))
        assertEquals("21%", ValueFormatter.formatNumber(20.7, "percent"))
        assertEquals("12.5%", ValueFormatter.formatNumber(12.5, "percent:1"))
    }

    @Test
    fun `durations`() {
        assertEquals("00:00", ValueFormatter.formatNumber(0.0, "duration"))
        assertEquals("00:00", ValueFormatter.formatNumber(-5.0, "duration"))
        assertEquals("00:59", ValueFormatter.formatNumber(59.9, "duration"))
        assertEquals("01:00", ValueFormatter.formatNumber(60.0, "duration"))
        assertEquals("59:59", ValueFormatter.formatNumber(3599.0, "duration"))
        assertEquals("01:01:01", ValueFormatter.formatNumber(3661.0, "duration"))
        assertEquals("23:59:59", ValueFormatter.formatNumber(86399.0, "duration"))
        assertEquals("24:00:00", ValueFormatter.formatNumber(86400.0, "duration"))
        assertEquals("01:01:01", ValueFormatter.formatNumber(3661.0, "duration-long"))
        assertEquals("1d 01:01:01", ValueFormatter.formatNumber(90061.0, "duration-long"))
        assertEquals("1d 00:00:00", ValueFormatter.formatNumber(86400.0, "duration-long"))
        Strings.setLanguage(UiLanguage.ZH_CN)
        assertEquals("1天 01:01:01", ValueFormatter.formatNumber(90061.0, "duration-long"))
    }

    // ---- pipeline tokens ---------------------------------------------------------------------

    @Test
    fun `math tokens`() {
        assertEquals("25", ValueFormatter.formatNumber(20.0, "math:add:5"))
        assertEquals("15", ValueFormatter.formatNumber(20.0, "math:sub:5"))
        assertEquals("40", ValueFormatter.formatNumber(20.0, "math:mul:2"))
        assertEquals("10", ValueFormatter.formatNumber(20.0, "math:div:2"))
        assertEquals("1.3", ValueFormatter.formatNumber(1.25, "math:round:1"))
        assertEquals("3", ValueFormatter.formatNumber(2.5, "math:round"))
        assertEquals("1", ValueFormatter.formatNumber(1.9, "math:floor"))
        assertEquals("2", ValueFormatter.formatNumber(1.1, "math:ceil"))
        assertEquals("5", ValueFormatter.formatNumber(-5.0, "math:abs"))
        assertEquals("NaN", ValueFormatter.formatNumber(5.0, "math:div:0"))
        assertEquals("5", ValueFormatter.formatNumber(5.0, "math:unknown"))
        // Chained tokens are applied left to right.
        assertEquals("25.0", ValueFormatter.formatNumber(20.0, "math:add:5|0.0"))
        assertEquals("0.00", ValueFormatter.formatNumber(1536.0, "math:div:1024|math:div:1024|0.00"))
    }

    @Test
    fun `text tokens`() {
        assertEquals("bcd", ValueFormatter.formatText("abcdef", "sub:1:3"))
        assertEquals("bcdef", ValueFormatter.formatText("abcdef", "sub:1"))
        assertEquals("abcdef", ValueFormatter.formatText("abcdef", "sub:-2"))
        assertEquals("", ValueFormatter.formatText("abcdef", "sub:9"))
        assertEquals("a+b", ValueFormatter.formatText("a-b", "replace:-:+"))
        assertEquals("a:b", ValueFormatter.formatText("a-b", "replace:-::"))
        assertEquals("ABC", ValueFormatter.formatText("abc", "upper"))
        assertEquals("abc", ValueFormatter.formatText("ABC", "lower"))
        assertEquals("ABC", ValueFormatter.formatText("abcdef", "sub:0:3|upper"))
    }

    @Test
    fun `auto token branch is chosen from the variable key`() {
        assertEquals("1.5 KB", ValueFormatter.formatWithKey(MetricValue.Number(1536.0), "auto:1", "memory.used_bytes"))
        assertEquals("1.5 KB/s", ValueFormatter.formatWithKey(MetricValue.Number(1536.0), "auto:1", "network.download_bps"))
        assertEquals("01:01:01", ValueFormatter.formatWithKey(MetricValue.Number(3661.0), "auto:1", "system.uptime_seconds"))
        assertEquals("42.5%", ValueFormatter.formatWithKey(MetricValue.Number(42.5), "auto:1", "cpu.usage"))
        assertEquals("42.5%", ValueFormatter.formatWithKey(MetricValue.Number(42.5), "auto:1", "memory.usage_percent"))
        assertEquals("42.50", ValueFormatter.formatWithKey(MetricValue.Number(42.5), "auto:2", "cpu.temperature"))
        // With no key context the fallback is F{n}.
        assertEquals("1536.0", ValueFormatter.formatNumber(1536.0, "auto:1"))
    }

    @Test
    fun `time token formats dates and relative durations`() {
        assertEquals("15:40", ValueFormatter.formatText("2026-10-05T15:40:35", "time:HH:mm"))
        assertEquals("2026-10-05 15:40:35", ValueFormatter.formatText("2026-10-05T15:40:35", "time:G"))
        assertEquals("05/10/2026", ValueFormatter.formatText("2026-10-05 15:40:35", "time:dd/MM/yyyy"))
        assertEquals("--", ValueFormatter.formatText("not a date", "time:HH:mm"))
        assertEquals("30s", ValueFormatter.formatNumber(30.0, "time:relative"))
        assertEquals("2m", ValueFormatter.formatNumber(120.0, "time:relative"))
        assertEquals("3h", ValueFormatter.formatNumber(3 * 3600.0, "time:relative"))
        Strings.setLanguage(UiLanguage.ZH_CN)
        assertEquals("30秒", ValueFormatter.formatNumber(30.0, "time:relative"))
    }

    // ---- MetricValue handling ----------------------------------------------------------------

    @Test
    fun `text readings are never coerced into numbers`() {
        assertEquals("超时", ValueFormatter.format(MetricValue.Text("超时"), "0"))
        assertEquals("20ms", ValueFormatter.format(MetricValue.Text("20ms"), "gb:1"))
        assertEquals("40", ValueFormatter.format(MetricValue.Text("40"), "0.00"))
        assertEquals("Charging", ValueFormatter.format(MetricValue.Text("Charging"), ""))
        assertEquals("超时", ValueFormatter.format(MetricValue.Text("超时"), null))
        assertEquals("TIMEOUT", ValueFormatter.format(MetricValue.Text("timeout"), "upper"))
        assertEquals("timeout", ValueFormatter.format(MetricValue.Text("timeout"), "0"))
    }

    @Test
    fun `unavailable readings render the sentinel never zero`() {
        assertEquals("--", ValueFormatter.format(MetricValue.Unavailable(UnavailableReason.NO_DATA), "0"))
        assertEquals("--", ValueFormatter.format(MetricValue.Unavailable(UnavailableReason.NOT_SUPPORTED), null))
        assertEquals("--", ValueFormatter.format(MetricValue.NotSupported, "gb:1"))
    }

    @Test
    fun `numeric readings are formatted through the same pipeline`() {
        assertEquals("43", ValueFormatter.format(MetricValue.Number(42.7), "0"))
        assertEquals("8.0", ValueFormatter.format(MetricValue.Number(8589934592.0), "gb:1"))
        assertEquals("3.46", ValueFormatter.format(MetricValue.Number(3.456), "0.00"))
    }
}
