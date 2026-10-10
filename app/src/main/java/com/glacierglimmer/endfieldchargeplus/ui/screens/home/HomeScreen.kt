package com.glacierglimmer.endfieldchargeplus.ui.screens.home

import android.app.Activity
import com.glacierglimmer.endfieldchargeplus.BuildConfig
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glacierglimmer.endfieldchargeplus.core.i18n.UiLanguage
import com.glacierglimmer.endfieldchargeplus.core.model.AppLanguage
import com.glacierglimmer.endfieldchargeplus.core.model.DisplayMode
import com.glacierglimmer.endfieldchargeplus.core.product.ProductInfo
import com.glacierglimmer.endfieldchargeplus.di.EcpContainer
import com.glacierglimmer.endfieldchargeplus.permission.EcpPermission
import com.glacierglimmer.endfieldchargeplus.localization.EcpMessages
import com.glacierglimmer.endfieldchargeplus.localization.LanguageController
import com.glacierglimmer.endfieldchargeplus.localization.LocalUiLanguage
import com.glacierglimmer.endfieldchargeplus.localization.s
import com.glacierglimmer.endfieldchargeplus.localization.t
import com.glacierglimmer.endfieldchargeplus.ui.components.ActionRow
import com.glacierglimmer.endfieldchargeplus.ui.components.ChipTone
import com.glacierglimmer.endfieldchargeplus.ui.components.InfoRow
import com.glacierglimmer.endfieldchargeplus.ui.components.PermissionRow
import com.glacierglimmer.endfieldchargeplus.ui.components.SectionCard
import com.glacierglimmer.endfieldchargeplus.ui.components.SegmentedRow
import com.glacierglimmer.endfieldchargeplus.ui.components.StatusChip
import com.glacierglimmer.endfieldchargeplus.ui.navigation.EcpDestination
import com.glacierglimmer.endfieldchargeplus.ui.screens.home.HomeViewModel.Companion.factory as homeViewModelFactory

/**
 * 首页 / Home.
 *
 * Product header, the honest HUD status, the permission status rows with their explanations and
 * the quick links to every page.
 */
@Composable
fun HomeScreen(
    container: EcpContainer,
    languageController: LanguageController,
    onNavigate: (EcpDestination) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(factory = homeViewModelFactory(container)),
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val runtime by viewModel.runtime.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val permissionsLoading by viewModel.permissionsLoading.collectAsStateWithLifecycle()
    val starting by viewModel.starting.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val pendingIntent by viewModel.pendingIntent.collectAsStateWithLifecycle()
    val language = LocalUiLanguage.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshPermissions() }

    pendingIntent?.let { intent ->
        androidx.compose.runtime.LaunchedEffect(intent) {
            runCatching { context.startActivity(intent) }
            viewModel.consumeIntent()
        }
    }

    val activeProfile = config.customHud.activeProfile()
    val displayMode = DisplayMode.fromWire(config.android.displayMode)
    val running = runtime.serviceRunning

    LazyColumn(modifier = modifier.fillMaxSize()) {
        if (message != null) {
            item {
                MessageCard(message!!, onDismiss = viewModel::consumeMessage)
            }
        }

        item {
            SectionCard(title = ProductInfo.NAME, subtitle = t("版本", "Version") + " " + BuildConfig.VERSION_NAME) {
                Column {
                    InfoRow(t("作者", "Author"), ProductInfo.AUTHOR)
                    InfoRow(t("项目网站", "Website"), ProductInfo.WEBSITE)
                    InfoRow(t("开源协议", "License"), ProductInfo.LICENSE_NAME)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        text = t("语言", "Language"),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    SegmentedRow(
                        options = LanguageController.preferences,
                        selected = AppLanguage.fromWire(config.uiLanguage),
                        label = { option -> languageLabel(option, language) },
                        onSelect = { option -> languageController.choose(option, scope) },
                    )
                }
            }
        }

        item {
            SectionCard(title = t("HUD 状态", "HUD status")) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusChip(
                            text = if (running) t("运行中", "Running") else t("已停止", "Stopped"),
                            tone = if (running) ChipTone.OK else ChipTone.OFF,
                        )
                        Text(
                            text = if (displayMode == DisplayMode.ISLAND) {
                                if (runtime.islandProviderId != null) t("原生实时通知已启动", "Native live update started")
                                else t("原生实时通知未启动", "Native live update stopped")
                            } else if (runtime.overlayShowing) {
                                t("悬浮窗已显示", "Overlay visible")
                            } else {
                                t("悬浮窗未显示", "Overlay hidden")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                    InfoRow(
                        t("显示方式", "Display mode"),
                        when (displayMode) {
                            DisplayMode.OVERLAY -> t("悬浮窗", "Overlay")
                            DisplayMode.ISLAND -> t("原生灵动岛", "Native live update")
                        },
                    )
                    InfoRow(
                        t("当前方案", "Active scheme"),
                        EcpMessages.profileDisplayName(activeProfile, language),
                    )
                    InfoRow(
                        t("采样运行中", "Sampler running"),
                        if (runtime.repositoryRunning) t("是", "Yes") else t("否", "No"),
                    )
                    runtime.lastError?.takeIf { it.isNotBlank() }?.let { error ->
                        InfoRow(
                            t("最近错误", "Last error"),
                            error,
                            valueColor = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = { (context as? Activity)?.let(viewModel::startHud) },
                            enabled = !running && !starting && !permissionsLoading,
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(t("启动 HUD", "Start HUD"))
                        }
                        OutlinedButton(
                            onClick = { viewModel.stopHud(context.applicationContext) },
                            enabled = running,
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(t("停止 HUD", "Stop HUD"))
                        }
                    }
                }
            }
        }

        item {
            SectionCard(
                title = t("权限状态", "Permissions"),
                subtitle = t(
                    "只有在你打开对应功能时才会请求权限。",
                    "Permissions are requested only when you turn on the feature that needs them.",
                ),
            ) {
                Column {
                    if (permissions.isEmpty()) {
                        Text(
                            if (permissionsLoading) t("正在检测权限与原生灵动岛支持…", "Checking permissions and native live updates…")
                            else t("无法完成权限检测，请重新打开此页。", "Permission detection failed; reopen this page."),
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    permissions.forEach { state ->
                        val canOpenSettings = state.permission == EcpPermission.OVERLAY ||
                            state.permission == EcpPermission.NOTIFICATIONS ||
                            (state.permission == EcpPermission.LIVE_UPDATE && state.canRequest)
                        PermissionRow(
                            title = EcpMessages.permissionTitle(state.permission),
                            explanation = EcpMessages.permissionExplanation(state.permission),
                            granted = state.granted,
                            statusText = if (state.permission == EcpPermission.LIVE_UPDATE) {
                                if (state.granted) t("支持", "Supported") else t("不可用", "Unavailable")
                            } else if (state.granted) {
                                t("已授予", "Granted")
                            } else {
                                t("未授予", "Not granted")
                            },
                            detail = EcpMessages.t(state.messageKey),
                            actionLabel = if (!canOpenSettings) null else if (state.granted) {
                                t("打开系统设置", "Open system settings")
                            } else {
                                t("去授权", "Grant access")
                            },
                            onAction = if (!canOpenSettings) null else {
                                {
                                    (context as? Activity)?.let { activity ->
                                        runCatching { viewModel.openPermissionSettings(activity, state.permission) }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }

        item {
            SectionCard(title = t("快速入口", "Quick links")) {
                Column {
                    EcpDestination.entries.filter { it != EcpDestination.HOME }.forEach { destination ->
                        ActionRow(
                            title = t(destination.titleZh, destination.titleEn),
                            onClick = { onNavigate(destination) },
                            trailing = {
                                Icon(Icons.Filled.ChevronRight, contentDescription = null)
                            },
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun MessageCard(message: HomeMessage, onDismiss: () -> Unit) {
    val text = when (message) {
        HomeMessage.HUD_STARTED -> t("HUD 已启动。", "The HUD has started.")
        HomeMessage.HUD_STOPPED -> t("HUD 已停止。", "The HUD has stopped.")
        HomeMessage.HUD_START_FAILED -> t(
            "无法启动 HUD 服务，请查看高级页面的日志。",
            "Could not start the HUD service; see the log on the Advanced page.",
        )
        HomeMessage.NOTIFICATION_PERMISSION_REQUESTED -> t(
            "已请求通知权限：图表需要它来显示常驻通知。",
            "Notification permission requested; the HUD needs it for its ongoing notification.",
        )
        HomeMessage.OVERLAY_PERMISSION_REQUIRED -> t(
            "缺少悬浮窗权限：已打开系统设置，请允许“显示在其他应用上层”。",
            "Overlay permission is missing: the system settings were opened, please allow \"Display over other apps\".",
        )
    }
    SectionCard(
        title = t("提示", "Notice"),
        subtitle = text,
        trailing = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(t("知道了", "Dismiss")) }
        },
    ) {
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * Label of one language preference. Deliberately **not** composable: it is used inside the
 * `label = { … }` lambda of [SegmentedRow], which Compose cannot treat as a composable scope.
 * The screen reads [LocalUiLanguage] in its own body, so a language switch still recomposes it.
 */
internal fun languageLabel(option: AppLanguage, language: UiLanguage): String = when (option) {
    AppLanguage.AUTO -> s("跟随系统", "Automatic")
    AppLanguage.SIMPLIFIED_CHINESE -> s("简体中文", "Chinese (Simplified)")
    AppLanguage.ENGLISH -> s("英语", "English")
}
