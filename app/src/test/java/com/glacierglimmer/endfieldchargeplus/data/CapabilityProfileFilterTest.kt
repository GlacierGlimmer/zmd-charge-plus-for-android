package com.glacierglimmer.endfieldchargeplus.data

import com.glacierglimmer.endfieldchargeplus.core.metrics.Variables
import com.glacierglimmer.endfieldchargeplus.core.model.CustomHudSettings
import com.glacierglimmer.endfieldchargeplus.core.model.HudColorRule
import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile
import com.glacierglimmer.endfieldchargeplus.metrics.Capability
import com.glacierglimmer.endfieldchargeplus.metrics.HardwareCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityProfileFilterTest {

    @Test
    fun `unsupported tokens are stripped and available tokens are kept`() {
        val profile = HudProfile(
            id = "p",
            name = "P",
            primaryTemplate = "GPU {gpu.usage|0}%",
            secondaryTemplate = "{cpu.usage|0}",
            rightTemplate = "{= round(gpu.temperature_c) }",
            progressVariable = "gpu.usage",
            colorRules = listOf(
                HudColorRule(variable = "gpu.usage"),
                HudColorRule(variable = "cpu.usage"),
            ),
        )

        val filtered = CapabilityProfileFilter.filter(profile, setOf("gpu.*"))

        assertEquals("GPU %", filtered.primaryTemplate)
        assertEquals("{cpu.usage|0}", filtered.secondaryTemplate)
        assertEquals("", filtered.rightTemplate)
        assertEquals("", filtered.progressVariable)
        assertEquals(listOf("cpu.usage"), filtered.colorRules.map { it.variable })
    }

    @Test
    fun `aliases resolve to the canonical variable before matching`() {
        assertFalse(CapabilityProfileFilter.isAvailable("ping.latency_ms", setOf("probe.latency_ms")))
        assertTrue(CapabilityProfileFilter.isAvailable("ping.latency_ms", setOf("probe.loss_percent")))
        assertFalse(CapabilityProfileFilter.isAvailable("probe.latency_ms", setOf("probe.*")))
    }

    @Test
    fun `pattern matching supports prefixes and per-core placeholders`() {
        assertTrue(CapabilityProfileFilter.matches("gpu.*", "gpu.memory_used_bytes"))
        assertFalse(CapabilityProfileFilter.matches("gpu.*", "cpu.usage"))
        assertTrue(CapabilityProfileFilter.matches("cpu.coreN.usage", "cpu.core3.usage"))
        assertTrue(CapabilityProfileFilter.matches("cpu.coreN.usage", "cpu.core12.usage"))
        assertFalse(CapabilityProfileFilter.matches("cpu.coreN.usage", "cpu.temperature_c"))
        assertTrue(CapabilityProfileFilter.matches("cpu.temperature_c", "CPU.TEMPERATURE_C"))
    }

    @Test
    fun `an entirely unsupported gpu family collapses to gpu star`() {
        val unsupported = CapabilityProfileFilter.unsupportedVariables(
            capabilities(
                gpuUsage = Capability.unsupported("capability_gpu_missing"),
                gpuFrequency = Capability.unsupported("capability_gpu_missing"),
                gpuTemperature = Capability.unsupported("capability_gpu_missing"),
                gpuMemory = Capability.unsupported("capability_gpu_missing"),
                cpuTemperature = Capability.unsupported("capability_cpu_temp_missing"),
                cpuPerCoreUsage = Capability.unsupported("capability_cpu_cores_missing"),
            ),
        )

        assertTrue(unsupported.contains("gpu.*"))
        assertTrue(unsupported.contains(Variables.CPU_TEMPERATURE_C))
        assertTrue(unsupported.contains("cpu.coreN.usage"))
    }

    @Test
    fun `a partially unsupported gpu family keeps the specific variable names`() {
        val unsupported = CapabilityProfileFilter.unsupportedVariables(
            capabilities(gpuMemory = Capability.unsupported("capability_gpu_memory_missing")),
        )

        assertFalse(unsupported.contains("gpu.*"))
        assertTrue(unsupported.contains(Variables.GPU_MEMORY_USED_BYTES))
        assertTrue(unsupported.contains(Variables.GPU_MEMORY_TOTAL_BYTES))
    }

    @Test
    fun `an unscanned device strips nothing`() {
        val unscanned = HardwareCapabilities.Unknown
        val unsupported = CapabilityProfileFilter.unsupportedVariables(
            capabilities(
                gpuUsage = unscanned,
                gpuFrequency = unscanned,
                gpuTemperature = unscanned,
                gpuMemory = unscanned,
                cpuTemperature = unscanned,
                cpuPerCoreUsage = unscanned,
            ),
        )

        assertTrue(unsupported.isEmpty())
    }

    @Test
    fun `a probe failure strips every probe variable except the honest status text`() {
        val unsupported = CapabilityProfileFilter.unsupportedVariables(
            capabilities(
                probeIcmp = Capability.unsupported("capability_probe_icmp_missing"),
                probeTcp = Capability.unsupported("capability_probe_tcp_missing"),
                probeUdp = Capability.unsupported("capability_probe_udp_missing"),
            ),
        )

        assertTrue(unsupported.contains(Variables.PROBE_LATENCY_MS))
        assertTrue(unsupported.contains(Variables.PROBE_LOSS_PERCENT))
        assertFalse(unsupported.contains(Variables.PROBE_STATUS_TEXT))
    }

    @Test
    fun `filtering a whole settings object leaves built-ins otherwise canonical`() {
        val settings = CustomHudSettings.createDefault()
        val filtered = CapabilityProfileFilter.filter(settings, setOf("gpu.*"))

        assertEquals(settings.profiles.size, filtered.profiles.size)
        settings.profiles.forEach { original ->
            val after = filtered.profiles.first { it.id == original.id }
            assertEquals(original.name, after.name)
            assertEquals(original.builtInKey, after.builtInKey)
        }
    }

    private fun capabilities(
        cpuPerCoreUsage: Capability = Capability.Supported,
        cpuTemperature: Capability = Capability.Supported,
        gpuUsage: Capability = Capability.Supported,
        gpuFrequency: Capability = Capability.Supported,
        gpuTemperature: Capability = Capability.Supported,
        gpuMemory: Capability = Capability.Supported,
        probeIcmp: Capability = Capability.Supported,
        probeTcp: Capability = Capability.Supported,
        probeUdp: Capability = Capability.Supported,
    ): HardwareCapabilities = HardwareCapabilities(
        cpuTotalUsage = Capability.Supported,
        cpuPerCoreUsage = cpuPerCoreUsage,
        cpuFrequency = Capability.Supported,
        cpuTemperature = cpuTemperature,
        gpuUsage = gpuUsage,
        gpuFrequency = gpuFrequency,
        gpuTemperature = gpuTemperature,
        gpuMemory = gpuMemory,
        batteryCurrent = Capability.Supported,
        batteryTemperature = Capability.Supported,
        batteryVoltage = Capability.Supported,
        memoryDetail = Capability.Supported,
        storage = Capability.Supported,
        networkTraffic = Capability.Supported,
        wifiSsid = Capability.Supported,
        probeIcmp = probeIcmp,
        probeTcp = probeTcp,
        probeUdp = probeUdp,
    )
}
