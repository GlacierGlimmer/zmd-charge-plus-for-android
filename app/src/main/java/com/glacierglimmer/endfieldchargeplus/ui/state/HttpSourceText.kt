package com.glacierglimmer.endfieldchargeplus.ui.state

import com.glacierglimmer.endfieldchargeplus.core.model.HttpFieldMapping

/**
 * Text form of the HTTP/JSON source editor.
 *
 * Headers are edited as `Key: Value` lines and field mappings as `variable = jsonPath` lines — the
 * same information the desktop edition keeps in JSON, in a form that is comfortable on a phone.
 * Both directions are pure functions so they are covered by JVM tests.
 */
object HttpSourceText {

    /** Parses `Key: Value` lines; blank lines and lines without a colon are ignored. */
    fun parseHeaders(text: String?): Map<String, String> {
        if (text.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val separator = trimmed.indexOf(':')
            if (separator <= 0) return@forEach
            val key = trimmed.substring(0, separator).trim()
            val value = trimmed.substring(separator + 1).trim()
            if (key.isNotEmpty()) result[key] = value
        }
        return result
    }

    /** Formats headers as stable, sorted `Key: Value` lines. */
    fun formatHeaders(headers: Map<String, String>): String =
        headers.entries.sortedBy { it.key.lowercase() }.joinToString("\n") { "${it.key}: ${it.value}" }

    /** Parses `variable = jsonPath` lines; `variable: jsonPath` is accepted as well. */
    fun parseFields(text: String?): List<HttpFieldMapping> {
        if (text.isNullOrBlank()) return emptyList()
        val result = mutableListOf<HttpFieldMapping>()
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val separator = listOf(trimmed.indexOf('='), trimmed.indexOf(':'))
                .filter { it > 0 }
                .minOrNull() ?: return@forEach
            val variable = trimmed.substring(0, separator).trim()
            val path = trimmed.substring(separator + 1).trim()
            if (variable.isNotEmpty()) result += HttpFieldMapping(variable = variable, jsonPath = path)
        }
        return result
    }

    /** Formats field mappings as `variable = jsonPath` lines. */
    fun formatFields(fields: List<HttpFieldMapping>): String =
        fields.joinToString("\n") { "${it.variable} = ${it.jsonPath}" }

    /** Rejects lines that would silently produce nothing, so the editor can flag them. */
    fun invalidFieldLines(text: String?): List<Int> {
        if (text.isNullOrBlank()) return emptyList()
        return text.lineSequence().mapIndexedNotNull { index, line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapIndexedNotNull null
            if (parseFields(trimmed).isEmpty()) index + 1 else null
        }.toList()
    }

    /**
     * A pragmatic URL check: the collector performs the real request, so the editor only rejects what
     * can never work (`http`/`https`, a host, no spaces).
     */
    fun isHttpUrl(url: String): Boolean {
        val value = url.trim()
        if (!value.startsWith("http://") && !value.startsWith("https://")) return false
        if (value.any { it.isWhitespace() }) return false
        val host = value.substringAfter("://").substringBefore('/').substringBefore('?')
        return host.isNotEmpty() && host.contains('.')
    }

    /** Lower-case, underscore sanitized variable segment, mirroring the engine's naming rule. */
    fun sanitizeSegment(value: String): String =
        value.trim().lowercase().replace(' ', '_').replace('-', '_')
}
