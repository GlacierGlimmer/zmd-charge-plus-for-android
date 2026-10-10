package com.glacierglimmer.endfieldchargeplus.core.metrics
import org.junit.Assert.*
import org.junit.Test

class AndroidVariableCatalogTest {
    @Test fun `desktop and unimplemented fields are absent instead of selectable placeholders`() {
        for(name in listOf("security.tpm.present","dev.git.branch","clipboard.preview","gpu.vram_bytes","gpu.memory_total_bytes",
            "cpu.l2_cache_kb","network.public_ipv4","network.wifi_ssid","disk.c.usage","disk.mount_ab12.used_bytes","usb.device.list")) assertNull(name,VariableRegistry.byName(name))
        assertTrue(VariableRegistry.ofType(VariableType.UNAVAILABLE).isEmpty())
        assertTrue(VariableRegistry.count() in 80..120)
    }
    @Test fun `real Android and Root-capable metrics remain selectable`() {
        val names=listOf("cpu.usage","cpu.frequency_ghz","cpu.max_frequency_ghz","cpu.temperature_c","gpu.usage",
            "gpu.frequency_ghz","gpu.temperature_c","memory.total_bytes","memory.cached_bytes","battery.percent","battery.current_ma",
            "device.model","network.download_bps","disk.system.available_bytes","disk.system.free_percent",
            "time.target.remaining_seconds","deepseek.balance","probe.latency_ms")
        names.forEach { assertTrue(it,VariableRegistry.byName(it)?.isSupportedOnAndroid==true) }
        assertTrue(VariableRegistry.byName("cpu.usage")!!.androidNote.contains("Root"))
        assertTrue(VariableRegistry.byName("gpu.usage")!!.androidNoteEn.contains("Root"))
    }
    @Test fun `visible descriptors have names and actual source descriptions in both languages`() {
        val all=VariableRegistry.all(); assertEquals(all.size,all.map { it.name }.distinct().size)
        all.forEach {
            assertTrue(it.name,it.descriptionZh.isNotBlank() && it.descriptionEn.isNotBlank())
            assertTrue(it.name,it.labelZh.isNotBlank() && it.labelEn.isNotBlank())
            assertTrue(it.name,it.androidNote.isNotBlank() && it.androidNoteEn.isNotBlank())
            assertFalse(it.name,it.name.contains('<'))
        }
    }
    @Test fun `legacy aliases use real readings and are case insensitive`() {
        assertEquals("probe.latency_ms",VariableRegistry.byName("ping.latency_ms")!!.name)
        assertEquals("cpu.usage",VariableRegistry.byName("CPU.USAGE_PERCENT")!!.name)
        assertEquals("cpu.model",VariableRegistry.byName("cpu.name")!!.name)
        assertEquals("device.uptime_seconds",VariableRegistry.byName("system.uptime_seconds")!!.name)
        assertEquals("disk.system.available_bytes",VariableRegistry.byName("disk.system.free_bytes")!!.name)
    }
    @Test fun `dynamic lookup accepts concrete kernel and HTTP fields and rejects pattern tokens`() {
        assertEquals("cpu.core0.usage",VariableRegistry.byName("cpu.core0.usage")!!.name)
        assertEquals("custom.weather.temp",VariableRegistry.byName("custom.weather.temp")!!.name)
        assertNotNull(VariableRegistry.byName("http.weather.temp"))
        assertNull(VariableRegistry.byName("cpu.core.temperature_avg"))
        assertNull(VariableRegistry.byName("custom.<source>.<field>"))
    }
    @Test fun `search and category grouping use the Android catalog`() {
        assertTrue(VariableRegistry.search("电池").any { it.name=="battery.percent" })
        assertTrue(VariableRegistry.search("MEMORY").all { it.name.startsWith("memory.") })
        assertTrue(VariableRegistry.search("security.tpm").isEmpty())
        assertEquals(VariableRegistry.count(),VariableRegistry.search("").size)
        assertEquals(VariableRegistry.count(),VariableRegistry.byCategory().values.sumOf { it.size })
    }
}
