package com.glacierglimmer.endfieldchargeplus.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glacierglimmer.endfieldchargeplus.localization.t
import com.glacierglimmer.endfieldchargeplus.update.GitHubUpdateClient
import com.glacierglimmer.endfieldchargeplus.update.UpdateChecker
import com.glacierglimmer.endfieldchargeplus.update.UpdateStatus

@Composable fun UpdatePrompt(checker: UpdateChecker) {
    val state by checker.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    state.promptVersion?.let { version ->
        AlertDialog(onDismissRequest = checker::dismissPrompt,
            title = { Text(t("发现新版本", "Update available")) },
            text = { Text(t("Android v$version 已发布，可前往发布页更新。", "Android v$version is available on the releases page.")) },
            confirmButton = { TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GitHubUpdateClient.RELEASES_URL))) }
                checker.dismissPrompt()
            }) { Text(t("查看更新", "View update")) } },
            dismissButton = { TextButton(onClick = checker::dismissPrompt) { Text(t("稍后", "Later")) } })
    }
}

@Composable fun UpdateCard(checker: UpdateChecker) {
    val state by checker.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SectionCard(title = t("检查更新", "Check updates"), subtitle = t("每次启动自动检查一次，也可以手动检查。", "Checks once at startup; you can also check manually.")) {
        Column {
            state.result?.let { result ->
                InfoRow(t("当前版本", "Current version"), "v${result.currentVersion}")
                InfoRow(t("最新版本", "Latest version"), result.latestVersion?.let { "v$it" } ?: "—")
            }
            Text(if (state.checking) t("正在连接 GitHub…", "Checking GitHub…") else when (state.result?.status) {
                UpdateStatus.UPDATE_AVAILABLE -> t("有新版本可用。", "An update is available.")
                UpdateStatus.UP_TO_DATE -> t("当前已是最新版本。", "You're up to date.")
                UpdateStatus.LOCAL_NEWER -> t("本地版本高于已发布版本。", "Your local version is newer.")
                UpdateStatus.NO_REMOTE_VERSION -> t("尚未找到已发布版本或标签。", "No published release or tag was found.")
                UpdateStatus.UNCOMPARABLE -> t("远端标签无法比较，请查看发布页。", "The remote tag cannot be compared; see releases.")
                UpdateStatus.NETWORK_ERROR -> t("无法连接 GitHub，请稍后重试。", "Could not reach GitHub; retry later.")
                UpdateStatus.TIMEOUT -> t("检查超时，请重试。", "The check timed out; retry.")
                UpdateStatus.ERROR -> t("无法读取更新信息，请重试。", "Could not read update details; retry.")
                null -> t("尚未检查。", "Not checked yet.")
            }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            OutlinedButton(onClick = checker::checkManually, enabled = !state.checking, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(t("检查更新", "Check updates"))
            }
            TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GitHubUpdateClient.RELEASES_URL))) } },
                modifier = Modifier.padding(horizontal = 16.dp)) { Text(t("打开发布页", "Open releases")) }
        }
    }
}
