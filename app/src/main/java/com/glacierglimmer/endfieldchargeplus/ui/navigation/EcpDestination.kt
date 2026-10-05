package com.glacierglimmer.endfieldchargeplus.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Every top-level page of the settings application.
 *
 * There are no dead entries: each destination is reachable from the navigation drawer, the
 * navigation rail (wide screens) or the bottom bar (compact screens), and each one renders a real
 * page.
 */
enum class EcpDestination(
    val route: String,
    val titleZh: String,
    val titleEn: String,
    val icon: ImageVector,
    /** Shown directly in the compact bottom bar. */
    val inBottomBar: Boolean,
) {
    HOME("home", "首页", "Home", Icons.Filled.Home, true),
    DISPLAY("display", "显示", "Display", Icons.Filled.Smartphone, true),
    CONTENT("content", "HUD 内容", "HUD Content", Icons.Filled.Dashboard, true),
    DATA("datasources", "数据源", "Data sources", Icons.Filled.Cloud, true),
    ADVANCED("advanced", "高级", "Advanced", Icons.Filled.Tune, false),
    VARIABLES("variables", "变量库", "Variables", Icons.AutoMirrored.Filled.List, false),
    ABOUT("about", "关于", "About", Icons.Filled.Info, false),
    ;

    companion object {
        fun fromRoute(route: String?): EcpDestination =
            entries.firstOrNull { it.route == route } ?: HOME

        /** The four primary destinations shown in the compact bottom bar. */
        val bottomBar: List<EcpDestination> = entries.filter { it.inBottomBar }
    }
}
