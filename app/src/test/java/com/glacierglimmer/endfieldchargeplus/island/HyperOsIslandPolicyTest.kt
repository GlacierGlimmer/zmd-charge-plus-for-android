package com.glacierglimmer.endfieldchargeplus.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Xiaomi HyperIsland states this product must show: 不支持 on non-HyperOS devices, and 尚未授权 /
 * 需要申请小米超级岛权限 on HyperOS without vendor authorization.
 */
class HyperOsIslandPolicyTest {

    @Test
    fun `a non xiaomi device is unsupported by the platform`() {
        val availability = HyperOsIslandPolicy.evaluate(
            probe(manufacturer = "Google", brand = "google"),
            notificationsEnabled = true,
        )

        assertEquals(IslandAvailabilityState.UNSUPPORTED_BY_PLATFORM, availability.state)
        assertEquals("island_state_unsupported", availability.messageKey)
    }

    @Test
    fun `a xiaomi device without a hyperos marker is unsupported`() {
        val availability = HyperOsIslandPolicy.evaluate(
            probe(display = "V14.0.5.0.TKACNXM", incremental = "V14.0.5.0.TKACNXM", focusProtocolVersion = 0),
            notificationsEnabled = true,
        )

        assertEquals(IslandAvailabilityState.UNSUPPORTED_BY_PLATFORM, availability.state)
        assertTrue(availability.detail.contains("MIUI"))
    }

    @Test
    fun `hyperos with an os1 or os2 focus protocol cannot host an island`() {
        val availability = HyperOsIslandPolicy.evaluate(probe(focusProtocolVersion = 2), notificationsEnabled = true)

        assertEquals(IslandAvailabilityState.UNSUPPORTED_BY_PLATFORM, availability.state)
        assertTrue(availability.detail.contains("focus protocol 2"))
    }

    @Test
    fun `an unknown focus protocol never reports island support`() {
        val availability = HyperOsIslandPolicy.evaluate(
            probe(focusProtocolVersion = 0, bridgeIntegrated = false),
            notificationsEnabled = true,
        )

        assertEquals(IslandAvailabilityState.UNSUPPORTED_BY_PLATFORM, availability.state)
    }

    @Test
    fun `hyperos without an integrated vendor bridge reports 需要申请小米超级岛权限`() {
        val availability = HyperOsIslandPolicy.evaluate(probe(bridgeIntegrated = false), notificationsEnabled = true)

        assertEquals(IslandAvailabilityState.VENDOR_PERMISSION_REQUIRED, availability.state)
        assertEquals(XiaomiHyperIslandProvider.KEY_VENDOR_PERMISSION, availability.messageKey)
        assertTrue(availability.detail.isNotEmpty())
        assertFalse(availability.usable)
    }

    @Test
    fun `an integrated bridge with disabled notifications is unavailable`() {
        val availability = HyperOsIslandPolicy.evaluate(probe(bridgeIntegrated = true), notificationsEnabled = false)

        assertEquals(IslandAvailabilityState.UNAVAILABLE, availability.state)
        assertEquals(AndroidLiveUpdateProvider.KEY_NOTIFICATIONS_DISABLED, availability.messageKey)
    }

    @Test
    fun `an integrated bridge with the focus permission turned off is not authorized`() {
        val availability = HyperOsIslandPolicy.evaluate(
            probe(bridgeIntegrated = true, focusPermissionGranted = false),
            notificationsEnabled = true,
        )

        assertEquals(IslandAvailabilityState.NOT_AUTHORIZED, availability.state)
        assertEquals(AndroidLiveUpdateProvider.KEY_NOT_AUTHORIZED, availability.messageKey)
    }

    @Test
    fun `an authorized hyperos device is available`() {
        val availability = HyperOsIslandPolicy.evaluate(
            probe(bridgeIntegrated = true, focusPermissionGranted = true),
            notificationsEnabled = true,
        )

        assertEquals(IslandAvailabilityState.AVAILABLE, availability.state)
        assertEquals("island_state_available", availability.messageKey)
        assertTrue(availability.usable)
    }

    @Test
    fun `an unknown focus permission is not treated as granted or denied`() {
        val availability = HyperOsIslandPolicy.evaluate(
            probe(bridgeIntegrated = true, focusPermissionGranted = null),
            notificationsEnabled = true,
        )

        assertEquals(IslandAvailabilityState.UNAVAILABLE, availability.state)
        assertFalse(availability.usable)
    }

    @Test
    fun `protocol three works even when public build strings have no hyperos marker`() {
        val availability = HyperOsIslandPolicy.evaluate(probe(display = "REL", incremental = "12345"), true)
        assertTrue(availability.usable)
    }

    @Test
    fun `a working transport with no xiaomi app id still requires vendor integration`() {
        val availability = HyperOsIslandPolicy.evaluate(probe(appIdConfigured = false), true)
        assertEquals(IslandAvailabilityState.VENDOR_PERMISSION_REQUIRED, availability.state)
        assertFalse(availability.usable)
        assertTrue(availability.detail.contains("APP_ID"))
    }

    @Test
    fun `xiaomi device detection uses public manufacturer and brand values`() {
        assertTrue(HyperOsIslandPolicy.isXiaomiDevice("Xiaomi", "Xiaomi"))
        assertTrue(HyperOsIslandPolicy.isXiaomiDevice("Redmi", "Redmi"))
        assertTrue(HyperOsIslandPolicy.isXiaomiDevice("Xiaomi", "POCO"))
        assertFalse(HyperOsIslandPolicy.isXiaomiDevice("Google", "google"))
        assertFalse(HyperOsIslandPolicy.isXiaomiDevice("", ""))
    }

    @Test
    fun `hyperos marker detection covers the documented build strings`() {
        assertTrue(HyperOsIslandPolicy.hasHyperOsMarker("HyperOS", "V816.0.4.0.UNACNXM"))
        assertTrue(HyperOsIslandPolicy.hasHyperOsMarker("OS3.0.1.0.WNACNXM", "OS3.0.1.0.WNACNXM"))
        assertTrue(HyperOsIslandPolicy.hasHyperOsMarker("", "OS2.0.5.0.VNACNXM"))
        assertFalse(HyperOsIslandPolicy.hasHyperOsMarker("V14.0.5.0.TKACNXM", "V14.0.5.0.TKACNXM"))
        assertFalse(HyperOsIslandPolicy.hasHyperOsMarker("", ""))
    }

    private fun probe(
        manufacturer: String = "Xiaomi",
        brand: String = "Xiaomi",
        display: String = "OS3.0.1.0.WNACNXM",
        incremental: String = "OS3.0.1.0.WNACNXM",
        focusProtocolVersion: Int = HyperOsIslandPolicy.MIN_FOCUS_PROTOCOL_FOR_ISLAND,
        focusPermissionGranted: Boolean? = true,
        bridgeIntegrated: Boolean = true,
        bridgeDetail: String = "bridge detail",
        appIdConfigured: Boolean = true,
    ) = HyperOsIslandProbe(
        manufacturer = manufacturer,
        brand = brand,
        display = display,
        incremental = incremental,
        focusProtocolVersion = focusProtocolVersion,
        focusPermissionGranted = focusPermissionGranted,
        bridgeIntegrated = bridgeIntegrated,
        bridgeDetail = bridgeDetail,
        appIdConfigured = appIdConfigured,
    )
}
