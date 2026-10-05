package com.glacierglimmer.endfieldchargeplus.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Thermal zone classification, cpufreq averaging, GPU node parsing and battery energy guards. */
class HardwareParsingTest {

    private fun zone(type: String, milliCelsius: Long?) = ThermalZone(type, milliCelsius, "/sys/class/thermal/$type/temp")

    @Test
    fun `cpu zones are identified by their type`() {
        assertTrue(ThermalZoneClassifier.isCpuZone("cpu-therm"))
        assertTrue(ThermalZoneClassifier.isCpuZone("CPU0"))
        assertTrue(ThermalZoneClassifier.isCpuZone("tsens_tz_sensor12"))
        assertTrue(ThermalZoneClassifier.isCpuZone("mtktscpu"))
        assertTrue(ThermalZoneClassifier.isCpuZone("bigcore0"))
        assertFalse(ThermalZoneClassifier.isCpuZone("battery"))
        assertFalse(ThermalZoneClassifier.isCpuZone("quiet_therm"))
        assertFalse(ThermalZoneClassifier.isCpuZone(""))
    }

    @Test
    fun `gpu zones are identified by their type`() {
        assertTrue(ThermalZoneClassifier.isGpuZone("gpu-therm"))
        assertTrue(ThermalZoneClassifier.isGpuZone("kgsl-3d0"))
        assertFalse(ThermalZoneClassifier.isGpuZone("cpu-therm"))
    }

    @Test
    fun `hottest cpu zone wins and gpu zones are ignored`() {
        val zones = listOf(
            zone("cpu0", 45_000),
            zone("cpu1", 52_500),
            zone("gpu", 60_000),
            zone("battery", 30_000),
        )

        assertEquals(52.5, ThermalZoneClassifier.hottestCpuCelsius(zones)!!, 0.0001)
        assertEquals(60.0, ThermalZoneClassifier.hottestGpuCelsius(zones)!!, 0.0001)
    }

    @Test
    fun `implausible thermal readings are rejected, not clamped`() {
        assertNull(ThermalZoneClassifier.celsius(0))
        assertNull(ThermalZoneClassifier.celsius(250_000))
        assertNull(ThermalZoneClassifier.celsius(-90_000))
        assertEquals(36.5, ThermalZoneClassifier.celsius(36_500)!!, 0.0001)
        assertNull(
            ThermalZoneClassifier.hottestCpuCelsius(listOf(zone("cpu0", null), zone("cpu1", 999_000))),
        )
    }

    @Test
    fun `cpufreq averaging ignores unreadable cores and yields null when none is readable`() {
        assertEquals(
            1_500_000.0,
            CpuFrequencyMath.averageKhz(listOf(1_000_000L, null, 2_000_000L, 1_500_000L))!!,
            0.001,
        )
        assertEquals(1_800_000.0, CpuFrequencyMath.averageKhz(listOf(1_800_000L, null, null))!!, 0.001)
        assertNull(CpuFrequencyMath.averageKhz(listOf(null, null, 0L, -5L)))
        assertNull(CpuFrequencyMath.averageKhz(emptyList()))
    }

    @Test
    fun `cpufreq converts kHz to MHz`() {
        assertEquals(1_800.0, CpuFrequencyMath.megahertz(1_800_000.0), 0.0001)
    }

    @Test
    fun `gpu percent nodes are parsed only when sane`() {
        assertEquals(12.0, GpuNodeParsers.parsePercent("12")!!, 0.0001)
        assertEquals(12.0, GpuNodeParsers.parsePercent("12 %")!!, 0.0001)
        assertEquals(12.0, GpuNodeParsers.parsePercent("12%\n")!!, 0.0001)
        assertNull(GpuNodeParsers.parsePercent("101"))
        assertNull(GpuNodeParsers.parsePercent("n/a"))
    }

    @Test
    fun `kgsl gpubusy pair and devfreq load are parsed`() {
        assertEquals(25.0, GpuNodeParsers.parseBusyPair("25 100")!!, 0.0001)
        assertNull(GpuNodeParsers.parseBusyPair("25"))
        assertNull(GpuNodeParsers.parseBusyPair("25 0"))
        assertEquals(37.0, GpuNodeParsers.parseDevfreqLoad("37@490000000Hz")!!, 0.0001)
        assertNull(GpuNodeParsers.parseDevfreqLoad("busy"))
    }

    @Test
    fun `gpu clock node is parsed as hertz`() {
        assertEquals(490_000_000.0, GpuNodeParsers.parseFrequencyHz("490000000")!!, 0.001)
        assertEquals(490_000_000.0, GpuNodeParsers.parseFrequencyHz("490000000 Hz")!!, 0.001)
        assertNull(GpuNodeParsers.parseFrequencyHz("0"))
    }

    @Test
    fun `cpuinfo model prefers Hardware then model name then Processor`() {
        val text = """
            processor	: 0
            Processor	: AArch64 Processor rev 14 (aarch64)
            model name	: Qualcomm Technologies, Inc SM8650
            Hardware	: Qualcomm Technologies, Inc SM8650
        """.trimIndent()

        assertEquals("Qualcomm Technologies, Inc SM8650", CpuInfoParser.model(text))
        assertEquals("AArch64 Processor rev 14 (aarch64)", CpuInfoParser.model("Processor\t: AArch64 Processor rev 14 (aarch64)\n"))
        assertNull(CpuInfoParser.model("processor : 0\n"))
    }

    @Test
    fun `battery energy maths refuses implausible inputs`() {
        // 4_000_000 uAh at 3.8 V = 15_200 mWh.
        assertEquals(15_200.0, BatteryEnergyMath.chargeToMilliWattHours(4_000_000.0, 3_800.0)!!, 0.01)
        assertNull(BatteryEnergyMath.chargeToMilliWattHours(0.0, 3_800.0))
        assertNull(BatteryEnergyMath.chargeToMilliWattHours(4_000_000.0, 0.0))
        assertNull(BatteryEnergyMath.chargeToMilliWattHours(4_000_000.0, 1_000_000.0))
    }

    @Test
    fun `full pack capacity is only extrapolated for a usable percentage`() {
        assertEquals(15_000.0, BatteryEnergyMath.fullMilliWattHours(7_500.0, 50.0)!!, 0.01)
        assertNull(BatteryEnergyMath.fullMilliWattHours(7_500.0, 1.0))
        assertNull(BatteryEnergyMath.fullMilliWattHours(7_500.0, 0.0))
    }

    @Test
    fun `counter delta becomes a milliamp estimate over the real interval`() {
        // 1_000 uAh drained in one hour = 1 mA.
        assertEquals(1.0, BatteryEnergyMath.currentMilliAmpsFromCounterDelta(1_000.0, 3_600_000L)!!, 0.0001)
        assertNull(BatteryEnergyMath.currentMilliAmpsFromCounterDelta(1_000.0, 0L))
        assertNull(BatteryEnergyMath.currentMilliAmpsFromCounterDelta(-5.0, 1_000L))
    }
}
