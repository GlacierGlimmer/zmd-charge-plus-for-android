package com.glacierglimmer.endfieldchargeplus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as EcpApplication).container
        val languageController = LanguageController(container)

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
