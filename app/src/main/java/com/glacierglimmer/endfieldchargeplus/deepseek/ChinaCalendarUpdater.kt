package com.glacierglimmer.endfieldchargeplus.deepseek

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Anonymous public calendar updates, independent of DeepSeek balance polling. */
class ChinaCalendarUpdater(private val directory: File, private val fetchAnnual: (Int) -> String? = ::download) {
    private var requestedYear = 0
    private var nextAttempt = 0L
    private var pending: Job? = null

    @Synchronized fun requestRefresh(scope: CoroutineScope, nowMs: Long): Job? {
        val year = Instant.ofEpochMilli(nowMs).atZone(PeakWindow.BEIJING).year
        if (pending?.isActive == true || (year == requestedYear && nowMs < nextAttempt)) return pending
        requestedYear = year; nextAttempt = nowMs + 24 * 60 * 60 * 1000L
        return scope.launch(Dispatchers.IO) { refresh(year) }.also { pending = it }
    }

    private fun refresh(year: Int) {
        val targets = listOf(year - 1, year, year + 1)
        for (target in targets) runCatching {
            val file = File(directory, "$target.json")
            if (file.isFile && file.length() <= MAX_BYTES) ChinaWorkCalendar.tryInstallAnnualJson(file.readText(), target)
        }
        for (target in targets) {
            var temporary: File? = null
            try {
                val json = fetchAnnual(target) ?: continue
                if (json.toByteArray(Charsets.UTF_8).size > MAX_BYTES || !ChinaWorkCalendar.tryInstallAnnualJson(json, target)) continue
                if (!directory.isDirectory && !directory.mkdirs()) continue
                temporary = File.createTempFile("annual-", ".json", directory)
                temporary.writeText(json)
                temporary.renameTo(File(directory, "$target.json"))
            } catch (_: Exception) {
                // Keep the embedded/cached calendar on network or storage failure.
            } finally { temporary?.delete() }
        }
    }

    companion object {
        private const val MAX_BYTES = 65536
        private fun download(year: Int): String? {
            for (prefix in listOf("https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/", "https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/")) {
                val connection = URL("$prefix$year.json").openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 6000; connection.readTimeout = 6000
                    if (connection.responseCode != 200 || connection.contentLengthLong > MAX_BYTES) continue
                    val bytes = connection.inputStream.use { it.readBytesBounded() }
                    val json = bytes.toString(Charsets.UTF_8)
                    if (ChinaWorkCalendar.isValidAnnualJson(json, year)) return json
                } catch (_: Exception) { } finally { connection.disconnect() }
            }
            return null
        }
        private fun java.io.InputStream.readBytesBounded(): ByteArray {
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
            while (true) {
                val count = read(buffer); if (count < 0) break
                require(output.size() + count <= MAX_BYTES)
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }
}
