package com.glacierglimmer.endfieldchargeplus.ui.screens.display

import android.app.Activity
import android.os.Build
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glacierglimmer.endfieldchargeplus.core.model.AnimationMode
import com.glacierglimmer.endfieldchargeplus.core.model.DisplayMode
import com.glacierglimmer.endfieldchargeplus.core.model.HudPosition
import com.glacierglimmer.endfieldchargeplus.core.model.HudPositionMode
import com.glacierglimmer.endfieldchargeplus.di.EcpContainer
import com.glacierglimmer.endfieldchargeplus.island.IslandAvailabilityState
import com.glacierglimmer.endfieldchargeplus.island.IslandProviderStatus
import com.glacierglimmer.endfieldchargeplus.localization.EcpMessages
import com.glacierglimmer.endfieldchargeplus.localization.keyed
import com.glacierglimmer.endfieldchargeplus.localization.s
import com.glacierglimmer.endfieldchargeplus.localization.t
import com.glacierglimmer.endfieldchargeplus.ui.components.ChipTone
import com.glacierglimmer.endfieldchargeplus.ui.components.DropdownRow
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

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshIslands() }
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
                    "悬浮窗使用系统级应用程序窗口；灵动岛使用 Android 原生实时通知接口。",
                    "The overlay uses a real system application window; the island uses the native Android live update API.",
                ),
            ) {
                Column {
                    SegmentedRow(
                        options = DisplayMode.entries.filter { it == DisplayMode.OVERLAY || Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA },
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
                    if (displayMode == DisplayMode.OVERLAY) {
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

                    }
                    SwitchRow(
                        title = t("开机自动启动", "Start on boot"),
                        subtitle = t(
                            "受系统后台启动限制，并使用当前显示方式所需的权限。",
                            "Subject to background-start limits and permissions for the selected display mode.",
                        ),
                        checked = config.android.startOnBoot,
                        onCheckedChange = viewModel::setStartOnBoot,
                    )
                    if (displayMode == DisplayMode.OVERLAY && profile != null) {
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
        }

        if (displayMode == DisplayMode.OVERLAY) {
            item {
                SectionCard(
                    title = t("悬浮窗位置与外观", "Overlay position and appearance"),
                    subtitle = t(
                        "使用预设锚点加微调，或指定自定义坐标。",
                        "Use a preset anchor with offsets, or specify custom coordinates.",
                    ),
                ) {
                    Column {
                        if (config.android.useDraggedPosition) {
                            androidx.compose.material3.TextButton(onClick = viewModel::restoreAnchor) {
                                Text(t("已自由拖动 · 恢复所选锚点", "Dragged position · restore selected anchor"))
                            }
                        }
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
                            subtitle = t("0.4 – 1.4 倍，松开后应用。", "0.4 – 1.4×. Applied on release."),
                            value = config.globalScale.toFloat(),
                            onValueChange = { viewModel.setGlobalScale(it.toDouble()) },
                            valueRange = 0.4f..1.4f,
                            valueLabel = String.format(java.util.Locale.US, "%.2f×", config.globalScale),
                            formatValue = { String.format(java.util.Locale.US, "%.2f×", it) },
                        )
                        SliderRow(
                            title = t("HUD 不透明度", "HUD opacity"),
                            subtitle = t("10% – 100%，松开后应用。", "10% – 100%. Applied on release."),
                            value = config.hudOpacity.toFloat(),
                            onValueChange = { viewModel.setHudOpacity(it.toDouble()) },
                            valueRange = 0.10f..1.0f,
                            valueLabel = UiFormatting.percentText(config.hudOpacity),
                            formatValue = { UiFormatting.percentText(it.toDouble()) },
                        )
                    }
                }
            }


        }
        if (displayMode == DisplayMode.ISLAND) {
            item {
                SectionCard(
                    title = t("Android 原生灵动岛", "Android native live update"),
                    subtitle = t(
                        "需要系统支持，并允许应用显示实时通知。",
                        "Requires system support and permission to show live updates.",
                    ),
                    trailing = {
                        androidx.compose.material3.TextButton(onClick = viewModel::refreshIslands) {
                            Text(t("重新检测", "Re-check"))
                        }
                    },
                ) {
                    Column {
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
                                    "正在检测 Android 原生灵动岛…",
                                    "Checking Android native live updates…",
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
                text = if (availability.state == IslandAvailabilityState.AVAILABLE)
                    t("接口可用；是否显示灵动岛由系统决定。", "The API is available; the system decides whether to show the island.")
                    else EcpMessages.t(availability.messageKey),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = t("位置、外观与显示资格由系统决定；指标更新最多每 5 秒一次。",
                "The system controls placement, appearance and eligibility; readings update at most every 5 seconds."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (availability.state == IslandAvailabilityState.NOT_AUTHORIZED) {
            OutlinedButton(
                onClick = onRequestAuthorization,
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Text(keyed("island_provider_request_authorization"))
            }
        }
    }
}

private fun displayModeLabel(mode: DisplayMode): String = when (mode) {
    DisplayMode.OVERLAY -> s("悬浮窗", "Overlay")
    DisplayMode.ISLAND -> s("原生灵动岛", "Native live update")
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
