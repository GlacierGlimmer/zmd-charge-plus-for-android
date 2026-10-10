package com.glacierglimmer.endfieldchargeplus.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class RootStatus { DISABLED, CHECKING, GRANTED, UNAVAILABLE }

/** A reusable su shell; bounded reads and timeouts keep a missing/denied root prompt off the UI. */
internal interface RootCommandShell {
    fun run(command: String, timeoutMs: Long): String?
    fun close()
    fun isAlive(): Boolean
}
internal class RootReadShell : RootCommandShell {
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "ecp-root-reader").apply { isDaemon = true } }
    @Volatile private var process: Process? = null
    private var input: BufferedWriter? = null
    private var output: BufferedReader? = null
    private var serial = 0L
    @Synchronized override fun run(command: String, timeoutMs: Long): String? {
        val future = executor.submit<String?> {
            if (process?.isAlive != true) {
                val created = ProcessBuilder("su").redirectErrorStream(true).start()
                process = created
                input = created.outputStream.bufferedWriter()
                output = created.inputStream.bufferedReader()
            }
            val marker = "__ECP_READ_END_${++serial}__"
            input!!.write("{ $command; }; printf '\\n$marker:%s\\n' ${'$'}?\n")
            input!!.flush()
            val text = StringBuilder()
            while (true) {
                val line = output!!.readLine() ?: return@submit null
                if (line.startsWith("$marker:")) return@submit if (line == "$marker:0") text.toString().trimEnd() else null
                text.append(line).append('\n')
                if (text.length > 1024 * 1024) { close(); return@submit null }
            }
            @Suppress("UNREACHABLE_CODE") null
        }
        return try { future.get(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: Exception) {
            close(); future.cancel(true); null
        }
    }
    override fun close() { process?.destroyForcibly(); process = null }
    override fun isAlive() = process?.isAlive == true
}

class RootAccessManager internal constructor(private val shell: RootCommandShell) {
    constructor() : this(RootReadShell())
    private val generation = AtomicLong()
    private val _status = MutableStateFlow(RootStatus.DISABLED)
    val status = _status.asStateFlow()
    val granted: Boolean get() = _status.value == RootStatus.GRANTED
    suspend fun configure(enabled: Boolean) {
        val request = generation.incrementAndGet()
        if (!enabled) { _status.value = RootStatus.DISABLED; shell.close(); return }
        _status.value = RootStatus.CHECKING
        val uid = withContext(Dispatchers.IO) { shell.run("id -u", 12_000)?.trim() }
        if (request != generation.get()) return
        _status.value = if (uid == "0") RootStatus.GRANTED else RootStatus.UNAVAILABLE
        if (!granted) shell.close()
    }
    /** Commands are constructed solely by KernelReader from its kernel path allowlist. */
    internal fun read(command: String): String? {
        if (!granted) return null
        val result = shell.run(command, 800)
        if (!shell.isAlive()) _status.value = RootStatus.UNAVAILABLE
        return result
    }
}
