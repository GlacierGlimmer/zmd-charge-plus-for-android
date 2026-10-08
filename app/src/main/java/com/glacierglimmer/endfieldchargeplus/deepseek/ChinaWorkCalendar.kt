package com.glacierglimmer.endfieldchargeplus.deepseek

import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** State Council holiday and makeup-workday notices; no weekday guesses for unknown years. */
object ChinaWorkCalendar {
    private data class YearCalendar(val holidays: Set<LocalDate>, val makeupWorkdays: Set<LocalDate>)

    private val years: Map<Int, YearCalendar> by lazy {
        runCatching {
            val stream = ChinaWorkCalendar::class.java.classLoader?.getResourceAsStream("china-holidays.json")
                ?: return@runCatching emptyMap<Int, YearCalendar>()
            val json = stream.bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
            json.getValue("years").jsonObject.map { (yearText, value) ->
                val year = yearText.toInt()
                val fields = value.jsonObject
                val holidays = buildSet {
                    for (range in fields.getValue("holidays").jsonArray) {
                        val ends = range.jsonPrimitive.content.split('/')
                        var day = LocalDate.parse(ends.first())
                        val end = LocalDate.parse(ends.last())
                        require(day.year == year && end.year == year && !end.isBefore(day))
                        while (!day.isAfter(end)) { add(day); day = day.plusDays(1) }
                    }
                }
                val workdays = fields.getValue("makeupWorkdays").jsonArray.map { LocalDate.parse(it.jsonPrimitive.content) }.toSet()
                require(workdays.all { it.year == year && it !in holidays })
                year to YearCalendar(holidays, workdays)
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    fun isChinaWorkday(day: LocalDate): Boolean? {
        val calendar = years[day.year] ?: return null
        if (day in calendar.makeupWorkdays) return true
        return day !in calendar.holidays && !isWeekend(day)
    }

    fun isPeakDay(day: LocalDate): Boolean? = if (isWeekend(day)) false else isChinaWorkday(day)

    private fun isWeekend(day: LocalDate): Boolean = day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY
}
