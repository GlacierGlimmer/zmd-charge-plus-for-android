package com.glacierglimmer.endfieldchargeplus.data

import com.glacierglimmer.endfieldchargeplus.core.json.ConfigCodec
import com.glacierglimmer.endfieldchargeplus.core.model.AndroidSettings
import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.core.model.CustomHttpSource
import com.glacierglimmer.endfieldchargeplus.core.model.CustomHudSettings
import com.glacierglimmer.endfieldchargeplus.core.model.HudColorRule
import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile
import com.glacierglimmer.endfieldchargeplus.core.model.HttpFieldMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigCodecTest {

    @Test
    fun `encode and decode round trip keeps every field`() {
        val config = AppConfig(
            schemaVersion = AppConfig.CURRENT_SCHEMA_VERSION,
            hudEnabled = false,
            uiLanguage = "zh-CN",
            globalScale = 1.25,
            displayDurationSeconds = 7.5,
            bounceStrength = 0.31,
            rippleIntensity = 1.75,
            rippleSpread = 1.2,
            hudOpacity = 0.5,
            positionMode = "CustomCoordinates",
            hudPosition = "BottomRight",
            hudOffsetX = 12,
            hudOffsetY = -13,
            hudCustomX = 44,
            hudCustomY = 55,
            customHud = CustomHudSettings(
                autoCycle = true,
                cycleSeconds = 42,
                cycleProfileIds = listOf("profile-a", "profile-b"),
                cycleAnimationMode = "Full",
                activeProfileId = "profile-a",
                deepSeekApiKeyProtected = "android-keystore-v1:AAAA",
                deepSeekPeakWindows = "08:00-09:00",
                profiles = listOf(
                    HudProfile(
                        id = "profile-a",
                        isBuiltIn = false,
                        category = "自定义",
                        name = "A",
                        animationMode = "Simple",
                        primaryTemplate = "{cpu.usage|0}",
                        colorRules = listOf(
                            HudColorRule(variable = "cpu.usage", operator = ">=", value = 90.0, color = "#FF0000"),
                        ),
                        probePort = 8443,
                    ),
                    HudProfile(id = "profile-b", name = "B"),
                ),
                httpSources = listOf(
                    CustomHttpSource(
                        name = "src",
                        url = "https://example.invalid/data",
                        refreshSeconds = 30,
                        headers = mapOf("Authorization" to "Bearer x"),
                        fields = listOf(HttpFieldMapping(variable = "value", jsonPath = "a.b[0]")),
                    ),
                ),
            ),
            android = AndroidSettings(
                displayMode = "Island",
                fastRefreshMs = 250,
                probeEnabled = true,
                islandProvider = "AndroidSystem",
            ),
        )

        val encoded = ConfigCodec.encode(config)
        val decoded = ConfigCodec.decode(encoded)

        assertTrue(decoded.isSuccess)
        assertEquals(config, decoded.getOrThrow())
    }

    @Test
    fun `decode accepts a PascalCase desktop export with numeric enums and null queue`() {
        val decoded = ConfigCodec.decode(DESKTOP_SAMPLE)

        assertTrue(decoded.isSuccess)
        val config = decoded.getOrThrow()
        assertEquals("zh-CN", config.uiLanguage)
        assertEquals(0.9, config.globalScale, 1e-9)
        // Numeric C# enums: PositionMode 0 -> Preset, HudPosition 1 -> TopCenter.
        assertEquals("Preset", config.positionMode)
        assertEquals("TopCenter", config.hudPosition)
        // Android-only defaults survive a desktop file.
        assertEquals("Overlay", config.android.displayMode)
        assertTrue(config.android.clickThrough)
        // A legacy null queue is meaningful and must stay null until normalization.
        assertNull(config.customHud.cycleProfileIds)
        assertTrue(config.customHud.autoCycle)
        assertEquals(15, config.customHud.cycleSeconds)
        assertEquals(1, config.customHud.profiles.size)
        assertEquals("system.memory", config.customHud.profiles.first().builtInKey)
    }

    @Test
    fun `decode accepts camelCase and snake_case spellings`() {
        val json = """
            {
              "hud_enabled": "false",
              "global_scale": "0.75",
              "customHud": {
                "auto_cycle": 1,
                "cycle_profile_ids": ["a"],
                "profiles": [
                  { "id": "a", "name": "A", "animation_mode": "simple", "probe_port": "8080" }
                ]
              }
            }
        """.trimIndent()

        val config = ConfigCodec.decode(json).getOrThrow()

        assertEquals(false, config.hudEnabled)
        assertEquals(0.75, config.globalScale, 1e-9)
        assertTrue(config.customHud.autoCycle)
        assertEquals(listOf("a"), config.customHud.cycleProfileIds)
        assertEquals("Simple", config.customHud.profiles.first().animationMode)
        assertEquals(8080, config.customHud.profiles.first().probePort)
    }

    @Test
    fun `decode never throws on malformed input`() {
        val malformed = listOf(
            "",
            "   ",
            "{",
            "{\"HudEnabled\": tr",
            "not json at all",
            "[1,2,3]",
            "{\"GlobalScale\": }",
        )
        malformed.forEach { text ->
            val result = ConfigCodec.decode(text)
            assertTrue("expected failure for '$text'", result.isFailure)
            val message = result.exceptionOrNull()?.message
            assertNotNull("missing message for '$text'", message)
            assertTrue("blank message for '$text'", message!!.isNotBlank())
        }
    }

    @Test
    fun `decode tolerates comments and a byte order mark`() {
        val json = "\uFEFF// exported by the desktop edition\n{\n  \"GlobalScale\": 1.0\n}\n"
        val config = ConfigCodec.decode(json).getOrThrow()
        assertEquals(1.0, config.globalScale, 1e-9)
    }

    @Test
    fun `profiles only round trip`() {
        val config = AppConfig()
        val encoded = ConfigCodec.encodeProfilesOnly(config)
        val profiles = ConfigCodec.decodeProfilesOnly(encoded)

        assertTrue(profiles.isSuccess)
        assertEquals(config.customHud.profiles.size, profiles.getOrThrow().size)
        assertEquals(
            config.customHud.profiles.map { it.builtInKey },
            profiles.getOrThrow().map { it.builtInKey },
        )
    }

    @Test
    fun `profiles only accepts every realistic shape`() {
        val bare = """[ { "Id": "x", "Name": "X" } ]"""
        val wrapped = """{ "Profiles": [ { "Id": "x", "Name": "X" } ] }"""
        val desktopFull = """{ "CustomHud": { "Profiles": [ { "Id": "x", "Name": "X" } ] } }"""

        listOf(bare, wrapped, desktopFull).forEach { json ->
            val result = ConfigCodec.decodeProfilesOnly(json)
            assertTrue("expected success for $json", result.isSuccess)
            assertEquals("x", result.getOrThrow().single().id)
        }

        assertTrue(ConfigCodec.decodeProfilesOnly("""{ "AutoCycle": true }""").isFailure)
        assertTrue(ConfigCodec.decodeProfilesOnly("garbage").isFailure)
    }

    private companion object {
        val DESKTOP_SAMPLE = """
            {
              "HudEnabled": true,
              "StartWithWindows": false,
              "UiLanguage": "zh-CN",
              "GlobalScale": 0.9,
              "DisplayDurationSeconds": 6.0,
              "BounceStrength": 0.275,
              "RippleIntensity": 1.0,
              "RippleSpread": 1.0,
              "HudOpacity": 0.8,
              "AlwaysVisible": false,
              "PersistentLayer": 1,
              "PositionMode": 0,
              "HudPosition": 1,
              "HudOffsetX": 0,
              "HudOffsetY": 0,
              "HudCustomX": 0,
              "HudCustomY": 0,
              "MonitorIndex": -1,
              "CustomHud": {
                "AutoCycle": true,
                "CycleSeconds": 15,
                "CycleProfileIds": null,
                "CycleAnimationMode": "Simple",
                "ActiveProfileId": "0123456789abcdef0123456789abcdef",
                "DeepSeekApiKeyProtected": "",
                "DeepSeekPeakWindows": "09:00-12:00;14:00-18:00",
                "Profiles": [
                  {
                    "Id": "0123456789abcdef0123456789abcdef",
                    "IsBuiltIn": true,
                    "BuiltInKey": "system.memory",
                    "Category": "系统",
                    "Name": "内存",
                    "AnimationMode": "Full"
                  }
                ],
                "HttpSources": []
              }
            }
        """.trimIndent()
    }
}
