package com.glacierglimmer.endfieldchargeplus.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-app diagnostics log.
 *
 * Two hard rules come from the desktop editions and are enforced here:
 *  * a DeepSeek API key (or any other secret) is never written in clear text — [redact] is applied
 *    to every message before it is stored;
 *  * the log is bounded, so a long running HUD can never fill the device storage.
 */
object AppLog {

    private const val MAX_ENTRIES = 600
    private const val MAX_FILE_BYTES = 512L * 1024L
    private const val MAX_FILES = 3
    private const val LOGCAT_TAG = "ECP"

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(
        val timestampMs: Long,
        val level: Level,
        val tag: String,
        val message: String,
    ) {
        fun format(): String {
            val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestampMs))
            return "$time ${level.name.first()} $tag: $message"
        }
    }

    private val entries = CopyOnWriteArrayList<Entry>()

    @Volatile
    private var verbose: Boolean = false

    @Volatile
    private var logDirectory: File? = null

    @Volatile
    private var installed = false

    /** Secrets registered for redaction; only their tail is kept to keep messages debuggable. */
    private val secrets = CopyOnWriteArrayList<String>()

    fun install(context: Context, verboseProvider: () -> Boolean) {
        if (installed) return
        installed = true
        logDirectory = File(context.filesDir, "logs").apply { mkdirs() }
        verbose = verboseProvider()
        i("AppLog", "Diagnostics log started (verbose=$verbose)")
    }

    fun setVerbose(enabled: Boolean) {
        verbose = enabled
    }

    fun isVerbose(): Boolean = verbose

    /** Registers a secret so that any occurrence of it is masked in later log lines. */
    fun registerSecret(secret: String?) {
        if (secret.isNullOrEmpty() || secret.length < 8) return
        if (!secrets.contains(secret)) secrets.add(secret)
    }

    fun clearSecrets() {
        secrets.clear()
    }

    fun redact(message: String?): String {
        var value = message ?: return ""
        for (secret in secrets) {
            value = value.replace(secret, mask(secret))
        }
        return value
    }

    /** Masks everything but the last four characters: `sk-abcdef123456` -> `****3456`. */
    fun mask(secret: String): String =
        if (secret.length <= 4) "****" else "****" + secret.takeLast(4)

    fun d(tag: String, message: String) {
        if (!verbose) return
        append(Level.DEBUG, tag, message)
    }

    fun i(tag: String, message: String) = append(Level.INFO, tag, message)

    fun w(tag: String, message: String, throwable: Throwable? = null) =
        append(Level.WARN, tag, describe(message, throwable))

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        append(Level.ERROR, tag, describe(message, throwable))

    fun entries(): List<Entry> = entries.toList()

    fun logFile(): File? = logDirectory?.let { File(it, "ecp.log") }

    fun clear() {
        entries.clear()
        runCatching { logFile()?.delete() }
    }

    /** Plain-text export used by the diagnostics page and the share sheet. */
    fun exportText(): String = buildString {
        appendLine("Endfield Charge Plus for Android — diagnostics log")
        appendLine("generated=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine()
        entries().forEach { appendLine(it.format()) }
    }

    private fun describe(message: String, throwable: Throwable?): String =
        if (throwable == null) message else "$message: ${throwable.javaClass.simpleName}: ${throwable.message}"

    private fun append(level: Level, tag: String, message: String) {
        val safe = redact(message)
        val entry = Entry(System.currentTimeMillis(), level, tag, safe)
        entries.add(entry)
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
        when (level) {
            Level.DEBUG -> Log.d(LOGCAT_TAG, "[$tag] $safe")
            Level.INFO -> Log.i(LOGCAT_TAG, "[$tag] $safe")
            Level.WARN -> Log.w(LOGCAT_TAG, "[$tag] $safe")
            Level.ERROR -> Log.e(LOGCAT_TAG, "[$tag] $safe")
        }
        writeToFile(entry)
    }

    private fun writeToFile(entry: Entry) {
        val file = logFile() ?: return
        runCatching {
            if (file.length() > MAX_FILE_BYTES) rotate()
            file.appendText(entry.format() + "\n")
        }
    }

    private fun rotate() {
        val directory = logDirectory ?: return
        runCatching {
            for (index in MAX_FILES - 1 downTo 1) {
                val source = File(directory, "ecp.$index.log")
                if (source.exists()) source.renameTo(File(directory, "ecp.${index + 1}.log"))
            }
            val current = File(directory, "ecp.log")
            if (current.exists()) current.renameTo(File(directory, "ecp.1.log"))
        }
    }
}
