package com.glacierglimmer.endfieldchargeplus.core.metrics
import org.junit.Assert.*
import org.junit.Test
class FrameTimingTest {
    @Test fun `statistics start is parsed for safe capture session ownership`() { assertEquals(123L,FrameTiming.statsStart("statsStart = 123\n")); assertNull(FrameTiming.statsStart("")) }
    @Test fun `latency uses presented timestamps instead of advertised refresh period`() {
        val output="8333333\n1 1000000000 1\n2 1010000000 2\n3 1020000000 3"
        assertEquals(100.0,FrameTiming.latencyFps(output,1100000000)!!,0.001)
    }
    @Test fun `invalid and pending timestamps cannot become invented FPS`() {
        assertNull(FrameTiming.latencyFps("16666666\n0 0 0\n1 9223372036854775807 1",2000000000))
        assertNull(FrameTiming.latencyFps("16666666\n1 1000000000 1\n1 1000000000 1",1100000000))
    }
    @Test fun `an idle measured layer reports zero`() {
        assertEquals(0.0,FrameTiming.latencyFps("16666666\n1 1000000000 1\n2 1010000000 2",4000000000)!!,0.0)
    }
    @Test fun `percentage has explicit full scale and preserves unavailable readings`() {
        assertEquals(50.0,FrameTiming.percent(60.0,120.0)!!,0.0)
        assertNull(FrameTiming.percent(null,120.0)); assertNull(FrameTiming.percent(Double.NaN,120.0))
        assertNull(FrameTiming.percent(60.0,0.0)); assertEquals(100.0,FrameTiming.percent(180.0,120.0)!!,0.0)
    }
    @Test fun `global frame count cannot accidentally read a per-layer count`() {
        assertEquals(42L,FrameTiming.totalFrames("totalFrames = 42\nlayerName = example\ntotalFrames = 900"))
        assertNull(FrameTiming.totalFrames("layerName = example\ntotalFrames = 900"))
    }
}
