package com.glacierglimmer.endfieldchargeplus.island

import android.app.Activity
import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.core.model.AndroidSettings
import com.glacierglimmer.endfieldchargeplus.core.model.HudRenderData
import com.glacierglimmer.endfieldchargeplus.core.model.IslandProviderKind
import com.glacierglimmer.endfieldchargeplus.data.ConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandProviderRegistryTest {
    @Test fun `auto and explicit Android selection use the native provider only`() {
        val f = Fixture(IslandProviderKind.AUTO, IslandAvailabilityState.AVAILABLE)
        assertSame(f.android, f.registry.autoSelect())
        assertSame(f.android, f.registry.select())
        assertEquals(1, f.registry.providers().size)
    }
    @Test fun `legacy vendor selection migrates to native Android`() {
        assertEquals(IslandProviderKind.ANDROID_SYSTEM, IslandProviderKind.fromWire("XiaomiHyperIsland"))
        assertEquals(IslandProviderKind.ANDROID_SYSTEM, IslandProviderKind.fromWire("xiaomi_hyper_island"))
        assertEquals(listOf("Auto", "AndroidSystem", "None"), IslandProviderKind.entries.map { it.wire })
    }
    @Test fun `unavailable and disabled providers cannot start`() {
        val unavailable = Fixture(IslandProviderKind.ANDROID_SYSTEM, IslandAvailabilityState.UNSUPPORTED_BY_PLATFORM)
        assertNull(unavailable.registry.select())
        val disabled = Fixture(IslandProviderKind.NONE, IslandAvailabilityState.AVAILABLE)
        assertNull(disabled.registry.select())
    }
    @Test fun `describe reports native platform state accurately`() {
        val f = Fixture(IslandProviderKind.AUTO, IslandAvailabilityState.UNSUPPORTED_BY_PLATFORM)
        val rows = f.registry.describe()
        assertEquals(1, rows.size)
        assertEquals(IslandAvailabilityState.UNSUPPORTED_BY_PLATFORM, rows.single().availability.state)
        assertTrue(!rows.single().usable)
    }
    @Test fun `reapplying the same selection does not cancel or repost the notification`() {
        val f = Fixture(IslandProviderKind.AUTO, IslandAvailabilityState.AVAILABLE)
        val scope = CoroutineScope(kotlin.coroutines.EmptyCoroutineContext)
        repeat(1000) { assertSame(f.android, f.registry.startSelected(scope)) }
        assertEquals(1, f.android.startCalls)
        assertEquals(0, f.android.stopCalls)
        f.registry.update(HudRenderData(title = "Memory"))
        assertEquals(1, f.android.updateCount)
        f.registry.stop(); assertEquals(1, f.android.stopCalls)
        assertNull(f.registry.activeProviderId())
    }
    @Test fun `a backend that refuses to start is not reported as running`() {
        val f = Fixture(IslandProviderKind.AUTO, IslandAvailabilityState.AVAILABLE, starts = false)
        assertNull(f.registry.startSelected(CoroutineScope(kotlin.coroutines.EmptyCoroutineContext)))
        assertTrue(!f.registry.isRunning())
    }
    @Test fun `updating without a running provider is a no-op`() {
        val f = Fixture(IslandProviderKind.AUTO, IslandAvailabilityState.AVAILABLE)
        f.registry.update(HudRenderData(title = "Memory"))
        assertEquals(0, f.android.updateCount)
    }

    @Test fun `shared Home and Display status publishes only the refreshed native verdict`() = runTest {
        val f = Fixture(IslandProviderKind.ANDROID_SYSTEM, IslandAvailabilityState.UNSUPPORTED_BY_PLATFORM)
        var cached = f.android.availability()
        var actual = IslandAvailability(IslandAvailabilityState.AVAILABLE, "available")
        val native = object : IslandProvider by f.android {
            override fun availability() = cached
            override suspend fun refreshAvailability(): IslandAvailability {
                cached = actual
                return cached
            }
        }
        val registry = IslandProviderRegistry(FakeConfigRepository(config(IslandProviderKind.ANDROID_SYSTEM)), listOf(native))
        assertTrue(registry.statuses.value.isEmpty())
        assertTrue(!native.availability().usable)
        registry.refreshAll()
        assertTrue(registry.statuses.value.single().usable)
        assertSame(native, registry.statuses.value.single().provider)
        actual = IslandAvailability(IslandAvailabilityState.NOT_AUTHORIZED, "revoked")
        registry.refreshAll()
        assertEquals(IslandAvailabilityState.NOT_AUTHORIZED, registry.statuses.value.single().availability.state)
        assertTrue(!registry.statuses.value.single().usable)
    }
    private class Fixture(kind: IslandProviderKind, state: IslandAvailabilityState, starts: Boolean = true) {
        val android = FakeProvider(IslandProviderKind.ANDROID_SYSTEM, "android_system_live_update", state,
            if (state == IslandAvailabilityState.AVAILABLE) "island_state_available" else "island_state_unsupported", starts)
        val registry = IslandProviderRegistry(FakeConfigRepository(config(kind)), listOf(android))
    }
    private class FakeProvider(
        override val kind: IslandProviderKind,
        override val id: String,
        private val state: IslandAvailabilityState,
        private val messageKey: String,
        private val startsSuccessfully: Boolean = true,
    ) : IslandProvider {
        override val nameKey: String = "name_$id"
        var startCalls: Int = 0
        var updateCount: Int = 0
        var stopCalls: Int = 0

        @Volatile
        private var running: Boolean = false

        override fun capabilities(): IslandCapabilities = IslandCapabilities(
            supportsTitle = true,
            supportsSubtitle = true,
            supportsProgress = true,
            supportsIcons = false,
            supportsMultipleLines = false,
            supportsCustomLayout = false,
            supportsContinuousUpdates = false,
            maxUpdateHz = 0.2,
        )

        override fun availability(): IslandAvailability = IslandAvailability(state, messageKey)

        override suspend fun refreshAvailability(): IslandAvailability = availability()

        override fun requestAuthorization(activity: Activity?): Boolean = false

        override fun start(scope: CoroutineScope) {
            startCalls++
            running = startsSuccessfully
        }

        override fun update(data: HudRenderData) {
            updateCount++
        }

        override fun stop() {
            stopCalls++
            running = false
        }

        override fun isRunning(): Boolean = running
    }

    private class FakeConfigRepository(initial: AppConfig) : ConfigRepository {
        private val state = MutableStateFlow(initial)

        override val config: StateFlow<AppConfig> get() = state

        override suspend fun load(): AppConfig = state.value

        override suspend fun update(transform: (AppConfig) -> AppConfig) {
            state.value = transform(state.value)
        }

        override suspend fun replace(config: AppConfig) {
            state.value = config
        }

        override suspend fun resetToDefaults() {
            state.value = AppConfig()
        }

        override suspend fun exportJson(): String = ""

        override suspend fun importJson(json: String): Result<AppConfig> = Result.success(AppConfig())

        override fun normalize(config: AppConfig): AppConfig = config
    }

    private companion object {
        fun config(kind: IslandProviderKind): AppConfig =
            AppConfig(android = AndroidSettings(islandProvider = kind.wire))
    }
}
