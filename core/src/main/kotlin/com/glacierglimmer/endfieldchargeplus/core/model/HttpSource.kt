package com.glacierglimmer.endfieldchargeplus.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A single JSON path extracted from a custom HTTP source and published as a variable. */
@Serializable
data class HttpFieldMapping(
    @SerialName("Variable") val variable: String = "value",
    @SerialName("JsonPath") val jsonPath: String = "",
)

/**
 * A user-defined HTTP/JSON data source. Mirrors `CustomHttpSource` from the desktop editions so
 * that imported configurations behave identically on Android.
 */
@Serializable
data class CustomHttpSource(
    @SerialName("Name") val name: String = "custom",
    @SerialName("Enabled") val enabled: Boolean = true,
    @SerialName("Url") val url: String = "",
    @SerialName("RefreshSeconds") val refreshSeconds: Int = 60,
    @SerialName("Headers") val headers: Map<String, String> = emptyMap(),
    @SerialName("Fields") val fields: List<HttpFieldMapping> = emptyList(),
)
