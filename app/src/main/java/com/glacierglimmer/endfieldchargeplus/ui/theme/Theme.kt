package com.glacierglimmer.endfieldchargeplus.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle

/**
 * The Android-native Material 3 theme of the settings shell.
 *
 * The Endfield game styling belongs to the HUD renderer only; the settings application is a normal
 * Material 3 app in light and dark, with the ECP accent (`#C6CA4C`) as the brand colour. Dynamic
 * colour is used on Android 12+ and the brand palette is the fallback.
 */
private val EcpAccent = Color(0xFFC6CA4C)
private val EcpAccentDeep = Color(0xFF4C5210)
private val EcpAccentSoft = Color(0xFFE3E7A6)
private val EcpInk = Color(0xFF1B1D18)

private val LightColors = lightColorScheme(
    primary = EcpAccentDeep,
    onPrimary = Color.White,
    primaryContainer = EcpAccentSoft,
    onPrimaryContainer = Color(0xFF171A05),
    secondary = Color(0xFF4F5A46),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3DEC6),
    onSecondaryContainer = Color(0xFF0E1507),
    tertiary = Color(0xFF38656A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFBCEBF0),
    onTertiaryContainer = Color(0xFF00201F),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7F8F3),
    onBackground = Color(0xFF1A1C18),
    surface = Color(0xFFF7F8F3),
    onSurface = Color(0xFF1A1C18),
    surfaceVariant = Color(0xFFE1E4D5),
    onSurfaceVariant = Color(0xFF44483D),
    outline = Color(0xFF75796C),
    outlineVariant = Color(0xFFC5C8BA),
)

private val DarkColors = darkColorScheme(
    primary = EcpAccent,
    onPrimary = EcpInk,
    primaryContainer = Color(0xFF3A3F12),
    onPrimaryContainer = Color(0xFFE7EBA0),
    secondary = Color(0xFFB7C7A6),
    onSecondary = Color(0xFF23301A),
    secondaryContainer = Color(0xFF39472D),
    onSecondaryContainer = Color(0xFFD3DEC6),
    tertiary = Color(0xFFA0CFD5),
    onTertiary = Color(0xFF003739),
    tertiaryContainer = Color(0xFF1F4E53),
    onTertiaryContainer = Color(0xFFBCEBF0),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF121310),
    onBackground = Color(0xFFE3E4DC),
    surface = Color(0xFF121310),
    onSurface = Color(0xFFE3E4DC),
    surfaceVariant = Color(0xFF44483D),
    onSurfaceVariant = Color(0xFFC5C8BA),
    outline = Color(0xFF8F9284),
    outlineVariant = Color(0xFF44483D),
)

/** The ECP settings theme; edge-to-edge safe and dark/light aware. */
@Composable
fun EcpTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography.let { base ->
            fun padded(style: TextStyle) = style.copy(
                platformStyle = PlatformTextStyle(includeFontPadding = true),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
            )
            base.copy(
                titleLarge = padded(base.titleLarge), titleMedium = padded(base.titleMedium),
                titleSmall = padded(base.titleSmall), bodyLarge = padded(base.bodyLarge),
                bodyMedium = padded(base.bodyMedium), bodySmall = padded(base.bodySmall),
                labelLarge = padded(base.labelLarge), labelMedium = padded(base.labelMedium),
                labelSmall = padded(base.labelSmall),
            )
        },
        content = content,
    )
}
