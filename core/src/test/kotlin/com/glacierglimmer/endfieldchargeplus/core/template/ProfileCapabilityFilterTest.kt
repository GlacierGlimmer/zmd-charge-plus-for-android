package com.glacierglimmer.endfieldchargeplus.core.template

import com.glacierglimmer.endfieldchargeplus.core.model.HudColorRule
import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for [ProfileCapabilityFilter], the pure part of the desktop "strip unsupported" rule. */
class ProfileCapabilityFilterTest {

    private fun profile(
        primary: String = "{cpu.usage|0}",
        secondary: String = " GHz",
        right: String = "{gpu.usage|0}",
        progress: String = "cpu.usage",
        rules: List<HudColorRule> = listOf(HudColorRule(variable = "cpu.usage", operator = ">=", value = 90.0)),
    ) = HudProfile(
        taglineTemplate = "/// SYSTEM",
        titleTemplate = "系统状态",
        primaryTemplate = primary,
        secondaryTemplate = secondary,
        rightTemplate = right,
        rightSuffix = "%",
        progressVariable = progress,
        colorRules = rules,
    )

    @Test
    fun `unsupported variable tokens are removed and literals stay`() {
        val filtered = ProfileCapabilityFilter.filter(
            profile(primary = "Loss {probe.loss_percent|0}", right = "丢包{probe.loss_percent|0}"),
            setOf("probe.loss_percent"),
        )
        assertEquals("Loss ", filtered.primaryTemplate)
        assertEquals("丢包", filtered.rightTemplate)
        assertEquals("%", filtered.rightSuffix)
        assertEquals("/// SYSTEM", filtered.taglineTemplate)
        assertEquals("系统状态", filtered.titleTemplate)
    }

    @Test
    fun `supported references are kept`() {
        val source = profile()
        val filtered = ProfileCapabilityFilter.filter(source, setOf("gpu.usage"))
        assertEquals("{cpu.usage|0}", filtered.primaryTemplate)
        assertEquals("{gpu.usage|0}" , source.rightTemplate)
        assertEquals("", filtered.rightTemplate)
        assertEquals("cpu.usage", filtered.progressVariable)
        assertEquals(1, filtered.colorRules.size)
    }

    @Test
    fun `expression tokens are dropped only when one of their variables is unsupported`() {
        val dropped = ProfileCapabilityFilter.filter(
            profile(primary = "GPU {= gpu.usage ?? 0}", secondary = ""),
            setOf("gpu.usage"),
        )
        assertEquals("GPU ", dropped.primaryTemplate)
        val kept = ProfileCapabilityFilter.filter(
            profile(primary = "{= cpu.usage > 50 ? 'HOT' : 'OK'}", right = "{= cpu.usage + 1|0}"),
            setOf("gpu.usage"),
        )
        assertEquals("{= cpu.usage > 50 ? 'HOT' : 'OK'}", kept.primaryTemplate)
        assertEquals("{= cpu.usage + 1|0}", kept.rightTemplate)
    }

    @Test
    fun `progress variable is cleared when it depends on an unsupported variable`() {
        assertEquals(
            "",
            ProfileCapabilityFilter.filter(profile(progress = "cpu.temperature_max"), setOf("cpu.temperature_max")).progressVariable,
        )
        assertEquals(
            "",
            ProfileCapabilityFilter.filter(profile(progress = "= gpu.usage + 1"), setOf("gpu.usage")).progressVariable,
        )
        assertEquals(
            "",
            ProfileCapabilityFilter.filter(profile(progress = "{= gpu.usage * 2 }"), setOf("gpu.usage")).progressVariable,
        )
        assertEquals(
            "= cpu.usage * 2",
            ProfileCapabilityFilter.filter(profile(progress = "= cpu.usage * 2"), setOf("gpu.usage")).progressVariable,
        )
    }

    @Test
    fun `colour rules with unsupported variables are dropped`() {
        val source = profile(
            rules = listOf(
                HudColorRule(variable = "cpu.usage", operator = ">=", value = 90.0),
                HudColorRule(variable = "cpu.temperature_max", operator = ">=", value = 80.0),
                HudColorRule(variable = "", operator = ">=", value = 1.0),
            ),
        )
        val filtered = ProfileCapabilityFilter.filter(source, setOf("cpu.temperature_max"))
        assertEquals(1, filtered.colorRules.size)
        assertEquals("cpu.usage", filtered.colorRules.single().variable)
    }

    @Test
    fun `aliases and case are canonicalised on both sides`() {
        val filtered = ProfileCapabilityFilter.filter(
            profile(primary = "{ping.latency_ms|0}", right = "{probe.loss_percent|0}"),
            setOf("PROBE.LATENCY_MS", "probe.loss_percent"),
        )
        assertEquals("", filtered.primaryTemplate)
        assertEquals("", filtered.rightTemplate)
    }

    @Test
    fun `a profile that needs no change is returned unchanged`() {
        val source = profile()
        assertSame(source, ProfileCapabilityFilter.filter(source, emptySet()))
        assertSame(source, ProfileCapabilityFilter.filter(source, setOf("something.else")))
        val changed = ProfileCapabilityFilter.filter(source, setOf("cpu.usage"))
        assertTrue(changed !== source)
        assertEquals("", changed.primaryTemplate)
        assertEquals("", changed.progressVariable)
        assertEquals(0, changed.colorRules.size)
    }
}
