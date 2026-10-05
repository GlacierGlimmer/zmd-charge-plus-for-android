package com.glacierglimmer.endfieldchargeplus.core.json

import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.core.model.CustomHudSettings
import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Serialization of the configuration and of the scheme ("方案") section.
 *
 * Compatibility rules implemented here:
 *  * the desktop trees write `System.Text.Json` PascalCase with numeric enums, so [EcpJson.parse]
 *    canonicalizes the tree before the strict serializer sees it;
 *  * a legacy file may carry `"CycleProfileIds": null`; that is a *meaningful* value (the normalizer
 *    migrates it to the default queue) and is therefore preserved as `null` by [decode];
 *  * a truncated or malformed file never throws out of these functions — it yields a
 *    `Result.failure` whose message is safe to show in the settings status line.
 */
object ConfigCodec {

    /** Pretty, desktop-compatible JSON of the whole configuration. */
    fun encode(config: AppConfig): String = EcpJson.encode(AppConfig.serializer(), config, pretty = true)

    /** Never throws. Returns the decoded configuration exactly as written (no normalization). */
    fun decode(json: String): Result<AppConfig> = EcpJson.decode(AppConfig.serializer(), json)

    /**
     * The scheme section only (`CustomHudSettings`: profiles, carousel, HTTP sources, peak windows).
     *
     * This is the shape used by the profile editor's own import/export, so it can be handed to the
     * desktop editions without dragging Android-only settings along.
     */
    fun encodeProfilesOnly(config: AppConfig): String =
        EcpJson.encode(CustomHudSettings.serializer(), config.customHud, pretty = true)

    /**
     * Decodes schemes from any of the shapes a user can realistically supply:
     *  * a bare array of profiles;
     *  * a `CustomHudSettings` object (`Profiles`, ...);
     *  * a whole desktop `AppSettings` object (`CustomHud.Profiles`);
     *  * camelCase / snake_case spellings of all of the above.
     */
    fun decodeProfilesOnly(json: String): Result<List<HudProfile>> =
        EcpJson.parse(json).fold(
            onSuccess = { element ->
                try {
                    Result.success(extractProfiles(element))
                } catch (t: Throwable) {
                    Result.failure(
                        ConfigFormatException(
                            "The file does not contain a profile list: ${t.message ?: t.javaClass.simpleName}",
                            t,
                        ),
                    )
                }
            },
            onFailure = { Result.failure(it) },
        )

    private fun extractProfiles(element: JsonElement): List<HudProfile> {
        val array: JsonArray = when (element) {
            is JsonArray -> element
            is JsonObject -> {
                val direct = element["Profiles"] as? JsonArray
                val nested = (element["CustomHud"] as? JsonObject)?.get("Profiles") as? JsonArray
                direct ?: nested ?: throw ConfigFormatException(
                    "No \"Profiles\" array was found (expected Profiles, CustomHud.Profiles or a bare array).",
                )
            }

            else -> throw ConfigFormatException("Expected a JSON object or array at the top level.")
        }
        return EcpJson.pretty.decodeFromJsonElement(ListSerializer(HudProfile.serializer()), array)
    }
}
