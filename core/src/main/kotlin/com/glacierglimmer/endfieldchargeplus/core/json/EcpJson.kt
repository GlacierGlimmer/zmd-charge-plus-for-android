package com.glacierglimmer.endfieldchargeplus.core.json

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Raised for configuration input that cannot be understood.
 *
 * The message is written for the settings UI (it is shown to the user in a status line), so it
 * always names what was wrong instead of leaking a raw stack trace.
 */
class ConfigFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The JSON contract of the configuration and profile import/export features.
 *
 * Two problems have to be solved at once:
 *
 *  1. kotlinx.serialization is strict by default (case sensitive, no unknown keys, enums must be
 *     spelled exactly), while the Windows/Linux/macOS editions write `System.Text.Json` with
 *     `PropertyNameCaseInsensitive = true` and **numeric** enums.
 *  2. Malformed, truncated or hand-edited files must never crash the application.
 *
 * [parse] therefore returns a *canonicalized* tree: every known key is renamed to the exact
 * `@SerialName` used by the model (accepting PascalCase, camelCase, snake_case and kebab-case),
 * numeric enums are turned back into their wire words (`PositionMode: 0` -> `"Preset"`), string
 * encoded numbers/booleans are repaired, and full-line `//` comments (the desktop HTTP-source
 * editor writes them) are stripped first. [decode] then feeds that tree to the strict serializer.
 *
 * Nothing here logs, throws on malformed input, or depends on Android: it is pure JVM code so it
 * can be unit tested and reused by the desktop editions.
 */
@OptIn(ExperimentalSerializationApi::class)
object EcpJson {

    /** Human-readable output used by every export path. */
    val pretty: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
        prettyPrintIndent = "  "
    }

    /** Compact output for DataStore, where one line keeps the preferences file small. */
    val compact: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
    }

    fun <T> encode(serializer: SerializationStrategy<T>, value: T, pretty: Boolean = true): String =
        if (pretty) this.pretty.encodeToString(serializer, value) else compact.encodeToString(serializer, value)

    /** Parses [text] leniently and decodes it, never throwing. */
    fun <T> decode(serializer: DeserializationStrategy<T>, text: String): Result<T> =
        parse(text).fold(
            onSuccess = { element ->
                try {
                    Result.success(pretty.decodeFromJsonElement(serializer, element))
                } catch (t: Throwable) {
                    Result.failure(
                        ConfigFormatException(
                            "Configuration JSON does not match the expected schema: ${describe(t)}",
                            t,
                        ),
                    )
                }
            },
            onFailure = { Result.failure(it) },
        )

    /**
     * Lenient parse + canonicalization. The returned element is safe to feed to any of the model
     * serializers; failures are a [ConfigFormatException] with a user-presentable message.
     */
    fun parse(text: String): Result<JsonElement> =
        try {
            val cleaned = normalizeText(text)
            if (cleaned.isBlank()) {
                Result.failure(ConfigFormatException("Configuration JSON is empty."))
            } else {
                Result.success(canonicalize(compact.parseToJsonElement(cleaned)))
            }
        } catch (t: Throwable) {
            Result.failure(ConfigFormatException("Configuration JSON could not be parsed: ${describe(t)}", t))
        }

    /** Convenience wrapper for callers that need an object (a bare array is rejected clearly). */
    fun parseObject(text: String): Result<JsonObject> =
        parse(text).mapCatching { element ->
            element as? JsonObject
                ?: throw ConfigFormatException("Expected a JSON object but found a ${kindOf(element)}.")
        }

    /**
     * Tolerant word matching used by the normalizers: compares ignoring case, `_`, `-` and spaces,
     * so `"Top_Center"`, `"top-center"` and `"TopCenter"` all match the wire word `"TopCenter"`.
     */
    fun matchWord(raw: String?, allowed: Collection<String>): String? {
        val candidate = normalizeWord(raw) ?: return null
        return allowed.firstOrNull { normalizeWord(it) == candidate }
    }

    /** `null` for blank input, otherwise the comparison form (lowercase, separators removed). */
    fun normalizeWord(raw: String?): String? =
        raw?.trim()?.lowercase()?.replace("_", "")?.replace("-", "")?.replace(" ", "")?.takeIf { it.isNotEmpty() }

    // ------------------------------------------------------------------------------------------
    // internals
    // ------------------------------------------------------------------------------------------

    /** Strips a UTF-8 BOM and the full-line `//` comments the desktop HTTP editor writes. */
    private fun normalizeText(text: String): String {
        val withoutBom = text.removePrefix("\uFEFF")
        return withoutBom.lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
    }

    private fun describe(t: Throwable): String {
        val message = t.message?.trim().orEmpty()
        return if (message.isEmpty()) t.javaClass.simpleName else "${t.javaClass.simpleName}: $message"
    }

    private fun kindOf(element: JsonElement): String = when (element) {
        is JsonObject -> "object"
        is JsonArray -> "array"
        is JsonNull -> "null"
        is JsonPrimitive -> if (element.isString) "string" else "literal"
    }

    private fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries.associate { (key, value) ->
                val canonical = CANONICAL_KEYS[EcpJson.normalizeWord(key)] ?: key
                canonical to coerceValue(canonical, canonicalize(value))
            },
        )

        is JsonArray -> JsonArray(element.map { canonicalize(it) })
        else -> element
    }

    private fun coerceValue(name: String, value: JsonElement): JsonElement {
        if (value is JsonNull) return value
        val primitive = value as? JsonPrimitive ?: return value
        if (name in ENUM_WORDS) {
            toEnumWord(name, primitive)?.let { return it }
        }
        if (name in BOOLEAN_KEYS) {
            toBoolean(primitive)?.let { return it }
        }
        // Only string-encoded numbers are repaired; real JSON numbers are left untouched so a
        // deliberately huge value is not silently rounded by a String round-trip.
        if (primitive.isString) {
            when (name) {
                in INT_KEYS -> primitive.content.trim().toIntOrNull()?.let { return JsonPrimitive(it) }
                in LONG_KEYS -> primitive.content.trim().toLongOrNull()?.let { return JsonPrimitive(it) }
                in DOUBLE_KEYS -> primitive.content.trim().toDoubleOrNull()?.let { return JsonPrimitive(it) }
            }
        }
        return value
    }

    private fun toEnumWord(name: String, primitive: JsonPrimitive): JsonPrimitive? {
        val words = ENUM_WORDS[name] ?: return null
        if (!primitive.isString) {
            val index = primitive.content.trim().toIntOrNull() ?: return null
            val word = words.getOrNull(index) ?: words.first()
            return JsonPrimitive(word)
        }
        val raw = primitive.content
        // A numeric string is also accepted (`"PositionMode": "1"`).
        raw.trim().toIntOrNull()?.let { index ->
            val word = words.getOrNull(index) ?: words.first()
            return JsonPrimitive(word)
        }
        val matched = matchWord(raw, words) ?: return null
        return JsonPrimitive(matched)
    }

    private fun toBoolean(primitive: JsonPrimitive): JsonPrimitive? {
        val text = primitive.content.trim()
        if (primitive.isString) {
            return when (text.lowercase()) {
                "true", "1", "yes", "on", "y", "是" -> JsonPrimitive(true)
                "false", "0", "no", "off", "n", "否" -> JsonPrimitive(false)
                else -> null
            }
        }
        return when (text) {
            "1" -> JsonPrimitive(true)
            "0" -> JsonPrimitive(false)
            else -> null
        }
    }

    /**
     * Every `@SerialName` used by the configuration model, keyed by its comparison form.
     *
     * The desktop trees write exactly these PascalCase names; listing them explicitly (instead of
     * reflecting over the serializers) keeps the mapping auditable and dependency free.
     */
    private val CANONICAL_KEYS: Map<String, String> = run {
        val keys = HashMap<String, String>()
        fun put(vararg names: String) {
            names.forEach { keys[EcpJson.normalizeWord(it)!!] = it }
        }
        // AppConfig
        put(
            "SchemaVersion", "HudEnabled", "UiLanguage", "GlobalScale", "DisplayDurationSeconds",
            "BounceStrength", "RippleIntensity", "RippleSpread", "HudOpacity", "PositionMode",
            "HudPosition", "HudOffsetX", "HudOffsetY", "HudCustomX", "HudCustomY", "CustomHud",
            "Android",
        )
        // AndroidSettings
        put(
            "DisplayMode", "OverlayXPortrait", "OverlayYPortrait", "OverlayXLandscape",
            "OverlayYLandscape", "HudScale", "ClickThrough", "AutoHideSeconds", "AlwaysVisible",
            "IslandProvider", "FastRefreshMs", "NormalRefreshMs", "SlowRefreshMs", "IdleRefreshMs",
            "ThrottleWhenHidden", "ThrottleWhenScreenOff", "ScreenOffRefreshMs", "ProbeEnabled",
            "ProbeIntervalSeconds", "ProbeTimeoutMs", "ProbeSampleWindow", "DeepSeekRefreshSeconds",
            "DeepSeekBaseUrl", "StartOnBoot", "AvoidCutout", "VerboseLogging", "CapabilityScanAt",
        )
        // CustomHudSettings
        put(
            "AutoCycle", "CycleSeconds", "CycleProfileIds", "CycleAnimationMode", "ActiveProfileId",
            "DeepSeekApiKeyProtected", "DeepSeekPeakWindows", "Profiles", "HttpSources",
        )
        // HudProfile
        put(
            "Id", "IsBuiltIn", "BuiltInKey", "Category", "Name", "AnimationMode",
            "TaglineTemplate", "TitleTemplate", "PrimaryTemplate", "SecondaryTemplate",
            "RightTemplate", "RightSuffix", "ProgressVariable", "ProgressMin", "ProgressMax",
            "LeftIcon", "RightIcon", "AccentColor", "ColorRules", "TimeTargetEnabled",
            "TimeTarget", "GpuAdapterId", "NetworkDisplayUnit", "NetworkPercentMode",
            "NetworkReferenceValue", "NetworkReferenceUnit", "PingTarget", "ProbeProtocol",
            "ProbePort",
        )
        // HudColorRule
        put("Variable", "Operator", "Value", "Color")
        // CustomHttpSource / HttpFieldMapping
        put("Enabled", "Url", "RefreshSeconds", "Headers", "Fields", "JsonPath")
        keys
    }

    /**
     * Fields whose desktop value is a C# enum and therefore persisted as a number.
     *
     * The order of each list is the declaration order of the C# enum, which is what
     * `System.Text.Json` writes.
     */
    private val ENUM_WORDS: Map<String, List<String>> = mapOf(
        "PositionMode" to listOf("Preset", "CustomCoordinates"),
        "HudPosition" to listOf(
            "TopLeft", "TopCenter", "TopRight", "CenterLeft", "Center",
            "CenterRight", "BottomLeft", "BottomCenter", "BottomRight",
        ),
        "DisplayMode" to listOf("Overlay", "Island"),
        "AnimationMode" to listOf("Full", "Simple"),
        "CycleAnimationMode" to listOf("Simple", "Full"),
        "ProbeProtocol" to listOf("ICMP", "TCP", "UDP"),
        "NetworkDisplayUnit" to listOf("AutoBytes", "Mbps"),
        "NetworkPercentMode" to listOf("Total", "Download", "Upload", "Max"),
        "IslandProvider" to listOf("Auto", "AndroidSystem", "XiaomiHyperIsland", "None"),
        "UiLanguage" to listOf("Auto", "zh-CN", "en-US"),
    )

    private val BOOLEAN_KEYS: Set<String> = setOf(
        "HudEnabled", "IsBuiltIn", "AutoCycle", "ClickThrough", "AlwaysVisible",
        "ThrottleWhenHidden", "ThrottleWhenScreenOff", "ProbeEnabled", "StartOnBoot",
        "AvoidCutout", "VerboseLogging", "TimeTargetEnabled", "Enabled",
    )

    private val INT_KEYS: Set<String> = setOf(
        "SchemaVersion", "CycleSeconds", "ProbePort", "RefreshSeconds", "HudOffsetX", "HudOffsetY",
        "HudCustomX", "HudCustomY", "OverlayXPortrait", "OverlayYPortrait", "OverlayXLandscape",
        "OverlayYLandscape", "ProbeIntervalSeconds", "ProbeTimeoutMs", "ProbeSampleWindow",
        "DeepSeekRefreshSeconds",
    )

    private val LONG_KEYS: Set<String> = setOf(
        "FastRefreshMs", "NormalRefreshMs", "SlowRefreshMs", "IdleRefreshMs", "ScreenOffRefreshMs",
        "CapabilityScanAt",
    )

    private val DOUBLE_KEYS: Set<String> = setOf(
        "GlobalScale", "DisplayDurationSeconds", "BounceStrength", "RippleIntensity",
        "RippleSpread", "HudOpacity", "HudScale", "AutoHideSeconds", "ProgressMin", "ProgressMax",
        "NetworkReferenceValue", "Value",
    )
}
