package com.glacierglimmer.endfieldchargeplus.island

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.glacierglimmer.endfieldchargeplus.core.model.HudRenderData
import com.glacierglimmer.endfieldchargeplus.core.model.IslandProviderKind
import com.glacierglimmer.endfieldchargeplus.data.ConfigRepository
import com.glacierglimmer.endfieldchargeplus.diagnostics.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns every island backend and answers the only two questions the rest of the app asks:
 * "which backend may run now" and "what should the settings page show".
 *
 * Selection is evaluated from the current configuration on every call, so a configuration change
 * needs no callback: the owner calls [startSelected]/[update]/[stop] and the registry picks the
 * provider that the user configured ([IslandProviderKind.AUTO] means "best usable backend").
 */
class IslandProviderRegistry private constructor(
    private val context: Context?,
    private val configRepository: ConfigRepository,
    /** The notification host the foreground service supplied; providers publish through it only. */
    val notificationHost: IslandNotificationHost,
    private val injectedProviders: List<IslandProvider>?,
) {

    /** The foreground service constructs the registry with the real host (see `di/EcpContainer.kt`). */
    constructor(
        context: Context,
        configRepository: ConfigRepository,
        notificationHost: IslandNotificationHost,
    ) : this(context, configRepository, notificationHost, null)

    /**
     * Test-only constructor: injects providers so selection and describe() are unit-testable without
     * an Android context. Not part of the runtime wiring; production code must use the
     * `(context, configRepository, notificationHost)` constructor.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    constructor(
        configRepository: ConfigRepository,
        providerList: List<IslandProvider>,
    ) : this(null, configRepository, IslandNotificationHost.Disabled, providerList)

    private val providerList: List<IslandProvider> by lazy {
        injectedProviders ?: defaultProviders(
            requireNotNull(context) { "a Context is required to build the real island providers" },
            notificationHost,
        )
    }

    @Volatile
    private var active: IslandProvider? = null

    private val refreshMutex = Mutex()
    private val _statuses = MutableStateFlow<List<IslandProviderStatus>>(emptyList())
    /** Shared by Home and Display; empty means detection has not completed yet. */
    val statuses: StateFlow<List<IslandProviderStatus>> = _statuses.asStateFlow()

    /** Every provider this build ships, in preference order. */
    fun providers(): List<IslandProvider> = providerList.toList()

    /** The provider for one explicit kind, regardless of its current availability. */
    fun providerFor(kind: IslandProviderKind): IslandProvider? = providerList.firstOrNull { it.kind == kind }

    /** Re-evaluates every provider and returns their availability in provider order. */
    suspend fun refreshAll(): List<IslandAvailability> = refreshMutex.withLock {
        val refreshed = providerList.map { it.refreshAvailability() }
        _statuses.value = describe()
        refreshed
    }

    /**
     * The usable Android system live-update provider for this device, otherwise `null`.
     */
    fun autoSelect(): IslandProvider? =
        providerList.sortedBy { preferenceRank(it.kind) }.firstOrNull { it.availability().usable }

    /**
     * Applies `AndroidSettings.islandProvider`: `Auto` delegates to [autoSelect], an explicit kind is
     * returned only when the provider is usable, `None` returns `null`.
     */
    fun select(): IslandProvider? = when (val kind = configuredKind()) {
        IslandProviderKind.AUTO -> autoSelect()
        IslandProviderKind.NONE -> null
        else -> providerFor(kind)?.takeIf { it.availability().usable }
    }

    /** What the settings page renders: provider, exact state and honest capabilities. */
    fun describe(): List<IslandProviderStatus> =
        providerList.map { IslandProviderStatus(it, it.availability(), it.capabilities()) }

    /** The provider currently running, if any. */
    fun activeProvider(): IslandProvider? = active

    /** Stable id of the provider currently running, for the settings/diagnostics page. */
    fun activeProviderId(): String? = active?.id

    /** Availability of the currently running provider, or `null` when nothing is running. */
    fun activeAvailability(): IslandAvailability? = active?.availability()

    fun configuredAvailability(): IslandAvailability? = when (val kind = configuredKind()) {
        IslandProviderKind.NONE -> null
        IslandProviderKind.AUTO -> autoSelect()?.availability()
            ?: providerFor(IslandProviderKind.ANDROID_SYSTEM)?.availability()
        else -> providerFor(kind)?.availability()
    }

    /**
     * Starts the selected provider and returns it, or `null` when nothing usable is configured or the
     * provider refused to start. Reapplying the same running provider is a no-op.
     */
    fun startSelected(scope: CoroutineScope): IslandProvider? {
        val provider = select()
        if (provider != null && provider === active && provider.isRunning()) return provider
        stop()
        if (provider == null) {
            AppLog.w(TAG, "no usable island provider for kind=${configuredKind()}")
            return null
        }
        provider.start(scope)
        if (!provider.isRunning()) {
            AppLog.w(TAG, "provider ${provider.id} did not start (${provider.availability().messageKey})")
            return null
        }
        active = provider
        AppLog.i(TAG, "island provider started: ${provider.id}")
        return provider
    }

    /** Forwards one HUD frame to the running provider; a no-op when nothing runs. */
    fun update(data: HudRenderData) {
        active?.update(data)
    }

    /** Stops the running provider; safe to call when nothing runs. */
    fun stop() {
        val provider = active ?: return
        active = null
        provider.stop()
        AppLog.i(TAG, "island provider stopped: ${provider.id}")
    }

    /** True while a provider is running. */
    fun isRunning(): Boolean = active?.isRunning() == true

    /**
     * Returns a registry bound to [host]. The foreground service calls this when it comes up, because
     * providers must publish through the service's notification host, not through the disabled one.
     */
    fun withNotificationHost(host: IslandNotificationHost): IslandProviderRegistry =
        IslandProviderRegistry(context, configRepository, host, injectedProviders)

    private fun configuredKind(): IslandProviderKind =
        IslandProviderKind.fromWire(configRepository.config.value.android.islandProvider)

    private fun preferenceRank(kind: IslandProviderKind): Int = when (kind) {
        IslandProviderKind.ANDROID_SYSTEM -> 1
        else -> 2
    }

    companion object {
        private const val TAG: String = "IslandRegistry"

        /**
         * This build only ships the native Android live-update backend.
         */
        fun defaultProviders(context: Context, notificationHost: IslandNotificationHost): List<IslandProvider> =
            listOf(
                AndroidLiveUpdateProvider(context, notificationHost),
            )
    }
}

/** One row of the island section on the settings page. */
data class IslandProviderStatus(
    val provider: IslandProvider,
    val availability: IslandAvailability,
    val capabilities: IslandCapabilities,
) {
    /** True when this backend can publish right now. */
    val usable: Boolean
        get() = availability.usable
}
