package com.glacierglimmer.endfieldchargeplus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.glacierglimmer.endfieldchargeplus.service.HudServiceController
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.glacierglimmer.endfieldchargeplus.localization.LanguageController
import com.glacierglimmer.endfieldchargeplus.ui.navigation.EcpApp
import com.glacierglimmer.endfieldchargeplus.ui.theme.EcpTheme

/**
 * The only Activity of the Android edition.
 *
 * It owns nothing but the window: edge-to-edge is enabled here, the language preference is applied
 * to the shared string table, and the whole UI is the Compose [EcpApp]. The HUD itself lives in
 * the foreground service, never in this Activity.
 */
class MainActivity : ComponentActivity() {

    override fun onStart() { super.onStart(); (application as EcpApplication).container.setForegroundUi(true) }
    override fun onResume() { super.onResume(); com.glacierglimmer.endfieldchargeplus.service.HudServiceController.refreshPermissions(this) }
    override fun onStop() { (application as EcpApplication).container.setForegroundUi(false); super.onStop() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as EcpApplication).container
        container.updateChecker.checkStartup()
        val languageController = LanguageController(container)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                container.configRepository.load()
                container.configRepository.config.map { it.hudEnabled to it.android.displayMode }
                    .distinctUntilChanged().collect { (enabled, _) ->
                        if (enabled && !HudServiceController.isRunning(this@MainActivity)) {
                            // Reconcile on each resume, including returning after an overlay grant.
                            HudServiceController.start(this@MainActivity)
                        } else if (!enabled && HudServiceController.isRunning(this@MainActivity)) {
                            HudServiceController.stop(this@MainActivity)
                        }
                    }
            }
        }

        setContent {
            val config by container.configRepository.config.collectAsStateWithLifecycle()
            LaunchedEffect(config.uiLanguage) {
                languageController.applyPreference(config.uiLanguage)
            }
            EcpTheme {
                EcpApp(container = container, languageController = languageController)
            }
        }
    }
}
