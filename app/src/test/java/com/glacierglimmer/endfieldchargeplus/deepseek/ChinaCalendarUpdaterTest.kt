package com.glacierglimmer.endfieldchargeplus.deepseek

import java.nio.file.Files
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChinaCalendarUpdaterTest {
    private fun fixture() = javaClass.classLoader!!.getResourceAsStream("holiday-annual-fixture.json")!!.bufferedReader().use { it.readText() }

    @Test fun `calendar rejects placeholders incorrect years and invalid sources`() {
        val annual = fixture()
        assertFalse(ChinaWorkCalendar.isValidAnnualJson(annual, 2041))
        assertFalse(ChinaWorkCalendar.tryInstallAnnualJson("{\"year\":2040,\"papers\":[],\"days\":[]}", 2040))
        assertFalse(ChinaWorkCalendar.tryInstallAnnualJson(annual.replace("www.gov.cn", "gov.cn.example.com"), 2040))
        assertFalse(ChinaWorkCalendar.tryInstallAnnualJson("{broken", 2040))
        assertFalse(ChinaWorkCalendar.tryInstallAnnualJson(annual.replace("国庆节", "Other"), 2040))
    }

    @Test fun `annual updates cache work offline throttle and refresh on Beijing rollover`() = runBlocking {
        val folder = Files.createTempDirectory("ecp-calendar-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val annual = fixture(); var calls = 0
            val updater = ChinaCalendarUpdater(folder) { year -> calls++; if (year == 2040) annual else null }
            val now = ZonedDateTime.parse("2040-12-31T12:00:00+08:00").toInstant().toEpochMilli()
            updater.requestRefresh(scope, now)!!.join()
            assertEquals(3, calls)
            assertTrue(folder.resolve("2040.json").isFile)
            assertEquals(true, ChinaWorkCalendar.isChinaWorkday(LocalDate.parse("2040-01-04")))
            assertEquals(false, ChinaWorkCalendar.isPeakDay(LocalDate.parse("2040-01-02")))
            updater.requestRefresh(scope, now + 3600_000)!!.join()
            assertEquals(3, calls)
            updater.requestRefresh(scope, now + 13 * 3600_000L)!!.join()
            assertEquals(6, calls)
            assertTrue(ChinaWorkCalendar.tryInstallAnnualJson(annual.replace("2040-01-03", "2040-01-04"), 2040))
            val offline = ChinaCalendarUpdater(folder) { throw java.io.IOException("offline") }
            offline.requestRefresh(scope, now)!!.join()
            assertEquals(true, ChinaWorkCalendar.isChinaWorkday(LocalDate.parse("2040-01-04")))
            assertEquals(annual, folder.resolve("2040.json").readText())
        } finally { scope.cancel(); folder.deleteRecursively() }
    }

    @Test fun `statutory holidays remain idle offline through 2030`() {
        for (year in 2027..2030) for (date in listOf("01-01", "05-01", "05-02", "10-01", "10-02", "10-03"))
            assertEquals(false, ChinaWorkCalendar.isPeakDay(LocalDate.parse("$year-$date")))
        for (date in listOf("2027-02-05", "2027-06-09", "2027-09-15", "2028-01-25", "2028-05-28", "2028-10-03", "2029-02-12", "2029-06-16", "2029-09-22", "2030-02-02", "2030-06-05", "2030-09-12"))
            assertEquals(false, ChinaWorkCalendar.isPeakDay(LocalDate.parse(date)))
        assertNull(ChinaWorkCalendar.isChinaWorkday(LocalDate.parse("2030-01-04")))
    }
}
