package com.glacierglimmer.endfieldchargeplus.ui.screens.about

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glacierglimmer.endfieldchargeplus.core.product.ProductInfo
import com.glacierglimmer.endfieldchargeplus.di.EcpContainer
import com.glacierglimmer.endfieldchargeplus.localization.t
import com.glacierglimmer.endfieldchargeplus.ui.components.InfoRow
import com.glacierglimmer.endfieldchargeplus.ui.components.SectionCard
import com.glacierglimmer.endfieldchargeplus.ui.screens.about.AboutViewModel.Companion.factory as aboutViewModelFactory

/**
 * 关于 / About.
 *
 * Product facts, attribution and licensing as they actually are in code, plus an honest capability
 * disclaimer that never claims more than this device allows.
 */
@Composable
fun AboutScreen(
    container: EcpContainer,
    modifier: Modifier = Modifier,
    viewModel: AboutViewModel = viewModel(factory = aboutViewModelFactory(container)),
) {
    val capabilities by viewModel.capabilities.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LazyColumn(modifier = modifier.fillMaxSize()) {
        item {
            SectionCard(
                title = ProductInfo.NAME,
                subtitle = "${t("版本", "Version")} ${ProductInfo.VERSION_NAME} (${ProductInfo.VERSION_CODE})",
            ) {
                Column {
                    InfoRow(t("简称", "Short name"), ProductInfo.SHORT_NAME)
                    InfoRow(t("作者", "Author"), ProductInfo.AUTHOR)
                    InfoRow(t("项目网站", "Website"), ProductInfo.WEBSITE)
                    InfoRow(t("应用包名", "Package"), ProductInfo.ANDROID_PACKAGE_NAME)
                }
            }
        }

        item {
            SectionCard(
                title = t("项目与协议", "Project and license"),
                subtitle = t(
                    "本项目是 QinAnze/zmd-charge 的派生版本，遵循 MIT 协议。",
                    "This project is a derivative of QinAnze/zmd-charge and is distributed under the MIT License.",
                ),
            ) {
                Column {
                    InfoRow(t("开源协议", "License"), ProductInfo.LICENSE_NAME)
                    InfoRow(t("本项目 GitHub", "Project GitHub"), ProductInfo.GITHUB_REPOSITORY)
                    InfoRow(t("桌面版仓库", "Desktop repository"), ProductInfo.DESKTOP_REPOSITORY)
                    InfoRow(t("原项目", "Upstream project"), ProductInfo.UPSTREAM_PROJECT)
                    OutlinedButton(
                        onClick = {
                            shareText(
                                context,
                                "${ProductInfo.NAME} ${ProductInfo.VERSION_NAME}\n" +
                                    "https://${ProductInfo.WEBSITE}\n" +
                                    "https://github.com/${ProductInfo.GITHUB_REPOSITORY}\n" +
                                    "upstream: https://github.com/${ProductInfo.UPSTREAM_PROJECT}",
                            )
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(t("分享应用信息", "Share app info"))
                    }
                }
            }
        }

        item {
            SectionCard(
                title = t("能力说明", "Capability disclaimer"),
                subtitle = viewModel.supportSummary(),
            ) {
                Column {
                    Text(
                        text = t(
                            "Android 不允许普通应用读取全部硬件指标：GPU 占用、CPU 温度、部分电池电流等接口在大多数设备上不可用。ECP 只会显示真正读到的数据，无法读取时明确标注“不支持”，绝不伪造数值。",
                            "Android does not let an ordinary app read every hardware metric: GPU load, CPU temperature and part of the battery current are unavailable on most devices. ECP only shows what it really reads, marks the rest as unsupported, and never fabricates a value.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    InfoRow(t("设备", "Device"), capabilities.deviceModel.ifBlank { "—" })
                    InfoRow(t("Android", "Android"), capabilities.androidRelease.ifBlank { "—" })
                    InfoRow(t("CPU", "CPU"), capabilities.cpuModel.ifBlank { "—" })
                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    OutlinedButton(
                        onClick = { shareText(context, viewModel.diagnosticsText(context)) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Text(t("分享设备与能力信息", "Share device and capability info"))
                    }
                }
            }
        }

        item {
            SectionCard(
                title = t("致谢", "Credits"),
                subtitle = t(
                    "感谢 QinAnze 的原始开源工作，以及 Avalonia、Jetpack Compose 等开源项目。",
                    "Thanks to QinAnze for the original open-source work, and to the Avalonia and Jetpack Compose projects.",
                ),
            ) {
                Column {
                    Text(
                        text = "© 2026 ${ProductInfo.AUTHOR}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun shareText(context: android.content.Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(Intent.createChooser(send, null)) }
}
