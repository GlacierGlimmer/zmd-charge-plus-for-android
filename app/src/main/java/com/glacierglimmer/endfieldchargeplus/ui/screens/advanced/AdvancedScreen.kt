package com.glacierglimmer.endfieldchargeplus.ui.screens.advanced

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glacierglimmer.endfieldchargeplus.data.ConfigFileTransfer
import com.glacierglimmer.endfieldchargeplus.di.EcpContainer
import com.glacierglimmer.endfieldchargeplus.localization.keyed
import com.glacierglimmer.endfieldchargeplus.localization.t
import com.glacierglimmer.endfieldchargeplus.ui.components.ChipTone
import com.glacierglimmer.endfieldchargeplus.ui.components.EmptyState
import com.glacierglimmer.endfieldchargeplus.ui.components.InfoRow
import com.glacierglimmer.endfieldchargeplus.ui.components.NumberFieldRow
import com.glacierglimmer.endfieldchargeplus.ui.components.SectionCard
import com.glacierglimmer.endfieldchargeplus.ui.components.StatusChip
import com.glacierglimmer.endfieldchargeplus.ui.components.SwitchRow
import com.glacierglimmer.endfieldchargeplus.ui.screens.advanced.AdvancedViewModel.Companion.factory as advancedViewModelFactory
import com.glacierglimmer.endfieldchargeplus.ui.state.CapabilityPresentation
import com.glacierglimmer.endfieldchargeplus.ui.state.UiFormatting
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 高级 / Advanced.
 *
 * Sampling cadence, configuration import/export through the Storage Access Framework, the bounded
 * in-app log viewer, the shareable diagnostics report, hardware capability re-detection with honest
 * unsupported reasons, verbose logging and the factory reset.
 */
@Composable
fun AdvancedScreen(
    container: EcpContainer,
    modifier: Modifier = Modifier,
    viewModel: AdvancedViewModel = viewModel(factory = advancedViewModelFactory(container)),
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
    val logLines by viewModel.logLines.collectAsStateWithLifecycle()
    val reDetecting by viewModel.reDetecting.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmReset by remember { mutableStateOf(false) }
    // The canonical transfer owns the JSON codec, the size cap and the clear-key protection.
    val fileTransfer = remember(container) { ConfigFileTransfer(context, container.configRepository) }

    val exportConfigLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(MIME_JSON),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = fileTransfer.exportTo(uri)
                viewModel.reportExport(result.isSuccess)
            }
        }
    }
    val importConfigLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = fileTransfer.importFrom(uri)
                result.onSuccess { imported -> container.configRepository.replace(imported) }
                viewModel.reportImport(result.isSuccess)
            }
        }
    }
    val exportLogLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(MIME_TEXT),
    ) { uri ->
        if (uri != null) {
            viewModel.reportExport(writeTextFile(context, uri, viewModel.logText()))
        }
    }
    val exportReportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(MIME_TEXT),
    ) { uri ->
        if (uri != null) {
            viewModel.reportExport(
                writeTextFile(context, uri, viewModel.diagnosticsText(context)),
            )
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(t("恢复全部默认设置？", "Reset everything to defaults?")) },
            text = {
                Text(
                    t(
                        "方案、数据源、刷新间隔与显示设置都会回到首次安装的默认值。此操作无法撤销。",
                        "Schemes, data sources, refresh intervals and display settings return to first-run defaults. This cannot be undone.",
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmReset = false
                        viewModel.resetToDefaults()
                    },
                ) { Text(t("恢复默认", "Reset")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(t("取消", "Cancel")) }
            },
        )
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            SectionCard(
                title = t("采样与刷新", "Sampling and refresh"),
                subtitle = t(
                    "毫秒为单位；HUD 隐藏或息屏时会自动降频。",
                    "Values are milliseconds; sampling slows down while the HUD is hidden or the screen is off.",
                ),
            ) {
                Column {
                    IntField(t("快速采样（延迟类）", "Fast (latency)"), config.android.fastRefreshMs.toInt(), "fast", 100..60_000, viewModel::setFastRefreshMs)
                    IntField(t("常规采样", "Normal"), config.android.normalRefreshMs.toInt(), "normal", 200..300_000, viewModel::setNormalRefreshMs)
                    IntField(t("慢速采样", "Slow"), config.android.slowRefreshMs.toInt(), "slow", 500..600_000, viewModel::setSlowRefreshMs)
                    IntField(t("空闲采样", "Idle"), config.android.idleRefreshMs.toInt(), "idle", 1_000..3_600_000, viewModel::setIdleRefreshMs)
                    IntField(t("息屏采样", "Screen off"), config.android.screenOffRefreshMs.toInt(), "screenOff", 1_000..3_600_000, viewModel::setScreenOffRefreshMs)
                    SwitchRow(
                        title = t("HUD 隐藏时降频", "Throttle while hidden"),
                        checked = config.android.throttleWhenHidden,
                        onCheckedChange = viewModel::setThrottleWhenHidden,
                    )
                    SwitchRow(
                        title = t("息屏时降频", "Throttle while the screen is off"),
                        checked = config.android.throttleWhenScreenOff,
                        onCheckedChange = viewModel::setThrottleWhenScreenOff,
                    )
                }
            }
        }

        item {
            SectionCard(
                title = t("硬件能力", "Hardware capabilities"),
                subtitle = viewModel.supportSummary(),
                trailing = {
                    TextButton(onClick = viewModel::refreshCapabilities, enabled = !reDetecting) {
                        Text(if (reDetecting) t("检测中…", "Detecting…") else t("重新检测", "Re-detect"))
                    }
                },
            ) {
                Column {
                    InfoRow(t("CPU", "CPU"), capabilities.cpuModel.ifBlank { "—" })
                    InfoRow(t("核心数", "Cores"), capabilities.cpuCoreCount.toString())
                    InfoRow(t("设备", "Device"), capabilities.deviceModel.ifBlank { "—" })
                    InfoRow(t("Android", "Android"), capabilities.androidRelease.ifBlank { "—" })
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    CapabilityPresentation.rows(capabilities).forEach { (label, capability) ->
                        CapabilityRow(
                            label = label,
                            supported = capability.supported,
                            reason = capability.reasonKey,
                            detail = capability.detail,
                        )
                    }
                }
            }
        }

        item {
            SectionCard(
                title = t("配置导入导出", "Configuration import and export"),
                subtitle = t(
                    "导出的 JSON 与桌面版互通；导入会先校验再替换当前配置。",
                    "The exported JSON is interchangeable with the desktop editions; an import is validated before it replaces the current configuration.",
                ),
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = { exportConfigLauncher.launch(fileTransfer.suggestedFileName()) },
                        ) {
                            Text(t("导出配置", "Export config"))
                        }
                        OutlinedButton(
                            onClick = { importConfigLauncher.launch(arrayOf(MIME_JSON)) },
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text(t("导入配置", "Import config"))
                        }
                        OutlinedButton(
                            onClick = { confirmReset = true },
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text(t("恢复全部默认", "Reset all"))
                        }
                    }
                    Text(
                        text = t(
                            "导入后立即生效；建议先导出当前配置作为备份。",
                            "An import takes effect immediately; export the current configuration first if you want a backup.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
        }

        item {
            SectionCard(
                title = t("诊断报告", "Diagnostics report"),
                subtitle = t(
                    "包含设备、能力、权限与配置摘要，可分享给开发者。",
                    "Includes device, capability, permission and configuration facts that can be shared with the developer.",
                ),
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = {
                                exportReportLauncher.launch(
                                    suggestedTextFileName("ecp-diagnostics"),
                                )
                            },
                        ) {
                            Text(t("保存报告", "Save report"))
                        }
                        OutlinedButton(
                            onClick = {
                                shareText(context, viewModel.diagnosticsText(context))
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text(t("分享", "Share"))
                        }
                    }
                    Text(
                        text = t(
                            "报告不会包含 API Key 或请求头内容。",
                            "The report never contains the API key or request header values.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
        }

        item {
            SectionCard(
                title = t("日志", "Log"),
                subtitle = t(
                    "最多保留 600 条记录与 3 个日志文件，长时间运行也不会占满存储。",
                    "At most 600 entries and 3 log files are kept, so a long-running HUD can never fill the storage.",
                ),
                trailing = {
                    TextButton(onClick = viewModel::refreshLog) { Text(t("刷新", "Refresh")) }
                },
            ) {
                Column {
                    SwitchRow(
                        title = t("详细日志", "Verbose logging"),
                        subtitle = t(
                            "开启后会记录调试级别的信息。",
                            "Records debug-level details when enabled.",
                        ),
                        checked = config.android.verboseLogging,
                        onCheckedChange = viewModel::setVerboseLogging,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = { exportLogLauncher.launch(suggestedTextFileName("ecp-log")) }) {
                            Text(t("保存日志", "Save log"))
                        }
                        OutlinedButton(
                            onClick = { shareText(context, viewModel.logText()) },
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text(t("分享日志", "Share log"))
                        }
                        OutlinedButton(
                            onClick = viewModel::clearLog,
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text(t("清空", "Clear"))
                        }
                    }
                    if (logLines.isEmpty()) {
                        EmptyState(t("暂无日志。", "No log entries yet."))
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            logLines.asReversed().forEach { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (message != null) {
            item {
                Text(
                    text = advancedMessageText(message!!),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun CapabilityRow(label: String, supported: Boolean, reason: String, detail: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            StatusChip(
                text = if (supported) t("支持", "Supported") else t("不支持", "Unsupported"),
                tone = if (supported) ChipTone.OK else ChipTone.OFF,
            )
        }
        if (!supported) {
            if (reason.isNotBlank()) {
                Text(
                    text = keyed(reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun IntField(
    label: String,
    value: Int,
    stateKey: String,
    range: IntRange,
    onValueChange: (Int) -> Unit,
) {
    var text by remember(stateKey) { mutableStateOf(value.toString()) }
    NumberFieldRow(
        label = label,
        value = text,
        onValueChange = { updated ->
            text = updated
            UiFormatting.parseInt(updated)?.let(onValueChange)
        },
        isError = !(UiFormatting.parseInt(text) in range),
        supporting = t(
            "范围 ${range.first} – ${range.last}",
            "Range ${range.first} – ${range.last}",
        ),
    )
}

private fun shareText(context: android.content.Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching {
        context.startActivity(Intent.createChooser(send, null))
    }
}

/** MIME type of the configuration export (desktop-compatible JSON). */
private const val MIME_JSON = "application/json"

/** MIME type of the log/report text exports. */
private const val MIME_TEXT = "text/plain"

/** Timestamped file name for the text exports; the user can still rename it in the picker. */
private fun suggestedTextFileName(prefix: String): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    return "$prefix-$stamp.txt"
}

/** Writes arbitrary text to a SAF [android.net.Uri]; returns true on success. */
private fun writeTextFile(context: android.content.Context, uri: android.net.Uri, text: String): Boolean =
    runCatching {
        val stream = context.contentResolver.openOutputStream(uri, "wt")
            ?: error("Unable to open the selected file for writing.")
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }.isSuccess

@Composable
private fun advancedMessageText(message: AdvancedMessage): String = when (message) {
    AdvancedMessage.LOG_CLEARED -> t("日志已清空。", "The log was cleared.")
    AdvancedMessage.CAPABILITIES_REFRESHED -> t("硬件能力已重新检测。", "Hardware capabilities were re-detected.")
    AdvancedMessage.RESET_APPLIED -> t("已恢复默认设置。", "Defaults were restored.")
    AdvancedMessage.EXPORT_OK -> t("已导出。", "Exported.")
    AdvancedMessage.EXPORT_FAILED -> t("导出失败。", "Export failed.")
    AdvancedMessage.IMPORT_OK -> t("配置已导入并应用。", "The configuration was imported and applied.")
    AdvancedMessage.IMPORT_FAILED -> t("导入失败：文件无效或无法读取。", "Import failed: the file is invalid or unreadable.")
}
