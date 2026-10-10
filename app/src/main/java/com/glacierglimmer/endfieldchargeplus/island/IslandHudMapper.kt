package com.glacierglimmer.endfieldchargeplus.island

import com.glacierglimmer.endfieldchargeplus.core.model.HudRenderData
import kotlin.math.round

/**
 * The reduced content model an island backend can express.
 *
 * It is intentionally much smaller than the overlay HUD: every real platform (Android promoted
 * ongoing notifications) draws its own layout and only accepts a
 * handful of text slots plus progress. Keeping this model free of Android types makes the
 * degradation rules unit-testable and keeps the decision "what can this backend show" in one place.
 */
data class IslandContent(
    /** Main line; empty when the backend has no title slot. */
    val title: String = "",
    /** Secondary line; empty when the backend has no subtitle slot or the HUD has no such text. */
    val subtitle: String = "",
    /** Single compact trailing line (the "short critical text" of the live-update layouts). */
    val shortText: String = "",
    /** 0..100, already rounded for display. `0.0` when the backend cannot show progress. */
    val progressPercent: Double = 0.0,
    /** Icon token from the HUD profile; empty when the backend cannot show custom icons. */
    val iconKey: String = "",
    /** `#RRGGBB` accent colour requested by the profile, passed through untouched. */
    val accentColor: String = "",
) {
    /** True when nothing could be expressed at all; providers must not publish such a frame. */
    val isEmpty: Boolean
        get() = title.isBlank() && subtitle.isBlank() && shortText.isBlank()

    /** Best single-line body text for backends that only accept `contentText`. */
    val bodyText: String
        get() = subtitle.ifBlank { shortText }
}

/**
 * Maps [HudRenderData] onto [IslandContent] for a concrete backend.
 *
 * The rules are deliberately lossy-but-honest:
 *  * a slot the backend does not have is dropped, never faked;
 *  * text that lost its own slot is folded into the single compact line so the user still sees it;
 *  * progress is only reported when the backend claims progress support.
 */
object IslandHudMapper {

    /** A chip has one very short slot; the expanded notification retains all metric fragments. */
    fun mapLiveUpdate(data: HudRenderData): IslandContent {
        val mapped = map(data, AndroidLiveUpdateProvider.CAPABILITIES)
        val primary = data.primaryText.trim()
        val secondary = data.secondaryText.trim()
        val metric = when {
            primary.isEmpty() -> secondary
            secondary.isEmpty() -> primary
            secondary.startsWith("/") || secondary.startsWith("%") -> primary + secondary
            else -> "$primary $secondary"
        }
        val right = (data.rightText + data.rightSuffix).trim()
        val body = listOf(metric, right).filter { it.isNotBlank() }.distinct().joinToString(SHORT_SEPARATOR)
        val compact = listOf(right, metric, primary, mapped.title)
            .map { it.replace(Regex("\\s+"), "").trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("/") && it.codePointCount(0, it.length) <= 7 }
            .orEmpty()
        return mapped.copy(subtitle = body, shortText = compact)
    }

    /** Separator used when several HUD fragments share one compact line. */
    const val SHORT_SEPARATOR: String = " · "

    /**
     * Degrades [data] to what [capabilities] can actually render.
     *
     * @param data the evaluated HUD frame.
     * @param capabilities the honest capability description of the target backend.
     */
    fun map(data: HudRenderData, capabilities: IslandCapabilities): IslandContent {
        val titleSource = data.title.ifBlank { data.tagline }
        val right = (data.rightText + data.rightSuffix).trim()

        val title = if (capabilities.supportsTitle) titleSource else ""
        val subtitle = if (capabilities.supportsSubtitle) data.primaryText else ""

        val folded = buildList {
            if (!capabilities.supportsTitle) add(titleSource)
            if (!capabilities.supportsSubtitle) add(data.primaryText)
            if (!capabilities.supportsLeftRightSplit) add(data.secondaryText)
            add(right)
        }.filter { it.isNotBlank() }.distinct()

        val progress = if (capabilities.supportsProgress && data.progress.isFinite()) {
            round(data.progress.coerceIn(0.0, 1.0) * 1000.0) / 10.0
        } else {
            0.0
        }

        return IslandContent(
            title = title,
            subtitle = subtitle,
            shortText = folded.joinToString(SHORT_SEPARATOR),
            progressPercent = progress,
            iconKey = if (capabilities.supportsIcons) data.leftIcon.ifBlank { data.rightIcon } else "",
            accentColor = data.accentColor.trim(),
        )
    }
}
