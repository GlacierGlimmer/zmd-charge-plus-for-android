package com.glacierglimmer.endfieldchargeplus.data

import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.glacierglimmer.endfieldchargeplus.core.json.ConfigCodec
import com.glacierglimmer.endfieldchargeplus.core.json.ConfigNormalizer
import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Context that never touches the framework. The repository and the transfer helper only need it for
 * `packageName`/`contentResolver`, neither of which is used by the pure paths under test.
 */
class StubContext : ContextWrapper(null)

/** In-memory [DataStore] used to exercise persistence without a real file. */
class FakePreferencesDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {

    private val state = MutableStateFlow(initial)

    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }

    fun snapshot(): Preferences = state.value
}

/** In-memory [ConfigRepository] used by the transfer tests. */
class FakeConfigRepository(initial: AppConfig = AppConfig()) : ConfigRepository {

    private val state = MutableStateFlow(initial)

    override val config: StateFlow<AppConfig> = state

    override suspend fun load(): AppConfig = state.value

    override suspend fun update(transform: (AppConfig) -> AppConfig) {
        state.value = normalize(transform(state.value))
    }

    override suspend fun replace(config: AppConfig) {
        state.value = normalize(config)
    }

    override suspend fun resetToDefaults() {
        state.value = normalize(AppConfig())
    }

    override suspend fun exportJson(): String = ConfigCodec.encode(state.value)

    override suspend fun importJson(json: String): Result<AppConfig> =
        ConfigCodec.decode(json).map { normalize(it) }

    override fun normalize(config: AppConfig): AppConfig = ConfigNormalizer.normalize(config)
}
