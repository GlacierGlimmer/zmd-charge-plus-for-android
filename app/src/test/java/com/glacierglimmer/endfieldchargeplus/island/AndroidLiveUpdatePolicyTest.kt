package com.glacierglimmer.endfieldchargeplus.island

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Availability to message-key mapping of the Android promoted ongoing notification backend.
 *
 * Every failing branch must name a state the settings page can show as 不支持 / 不可用 / 尚未授权.
 */
class AndroidLiveUpdatePolicyTest {

    @Test
    fun `api 35 has no promoted ongoing api and is unsupported by the platform`() {
        val availability = AndroidLiveUpdatePolicy.preconditions(probe(sdkInt = 35))

        assertEquals(IslandAvailabilityState.UNSUPPORTED_BY_PLATFORM, availability?.state)
        assertEquals("island_state_unsupported", availability?.messageKey)
        assertTrue(availability!!.detail.contains("API 36"))
    }

    @Test
    fun `api 36 with every check passing has no blocker`() {
        assertNull(AndroidLiveUpdatePolicy.preconditions(probe()))
    }

    @Test
    fun `disabled notifications are unavailable and never presented as connected`() {
        val availability = AndroidLiveUpdatePolicy.preconditions(probe(notificationsEnabled = false))

        assertEquals(IslandAvailabilityState.UNAVAILABLE, availability?.state)
        assertEquals(AndroidLiveUpdateProvider.KEY_NOTIFICATIONS_DISABLED, availability?.messageKey)
    }

    @Test
    fun `a blocked notification channel is unavailable`() {
        val availability = AndroidLiveUpdatePolicy.preconditions(probe(channelBlocked = true))

        assertEquals(IslandAvailabilityState.UNAVAILABLE, availability?.state)
        assertEquals(AndroidLiveUpdateProvider.KEY_CHANNEL_DISABLED, availability?.messageKey)
    }

    @Test
    fun `a failed promotion check is unavailable and keeps the technical detail`() {
        val availability = AndroidLiveUpdatePolicy.preconditions(
            probe(canPostPromoted = null, failureDetail = "NoSuchMethodError: canPostPromotedNotifications"),
        )

        assertEquals(IslandAvailabilityState.UNAVAILABLE, availability?.state)
        assertEquals(AndroidLiveUpdateProvider.KEY_PROMOTION_CHECK_FAILED, availability?.messageKey)
        assertEquals("NoSuchMethodError: canPostPromotedNotifications", availability?.detail)
    }

    @Test
    fun `missing user authorization is reported as not authorized with a settings pointer`() {
        val availability = AndroidLiveUpdatePolicy.preconditions(probe(canPostPromoted = false))

        assertEquals(IslandAvailabilityState.NOT_AUTHORIZED, availability?.state)
        assertEquals(AndroidLiveUpdateProvider.KEY_NOT_AUTHORIZED, availability?.messageKey)
        assertTrue(availability!!.detail.contains("ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS"))
    }

    @Test
    fun `the notification api level constant matches android 16`() {
        assertEquals(36, AndroidLiveUpdateProvider.MIN_PROMOTED_API_LEVEL)
        assertEquals("android.permission.POST_NOTIFICATIONS", AndroidLiveUpdateProvider.PERMISSION_POST_NOTIFICATIONS)
    }

    @Test
    fun `both documented android 16 notification shapes are probed`() {
        assertEquals(2, PromotableShape.CANDIDATES.size)
        assertEquals(true, PromotableShape.ANDROID_16.colorized)
        assertEquals(false, PromotableShape.ANDROID_16.requestPromoted)
        assertEquals(false, PromotableShape.ANDROID_16_QPR1.colorized)
        assertEquals(true, PromotableShape.ANDROID_16_QPR1.requestPromoted)
        assertEquals(PromotableShape.ANDROID_16_QPR1, PromotableShape.CANDIDATES.first())
    }

    private fun probe(
        sdkInt: Int = AndroidLiveUpdateProvider.MIN_PROMOTED_API_LEVEL,
        notificationsEnabled: Boolean = true,
        channelBlocked: Boolean = false,
        canPostPromoted: Boolean? = true,
        failureDetail: String = "",
    ) = AndroidLiveUpdateProbe(
        sdkInt = sdkInt,
        notificationsEnabled = notificationsEnabled,
        channelBlocked = channelBlocked,
        canPostPromoted = canPostPromoted,
        failureDetail = failureDetail,
    )
}
