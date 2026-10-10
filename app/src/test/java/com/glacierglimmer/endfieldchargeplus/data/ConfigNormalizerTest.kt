package com.glacierglimmer.endfieldchargeplus.data

import com.glacierglimmer.endfieldchargeplus.core.json.ConfigNormalizer
import com.glacierglimmer.endfieldchargeplus.core.model.AndroidSettings
import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.core.model.BuiltInProfiles
import com.glacierglimmer.endfieldchargeplus.core.model.CustomHudSettings
import com.glacierglimmer.endfieldchargeplus.core.model.HudColorRule
import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigNormalizerTest {

    private val memoryId = BuiltInProfiles.idOf(BuiltInProfiles.MEMORY)

    @Test fun `hidden legacy backend choices migrate to the sole native backend without enabling HUD`() {
        for (old in listOf("Auto", "None", "XiaomiHyperIsland", "AndroidSystem")) {
            val normalized = ConfigNormalizer.normalize(AppConfig(hudEnabled = false,
                android = AndroidSettings(displayMode = "Island", islandProvider = old)))
            assertEquals("AndroidSystem", normalized.android.islandProvider)
            assertEquals("Island", normalized.android.displayMode)
            assertFalse(normalized.hudEnabled)
        }
    }

    @Test
    fun `built-ins are always re-created exactly once and custom schemes survive`() {
        val custom = HudProfile(id = "custom-1", name = "我的方案", category = "自定义")
        val config = AppConfig(
            customHud = CustomHudSettings(
                profiles = listOf(custom),
                activeProfileId = "custom-1",
                cycleProfileIds = listOf("custom-1"),
            ),
        )

        val normalized = ConfigNormalizer.normalize(config)
        val profiles = normalized.customHud.profiles

        assertEquals(BuiltInProfiles.all().size + 1, profiles.size)
        assertEquals(BuiltInProfiles.all().size, profiles.count { it.isBuiltIn })
        assertEquals(1, profiles.count { !it.isBuiltIn })
        // Deterministic ids, never duplicated.
        assertEquals(profiles.size, profiles.map { it.id }.distinct().size)
        BuiltInProfiles.all().forEach { builtIn ->
            assertEquals(
                BuiltInProfiles.idOf(builtIn.builtInKey),
                profiles.first { it.builtInKey == builtIn.builtInKey }.id,
            )
        }
        // The user's scheme is untouched.
        val kept = profiles.first { !it.isBuiltIn }
        assertEquals("我的方案", kept.name)
        assertEquals("custom-1", kept.id)
    }

    @Test
    fun `references to a replaced built-in id are remapped`() {
        val desktopBuiltIn = HudProfile(
            id = "0123456789abcdef0123456789abcdef",
            isBuiltIn = true,
            builtInKey = BuiltInProfiles.MEMORY,
            category = "系统",
            name = "内存",
        )
        val config = AppConfig(
            customHud = CustomHudSettings(
                profiles = listOf(desktopBuiltIn),
                activeProfileId = desktopBuiltIn.id,
                cycleProfileIds = listOf(desktopBuiltIn.id),
            ),
        )

        val normalized = ConfigNormalizer.normalize(config).customHud

        assertEquals(memoryId, normalized.activeProfileId)
        assertEquals(listOf(memoryId), normalized.cycleProfileIds)
        assertEquals(memoryId, normalized.profiles.first { it.builtInKey == BuiltInProfiles.MEMORY }.id)
    }

    @Test
    fun `a null carousel queue is migrated to the default queue once`() {
        val custom = HudProfile(id = "custom-1", name = "我的方案")
        val config = AppConfig(
            customHud = CustomHudSettings(
                profiles = listOf(custom),
                activeProfileId = memoryId,
                cycleProfileIds = null,
            ),
        )

        val normalized = ConfigNormalizer.normalize(config).customHud
        val queue = normalized.cycleProfileIds

        assertNotNull(queue)
        assertEquals(7, queue!!.size)
        assertFalse(queue.contains("custom-1"))
        assertTrue(queue.contains(BuiltInProfiles.idOf(BuiltInProfiles.TIME_DAY_PROGRESS)))
        assertTrue(queue.all { id -> normalized.profiles.any { it.id == id } })
    }

    @Test
    fun `an explicit empty carousel queue stays empty and stale entries are dropped`() {
        val empty = ConfigNormalizer.normalize(
            AppConfig(customHud = CustomHudSettings(cycleProfileIds = emptyList())),
        ).customHud
        assertEquals(emptyList<String>(), empty.cycleProfileIds)

        val stale = ConfigNormalizer.normalize(
            AppConfig(
                customHud = CustomHudSettings(
                    cycleProfileIds = listOf(memoryId, "ghost", memoryId, "  "),
                ),
            ),
        ).customHud
        assertEquals(listOf(memoryId), stale.cycleProfileIds)
    }

    @Test
    fun `an invalid active scheme is repaired to the memory scheme`() {
        val normalized = ConfigNormalizer.normalize(
            AppConfig(customHud = CustomHudSettings(activeProfileId = "does-not-exist")),
        ).customHud

        assertEquals(memoryId, normalized.activeProfileId)
        assertEquals(BuiltInProfiles.MEMORY, normalized.activeProfile()?.builtInKey)
    }

    @Test
    fun `numeric ranges are clamped to the values ECP enforces`() {
        val config = AppConfig(
            schemaVersion = 0,
            globalScale = 99.0,
            displayDurationSeconds = 0.1,
            bounceStrength = 9.0,
            rippleIntensity = -4.0,
            rippleSpread = 3.0,
            hudOpacity = 5.0,
            hudOffsetX = 10_000_000,
            customHud = CustomHudSettings(
                cycleSeconds = 99_999,
                profiles = listOf(
                    HudProfile(
                        id = "custom-1",
                        name = "P",
                        progressMin = 50.0,
                        progressMax = 50.0,
                        networkReferenceValue = -1.0,
                        probePort = 70000,
                    ),
                ),
            ),
            android = AndroidSettings(
                hudScale = 12.0,
                autoHideSeconds = -3.0,
                fastRefreshMs = 5,
                normalRefreshMs = 1,
                slowRefreshMs = 2,
                idleRefreshMs = 3,
                screenOffRefreshMs = 9_999_999,
                probeIntervalSeconds = 0,
                probeTimeoutMs = 1,
                probeSampleWindow = 10_000,
                deepSeekRefreshSeconds = 1,
            ),
        )

        val normalized = ConfigNormalizer.normalize(config)

        assertEquals(AppConfig.CURRENT_SCHEMA_VERSION, normalized.schemaVersion)
        assertEquals(1.4, normalized.globalScale, 1e-9)
        assertEquals(3.0, normalized.displayDurationSeconds, 1e-9)
        assertEquals(0.5, normalized.bounceStrength, 1e-9)
        assertEquals(0.0, normalized.rippleIntensity, 1e-9)
        assertEquals(1.5, normalized.rippleSpread, 1e-9)
        assertEquals(1.0, normalized.hudOpacity, 1e-9)
        assertEquals(100_000, normalized.hudOffsetX)
        assertEquals(3600, normalized.customHud.cycleSeconds)

        val profile = normalized.customHud.profiles.first { it.id == "custom-1" }
        assertEquals(0.0, profile.progressMin, 1e-9)
        assertEquals(100.0, profile.progressMax, 1e-9)
        assertEquals(ConfigNormalizer.DEFAULT_NETWORK_REFERENCE_VALUE, profile.networkReferenceValue, 1e-9)
        assertEquals(65_535, profile.probePort)

        val android = normalized.android
        assertEquals(2.0, android.hudScale, 1e-9)
        assertEquals(0.5, android.autoHideSeconds, 1e-9)
        assertTrue(android.fastRefreshMs >= 100)
        assertTrue(android.fastRefreshMs <= android.normalRefreshMs)
        assertTrue(android.normalRefreshMs <= android.slowRefreshMs)
        assertTrue(android.slowRefreshMs <= android.idleRefreshMs)
        assertEquals(3_600_000, android.screenOffRefreshMs)
        assertEquals(1, android.probeIntervalSeconds)
        assertEquals(100, android.probeTimeoutMs)
        assertEquals(300, android.probeSampleWindow)
        assertEquals(30, android.deepSeekRefreshSeconds)
    }

    @Test
    fun `colours are validated and animation modes stay within Full and Simple`() {
        val config = AppConfig(
            customHud = CustomHudSettings(
                profiles = listOf(
                    HudProfile(
                        id = "custom-1",
                        name = "P",
                        animationMode = "Fancy",
                        accentColor = "#GGGGGG",
                        colorRules = listOf(
                            HudColorRule(variable = "cpu.usage", operator = "~", value = 1.0, color = "red"),
                            HudColorRule(variable = "cpu.usage", operator = ">=", value = 2.0, color = "#ff00aa"),
                            HudColorRule(variable = "  ", operator = ">=", value = 3.0, color = "#00FF00"),
                        ),
                    ),
                    HudProfile(id = "custom-2", name = "Q", animationMode = "simple"),
                ),
            ),
        )

        val normalized = ConfigNormalizer.normalize(config).customHud
        val first = normalized.profiles.first { it.id == "custom-1" }
        val second = normalized.profiles.first { it.id == "custom-2" }

        assertEquals("Full", first.animationMode)
        assertEquals("Simple", second.animationMode)
        assertEquals(ConfigNormalizer.DEFAULT_ACCENT_COLOR, first.accentColor)
        assertEquals(1, first.colorRules.size)
        assertEquals(">=", first.colorRules.first().operator)
        assertEquals("#FF00AA", first.colorRules.first().color)
    }

    @Test
    fun `time targets and peak windows are sanitized`() {
        assertEquals("09:05:00", ConfigNormalizer.sanitizeTimeTarget("9:5"))
        assertEquals("10:00:00", ConfigNormalizer.sanitizeTimeTarget("25:00"))
        assertEquals("10:00:00", ConfigNormalizer.sanitizeTimeTarget("tomorrow"))
        assertEquals("23:59:59", ConfigNormalizer.sanitizeTimeTarget("23:59:59"))
        assertEquals("10:00:00", ConfigNormalizer.sanitizeTimeTarget(null))

        assertEquals("09:00-12:00", ConfigNormalizer.sanitizePeakWindows("09:00-12:00;garbage;18:00-17:00"))
        assertEquals(
            ConfigNormalizer.DEFAULT_DEEPSEEK_PEAK_WINDOWS,
            ConfigNormalizer.sanitizePeakWindows(""),
        )
        assertEquals("08:00-09:00;10:00-11:00", ConfigNormalizer.sanitizePeakWindows(" 08:00-09:00 ;10:00-11:00"))
    }

    @Test
    fun `the protected api key blob is never modified`() {
        val blob = "linux-aesgcm-v1:AAAA/BBBB+CCCC="
        val normalized = ConfigNormalizer.normalize(
            AppConfig(customHud = CustomHudSettings(deepSeekApiKeyProtected = blob)),
        )
        assertEquals(blob, normalized.customHud.deepSeekApiKeyProtected)
    }

    @Test
    fun `android fields are clamped and never dropped`() {
        val normalized = ConfigNormalizer.normalize(
            AppConfig(
                android = AndroidSettings(
                    displayMode = "island",
                    islandProvider = "xiaomi_hyper_island",
                    deepSeekBaseUrl = "api.deepseek.com",
                    capabilityScanAt = -5,
                    probeEnabled = true,
                    verboseLogging = true,
                    startOnBoot = true,
                ),
            ),
        ).android

        assertEquals("Island", normalized.displayMode)
        assertEquals("AndroidSystem", normalized.islandProvider)
        assertEquals(ConfigNormalizer.DEFAULT_DEEPSEEK_BASE_URL, normalized.deepSeekBaseUrl)
        assertEquals(0L, normalized.capabilityScanAt)
        assertTrue(normalized.probeEnabled)
        assertTrue(normalized.verboseLogging)
        assertTrue(normalized.startOnBoot)
        assertTrue(normalized.throttleWhenHidden)
        assertTrue(normalized.throttleWhenScreenOff)
    }

    @Test
    fun `stale built-in rows are dropped and legacy category names are folded`() {
        val staleBuiltIn = HudProfile(id = "stale", isBuiltIn = true, builtInKey = "system.removed")
        val legacyCustom = HudProfile(id = "legacy", name = "温度", category = "我的")
        val config = AppConfig(
            customHud = CustomHudSettings(
                profiles = listOf(staleBuiltIn, legacyCustom),
                activeProfileId = "legacy",
                cycleProfileIds = listOf("legacy"),
            ),
        )

        val normalized = ConfigNormalizer.normalize(config).customHud

        assertFalse(normalized.profiles.any { it.builtInKey == "system.removed" })
        val folded = normalized.profiles.first { it.id == "legacy" }
        assertEquals("我的 - 温度", folded.name)
        assertEquals(ConfigNormalizer.CUSTOM_CATEGORY, folded.category)
        assertFalse(folded.isBuiltIn)
    }
}
