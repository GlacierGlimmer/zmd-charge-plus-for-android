package com.glacierglimmer.endfieldchargeplus.metrics
import android.os.BatteryManager
import org.junit.Assert.*
import org.junit.Test

class BatteryReadingPolicyTest {
    @Test fun `unknown battery state is not converted to a false charging or battery source value`() {
        assertNull(BatteryText.charging(BatteryManager.BATTERY_STATUS_UNKNOWN))
        assertNull(BatteryText.powerSourceName(-1))
        assertNull(BatteryText.powerSourceName(1234))
        assertEquals(true,BatteryText.charging(BatteryManager.BATTERY_STATUS_CHARGING))
        assertEquals(false,BatteryText.charging(BatteryManager.BATTERY_STATUS_DISCHARGING))
        assertEquals("Battery",BatteryText.powerSourceName(0))
    }
    @Test fun `charge counter estimates cover discharging and reject reset-sized jumps`() {
        assertEquals(1000.0,BatteryEnergyMath.currentMagnitudeFromSignedCounterDelta(-10.0,36)!!,0.001)
        assertEquals(1000.0,BatteryEnergyMath.currentMagnitudeFromSignedCounterDelta(10.0,36)!!,0.001)
        assertNull(BatteryEnergyMath.currentMagnitudeFromSignedCounterDelta(-1000000.0,1000))
    }
}
