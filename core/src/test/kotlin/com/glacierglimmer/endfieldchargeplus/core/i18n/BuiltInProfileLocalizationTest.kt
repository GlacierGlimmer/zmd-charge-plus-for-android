package com.glacierglimmer.endfieldchargeplus.core.i18n

import com.glacierglimmer.endfieldchargeplus.core.metrics.MetricSnapshot
import com.glacierglimmer.endfieldchargeplus.core.model.BuiltInProfiles
import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile
import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import com.glacierglimmer.endfieldchargeplus.core.template.TemplateEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [BuiltInProfileLocalization]: the English overlay of the nine built-in schemes, the
 * display-name rule and the category map — plus an integration check that every built-in renders
 * without the `--` sentinel in both languages when the data exists.
 */
class BuiltInProfileLocalizationTest {

    private fun builtIn(key: String): HudProfile =
        BuiltInProfiles.all().first { it.builtInKey == key }

    @Test
    fun `English overlay matches the desktop table`() {
        assertEquals("Battery", BuiltInProfileLocalization.forLanguage(builtIn(BuiltInProfiles.BATTERY), UiLanguage.EN).titleTemplate)
        assertEquals("System", BuiltInProfileLocalization.forLanguage(builtIn(BuiltInProfiles.BATTERY), UiLanguage.EN).category)
        assertEquals("Battery", BuiltInProfileLocalization.forLanguage(builtIn(BuiltInProfiles.BATTERY), UiLanguage.EN).name)
        assertEquals("Memory", BuiltInProfileLocalization.forLanguage(builtIn(BuiltInProfiles.MEMORY), UiLanguage.EN).titleTemplate)
        assertEquals("System Disk", BuiltInProfileLocalization.forLanguage(builtIn(BuiltInProfiles.DISK), UiLanguage.EN).name)
        assertEquals("Day Progress", BuiltInProfileLocalization.forLanguage(builtIn(BuiltInProfiles.TIME_DAY_PROGRESS), UiLanguage.EN).name)
        val deepseek = BuiltInProfileLocalization.forLanguage(builtIn(BuiltInProfiles.DEEPSEEK_BALANCE), UiLanguage.EN)
        assertEquals("Balance / Period", deepseek.name)
        assertEquals("DeepSeek {deepseek.period.name}", deepseek.titleTemplate)
        val probe = BuiltInProfileLocalization.forLanguage(builtIn(BuiltInProfiles.NETWORK_PROBE), UiLanguage.EN)
        assertEquals("Packet Probe", probe.name)
        assertEquals("Packet Probe", probe.titleTemplate)
        assertEquals("Loss {probe.loss_percent|0}", probe.rightTemplate)
        assertEquals("Network", probe.category)
    }

    @Test
    fun `the overlay never touches language independent fields`() {
        val source = builtIn(BuiltInProfiles.CPU)
        val english = BuiltInProfileLocalization.forLanguage(source, UiLanguage.EN)
        assertEquals(source.taglineTemplate, english.taglineTemplate)
        assertEquals(source.primaryTemplate, english.primaryTemplate)
        assertEquals(source.secondaryTemplate, english.secondaryTemplate)
        assertEquals(source.rightTemplate, english.rightTemplate)
        assertEquals(source.rightSuffix, english.rightSuffix)
        assertEquals(source.progressVariable, english.progressVariable)
        assertEquals(source.leftIcon, english.leftIcon)
        assertEquals(source.accentColor, english.accentColor)
        assertEquals(source.colorRules, english.colorRules)
        assertEquals(source.id, english.id)
    }

    @Test
    fun `Chinese and custom profiles are returned unchanged`() {
        val cpu = builtIn(BuiltInProfiles.CPU)
        assertSame(cpu, BuiltInProfileLocalization.forLanguage(cpu, UiLanguage.ZH_CN))
        val custom = HudProfile(name = "我的方案", builtInKey = "")
        assertSame(custom, BuiltInProfileLocalization.forLanguage(custom, UiLanguage.EN))
        val unknownKey = HudProfile(name = "x", builtInKey = "system.unknown")
        assertSame(unknownKey, BuiltInProfileLocalization.forLanguage(unknownKey, UiLanguage.EN))
    }

    @Test
    fun `displayName follows the desktop rule`() {
        val battery = builtIn(BuiltInProfiles.BATTERY)
        assertEquals("System - Battery", BuiltInProfileLocalization.displayName(battery, UiLanguage.EN))
        assertEquals("系统 - 电池", BuiltInProfileLocalization.displayName(battery, UiLanguage.ZH_CN))
        assertEquals("我的方案", BuiltInProfileLocalization.displayName(HudProfile(name = "我的方案"), UiLanguage.EN))
        assertEquals("Custom Profile", BuiltInProfileLocalization.displayName(HudProfile(name = ""), UiLanguage.EN))
        assertEquals("自定义方案", BuiltInProfileLocalization.displayName(HudProfile(name = ""), UiLanguage.ZH_CN))
    }

    @Test
    fun `categoryName ports the desktop category map`() {
        assertEquals("Packet Probe", BuiltInProfileLocalization.categoryName("网络包探测器", UiLanguage.EN))
        assertEquals("Ping (Compatibility)", BuiltInProfileLocalization.categoryName("Ping（兼容）", UiLanguage.EN))
        assertEquals("USB / Devices", BuiltInProfileLocalization.categoryName("USB / 外设", UiLanguage.EN))
        assertEquals("Custom Data", BuiltInProfileLocalization.categoryName("自定义数据", UiLanguage.EN))
        assertEquals("DeepSeek API", BuiltInProfileLocalization.categoryName("DeepSeek API", UiLanguage.EN))
        assertEquals("CPU", BuiltInProfileLocalization.categoryName("CPU", UiLanguage.EN))
        assertEquals("网络探测", BuiltInProfileLocalization.categoryName("网络探测", UiLanguage.ZH_CN))
        // Unknown categories fall back to the desktop HumanizeWords algorithm.
        assertEquals("USB Device", BuiltInProfileLocalization.categoryName("usb-device", UiLanguage.EN))
        assertEquals("New Category", BuiltInProfileLocalization.categoryName("new_category", UiLanguage.EN))
    }

    @Test
    fun `every built-in renders without the sentinel in both languages`() {
        val snapshot = MetricSnapshot(
            mapOf(
                "battery.remaining_mwh" to MetricValue.Number(45000.0),
                "battery.full_mwh" to MetricValue.Number(60000.0),
                "battery.percent" to MetricValue.Number(75.0),
                "cpu.frequency_ghz" to MetricValue.Number(3.45),
                "cpu.usage" to MetricValue.Number(42.0),
                "memory.used_bytes" to MetricValue.Number(8589934592.0),
                "memory.total_bytes" to MetricValue.Number(17179869184.0),
                "memory.usage" to MetricValue.Number(50.0),
                "gpu.memory_used_bytes" to MetricValue.Number(2147483648.0),
                "gpu.memory_total_bytes" to MetricValue.Number(8589934592.0),
                "gpu.usage" to MetricValue.Number(30.0),
                "network.display_download" to MetricValue.Text("1.5 MB/s"),
                "network.display_upload" to MetricValue.Text("0.2 MB/s"),
                "network.profile_percent_text" to MetricValue.Text("↑ 30%"),
                "disk.system.used_bytes" to MetricValue.Number(107374182400.0),
                "disk.system.total_bytes" to MetricValue.Number(268435456000.0),
                "disk.system.usage" to MetricValue.Number(40.0),
                "time.current" to MetricValue.Text("15:40:35"),
                "time.display.status_text" to MetricValue.Text("65%"),
                "deepseek.balance" to MetricValue.Number(12.5),
                "deepseek.period.name" to MetricValue.Text("Off-peak"),
                "deepseek.period.name_zh" to MetricValue.Text("低谷"),
                "deepseek.period.remaining_text" to MetricValue.Text("Left 20%"),
                "deepseek.period.progress_text" to MetricValue.Text("80%"),
                "probe.latency_ms" to MetricValue.Number(20.0),
                "probe.loss_percent" to MetricValue.Number(0.0),
            ),
            timestampMs = 1L,
        )
        val resolver: (String) -> MetricValue? = { snapshot[it] }
        for (language in UiLanguage.entries) {
            for (profile in BuiltInProfiles.all()) {
                val localized = BuiltInProfileLocalization.forLanguage(profile, language)
                val fields = mapOf(
                    "tagline" to localized.taglineTemplate,
                    "title" to localized.titleTemplate,
                    "primary" to localized.primaryTemplate,
                    "secondary" to localized.secondaryTemplate,
                    "right" to localized.rightTemplate,
                )
                for ((field, template) in fields) {
                    val rendered = TemplateEngine.render(template, resolver)
                    assertFalse(
                        "${profile.builtInKey}/$language/$field rendered '$rendered' from '$template'",
                        rendered.contains(TemplateEngine.UNKNOWN_SENTINEL),
                    )
                }
            }
        }
    }

    @Test
    fun `the English built-in probe template renders a real loss value`() {
        val probe = BuiltInProfileLocalization.forLanguage(builtIn(BuiltInProfiles.NETWORK_PROBE), UiLanguage.EN)
        val loss = TemplateEngine.render(probe.rightTemplate, { name: String ->
            if (name == "probe.loss_percent") MetricValue.Number(25.4) else null
        })
        assertEquals("Loss 25", loss)
        val missing = TemplateEngine.render(probe.rightTemplate, { _: String -> null })
        assertEquals("Loss --", missing)
        assertTrue(builtIn(BuiltInProfiles.NETWORK_PROBE).rightTemplate.contains("丢包"))
    }
}
