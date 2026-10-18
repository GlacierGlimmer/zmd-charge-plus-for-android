package com.glacierglimmer.endfieldchargeplus.ui.components
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glacierglimmer.endfieldchargeplus.di.EcpContainer
import com.glacierglimmer.endfieldchargeplus.localization.t
import com.glacierglimmer.endfieldchargeplus.service.HudRuntimeState
import com.glacierglimmer.endfieldchargeplus.root.RootStatus

@Composable
fun ProfileDataPrompt(container: EcpContainer) {
    val config by container.configRepository.config.collectAsStateWithLifecycle()
    val snapshot by container.metricRepository.snapshot.collectAsStateWithLifecycle()
    val runtime by HudRuntimeState.status.collectAsStateWithLifecycle()
    val rootStatus by container.rootAccess.status.collectAsStateWithLifecycle()
    val notice by container.dataWarnings.pending.collectAsStateWithLifecycle()
    val profile=config.customHud.profiles.firstOrNull { it.id==runtime.activeProfileId } ?: config.customHud.activeProfile()
    LaunchedEffect(profile,snapshot,rootStatus) {
        if(config.hudEnabled && profile!=null && rootStatus!=RootStatus.CHECKING) container.dataWarnings.check(profile,snapshot)
    }
    notice?.let { warning ->
        AlertDialog(onDismissRequest={container.dataWarnings.dismiss()},
            title={Text(t("HUD 数据提示","HUD data warning"))},
            text={Text(t("方案“${warning.name}”的数据不可用或无效。请检查 ROOT／权限、设备支持、所选窗口和数据源设置。","Data for '${warning.name}' is unavailable or invalid. Check ROOT/permissions, device support, the selected window and data-source settings.")+"\n\n"+warning.keys.joinToString("\n"))},
            confirmButton={Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(checked=false,onCheckedChange={checked -> if(checked) container.dataWarnings.ignore(warning)})
                    Text(t("此数据不再提示","Don't warn about this data again"))
                }
                TextButton(onClick={container.dataWarnings.dismiss()}) {Text(t("知道了","OK"))}
            }})
    }
}
