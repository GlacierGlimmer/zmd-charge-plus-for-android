package com.glacierglimmer.endfieldchargeplus.ui.screens.datasources

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glacierglimmer.endfieldchargeplus.di.EcpContainer
import com.glacierglimmer.endfieldchargeplus.localization.t
import com.glacierglimmer.endfieldchargeplus.localization.keyed
import com.glacierglimmer.endfieldchargeplus.ui.screens.variables.VariablesScreen
import com.glacierglimmer.endfieldchargeplus.ui.state.VariableProjection
import com.glacierglimmer.endfieldchargeplus.localization.LocalUiLanguage
import kotlinx.coroutines.launch

@Composable
fun DataManagementScreen(container:EcpContainer,initialTab:Int=0) {
    var tab by remember(initialTab) {mutableIntStateOf(initialTab.coerceIn(0,2))}
    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex=tab,edgePadding=12.dp) {
            listOf(t("变量库","Variable Library"),t("硬件数据","Hardware Data"),t("数据源配置","Data Source Configuration")).forEachIndexed {index,label ->
                Tab(selected=index==tab,onClick={tab=index},text={Text(label)})
            }
        }
        Box(Modifier.weight(1f)) {
            when(tab) {0->VariablesScreen(container);1->HardwareDataScreen(container);else->DataSourcesScreen(container)}
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun HardwareDataScreen(container:EcpContainer) {
    val snapshot by container.metricRepository.snapshot.collectAsStateWithLifecycle()
    val language=LocalUiLanguage.current
    var query by remember {mutableStateOf("")}
    var reading by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val clipboard=LocalClipboardManager.current
    val rows=remember(snapshot,language,query) {VariableProjection.rows(snapshot,language).filter {row ->
        listOf("cpu.","memory.","gpu.","battery.","disk.","system.","device.").any {row.name.startsWith(it)} &&
            (query.isBlank() || "${row.label} ${row.name}".contains(query,true))
    }}
    Column(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(t("显示设备和系统实际提供的读数；不可用项目显示 --，并说明原因。","Shows readings actually provided by the device and system. Unavailable items show -- and their reason."))
        Button(enabled=!reading,onClick={reading=true;scope.launch {try {container.metricRepository.refreshCapabilities();container.metricRepository.refreshNow()} finally {reading=false}}}) {Text(t("重新检测","Refresh"))}
        OutlinedTextField(value=query,onValueChange={query=it},label={Text(t("搜索名称、来源 ID 或变量","Search name, source ID or variable"))},modifier=Modifier.fillMaxWidth(),singleLine=true)
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(rows,key={it.name}) {row ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Text(row.label,style=MaterialTheme.typography.titleMedium)
                        Text(row.name,fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)
                        Text(snapshot.text(row.name) ?: "--")
                        snapshot.unavailableReason(row.name)?.let {reason ->Text(keyed("unavailable_reason_"+reason.name.lowercase()))}
                        TextButton(onClick={clipboard.setText(AnnotatedString("{${row.name}}"))}) {Text(t("复制所选变量","Copy selected variable"))}
                    }
                }
            }
        }
    }
}
