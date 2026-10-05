package com.glacierglimmer.endfieldchargeplus.data

import com.glacierglimmer.endfieldchargeplus.core.json.ConfigCodec
import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.core.model.BuiltInProfiles
import com.glacierglimmer.endfieldchargeplus.core.model.CustomHudSettings
import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataStoreConfigRepositoryTest {

    private fun repository(unsupported: Set<String> = emptySet()): DataStoreConfigRepository =
        DataStoreConfigRepository(StubContext()) { unsupported }

    private fun repositoryWithStore(
        store: FakePreferencesDataStore,
        unsupported: Set<String> = emptySet(),
    ): DataStoreConfigRepository = DataStoreConfigRepository(StubContext(), store).apply {
        capabilityVariableProvider = { unsupported }
    }

    @Test
    fun `normalize clamps, repairs and migrates through the repository`() {
        val repository = repository()

        val normalized = repository.normalize(
            AppConfig(
                schemaVersion = 0,
                globalScale = 99.0,
                hudOpacity = -2.0,
                customHud = CustomHudSettings(activeProfileId = "ghost", cycleProfileIds = null),
            ),
        )

        assertEquals(AppConfig.CURRENT_SCHEMA_VERSION, normalized.schemaVersion)
        assertEquals(1.4, normalized.globalScale, 1e-9)
        assertEquals(0.1, normalized.hudOpacity, 1e-9)
        assertEquals(BuiltInProfiles.idOf(BuiltInProfiles.MEMORY), normalized.customHud.activeProfileId)
        assertEquals(7, normalized.customHud.cycleProfileIds?.size)
        assertEquals(BuiltInProfiles.all().size, normalized.customHud.profiles.size)
    }

    @Test
    fun `importJson normalizes but does not persist`() = runTest {
        val repository = repository()

        val result = repository.importJson("""{ "GlobalScale": 50.0, "HudOpacity": -3.0 }""")

        assertTrue(result.isSuccess)
        // Normalized on the way out...
        assertEquals(1.4, result.getOrThrow().globalScale, 1e-9)
        assertEquals(0.1, result.getOrThrow().hudOpacity, 1e-9)
        // ...and nothing was written to the flow (the caller decides with replace()).
        assertEquals(AppConfig(), repository.config.value)
    }

    @Test
    fun `importJson accepts a desktop export and rejects garbage`() = runTest {
        val repository = repository()

        val ok = repository.importJson(
            """{ "PositionMode": 0, "HudPosition": 8, "CustomHud": { "CycleProfileIds": null } }""",
        )
        assertTrue(ok.isSuccess)
        assertEquals("Preset", ok.getOrThrow().positionMode)
        assertEquals("BottomRight", ok.getOrThrow().hudPosition)

        val bad = repository.importJson("{\"HudEnabled\": tr")
        assertTrue(bad.isFailure)
        assertFalse(bad.exceptionOrNull()?.message.isNullOrBlank())
    }

    @Test
    fun `capability filtering is applied to imported schemes`() = runTest {
        val repository = repository(setOf("gpu.*"))
        val json = """
            {
              "CustomHud": {
                "ActiveProfileId": "custom-1",
                "CycleProfileIds": ["custom-1"],
                "Profiles": [
                  {
                    "Id": "custom-1",
                    "Name": "GPU 鏂规",
                    "PrimaryTemplate": "{gpu.usage|0}",
                    "SecondaryTemplate": "{cpu.usage|0}",
                    "ProgressVariable": "gpu.usage"
                  }
                ]
              }
            }
        """.trimIndent()

        val config = repository.importJson(json).getOrThrow()
        val custom = config.customHud.profiles.first { it.id == "custom-1" }

        assertEquals("", custom.primaryTemplate)
        assertEquals("{cpu.usage|0}", custom.secondaryTemplate)
        assertEquals("", custom.progressVariable)
    }

    @Test
    fun `capability filtering is skipped when nothing is unsupported`() = runTest {
        val repository = repository()

        val config = repository.importJson(
            """{ "CustomHud": { "Profiles": [ { "Id": "p", "Name": "P", "PrimaryTemplate": "{gpu.usage|0}" } ] } }""",
        ).getOrThrow()

        val custom = config.customHud.profiles.first { it.id == "p" }
        assertEquals("{gpu.usage|0}", custom.primaryTemplate)
    }

    @Test
    fun `a failing capability provider never breaks the import path`() = runTest {
        val repository = DataStoreConfigRepository(StubContext()) {
            throw IllegalStateException("scan exploded")
        }

        val result = repository.importJson("""{ "GlobalScale": 5.0 }""")

        assertTrue(result.isSuccess)
        assertEquals(1.4, result.getOrThrow().globalScale, 1e-9)
    }

    @Test
    fun `load reads the stored json, normalizes it and emits it`() = runTest {
        val store = FakePreferencesDataStore(
            preferencesOf(DataStoreConfigRepository.KEY_CONFIG to """{ "GlobalScale": 9.0 }"""),
        )
        val repository = repositoryWithStore(store)

        val loaded = repository.load()

        assertEquals(1.4, loaded.globalScale, 1e-9)
        assertEquals(loaded, repository.config.value)
    }

    @Test
    fun `corrupt stored json falls back to defaults and is preserved`() = runTest {
        val corrupt = """{ "HudEnabled": tr"""
        val store = FakePreferencesDataStore(
            preferencesOf(DataStoreConfigRepository.KEY_CONFIG to corrupt),
        )
        val repository = repositoryWithStore(store)

        val loaded = repository.load()

        assertEquals(AppConfig(), loaded)
        assertEquals(corrupt, store.snapshot()[DataStoreConfigRepository.KEY_CORRUPT])
    }

    @Test
    fun `an empty store yields defaults without writing`() = runTest {
        val store = FakePreferencesDataStore()
        val repository = repositoryWithStore(store)

        assertEquals(AppConfig(), repository.load())
        assertNull(store.snapshot()[DataStoreConfigRepository.KEY_CONFIG])
    }

    @Test
    fun `every save keeps the previous json under config_previous`() = runTest {
        val store = FakePreferencesDataStore()
        val repository = repositoryWithStore(store)

        repository.update { it.copy(globalScale = 1.1) }
        val first = store.snapshot()[DataStoreConfigRepository.KEY_CONFIG]
        assertNotNull(first)

        repository.update { it.copy(globalScale = 1.2) }

        assertEquals(first, store.snapshot()[DataStoreConfigRepository.KEY_PREVIOUS])
        assertEquals(1.2, repository.config.value.globalScale, 1e-9)
        assertEquals(
            1.1,
            ConfigCodec.decode(store.snapshot()[DataStoreConfigRepository.KEY_PREVIOUS]!!)
                .getOrThrow().globalScale,
            1e-9,
        )
    }

    @Test
    fun `replace persists normalized and capability filtered data`() = runTest {
        val store = FakePreferencesDataStore()
        val repository = repositoryWithStore(store, setOf("gpu.*"))

        repository.replace(
            AppConfig(
                globalScale = 99.0,
                customHud = CustomHudSettings(
                    profiles = listOf(HudProfile(id = "p", name = "P", primaryTemplate = "{gpu.usage|0}")),
                ),
            ),
        )

        assertEquals(1.4, repository.config.value.globalScale, 1e-9)
        val persisted = ConfigCodec.decode(store.snapshot()[DataStoreConfigRepository.KEY_CONFIG]!!).getOrThrow()
        assertEquals(1.4, persisted.globalScale, 1e-9)
        assertEquals("", persisted.customHud.profiles.first { it.id == "p" }.primaryTemplate)
    }

    @Test
    fun `exportJson returns the pretty json of the current configuration`() = runTest {
        val store = FakePreferencesDataStore()
        val repository = repositoryWithStore(store)

        val json = repository.exportJson()

        assertTrue(json.contains("\n"))
        assertEquals(AppConfig(), ConfigCodec.decode(json).getOrThrow())
    }
}

