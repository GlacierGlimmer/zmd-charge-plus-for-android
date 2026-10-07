package com.glacierglimmer.endfieldchargeplus.core.metrics

import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import org.junit.Assert.*
import org.junit.Test

class BugRegressionTest {
    @Test fun storageAliasesResolveToThePublishedAvailableSpace() {
        val snapshot = MetricSnapshot(mapOf(Variables.DISK_SYSTEM_AVAILABLE_BYTES to MetricValue.Number(1234.0)))
        for (name in listOf("disk.free_bytes", "disk.available_bytes", "disk.system.free_bytes")) {
            assertEquals(name, MetricValue.Number(1234.0), snapshot[name])
        }
    }

    @Test fun descriptionsExplainAndroidSourcesInBothLanguages() {
        for (descriptor in VariableRegistry.all()) {
            assertFalse(descriptor.name, descriptor.descriptionZh.contains("Win32"))
            assertFalse(descriptor.name, descriptor.descriptionZh.contains("WMI"))
            assertFalse(descriptor.name, descriptor.descriptionEn.contains(Regex("[\\u3400-\\u9fff]")))
            assertTrue(descriptor.name, descriptor.androidNoteEn.isNotBlank())
        }
        assertTrue(VariableRegistry.byName("cpu.usage")!!.descriptionEn.contains("/proc/stat"))
        assertTrue(VariableRegistry.byName("disk.system.available_bytes")!!.descriptionEn.contains("reserved blocks"))
    }
}
