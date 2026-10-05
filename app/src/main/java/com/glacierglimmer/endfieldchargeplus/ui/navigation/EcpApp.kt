package com.glacierglimmer.endfieldchargeplus.ui.navigation

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.glacierglimmer.endfieldchargeplus.di.EcpContainer
import com.glacierglimmer.endfieldchargeplus.localization.LanguageController
import com.glacierglimmer.endfieldchargeplus.localization.LocalUiLanguage
import com.glacierglimmer.endfieldchargeplus.localization.t
import com.glacierglimmer.endfieldchargeplus.ui.screens.about.AboutScreen
import com.glacierglimmer.endfieldchargeplus.ui.screens.advanced.AdvancedScreen
import com.glacierglimmer.endfieldchargeplus.ui.screens.content.ContentScreen
import com.glacierglimmer.endfieldchargeplus.ui.screens.datasources.DataSourcesScreen
import com.glacierglimmer.endfieldchargeplus.ui.screens.display.DisplayScreen
import com.glacierglimmer.endfieldchargeplus.ui.screens.home.HomeScreen
import com.glacierglimmer.endfieldchargeplus.ui.screens.variables.VariablesScreen
import kotlinx.coroutines.launch

/**
 * Root of the settings application.
 *
 * Layout is responsive: a navigation rail plus the content on wide screens (≥ 720 dp), and a
 * navigation drawer plus a four-item bottom bar on compact screens. Insets are handled by
 * [Scaffold], so the app is correct in portrait, landscape, on displays with a cutout and at any
 * density.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EcpApp(
    container: EcpContainer,
    languageController: LanguageController,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = EcpDestination.fromRoute(backStackEntry?.destination?.route)
    val wide = LocalConfiguration.current.screenWidthDp >= 720

    CompositionLocalProvider(LocalUiLanguage provides languageController.language) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    Text(
                        text = t("设置", "Settings"),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                    EcpDestination.entries.forEach { destination ->
                        NavigationDrawerItem(
                            selected = destination == current,
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(t(destination.titleZh, destination.titleEn)) },
                            onClick = {
                                scope.launch { drawerState.close() }
                                navController.navigateTo(destination)
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                        )
                    }
                }
            },
        ) {
            Scaffold(
                modifier = modifier,
                topBar = {
                    TopAppBar(
                        title = { Text(t(current.titleZh, current.titleEn)) },
                        navigationIcon = {
                            if (!wide) {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Filled.Menu, contentDescription = t("打开导航", "Open navigation"))
                                }
                            }
                        },
                    )
                },
                bottomBar = {
                    if (!wide) {
                        NavigationBar {
                            EcpDestination.bottomBar.forEach { destination ->
                                NavigationBarItem(
                                    selected = destination == current,
                                    onClick = { navController.navigateTo(destination) },
                                    icon = { Icon(destination.icon, contentDescription = null) },
                                    label = { Text(t(destination.titleZh, destination.titleEn)) },
                                )
                            }
                            NavigationBarItem(
                                selected = false,
                                onClick = { scope.launch { drawerState.open() } },
                                icon = { Icon(Icons.Filled.MoreHoriz, contentDescription = null) },
                                label = { Text(t("更多", "More")) },
                            )
                        }
                    }
                },
            ) { innerPadding ->
                Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                    if (wide) {
                        NavigationRail {
                            EcpDestination.entries.forEach { destination ->
                                NavigationRailItem(
                                    selected = destination == current,
                                    onClick = { navController.navigateTo(destination) },
                                    icon = { Icon(destination.icon, contentDescription = null) },
                                    label = { Text(t(destination.titleZh, destination.titleEn)) },
                                )
                            }
                        }
                    }
                    EcpNavGraph(
                        navController = navController,
                        container = container,
                        languageController = languageController,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** The single NavHost of the application. */
@Composable
private fun EcpNavGraph(
    navController: NavHostController,
    container: EcpContainer,
    languageController: LanguageController,
    modifier: Modifier = Modifier,
) {
    val go: (EcpDestination) -> Unit = { navController.navigateTo(it) }
    NavHost(
        navController = navController,
        startDestination = EcpDestination.HOME.route,
        modifier = modifier,
    ) {
        composable(EcpDestination.HOME.route) {
            HomeScreen(
                container = container,
                languageController = languageController,
                onNavigate = go,
            )
        }
        composable(EcpDestination.DISPLAY.route) { DisplayScreen(container = container, onNavigate = go) }
        composable(EcpDestination.CONTENT.route) { ContentScreen(container = container) }
        composable(EcpDestination.DATA.route) { DataSourcesScreen(container = container) }
        composable(EcpDestination.ADVANCED.route) { AdvancedScreen(container = container) }
        composable(EcpDestination.VARIABLES.route) { VariablesScreen(container = container) }
        composable(EcpDestination.ABOUT.route) { AboutScreen(container = container) }
    }
}

/** Navigates to [destination], reusing the existing entry instead of stacking duplicates. */
private fun NavHostController.navigateTo(destination: EcpDestination) {
    navigate(destination.route) {
        popUpTo(EcpDestination.HOME.route) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
