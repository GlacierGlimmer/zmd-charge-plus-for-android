package com.glacierglimmer.endfieldchargeplus.metrics

import android.content.Context
import android.hardware.display.DisplayManager
import com.glacierglimmer.endfieldchargeplus.core.metrics.FrameTiming
import com.glacierglimmer.endfieldchargeplus.core.model.HudProfile
import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import com.glacierglimmer.endfieldchargeplus.core.model.SamplingTier
import com.glacierglimmer.endfieldchargeplus.core.model.UnavailableReason
import com.glacierglimmer.endfieldchargeplus.root.RootAccessManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

data class FrameTarget(val id: String,val name: String)

class FrameRateReader(private val context: Context, private val root: RootAccessManager) {
    private var previousFrames: Long?=null
    private var previousAt=0L
    private var enabledByUs=false
    private var statsRequested=false
    private val samplingLock=kotlinx.coroutines.sync.Mutex()
    suspend fun windows(): List<FrameTarget> = withContext(Dispatchers.IO) {
        root.read("dumpsys SurfaceFlinger --list").orEmpty().lineSequence().map { it.trim() }
            .filter { it.isNotBlank() && !it.contains("endfieldchargeplus",ignoreCase=true) && !it.any { c -> c=='\n' || c=='\r' || c=='\u0000' } }
            .distinct().map { FrameTarget(it,it) }.toList()
    }
    fun displays(): List<FrameTarget> = context.getSystemService(DisplayManager::class.java)?.displays.orEmpty()
        .map { FrameTarget(it.displayId.toString(),it.name) }
    suspend fun read(kind: String,profile: HudProfile): Map<String,MetricValue> = withContext(Dispatchers.IO) { samplingLock.withLock {
        val prefix="frame.$kind."
        var fps: Double?=null
        var name=if(kind=="window") profile.frameWindowId else displays().firstOrNull { it.id==(profile.frameDisplayId.ifBlank { "0" }) }?.name.orEmpty()
        var reason=UnavailableReason.NO_DATA
        var status="Waiting for frame samples"
        if(!root.granted) { previousFrames=null; previousAt=0; reason=UnavailableReason.PERMISSION_REQUIRED; status="ROOT permission required" }
        else if(kind=="window") {
            val target=profile.frameWindowId
            if(target.isBlank()) status="Select a SurfaceFlinger window/layer"
            else if(windows().none { it.id==target }) status="Selected window/layer has closed"
            else {
                val escaped="'"+target.replace("'","'\\''")+"'"
                val output=root.read("dumpsys SurfaceFlinger --latency $escaped")
                fps=output?.let { FrameTiming.latencyFps(it,System.nanoTime()) }
                status=if(fps!=null) "OK" else "Device does not expose valid frame timestamps for this layer"
            }
        } else {
            val displays=displays()
            // Android's global timestats counter cannot be attributed to an external display.
            if(displays.size!=1 || displays.single().id!=profile.frameDisplayId.ifBlank { "0" }) {
                status="SurfaceFlinger cannot attribute its global frame counter to this selected display"
                reason=UnavailableReason.NOT_AVAILABLE_ON_DEVICE
            } else {
                var output=root.read("dumpsys SurfaceFlinger --timestats -dump -maxlayers 0")
                if(!statsRequested) {
                    val before=FrameTiming.statsStart(output.orEmpty())
                    if(root.read("dumpsys SurfaceFlinger --timestats -enable")!=null) {
                        statsRequested=true
                        output=root.read("dumpsys SurfaceFlinger --timestats -dump -maxlayers 0")
                        val after=FrameTiming.statsStart(output.orEmpty())
                        enabledByUs=after!=null && after!=before
                    } else output=null
                }
                val count=FrameTiming.totalFrames(output.orEmpty()); val now=System.nanoTime()
                val previous=previousFrames
                if(count!=null && previous!=null && count>=previous && now>previousAt) fps=(count-previous)*1e9/(now-previousAt)
                previousFrames=count; previousAt=now
                status=if(fps!=null) "OK" else "Waiting for SurfaceFlinger frame counter; vendor support required"
            }
        }
        mapOf(prefix+"fps" to (fps?.let(MetricValue::number) ?: MetricValue.Unavailable(reason,status)),
            prefix+"percent" to MetricValue.number(FrameTiming.percent(fps,profile.frameFullScaleFps)),
            prefix+"name" to MetricValue.text(name),prefix+"status" to MetricValue.Text(status))
    } }
    suspend fun stop() = withContext(Dispatchers.IO) { samplingLock.withLock {
        if(enabledByUs) root.read("dumpsys SurfaceFlinger --timestats -disable")
        enabledByUs=false; statsRequested=false; previousFrames=null; previousAt=0
    } }
}

class FrameRateCollector(private val reader: FrameRateReader, private val environment: MetricEnvironment): MetricCollector {
    override val id="frame-rate"
    override val tier=SamplingTier.NORMAL
    override suspend fun collect(into: MutableMap<String,MetricValue>) {
        val profile=environment.activeProfile() ?: return
        val required=com.glacierglimmer.endfieldchargeplus.core.template.TemplateEngine.extractKeys(
            profile.primaryTemplate,profile.secondaryTemplate,profile.rightTemplate,profile.titleTemplate,profile.taglineTemplate,profile.rightSuffix)+
            com.glacierglimmer.endfieldchargeplus.core.template.TemplateEngine.extractExpressionKeys(profile.progressVariable)+
            profile.colorRules.flatMap { com.glacierglimmer.endfieldchargeplus.core.template.TemplateEngine.extractExpressionKeys(it.variable) }
        var active=false
        for(kind in listOf("display","window")) if(required.any { it.startsWith("frame.$kind.") }) { into.putAll(reader.read(kind,profile)); active=true }
        if(!active) reader.stop()
    }
}
