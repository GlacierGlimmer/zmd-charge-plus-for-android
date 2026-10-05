package com.glacierglimmer.endfieldchargeplus.island

import android.app.Activity
import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.core.model.AndroidSettings
import com.glacierglimmer.endfieldchargeplus.core.model.HudRenderData
import com.glacierglimmer.endfieldchargeplus.core.model.IslandProviderKind
import com.glacierglimmer.endfieldchargeplus.data.ConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Selection and state reporting of [IslandProviderRegistry]: the Auto preference order, the explicit
 * provider kinds and the running-provider bookkeeping the foreground service relies on.
 */
class IslandProviderRegistryTest {

    @Test
    fun `auto select prefers xiaomi hyper island when it is usable`() {
        val fixture = Fixture(
            configuredKind = IslandProviderKind.AUTO,
            xiaomiState = IslandAvailabilityState.AVAILABLE,
            androidState = IslandAvailabilityState.AVAILABLE,
        )

        assertSame(fixture.xiaomi, fixture.registry.autoSelect())
    }

    @Test
    fun `auto select falls back to the android live update when xiaomi needs authorization`() {
        val fixture = Fixture(
            configuredKind = IslandProviderKind.AUTO,
            xiaomiState = IslandAvailabilityState.VENDOR_PERMISSION_REQUIRED,
            androidState = IslandAvailabilityState.AVAILABLE,
        )

        assertSame(fixture.android, fixture.registry.autoSelect())
    }

    @Test
    fun `auto select returns nothing when no backend is usable`() {
        val fixture = Fixture(
            configuredKind = IslandProviderKind.AUTO,
            xiaomiState = IslandAvailabilityState.VENDOR_PERMISSION_REQUIRED,
            androidState = IslandAvailabilityState.UNSUPPORTED_BY_PLATFORM,
        )

        assertNull(fixture.registry.autoSelect())
        assertNull(fixture.registry.select())
    }

    @Test
    fun `an explicitly configured provider is only selected while it is usable`() {
        val usable = Fixture(
            configuredKind = IslandProviderKind.XIAOMI_HYPER_ISLAND,
            xiaomiState = IslandAvailabilityState.AVAILABLE,
            androidState = IslandAvailabilityState.AVAILABLE,
        )
        val unusable = Fixture(
            configuredKind = IslandProviderKind.XIAOMI_HYPER_ISLAND,
            xiaomiState = IslandAvailabilityState.NOT_AUTHORIZED,
            androidState = IslandAvailabilityState.AVAILABLE,
        )

        assertSame(usable.xiaomi, usable.registry.select())
        assertNull(unusable.registry.select())
        assertSame(unusable.xiaomi, unusable.registry.providerFor(IslandProviderKind.XIAOMI_HYPER_ISLAND))
    }

    @Test
    fun `the none kind never selects a provider even when one is usable`() {
        val fixture = Fixture(
            configuredKind = IslandProviderKind.NONE,
            xiaomiState = IslandAvailabilityState.AVAILABLE,
            androidState = IslandAvailabilityState.AVAILABLE,
        )

        assertNull(fixture.registry.select())
    }

    @Test
    fun `describe reports the exact state and capabilities of every provider`() {
        val fixture = Fixture(
            configuredKind = IslandProviderKind.AUTO,
            xiaomiState = IslandAvailabilityState.VENDOR_PERMISSION_REQUIRED,
            androidState = IslandAvailabilityState.UNSUPPORTED_BY_PLATFORM,
        )

        val rows = fixture.registry.describe()

        assertEquals(2, rows.size)
        val xiaomiRow = rows.first { it.provider.kind == IslandProviderKind.XIAOMI_HYPER_ISLAND }
        val androidRow = rows.first { it.provider.kind == IslandProviderKind.ANDROID_SYSTEM }
        assertEquals(IslandAvailabilityState.VENDOR_PERMISSION_REQUIRED, xiaomiRow.availability.state)
        assertEquals("island_state_vendor_permission", xiaomiRow.availability.messageKey)
        assertEquals(IslandAvailabilityState.UNSUPPORTED_BY_PLATFORM, androidRow.availability.state)
        assertEquals("island_state_unsupported", androidRow.availability.messageKey)
        assertTrue(rows.none { it.usable })
        assertTrue(rows.all { it.capabilities.maxUpdateHz > 0.0 })
    }

    @Test
    fun `startSelected starts the chosen provider, forwards frames and stops it again`() {
        val fixture = Fixture(
            configuredKind = IslandProviderKind.AUTO,
            xiaomiState = IslandAvailabilityState.VENDOR_PERMISSION_REQUIRED,
            androidState = IslandAvailabilityState.AVAILABLE,
        )
        val scope = CoroutineScope(kotlin.coroutines.EmptyCoroutineContext)

        val started = fixture.registry.startSelected(scope)

        assertSame(fixture.android, started)
        assertEquals(1, fixture.android.startCalls)
        assertEquals("android_system_live_update", fixture.registry.activeProviderId())
        assertEquals(IslandAvailabilityState.AVAILABLE, fixture.registry.activeAvailability()?.state)
        assertTrue(fixture.registry.isRunning())

        fixture.registry.update(HudRenderData(title = "Charging"))
        assertEquals(1, fixture.android.updateCount)

        fixture.registry.stop()
        assertEquals(1, fixture.android.stopCalls)
        assertNull(fixture.registry.activeProviderId())
        assertNull(fixture.registry.activeAvailability())
        assertTrue(!fixture.registry.isRunning())
    }

    @Test
    fun `startSelected reports nothing when the provider refuses to start`() {
        val fixture = Fixture(
            configuredKind = IslandProviderKind.AUTO,
            xiaomiState = IslandAvailabilityState.AVAILABLE,
            androidState = IslandAvailabilityState.UNSUPPORTED_BY_PLATFORM,
            xiaomiStarts = false,
        )
        val scope = CoroutineScope(kotlin.coroutines.EmptyCoroutineContext)

        assertNull(fixture.registry.startSelected(scope))
        assertNull(fixture.registry.activeProviderId())
    }

    @Test
    fun `updating without a running provider is a harmless no-op`() {
        val fixture = Fixture(IslandProviderKind.AUTO, IslandAvailabilityState.AVAILABLE, IslandAvailabilityState.AVAILABLE)

        fixture.registry.update(HudRenderData(title = "Charging"))

        assertEquals(0, fixture.android.updateCount)
        assertEquals(0, fixture.xiaomi.updateCount)
    }

    @Test
    fun `withNotificationHost rebinds the registry to the service host`() {
        val fixture = Fixture(IslandProviderKind.AUTO, IslandAvailabilityState.AVAILABLE, IslandAvailabilityState.AVAILABLE)
        val host = object : IslandNotificationHost {
            override fun publish(id: Int, builder: android.app.Notification.Builder) = Unit
            override fun update(id: Int, builder: android.app.Notification.Builder) = Unit
            override fun cancel(id: Int) = Unit
            override fun areNotificationsEnabled(): Boolean = true
        }

        val rebound = fixture.registry.withNotificationHost(host)

        assertSame(host, rebound.notificationHost)
        assertSame(fixture.registry.providers().first(), rebound.providers().first())
    }

    private class Fixture(
        configuredKind: IslandProviderKind,
        xiaomiState: IslandAvailabilityState,
        androidState: IslandAvailabilityState,
        xiaomiStarts: Boolean = true,
        androidStarts: Boolean = true,
    ) {
        val xiaomi = FakeProvider(
            kind = IslandProviderKind.XIAOMI_HYPER_ISLAND,
            id = "xiaomi_hyper_island",
            state = xiaomiState,
            messageKey = if (xiaomiState == IslandAvailabilityState.VENDOR_PERMISSION_REQUIRED) {
                "island_state_vendor_permission"
            } else {
                "island_state_unsupported"
            },
            startsSuccessfully = xiaomiStarts,
        )
        val android = FakeProvider(
            kind = IslandProviderKind.ANDROID_SYSTEM,
            id = "android_system_live_update",
            state = androidState,
            messageKey = if (androidState == IslandAvailabilityState.AVAILABLE) {
                "island_state_available"
            } else {
                "island_state_unsupported"
            },
            startsSuccessfully = androidStarts,
        )
        val registry = IslandProviderRegistry(
            FakeConfigRepository(config(configuredKind)),
            listOf(xiaomi, android),
        )
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
