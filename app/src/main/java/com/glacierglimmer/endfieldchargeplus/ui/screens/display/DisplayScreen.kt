package com.glacierglimmer.endfieldchargeplus.ui.screens.display

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glacierglimmer.endfieldchargeplus.core.model.AnimationMode
import com.glacierglimmer.endfieldchargeplus.core.model.DisplayMode
import com.glacierglimmer.endfieldchargeplus.core.model.HudPosition
import com.glacierglimmer.endfieldchargeplus.core.model.HudPositionMode
import com.glacierglimmer.endfieldchargeplus.core.model.IslandProviderKind
import com.glacierglimmer.endfieldchargeplus.di.EcpContainer
import com.glacierglimmer.endfieldchargeplus.island.IslandAvailabilityState
import com.glacierglimmer.endfieldchargeplus.island.IslandCapabilities
import com.glacierglimmer.endfieldchargeplus.island.IslandProviderStatus
import com.glacierglimmer.endfieldchargeplus.localization.EcpMessages
import com.glacierglimmer.endfieldchargeplus.localization.keyed
import com.glacierglimmer.endfieldchargeplus.localization.keyedText
import com.glacierglimmer.endfieldchargeplus.localization.s
import com.glacierglimmer.endfieldchargeplus.localization.t
import com.glacierglimmer.endfieldchargeplus.ui.components.ChipTone
import com.glacierglimmer.endfieldchargeplus.ui.components.DropdownRow
import com.glacierglimmer.endfieldchargeplus.ui.components.InfoRow
import com.glacierglimmer.endfieldchargeplus.ui.components.NumberFieldRow
import com.glacierglimmer.endfieldchargeplus.ui.components.SectionCard
import com.glacierglimmer.endfieldchargeplus.ui.components.SegmentedRow
import com.glacierglimmer.endfieldchargeplus.ui.components.SliderRow
import com.glacierglimmer.endfieldchargeplus.ui.components.StatusChip
import com.glacierglimmer.endfieldchargeplus.ui.components.SwitchRow
import com.glacierglimmer.endfieldchargeplus.ui.screens.display.DisplayViewModel.Companion.factory as displayViewModelFactory
import com.glacierglimmer.endfieldchargeplus.ui.state.UiFormatting

/**
 * 显示 / Display.
 *
 * Overlay configuration (position, scale, opacity, touch behaviour) and the island backend with its
 * honest availability and capability limitations.
 */
@Composable
fun DisplayScreen(
    container: EcpContainer,
    onNavigate: (com.glacierglimmer.endfieldchargeplus.ui.navigation.EcpDestination) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DisplayViewModel = viewModel(factory = displayViewModelFactory(container)),
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val islands by viewModel.islands.collectAsStateWithLifecycle()
    val pendingIntent by viewModel.pendingIntent.collectAsStateWithLifecycle()
    val authorizationLaunched by viewModel.authorizationLaunched.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) { viewModel.refreshIslands() }
    pendingIntent?.let { intent ->
        LaunchedEffect(intent) {
            runCatching { context.startActivity(intent) }
            viewModel.consumeIntent()
        }
    }

    val displayMode = DisplayMode.fromWire(config.android.displayMode)
    val overlayGranted = viewModel.isOverlayGranted()
    val profile = config.customHud.activeProfile()

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            SectionCard(
                title = t("显示方式", "Display mode"),
                subtitle = t(
                    "悬浮窗使用系统级应用程序窗口；灵动岛使用平台/厂商的状态栏接口。",
                    "The overlay uses a real system application window; the island uses the platform or vendor status-bar API.",
                ),
            ) {
                Column {
                    SegmentedRow(
                        options = DisplayMode.entries,
                        selected = displayMode,
                        label = { mode -> displayModeLabel(mode) },
                        onSelect = viewModel::setDisplayMode,
                    )
                    Spacer(Modifier.height(6.dp))
                    SwitchRow(
                        title = t("HUD 总开关", "HUD master switch"),
                        subtitle = t(
                            "关闭后不再显示任何 HUD 输出。",
                            "When off, no HUD output is shown at all.",
                        ),
                        checked = config.hudEnabled,
                        onCheckedChange = viewModel::setHudEnabled,
                    )
                    SwitchRow(
                        title = t("一直显示", "Always visible"),
                        subtitle = t(
                            "关闭时按需显示（电源插拔/触发唤出）。",
                            "When off, the HUD appears on demand (power events / reveal trigger).",
                        ),
                        checked = config.android.alwaysVisible,
                        onCheckedChange = viewModel::setAlwaysVisible,
                    )
                    SwitchRow(
                        title = t("点击穿透", "Click-through"),
                        subtitle = t(
                            "开启后 HUD 不接收触摸，操作始终落到下面的应用。",
                            "When on, the HUD never consumes touches; input goes to the app underneath.",
                        ),
                        checked = config.android.clickThrough,
                        onCheckedChange = viewModel::setClickThrough,
                    )
                    SwitchRow(
                        title = t("避开刘海 / 挖孔", "Avoid cutout"),
                        subtitle = t(
                            "锚定 HUD 时避开状态栏与显示挖孔区域。",
                            "Keeps the anchored HUD clear of the status bar and the display cutout.",
                        ),
                        checked = config.android.avoidCutout,
                        onCheckedChange = viewModel::setAvoidCutout,
                    )
                    SwitchRow(
                        title = t("开机自动启动", "Start on boot"),
                        subtitle = t(
                            "仅在系统允许且你已开启悬浮窗权限时生效。",
                            "Only effective when the system allows it and the overlay permission is granted.",
                        ),
                        checked = config.android.startOnBoot,
                        onCheckedChange = viewModel::setStartOnBoot,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = t("动画模式", "Animation"),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    SegmentedRow(
                        options = AnimationMode.entries,
                        selected = AnimationMode.fromWire(profile?.animationMode),
                        label = { mode ->
                            when (mode) {
                                AnimationMode.SIMPLE -> s("简洁", "Simple")
                                AnimationMode.FULL -> s("完整", "Full")
                            }
                        },
                        onSelect = viewModel::setAnimationMode,
                    )
                    if (!overlayGranted) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = t(
                                "尚未授予悬浮窗权限：启用悬浮窗时会打开系统设置页面。",
                                "Overlay permission is not granted yet; enabling the overlay opens the system settings page.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }

        item {
            SectionCard(
                title = t("悬浮窗位置与外观", "Overlay position and appearance"),
                subtitle = t(
                    "使用预设锚点加微调，或指定自定义坐标。",
                    "Use a preset anchor with offsets, or specify custom coordinates.",
                ),
            ) {
                Column {
                    DropdownRow(
                        title = t("定位方式", "Positioning"),
                        options = listOf(
                            t("预设位置 + 微调", "Preset + offset"),
                            t("自定义坐标", "Custom coordinates"),
                        ),
                        selectedIndex = if (config.positionModeEnum == HudPositionMode.CUSTOM_COORDINATES) 1 else 0,
                        onSelected = { index ->
                            viewModel.setPositionMode(
                                if (index == 1) HudPositionMode.CUSTOM_COORDINATES else HudPositionMode.PRESET,
                            )
                        },
                    )
                    if (config.positionModeEnum == HudPositionMode.PRESET) {
                        DropdownRow(
                            title = t("预设位置", "Preset"),
                            options = HudPosition.entries.map { positionLabel(it) },
                            selectedIndex = HudPosition.entries.indexOf(config.positionEnum)
                                .coerceAtLeast(0),
                            onSelected = { index -> viewModel.setHudPosition(HudPosition.entries[index]) },
                        )
                        IntField(
                            label = t("X 微调 / px", "X offset / px"),
                            value = config.hudOffsetX,
                            onValueChange = viewModel::setOffsetX,
                            key = "offsetX",
                        )
                        IntField(
                            label = t("Y 微调 / px", "Y offset / px"),
                            value = config.hudOffsetY,
                            onValueChange = viewModel::setOffsetY,
                            key = "offsetY",
                        )
                        Text(
                            text = t("X 向右为正，Y 向下为正。", "+X is right, +Y is down."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    } else {
                        IntField(
                            label = t("X 坐标 / px", "X / px"),
                            value = config.hudCustomX,
                            onValueChange = viewModel::setCustomX,
                            key = "customX",
                        )
                        IntField(
                            label = t("Y 坐标 / px", "Y / px"),
                            value = config.hudCustomY,
                            onValueChange = viewModel::setCustomY,
                            key = "customY",
                        )
                    }
                    SliderRow(
                        title = t("HUD 缩放", "HUD scale"),
                        subtitle = t("0.4 – 1.4 倍。", "0.4 – 1.4×."),
                        value = config.globalScale.toFloat(),
                        onValueChange = { viewModel.setGlobalScale(it.toDouble()) },
                        valueRange = 0.4f..1.4f,
                        valueLabel = String.format(java.util.Locale.US, "%.2f×", config.globalScale),
                    )
                    SliderRow(
                        title = t("HUD 不透明度", "HUD opacity"),
                        subtitle = t("10% – 100%。", "10% – 100%."),
                        value = config.hudOpacity.toFloat(),
                        onValueChange = { viewModel.setHudOpacity(it.toDouble()) },
                        valueRange = 0.10f..1.0f,
                        valueLabel = UiFormatting.percentText(config.hudOpacity),
                    )
                }
            }
        }

        item {
            SectionCard(
                title = t("灵动岛 / 实时通知", "Island / live update"),
                subtitle = t(
                    "未支持或未授权的后端会如实显示原因，绝不伪装已连接。",
                    "An unsupported or unauthorized backend states its reason; the app never pretends it is connected.",
                ),
                trailing = {
                    androidx.compose.material3.TextButton(onClick = viewModel::refreshIslands) {
                        Text(t("重新检测", "Re-check"))
                    }
                },
            ) {
                Column {
                    DropdownRow(
                        title = t("后端", "Backend"),
                        options = IslandProviderKind.entries.map { islandKindLabel(it) },
                        selectedIndex = IslandProviderKind.entries
                            .indexOf(IslandProviderKind.fromWire(config.android.islandProvider))
                            .coerceAtLeast(0),
                        onSelected = { index -> viewModel.setIslandProvider(IslandProviderKind.entries[index]) },
                    )
                    if (authorizationLaunched) {
                        Text(
                            text = keyed("island_authorization_launched"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    if (islands.isEmpty()) {
                        Text(
                            text = t(
                                "尚未注册任何灵动岛后端。",
                                "No island backend is registered on this build.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    } else {
                        islands.forEach { status ->
                            IslandStatusCard(
                                status = status,
                                onRequestAuthorization = {
                                    viewModel.requestAuthorization(status.provider, context as? Activity)
                                },
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun IntField(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    key: String,
) {
    var text by remember(key) { mutableStateOf(value.toString()) }
    NumberFieldRow(
        label = label,
        value = text,
        onValueChange = { updated ->
            text = updated
            UiFormatting.parseInt(updated)?.let(onValueChange)
        },
        isError = UiFormatting.parseInt(text) == null,
        supporting = if (UiFormatting.parseInt(text) == null) {
            t("请输入整数。", "Enter a whole number.")
        } else {
            null
        },
    )
}

@Composable
private fun IslandStatusCard(
    status: IslandProviderStatus,
    onRequestAuthorization: () -> Unit,
) {
    val availability = status.availability
    val capabilities = status.capabilities
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = keyed(status.provider.nameKey),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = keyed(availability.messageKey).ifBlank {
                        EcpMessages.islandState(availability.state)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (status.provider.isPreferred()) {
                StatusChip(text = keyed("island_provider_preferred"), tone = ChipTone.INFO)
                Spacer(Modifier.width(6.dp))
            }
            StatusChip(
                text = EcpMessages.islandState(availability.state),
                tone = when (availability.state) {
                    IslandAvailabilityState.AVAILABLE -> ChipTone.OK
                    IslandAvailabilityState.UNSUPPORTED_BY_PLATFORM -> ChipTone.OFF
                    else -> ChipTone.WARN
                },
            )
        }
        if (availability.detail.isNotBlank()) {
            Text(
                text = availability.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        CapabilityRows(capabilities)
        if (capabilities.limitationKeys.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = t("接口限制", "API limitations"),
                style = MaterialTheme.typography.labelLarge,
            )
            capabilities.limitationKeys.forEach { key ->
                Text(
                    text = "• " + keyed(key),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (availability.state == IslandAvailabilityState.NOT_AUTHORIZED ||
            availability.state == IslandAvailabilityState.VENDOR_PERMISSION_REQUIRED
        ) {
            OutlinedButton(
                onClick = onRequestAuthorization,
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Text(keyed("island_provider_request_authorization"))
            }
        }
    }
}

@Composable
private fun CapabilityRows(capabilities: IslandCapabilities) {
    Column {
        InfoRow(t("标题", "Title"), yesNo(capabilities.supportsTitle))
        InfoRow(t("副标题", "Subtitle"), yesNo(capabilities.supportsSubtitle))
        InfoRow(t("进度", "Progress"), yesNo(capabilities.supportsProgress))
        InfoRow(t("图标", "Icons"), yesNo(capabilities.supportsIcons))
        InfoRow(t("多行文本", "Multiple lines"), yesNo(capabilities.supportsMultipleLines))
        InfoRow(t("自定义布局", "Custom layout"), yesNo(capabilities.supportsCustomLayout))
        InfoRow(t("左右分区", "Left/right split"), yesNo(capabilities.supportsLeftRightSplit))
        InfoRow(t("持续更新", "Continuous updates"), yesNo(capabilities.supportsContinuousUpdates))
        InfoRow(
            t("最大刷新率", "Max update rate"),
            String.format(java.util.Locale.US, "%.1f Hz", capabilities.maxUpdateHz),
        )
        if (capabilities.experimental) {
            InfoRow(t("实验性", "Experimental"), t("是", "Yes"))
        }
    }
}

// The label helpers below are deliberately non-composable: Compose cannot treat the
// `label = { … }` / `map { … }` lambdas that use them as composable scopes. They read the shared
// Strings table; the screen reads LocalUiLanguage in its own body, so a language switch still
// recomposes the page.
private fun yesNo(value: Boolean): String = if (value) s("是", "Yes") else s("否", "No")

private fun displayModeLabel(mode: DisplayMode): String = when (mode) {
    DisplayMode.OVERLAY -> s("悬浮窗", "Overlay")
    DisplayMode.ISLAND -> s("灵动岛接口", "Island")
}

private fun islandKindLabel(kind: IslandProviderKind): String = when (kind) {
    IslandProviderKind.AUTO -> keyedText("island_provider_auto")
    IslandProviderKind.ANDROID_SYSTEM -> keyedText("island_provider_android_system")
    IslandProviderKind.XIAOMI_HYPER_ISLAND -> keyedText("island_provider_xiaomi_hyper_island")
    IslandProviderKind.NONE -> keyedText("island_provider_none")
}

private fun positionLabel(position: HudPosition): String = when (position) {
    HudPosition.TOP_LEFT -> s("左上", "Top left")
    HudPosition.TOP_CENTER -> s("顶部居中", "Top center")
    HudPosition.TOP_RIGHT -> s("右上", "Top right")
    HudPosition.CENTER_LEFT -> s("左侧居中", "Center left")
    HudPosition.CENTER -> s("屏幕居中", "Center")
    HudPosition.CENTER_RIGHT -> s("右侧居中", "Center right")
    HudPosition.BOTTOM_LEFT -> s("左下", "Bottom left")
    HudPosition.BOTTOM_CENTER -> s("底部居中", "Bottom center")
    HudPosition.BOTTOM_RIGHT -> s("右下", "Bottom right")
}
