package com.glacierglimmer.endfieldchargeplus.island

import android.app.Notification
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Xiaomi bridge seam must report "not integrated" instead of a fake success, and the island
 * payload must follow the documented `param_v2` shape.
 */
class XiaomiIslandBridgeTest {

    @Test
    fun `not integrated bridge reports that it is not integrated`() {
        assertFalse(NotIntegratedBridge.isIntegrated())
        assertTrue(NotIntegratedBridge.integrationDetail(), NotIntegratedBridge.integrationDetail().contains("not integrated"))
        assertTrue(NotIntegratedBridge.integrationDetail().contains("com.xiaomi.xms.APP_ID"))
        assertTrue(NotIntegratedBridge.documentationUrl().startsWith("https://dev.mi.com/"))
    }

    @Test
    fun `not integrated bridge refuses to publish and never touches the host`() {
        val host = RecordingHost()

        val published = NotIntegratedBridge.publish(host, 7, builder(), """{"param_v2":{}}""")

        assertFalse(published)
        assertEquals(0, host.published.size)
        assertEquals(0, host.updated.size)
    }

    @Test
    fun `documentation urls point at the official xiaomi pages`() {
        assertTrue(XiaomiIslandBridge.DOCUMENTATION_ACCESS_URL.contains("dev.mi.com/xiaomihyperos/documentation"))
        assertTrue(XiaomiIslandBridge.DOCUMENTATION_GUIDE_URL.contains("pId=2131"))
        assertTrue(XiaomiIslandBridge.DOCUMENTATION_PRODUCT_URL.contains("pId=2140"))
    }

    @Test
    fun `island payload carries the documented param_v2 fields`() {
        val json = Json.parseToJsonElement(
            XiaomiIslandParams.build(
                IslandContent(
                    title = "Charging",
                    subtitle = "78%",
                    shortText = "25.4 W",
                    progressPercent = 78.4,
                    iconKey = "bolt",
                    accentColor = "#C6CA4C",
                ),
                business = "charging",
            ),
        ).jsonObject

        val param = json["param_v2"]!!.jsonObject
        assertEquals("charging", param["business"]!!.jsonPrimitive.content)
        assertEquals("1", param["protocol"]!!.jsonPrimitive.content)
        assertEquals("true", param["updatable"]!!.jsonPrimitive.content)
        assertEquals("Charging", param["aodTitle"]!!.jsonPrimitive.content)

        val island = param["param_island"]!!.jsonObject
        assertEquals("#C6CA4C", island["highlightColor"]!!.jsonPrimitive.content)
        val textInfo = island["bigIslandArea"]!!.jsonObject["imageTextInfoLeft"]!!.jsonObject["miui.focus.paramtextInfo"]!!.jsonObject
        assertEquals("Charging", textInfo["frontTitle"]!!.jsonPrimitive.content)
        assertEquals("25.4 W", textInfo["title"]!!.jsonPrimitive.content)
        assertEquals("78%", textInfo["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `island payload omits the accent colour when the profile did not provide a hex value`() {
        val json = Json.parseToJsonElement(
            XiaomiIslandParams.build(IslandContent(title = "Charging", accentColor = "rebeccapurple"), business = "charging"),
        ).jsonObject

        val island = json["param_v2"]!!.jsonObject["param_island"]!!.jsonObject
        assertFalse(island.containsKey("highlightColor"))
    }

    private fun builder(): Notification.Builder = Notification.Builder(null, "channel")

    private class RecordingHost : IslandNotificationHost {
        val published = mutableListOf<Notification.Builder>()
        val updated = mutableListOf<Notification.Builder>()
        var cancelled = 0

        override fun publish(id: Int, builder: Notification.Builder) {
            published.add(builder)
        }

        override fun update(id: Int, builder: Notification.Builder) {
            updated.add(builder)
        }

        override fun cancel(id: Int) {
            cancelled++
        }

        override fun areNotificationsEnabled(): Boolean = true
    }
}
