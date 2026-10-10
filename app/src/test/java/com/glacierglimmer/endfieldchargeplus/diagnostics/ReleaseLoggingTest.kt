package com.glacierglimmer.endfieldchargeplus.diagnostics

import com.glacierglimmer.endfieldchargeplus.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReleaseLoggingTest {
    @Test
    fun importedVerbosePreferenceCannotEnableDebugLoggingInRelease() {
        AppLog.clear()
        try {
            AppLog.setVerbose(true)
            assertEquals(BuildConfig.DEBUG, AppLog.isVerbose())
            AppLog.d("ReleaseCheck", "verbose-only entry")
            if (!BuildConfig.DEBUG) {
                assertFalse(AppLog.entries().any { it.level == AppLog.Level.DEBUG })
            }
        } finally {
            AppLog.setVerbose(false)
            AppLog.clear()
        }
    }
}
