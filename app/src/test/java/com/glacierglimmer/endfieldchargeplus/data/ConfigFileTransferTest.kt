package com.glacierglimmer.endfieldchargeplus.data

import com.glacierglimmer.endfieldchargeplus.core.model.AppConfig
import com.glacierglimmer.endfieldchargeplus.core.model.CustomHudSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigFileTransferTest {

    private fun transfer(repository: ConfigRepository = FakeConfigRepository()): ConfigFileTransfer =
        ConfigFileTransfer(StubContext(), repository)

    @Test
    fun `suggested file name is the documented cross-platform name`() {
        assertEquals("endfield-charge-plus-android-config.json", transfer().suggestedFileName())
    }

    @Test
    fun `a clear-text api key is blanked before it can be exported`() {
        val withKey = AppConfig(
            customHud = CustomHudSettings(deepSeekApiKeyProtected = "sk-1234567890abcdefghijklmn"),
        )

        val sanitized = transfer().sanitizeSecrets(withKey)

        assertEquals("", sanitized.customHud.deepSeekApiKeyProtected)
    }

    @Test
    fun `protected blobs survive sanitisation untouched`() {
        val blobs = listOf(
            "android-keystore-v1:AAAA/BBBB+CCCC=",
            "linux-aesgcm-v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "macos-keychain-v1:0123456789abcdef",
            "AQAAANCMnd8BFdERjHoAwE/Cl+sBAAAA1234567890abcdef==",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "",
        )

        val transfer = transfer()
        blobs.forEach { blob ->
            val config = AppConfig(customHud = CustomHudSettings(deepSeekApiKeyProtected = blob))
            assertEquals(blob, transfer.sanitizeSecrets(config).customHud.deepSeekApiKeyProtected)
        }
    }

    @Test
    fun `clear secret detection distinguishes raw tokens from protected blobs`() {
        val transfer = transfer()

        assertTrue(transfer.looksLikeClearSecret("sk-abcdefghijklmnopqrstuvwx"))
        assertTrue(transfer.looksLikeClearSecret("abcdefghijklmnopqrstuvwxyz012345"))
        assertFalse(transfer.looksLikeClearSecret("android-keystore-v1:whatever"))
        assertFalse(transfer.looksLikeClearSecret("AQAAANCMnd8BFdERjHoAwE/Cl+sBAAAA"))
        assertFalse(transfer.looksLikeClearSecret(""))
    }

    @Test
    fun `an oversized file constant matches the documented four megabyte limit`() {
        assertEquals(4L * 1024L * 1024L, ConfigFileTransfer.MAX_FILE_BYTES)
    }
}
