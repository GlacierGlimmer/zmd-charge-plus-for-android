package com.glacierglimmer.endfieldchargeplus.update

import com.glacierglimmer.endfieldchargeplus.network.HttpTransport
import com.glacierglimmer.endfieldchargeplus.network.UrlConnectionTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

enum class UpdateStatus { NO_REMOTE_VERSION, UNCOMPARABLE, UPDATE_AVAILABLE, UP_TO_DATE, LOCAL_NEWER, NETWORK_ERROR, TIMEOUT, ERROR }
data class UpdateResult(val currentVersion: String, val latestVersion: String? = null, val status: UpdateStatus,
    val detail: String = "") {
    val hasUpdate: Boolean get() = status == UpdateStatus.UPDATE_AVAILABLE
}
data class UpdateState(val checking: Boolean = false, val result: UpdateResult? = null, val promptVersion: String? = null)

/** Same release -> tag fallback and numeric Version comparison as the PC About page. */
object UpdateVersions {
    fun normalize(tag: String): String = tag.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
    private fun parse(tag: String): List<Int>? {
        val parts = normalize(tag).split('.')
        if (parts.size !in 2..4) return null
        val numbers = parts.map { part ->
            if (!part.matches(Regex("[0-9]+"))) return null
            part.toIntOrNull() ?: return null
        }
        return numbers + List(4 - numbers.size) { -1 }
    }
    fun compare(current: String, latest: String?): UpdateResult {
        val local = normalize(current)
        if (latest.isNullOrBlank()) return UpdateResult(local, status = UpdateStatus.NO_REMOTE_VERSION)
        val remote = normalize(latest)
        val a = parse(local); val b = parse(remote)
        if (a == null || b == null) return UpdateResult(local, remote, UpdateStatus.UNCOMPARABLE)
        val comparison = a.zip(b).map { (x,y) -> x.compareTo(y) }.firstOrNull { it != 0 } ?: 0
        return UpdateResult(local, remote, when {
            comparison < 0 -> UpdateStatus.UPDATE_AVAILABLE
            comparison == 0 -> UpdateStatus.UP_TO_DATE
            else -> UpdateStatus.LOCAL_NEWER
        })
    }
}

class GitHubUpdateClient(private val transport: HttpTransport = UrlConnectionTransport) {
    suspend fun check(current: String): UpdateResult = try {
        withTimeout(10_000) {
            val release = request("releases/latest", current)
            val tag = release?.let { Json.parseToJsonElement(it).jsonObject["tag_name"]?.jsonPrimitive?.contentOrNull }
            val latest = tag?.takeIf { it.isNotBlank() } ?: request("tags?per_page=1", current)?.let {
                Json.parseToJsonElement(it).jsonArray.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
            }
            UpdateVersions.compare(current, latest)
        }
    } catch (_: TimeoutCancellationException) {
        UpdateResult(UpdateVersions.normalize(current), status = UpdateStatus.TIMEOUT)
    } catch (_: SocketTimeoutException) {
        UpdateResult(UpdateVersions.normalize(current), status = UpdateStatus.TIMEOUT)
    } catch (cancelled: CancellationException) { throw cancelled
    } catch (error: IOException) {
        UpdateResult(UpdateVersions.normalize(current), status = UpdateStatus.NETWORK_ERROR, detail = error.message.orEmpty())
    } catch (_: Exception) {
        UpdateResult(UpdateVersions.normalize(current), status = UpdateStatus.ERROR)
    }

    private suspend fun request(path: String, current: String): String? {
        val response = transport.get("https://api.github.com/repos/$REPOSITORY/$path",
            mapOf("Accept" to "application/vnd.github+json", "User-Agent" to "EndfieldChargePlusAndroid/${UpdateVersions.normalize(current)}"),
            timeoutMs = 10_000, maxBytes = 512 * 1024)
        if (response.statusCode == 404) return null
        if (response.statusCode !in 200..299) throw IOException("HTTP ${response.statusCode}")
        return response.body
    }
    companion object {
        const val REPOSITORY = "GlacierGlimmer/zmd-charge-plus-for-android"
        const val RELEASES_URL = "https://github.com/$REPOSITORY/releases"
    }
}

/** One automatic check per application process; manual checks share the same result and lock. */
class UpdateChecker(private val scope: CoroutineScope, private val currentVersion: String,
    private val fetch: suspend (String) -> UpdateResult, private val notifyUpdate: (UpdateResult) -> Unit) {
    private val started = AtomicBoolean(false)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(UpdateState())
    val state = _state.asStateFlow()
    private var notifiedVersion: String? = null
    fun checkStartup() { if (started.compareAndSet(false, true)) checkManually() }
    fun checkManually() { scope.launch { check() } }
    suspend fun check() {
        if (!mutex.tryLock()) return
        try {
            _state.value = _state.value.copy(checking = true)
            val result = fetch(currentVersion)
            val freshUpdate = result.hasUpdate && result.latestVersion != notifiedVersion
            _state.value = UpdateState(result = result,
                promptVersion = if (freshUpdate) result.latestVersion else _state.value.promptVersion)
            if (freshUpdate) {
                notifiedVersion = result.latestVersion
                runCatching { notifyUpdate(result) }
            }
        } finally { _state.value = _state.value.copy(checking = false); mutex.unlock() }
    }
    fun dismissPrompt() { _state.value = _state.value.copy(promptVersion = null) }
}
