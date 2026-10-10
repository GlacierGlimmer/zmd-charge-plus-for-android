package com.glacierglimmer.endfieldchargeplus.ui.screens.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glacierglimmer.endfieldchargeplus.core.i18n.UiLanguage
import com.glacierglimmer.endfieldchargeplus.core.model.AnimationMode
import com.glacierglimmer.endfieldchargeplus.core.model.DisplayMode
import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile
import com.glacierglimmer.endfieldchargeplus.core.model.HudRenderData
import com.glacierglimmer.endfieldchargeplus.island.IslandHudMapper
import com.glacierglimmer.endfieldchargeplus.core.model.NetworkDisplayUnit
import com.glacierglimmer.endfieldchargeplus.core.model.NetworkPercentMode
import com.glacierglimmer.endfieldchargeplus.core.model.ProbeProtocol
import com.glacierglimmer.endfieldchargeplus.di.EcpContainer
import com.glacierglimmer.endfieldchargeplus.localization.EcpMessages
import com.glacierglimmer.endfieldchargeplus.localization.LocalUiLanguage
import com.glacierglimmer.endfieldchargeplus.localization.s
import com.glacierglimmer.endfieldchargeplus.localization.t
import com.glacierglimmer.endfieldchargeplus.overlay.HudIconCatalog
import com.glacierglimmer.endfieldchargeplus.ui.components.ColorRuleEditor
import com.glacierglimmer.endfieldchargeplus.ui.components.ColorRuleStrings
import com.glacierglimmer.endfieldchargeplus.ui.components.DropdownRow
import com.glacierglimmer.endfieldchargeplus.ui.components.EmptyState
import com.glacierglimmer.endfieldchargeplus.ui.components.HudPreviewCard
import com.glacierglimmer.endfieldchargeplus.ui.components.NumberFieldRow
import com.glacierglimmer.endfieldchargeplus.ui.components.SectionCard
import com.glacierglimmer.endfieldchargeplus.ui.components.SegmentedRow
import com.glacierglimmer.endfieldchargeplus.ui.components.SwitchRow
import com.glacierglimmer.endfieldchargeplus.ui.components.TemplateField
import com.glacierglimmer.endfieldchargeplus.ui.components.VariablePickerSheet
import com.glacierglimmer.endfieldchargeplus.ui.screens.content.ContentViewModel.Companion.factory as contentViewModelFactory
import com.glacierglimmer.endfieldchargeplus.ui.state.ProfileEdits
import com.glacierglimmer.endfieldchargeplus.ui.state.TemplateText
import com.glacierglimmer.endfieldchargeplus.ui.state.UiFormatting
import com.glacierglimmer.endfieldchargeplus.ui.state.VariableRow

/** Which template field the variable picker inserts into. */
private enum class TemplateSlot { TAGLINE, TITLE, PRIMARY, SECONDARY, RIGHT, RIGHT_SUFFIX, PROGRESS }

/**
 * HUD 内容 / HUD content.
 *
 * Scheme selection and management, the left/right content areas with template fields and the
 * variable picker, icons, progress ring, accent colour rules, the profile-scoped network/time/probe
 * options and the automatic carousel — with a live preview that reuses the real HUD builder.
 */
@Composable
fun ContentScreen(
    container: EcpContainer,
    modifier: Modifier = Modifier,
    viewModel: ContentViewModel = viewModel(factory = contentViewModelFactory(container)),
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val language = LocalUiLanguage.current
    val wide = LocalConfiguration.current.screenWidthDp >= 840

    DisposableEffect(Unit) {
        viewModel.onScreenVisible()
        onDispose { viewModel.onScreenHidden() }
    }

    val rows = remember(snapshot, language) { viewModel.variableRows(snapshot, language) }
    var pickerSlot by remember { mutableStateOf<TemplateSlot?>(null) }

    val profile = draft
    if (profile == null) {
        EmptyState(t("尚未载入任何方案。", "No scheme is loaded yet."))
        return
    }

    val renderData = remember(profile, snapshot, language) {
        viewModel.render(profile, snapshot, language)
    }
    val previewTitle = t("实时预览", "Live preview")
    val previewSubtitle = EcpMessages.profileDisplayName(profile, language)

    pickerSlot?.let { slot ->
        VariablePickerSheet(
            rows = rows,
            title = t("插入变量", "Insert variable"),
            searchPlaceholder = t("搜索：CPU 使用率 / cpu.usage", "Search: CPU usage / cpu.usage"),
            allCategoriesLabel = t("全部分类", "All categories"),
            unavailableLabel = t("没有匹配的变量。", "No matching variable."),
            onDismiss = { pickerSlot = null },
            onPick = { row ->
                insertVariable(viewModel, slot, row)
                pickerSlot = null
            },
        )
    }

    if (wide) {
        Row(modifier = modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    ContentEditor(
                        viewModel = viewModel,
                        profile = profile,
                        config = config,
                        language = language,
                        message = message,
                        onPickVariable = { pickerSlot = it },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
            Column(
                modifier = Modifier
                    .width(360.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                OutputPreviewCard(renderData, config, previewTitle, previewSubtitle)
            }
        }
    } else {
        LazyColumn(modifier = modifier.fillMaxSize()) {
            item {
                Column(modifier = Modifier.padding(12.dp)) {
                    OutputPreviewCard(renderData, config, previewTitle, previewSubtitle)
                }
            }
            item {
                ContentEditor(
                    viewModel = viewModel,
                    profile = profile,
                    config = config,
                    language = language,
                    message = message,
                    onPickVariable = { pickerSlot = it },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun OutputPreviewCard(data: HudRenderData, config: AppConfig, title: String, subtitle: String) {
    if (DisplayMode.fromWire(config.android.displayMode) == DisplayMode.OVERLAY) {
        HudPreviewCard(data = data, title = title, subtitle = subtitle)
        return
    }
    val content = remember(data) { IslandHudMapper.mapLiveUpdate(data) }
    SectionCard(title = title, subtitle = subtitle) {
        Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(t("收起：", "Collapsed: ") + content.shortText.ifBlank { "—" })
            Text(content.title, style = MaterialTheme.typography.titleMedium)
            Text(content.bodyText)
            LinearProgressIndicator(progress = { (content.progressPercent / 100).toFloat() }, modifier = Modifier.fillMaxWidth())
            Text(t("这里预览通知内容，实际外观与位置由系统决定。",
                "This previews notification content; the system controls its appearance and placement."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun insertVariable(viewModel: ContentViewModel, slot: TemplateSlot, row: VariableRow) {
    val token = row.name
    viewModel.updateDraft { profile ->
        fun append(text: String): String = TemplateText.insertKey(text, token, text.length).first
        when (slot) {
            TemplateSlot.TAGLINE -> profile.copy(taglineTemplate = append(profile.taglineTemplate))
            TemplateSlot.TITLE -> profile.copy(titleTemplate = append(profile.titleTemplate))
            TemplateSlot.PRIMARY -> profile.copy(primaryTemplate = append(profile.primaryTemplate))
            TemplateSlot.SECONDARY -> profile.copy(secondaryTemplate = append(profile.secondaryTemplate))
            TemplateSlot.RIGHT -> profile.copy(rightTemplate = append(profile.rightTemplate))
            TemplateSlot.RIGHT_SUFFIX -> profile.copy(rightSuffix = append(profile.rightSuffix))
            TemplateSlot.PROGRESS -> profile.copy(progressVariable = append(profile.progressVariable))
        }
    }
}

@Composable
private fun ContentEditor(
    viewModel: ContentViewModel,
    profile: HudProfile,
    config: AppConfig,
    language: UiLanguage,
    message: ContentMessage?,
    onPickVariable: (TemplateSlot) -> Unit,
) {
    val stored = config.customHud.profileById(profile.id)
    val builtIn = ProfileEdits.isBuiltIn(stored)
    val usesNetwork = ProfileNeeds.network(profile)
    val usesTime = ProfileNeeds.time(profile)
    val usesProbe = ProfileNeeds.probe(profile)
    val overlayAppearance = DisplayMode.fromWire(config.android.displayMode) == DisplayMode.OVERLAY

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SchemeCard(
            viewModel = viewModel,
            profile = profile,
            config = config,
            language = language,
            builtIn = builtIn,
            message = message,
        )

        SectionCard(
            title = t("左侧主体信息", "Left main information"),
            subtitle = t(
                "主要数值与补充文字，支持 {变量|格式} 与 {= 表达式}。",
                "The primary value and its note; supports {variable|format} and {= expression}.",
            ),
        ) {
            Column {
                TemplateField(
                    label = t("上行文字", "Tagline"),
                    value = profile.taglineTemplate,
                    onValueChange = { value -> viewModel.updateDraft { it.copy(taglineTemplate = value) } },
                    onInsertVariable = { onPickVariable(TemplateSlot.TAGLINE) },
                    placeholder = "/// SYSTEM MONITOR",
                    isError = TemplateText.validate(profile.taglineTemplate) != null,
                    supporting = templateSupport(profile.taglineTemplate),
                )
                TemplateField(
                    label = t("主标题", "Title"),
                    value = profile.titleTemplate,
                    onValueChange = { value -> viewModel.updateDraft { it.copy(titleTemplate = value) } },
                    onInsertVariable = { onPickVariable(TemplateSlot.TITLE) },
                    placeholder = t("系统状态", "System status"),
                    isError = TemplateText.validate(profile.titleTemplate) != null,
                    supporting = templateSupport(profile.titleTemplate),
                )
                TemplateField(
                    label = t("主值", "Primary"),
                    value = profile.primaryTemplate,
                    onValueChange = { value -> viewModel.updateDraft { it.copy(primaryTemplate = value) } },
                    onInsertVariable = { onPickVariable(TemplateSlot.PRIMARY) },
                    placeholder = "{memory.used_bytes|gb:1}",
                    minLines = 2,
                    isError = TemplateText.validate(profile.primaryTemplate) != null,
                    supporting = templateSupport(profile.primaryTemplate),
                )
                TemplateField(
                    label = t("次值 / 补充文字", "Secondary / note"),
                    value = profile.secondaryTemplate,
                    onValueChange = { value -> viewModel.updateDraft { it.copy(secondaryTemplate = value) } },
                    onInsertVariable = { onPickVariable(TemplateSlot.SECONDARY) },
                    placeholder = "/{memory.total_bytes|gb:1} GB",
                    minLines = 2,
                    isError = TemplateText.validate(profile.secondaryTemplate) != null,
                    supporting = templateSupport(profile.secondaryTemplate),
                )
            }
        }

        SectionCard(
            title = t("右侧状态值", "Right status"),
            subtitle = t(
                "使用率、剩余比例、已过进度等状态信息，可与进度环联动。",
                "Usage, remaining share or elapsed progress, optionally driving the ring.",
            ),
        ) {
            Column {
                TemplateField(
                    label = t("状态值", "Status"),
                    value = profile.rightTemplate,
                    onValueChange = { value -> viewModel.updateDraft { it.copy(rightTemplate = value) } },
                    onInsertVariable = { onPickVariable(TemplateSlot.RIGHT) },
                    placeholder = "{cpu.usage|0}",
                    minLines = 2,
                    isError = TemplateText.validate(profile.rightTemplate) != null,
                    supporting = templateSupport(profile.rightTemplate),
                )
                TemplateField(
                    label = t("后缀", "Suffix"),
                    value = profile.rightSuffix,
                    onValueChange = { value -> viewModel.updateDraft { it.copy(rightSuffix = value) } },
                    onInsertVariable = { onPickVariable(TemplateSlot.RIGHT_SUFFIX) },
                    placeholder = "%",
                )
            }
        }

        SectionCard(
            title = if (overlayAppearance) t("图标与进度环", "Icons and progress ring") else t("系统进度条", "System progress bar"),
            subtitle = t(
                if (overlayAppearance) "图标来自与 HUD 渲染器相同的图标目录。" else "使用原生通知的进度条显示下方数值范围。",
                if (overlayAppearance) "Icons come from the same catalog the HUD renderer uses." else "The native notification progress bar uses the value range below.",
            ),
        ) {
            Column {
                if (overlayAppearance) {
                    DropdownRow(
                        title = t("左侧图标", "Left icon"),
                        options = HudIconCatalog.names,
                        selectedIndex = HudIconCatalog.names.indexOf(profile.leftIcon).coerceAtLeast(0),
                        onSelected = { index ->
                            val icon = HudIconCatalog.names[index]
                            viewModel.updateDraft { it.copy(leftIcon = icon) }
                        },
                    )
                    DropdownRow(
                        title = t("右侧图标", "Right icon"),
                        options = HudIconCatalog.names,
                        selectedIndex = HudIconCatalog.names.indexOf(profile.rightIcon).coerceAtLeast(0),
                        onSelected = { index ->
                            val icon = HudIconCatalog.names[index]
                            viewModel.updateDraft { it.copy(rightIcon = icon) }
                        },
                    )

                }
                TemplateField(
                    label = t("进度变量", "Progress value"),
                    value = profile.progressVariable,
                    onValueChange = { value -> viewModel.updateDraft { it.copy(progressVariable = value) } },
                    onInsertVariable = { onPickVariable(TemplateSlot.PROGRESS) },
                    placeholder = t(
                        "cpu.usage 或 = (cpu.usage + gpu.usage) / 2",
                        "cpu.usage or = (cpu.usage + gpu.usage) / 2",
                    ),
                )
                NumericDraftField(
                    label = t("最小值", "Minimum"),
                    value = profile.progressMin,
                    onValueChange = { value -> viewModel.updateDraft { it.copy(progressMin = value) } },
                    stateKey = profile.id + ":progressMin",
                )
                NumericDraftField(
                    label = t("最大值", "Maximum"),
                    value = profile.progressMax,
                    onValueChange = { value -> viewModel.updateDraft { it.copy(progressMax = value) } },
                    stateKey = profile.id + ":progressMax",
                )
                if (profile.progressMax <= profile.progressMin) {
                    Text(
                        text = t(
                            "最大值必须大于最小值，否则进度环始终为 0。",
                            "The maximum must exceed the minimum, otherwise the ring is always 0.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }

        if (overlayAppearance) {
            SectionCard(
                title = t("颜色", "Colour"),
                subtitle = t(
                    "默认强调色与条件变色规则。",
                    "Default accent colour and conditional colour rules.",
                ),
            ) {
                Column {
                    ColourField(
                        label = t("默认强调色", "Accent colour"),
                        value = profile.accentColor,
                        onValueChange = { value -> viewModel.updateDraft { it.copy(accentColor = value) } },
                        stateKey = profile.id + ":accent",
                    )
                    ColorRuleEditor(
                        rules = profile.colorRules,
                        onRulesChange = { rules -> viewModel.updateDraft { it.copy(colorRules = rules) } },
                        editorKey = profile.id,
                        strings = ColorRuleStrings(
                            help = t(
                                "每行：变量 运算符 数值 => 颜色，例如 cpu.usage >= 90 => #FF4D4F",
                                "One per line: variable operator value => colour, e.g. cpu.usage >= 90 => #FF4D4F",
                            ),
                            variable = t("变量", "Variable"),
                            operator = t("运算符", "Operator"),
                            threshold = t("数值", "Value"),
                            color = t("颜色", "Colour"),
                            add = t("添加", "Add"),
                            empty = t("暂无规则。", "No rules yet."),
                            invalidLines = { lines ->
                                s(
                                    "无法解析的行：" + lines.joinToString(", "),
                                    "Unparsable lines: " + lines.joinToString(", "),
                                )
                            },
                        ),
                    )
                }
            }


        }
        if (usesNetwork) {
            SectionCard(
                title = t("网络选项", "Network options"),
                subtitle = t(
                    "网络显示单位与百分比基准。",
                    "Network display unit and percentage basis.",
                ),
            ) {
                Column {
                    SegmentedRow(
                        options = NetworkDisplayUnit.entries,
                        selected = NetworkDisplayUnit.fromWire(profile.networkDisplayUnit),
                        label = { unit ->
                            when (unit) {
                                NetworkDisplayUnit.MBPS -> "Mbps"
                                NetworkDisplayUnit.AUTO_BYTES -> s("KB/s · MB/s（自动）", "KB/s · MB/s (auto)")
                            }
                        },
                        onSelect = { unit ->
                            viewModel.updateDraft { it.copy(networkDisplayUnit = unit.wire) }
                        },
                    )
                    DropdownRow(
                        title = t("百分比计算方式", "Percent basis"),
                        options = listOf(
                            t("总吞吐量", "Total throughput"),
                            t("仅下载", "Download only"),
                            t("仅上传", "Upload only"),
                            t("较大值", "Larger direction"),
                        ),
                        selectedIndex = when (NetworkPercentMode.fromWire(profile.networkPercentMode)) {
                            NetworkPercentMode.TOTAL -> 0
                            NetworkPercentMode.DOWNLOAD -> 1
                            NetworkPercentMode.UPLOAD -> 2
                            NetworkPercentMode.MAX -> 3
                        },
                        onSelected = { index ->
                            val mode = when (index) {
                                1 -> NetworkPercentMode.DOWNLOAD
                                2 -> NetworkPercentMode.UPLOAD
                                3 -> NetworkPercentMode.MAX
                                else -> NetworkPercentMode.TOTAL
                            }
                            viewModel.updateDraft { it.copy(networkPercentMode = mode.wire) }
                        },
                    )
                    NumericDraftField(
                        label = t("100% 对应速度", "100% reference speed"),
                        value = profile.networkReferenceValue,
                        onValueChange = { value ->
                            viewModel.updateDraft { it.copy(networkReferenceValue = value) }
                        },
                        stateKey = profile.id + ":networkReference",
                    )
                    DropdownRow(
                        title = t("参考单位", "Reference unit"),
                        options = listOf("Mbps", "KB/s", "MB/s"),
                        selectedIndex = when (profile.networkReferenceUnit) {
                            "Mbps" -> 0
                            "KB/s" -> 1
                            else -> 2
                        },
                        onSelected = { index ->
                            val unit = when (index) {
                                0 -> "Mbps"
                                1 -> "KB/s"
                                else -> "MB/s"
                            }
                            viewModel.updateDraft { it.copy(networkReferenceUnit = unit) }
                        },
                    )
                }
            }
        }

        if (usesTime) {
            SectionCard(
                title = t("时间选项", "Time options"),
                subtitle = t(
                    "关闭时显示当天进度；开启后显示距离目标时间的剩余比例。",
                    "Off shows today's progress; on shows the remaining share until the daily target.",
                ),
            ) {
                Column {
                    SwitchRow(
                        title = t("目标时间模式", "Target-time mode"),
                        checked = profile.timeTargetEnabled,
                        onCheckedChange = { enabled ->
                            viewModel.updateDraft { it.copy(timeTargetEnabled = enabled) }
                        },
                    )
                    if (profile.timeTargetEnabled) {
                        StringDraftField(
                            label = t("每日目标时间（HH:mm:ss）", "Daily target (HH:mm:ss)"),
                            value = profile.timeTarget,
                            onValueChange = { value -> viewModel.updateDraft { it.copy(timeTarget = value) } },
                            stateKey = profile.id + ":timeTarget",
                            isError = !isValidTime(profile.timeTarget),
                            supporting = if (isValidTime(profile.timeTarget)) {
                                null
                            } else {
                                t("请输入 00:00:00 – 23:59:59。", "Enter a time between 00:00:00 and 23:59:59.")
                            },
                        )
                    }
                }
            }
        }

        if (usesProbe) {
            SectionCard(
                title = t("探测选项", "Probe options"),
                subtitle = t(
                    "支持 IPv4、IPv6 或域名；端口仅用于 TCP / UDP。",
                    "IPv4, IPv6 or a host name; the port is used by TCP / UDP only.",
                ),
            ) {
                Column {
                    StringDraftField(
                        label = t("检测地址", "Probe target"),
                        value = profile.pingTarget,
                        onValueChange = { value -> viewModel.updateDraft { it.copy(pingTarget = value) } },
                        stateKey = profile.id + ":pingTarget",
                        isError = profile.pingTarget.isBlank(),
                        supporting = if (profile.pingTarget.isBlank()) {
                            t("检测地址不能为空。", "The probe target cannot be empty.")
                        } else {
                            t("例如：1.1.1.1 或 example.com", "e.g. 1.1.1.1 or example.com")
                        },
                    )
                    DropdownRow(
                        title = t("检测协议", "Protocol"),
                        options = ProbeProtocol.entries.map { it.wire },
                        selectedIndex = ProbeProtocol.entries
                            .indexOf(ProbeProtocol.fromWire(profile.probeProtocol))
                            .coerceAtLeast(0),
                        onSelected = { index ->
                            viewModel.updateDraft { it.copy(probeProtocol = ProbeProtocol.entries[index].wire) }
                        },
                    )
                    NumericDraftField(
                        label = t("检测端口", "Port"),
                        value = profile.probePort.toDouble(),
                        onValueChange = { value ->
                            viewModel.updateDraft { it.copy(probePort = value.toInt().coerceIn(1, 65535)) }
                        },
                        stateKey = profile.id + ":probePort",
                        decimals = false,
                    )
                }
            }
        }

        CarouselCard(viewModel = viewModel, config = config, language = language)
    }
}

@Composable
private fun SchemeCard(
    viewModel: ContentViewModel,
    profile: HudProfile,
    config: AppConfig,
    language: UiLanguage,
    builtIn: Boolean,
    message: ContentMessage?,
) {
    var nameDraft by remember(profile.id) { mutableStateOf(profile.name) }
    val profiles = config.customHud.profiles
    val activeId = config.customHud.activeProfileId
    val displayName = EcpMessages.profileDisplayName(profile, language)

    SectionCard(
        title = t("方案管理", "Scheme management"),
        subtitle = t(
            "选择当前显示的方案；内置方案保存时会创建副本。",
            "Choose the displayed scheme; saving a built-in creates a copy.",
        ),
    ) {
        Column {
            DropdownRow(
                title = t("当前方案", "Active scheme"),
                options = profiles.map { EcpMessages.profileDisplayName(it, language) },
                selectedIndex = profiles.indexOfFirst { it.id == profile.id }.coerceAtLeast(0),
                onSelected = { index -> viewModel.selectProfile(profiles[index].id) },
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (profile.id == activeId) {
                        t("当前使用中", "Currently active")
                    } else {
                        t("未使用", "Not active")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = viewModel::setActiveProfile,
                    enabled = profile.id != activeId,
                ) {
                    Text(t("设为当前", "Set active"))
                }
            }
            StringDraftField(
                label = if (builtIn) {
                    t("另存为名称", "Save copy as")
                } else {
                    t("方案名称", "Scheme name")
                },
                value = nameDraft,
                onValueChange = { nameDraft = it },
                stateKey = profile.id + ":schemeName",
                isError = nameDraft.isBlank(),
                supporting = if (nameDraft.isBlank()) {
                    t("名称不能为空。", "The name cannot be empty.")
                } else {
                    displayName
                },
            )
            if (DisplayMode.fromWire(config.android.displayMode) == DisplayMode.OVERLAY) {
                SegmentedRow(
                    options = AnimationMode.entries,
                    selected = AnimationMode.fromWire(profile.animationMode),
                    label = { mode ->
                        when (mode) {
                            AnimationMode.SIMPLE -> s("简洁", "Simple")
                            AnimationMode.FULL -> s("完整", "Full")
                        }
                    },
                    onSelect = { mode -> viewModel.updateDraft { it.copy(animationMode = mode.wire) } },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        viewModel.save(
                            enteredName = nameDraft.trim(),
                            modifiedSuffix = s("（已更改）", " (Modified)"),
                            customFallbackName = s("自定义方案", "Custom Profile"),
                        )
                    },
                ) {
                    Text(t("保存", "Save"))
                }
                OutlinedButton(
                    onClick = {
                        viewModel.createCustom(
                            s("自定义方案", "Custom Profile"),
                            s("系统状态", "System Status"),
                        )
                    },
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(t("新建", "New"))
                }
                OutlinedButton(
                    onClick = { viewModel.duplicate(displayName + s(" 副本", " copy")) },
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(t("复制", "Duplicate"))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = viewModel::revertDraft) {
                    Text(t("放弃修改", "Discard changes"))
                }
                if (!builtIn) {
                    OutlinedButton(
                        onClick = viewModel::delete,
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text(t("删除", "Delete"))
                    }
                }
            }
            if (message != null) {
                Text(
                    text = contentMessageText(message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun CarouselCard(viewModel: ContentViewModel, config: AppConfig, language: UiLanguage) {
    val customHud = config.customHud
    val queueIds = customHud.effectiveCycleProfileIds()
    val queued = queueIds.mapNotNull { id -> customHud.profileById(id) }
    val available = customHud.profiles.filter { profile -> queueIds.none { it == profile.id } }
    var interval by remember(customHud.cycleSeconds) { mutableStateOf(customHud.cycleSeconds.toString()) }

    SectionCard(
        title = t("自动轮播", "Automatic cycle"),
        subtitle = t(
            "按队列顺序循环切换方案；队列只引用仍然存在的方案。",
            "Cycles through the queue in order; the queue only references existing schemes.",
        ),
    ) {
        Column {
            SwitchRow(
                title = t("开启自动轮播", "Enable automatic cycle"),
                checked = customHud.autoCycle,
                onCheckedChange = viewModel::setAutoCycle,
            )
            NumberFieldRow(
                label = t("轮播间隔 / 秒", "Interval / s"),
                value = interval,
                onValueChange = { text ->
                    interval = text
                    UiFormatting.parseInt(text)?.let(viewModel::setCycleSeconds)
                },
                isError = !(UiFormatting.parseInt(interval) in 3..3600),
                supporting = t("范围 3 – 3600 秒。", "Range 3 – 3600 seconds."),
            )
            if (DisplayMode.fromWire(config.android.displayMode) == DisplayMode.OVERLAY) {
                Text(t("轮播切换动画", "Cycle transition animation"), modifier = Modifier.padding(horizontal = 16.dp))
                SegmentedRow(options = AnimationMode.entries,
                    selected = AnimationMode.fromWire(customHud.cycleAnimationMode),
                    label = { if (it == AnimationMode.SIMPLE) s("简洁", "Simple") else s("完整", "Full") },
                    onSelect = viewModel::setCycleAnimationMode)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = t("轮播队列", "Cycle queue"),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (queued.isEmpty()) {
                EmptyState(t("队列为空。", "The queue is empty."))
            } else {
                queued.forEachIndexed { index, profile ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${index + 1}. " + EcpMessages.profileDisplayName(profile, language),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { viewModel.moveCycleEntry(index, -1) },
                            enabled = index > 0,
                        ) { Text(t("上移", "Up")) }
                        TextButton(
                            onClick = { viewModel.moveCycleEntry(index, 1) },
                            enabled = index < queued.size - 1,
                        ) { Text(t("下移", "Down")) }
                        TextButton(onClick = { viewModel.removeCycleEntry(index) }) {
                            Text(t("移除", "Remove"))
                        }
                    }
                }
            }
            if (available.isNotEmpty()) {
                Text(
                    text = t("添加到队列", "Add to queue"),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                available.forEach { profile ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = EcpMessages.profileDisplayName(profile, language),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { viewModel.addCycleEntry(profile.id) }) {
                            Text(t("添加", "Add"))
                        }
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            Text(
                text = t(
                    "轮播动画会覆盖各方案自身的动画模式。",
                    "The cycle animation overrides each scheme's own animation mode.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun contentMessageText(message: ContentMessage): String = when (message) {
    ContentMessage.SAVED -> t("方案已保存。", "Scheme saved.")
    ContentMessage.SAVED_AS_COPY -> t(
        "已基于内置方案创建副本，原预设保持不变。",
        "A copy was created from the built-in scheme; the preset is unchanged.",
    )
    ContentMessage.NOTHING_CHANGED -> t("没有需要保存的更改。", "Nothing to save.")
    ContentMessage.BUILT_IN_READ_ONLY -> t("内置方案不可删除。", "Built-in schemes cannot be deleted.")
    ContentMessage.CREATED -> t("方案已创建。", "Scheme created.")
    ContentMessage.DELETED -> t("方案已删除。", "Scheme deleted.")
}

@Composable
private fun templateSupport(text: String): String? {
    val problem = TemplateText.validate(text) ?: return null
    return when (problem.issue) {
        TemplateText.Issue.UNBALANCED_BRACES -> t("花括号不匹配。", "The braces do not match.")
        TemplateText.Issue.EMPTY_KEY -> t("变量名为空。", "The variable name is empty.")
        TemplateText.Issue.NESTED_BRACES -> t("不支持嵌套花括号。", "Nested braces are not supported.")
        TemplateText.Issue.EMPTY_EXPRESSION -> t("表达式为空。", "The expression is empty.")
    }
}

/** A numeric draft field backed by local text so partial input never rewrites the draft. */
@Composable
private fun NumericDraftField(
    label: String,
    value: Double,
    onValueChange: (Double) -> Unit,
    stateKey: String,
    decimals: Boolean = true,
) {
    var text by remember(stateKey) { mutableStateOf(UiFormatting.numberText(value)) }
    NumberFieldRow(
        label = label,
        value = text,
        onValueChange = { updated ->
            text = updated
            UiFormatting.parseDouble(updated)?.let(onValueChange)
        },
        decimal = decimals,
        isError = UiFormatting.parseDouble(text) == null,
        supporting = if (UiFormatting.parseDouble(text) == null) {
            t("请输入数值。", "Enter a number.")
        } else {
            null
        },
    )
}

/** A single-line draft text field backed by local text. */
@Composable
private fun StringDraftField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    stateKey: String,
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

@Composable
private fun ColourField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    stateKey: String,
) {
    var text by remember(stateKey) { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { updated ->
            text = updated
            if (UiFormatting.isColor(updated)) onValueChange(updated)
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        label = { Text(label) },
        isError = !UiFormatting.isColor(text),
        supportingText = if (UiFormatting.isColor(text)) {
            null
        } else {
            { Text(t("请输入 #RRGGBB 或 #AARRGGBB。", "Enter #RRGGBB or #AARRGGBB.")) }
        },
        singleLine = true,
    )
}

private fun isValidTime(text: String): Boolean {
    val parts = text.trim().split(':')
    if (parts.size != 3) return false
    val hours = parts[0].toIntOrNull() ?: return false
    val minutes = parts[1].toIntOrNull() ?: return false
    val seconds = parts[2].toIntOrNull() ?: return false
    return hours in 0..23 && minutes in 0..59 && seconds in 0..59
}

/** Which profile-scoped option groups are relevant for a scheme. */
internal object ProfileNeeds {

    fun network(profile: HudProfile): Boolean =
        profile.builtInKey.equals("system.network", ignoreCase = true) ||
            keys(profile).any { it.startsWith("network.") }

    fun time(profile: HudProfile): Boolean =
        profile.builtInKey.equals("time.day-progress", ignoreCase = true) ||
            keys(profile).any { it.startsWith("time.") }

    fun probe(profile: HudProfile): Boolean =
        profile.builtInKey.equals("network.ping", ignoreCase = true) ||
            keys(profile).any { it.startsWith("probe.") || it.startsWith("ping.") }

    private fun keys(profile: HudProfile): List<String> = TemplateText.variableKeys(
        profile.taglineTemplate,
    ) + TemplateText.variableKeys(profile.titleTemplate) +
        TemplateText.variableKeys(profile.primaryTemplate) +
        TemplateText.variableKeys(profile.secondaryTemplate) +
        TemplateText.variableKeys(profile.rightTemplate) +
        TemplateText.progressKeys(profile.progressVariable)
}
