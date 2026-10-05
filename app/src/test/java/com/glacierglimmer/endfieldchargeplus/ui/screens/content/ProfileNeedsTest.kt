package com.glacierglimmer.endfieldchargeplus.ui.screens.content

import com.glacierglimmer.endfieldchargeplus.core.model.BuiltInProfiles
import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which profile-scoped option groups the Content page shows.
 *
 * This mirrors the desktop editor: the network options appear only for a scheme that reads
 * `network.*`, the time options only for `time.*`, the probe options only for `probe.*`/`ping.*`.
 */
class ProfileNeedsTest {

    @Test
    fun theNetworkBuiltInNeedsNetworkOptions() {
        val profile = HudProfile(builtInKey = BuiltInProfiles.NETWORK, primaryTemplate = "{memory.usage}")
        assertTrue(ProfileNeeds.network(profile))
        assertFalse(ProfileNeeds.time(profile))
        assertFalse(ProfileNeeds.probe(profile))
    }

    @Test
    fun aTemplateThatReadsNetworkVariablesNeedsNetworkOptions() {
        val profile = HudProfile(primaryTemplate = "{network.download_bps|speed}")
        assertTrue(ProfileNeeds.network(profile))
        assertFalse(ProfileNeeds.time(profile))
    }

    @Test
    fun theTimeBuiltInNeedsTimeOptions() {
        val profile = HudProfile(
            builtInKey = BuiltInProfiles.TIME_DAY_PROGRESS,
            rightTemplate = "{time.display.status_text}",
        )
        assertTrue(ProfileNeeds.time(profile))
        assertFalse(ProfileNeeds.network(profile))
    }

    @Test
    fun theProbeBuiltInNeedsProbeOptionsEvenWithoutProbeTokens() {
        val profile = HudProfile(builtInKey = BuiltInProfiles.NETWORK_PROBE, primaryTemplate = "{probe.latency_ms|0}ms")
        assertTrue(ProfileNeeds.probe(profile))
        assertFalse(ProfileNeeds.network(profile))
    }

    @Test
    fun legacyPingTokensAlsoSelectTheProbeOptions() {
        val profile = HudProfile(primaryTemplate = "{ping.latency_ms|0}ms")
        assertTrue(ProfileNeeds.probe(profile))
    }

    @Test
    fun aPlainSchemeNeedsNoContextOptions() {
        val profile = HudProfile(primaryTemplate = "{cpu.usage|0}")
        assertFalse(ProfileNeeds.network(profile))
        assertFalse(ProfileNeeds.time(profile))
        assertFalse(ProfileNeeds.probe(profile))
    }

    @Test
    fun theProgressVariableCountsToo() {
        val profile = HudProfile(primaryTemplate = "{cpu.usage|0}", progressVariable = "network.profile_percent")
        assertTrue(ProfileNeeds.network(profile))
    }
}
