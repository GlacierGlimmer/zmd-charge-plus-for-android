package com.glacierglimmer.endfieldchargeplus.core.metrics

/** SurfaceFlinger timestamps are nanoseconds from CLOCK_MONOTONIC, not the configured refresh period. */
object FrameTiming {
    fun latencyFps(output: String, nowNanos: Long): Double? {
        val frames = output.lineSequence().drop(1).mapNotNull { line ->
            val columns=line.trim().split(Regex("\\s+"))
            if(columns.size!=3) null else columns[1].toLongOrNull()?.takeIf { it>0 && it<Long.MAX_VALUE && it<=nowNanos }
        }.distinct().sorted().toList()
        if(frames.isEmpty()) return null
        val recent=frames.filter { nowNanos-it<=2_000_000_000L }
        if(recent.isEmpty()) return 0.0
        if(recent.size<2) return null
        val span=recent.last()-recent.first()
        return if(span>0) (recent.size-1)*1e9/span else null
    }
    fun totalFrames(output: String): Long? = Regex("(?m)^\\s*totalFrames(?:Legacy)?\\s*=\\s*(\\d+)\\s*$")
        .find(output.substringBefore("layerName"))?.groupValues?.get(1)?.toLongOrNull()
    fun statsStart(output: String): Long? = Regex("(?m)^\\s*statsStart\\s*=\\s*(\\d+)\\s*$").find(output)?.groupValues?.get(1)?.toLongOrNull()
    fun percent(fps: Double?, fullScale: Double): Double? = fps?.takeIf { it.isFinite() && it>=0 && fullScale.isFinite() && fullScale>0 }
        ?.let { (it/fullScale*100).coerceIn(0.0,100.0) }
}
