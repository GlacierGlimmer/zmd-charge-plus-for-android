package com.glacierglimmer.endfieldchargeplus.core.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [VariableRegistry]: real ECP coverage, Android availability policy, category order and
 * lookups.
 */
class VariableRegistryTest {

    /** Every `const val ... = "name"` declared by [Variables], via plain reflection. */
    private fun declaredVariableNames(): List<String> {
        val prefixes = setOf(Variables.CPU_PER_CORE_PREFIX, Variables.HTTP_PREFIX, Variables.HTTP_LEGACY_PREFIX)
        val fields = Variables::class.java.declaredFields
        return fields
            .filter { it.type == String::class.java }
            .mapNotNull { runCatching { it.isAccessible = true; it.get(null) as? String }.getOrNull() }
            .filter { it.isNotBlank() && it !in prefixes }
    }

    @Test
    fun `the registry covers every name declared in Variables`() {
        val names = declaredVariableNames()
        assertTrue("reflection found too few constants: ${names.size}", names.size >= 85)
        val missing = names.filter { VariableRegistry.byName(it) == null }
        assertTrue("Variables constants missing from the registry: $missing", missing.isEmpty())
    }

    @Test
    fun `the registry contains the real ECP catalog`() {
        assertTrue("registry looks truncated: ${VariableRegistry.count()}", VariableRegistry.count() >= 429)
        val expected = listOf(
            "cpu.usage", "cpu.user_usage", "cpu.kernel_usage", "cpu.physical_cores", "cpu.l2_cache_kb",
            "memory.total_bytes", "memory.usage", "memory.commit_used_bytes", "memory.cache_bytes",
            "battery.percent", "battery.remaining_mwh", "battery.cycle_count", "battery.chemistry",
            "gpu.name", "gpu.count", "gpu.vram_bytes", "network.public_ipv4", "network.dns_servers",
            "network.packets_received", "disk.system.used_bytes", "disk.fixed.usage",
            "probe.latency_ms", "probe.reply_status", "ping.loss_percent", "time.current",
            "time.day.progress", "time.world.nyc", "system.machine_name", "system.kernel_version",
            "display.scale_percent", "clipboard.preview", "security.tpm.present",
            "process.top_cpu.usage", "usb.device.list", "dev.git.branch", "app.working_set_bytes",
            "deepseek.balance", "deepseek.period.progress", "custom.<source>.<field>",
            "disk.mount_<hash>.used_bytes",
        )
        val missing = expected.filter { VariableRegistry.byName(it) == null }
        assertTrue("missing real ECP keys: $missing", missing.isEmpty())
    }

    @Test
    fun `per core usage is never invented`() {
        // The desktop catalogs publish only cpu.core.temperature_avg / cpu.core.voltage.
        assertNotNull(VariableRegistry.byName("cpu.core.temperature_avg"))
        assertNotNull(VariableRegistry.byName("cpu.core.voltage"))
        assertNull(VariableRegistry.byName("cpu.core.0.usage"))
        assertNull(VariableRegistry.byName("cpu.core.temperature"))
        assertNull(VariableRegistry.byName("totally.made.up"))
    }

    @Test
    fun `android impossible metrics are unavailable with an honest note`() {
        val unavailable = listOf(
            "cpu.temperature_max", "cpu.temperature_c", "cpu.core.temperature_avg",
            "gpu.usage", "gpu.usage_3d", "gpu.temperature", "gpu.vram_bytes", "gpu.frequency_ghz",
            "disk.system.read_bps", "disk.system.active_percent", "system.motherboard_temperature",
            "security.secure_boot", "dev.git.branch", "usb.device.list", "process.top_cpu.usage",
            "display.monitor_count", "network.tcp_connections", "network.receive_errors",
            "probe.ttl", "app.handle_count",
        )
        for (name in unavailable) {
            val descriptor = VariableRegistry.byName(name)
            assertNotNull("missing $name", descriptor)
            assertEquals("$name should be UNAVAILABLE", VariableType.UNAVAILABLE, descriptor!!.type)
            assertTrue("$name needs an androidNote", descriptor.androidNote.isNotBlank())
            assertFalse("$name must not be advertised as supported", descriptor.isSupportedOnAndroid)
        }
    }

    @Test
    fun `available metrics carry the platform source note`() {
        assertTrue(VariableRegistry.byName("cpu.usage")!!.androidNote.contains("/proc/stat"))
        assertTrue(VariableRegistry.byName("memory.total_bytes")!!.androidNote.contains("meminfo"))
        assertTrue(VariableRegistry.byName("battery.percent")!!.androidNote.contains("BatteryManager"))
        assertTrue(VariableRegistry.byName("network.download_bps")!!.androidNote.contains("TrafficStats"))
        assertTrue(VariableRegistry.byName("disk.system.used_bytes")!!.androidNote.contains("StatFs"))
        assertTrue(VariableRegistry.byName("probe.latency_ms")!!.androidNote.contains("TCP"))
        assertTrue(VariableRegistry.byName("device.model")!!.androidNote.contains("Build"))
        assertEquals(VariableType.UNAVAILABLE, VariableRegistry.byName("cpu.temperature_c")!!.type)
        assertEquals(VariableType.NUMBER, VariableRegistry.byName("system.uptime_seconds")!!.type)
        assertEquals(VariableType.PROGRESS, VariableRegistry.byName("cpu.usage")!!.type)
        assertEquals(VariableType.PROGRESS, VariableRegistry.byName("memory.usage")!!.type)
        assertEquals(VariableType.PROGRESS, VariableRegistry.byName("battery.percent")!!.type)
    }

    @Test
    fun `every descriptor is complete`() {
        for (descriptor in VariableRegistry.all()) {
            assertTrue("blank name", descriptor.name.isNotBlank())
            assertTrue("blank category for ${descriptor.name}", descriptor.categoryKey.isNotBlank())
            assertTrue("blank zh description for ${descriptor.name}", descriptor.descriptionZh.isNotBlank())
            assertTrue("blank en description for ${descriptor.name}", descriptor.descriptionEn.isNotBlank())
            assertTrue("blank zh label for ${descriptor.name}", descriptor.labelZh.isNotBlank())
            assertTrue("blank en label for ${descriptor.name}", descriptor.labelEn.isNotBlank())
            assertTrue("unknown category for ${descriptor.name}", descriptor.categoryKey in VariableRegistry.categories())
            if (descriptor.type == VariableType.UNAVAILABLE) {
                assertTrue("${descriptor.name} needs an androidNote", descriptor.androidNote.isNotBlank())
            }
        }
    }

    @Test
    fun `categories are grouped in ECP display order`() {
        val categories = VariableRegistry.categories()
        assertEquals("CPU", categories.first())
        assertTrue(categories.containsAll(listOf("CPU", "GPU", "内存", "磁盘", "电池", "网络", "时间", "系统")))
        assertFalse("duplicate categories", categories.size != categories.distinct().size)
        val grouped = VariableRegistry.byCategory()
        assertEquals(categories, grouped.keys.toList())
        assertEquals(VariableRegistry.count(), grouped.values.sumOf { it.size })
        assertTrue(grouped.getValue("CPU").all { it.categoryKey == "CPU" })
    }

    @Test
    fun `search matches key label description and category`() {
        assertTrue(VariableRegistry.search("cpu.usage").any { it.name == "cpu.usage" })
        assertTrue(VariableRegistry.search("Usage").any { it.name == "cpu.usage" })
        assertTrue(VariableRegistry.search("电池").any { it.name == "battery.percent" })
        assertTrue(VariableRegistry.search("电池").any { it.name == "battery.percent" })
        assertTrue(VariableRegistry.search("MEMORY").any { it.name.startsWith("memory.") })
        assertEquals(VariableRegistry.count(), VariableRegistry.search("").size)
        assertEquals(VariableRegistry.count(), VariableRegistry.search("   ").size)
        assertTrue(VariableRegistry.search("batterymanag").any { it.name == "battery.percent" })
        assertTrue(VariableRegistry.search("这样的变量不存在").isEmpty())
    }

    @Test
    fun `byName resolves aliases and dynamic families`() {
        assertEquals(VariableRegistry.byName("probe.latency_ms"), VariableRegistry.byName("ping.latency_ms"))
        assertEquals("cpu.usage", VariableRegistry.byName("cpu.usage_percent")!!.name)
        assertEquals("battery.percent", VariableRegistry.byName("battery.level")!!.name)

        val drive = VariableRegistry.byName("disk.c.usage")
        assertNotNull(drive)
        assertEquals("disk.c.usage", drive!!.name)
        assertEquals(VariableType.PROGRESS, drive.type)
        assertEquals(VariableType.UNAVAILABLE, VariableRegistry.byName("disk.c.read_bps")!!.type)

        val mount = VariableRegistry.byName("disk.mount_ab12cd34.used_bytes")
        assertNotNull(mount)
        assertEquals("disk.mount_ab12cd34.used_bytes", mount!!.name)

        val custom = VariableRegistry.byName("custom.myapi.temperature")
        assertNotNull(custom)
        assertEquals("custom.myapi.temperature", custom!!.name)
        assertEquals("自定义数据", custom.categoryKey)
        assertNotNull(VariableRegistry.byName("http.myapi.temperature"))

        assertNull(VariableRegistry.byName("   "))
        assertNull(VariableRegistry.byName("no.such.key"))
    }

    @Test
    fun `ofType and count are consistent`() {
        val unavailable = VariableRegistry.ofType(VariableType.UNAVAILABLE)
        assertTrue(unavailable.isNotEmpty())
        assertTrue(unavailable.all { it.androidNote.isNotBlank() })
        assertEquals(
            VariableRegistry.count(),
            VariableType.entries.sumOf { VariableRegistry.ofType(it).size },
        )
        assertTrue(VariableRegistry.ofType(VariableType.BOOLEAN).any { it.name == "battery.charging" })
        assertTrue(VariableRegistry.ofType(VariableType.TEXT).any { it.name == "battery.status_text" })
    }
}
