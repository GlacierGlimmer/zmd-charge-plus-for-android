package com.glacierglimmer.endfieldchargeplus.ui

import com.glacierglimmer.endfieldchargeplus.ui.state.VariableRow
import com.glacierglimmer.endfieldchargeplus.ui.state.VariableSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Search/grouping of the Variable Library (the pure part of the Variables ViewModel). */
class VariableSearchTest {

    private val rows = listOf(
        row("cpu.usage", "CPU", label = "CPU Usage", description = "Current total CPU load"),
        row("cpu.frequency_ghz", "CPU", label = "CPU Frequency", description = "Current clock"),
        row("memory.used_bytes", "内存", label = "Used memory", description = "Physical memory in use"),
        row("battery.percent", "电池", label = "Battery level", description = "Remaining charge"),
        row("probe.latency_ms", "网络探测", label = "Latency", description = "Round trip time"),
    )

    private fun row(
        name: String,
        category: String,
        label: String,
        description: String,
    ) = VariableRow(
        name = name,
        categoryKey = category,
        label = label,
        typeLabel = "Number",
        unit = "%",
        description = description,
        formats = "0",
    )

    @Test
    fun anEmptyQueryReturnsEverythingInRegistryOrder() {
        assertEquals(rows, VariableSearch.filter(rows, "", VariableSearch.ALL_CATEGORIES))
        assertEquals(rows, VariableSearch.filter(rows, "   ", null))
    }

    @Test
    fun theQueryMatchesKeyLabelDescriptionAndCategory() {
        assertEquals(listOf("cpu.usage"), VariableSearch.filter(rows, "cpu.usage", null).map { it.name })
        assertEquals(listOf("cpu.usage"), VariableSearch.filter(rows, "usage", null).map { it.name })
        assertEquals(listOf("battery.percent"), VariableSearch.filter(rows, "remaining charge", null).map { it.name })
        assertEquals(
            listOf("cpu.usage", "cpu.frequency_ghz"),
            VariableSearch.filter(rows, "CPU", null).map { it.name },
        )
        assertTrue(VariableSearch.filter(rows, "nonexistent", null).isEmpty())
    }

    @Test
    fun theCategoryFilterIsExactAndCombinesWithTheQuery() {
        assertEquals(
            listOf("battery.percent"),
            VariableSearch.filter(rows, "", "电池").map { it.name },
        )
        assertTrue(VariableSearch.filter(rows, "cpu", "电池").isEmpty())
    }

    @Test
    fun categoriesKeepFirstSeenOrder() {
        assertEquals(listOf("CPU", "内存", "电池", "网络探测"), VariableSearch.categories(rows))
    }

    @Test
    fun groupingKeepsTheRegistryOrderInsideACategory() {
        val grouped = VariableSearch.grouped(rows)
        assertEquals(listOf("CPU", "内存", "电池", "网络探测"), grouped.map { it.first })
        assertEquals(listOf("cpu.usage", "cpu.frequency_ghz"), grouped.first().second.map { it.name })
    }

    @Test
    fun theTemplateTokenIsBraced() {
        assertEquals("{cpu.usage}", VariableSearch.token(rows[0]))
    }
}
