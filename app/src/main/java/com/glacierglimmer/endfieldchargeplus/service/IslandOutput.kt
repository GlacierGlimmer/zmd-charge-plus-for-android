package com.glacierglimmer.endfieldchargeplus.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.glacierglimmer.endfieldchargeplus.core.model.HudRenderData
import com.glacierglimmer.endfieldchargeplus.diagnostics.AppLog
import com.glacierglimmer.endfieldchargeplus.island.IslandAvailability
import com.glacierglimmer.endfieldchargeplus.island.IslandNotificationHost
import com.glacierglimmer.endfieldchargeplus.island.IslandProviderRegistry
import kotlinx.coroutines.CoroutineScope

/**
 * Narrow view of the island output used by [HudForegroundService].
 *
 * Every call into `IslandProviderRegistry` goes through this interface and [RegistryIslandOutput],
 * so the service depends on three methods instead of the whole registry surface and a signature
 * change on the island side stays a one-line fix here.
 */
internal interface IslandOutput {

    /** Starts the provider the user configured; true only when a provider actually started. */
    fun start(): Boolean
    suspend fun refreshAvailability() {}

    /** Pushes one rendered frame. */
    fun update(data: HudRenderData)

    fun stop()

    /** Id of the provider that is publishing, or null. */
    fun providerId(): String?

    /** Last availability reported by the registry, or null. */
    fun availability(): IslandAvailability?
}

/** Adapter over the island registry created by `EcpContainer`. */
internal class RegistryIslandOutput(
    private val registry: IslandProviderRegistry,
    private val scope: CoroutineScope,
) : IslandOutput {

    override suspend fun refreshAvailability() { registry.refreshAll() }

    override fun start(): Boolean {
        // Selection is re-read from the configuration on every call; the provider is only started
        // when the platform reports it as usable, and the return value says whether it really did.
        return registry.startSelected(scope) != null
    }

    override fun update(data: HudRenderData) {
        registry.update(data)
    }

    override fun stop() {
        registry.stop()
    }

    override fun providerId(): String? = registry.activeProviderId()

    override fun availability(): IslandAvailability? = registry.activeAvailability() ?: registry.configuredAvailability()
}

/**
 * The real island notification host used while the HUD service runs.
 *
 * Island providers that publish a promoted ongoing notification build a platform
 * [android.app.Notification.Builder] and hand it over here; providers are expected to set
 * [HudNotificationFactory.CHANNEL_ID] on that builder.
 */
internal class ServiceIslandNotificationHost(context: Context) : IslandNotificationHost {

    private val manager: NotificationManager? = context.getSystemService(NotificationManager::class.java)

    override fun publish(id: Int, builder: Notification.Builder) {
        checkNotNull(manager) { "NotificationManager is unavailable" }.notify(id, builder.build())
    }

    override fun update(id: Int, builder: Notification.Builder) {
        publish(id, builder)
    }

    override fun cancel(id: Int) {
        runCatching { manager?.cancel(id) }
    }

    override fun areNotificationsEnabled(): Boolean = manager?.areNotificationsEnabled() ?: false

    private companion object {
        const val TAG = "HudIslandHost"
    }
}
