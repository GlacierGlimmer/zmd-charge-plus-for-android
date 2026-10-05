package com.glacierglimmer.endfieldchargeplus.island

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import com.glacierglimmer.endfieldchargeplus.core.model.HudRenderData
import com.glacierglimmer.endfieldchargeplus.core.model.IslandProviderKind
import com.glacierglimmer.endfieldchargeplus.diagnostics.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Xiaomi HyperOS "超级岛 / HyperIsland" backend.
 *
 * Research summary (sources in `docs/audit/03-island-platforms.md`): HyperIsland is *not* an SDK.
 * An application publishes by posting an ordinary notification whose extras carry a JSON string
 * under `miui.focus.param`, and the ROM only honours it for applications that went through the
 * Xiaomi developer console (enterprise account, on-shelf app, activated 超级岛 service, signing
 * fingerprint, `com.xiaomi.xms.APP_ID` meta-data, scenario pre-review + formal review + on-device
 * verification). A normal third-party app can therefore never publish an island on its own, and this
 * project bundles no vendor SDK: the publishing path lives behind [XiaomiIslandBridge] and the only
 * implementation shipped here is [NotIntegratedBridge], which refuses to publish.
 *
 * Device detection uses public build information only ([Build.MANUFACTURER], [Build.BRAND],
 * [Build.DISPLAY], [Build.VERSION.INCREMENTAL], [Build.VERSION.SDK_INT]) plus two documented public
 * Android entry points (`Settings.System` and the vendor ContentProvider method `canShowFocus`).
 * It never reads hidden system properties through reflection and never reflects into vendor classes.
 */
class XiaomiHyperIslandProvider(
    private val context: Context,
    private val notificationHost: IslandNotificationHost,
    private val bridge: XiaomiIslandBridge = NotIntegratedBridge,
) : IslandProvider {

    override val kind: IslandProviderKind = IslandProviderKind.XIAOMI_HYPER_ISLAND

    override val id: String = ID

    override val nameKey: String = NAME_KEY

    @Volatile
    private var lastAvailability: IslandAvailability? = null

    @Volatile
    private var lastError: String = ""

    @Volatile
    private var running: Boolean = false

    @Volatile
    private var content: IslandContent = IslandContent()

    /** True on Xiaomi devices running a HyperOS build (see [HyperOsIslandPolicy]). */
    override fun isPreferred(): Boolean =
        HyperOsIslandPolicy.isXiaomiDevice(Build.MANUFACTURER ?: "", Build.BRAND ?: "")

    override fun capabilities(): IslandCapabilities = CAPABILITIES

    override fun availability(): IslandAvailability =
        lastAvailability ?: evaluate().also { lastAvailability = it }

    override suspend fun refreshAvailability(): IslandAvailability = evaluate().also { lastAvailability = it }

    /**
     * HyperIsland authorization is granted in the Xiaomi developer console, not on the device, so
     * there is no activity this app may launch. The method reports `false` and only logs the
     * documented entry point instead of pretending an authorization flow exists.
     */
    override fun requestAuthorization(activity: Activity?): Boolean {
        AppLog.i(
            TAG,
            "HyperIsland authorization cannot be requested from the app; it is granted in the Xiaomi " +
                "developer console after scenario review (${XiaomiIslandBridge.DOCUMENTATION_ACCESS_URL})",
        )
        return false
    }

    /** Publishes the first frame; refuses whenever the vendor bridge is not integrated. */
    override fun start(scope: CoroutineScope) {
        if (running) return
        val availability = refreshAvailabilityBlocking()
        if (!availability.usable) {
            AppLog.w(TAG, "start refused: state=${availability.state} key=${availability.messageKey} ${availability.detail}")
            return
        }
        if (!bridge.isIntegrated()) {
            lastAvailability = IslandAvailability.needsVendorPermission(bridge.integrationDetail())
            AppLog.w(TAG, "start refused: vendor bridge not integrated (${bridge.documentationUrl()})")
            return
        }
        ensureChannel()
        val published = publishFrame()
        running = published
        if (published) {
            AppLog.i(TAG, "HyperIsland frame published through the vendor bridge (id=$NOTIFICATION_ID)")
        } else {
            lastError = "vendor bridge refused to publish: ${bridge.integrationDetail()}"
            lastAvailability = IslandAvailability.needsVendorPermission(lastError)
            AppLog.w(TAG, "HyperIsland publish refused: $lastError")
        }
    }

    /** Pushes one HUD frame; dropped while not running, never reported as success when refused. */
    override fun update(data: HudRenderData) {
        content = IslandHudMapper.map(data, capabilities())
        if (!running) return
        if (!publishFrame()) {
            running = false
            lastError = "vendor bridge refused to update: ${bridge.integrationDetail()}"
            lastAvailability = IslandAvailability.needsVendorPermission(lastError)
            AppLog.w(TAG, "HyperIsland update refused: $lastError")
        }
    }

    override fun stop() {
        if (!running) return
        running = false
        try {
            notificationHost.cancel(NOTIFICATION_ID)
        } catch (t: Throwable) {
            AppLog.w(TAG, "cancelling the HyperIsland notification failed", t)
        }
    }

    override fun isRunning(): Boolean = running

    /** Last publish/update failure, surfaced in the diagnostics page. */
    fun lastError(): String = lastError

    private fun publishFrame(): Boolean {
        val params = XiaomiIslandParams.build(content, BUSINESS)
        val builder = newBuilder(content)
        return try {
            bridge.publish(notificationHost, NOTIFICATION_ID, builder, params)
        } catch (t: Throwable) {
            AppLog.e(TAG, "vendor bridge publish threw", t)
            false
        }
    }

    private fun refreshAvailabilityBlocking(): IslandAvailability = evaluate().also { lastAvailability = it }

    private fun evaluate(): IslandAvailability =
        HyperOsIslandPolicy.evaluate(probe(), notificationHost.areNotificationsEnabled())

    /** Collects the public facts once per availability refresh. */
    private fun probe(): HyperOsIslandProbe {
        val manufacturer = Build.MANUFACTURER ?: ""
        val brand = Build.BRAND ?: ""
        val display = Build.DISPLAY ?: ""
        val incremental = Build.VERSION.INCREMENTAL ?: ""
        val xiaomi = HyperOsIslandPolicy.isXiaomiDevice(manufacturer, brand)
        val hyperOs = xiaomi && HyperOsIslandPolicy.hasHyperOsMarker(display, incremental)
        val protocol = if (hyperOs) focusProtocolVersion() else 0
        val focusPermission = if (protocol >= HyperOsIslandPolicy.MIN_FOCUS_PROTOCOL_FOR_ISLAND) {
            focusPermissionGranted()
        } else {
            null
        }
        return HyperOsIslandProbe(
            manufacturer = manufacturer,
            brand = brand,
            display = display,
            incremental = incremental,
            focusProtocolVersion = protocol,
            focusPermissionGranted = focusPermission,
            bridgeIntegrated = bridge.isIntegrated(),
            bridgeDetail = bridge.integrationDetail(),
        )
    }

    /**
     * Reads the documented `notification_focus_protocol` system setting (1 = OS1, 2 = OS2 focus
     * notifications, 3 = OS3 HyperIsland). Returns `0` when the setting does not exist, which means
     * "not a HyperIsland capable ROM" for the policy.
     */
    private fun focusProtocolVersion(): Int = try {
        Settings.System.getInt(context.contentResolver, FOCUS_PROTOCOL_SETTING, 0)
    } catch (t: Throwable) {
        AppLog.d(TAG, "notification_focus_protocol unavailable: ${t.javaClass.simpleName}")
        0
    }

    /**
     * Queries the vendor documented public ContentProvider method `canShowFocus`. Returns `null` when
     * the provider is absent or the call is refused, so "unknown" is never reported as granted.
     */
    private fun focusPermissionGranted(): Boolean? = try {
        val extras = Bundle().apply { putString(FOCUS_PERMISSION_PACKAGE_KEY, context.packageName) }
        context.contentResolver
            .call(android.net.Uri.parse(FOCUS_PERMISSION_URI), FOCUS_PERMISSION_METHOD, null, extras)
            ?.getBoolean(FOCUS_PERMISSION_RESULT_KEY, false)
    } catch (t: Throwable) {
        AppLog.d(TAG, "canShowFocus query failed: ${t.javaClass.simpleName}")
        null
    }

    private fun newBuilder(content: IslandContent): Notification.Builder =
        Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIconRes())
            .setContentTitle(content.title.ifBlank { FALLBACK_TITLE })
            .setContentText(content.bodyText.ifBlank { content.shortText })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_PROGRESS)

    private fun smallIconRes(): Int =
        try {
            val icon = context.applicationInfo.icon
            if (icon != 0) icon else android.R.drawable.stat_sys_download
        } catch (t: Throwable) {
            android.R.drawable.stat_sys_download
        }

    private fun ensureChannel() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
        channel.description = CHANNEL_DESCRIPTION
        manager.createNotificationChannel(channel)
    }

    companion object {
        /** Stable provider id used in logs and settings. */
        const val ID: String = "xiaomi_hyper_island"

        /** Localization key of the provider name. */
        const val NAME_KEY: String = "island_provider_xiaomi_hyper_island"

        /** Notification channel used for the (would-be) island notification. */
        const val CHANNEL_ID: String = "ecp_island_hyper"

        private const val CHANNEL_NAME: String = "HUD HyperIsland"
        private const val CHANNEL_DESCRIPTION: String = "HyperIsland notification carrying the HUD status"

        /** Notification id of the single island notification. */
        const val NOTIFICATION_ID: Int = 0xEC9A02

        /** Vendor "business / 运营场景" value sent in `param_v2.business`. */
        const val BUSINESS: String = "charging"

        /** Documented system setting that reports the OS1/OS2/OS3 focus protocol version. */
        const val FOCUS_PROTOCOL_SETTING: String = "notification_focus_protocol"

        /** Documented vendor ContentProvider used to query the focus-notification permission. */
        const val FOCUS_PERMISSION_URI: String = "content://miui.statusbar.notification.public"
        const val FOCUS_PERMISSION_METHOD: String = "canShowFocus"
        const val FOCUS_PERMISSION_PACKAGE_KEY: String = "package"
        const val FOCUS_PERMISSION_RESULT_KEY: String = "canShowFocus"

        /**
         * Manifest `meta-data` names required by the vendor console integration. They are declared
         * here as constants because the application id itself is issued by Xiaomi per app and must be
         * filled in by the release owner (see the audit document).
         */
        const val META_DATA_APP_ID: String = "com.xiaomi.xms.APP_ID"
        const val META_DATA_BUILD_TYPE_DEBUG: String = "com.xiaomi.xms.BUILD_TYPE_DEBUG"

        /** Notification extra key that carries the island JSON. */
        const val EXTRA_FOCUS_PARAM: String = "miui.focus.param"

        /** Message key of the "vendor authorization missing" state (尚未授权 / 需要申请小米超级岛权限). */
        const val KEY_VENDOR_PERMISSION: String = "island_state_vendor_permission"

        private const val TAG: String = "IslandXiaomi"
        private const val FALLBACK_TITLE: String = "Endfield Charge Plus"

        /** What an authorized HyperIsland integration could express (template driven). */
        val CAPABILITIES: IslandCapabilities = IslandCapabilities(
            supportsTitle = true,
            supportsSubtitle = true,
            supportsProgress = true,
            supportsIcons = true,
            supportsMultipleLines = true,
            supportsCustomLayout = false,
            supportsContinuousUpdates = true,
            maxUpdateHz = 1.0,
            experimental = true,
            limitationKeys = listOf(
                "island_limit_vendor_review_required",
                "island_limit_no_custom_layout",
                "island_limit_scenario_restricted",
            ),
            supportsLeftRightSplit = false,
        )
    }
}

/**
 * The vendor publishing seam.
 *
 * Implementing this interface is the only way `XiaomiHyperIslandProvider` may ever reach HyperIsland.
 * A real implementation would add the `miui.focus.param` extra (plus `miui.focus.pics` /
 * `miui.focus.actions` for icons and buttons) to the notification and hand it to the host; it must
 * return `false` when the ROM or the vendor authorization refuses the publish.
 */
interface XiaomiIslandBridge {

    /** True only when a vendor-authorized integration is present in this build. */
    fun isIntegrated(): Boolean

    /** Human readable reason why publishing is or is not possible. */
    fun integrationDetail(): String

    /** Official documentation entry point for the integration. */
    fun documentationUrl(): String

    /**
     * Adds the island parameters to [builder] and publishes through [notificationHost].
     *
     * @return `true` only when the notification carrying the island parameters was really handed to
     *   the notification stack; implementations must never return `true` when they did not publish.
     */
    fun publish(
        notificationHost: IslandNotificationHost,
        notificationId: Int,
        builder: Notification.Builder,
        islandParamsJson: String,
    ): Boolean

    companion object {
        /** Official Xiaomi HyperOS developer documentation (接入流程). */
        const val DOCUMENTATION_ACCESS_URL: String =
            "https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2132"

        /** Official Xiaomi HyperOS developer documentation (开发指南). */
        const val DOCUMENTATION_GUIDE_URL: String =
            "https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131"

        /** Official Xiaomi HyperOS developer documentation (业务介绍 / admission principles). */
        const val DOCUMENTATION_PRODUCT_URL: String =
            "https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2140"
    }
}

/**
 * The deliberately non-integrated [XiaomiIslandBridge].
 *
 * This project has no Xiaomi enterprise developer account, no activated 超级岛 service, no
 * `com.xiaomi.xms.APP_ID` issued for this package and no vendor SDK artifact to bundle, so the only
 * correct behaviour is to report "not integrated" and refuse to publish. It never fakes success and
 * uses no reflection.
 */
object NotIntegratedBridge : XiaomiIslandBridge {

    private const val TAG: String = "IslandXiaomiBridge"

    override fun isIntegrated(): Boolean = false

    override fun integrationDetail(): String =
        "Xiaomi HyperIsland is not integrated in this build: the app has no Xiaomi enterprise developer " +
            "account, no activated island service, no issued com.xiaomi.xms.APP_ID and no scenario review " +
            "for this package; the vendor documents that an unauthorized app cannot publish an island"

    override fun documentationUrl(): String = XiaomiIslandBridge.DOCUMENTATION_ACCESS_URL

    override fun publish(
        notificationHost: IslandNotificationHost,
        notificationId: Int,
        builder: Notification.Builder,
        islandParamsJson: String,
    ): Boolean {
        AppLog.w(
            TAG,
            "refusing to publish HyperIsland: not integrated (${documentationUrl()}); " +
                "the ${islandParamsJson.length}-character island payload was not handed to the notification stack",
        )
        return false
    }
}

/**
 * Builds the `miui.focus.param` JSON payload documented by Xiaomi.
 *
 * Only the fields this product can fill honestly are emitted: the summary/expanded-area text comes
 * from [IslandContent] and the business value marks the scenario. The vendor key
 * `miui.focus.paramtextInfo` is quoted exactly as published by Xiaomi; it is not a typo introduced
 * here.
 */
object XiaomiIslandParams {

    /** Serializes the island payload for one HUD frame. */
    fun build(content: IslandContent, business: String): String = buildJsonObject {
        putJsonObject("param_v2") {
            put("protocol", 1)
            put("business", business)
            put("updatable", true)
            put("enableFloat", false)
            if (content.title.isNotBlank()) put("aodTitle", content.title)
            putJsonObject("param_island") {
                put("islandProperty", 1)
                if (content.accentColor.startsWith("#")) put("highlightColor", content.accentColor)
                putJsonObject("bigIslandArea") {
                    putJsonObject("imageTextInfoLeft") {
                        putJsonObject("miui.focus.paramtextInfo") {
                            put("frontTitle", content.title)
                            put("title", content.shortText)
                            put("content", content.bodyText)
                            put("useHighLight", false)
                        }
                    }
                }
                putJsonObject("smallIslandArea") {
                    putJsonObject("picInfo") {
                        put("type", 1)
                        if (content.iconKey.isNotBlank()) put("pic", content.iconKey)
                    }
                }
            }
        }
    }.toString()
}

/** Pure decision input for [HyperOsIslandPolicy], free of Android types so it is testable. */
data class HyperOsIslandProbe(
    val manufacturer: String,
    val brand: String,
    val display: String,
    val incremental: String,
    val focusProtocolVersion: Int,
    val focusPermissionGranted: Boolean?,
    val bridgeIntegrated: Boolean,
    val bridgeDetail: String = "",
)

/**
 * HyperOS detection and availability policy.
 *
 * Detection is limited to public build fields; the vendor-suggested `persist.sys.feature.island`
 * lookup requires reflection into `android.os.SystemProperties` and is deliberately not used.
 */
object HyperOsIslandPolicy {

    /** `notification_focus_protocol` value that means "OS3, HyperIsland capable". */
    const val MIN_FOCUS_PROTOCOL_FOR_ISLAND: Int = 3

    private val XIAOMI_BRANDS: Set<String> = setOf("xiaomi", "redmi", "poco")

    private val HYPER_OS_MARKERS: List<String> =
        listOf("hyperos", "hyper os", "os1.", "os2.", "os3.", "v816.", "v817.")

    /** True when the public manufacturer/brand information identifies a Xiaomi device. */
    fun isXiaomiDevice(manufacturer: String, brand: String): Boolean {
        val values = listOf(manufacturer, brand).map { it.trim().lowercase() }
        return values.any { value -> value.isNotEmpty() && (XIAOMI_BRANDS.contains(value) || value.contains("xiaomi")) }
    }

    /** True when the build strings carry a HyperOS marker (MIUI builds do not). */
    fun hasHyperOsMarker(display: String, incremental: String): Boolean {
        val haystack = (display + " " + incremental).lowercase()
        return HYPER_OS_MARKERS.any { haystack.contains(it) }
    }

    /**
     * Maps the probed facts to the availability shown in settings.
     *
     * Order matters: "not a HyperOS device" and "authorization missing" are the two states the user
     * must be able to distinguish, so device support is decided before the authorization state.
     */
    fun evaluate(probe: HyperOsIslandProbe, notificationsEnabled: Boolean): IslandAvailability {
        if (!isXiaomiDevice(probe.manufacturer, probe.brand)) {
            return IslandAvailability.unsupported(
                "manufacturer=${probe.manufacturer.ifBlank { "unknown" }}, " +
                    "brand=${probe.brand.ifBlank { "unknown" }} is not a Xiaomi HyperOS device",
            )
        }
        if (!hasHyperOsMarker(probe.display, probe.incremental)) {
            return IslandAvailability.unsupported(
                "Xiaomi device without a HyperOS marker in Build.DISPLAY/Build.VERSION.INCREMENTAL; " +
                    "MIUI has no HyperIsland API",
            )
        }
        if (probe.focusProtocolVersion in 1 until MIN_FOCUS_PROTOCOL_FOR_ISLAND) {
            return IslandAvailability.unsupported(
                "HyperIsland needs OS3 (notification_focus_protocol=$MIN_FOCUS_PROTOCOL_FOR_ISLAND); " +
                    "this ROM reports focus protocol ${probe.focusProtocolVersion} (OS1/OS2 focus notifications only)",
            )
        }
        if (!probe.bridgeIntegrated) {
            return IslandAvailability.needsVendorPermission(
                probe.bridgeDetail.ifBlank { "Xiaomi vendor authorization and scenario review are required" },
            )
        }
        if (!notificationsEnabled) {
            return IslandAvailability.unavailable(
                AndroidLiveUpdateProvider.KEY_NOTIFICATIONS_DISABLED,
                "notifications are disabled for this app, so even an authorized island cannot be posted",
            )
        }
        if (probe.focusPermissionGranted == false) {
            return IslandAvailability(
                IslandAvailabilityState.NOT_AUTHORIZED,
                AndroidLiveUpdateProvider.KEY_NOT_AUTHORIZED,
                "the user disabled focus notifications for this app; HyperIsland requires the focus permission",
            )
        }
        return IslandAvailability.available(
            "HyperOS with focus protocol ${probe.focusProtocolVersion} and an integrated, authorized vendor bridge",
        )
    }
}
