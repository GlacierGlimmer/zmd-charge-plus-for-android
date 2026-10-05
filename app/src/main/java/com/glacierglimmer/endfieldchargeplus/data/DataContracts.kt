package com.glacierglimmer.endfieldchargeplus.data

import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import kotlinx.coroutines.flow.StateFlow

/**
 * Persistence of the whole configuration.
 *
 * The Android edition stores ordinary settings in DataStore and the scheme/HTTP-source part as
 * JSON, so an exported file stays meaningful for the desktop editions. Compose state is never
 * serialized.
 */
interface ConfigRepository {

    /** Always-current configuration; emits immediately with the loaded value. */
    val config: StateFlow<AppConfig>

    suspend fun load(): AppConfig

    /** Read-modify-write in one atomic step. */
    suspend fun update(transform: (AppConfig) -> AppConfig)

    suspend fun replace(config: AppConfig)

    suspend fun resetToDefaults()

    /** Serializes the current configuration exactly as the import/export feature expects. */
    suspend fun exportJson(): String

    /** Parses and validates an imported configuration. Never throws; failures are a Result. */
    suspend fun importJson(json: String): Result<AppConfig>

    /**
     * Applies the cross-platform normalizer: missing built-in schemes are re-created, stale carousel
     * references are dropped, numeric ranges are clamped, and legacy spellings are migrated.
     */
    fun normalize(config: AppConfig): AppConfig
}

/**
 * Storage for secrets (the DeepSeek API key).
 *
 * Implementations must encrypt at rest with an Android Keystore backed key and must never log or
 * export the clear value.
 */
interface SecretStore {

    /** True when encrypted storage is usable on this device. */
    fun isAvailable(): Boolean

    fun put(key: String, value: String?)

    fun get(key: String): String?

    fun remove(key: String)

    companion object {
        const val KEY_DEEPSEEK_API_KEY = "deepseek_api_key"
    }
}
