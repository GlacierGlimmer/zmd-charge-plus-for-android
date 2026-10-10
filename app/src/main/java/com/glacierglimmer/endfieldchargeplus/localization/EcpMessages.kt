package com.glacierglimmer.endfieldchargeplus.localization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import com.glacierglimmer.endfieldchargeplus.core.i18n.BuiltInProfileLocalization
import com.glacierglimmer.endfieldchargeplus.core.i18n.Strings
import com.glacierglimmer.endfieldchargeplus.core.i18n.UiLanguage
import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile
import com.glacierglimmer.endfieldchargeplus.core.model.UnavailableReason
import com.glacierglimmer.endfieldchargeplus.island.IslandAvailabilityState
import com.glacierglimmer.endfieldchargeplus.permission.EcpPermission

/**
 * The concrete language the settings shell is rendering in.
 *
 * Reading this value inside a composable is what makes the whole UI recompose after a language
 * change; the string tables themselves live in the shared core [Strings] object so that the HUD,
 * the foreground notification and the settings UI always agree.
 */
val LocalUiLanguage = compositionLocalOf { UiLanguage.ZH_CN }

/** Bilingual text through the shared core table, recomposing when the language changes. */
@Composable
fun t(zh: String, en: String): String {
    return if (LocalUiLanguage.current == UiLanguage.EN) en else zh
}

/**
 * Bilingual text for **non-composable** call sites (for example a `label = { … }` lambda or a
 * `map { … }` that builds dropdown options).
 *
 * The language itself is shared state in [Strings], so the text is always correct; the screen that
 * owns the lambda reads [LocalUiLanguage] in its own body (through [t] or directly), which is what
 * makes it recompose and recreate the lambda after a language switch.
 */
fun s(zh: String, en: String): String = Strings.t(zh, en)

/** Key-addressed variant of [s] for non-composable call sites. */
fun keyedText(key: String): String = EcpMessages.t(key)

/**
 * Text addressed by a stable localization key.
 *
 * Keys come from contracts that live outside the UI (`PermissionState.messageKey`,
 * `Capability.reasonKey`, `IslandAvailability.messageKey`, `IslandCapabilities.limitationKeys`), so
 * they are resolved here in one place. Unknown keys degrade to a readable ASCII form instead of
 * leaking a key name or a Chinese literal into an English screen.
 */
@Composable
fun keyed(key: String): String {
    return if (LocalUiLanguage.current == UiLanguage.EN) EcpMessages.en(key) ?: EcpMessages.t(key)
        else EcpMessages.zh(key) ?: EcpMessages.t(key)
}

/**
 * All key-addressed strings of the settings shell.
 *
 * The desktop editions key their static UI text by the Simplified-Chinese literal; on Android the
 * UI is bilingual through [Strings.t], and the keys below are the additional ones the contracts
 * hand to the UI (permission, capability, island). Every entry carries both languages, and the
 * unit test asserts that no Chinese character can reach an English screen.
 */
object EcpMessages {

    private val table: Map<String, Pair<String, String>> = buildMap {
        // ---------------------------------------------------------------- permission classes
        put("permission.overlay.granted", "已允许显示在其他应用上层。" to "Drawing over other apps is allowed.")
        put("permission.overlay.required", "需要在系统设置中允许显示在其他应用上层。" to "Allow drawing over other apps in system settings.")
        put("permission.notifications.granted", "已允许显示通知。" to "Notifications are allowed.")
        put("permission.notifications.required", "需要开启应用通知权限。" to "Enable notification permission for the app.")
        put("permission.foreground_service.declared", "已配置前台服务权限，运行 HUD 时使用。" to "Foreground service permission is declared for the HUD.")
        put("permission.foreground_service.missing", "应用缺少前台服务权限。" to "The app is missing foreground service permission.")
        put("permission.network.connected", "网络已连接。" to "The network is connected.")
        put("permission.network.offline", "当前没有可用网络。" to "No network is currently available.")
        put("permission.network.missing", "应用缺少网络访问权限。" to "The app is missing network access permission.")
        put("permission.boot_start.declared", "已配置开机启动权限，可在设置中开启。" to "Boot permission is declared; enable startup in settings.")
        put("permission.boot_start.missing", "应用缺少开机启动权限。" to "The app is missing boot permission.")
        put("permission.live_update.granted", "系统允许实时通知接口。" to "The system allows live update notifications.")
        put("permission.live_update.unavailable", "本设备暂不支持实时通知，或尚未授权。" to "Live updates are unsupported or not authorized on this device.")
        put("permission_overlay", "悬浮窗权限" to "Overlay permission")
        put("permission_notifications", "通知权限" to "Notification permission")
        put("permission_foreground_service", "前台服务" to "Foreground service")
        put("permission_network", "网络访问" to "Network access")
        put("permission_boot_start", "开机自启" to "Start on boot")
        put("permission_live_update", "实时通知接口" to "Live update API")
        put("permission_overlay_required", "启用悬浮 HUD 前需要授予“显示在其他应用上层”。" to
            "Turn on \"Display over other apps\" before enabling the floating HUD.")
        put("permission_notifications_required", "启动 HUD 时需要通知权限来显示常驻通知。" to
            "Starting the HUD needs notification permission for its ongoing notification.")
        put("permission_granted", "已授予" to "Granted")
        put("permission_denied", "未授予" to "Not granted")
        put("permission_granted_explanation", "系统已经允许该功能。" to
            "The system has already allowed this feature.")
        put("permission_open_settings", "打开系统设置" to "Open system settings")
        put("permission_requested_before", "之前已请求过；请在系统设置中重新开启。" to
            "Already requested once; re-enable it in system settings.")

        // ---------------------------------------------------------------- capability reasons
        put("capability_not_scanned", "尚未检测硬件能力。" to "Hardware capabilities have not been scanned yet.")
        put("capability_partial", "仅能读取部分数据。" to "Only part of this metric can be read.")
        put("capability_not_supported", "Android 没有公开接口。" to "Android exposes no public API for this.")
        put("capability_permission_required", "需要额外权限或特殊访问。" to
            "An extra permission or special access is required.")
        put("capability_not_available", "本设备/内核未提供该数据源。" to
            "This device or kernel does not provide the data source.")
        put("capability_no_data", "尚未采样到数据。" to "No sample has been collected yet.")
        put("capability_disabled", "该数据源已被关闭。" to "This data source is turned off.")
        put("capability_network_error", "网络请求失败。" to "The network request failed.")
        put("capability_not_authorized", "尚未获得授权。" to "Authorization has not been granted.")
        put("capability_no_public_api", "系统未公开此接口。" to "The platform does not publish this API.")
        put("capability_api_level", "系统版本过低，缺少对应接口。" to
            "The Android version is too low for this API.")
        put("capability_hidden_api", "仅系统应用可读（隐藏接口）。" to
            "Readable only by system apps (hidden API).")
        put("capability_vendor_only", "仅厂商应用可读。" to "Readable only by vendor applications.")
        put("capability_sysfs_missing", "设备未暴露对应的内核节点。" to
            "The device does not expose the matching kernel node.")
        put("capability_battery_stats_unavailable", "电池统计接口不可用或被系统限制。" to
            "Battery statistics are unavailable or restricted by the system.")
        put("capability_sensor_missing", "设备没有该传感器。" to "The device has no such sensor.")
        put("capability_storage_scoped", "分区存储限制了可读范围。" to
            "Scoped storage limits what can be read.")
        put("capability_scoped_storage", "分区存储限制了可读范围。" to
            "Scoped storage limits what can be read.")
        put("capability_requires_root", "需要 root 权限。" to "Root access would be required.")
        put("capability_requires_shizuku", "需要 Shizuku 等提权服务。" to
            "An elevated helper such as Shizuku would be required.")
        put("capability_gpu_no_api", "Android 未公开 GPU 占用接口。" to
            "Android publishes no public GPU load API.")
        put("capability_network_unavailable", "当前网络不可达。" to "The network is unreachable right now.")
        put("capability_wifi_permission", "需要位置信息权限才能读取 Wi-Fi 名称。" to
            "Reading the Wi-Fi name needs location permission.")
        put("capability_icmp_restricted", "部分网络禁止 ICMP，探测会自动回退到 TCP。" to
            "Some networks block ICMP; the probe falls back to TCP.")

        // ---------------------------------------------------------------- island states
        // The island layer emits these keys verbatim (frozen contract, see
        // docs/audit/03-island-platforms.md); the wording matches res/values*/strings.xml.
        put("island_state_available", "可用" to "Available")
        put("island_state_unsupported", "不支持" to "Not supported")
        put("island_state_not_authorized", "尚未授权" to "Not authorized")
        put("island_state_notifications_disabled", "通知已关闭" to "Notifications are disabled")
        put("island_state_channel_disabled", "通知渠道已关闭" to "The notification channel is disabled")
        put("island_state_promotion_check_failed", "无法确认灵动岛资格" to "Could not verify island eligibility")
        put("island_state_not_promotable", "当前设备/场景不可用" to "Not available on this device or scenario")
        put("island_state_publish_failed", "发布失败" to "Publishing failed")
        // Generic fallback, used only when a provider reports UNAVAILABLE with a key of its own.
        put("island_state_unavailable", "不可用" to "Unavailable")

        // ---------------------------------------------------------------- island limitations
        put("island_limit_no_custom_layout", "不支持自定义布局" to "No custom layout")
        put("island_limit_no_left_right_split", "不支持左右分区显示" to "No left/right split")
        put("island_limit_scenario_restricted", "系统限制使用场景" to "Restricted usage scenarios")
        put("island_limit_platform_colorized_required", "需要平台彩色化形态" to "Requires the platform colorized shape")
        put("island_limit_throttled_updates", "系统会限制更新频率" to "The system throttles update frequency")
        put("island_limit_vendor_review_required", "需要厂商审核授权" to "Vendor review and authorization required")
        // Additional limitation keys the UI can render when a backend reports them.
        put("island_limit_no_progress", "该接口不支持进度显示。" to "This API cannot show progress.")
        put("island_limit_no_icons", "该接口不支持图标。" to "This API cannot show icons.")
        put("island_limit_single_line", "仅支持单行文本。" to "Only a single line of text is supported.")
        put("island_limit_requires_notification", "必须保留常驻通知。" to
            "An ongoing notification must stay visible.")
        put("island_limit_experimental", "该后端为实验性实现。" to "This backend is experimental.")
        put("island_limit_android_16_required", "需要 Android 16 及以上。" to "Android 16 or newer is required.")
        put("island_limit_no_arbitrary_views", "不允许任意视图，只能使用官方模板。" to
            "Arbitrary views are not allowed; only official templates can be used.")

        // ---------------------------------------------------------------- island providers
        put("island_provider_auto", "自动选择" to "Automatic")
        put("island_provider_android_system", "Android 系统" to "Android system")
        put("island_provider_none", "不使用灵动岛" to "No island output")
        put("island_provider_preferred", "推荐" to "Recommended")
        put("island_provider_request_authorization", "申请授权" to "Request authorization")
        put("island_authorization_launched", "已打开授权界面，返回后会自动重新检测。" to
            "The authorization screen was opened; availability is re-checked on return.")

        // ---------------------------------------------------------------- misc keys
        put("log_level_debug", "详细" to "Detailed")
        put("log_level_info", "信息" to "Info")
        put("log_level_warn", "警告" to "Warning")
        put("log_level_error", "错误" to "Error")
        put("share_log_title", "分享诊断日志" to "Share diagnostics log")
        put("share_diagnostics_title", "分享诊断报告" to "Share diagnostics report")
        put("share_config_title", "分享配置" to "Share configuration")
        put("unavailable_reason_not_supported", "系统不支持" to "Not supported by the system")
        put("unavailable_reason_permission_required", "缺少权限" to "Permission required")
        put("unavailable_reason_not_available_on_device", "设备不支持" to "Not available on this device")
        put("unavailable_reason_no_data", "尚无数据" to "No data yet")
        put("unavailable_reason_disabled", "已关闭" to "Turned off")
        put("unavailable_reason_network_error", "网络错误" to "Network error")
        put("unavailable_reason_not_authorized", "未授权" to "Not authorized")
    }

    /** Localized text for [key]; unknown keys degrade to a readable ASCII form. */
    fun t(key: String): String {
        val pair = table[key] ?: return fallback(key)
        return Strings.t(pair.first, pair.second)
    }

    /** Chinese text for [key], or null when the key is unknown. */
    fun zh(key: String): String? = table[key]?.first

    /** English text for [key], or null when the key is unknown. */
    fun en(key: String): String? = table[key]?.second

    /** Every key this object can resolve; used by the unit test that guards the tables. */
    fun keys(): Set<String> = table.keys

    /** True when [key] is a known key of this object. */
    fun knows(key: String): Boolean = table.containsKey(key)

    private fun fallback(key: String): String =
        key.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }

    // ------------------------------------------------------------------ enum-driven helpers

    /** Title of one permission class. */
    fun permissionTitle(permission: EcpPermission): String = t(permissionKey(permission))

    /** Why the product needs this permission, in the current language. */
    fun permissionExplanation(permission: EcpPermission): String = when (permission) {
        EcpPermission.OVERLAY -> Strings.t(
            "允许 HUD 绘制在其他应用上层；只有你打开悬浮 HUD 时才会请求。",
            "Lets the HUD draw over other apps; only requested when you enable the floating HUD.",
        )
        EcpPermission.NOTIFICATIONS -> Strings.t(
            "Android 13 及以上需要通知权限来显示 HUD 的常驻通知；启动 HUD 时才会请求。",
            "Android 13+ needs notification permission for the HUD's ongoing notification; requested only when the HUD starts.",
        )
        EcpPermission.FOREGROUND_SERVICE -> Strings.t(
            "长期显示 HUD 需要前台服务，否则系统会终止采样。",
            "A foreground service keeps the HUD alive; without it the system stops sampling.",
        )
        EcpPermission.NETWORK -> Strings.t(
            "用于网络速率、丢包探测、DeepSeek 余额与自定义 HTTP 数据源。",
            "Used for network rates, packet probing, DeepSeek balance and custom HTTP sources.",
        )
        EcpPermission.BOOT_START -> Strings.t(
            "仅在你主动打开“开机自动启动”后才使用。",
            "Only used after you turn on \"start on boot\" yourself.",
        )
        EcpPermission.LIVE_UPDATE -> Strings.t(
            "Android 的实时通知/灵动岛接口，用于在状态栏展示 HUD 内容。",
            "The Android live update / island API used to show HUD content around the status bar.",
        )
    }

    /** Stable key of one permission class. */
    fun permissionKey(permission: EcpPermission): String = when (permission) {
        EcpPermission.OVERLAY -> "permission_overlay"
        EcpPermission.NOTIFICATIONS -> "permission_notifications"
        EcpPermission.FOREGROUND_SERVICE -> "permission_foreground_service"
        EcpPermission.NETWORK -> "permission_network"
        EcpPermission.BOOT_START -> "permission_boot_start"
        EcpPermission.LIVE_UPDATE -> "permission_live_update"
    }

    /** Localized label for a value that the platform could not produce. */
    fun unavailableReason(reason: UnavailableReason): String = when (reason) {
        UnavailableReason.NOT_SUPPORTED -> t("unavailable_reason_not_supported")
        UnavailableReason.PERMISSION_REQUIRED -> t("unavailable_reason_permission_required")
        UnavailableReason.NOT_AVAILABLE_ON_DEVICE -> t("unavailable_reason_not_available_on_device")
        UnavailableReason.NO_DATA -> t("unavailable_reason_no_data")
        UnavailableReason.DISABLED -> t("unavailable_reason_disabled")
        UnavailableReason.NETWORK_ERROR -> t("unavailable_reason_network_error")
        UnavailableReason.NOT_AUTHORIZED -> t("unavailable_reason_not_authorized")
    }

    /** Localized label for one island availability state. */
    fun islandState(state: IslandAvailabilityState): String = when (state) {
        IslandAvailabilityState.AVAILABLE -> t("island_state_available")
        IslandAvailabilityState.UNSUPPORTED_BY_PLATFORM -> t("island_state_unsupported")
        IslandAvailabilityState.NOT_AUTHORIZED -> t("island_state_not_authorized")
        IslandAvailabilityState.UNAVAILABLE -> t("island_state_unavailable")
    }

    // ------------------------------------------------------------------ shared engine helpers

    /**
     * Display name of a scheme. Built-in schemes are localized by the engine's
     * `BuiltInProfileLocalization`; a custom scheme keeps the name the user typed.
     */
    fun profileDisplayName(profile: HudProfile?, language: UiLanguage): String {
        if (profile == null) return Strings.t("未选择方案", "No active profile")
        return BuiltInProfileLocalization.displayName(profile, language)
    }

    /** Localized category of a scheme or variable. */
    fun categoryName(category: String, language: UiLanguage): String =
        BuiltInProfileLocalization.categoryName(category, language)

    /**
     * Localized label of a `VariableType` enum constant.
     *
     * The enum lives in the engine module, so it is matched by name; an unknown value falls through
     * unchanged rather than inventing a translation.
     */
    fun variableTypeLabel(typeName: String): String = when (typeName.uppercase()) {
        "NUMBER" -> Strings.t("数值", "Number")
        "TEXT" -> Strings.t("文本", "Text")
        "BOOLEAN" -> Strings.t("布尔", "Boolean")
        "INTEGER" -> Strings.t("整数", "Integer")
        "DURATION" -> Strings.t("时长", "Duration")
        "BYTES" -> Strings.t("字节", "Bytes")
        "PERCENT" -> Strings.t("百分比", "Percent")
        "PROGRESS" -> Strings.t("进度", "Progress")
        "UNAVAILABLE" -> Strings.t("不支持", "Not supported")
        "SPEED" -> Strings.t("速率", "Speed")
        else -> typeName
    }
}
