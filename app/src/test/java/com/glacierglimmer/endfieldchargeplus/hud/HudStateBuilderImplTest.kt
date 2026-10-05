package com.glacierglimmer.endfieldchargeplus.hud

import com.glacierglimmer.endfieldchargeplus.core.i18n.UiLanguage
import com.glacierglimmer.endfieldchargeplus.core.metrics.MetricSnapshot
import com.glacierglimmer.endfieldchargeplus.core.model.BuiltInProfiles
import com.glacierglimmer.endfieldchargeplus.core.model.HudColorRule
import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile
import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [HudStateBuilderImpl]: templates, progress clamping, colour-rule order and the
 * sentinel for missing data — all without any Android API.
 */
class HudStateBuilderImplTest {

    private val builder = HudStateBuilderImpl()

    private fun builtIn(key: String): HudProfile =
        BuiltInProfiles.all().first { it.builtInKey == key }

    private fun snapshot(vararg values: Pair<String, MetricValue>): MetricSnapshot =
        MetricSnapshot(values.toMap(), timestampMs = 1L)

    private fun number(value: Double) = MetricValue.Number(value)

    @Test
    fun `cpu built in renders templates and progress`() {
        val data = builder.build(
            builtIn(BuiltInProfiles.CPU),
            snapshot("cpu.frequency_ghz" to number(3.456), "cpu.usage" to number(95.0)),
            UiLanguage.ZH_CN,
        )
        assertEquals("/// CPU", data.tagline)
        assertEquals("CPU", data.title)
        assertEquals("3.46", data.primaryText)
        assertEquals(" GHz", data.secondaryText)
        assertEquals("95", data.rightText)
        assertEquals("%", data.rightSuffix)
        assertEquals(0.95, data.progress, 1e-9)
        assertEquals("#FF4D4F", data.accentColor)
        assertEquals("cpu", data.leftIcon)
        assertFalse(data.simpleAnimation)
    }

    @Test
    fun `simple animation follows the animation mode`() {
        val profile = builtIn(BuiltInProfiles.CPU).copy(animationMode = "Simple")
        assertTrue(builder.build(profile, snapshot(), UiLanguage.ZH_CN).simpleAnimation)
        assertFalse(builder.build(profile.copy(animationMode = "Full"), snapshot(), UiLanguage.ZH_CN).simpleAnimation)
    }

    @Test
    fun `missing readings render the sentinel never zero`() {
        val data = builder.build(builtIn(BuiltInProfiles.GPU), snapshot(), UiLanguage.ZH_CN)
        assertEquals("--", data.primaryText)
        assertEquals("/-- GB", data.secondaryText)
        assertEquals("--", data.rightText)
        assertEquals("%", data.rightSuffix)
        assertEquals(0.0, data.progress, 0.0)
        assertEquals("#C6CA4C", data.accentColor)
    }

    @Test
    fun `secondary unit suffixes survive an unavailable value`() {
        val data = builder.build(builtIn(BuiltInProfiles.NETWORK_PROBE), snapshot(), UiLanguage.EN)
        assertEquals("--ms", data.primaryText)
        assertEquals("Loss --", data.rightText)
        assertEquals("%", data.rightSuffix)
    }

    @Test
    fun `memory profile uses binary byte units`() {
        val data = builder.build(
            builtIn(BuiltInProfiles.MEMORY),
            snapshot(
                "memory.used_bytes" to number(8589934592.0),
                "memory.total_bytes" to number(17179869184.0),
                "memory.usage" to number(50.0),
            ),
            UiLanguage.ZH_CN,
        )
        assertEquals("8.0", data.primaryText)
        assertEquals("/16.0 GB", data.secondaryText)
        assertEquals("50", data.rightText)
        assertEquals(0.5, data.progress, 1e-9)
    }

    @Test
    fun `colour rules are evaluated in order and the first match wins`() {
        val cpu = builtIn(BuiltInProfiles.CPU)
        assertEquals("#FF4D4F", builder.resolveAccent(cpu, snapshot("cpu.usage" to number(95.0))))
        assertEquals("#FFB84D", builder.resolveAccent(cpu, snapshot("cpu.usage" to number(80.0))))
        assertEquals("#C6CA4C", builder.resolveAccent(cpu, snapshot("cpu.usage" to number(10.0))))
        assertEquals("#FF4D4F", builder.matchingRule(cpu, snapshot("cpu.usage" to number(90.0)))!!.color)
        assertNull(builder.matchingRule(cpu, snapshot("cpu.usage" to number(10.0))))
        assertNull(builder.matchingRule(cpu, snapshot()))
    }

    @Test
    fun `battery rule uses the lower bound comparison`() {
        val battery = builtIn(BuiltInProfiles.BATTERY)
        assertEquals("#FF4D4F", builder.resolveAccent(battery, snapshot("battery.percent" to number(20.0))))
        assertEquals("#FF4D4F", builder.resolveAccent(battery, snapshot("battery.percent" to number(5.0))))
        assertEquals("#C6CA4C", builder.resolveAccent(battery, snapshot("battery.percent" to number(21.0))))
    }

    @Test
    fun `text readings never satisfy a numeric colour rule`() {
        val profile = HudProfile(
            colorRules = listOf(HudColorRule(variable = "battery.status_text", operator = ">=", value = 0.0)),
        )
        assertNull(builder.matchingRule(profile, snapshot("battery.status_text" to MetricValue.Text("Charging"))))
        assertEquals("#C6CA4C", builder.resolveAccent(profile, snapshot("battery.status_text" to MetricValue.Text("Charging"))))
    }

    @Test
    fun `unknown colour operators never match`() {
        val profile = HudProfile(colorRules = listOf(HudColorRule(variable = "cpu.usage", operator = "~=", value = 1.0)))
        assertNull(builder.matchingRule(profile, snapshot("cpu.usage" to number(1.0))))
        val equals = HudProfile(colorRules = listOf(HudColorRule(variable = "cpu.usage", operator = "==", value = 50.0)))
        assertEquals("==", builder.matchingRule(equals, snapshot("cpu.usage" to number(50.0000005)))!!.operator)
        assertNull(builder.matchingRule(equals, snapshot("cpu.usage" to number(50.1))))
        val notEquals = HudProfile(colorRules = listOf(HudColorRule(variable = "cpu.usage", operator = "!=", value = 50.0)))
        assertNull(builder.matchingRule(notEquals, snapshot("cpu.usage" to number(50.0))))
        assertEquals("!=", builder.matchingRule(notEquals, snapshot("cpu.usage" to number(49.0)))!!.operator)
    }

    @Test
    fun `progress clamps to the configured span`() {
        val cpu = builtIn(BuiltInProfiles.CPU)
        assertEquals(1.0, builder.resolveProgress(cpu, snapshot("cpu.usage" to number(150.0))), 0.0)
        assertEquals(0.0, builder.resolveProgress(cpu, snapshot("cpu.usage" to number(-10.0))), 0.0)
        assertEquals(0.75, builder.resolveProgress(cpu, snapshot("cpu.usage" to number(75.0))), 1e-9)
        // A degenerate span is zero progress, not a division by zero.
        assertEquals(0.0, builder.resolveProgress(cpu.copy(progressMin = 10.0, progressMax = 10.0), snapshot("cpu.usage" to number(10.0))), 0.0)
    }

    @Test
    fun `progress accepts an expression and a custom span`() {
        val profile = builtIn(BuiltInProfiles.MEMORY).copy(
            progressVariable = "= (cpu.usage + gpu.usage) / 2",
            progressMin = 0.0,
            progressMax = 100.0,
        )
        val data = builder.build(
            profile,
            snapshot("cpu.usage" to number(40.0), "gpu.usage" to number(60.0), "memory.used_bytes" to number(0.0)),
            UiLanguage.ZH_CN,
        )
        assertEquals(0.5, data.progress, 1e-9)
        val span = profile.copy(progressVariable = "cpu.usage", progressMin = 50.0, progressMax = 100.0)
        assertEquals(1.0, builder.resolveProgress(span, snapshot("cpu.usage" to number(100.0))), 1e-9)
        assertEquals(0.5, builder.resolveProgress(span, snapshot("cpu.usage" to number(75.0))), 1e-9)
    }

    @Test
    fun `legacy aliases are resolved when rendering`() {
        val profile = HudProfile(primaryTemplate = "{ping.latency_ms|0}ms", rightTemplate = "{ping.loss_percent|0}")
        val data = builder.build(
            profile,
            snapshot("probe.latency_ms" to number(20.0), "probe.loss_percent" to number(5.0)),
            UiLanguage.ZH_CN,
        )
        assertEquals("20ms", data.primaryText)
        assertEquals("5", data.rightText)
    }

    @Test
    fun `English localization is applied before rendering`() {
        val battery = builder.build(
            builtIn(BuiltInProfiles.BATTERY),
            snapshot(
                "battery.remaining_mwh" to number(30000.0),
                "battery.full_mwh" to number(60000.0),
                "battery.percent" to number(50.0),
            ),
            UiLanguage.EN,
        )
        assertEquals("Battery", battery.title)
        val probe = builder.build(
            builtIn(BuiltInProfiles.NETWORK_PROBE),
            snapshot("probe.latency_ms" to number(20.0), "probe.loss_percent" to number(25.4)),
            UiLanguage.EN,
        )
        assertEquals("Packet Probe", probe.title)
        assertEquals("Loss 25", probe.rightText)
    }

    @Test
    fun `the profile is never mutated by rendering`() {
        val battery = builtIn(BuiltInProfiles.BATTERY)
        builder.build(battery, snapshot("battery.percent" to number(10.0)), UiLanguage.EN)
        assertEquals("电池", battery.titleTemplate)
        assertEquals("系统", battery.category)
        assertEquals("丢包{probe.loss_percent|0}", builtIn(BuiltInProfiles.NETWORK_PROBE).let { profile ->
            builder.build(profile, snapshot(), UiLanguage.EN)
            profile.rightTemplate
        })
    }

    @Test
    fun `default HudProfile renders its documented template`() {
        val data = builder.build(
            HudProfile(),
            snapshot("cpu.frequency_ghz" to number(3.456), "cpu.usage" to number(42.7)),
            UiLanguage.ZH_CN,
        )
        assertEquals("/// SYSTEM MONITOR", data.tagline)
        assertEquals("系统状态", data.title)
        assertEquals("3.46", data.primaryText)
        assertEquals(" GHz", data.secondaryText)
        assertEquals("43", data.rightText)
        assertEquals("%", data.rightSuffix)
        assertEquals(0.427, data.progress, 1e-9)
    }
}
