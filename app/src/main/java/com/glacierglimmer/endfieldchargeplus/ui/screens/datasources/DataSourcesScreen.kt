package com.glacierglimmer.endfieldchargeplus.ui.screens.datasources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glacierglimmer.endfieldchargeplus.core.metrics.Variables
import com.glacierglimmer.endfieldchargeplus.core.model.CustomHttpSource
import com.glacierglimmer.endfieldchargeplus.core.model.ProbeProtocol
import com.glacierglimmer.endfieldchargeplus.di.EcpContainer
import com.glacierglimmer.endfieldchargeplus.localization.EcpMessages
import com.glacierglimmer.endfieldchargeplus.localization.LocalUiLanguage
import com.glacierglimmer.endfieldchargeplus.localization.t
import com.glacierglimmer.endfieldchargeplus.ui.components.DropdownRow
import com.glacierglimmer.endfieldchargeplus.ui.components.EmptyState
import com.glacierglimmer.endfieldchargeplus.ui.components.InfoRow
import com.glacierglimmer.endfieldchargeplus.ui.components.NumberFieldRow
import com.glacierglimmer.endfieldchargeplus.ui.components.SectionCard
import com.glacierglimmer.endfieldchargeplus.ui.components.StatusChip
import com.glacierglimmer.endfieldchargeplus.ui.components.SwitchRow
import com.glacierglimmer.endfieldchargeplus.ui.components.ChipTone
import com.glacierglimmer.endfieldchargeplus.ui.screens.datasources.DataSourcesViewModel.Companion.factory as dataSourcesViewModelFactory
import com.glacierglimmer.endfieldchargeplus.ui.state.HttpSourceText
import com.glacierglimmer.endfieldchargeplus.ui.state.UiFormatting

/**
 * 数据源 / Data sources.
 *
 * System metric status, the network rates, the packet probe, the DeepSeek credential/endpoint and
 * the custom HTTP/JSON sources. Everything the page reports comes from the live snapshot or from a
 * real request; nothing is a placeholder.
 */
@Composable
fun DataSourcesScreen(
    container: EcpContainer,
    modifier: Modifier = Modifier,
    viewModel: DataSourcesViewModel = viewModel(factory = dataSourcesViewModelFactory(container)),
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val language = LocalUiLanguage.current

    val monitored = listOf(
        Variables.CPU_USAGE to t("CPU 使用率", "CPU usage"),
        Variables.MEMORY_USAGE to t("内存使用率", "Memory usage"),
        Variables.BATTERY_PERCENT to t("电池电量", "Battery level"),
        Variables.GPU_USAGE to t("GPU 使用率", "GPU usage"),
        Variables.NETWORK_DOWNLOAD_BPS to t("下载速率", "Download rate"),
        Variables.DISK_SYSTEM_USAGE to t("系统存储使用率", "System storage usage"),
        Variables.PROBE_LATENCY_MS to t("探测延迟", "Probe latency"),
        Variables.DEEPSEEK_BALANCE to t("DeepSeek 余额", "DeepSeek balance"),
        Variables.TIME_CURRENT to t("当前时间", "Current time"),
    )
    val availableCount = snapshot.values.count { it.value.isAvailable }

    Column(modifier = modifier.fillMaxSize().padding(bottom = 12.dp)) {
        DataSourcesContent(
            viewModel = viewModel,
            config = config,
            language = language,
            monitoredRows = monitored,
            availableCount = availableCount,
            totalCount = snapshot.values.size,
            snapshot = snapshot,
            message = message,
        )
    }
}

@Composable
private fun DataSourcesContent(
    viewModel: DataSourcesViewModel,
    config: com.glacierglimmer.endfieldchargeplus.core.model.AppConfig,
    language: com.glacierglimmer.endfieldchargeplus.core.i18n.UiLanguage,
    monitoredRows: List<Pair<String, String>>,
    availableCount: Int,
    totalCount: Int,
    snapshot: com.glacierglimmer.endfieldchargeplus.core.metrics.MetricSnapshot,
    message: DataSourceMessage?,
) {
    androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            SectionCard(
                title = t("系统指标状态", "System metric status"),
                subtitle = t(
                    "只显示平台真正能读到的数据。",
                    "Only metrics the platform really produces are shown as available.",
                ),
                trailing = {
                    TextButton(onClick = viewModel::refreshNow) { Text(t("立即刷新", "Refresh now")) }
                },
            ) {
                Column {
                    InfoRow(
                        t("已获取变量", "Variables with a reading"),
                        "$availableCount / $totalCount",
                    )
                    monitoredRows.forEach { (variable, label) ->
                        val available = viewModel.isAvailable(variable)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            StatusChip(
                                text = if (available) t("可用", "Available") else t("不可用", "Unavailable"),
                                tone = if (available) ChipTone.OK else ChipTone.OFF,
                            )
                        }
                    }
                    Text(
                        text = t(
                            "不可用通常表示系统没有公开接口、设备没有该传感器或尚未采样。",
                            "Unavailable usually means the platform has no public API, the device has no sensor, or no sample has arrived yet.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
        }

        item {
            val profile = viewModel.activeProfile()
            SectionCard(
                title = t("数据包探测", "Packet probe"),
                subtitle = t(
                    "ICMP 优先；受限网络会自动使用 TCP / UDP。目标与端口来自当前方案。",
                    "ICMP is preferred; restricted networks fall back to TCP / UDP. Target and port come from the active scheme.",
                ),
            ) {
                Column {
                    SwitchRow(
                        title = t("启用探测", "Enable probing"),
                        checked = config.android.probeEnabled,
                        onCheckedChange = viewModel::setProbeEnabled,
                    )
                    IntSettingField(
                        label = t("探测间隔 / 秒", "Interval / s"),
                        value = config.android.probeIntervalSeconds,
                        stateKey = "probeInterval",
                        onValueChange = viewModel::setProbeIntervalSeconds,
                        range = 1..3600,
                    )
                    IntSettingField(
                        label = t("单次超时 / 毫秒", "Timeout / ms"),
                        value = config.android.probeTimeoutMs,
                        stateKey = "probeTimeout",
                        onValueChange = viewModel::setProbeTimeoutMs,
                        range = 100..30_000,
                    )
                    IntSettingField(
                        label = t("统计窗口 / 样本", "Sample window"),
                        value = config.android.probeSampleWindow,
                        stateKey = "probeWindow",
                        onValueChange = viewModel::setProbeSampleWindow,
                        range = 3..120,
                    )
                    if (profile != null) {
                        ProbeTargetField(
                            value = profile.pingTarget,
                            stateKey = profile.id + ":probeTarget",
                            onValueChange = { value -> viewModel.updateActiveProfileProbe(target = value) },
                        )
                        DropdownRow(
                            title = t("协议", "Protocol"),
                            options = ProbeProtocol.entries.map { it.wire },
                            selectedIndex = ProbeProtocol.entries
                                .indexOf(ProbeProtocol.fromWire(profile.probeProtocol))
                                .coerceAtLeast(0),
                            onSelected = { index ->
                                viewModel.updateActiveProfileProbe(
                                    protocol = ProbeProtocol.entries[index].wire,
                                )
                            },
                        )
                        IntSettingField(
                            label = t("端口", "Port"),
                            value = profile.probePort,
                            stateKey = profile.id + ":probePort",
                            onValueChange = { port -> viewModel.updateActiveProfileProbe(port = port) },
                            range = 1..65535,
                        )
                    }
                    InfoRow(
                        t("当前延迟", "Current latency"),
                        snapshotText(snapshot, Variables.PROBE_LATENCY_MS),
                    )
                    InfoRow(
                        t("当前丢包率", "Current loss"),
                        snapshotText(snapshot, Variables.PROBE_LOSS_PERCENT),
                    )
                }
            }
        }

        item {
            SectionCard(
                title = t("网络速率", "Network rates"),
                subtitle = t(
                    "所有活动接口的合计速率。",
                    "Combined rate of every active interface.",
                ),
            ) {
                Column {
                    InfoRow(
                        t("下载", "Download"),
                        UiFormatting.bytesPerSecondText(snapshot.numberOrNull(Variables.NETWORK_DOWNLOAD_BPS)),
                    )
                    InfoRow(
                        t("上传", "Upload"),
                        UiFormatting.bytesPerSecondText(snapshot.numberOrNull(Variables.NETWORK_UPLOAD_BPS)),
                    )
                    InfoRow(t("网络类型", "Network type"), snapshotText(snapshot, Variables.NETWORK_TYPE))
                    InfoRow(t("已连接", "Connected"), snapshotText(snapshot, Variables.NETWORK_CONNECTED))
                    InfoRow(t("接口", "Interface"), snapshotText(snapshot, Variables.NETWORK_INTERFACE))
                }
            }
        }

        item {
            DeepSeekCard(viewModel = viewModel, config = config)
        }

        item {
            HttpSourcesCard(viewModel = viewModel, sources = config.customHud.httpSources)
        }

        if (message != null) {
            item {
                Text(
                    text = dataSourceMessageText(message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
    // `language` participates in recomposition of every localized label above.
    @Suppress("UNUSED_EXPRESSION")
    language
}

@Composable
private fun DeepSeekCard(viewModel: DataSourcesViewModel, config: com.glacierglimmer.endfieldchargeplus.core.model.AppConfig) {
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val available by viewModel.secretStoreAvailable.collectAsStateWithLifecycle()
    val testing by viewModel.testing.collectAsStateWithLifecycle()
    val outcome by viewModel.testOutcome.collectAsStateWithLifecycle()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    var keyDraft by remember(apiKey) { mutableStateOf(apiKey) }

    SectionCard(
        title = "DeepSeek API",
        subtitle = t(
            "余额查询与峰谷时段；API Key 使用 Android Keystore 加密保存。",
            "Balance queries and peak/off-peak windows; the API key is encrypted with the Android Keystore.",
        ),
    ) {
        Column {
            if (!available) {
                Text(
                    text = t(
                        "加密存储不可用，无法安全保存 API Key。",
                        "Encrypted storage is unavailable, so the API key cannot be saved safely.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            OutlinedTextField(
                value = keyDraft,
                onValueChange = { keyDraft = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                label = { Text(t("API Key", "API key")) },
                placeholder = { Text("sk-…") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                enabled = available,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = { viewModel.saveApiKey(keyDraft) },
                    enabled = available,
                ) {
                    Text(t("保存", "Save"))
                }
                OutlinedButton(
                    onClick = {
                        keyDraft = ""
                        viewModel.saveApiKey("")
                    },
                    enabled = available && apiKey.isNotEmpty(),
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(t("删除", "Remove"))
                }
                OutlinedButton(
                    onClick = viewModel::testDeepSeek,
                    enabled = available && apiKey.isNotEmpty() && !testing,
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(if (testing) t("测试中…", "Testing…") else t("测试连接", "Test connection"))
                }
            }
            outcome?.let { result ->
                Text(
                    text = testOutcomeText(result),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result.reachable) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            StringSettingField(
                label = t("接口地址", "Base URL"),
                value = config.android.deepSeekBaseUrl,
                stateKey = "deepSeekBaseUrl",
                onValueChange = viewModel::setDeepSeekBaseUrl,
                isError = !HttpSourceText.isHttpUrl(config.android.deepSeekBaseUrl),
            )
            IntSettingField(
                label = t("刷新间隔 / 秒", "Refresh / s"),
                value = config.android.deepSeekRefreshSeconds,
                stateKey = "deepSeekRefresh",
                onValueChange = viewModel::setDeepSeekRefreshSeconds,
                range = 30..86_400,
            )
            StringSettingField(
                label = t("高峰窗口（北京时间，分号分隔）", "Peak windows (Beijing time, semicolon separated)"),
                value = config.customHud.deepSeekPeakWindows,
                stateKey = "deepSeekPeakWindows",
                onValueChange = viewModel::setDeepSeekPeakWindows,
                isError = config.customHud.deepSeekPeakWindows.isBlank(),
                supporting = t(
                    "例如 09:00-12:00;14:00-18:00；仅工作日生效。",
                    "For example 09:00-12:00;14:00-18:00; weekdays only.",
                ),
            )
            InfoRow(
                t("当前时段", "Current period"),
                snapshotText(snapshot, Variables.DEEPSEEK_PERIOD_NAME),
            )
            InfoRow(
                t("余额", "Balance"),
                snapshotText(snapshot, Variables.DEEPSEEK_BALANCE),
            )
        }
    }
}

@Composable
private fun HttpSourcesCard(viewModel: DataSourcesViewModel, sources: List<CustomHttpSource>) {
    SectionCard(
        title = t("HTTP / JSON 数据源", "HTTP / JSON sources"),
        subtitle = t(
            "GET 一个返回 JSON 的接口，把字段映射为 custom.数据源.字段 变量。",
            "GET a JSON endpoint and map its fields to custom.source.field variables.",
        ),
        trailing = {
            TextButton(onClick = viewModel::addHttpSource) { Text(t("添加", "Add")) }
        },
    ) {
        Column {
            if (sources.isEmpty()) {
                EmptyState(t("尚未配置数据源。", "No data source configured yet."))
            } else {
                sources.forEachIndexed { index, source ->
                    HttpSourceEditor(
                        index = index,
                        source = source,
                        onChange = { transform -> viewModel.updateHttpSource(index, transform) },
                        onRemove = { viewModel.removeHttpSource(index) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun HttpSourceEditor(
    index: Int,
    source: CustomHttpSource,
    onChange: ((CustomHttpSource) -> CustomHttpSource) -> Unit,
    onRemove: () -> Unit,
) {
    var nameDraft by remember(source.name) { mutableStateOf(source.name) }
    var urlDraft by remember(source.url) { mutableStateOf(source.url) }
    var headersDraft by remember(source.headers) {
        mutableStateOf(HttpSourceText.formatHeaders(source.headers))
    }
    var fieldsDraft by remember(source.fields) {
        mutableStateOf(HttpSourceText.formatFields(source.fields))
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = t("数据源", "Source") + " ${index + 1}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRemove) { Text(t("删除", "Delete")) }
        }
        SwitchRow(
            title = t("启用", "Enabled"),
            checked = source.enabled,
            onCheckedChange = { enabled -> onChange { it.copy(enabled = enabled) } },
        )
        OutlinedTextField(
            value = nameDraft,
            onValueChange = { value ->
                nameDraft = value
                onChange { it.copy(name = value) }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            label = { Text(t("名称（变量前缀）", "Name (variable prefix)")) },
            supportingText = {
                Text(
                    t(
                        "变量前缀：custom." + HttpSourceText.sanitizeSegment(nameDraft) + ".*",
                        "Variable prefix: custom." + HttpSourceText.sanitizeSegment(nameDraft) + ".*",
                    ),
                )
            },
            singleLine = true,
        )
        OutlinedTextField(
            value = urlDraft,
            onValueChange = { value ->
                urlDraft = value
                onChange { it.copy(url = value) }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            label = { Text(t("GET 地址", "GET URL")) },
            isError = urlDraft.isNotBlank() && !HttpSourceText.isHttpUrl(urlDraft),
            supportingText = if (urlDraft.isNotBlank() && !HttpSourceText.isHttpUrl(urlDraft)) {
                { Text(t("请输入 http/https 地址。", "Enter an http/https URL.")) }
            } else {
                null
            },
            singleLine = true,
        )
        OutlinedTextField(
            value = headersDraft,
            onValueChange = { value ->
                headersDraft = value
                onChange { it.copy(headers = HttpSourceText.parseHeaders(value)) }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            label = { Text(t("请求头（每行 Key: Value）", "Headers (one Key: Value per line)")) },
            minLines = 2,
            supportingText = {
                Text(
                    t(
                        "敏感值可用 \${env:环境变量名} 占位。",
                        "Secrets may use \${env:VARIABLE_NAME} placeholders.",
                    ),
                )
            },
        )
        OutlinedTextField(
            value = fieldsDraft,
            onValueChange = { value ->
                fieldsDraft = value
                onChange { it.copy(fields = HttpSourceText.parseFields(value)) }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            label = { Text(t("字段映射（每行 变量 = JSON 路径）", "Field mapping (one variable = JSON path per line)")) },
            minLines = 2,
            isError = HttpSourceText.invalidFieldLines(fieldsDraft).isNotEmpty(),
            supportingText = {
                val invalid = HttpSourceText.invalidFieldLines(fieldsDraft)
                if (invalid.isEmpty()) {
                    Text(t("例如 value = data.items[0].value", "e.g. value = data.items[0].value"))
                } else {
                    Text(
                        t(
                            "无法解析的行：" + invalid.joinToString(", "),
                            "Unparsable lines: " + invalid.joinToString(", "),
                        ),
                    )
                }
            },
        )
        IntSettingField(
            label = t("刷新间隔 / 秒", "Refresh / s"),
            value = source.refreshSeconds,
            stateKey = "httpRefresh$index",
            onValueChange = { seconds -> onChange { it.copy(refreshSeconds = seconds) } },
            range = 5..86_400,
        )
    }
}

@Composable
private fun ProbeTargetField(value: String, stateKey: String, onValueChange: (String) -> Unit) {
    var text by remember(stateKey) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { updated ->
            text = updated
            onValueChange(updated)
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        label = { Text(t("检测地址", "Probe target")) },
        isError = text.isBlank(),
        supportingText = if (text.isBlank()) {
            { Text(t("检测地址不能为空。", "The probe target cannot be empty.")) }
        } else {
            { Text(t("IPv4、IPv6 或域名。", "IPv4, IPv6 or a host name.")) }
        },
        singleLine = true,
    )
}

@Composable
private fun IntSettingField(
    label: String,
    value: Int,
    stateKey: String,
    onValueChange: (Int) -> Unit,
    range: IntRange,
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

@Composable
private fun StringSettingField(
    label: String,
    value: String,
    stateKey: String,
    onValueChange: (String) -> Unit,
    isError: Boolean = false,
    supporting: String? = null,
) {
    var text by remember(stateKey) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { updated ->
            text = updated
            onValueChange(updated)
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        label = { Text(label) },
        isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        singleLine = true,
    )
}

private fun snapshotText(
    snapshot: com.glacierglimmer.endfieldchargeplus.core.metrics.MetricSnapshot,
    variable: String,
): String {
    val value = snapshot[variable] ?: return "—"
    return when (value) {
        is com.glacierglimmer.endfieldchargeplus.core.model.MetricValue.Number ->
            UiFormatting.numberText(value.value)

        is com.glacierglimmer.endfieldchargeplus.core.model.MetricValue.Text -> value.value
        is com.glacierglimmer.endfieldchargeplus.core.model.MetricValue.Unavailable -> {
            val reason = value.reason
            EcpMessages.unavailableReason(reason)
        }
    }
}

@Composable
private fun testOutcomeText(outcome: DeepSeekTestOutcome): String = when {
    outcome.reachable -> t("连接成功。", "Connection succeeded.")
    outcome.note == "missing_key" -> t("请先填写 API Key。", "Enter an API key first.")
    outcome.note == "invalid_base_url" -> t("接口地址无效。", "The base URL is invalid.")
    outcome.httpStatus == 401 || outcome.httpStatus == 403 ->
        t("鉴权失败，请检查 API Key。", "Authentication failed; check the API key.")

    outcome.httpStatus != null -> t(
        "接口返回 HTTP ${outcome.httpStatus}。",
        "The endpoint returned HTTP ${outcome.httpStatus}.",
    )

    else -> t("请求失败：${outcome.note}", "Request failed: ${outcome.note}")
}

/** Local alias so the screen reads naturally; the type lives in the state package. */
private typealias DeepSeekTestOutcome = com.glacierglimmer.endfieldchargeplus.ui.state.DeepSeekTest.Outcome

@Composable
private fun dataSourceMessageText(message: DataSourceMessage): String = when (message) {
    DataSourceMessage.API_KEY_SAVED -> t("API Key 已加密保存。", "The API key was saved encrypted.")
    DataSourceMessage.API_KEY_REMOVED -> t("API Key 已删除。", "The API key was removed.")
    DataSourceMessage.SECRET_STORE_UNAVAILABLE -> t(
        "加密存储不可用，无法保存 API Key。",
        "Encrypted storage is unavailable; the API key was not saved.",
    )
    DataSourceMessage.SOURCE_ADDED -> t("已添加数据源。", "Data source added.")
    DataSourceMessage.SOURCE_REMOVED -> t("已删除数据源。", "Data source removed.")
}
