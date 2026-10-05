package com.glacierglimmer.endfieldchargeplus.network

import android.content.Context
import com.glacierglimmer.endfieldchargeplus.core.model.CustomHttpSource
import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import com.glacierglimmer.endfieldchargeplus.core.model.SamplingTier
import com.glacierglimmer.endfieldchargeplus.core.model.UnavailableReason
import com.glacierglimmer.endfieldchargeplus.core.metrics.Variables
import com.glacierglimmer.endfieldchargeplus.metrics.MetricCollector
import com.glacierglimmer.endfieldchargeplus.metrics.MetricEnvironment
import kotlinx.serialization.json.JsonElement

/**
 * Publishes every enabled custom HTTP/JSON source as `custom.<source>.<field>` variables.
 *
 * Semantics ported from the desktop editions, with the Android hardening the audit asks for:
 *  * the refresh cadence is clamped to at least 5 s ([HttpSourceMapping.refreshIntervalMs]);
 *  * a failing source backs off exponentially up to ten times its interval
 *    ([HttpSourceMapping.backoffDelayMs]) instead of being requested on every tick;
 *  * the transport itself uses HTTPS-only plus a 512 KB cap and explicit timeouts
 *    ([DefaultHttpJsonClient]);
 *  * when a request fails the affected variables become `Unavailable(NETWORK_ERROR)` — the stale
 *    cached body is dropped, so the HUD can never show a value that is no longer being refreshed;
 *  * header values may reference environment variables as `${NAME}` or the desktop `${env:NAME}`.
 *
 * Each field is written twice: the canonical `custom.` name from [Variables.httpVariable] and the
 * legacy `http.` mirror, so schemes written for the desktop editions keep rendering.
 */
class HttpJsonCollector(
    private val context: Context,
    private val environment: MetricEnvironment,
    private val client: DefaultHttpJsonClient,
) : MetricCollector {

    override val id: String = "http_json"

    override val tier: SamplingTier = SamplingTier.SLOW

    private val states = LinkedHashMap<String, SourceState>()

    override suspend fun collect(into: MutableMap<String, MetricValue>) {
        val sources = environment.config().customHud.httpSources
            .filter { it.enabled && it.url.isNotBlank() }
        val online = ConnectivityStatus.isOnline(context)

        for (source in sources) {
            val state = states.getOrPut(cacheKey(source)) { SourceState() }
            refreshIfDue(source, state, online)
            publish(source, state, into)
        }

        states.keys.retainAll(sources.map { cacheKey(it) }.toSet())
    }

    private suspend fun refreshIfDue(source: CustomHttpSource, state: SourceState, online: Boolean) {
        val now = System.currentTimeMillis()
        if (now < state.nextAttemptAtMs) return

        val intervalMs = HttpSourceMapping.refreshIntervalMs(source.refreshSeconds)
        if (!online) {
            recordFailure(state, intervalMs, now)
            return
        }

        val headers = source.headers.entries.associate { (name, value) ->
            name to HttpSourceMapping.expandEnvironment(value)
        }
        val element = client.getJson(source.url, headers, REQUEST_TIMEOUT_MS).getOrNull()
        if (element == null) {
            recordFailure(state, intervalMs, now)
        } else {
            state.element = element
            state.failureCount = 0
            state.nextAttemptAtMs = now + intervalMs
        }
    }

    private fun recordFailure(state: SourceState, intervalMs: Long, now: Long) {
        state.element = null
        state.failureCount += 1
        state.nextAttemptAtMs = now + HttpSourceMapping.backoffDelayMs(intervalMs, state.failureCount)
    }

    private fun publish(
        source: CustomHttpSource,
        state: SourceState,
        into: MutableMap<String, MetricValue>,
    ) {
        val element = state.element
        for (field in source.fields) {
            val canonical = Variables.httpVariable(source.name, field.variable)
            val legacy = Variables.HTTP_LEGACY_PREFIX + canonical.removePrefix(Variables.HTTP_PREFIX)
            val value = if (element == null) {
                MetricValue.Unavailable(UnavailableReason.NETWORK_ERROR, "source_request_failed")
            } else {
                HttpSourceMapping.toMetricValue(JsonPath.extract(element, field.jsonPath))
            }
            into[canonical] = value
            into[legacy] = value
        }
    }

    private fun cacheKey(source: CustomHttpSource): String = source.name + "|" + source.url

    private class SourceState {
        var element: JsonElement? = null
        var nextAttemptAtMs: Long = 0L
        var failureCount: Int = 0
    }

    companion object {
        /** Desktop parity: the shared desktop `HttpClient` uses an 8 s timeout. */
        const val REQUEST_TIMEOUT_MS = 8_000
    }
}
