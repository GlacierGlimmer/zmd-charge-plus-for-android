package com.glacierglimmer.endfieldchargeplus.core.json

import com.glacierglimmer.endfieldchargeplus.core.model.AndroidSettings
import com.glacierglimmer.endfieldchargeplus.core.model.AnimationMode
import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.core.model.AppLanguage
import com.glacierglimmer.endfieldchargeplus.core.model.BuiltInProfiles
import com.glacierglimmer.endfieldchargeplus.core.model.CustomHttpSource
import com.glacierglimmer.endfieldchargeplus.core.model.CustomHudSettings
import com.glacierglimmer.endfieldchargeplus.core.model.DisplayMode
import com.glacierglimmer.endfieldchargeplus.core.model.HudColorRule
import com.glacierglimmer.endfieldchargeplus.core.model.HudPosition
import com.glacierglimmer.endfieldchargeplus.core.model.HudPositionMode
import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile
import com.glacierglimmer.endfieldchargeplus.core.model.IslandProviderKind
import com.glacierglimmer.endfieldchargeplus.core.model.NetworkDisplayUnit
import com.glacierglimmer.endfieldchargeplus.core.model.NetworkPercentMode
import com.glacierglimmer.endfieldchargeplus.core.model.ProbeProtocol
import java.util.Locale

/**
 * Cross-platform configuration normalizer.
 *
 * A faithful port of `HudSettingsNormalizer.cs` from the Windows edition, plus the Android-only
 * fields. Norm rules, in the order the desktop applies them:
 *
 *  1. every built-in scheme is re-materialized from [BuiltInProfiles] (content is dictated by code);
 *  2. a built-in is matched by `BuiltInKey` first, then by the legacy `Category` + `Name` pair;
 *  3. built-ins are never duplicated and never overwrite a user scheme;
 *  4. user schemes stay editable, stale `IsBuiltIn` rows are dropped;
 *  5. the carousel queue is identity based: unknown ids are dropped, duplicates removed, an
 *     explicit empty queue stays empty and a `null` queue (legacy file) is migrated **once** to the
 *     default queue (系统 schemes + `time.day-progress`);
 *  6. `ActiveProfileId` is repaired to the memory scheme;
 *  7. every numeric range ECP enforces is clamped, colours are validated, `AnimationMode` is kept
 *     within `Full`/`Simple`, `TimeTarget` and `DeepSeekPeakWindows` are sanitized;
 *  8. `SchemaVersion` is migrated forward to [AppConfig.CURRENT_SCHEMA_VERSION].
 *
 * Android difference from the desktop: a built-in keeps the **deterministic** id from
 * [BuiltInProfiles] instead of the id stored in the file. References to the old id (active scheme,
 * carousel queue) are remapped, so an imported desktop configuration keeps its meaning.
 *
 * [note] receives a human-readable description of everything that was repaired. The repository
 * forwards those to the diagnostics log, so a normalization never happens silently.
 */
object ConfigNormalizer {

    const val DEFAULT_ACCENT_COLOR = "#C6CA4C"
    const val DEFAULT_TIME_TARGET = "10:00:00"
    const val DEFAULT_PING_TARGET = "1.1.1.1"
    const val DEFAULT_PROBE_PORT = 443
    const val DEFAULT_DEEPSEEK_PEAK_WINDOWS = "09:00-12:00;14:00-18:00"
    const val DEFAULT_DEEPSEEK_BASE_URL = "https://api.deepseek.com"
    const val DEFAULT_NETWORK_REFERENCE_VALUE = 100.0
    const val DEFAULT_NETWORK_REFERENCE_UNIT = "MB/s"
    const val CUSTOM_CATEGORY = "自定义"
    const val CUSTOM_NAME = "自定义 HUD"

    // Android-only ranges.
    private const val MIN_HUD_SCALE = 0.4
    private const val MAX_HUD_SCALE = 2.0
    private const val MIN_AUTO_HIDE_SECONDS = 0.5
    private const val MAX_AUTO_HIDE_SECONDS = 120.0
    private const val MIN_REFRESH_MS = 100L
    private const val MAX_REFRESH_MS = 3_600_000L
    private const val MIN_PROBE_INTERVAL_SECONDS = 1
    private const val MAX_PROBE_INTERVAL_SECONDS = 3_600
    private const val MIN_PROBE_TIMEOUT_MS = 100
    private const val MAX_PROBE_TIMEOUT_MS = 30_000
    private const val MIN_PROBE_SAMPLE_WINDOW = 1
    private const val MAX_PROBE_SAMPLE_WINDOW = 300
    private const val MIN_DEEPSEEK_REFRESH_SECONDS = 30
    private const val MAX_DEEPSEEK_REFRESH_SECONDS = 86_400
    private const val MIN_HTTP_REFRESH_SECONDS = 5
    private const val MAX_HTTP_REFRESH_SECONDS = 86_400
    private const val MAX_ABSOLUTE_COORDINATE = 100_000
    private const val MAX_ABSOLUTE_PROGRESS = 1.0e9

    private val VALID_OPERATORS = setOf(">=", "<=", "==", "!=", ">", "<")
    private val COLOR_PATTERN = Regex("^#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
    private val PEAK_WINDOW_PATTERN = Regex("^([01]\\d|2[0-3]):([0-5]\\d)-([01]\\d|2[0-3]):([0-5]\\d)$")

    // ------------------------------------------------------------------------------------------
    // AppConfig
    // ------------------------------------------------------------------------------------------

    fun normalize(config: AppConfig, note: (String) -> Unit = {}): AppConfig {
        if (config.schemaVersion != AppConfig.CURRENT_SCHEMA_VERSION) {
            note(
                "SchemaVersion ${config.schemaVersion} migrated to ${AppConfig.CURRENT_SCHEMA_VERSION}" +
                    if (config.schemaVersion > AppConfig.CURRENT_SCHEMA_VERSION) " (file came from a newer build)" else "",
            )
        }
        return config.copy(
            schemaVersion = AppConfig.CURRENT_SCHEMA_VERSION,
            uiLanguage = normalizeLanguage(config.uiLanguage, note),
            globalScale = clampDouble(config.globalScale, 0.4, 1.4, AppConfig.DEFAULT_GLOBAL_SCALE),
            displayDurationSeconds = clampDouble(
                config.displayDurationSeconds, 3.0, 10.0, AppConfig.DEFAULT_DISPLAY_DURATION_SECONDS,
            ),
            bounceStrength = clampDouble(config.bounceStrength, 0.0, 0.5, AppConfig.DEFAULT_BOUNCE_STRENGTH),
            rippleIntensity = clampDouble(config.rippleIntensity, 0.0, 2.0, AppConfig.DEFAULT_RIPPLE_INTENSITY),
            rippleSpread = clampDouble(config.rippleSpread, 0.5, 1.5, AppConfig.DEFAULT_RIPPLE_SPREAD),
            hudOpacity = clampDouble(config.hudOpacity, 0.10, 1.0, AppConfig.DEFAULT_HUD_OPACITY),
            positionMode = tolerantWire(
                config.positionMode,
                HudPositionMode.entries.map { it.wire },
                HudPositionMode.PRESET.wire,
            ),
            hudPosition = hudPositionWire(config.hudPosition),
            hudOffsetX = clampInt(config.hudOffsetX),
            hudOffsetY = clampInt(config.hudOffsetY),
            hudCustomX = clampInt(config.hudCustomX),
            hudCustomY = clampInt(config.hudCustomY),
            customHud = normalizeSettings(config.customHud, note),
            android = normalizeAndroid(config.android, note),
        )
    }

    private fun normalizeLanguage(raw: String?, note: (String) -> Unit): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return AppLanguage.AUTO.wire
        EcpJson.matchWord(value, listOf("Auto", "zh-CN", "en-US"))?.let { return AppLanguage.fromWire(it).wire }
        val mapped = when (EcpJson.normalizeWord(value)) {
            "zh", "zhcn", "zhhans", "zhhant", "chinese", "simplifiedchinese", "中文", "简体中文" -> "zh-CN"
            "en", "enus", "english", "英文" -> "en-US"
            "system", "followsystem", "跟随系统" -> "Auto"
            else -> null
        }
        if (mapped == null) {
            note("unknown UiLanguage '$value' -> ${AppLanguage.AUTO.wire}")
            return AppLanguage.AUTO.wire
        }
        return AppLanguage.fromWire(mapped).wire
    }

    /**
     * Windows spelling (`TopCenter`) of a HUD anchor.
     *
     * Implemented here because the desktop spelling is the cross-platform contract used on export.
     * `HudPosition.fromWire` is the tolerant reader for the opposite direction.
     */
    fun hudPositionWire(value: String?): String {
        val position = HudPosition.fromWire(value)
        return position.name.split("_").joinToString("") { part ->
            part.lowercase(Locale.US).replaceFirstChar { it.uppercaseChar() }
        }
    }

    // ------------------------------------------------------------------------------------------
    // CustomHudSettings (profiles + carousel + HTTP sources)
    // ------------------------------------------------------------------------------------------

    fun normalizeSettings(settings: CustomHudSettings, note: (String) -> Unit = {}): CustomHudSettings {
        val builtIns = BuiltInProfiles.all()
        val normalized = ArrayList<HudProfile>(builtIns.size + settings.profiles.size)
        val consumed = HashSet<String>()
        val idRemap = HashMap<String, String>()

        builtIns.forEach { builtIn ->
            val existing = settings.profiles.firstOrNull {
                it.builtInKey.isNotBlank() && it.builtInKey.trim().equals(builtIn.builtInKey, ignoreCase = true)
            } ?: settings.profiles.firstOrNull {
                it.builtInKey.isBlank() && !consumed.contains(idKey(it.id)) && isLegacyMatch(it, builtIn)
            }

            val canonicalId = BuiltInProfiles.idOf(builtIn.builtInKey)
            if (existing != null) {
                consumed.add(idKey(existing.id))
                if (existing.id.isNotBlank() && !existing.id.equals(canonicalId, ignoreCase = true)) {
                    idRemap[idKey(existing.id)] = canonicalId
                }
            }

            val dayProgress = builtIn.builtInKey.equals(BuiltInProfiles.TIME_DAY_PROGRESS, ignoreCase = true)
            val gpu = builtIn.builtInKey.equals(BuiltInProfiles.GPU, ignoreCase = true)
            val canonical = builtIn.copy(
                id = canonicalId,
                isBuiltIn = true,
                builtInKey = builtIn.builtInKey,
                timeTargetEnabled = if (dayProgress) {
                    existing?.timeTargetEnabled ?: builtIn.timeTargetEnabled
                } else {
                    builtIn.timeTargetEnabled
                },
                timeTarget = if (dayProgress) {
                    sanitizeTimeTarget(existing?.timeTarget ?: builtIn.timeTarget)
                } else {
                    builtIn.timeTarget
                },
                gpuAdapterId = if (gpu) {
                    existing?.gpuAdapterId?.trim().orEmpty().ifBlank { builtIn.gpuAdapterId }
                } else {
                    builtIn.gpuAdapterId
                },
            )
            normalized.add(sanitizeProfile(canonical, note))
        }

        val seenIds = HashSet<String>()
        normalized.forEach { seenIds.add(idKey(it.id)) }

        settings.profiles.forEach { profile ->
            val key = idKey(profile.id)
            if (consumed.contains(key)) return@forEach
            if (profile.isBuiltIn || profile.builtInKey.isNotBlank()) {
                note("dropped built-in scheme '${profile.name}' (key '${profile.builtInKey}' does not exist)")
                return@forEach
            }
            if (!seenIds.add(key)) {
                note("dropped duplicated scheme id '${profile.id}'")
                return@forEach
            }
            normalized.add(normalizeCustomProfile(profile, note))
        }

        val activeId = resolveActiveProfileId(settings.activeProfileId, normalized, idRemap, note)

        val cycleIds = resolveCycleProfileIds(settings, normalized, idRemap, note)

        val cycleAnimationMode = if (settings.cycleAnimationMode.trim().equals(AnimationMode.SIMPLE.wire, true)) {
            AnimationMode.SIMPLE.wire
        } else {
            AnimationMode.FULL.wire
        }

        return settings.copy(
            cycleSeconds = clampInt(settings.cycleSeconds, 3, 3_600),
            cycleProfileIds = cycleIds,
            cycleAnimationMode = cycleAnimationMode,
            activeProfileId = activeId,
            // Platform-opaque blob: never inspected, rewritten, logged or exported in clear text.
            deepSeekApiKeyProtected = settings.deepSeekApiKeyProtected,
            deepSeekPeakWindows = sanitizePeakWindows(settings.deepSeekPeakWindows, note),
            profiles = normalized,
            httpSources = settings.httpSources.map { normalizeHttpSource(it) },
        )
    }

    private fun resolveActiveProfileId(
        rawActiveId: String,
        profiles: List<HudProfile>,
        idRemap: Map<String, String>,
        note: (String) -> Unit,
    ): String {
        val remapped = idRemap[idKey(rawActiveId)] ?: rawActiveId
        profiles.firstOrNull { it.id.equals(remapped, ignoreCase = true) }?.let { return it.id }

        val fallback = profiles.firstOrNull { it.builtInKey.equals(BuiltInProfiles.MEMORY, ignoreCase = true) }
            ?: profiles.firstOrNull()
        if (rawActiveId.isNotBlank()) {
            note("ActiveProfileId '$rawActiveId' no longer exists -> '${fallback?.name ?: "<none>"}'")
        }
        return fallback?.id.orEmpty()
    }

    private fun resolveCycleProfileIds(
        settings: CustomHudSettings,
        profiles: List<HudProfile>,
        idRemap: Map<String, String>,
        note: (String) -> Unit,
    ): List<String> {
        val explicit = settings.cycleProfileIds
        if (explicit == null) {
            val queue = profiles
                .filter { it.category.equals("系统", ignoreCase = true) || it.builtInKey.equals(BuiltInProfiles.TIME_DAY_PROGRESS, true) }
                .map { it.id }
            note("legacy CycleProfileIds was null; migrated to the default carousel queue (${queue.size} entries)")
            return queue
        }
        val dropped = explicit.size
        val resolved = explicit
            .mapNotNull { raw -> raw?.trim()?.takeIf { it.isNotEmpty() } }
            .mapNotNull { raw -> idRemap[idKey(raw)] ?: raw }
            .mapNotNull { candidate -> profiles.firstOrNull { it.id.equals(candidate, ignoreCase = true) }?.id }
            .distinctBy { idKey(it) }
        if (resolved.size != dropped) {
            note("carousel queue cleaned: ${dropped - resolved.size} stale/duplicate entr(ies) removed")
        }
        return resolved
    }

    private fun normalizeCustomProfile(profile: HudProfile, note: (String) -> Unit): HudProfile {
        val category = profile.category.trim().ifBlank { CUSTOM_CATEGORY }
        val name = profile.name.trim().ifBlank { CUSTOM_NAME }
        // Legacy settings may carry a two-level custom category/name; fold those once so users do
        // not lose the meaning of older configurations.
        val mergedName = if (category.equals(CUSTOM_CATEGORY, ignoreCase = true)) name else "$category - $name"
        return sanitizeProfile(
            profile.copy(
                isBuiltIn = false,
                builtInKey = "",
                category = CUSTOM_CATEGORY,
                name = mergedName,
            ),
            note,
        )
    }

    /** Rules shared by built-in and user schemes: enums, colours, ranges, probe and time fields. */
    fun sanitizeProfile(profile: HudProfile, note: (String) -> Unit = {}): HudProfile {
        val accent = normalizeColor(profile.accentColor)
        if (accent == null) {
            note("invalid accent colour '${profile.accentColor}' -> $DEFAULT_ACCENT_COLOR")
        }
        val rules = profile.colorRules.mapNotNull { rule -> normalizeColorRule(rule, note) }
        val (progressMin, progressMax) = normalizeProgressRange(profile, note)

        return profile.copy(
            animationMode = tolerantWire(
                profile.animationMode,
                AnimationMode.entries.map { it.wire },
                AnimationMode.FULL.wire,
            ),
            accentColor = accent ?: DEFAULT_ACCENT_COLOR,
            colorRules = rules,
            progressVariable = profile.progressVariable.trim(),
            progressMin = progressMin,
            progressMax = progressMax,
            timeTarget = sanitizeTimeTarget(profile.timeTarget),
            pingTarget = profile.pingTarget.trim().ifBlank { DEFAULT_PING_TARGET },
            probeProtocol = tolerantWire(
                profile.probeProtocol,
                ProbeProtocol.entries.map { it.wire },
                ProbeProtocol.ICMP.wire,
            ),
            probePort = normalizeProbePort(profile.probePort),
            gpuAdapterId = profile.gpuAdapterId.trim(),
            networkDisplayUnit = tolerantWire(
                profile.networkDisplayUnit,
                NetworkDisplayUnit.entries.map { it.wire },
                NetworkDisplayUnit.AUTO_BYTES.wire,
            ),
            networkPercentMode = tolerantWire(
                profile.networkPercentMode,
                NetworkPercentMode.entries.map { it.wire },
                NetworkPercentMode.TOTAL.wire,
            ),
            networkReferenceValue = normalizeReferenceValue(profile.networkReferenceValue),
            networkReferenceUnit = profile.networkReferenceUnit.trim().ifBlank { DEFAULT_NETWORK_REFERENCE_UNIT },
        )
    }

    private fun normalizeColorRule(rule: HudColorRule, note: (String) -> Unit): HudColorRule? {
        val variable = rule.variable.trim()
        if (variable.isEmpty()) {
            note("dropped colour rule without a variable")
            return null
        }
        val color = normalizeColor(rule.color)
        if (color == null) {
            note("dropped colour rule for '$variable' with invalid colour '${rule.color}'")
            return null
        }
        val operator = rule.operator.trim()
        val safeOperator = if (operator in VALID_OPERATORS) operator else ">=".also {
            note("colour rule for '$variable' used unknown operator '$operator' -> '>='")
        }
        return rule.copy(
            variable = variable,
            operator = safeOperator,
            value = if (rule.value.isFinite()) rule.value else 0.0,
            color = color,
        )
    }

    private fun normalizeProgressRange(profile: HudProfile, note: (String) -> Unit): Pair<Double, Double> {
        val min = if (profile.progressMin.isFinite()) {
            profile.progressMin.coerceIn(-MAX_ABSOLUTE_PROGRESS, MAX_ABSOLUTE_PROGRESS)
        } else {
            0.0.also { note("scheme '${profile.name}' had a non-finite ProgressMin -> 0") }
        }
        val max = if (profile.progressMax.isFinite()) {
            profile.progressMax.coerceIn(-MAX_ABSOLUTE_PROGRESS, MAX_ABSOLUTE_PROGRESS)
        } else {
            100.0.also { note("scheme '${profile.name}' had a non-finite ProgressMax -> 100") }
        }
        if (max - min < 1e-9) {
            note("scheme '${profile.name}' had an empty progress range; reset to 0..100")
            return 0.0 to 100.0
        }
        if (min != profile.progressMin || max != profile.progressMax) {
            note("scheme '${profile.name}' progress range clamped to $min..$max")
        }
        return min to max
    }

    private fun normalizeReferenceValue(value: Double): Double =
        if (value.isFinite() && value > 0.0) value.coerceIn(0.001, MAX_ABSOLUTE_PROGRESS) else DEFAULT_NETWORK_REFERENCE_VALUE

    private fun normalizeProbePort(value: Int): Int = clampInt(if (value <= 0) DEFAULT_PROBE_PORT else value, 1, 65_535)

    private fun normalizeHttpSource(source: CustomHttpSource): CustomHttpSource = source.copy(
        name = source.name.trim().ifBlank { "custom" },
        url = source.url.trim(),
        refreshSeconds = clampInt(source.refreshSeconds, MIN_HTTP_REFRESH_SECONDS, MAX_HTTP_REFRESH_SECONDS),
        headers = source.headers.entries
            .filter { it.key.isNotBlank() }
            .associate { it.key.trim() to it.value },
        fields = source.fields.map {
            it.copy(
                variable = it.variable.trim().ifBlank { "value" },
                jsonPath = it.jsonPath.trim(),
            )
        },
    )

    // ------------------------------------------------------------------------------------------
    // Android-only settings
    // ------------------------------------------------------------------------------------------

    fun normalizeAndroid(settings: AndroidSettings, note: (String) -> Unit = {}): AndroidSettings {
        val fast = clampLong(settings.fastRefreshMs, MIN_REFRESH_MS, MAX_REFRESH_MS)
        val normal = maxOf(fast, clampLong(settings.normalRefreshMs, MIN_REFRESH_MS, MAX_REFRESH_MS))
        val slow = maxOf(normal, clampLong(settings.slowRefreshMs, MIN_REFRESH_MS, MAX_REFRESH_MS))
        val idle = maxOf(slow, clampLong(settings.idleRefreshMs, MIN_REFRESH_MS, MAX_REFRESH_MS))
        if (fast != settings.fastRefreshMs || normal != settings.normalRefreshMs ||
            slow != settings.slowRefreshMs || idle != settings.idleRefreshMs
        ) {
            note("sampling intervals normalized to fast=$fast normal=$normal slow=$slow idle=$idle ms")
        }

        val baseUrl = normalizeBaseUrl(settings.deepSeekBaseUrl, note)

        return settings.copy(
            displayMode = tolerantWire(
                settings.displayMode,
                DisplayMode.entries.map { it.wire },
                DisplayMode.OVERLAY.wire,
            ),
            islandProvider = tolerantWire(
                settings.islandProvider,
                IslandProviderKind.entries.map { it.wire },
                IslandProviderKind.AUTO.wire,
            ),
            overlayXPortrait = clampInt(settings.overlayXPortrait),
            overlayYPortrait = clampInt(settings.overlayYPortrait),
            overlayXLandscape = clampInt(settings.overlayXLandscape),
            overlayYLandscape = clampInt(settings.overlayYLandscape),
            hudScale = clampDouble(settings.hudScale, MIN_HUD_SCALE, MAX_HUD_SCALE, 1.0),
            autoHideSeconds = clampDouble(
                settings.autoHideSeconds, MIN_AUTO_HIDE_SECONDS, MAX_AUTO_HIDE_SECONDS, 6.0,
            ),
            fastRefreshMs = fast,
            normalRefreshMs = normal,
            slowRefreshMs = slow,
            idleRefreshMs = idle,
            screenOffRefreshMs = clampLong(settings.screenOffRefreshMs, MIN_REFRESH_MS, MAX_REFRESH_MS),
            probeIntervalSeconds = clampInt(
                settings.probeIntervalSeconds, MIN_PROBE_INTERVAL_SECONDS, MAX_PROBE_INTERVAL_SECONDS,
            ),
            probeTimeoutMs = clampInt(settings.probeTimeoutMs, MIN_PROBE_TIMEOUT_MS, MAX_PROBE_TIMEOUT_MS),
            probeSampleWindow = clampInt(
                settings.probeSampleWindow, MIN_PROBE_SAMPLE_WINDOW, MAX_PROBE_SAMPLE_WINDOW,
            ),
            deepSeekRefreshSeconds = clampInt(
                settings.deepSeekRefreshSeconds, MIN_DEEPSEEK_REFRESH_SECONDS, MAX_DEEPSEEK_REFRESH_SECONDS,
            ),
            deepSeekBaseUrl = baseUrl,
            capabilityScanAt = if (settings.capabilityScanAt < 0L) 0L else settings.capabilityScanAt,
        )
    }

    private fun normalizeBaseUrl(raw: String?, note: (String) -> Unit): String {
        val value = raw?.trim().orEmpty().trimEnd('/')
        return if (value.startsWith("http://") || value.startsWith("https://")) {
            value
        } else {
            note("invalid DeepSeek base URL '${raw.orEmpty()}' -> $DEFAULT_DEEPSEEK_BASE_URL")
            DEFAULT_DEEPSEEK_BASE_URL
        }
    }

    // ------------------------------------------------------------------------------------------
    // small helpers
    // ------------------------------------------------------------------------------------------

    /** Sanitizes `HH:mm:ss` exactly like `HudSettingsNormalizer.NormalizeTargetTime`. */
    fun sanitizeTimeTarget(raw: String?): String {
        val parts = raw?.trim()?.split(":") ?: return DEFAULT_TIME_TARGET
        if (parts.size !in 2..3) return DEFAULT_TIME_TARGET
        val hours = parts[0].trim().toIntOrNull() ?: return DEFAULT_TIME_TARGET
        val minutes = parts[1].trim().toIntOrNull() ?: return DEFAULT_TIME_TARGET
        val seconds = if (parts.size == 3) parts[2].trim().toIntOrNull() ?: return DEFAULT_TIME_TARGET else 0
        if (hours !in 0..23 || minutes !in 0..59 || seconds !in 0..59) return DEFAULT_TIME_TARGET
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    /** Sanitizes `HH:mm-HH:mm;...`, dropping unusable pairs; a blank result falls back to default. */
    fun sanitizePeakWindows(raw: String?, note: (String) -> Unit = {}): String {
        val entries = raw?.split(";")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        if (entries.isEmpty()) return DEFAULT_DEEPSEEK_PEAK_WINDOWS
        val valid = entries.filter { entry ->
            val match = PEAK_WINDOW_PATTERN.matchEntire(entry)
            if (match == null) {
                note("dropped invalid DeepSeek peak window '$entry'")
                false
            } else {
                val start = match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
                val end = match.groupValues[3].toInt() * 60 + match.groupValues[4].toInt()
                if (start >= end) {
                    note("dropped DeepSeek peak window '$entry' (end must be after start)")
                    false
                } else {
                    true
                }
            }
        }
        return if (valid.isEmpty()) DEFAULT_DEEPSEEK_PEAK_WINDOWS else valid.joinToString(";")
    }

    /** `#RRGGBB` (and the desktop-accepted `#RRGGBBAA`), upper-cased; `null` when invalid. */
    fun normalizeColor(raw: String?): String? =
        raw?.trim()?.takeIf { COLOR_PATTERN.matches(it) }?.uppercase(Locale.US)

    private fun isLegacyMatch(candidate: HudProfile, builtIn: HudProfile): Boolean =
        candidate.category.trim().equals(builtIn.category, ignoreCase = true) &&
            candidate.name.trim().equals(builtIn.name, ignoreCase = true)

    private fun idKey(id: String?): String = EcpJson.normalizeWord(id).orEmpty()

    /**
     * Tolerant enum reading: `"xiaomi_hyper_island"`, `"Xiaomi-Hyper-Island"` and
     * `"XiaomiHyperIsland"` all resolve to the same wire word; anything unrecognized falls back to
     * [fallback] instead of inventing a value.
     */
    private fun tolerantWire(raw: String?, allowed: List<String>, fallback: String): String =
        EcpJson.matchWord(raw, allowed) ?: fallback

    private fun clampInt(value: Int, min: Int, max: Int): Int = value.coerceIn(min, max)

    private fun clampInt(value: Int): Int = clampInt(value, -MAX_ABSOLUTE_COORDINATE, MAX_ABSOLUTE_COORDINATE)

    private fun clampLong(value: Long, min: Long, max: Long): Long = value.coerceIn(min, max)

    private fun clampDouble(value: Double, min: Double, max: Double, fallback: Double): Double =
        if (value.isFinite()) value.coerceIn(min, max) else fallback
}
