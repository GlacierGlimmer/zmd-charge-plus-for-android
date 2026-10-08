package com.glacierglimmer.endfieldchargeplus.deepseek

import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class DeepSeekPeriodPolicyTest {
    @Test
    fun `official calendar cases match desktop across holidays and year boundaries`() {
        val stream = javaClass.classLoader!!.getResourceAsStream("deepseek-period-cases.json")!!
        val cases = stream.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonArray }
        assertEquals(24, cases.size)
        for (value in cases) {
            val case = value.jsonObject
            val label = case.getValue("name").jsonPrimitive.content
            val instant = ZonedDateTime.parse(case.getValue("instant").jsonPrimitive.content)
            val state = DeepSeekPeriodPolicy.evaluate(instant)
            val peak = case.getValue("peak").let { if (it == JsonNull) null else it.jsonPrimitive.boolean }
            fun date(name: String): ZonedDateTime? = case.getValue(name).let { if (it == JsonNull) null else ZonedDateTime.parse(it.jsonPrimitive.content).withZoneSameInstant(PeakWindow.BEIJING) }
            assertEquals("$label: period", peak, state.isPeak)
            assertEquals("$label: next transition", date("next"), state.nextTransition)
            assertEquals("$label: segment start", date("start"), state.segmentStart)
            if (state.segmentStart == null || state.nextTransition == null) assertNull(state.progressPercent)
            else assertTrue("$label: progress", state.progressPercent!! in 0.0..100.0)
        }
    }

    @Test
    fun `all 2026 makeup weekends remain idle under DeepSeek official policy`() {
        for (text in listOf("2026-01-04", "2026-02-14", "2026-02-28", "2026-05-09", "2026-09-20", "2026-10-10")) {
            val date = LocalDate.parse(text)
            assertEquals(true, ChinaWorkCalendar.isChinaWorkday(date))
            assertEquals(false, ChinaWorkCalendar.isPeakDay(date))
        }
    }

    @Test
    fun `long holiday countdown and progress span the entire idle period`() {
        val now = ZonedDateTime.parse("2026-02-23T15:00:00+08:00")
        val state = DeepSeekPeriodPolicy.evaluate(now)
        assertEquals(false, state.isPeak)
        assertEquals(18 * 3600.0, state.remainingSeconds!!, 0.001)
        assertEquals("低谷时段剩余18:00:00", state.remainingText(false))
        val earlier = DeepSeekPeriodPolicy.evaluate(ZonedDateTime.parse("2026-02-23T14:59:59+08:00"))
        assertEquals(1.0, earlier.remainingSeconds!! - state.remainingSeconds!!, 0.001)
        assertTrue(state.progressPercent!! > earlier.progressPercent!!)
    }

    @Test
    fun `unknown calendar never fabricates a zero progress or peak status`() {
        val state = DeepSeekPeriodPolicy.evaluate(ZonedDateTime.parse("2027-01-04T10:00:00+08:00"))
        assertNull(state.isPeak)
        assertNull(state.remainingSeconds)
        assertNull(state.progressPercent)
        assertEquals("节假日日历待更新", state.remainingText(false))
        assertEquals("Holiday calendar needs an update", state.progressText(true))
    }
}
