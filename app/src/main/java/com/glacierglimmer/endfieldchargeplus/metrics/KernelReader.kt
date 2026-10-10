package com.glacierglimmer.endfieldchargeplus.metrics

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Kernel-only read interface, also used by fixture tests of the actual collectors. */
interface KernelReader {
    fun readText(path: String): String?
    fun listNames(path: String): List<String>
    fun readLong(path: String): Long? = readText(path)?.trim()?.toLongOrNull()
}

object FileKernelReader : KernelReader {
    override fun readText(path: String): String? = runCatching {
        File(path).inputStream().bufferedReader().use { reader ->
            val chars = CharArray(4096)
            val text = StringBuilder()
            while (true) {
                val count = reader.read(chars)
                if (count < 0) break
                text.append(chars, 0, count)
                if (text.length > 1024 * 1024) return@use null
            }
            text.toString().takeIf { it.isNotBlank() }
        }
    }.getOrNull()
    override fun listNames(path: String): List<String> = runCatching { File(path).list()?.toList().orEmpty() }.getOrDefault(emptyList())
}

/** Root only supplements denied reads; missing nodes have a backoff, not a process per HUD frame. */
class RootKernelReader(private val root: com.glacierglimmer.endfieldchargeplus.root.RootAccessManager,
    private val direct: KernelReader = FileKernelReader,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) : KernelReader {
    private val missingUntil = ConcurrentHashMap<String, Long>()
    fun invalidate() { missingUntil.clear() }
    override fun readText(path: String): String? = direct.readText(path) ?: rootRead("cat", path)
    override fun listNames(path: String): List<String> = direct.listNames(path).takeIf { it.isNotEmpty() }
        ?: rootRead("ls -1", path)?.lineSequence()?.filter { it.matches(Regex("[A-Za-z0-9_.:-]+")) }?.take(1024)?.toList().orEmpty()
    private fun rootRead(command: String, path: String): String? {
        if (!isKernelPath(path) || !root.granted || (missingUntil["$command:$path"] ?: 0L) > nowMs()) return null
        val result = root.read("$command -- '$path'")?.takeIf { it.isNotBlank() }
        if (result == null) missingUntil["$command:$path"] = nowMs() + 60_000
        return result
    }
    companion object {
        fun isKernelPath(path: String): Boolean = path.matches(Regex("/[A-Za-z0-9_./:-]+")) &&
            path.split('/').none { it == ".." } &&
            (path in setOf("/proc/stat", "/proc/meminfo", "/proc/cpuinfo") ||
                listOf("/sys/class/thermal/", "/sys/class/devfreq/", "/sys/class/kgsl/", "/sys/class/misc/mali0/",
                    "/sys/kernel/gpu/", "/sys/kernel/kgsl/", "/sys/devices/system/cpu/").any { path.startsWith(it) } ||
                path in setOf("/sys/class/thermal", "/sys/class/devfreq", "/sys/devices/system/cpu"))
    }
}
