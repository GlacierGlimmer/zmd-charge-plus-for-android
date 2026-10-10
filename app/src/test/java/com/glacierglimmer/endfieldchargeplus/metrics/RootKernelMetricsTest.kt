package com.glacierglimmer.endfieldchargeplus.metrics

import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import com.glacierglimmer.endfieldchargeplus.core.metrics.Variables
import com.glacierglimmer.endfieldchargeplus.root.RootAccessManager
import com.glacierglimmer.endfieldchargeplus.root.RootCommandShell
import com.glacierglimmer.endfieldchargeplus.root.RootStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RootKernelMetricsTest {
    @Test fun `root-gated proc samples produce actual two-sample CPU and per-core load`() = runTest {
        val shell=FixtureShell(); val root=RootAccessManager(shell); val reader=RootKernelReader(root, EmptyReader)
        val cpu=CpuNodeMetrics(reader) { 1 }
        val absent=mutableMapOf<String,MetricValue>(); cpu.collect(absent)
        assertTrue(absent[Variables.CPU_USAGE] is MetricValue.Unavailable)
        assertTrue(shell.commands.isEmpty())
        root.configure(true); assertEquals(RootStatus.GRANTED,root.status.value); reader.invalidate()
        shell.files["/proc/stat"]="cpu 10 0 10 80 0 0 0 0\ncpu0 10 0 10 80 0 0 0 0"
        shell.files["/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq"]="1800000"
        shell.files["/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq"]="3000000"
        val first=mutableMapOf<String,MetricValue>(); cpu.collect(first)
        assertTrue(first[Variables.CPU_USAGE] is MetricValue.Unavailable)
        shell.files["/proc/stat"]="cpu 20 0 25 105 0 0 0 0\ncpu0 20 0 25 105 0 0 0 0"
        val second=mutableMapOf<String,MetricValue>(); cpu.collect(second)
        assertEquals(50.0,(second[Variables.CPU_USAGE] as MetricValue.Number).value,1e-6)
        assertEquals(50.0,(second["cpu.core0.usage"] as MetricValue.Number).value,1e-6)
        assertEquals(1.8,(second[Variables.CPU_FREQUENCY_GHZ] as MetricValue.Number).value,1e-6)
        assertEquals(3.0,(second[Variables.CPU_MAX_FREQUENCY_GHZ] as MetricValue.Number).value,1e-6)
    }
    @Test fun `Root GPU readings come from known vendor nodes while absent memory fields are omitted`() = runTest {
        val shell=FixtureShell(); val root=RootAccessManager(shell); root.configure(true)
        val reader=RootKernelReader(root, EmptyReader)
        shell.files[MetricPaths.KGSL_GPU_BUSY]="17 20"
        shell.files[MetricPaths.KGSL_CLOCK]="500000000"
        shell.files[MetricPaths.KGSL_MODEL]="Adreno"
        shell.files["/sys/class/thermal/thermal_zone0/type"]="gpu-thermal"
        shell.files["/sys/class/thermal/thermal_zone0/temp"]="53000"
        val values=mutableMapOf<String,MetricValue>(); GpuCollector(null,null,reader).collect(values)
        assertEquals(85.0,(values[Variables.GPU_USAGE] as MetricValue.Number).value,1e-6)
        assertEquals(0.5,(values[Variables.GPU_FREQUENCY_GHZ] as MetricValue.Number).value,1e-6)
        assertEquals(53.0,(values[Variables.GPU_TEMPERATURE_C] as MetricValue.Number).value,1e-6)
        assertEquals("Adreno",(values[Variables.GPU_MODEL] as MetricValue.Text).value)
        assertFalse(values.containsKey(Variables.GPU_MEMORY_TOTAL_BYTES))
        assertFalse(values.containsKey(Variables.GPU_MEMORY_USED_BYTES))
    }
    @Test fun `disabling Root immediately stops privileged reads and absent nodes use a backoff`() = runTest {
        val shell=FixtureShell(); val root=RootAccessManager(shell); root.configure(true)
        var now=0L; val reader=RootKernelReader(root, EmptyReader) { now }
        repeat(100) { assertNull(reader.readText("/proc/stat")) }
        assertEquals(1,shell.commands.count { it.startsWith("cat") })
        now=60_001; reader.readText("/proc/stat")
        assertEquals(2,shell.commands.count { it.startsWith("cat") })
        root.configure(false); val calls=shell.commands.size
        repeat(100) { reader.readText("/proc/stat") }
        assertEquals(calls,shell.commands.size)
    }
    @Test fun `Root path validation excludes system files and shell injection`() {
        for(path in listOf("/data/secret", "/sys/class/thermal/../../data/secret", "/proc/stat'; reboot #", "/sys/kernel/gpu/\nreboot")) assertFalse(RootKernelReader.isKernelPath(path))
        assertTrue(RootKernelReader.isKernelPath("/proc/stat"))
        assertTrue(RootKernelReader.isKernelPath("/sys/class/thermal/thermal_zone0/temp"))
    }
    @Test fun `CPU thermal reading is not substituted with a battery sensor`() {
        val reader=object:KernelReader {
            override fun listNames(path:String)=listOf("thermal_zone0", "thermal_zone1")
            override fun readText(path:String)=when(path) {
                "/sys/class/thermal/thermal_zone0/type"->"battery"
                "/sys/class/thermal/thermal_zone0/temp"->"32000"
                "/sys/class/thermal/thermal_zone1/type"->"cpu-thermal"
                "/sys/class/thermal/thermal_zone1/temp"->"68000"
                else->null
            }
        }
        val values=mutableMapOf<String,MetricValue>(); CpuNodeMetrics(reader) { 1 }.collect(values)
        assertEquals(68.0,(values[Variables.CPU_TEMPERATURE_C] as MetricValue.Number).value,1e-6)
    }
    private object EmptyReader:KernelReader { override fun readText(path:String):String?=null; override fun listNames(path:String)=emptyList<String>() }
    private class FixtureShell:RootCommandShell {
        val commands=mutableListOf<String>(); val files=mutableMapOf<String,String>(); var alive=false
        override fun run(command:String,timeoutMs:Long):String? {
            commands+=command; alive=true
            if(command=="id -u")return "0"
            val path=command.substringAfter("'").substringBeforeLast("'")
            return if(command.startsWith("cat")) files[path] else when(path) {
                "/sys/devices/system/cpu"->"cpu0"
                "/sys/class/thermal"->"thermal_zone0"
                else->null
            }
        }
        override fun close() { alive=false }
        override fun isAlive()=alive
    }
}
