package com.glacierglimmer.endfieldchargeplus.deepseek

import java.net.URI
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Complete announced schedules plus known statutory dates through 2030. */
object ChinaWorkCalendar {
    private data class YearCalendar(val holidays: Set<LocalDate>, val makeupWorkdays: Set<LocalDate>, val complete: Boolean = true)
    private data class Annual(val holidays: Set<LocalDate>, val workdays: Set<LocalDate>)
    @Volatile private var years: Map<Int, YearCalendar> = loadEmbedded()

    private fun loadEmbedded(): Map<Int, YearCalendar> = runCatching {
        val stream = ChinaWorkCalendar::class.java.classLoader?.getResourceAsStream("china-holidays.json")
            ?: return@runCatching emptyMap<Int, YearCalendar>()
        val json = stream.bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()).jsonObject }
        json.getValue("years").jsonObject.map { (yearText, value) ->
            val year = yearText.toInt(); val fields = value.jsonObject
            val holidays = buildSet {
                for (range in fields.getValue("holidays").jsonArray) {
                    val ends = range.jsonPrimitive.content.split('/')
                    var day = LocalDate.parse(ends.first()); val end = LocalDate.parse(ends.last())
                    require(day.year == year && end.year == year && !end.isBefore(day))
                    while (!day.isAfter(end)) { add(day); day = day.plusDays(1) }
                }
            }
            val workdays = fields.getValue("makeupWorkdays").jsonArray.map { LocalDate.parse(it.jsonPrimitive.content) }.toSet()
            require(workdays.all { it.year == year && it !in holidays })
            year to YearCalendar(holidays, workdays, fields["coverage"]?.jsonPrimitive?.content != "statutory-only")
        }.toMap()
    }.getOrDefault(emptyMap())

    fun isChinaWorkday(day: LocalDate): Boolean? {
        val calendar = years[day.year] ?: return null
        if (day in calendar.holidays) return false
        if (day in calendar.makeupWorkdays) return true
        return if (calendar.complete) !isWeekend(day) else null
    }
    fun isPeakDay(day: LocalDate): Boolean? = if (isWeekend(day)) false else isChinaWorkday(day)
    private fun isWeekend(day: LocalDate) = day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY

    fun isValidAnnualJson(json: String, expectedYear: Int): Boolean = parseAnnual(json, expectedYear) != null

    @Synchronized fun tryInstallAnnualJson(json: String, expectedYear: Int): Boolean {
        val parsed = parseAnnual(json, expectedYear) ?: return false
        val updated = years.toMutableMap()
        updated[expectedYear] = YearCalendar(parsed.holidays.filter { it.year == expectedYear }.toSet(), parsed.workdays.filter { it.year == expectedYear }.toSet())
        val previous = expectedYear - 1
        updated[previous]?.let { prior ->
            val holidays = parsed.holidays.filter { it.year == previous }.toSet()
            val workdays = parsed.workdays.filter { it.year == previous }.toSet()
            updated[previous] = prior.copy(holidays = prior.holidays + holidays - workdays, makeupWorkdays = prior.makeupWorkdays + workdays - holidays)
        }
        years = updated.toMap()
        return true
    }

    private fun parseAnnual(json: String, expectedYear: Int): Annual? = runCatching {
        require(json.length <= 65536)
        val root = Json.parseToJsonElement(json).jsonObject
        require(root.getValue("year").jsonPrimitive.int == expectedYear)
        val papers = root.getValue("papers").jsonArray
        require(papers.isNotEmpty() && papers.all { paper ->
            val uri = URI(paper.jsonPrimitive.content); val host = uri.host?.lowercase().orEmpty()
            uri.scheme == "https" && (host == "gov.cn" || host.endsWith(".gov.cn"))
        })
        val holidays = mutableSetOf<LocalDate>(); val workdays = mutableSetOf<LocalDate>(); val names = mutableSetOf<String>()
        for (item in root.getValue("days").jsonArray) {
            val fields = item.jsonObject; val date = LocalDate.parse(fields.getValue("date").jsonPrimitive.content)
            require(date.year == expectedYear || (date.year == expectedYear - 1 && date.monthValue == 12))
            require(date !in holidays && date !in workdays)
            if (fields.getValue("isOffDay").jsonPrimitive.boolean) {
                holidays += date
                if (date.year == expectedYear) names += fields.getValue("name").jsonPrimitive.content
            } else workdays += date
        }
        require(holidays.size >= 13 && listOf("元旦", "春节", "清明节", "劳动节", "端午节", "中秋节", "国庆节").all { holiday -> names.any { holiday in it } })
        Annual(holidays, workdays)
    }.getOrNull()
}
