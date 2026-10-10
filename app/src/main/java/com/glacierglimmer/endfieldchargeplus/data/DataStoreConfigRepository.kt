package com.glacierglimmer.endfieldchargeplus.data

import android.content.Context
import android.os.Build
import com.glacierglimmer.endfieldchargeplus.core.model.DisplayMode
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.glacierglimmer.endfieldchargeplus.core.json.ConfigCodec
import com.glacierglimmer.endfieldchargeplus.core.json.ConfigFormatException
import com.glacierglimmer.endfieldchargeplus.core.json.ConfigNormalizer
import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.diagnostics.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** One Preferences DataStore for the whole application configuration. */
private val Context.ecpConfigDataStore: DataStore<Preferences> by preferencesDataStore(name = "ecp_config")

/**
 * [ConfigRepository] on top of Preferences DataStore.
 *
 * The entire configuration is persisted as a single JSON string under the `config_json` key, which
 * is exactly the string the desktop editions understand. Two safety rules are implemented:
 *
 *  * **nothing corrupt is destroyed** — an unparseable value is copied to `config_backup_corrupt`
 *    before the defaults are used;
 *  * **nothing pre-migration is destroyed** — every save stores the previous JSON under
 *    `config_previous` (the Android analogue of the desktop `settings.previous.json`), which also
 *    covers the capability filtering performed by [CapabilityProfileFilter].
 *
 * The in-memory [config] flow is the single source of truth for the UI; no Compose state is ever
 * serialized.
 *
 * @param capabilityVariables provider for the unsupported-variable patterns of this device. It
 *   defaults to "nothing unsupported" so that a build without a capability scan never rewrites a
 *   configuration; wire it from the metrics layer (`CapabilityProfileFilter.unsupportedVariables`)
 *   through [capabilityVariableProvider].
 */
class DataStoreConfigRepository(
    private val context: Context,
    capabilityVariables: () -> Set<String> = { emptySet() },
) : ConfigRepository {

    /**
     * Unsupported-variable patterns applied before persisting. Mutable so the container can wire it
     * after the first hardware capability scan completes.
     */
    @Volatile
    var capabilityVariableProvider: () -> Set<String> = capabilityVariables

    /** Test seam: a caller-supplied store replaces the app-wide `ecp_config` DataStore. */
    private var overrideStore: DataStore<Preferences>? = null

    internal constructor(context: Context, store: DataStore<Preferences>) : this(context) {
        overrideStore = store
    }

    private val dataStore: DataStore<Preferences> get() = overrideStore ?: context.ecpConfigDataStore

    private val writeMutex = Mutex()
    private val loadMutex = Mutex()

    private val mutableConfig = MutableStateFlow(AppConfig())

    /** Always-current configuration; emits immediately with the loaded value. */
    override val config: StateFlow<AppConfig> = mutableConfig.asStateFlow()

    @Volatile
    private var loaded = false

    override suspend fun load(): AppConfig = withContext(Dispatchers.IO) {
        if (loaded) return@withContext mutableConfig.value
        loadMutex.withLock {
            if (loaded) return@withLock mutableConfig.value
            val restored = applyCapabilityFilter(normalize(readStored()))
            registerSecrets(restored)
            mutableConfig.value = restored
            loaded = true
            restored
        }
    }

    override suspend fun update(transform: (AppConfig) -> AppConfig) {
        withContext(Dispatchers.IO) {
            load()
            writeMutex.withLock {
                val next = applyCapabilityFilter(normalize(transform(mutableConfig.value)))
                if (next == mutableConfig.value) return@withLock
                persist(next)
                mutableConfig.value = next
            }
        }
    }

    override suspend fun replace(config: AppConfig) {
        withContext(Dispatchers.IO) {
            load()
            writeMutex.withLock {
                val next = applyCapabilityFilter(normalize(config))
                if (next == mutableConfig.value) return@withLock
                persist(next)
                mutableConfig.value = next
            }
        }
    }

    override suspend fun resetToDefaults() {
        replace(AppConfig())
    }

    override suspend fun exportJson(): String {
        load()
        return ConfigCodec.encode(mutableConfig.value)
    }

    /**
     * Parses, validates and normalizes an imported configuration **without persisting it**; the
     * caller decides when to call [replace]. Never throws.
     */
    override suspend fun importJson(json: String): Result<AppConfig> =
        try {
            ConfigCodec.decode(json).map { imported -> applyCapabilityFilter(normalize(imported)) }
        } catch (t: Throwable) {
            AppLog.e(TAG, "Imported configuration could not be processed", t)
            Result.failure(ConfigFormatException("Imported configuration could not be processed: ${t.message}", t))
        }

    override fun normalize(config: AppConfig): AppConfig {
        val normalized = ConfigNormalizer.normalize(config) { note -> AppLog.d(TAG, note) }
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA && DisplayMode.fromWire(normalized.android.displayMode) == DisplayMode.ISLAND)
            normalized.copy(android = normalized.android.copy(displayMode = DisplayMode.OVERLAY.wire)) else normalized
    }

    // ------------------------------------------------------------------------------------------
    // storage
    // ------------------------------------------------------------------------------------------

    private suspend fun readStored(): AppConfig {
        val preferences = try {
            dataStore.data
                .catch { throwable ->
                    if (throwable is IOException) {
                        AppLog.e(TAG, "Configuration store could not be read; using defaults", throwable)
                        emit(emptyPreferences())
                    } else {
                        throw throwable
                    }
                }
                .first()
        } catch (t: Throwable) {
            AppLog.e(TAG, "Configuration store is unavailable; using defaults", t)
            return AppConfig()
        }

        val text = preferences[KEY_CONFIG]
        if (text.isNullOrBlank()) return AppConfig()

        return ConfigCodec.decode(text).getOrElse { failure ->
            // Keep the exact bytes the user had; never destroy a file we failed to understand.
            AppLog.e(TAG, "Stored configuration is corrupt; preserved under '$KEY_CORRUPT_NAME'", failure)
            runCatching { dataStore.edit { preferences -> preferences[KEY_CORRUPT] = text } }
                .onFailure { AppLog.e(TAG, "Could not preserve the corrupt configuration text", it) }
            AppConfig()
        }
    }

    private suspend fun persist(config: AppConfig) {
        registerSecrets(config)
        val json = ConfigCodec.encode(config)
        dataStore.edit { preferences ->
            val previous = preferences[KEY_CONFIG]
            if (!previous.isNullOrBlank() && previous != json) {
                preferences[KEY_PREVIOUS] = previous
            }
            preferences[KEY_CONFIG] = json
        }
        AppLog.i(TAG, "Configuration saved (${json.length} characters)")
    }

    /** Applies [CapabilityProfileFilter] over the schemes using the current device verdicts. */
    private fun applyCapabilityFilter(config: AppConfig): AppConfig {
        val unsupported = try {
            capabilityVariableProvider()
        } catch (t: Throwable) {
            AppLog.e(TAG, "Capability variable provider failed; skipping profile filtering", t)
            emptySet()
        }
        if (unsupported.isEmpty()) return config
        val filtered = CapabilityProfileFilter.filter(config.customHud, unsupported)
        if (filtered == config.customHud) return config
        AppLog.i(TAG, "Removed ${unsupported.size} unsupported variable pattern(s) from the schemes")
        return config.copy(customHud = filtered)
    }

    /**
     * Registers the protected key blob for log redaction. The blob itself is opaque and is never
     * inspected, decrypted or written to the log.
     */
    private fun registerSecrets(config: AppConfig) {
        AppLog.registerSecret(config.customHud.deepSeekApiKeyProtected.takeIf { it.isNotBlank() })
    }

    companion object {
        private const val TAG = "ConfigRepository"

        /** Preferences key holding the whole configuration as JSON. */
        const val KEY_CONFIG_NAME = "config_json"

        /** The last configuration this device ran with, kept before every migration/save. */
        const val KEY_PREVIOUS_NAME = "config_previous"

        /** The exact text of a configuration that could not be parsed. */
        const val KEY_CORRUPT_NAME = "config_backup_corrupt"

        val KEY_CONFIG = stringPreferencesKey(KEY_CONFIG_NAME)
        val KEY_PREVIOUS = stringPreferencesKey(KEY_PREVIOUS_NAME)
        val KEY_CORRUPT = stringPreferencesKey(KEY_CORRUPT_NAME)
    }
}
