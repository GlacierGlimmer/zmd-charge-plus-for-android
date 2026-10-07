package com.glacierglimmer.endfieldchargeplus.ui.screens.variables

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glacierglimmer.endfieldchargeplus.di.EcpContainer
import com.glacierglimmer.endfieldchargeplus.localization.EcpMessages
import com.glacierglimmer.endfieldchargeplus.localization.LocalUiLanguage
import com.glacierglimmer.endfieldchargeplus.localization.t
import com.glacierglimmer.endfieldchargeplus.ui.components.ChipTone
import com.glacierglimmer.endfieldchargeplus.ui.components.EmptyState
import com.glacierglimmer.endfieldchargeplus.ui.components.InfoRow
import com.glacierglimmer.endfieldchargeplus.ui.components.SectionCard
import com.glacierglimmer.endfieldchargeplus.ui.components.StatusChip
import com.glacierglimmer.endfieldchargeplus.ui.screens.variables.VariablesViewModel.Companion.factory as variablesViewModelFactory
import com.glacierglimmer.endfieldchargeplus.ui.state.VariableRow
import com.glacierglimmer.endfieldchargeplus.ui.state.VariableSearch

/**
 * 变量库 / Variable library.
 *
 * Browse and search the whole registry grouped by category, see type/unit/format suggestions and the
 * live availability of each variable, and copy either the key or a ready-to-paste template token.
 * On wide screens the list and the detail pane are shown side by side.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VariablesScreen(
    container: EcpContainer,
    modifier: Modifier = Modifier,
    viewModel: VariablesViewModel = viewModel(factory = variablesViewModelFactory(container)),
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val category by viewModel.category.collectAsStateWithLifecycle()
    val selectedName by viewModel.selectedName.collectAsStateWithLifecycle()
    val language = LocalUiLanguage.current
    val context = LocalContext.current
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    var sheetOpen by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf<String?>(null) }

    val rows = remember(snapshot, language) { viewModel.rows(snapshot, language) }
    val categories = remember(rows) { viewModel.categories(rows) }
    val filtered = remember(rows, query, category) { VariableSearch.filter(rows, query, category) }
    val selected = remember(filtered, selectedName) {
        filtered.firstOrNull { it.name == selectedName } ?: filtered.firstOrNull()
    }

    val onCopy: (String, String) -> Unit = { label, text ->
        copyToClipboard(context, text)
        copied = label
    }

    Column(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(t("搜索变量", "Search variables")) },
                placeholder = { Text(t("CPU 使用率 / cpu.usage", "CPU usage / cpu.usage")) },
                singleLine = true,
            )
            Spacer(Modifier.height(6.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
            ) {
                item {
                    FilterChip(
                        selected = category == VariableSearch.ALL_CATEGORIES,
                        onClick = { viewModel.setCategory(VariableSearch.ALL_CATEGORIES) },
                        label = { Text(t("全部分类", "All categories")) },
                    )
                }
                items(categories, key = { it }) { key ->
                    FilterChip(
                        selected = category == key,
                        onClick = { viewModel.setCategory(key) },
                        label = { Text(EcpMessages.categoryName(key, language)) },
                    )
                }
            }
            copied?.let { label ->
                Text(
                    text = t("已复制：$label", "Copied: $label"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        HorizontalDivider()

        if (filtered.isEmpty()) {
            EmptyState(t("没有匹配的变量。", "No matching variable."))
            return@Column
        }

        if (wide) {
            Row(modifier = Modifier.fillMaxSize()) {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filtered, key = { it.name }) { row ->
                        VariableListRow(
                            row = row,
                            selected = row.name == selected?.name,
                            onClick = { viewModel.select(row.name) },
                        )
                        HorizontalDivider()
                    }
                }
                Column(
                    modifier = Modifier
                        .width(380.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    selected?.let { VariableDetail(row = it, onCopy = onCopy) }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.name }) { row ->
                    VariableListRow(
                        row = row,
                        selected = false,
                        onClick = {
                            viewModel.select(row.name)
                            sheetOpen = true
                        },
                    )
                    HorizontalDivider()
                }
            }
            if (sheetOpen && selected != null) {
                ModalBottomSheet(
                    onDismissRequest = { sheetOpen = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        VariableDetail(row = selected, onCopy = onCopy)
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun VariableListRow(row: VariableRow, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            StatusChip(
                text = if (row.available) {
                    t("可用", "Available")
                } else if (row.unavailableReason.isNotBlank()) {
                    row.unavailableReason
                } else {
                    t("尚无数据", "No data yet")
                },
                tone = if (row.available) ChipTone.OK else ChipTone.OFF,
            )
        }
        Text(
            text = row.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VariableDetail(row: VariableRow, onCopy: (String, String) -> Unit) {
    val language = LocalUiLanguage.current
    SectionCard(
        title = row.name,
        subtitle = EcpMessages.categoryName(row.categoryKey, language),
    ) {
        Column {
            Text(
                text = row.description,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(6.dp))
            InfoRow(t("类型", "Type"), row.typeLabel)
            InfoRow(t("单位", "Unit"), row.unit.ifBlank { t("无固定单位", "No fixed unit") })
            InfoRow(t("常用格式", "Common formats"), row.formats.ifBlank { t("无需格式化", "No formatting") })
            InfoRow(t("模板写法", "Template"), "{" + row.name + "}")
            if (row.androidNote.isNotBlank()) {
                InfoRow(t("Android 说明", "Android note"), row.androidNote)
            }
            InfoRow(
                t("当前状态", "Current state"),
                if (row.available) {
                    t("可用", "Available")
                } else if (row.unavailableReason.isNotBlank()) {
                    row.unavailableReason
                } else {
                    t("尚无数据", "No data yet")
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { onCopy("key", row.name) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(t("复制变量名", "Copy key"))
                }
                OutlinedButton(
                    onClick = { onCopy("template", "{" + row.name + "}") },
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(t("复制模板", "Copy template"))
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    runCatching { manager.setPrimaryClip(ClipData.newPlainText("ECP", text)) }
}
