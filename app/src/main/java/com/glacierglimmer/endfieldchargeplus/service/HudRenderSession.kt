package com.glacierglimmer.endfieldchargeplus.service

import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.core.model.DisplayMode
import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile
import com.glacierglimmer.endfieldchargeplus.core.model.HudRenderData
import com.glacierglimmer.endfieldchargeplus.overlay.OverlayController

/** Serializes configuration readiness and the first output, independently of sampling order. */
internal class HudRenderSession(
    private val overlay: OverlayController,
    private val buildData: (HudProfile) -> HudRenderData,
    private val selectSamplingProfile: (HudProfile) -> Unit,
    private val updateIsland: (HudRenderData) -> Unit,
) {
    private var config: AppConfig? = null
    var displayedProfileId: String = ""
        private set

    fun configure(value: AppConfig) { config = value }

    fun render(profile: HudProfile, animate: Boolean) {
        val settings = config ?: return
        if (!settings.hudEnabled) return
        val firstOutput = displayedProfileId.isEmpty()
        val data = buildData(profile)
        selectSamplingProfile(profile)
        displayedProfileId = profile.id
        if (DisplayMode.fromWire(settings.android.displayMode) == DisplayMode.ISLAND) {
            updateIsland(data)
        } else if (overlay.isShowing() || animate || firstOutput || settings.android.alwaysVisible) {
            // Persistent output must recover an unattached window; a hidden transient stays hidden
            // until a real reveal event. The first output is always a reveal, even after early samples.
            overlay.update(data, animate || firstOutput)
        }
    }
}
