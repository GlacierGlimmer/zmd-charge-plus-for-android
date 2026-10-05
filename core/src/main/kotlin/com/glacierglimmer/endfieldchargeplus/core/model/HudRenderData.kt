package com.glacierglimmer.endfieldchargeplus.core.model

/**
 * Everything the HUD renderer needs for one frame, after templates have been evaluated.
 *
 * Mirrors `HudRenderData` from the Windows edition; `simpleAnimation` selects the simple
 * (fade/slide only) transition instead of the full geometric transition.
 */
data class HudRenderData(
    val tagline: String = "",
    val title: String = "",
    val primaryText: String = "",
    val secondaryText: String = "",
    val rightText: String = "",
    val rightSuffix: String = "",
    val progress: Double = 0.0,
    val leftIcon: String = "",
    val rightIcon: String = "",
    val accentColor: String = "#C6CA4C",
    val simpleAnimation: Boolean = false,
)
