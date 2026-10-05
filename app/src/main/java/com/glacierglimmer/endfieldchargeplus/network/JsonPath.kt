package com.glacierglimmer.endfieldchargeplus.network

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Minimal JSON path reader with the same grammar as the desktop editions
 * (`VariableHub.TryJsonPath`).
 *
 * Supported forms:
 *  * dotted property paths: `data.cpu`;
 *  * array indices, including several in a row: `players[0].name`, `a[0][1]`;
 *  * the optional root markers `$` and `.`: `$`, `.`, `$.a.b` and `a.b` are equivalent.
 *
 * Deliberately unsupported (exactly like the desktop editions): wildcards, recursive descent,
 * slices, filters, negative indices and quoted keys containing a dot.
 *
 * Every method is total: a missing node, a type mismatch or a malformed path yields `null` and
 * never throws, because a user-supplied path must not be able to break the sampling loop.
 */
object JsonPath {

    /**
     * Returns the node selected by [path], or `null` when the path is missing or does not apply to
     * the shape of [root]. The JSON literal `null` is returned as [JsonNull] (not as a Kotlin
     * `null`) so callers can distinguish "absent" from "explicitly null".
     */
    fun extract(root: JsonElement, path: String): JsonElement? {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || trimmed == "$" || trimmed == ".") return root

        val body = trimmed.trimStart('$').trimStart('.')
        var current: JsonElement = root
        for (part in body.split('.')) {
            if (part.isEmpty()) continue

            var bracket = part.indexOf('[')
            val property = if (bracket >= 0) part.substring(0, bracket) else part
            if (property.isNotEmpty()) {
                val obj = current as? JsonObject ?: return null
                current = obj[property] ?: return null
            }

            while (bracket >= 0) {
                val end = part.indexOf(']', bracket + 1)
                if (end < 0) return null
                val rawIndex = part.substring(bracket + 1, end)
                val index = rawIndex.toIntOrNull() ?: return null
                val array = current as? JsonArray ?: return null
                if (index < 0 || index >= array.size) return null
                current = array[index]
                bracket = part.indexOf('[', end + 1)
            }
        }
        return current
    }

    /** The textual form of the node at [path], or `null` when it is absent or JSON `null`. */
    fun extractString(root: JsonElement, path: String): String? =
        when (val element = extract(root, path)) {
            null, JsonNull -> null
            is JsonPrimitive -> element.content
            else -> element.toString()
        }

    /** The numeric form of the node at [path], or `null` when it is absent or not numeric. */
    fun extractDouble(root: JsonElement, path: String): Double? =
        when (val element = extract(root, path)) {
            null, JsonNull -> null
            is JsonPrimitive -> element.doubleOrNull ?: element.content.toDoubleOrNull()
            else -> null
        }
}
