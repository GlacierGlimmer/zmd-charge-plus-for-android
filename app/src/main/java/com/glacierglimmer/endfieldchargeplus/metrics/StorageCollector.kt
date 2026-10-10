package com.glacierglimmer.endfieldchargeplus.metrics

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import com.glacierglimmer.endfieldchargeplus.core.metrics.Variables
import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import com.glacierglimmer.endfieldchargeplus.core.model.SamplingTier
import com.glacierglimmer.endfieldchargeplus.core.model.UnavailableReason

/**
 * Storage capacity of the data volume and of the primary external volume.
 *
 * `StatFs` is the only public capacity API on Android. A volume that cannot be read (unmounted,
 * locked or not exposed) yields `Unavailable` with the probed path rather than a fabricated
 * `0 / 0 GB`. Block I/O rates are deliberately absent: no public API exists.
 */
class StorageCollector(
    private val context: Context,
    @Suppress("UNUSED_PARAMETER") environment: MetricEnvironment,
) : MetricCollector {

    override val id: String = "storage"

    override val tier: SamplingTier = SamplingTier.SLOW

    override suspend fun collect(into: MutableMap<String, MetricValue>) {
        publishVolume(into, SYSTEM_PREFIX, Environment.getDataDirectory().absolutePath)

        val externalPath = primaryExternalPath()
        if (externalPath == null) {
            val detail = "no mounted primary external storage volume (StorageManager)"
            into.putUnavailable("${DATA_PREFIX}total_bytes", UnavailableReason.NOT_AVAILABLE_ON_DEVICE, detail)
            into.putUnavailable("${DATA_PREFIX}used_bytes", UnavailableReason.NOT_AVAILABLE_ON_DEVICE, detail)
            into.putUnavailable("${DATA_PREFIX}available_bytes", UnavailableReason.NOT_AVAILABLE_ON_DEVICE, detail)
            into.putUnavailable("${DATA_PREFIX}usage", UnavailableReason.NOT_AVAILABLE_ON_DEVICE, detail)
        } else {
            publishVolume(into, DATA_PREFIX, externalPath)
        }
    }

    private fun publishVolume(into: MutableMap<String, MetricValue>, prefix: String, path: String) {
        val statistics = try {
            StatFs(path)
        } catch (error: Exception) {
            null
        }
        if (statistics == null) {
            putVolumeUnavailable(into, prefix, "StatFs($path) could not be constructed")
            return
        }
        val blockSize = runCatching { statistics.blockSizeLong }.getOrDefault(0L)
        val total = runCatching { statistics.blockCountLong * blockSize }.getOrDefault(0L)
        val available = runCatching { statistics.availableBlocksLong * blockSize }.getOrDefault(0L)
        if (total <= 0L) {
            putVolumeUnavailable(into, prefix, "StatFs($path) reports no blocks")
            return
        }
        val used = (total - available).coerceAtLeast(0L)
        into["${prefix}total_bytes"] = MetricValue.Number(total.toDouble())
        into["${prefix}used_bytes"] = MetricValue.Number(used.toDouble())
        into["${prefix}available_bytes"] = MetricValue.Number(available.toDouble())
        into["${prefix}usage"] =
            MetricValue.Number((used.toDouble() * 100.0 / total.toDouble()).coerceIn(0.0, 100.0))
        into["${prefix}free_percent"] = MetricValue.Number((available.toDouble() * 100.0 / total).coerceIn(0.0, 100.0))
    }

    private fun putVolumeUnavailable(into: MutableMap<String, MetricValue>, prefix: String, detail: String) {
        into.putUnavailable("${prefix}total_bytes", UnavailableReason.NOT_AVAILABLE_ON_DEVICE, detail)
        into.putUnavailable("${prefix}used_bytes", UnavailableReason.NOT_AVAILABLE_ON_DEVICE, detail)
        into.putUnavailable("${prefix}available_bytes", UnavailableReason.NOT_AVAILABLE_ON_DEVICE, detail)
        into.putUnavailable("${prefix}usage", UnavailableReason.NOT_AVAILABLE_ON_DEVICE, detail)
        into.putUnavailable("${prefix}free_percent", UnavailableReason.NOT_AVAILABLE_ON_DEVICE, detail)
    }

    /**
     * Primary removable/emulated external volume.
     *
     * `StorageVolume.getDirectory()` is API 30+; on API 26–29 the framework only exposes
     * `Environment.getExternalStorageDirectory()`, which is exactly the primary volume there.
     */
    private fun primaryExternalPath(): String? {
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        val volume = storageManager?.let { manager ->
            runCatching { manager.storageVolumes.firstOrNull { it.isPrimary } ?: manager.storageVolumes.firstOrNull() }
                .getOrNull()
        }
        if (volume != null) {
            val state = runCatching { volume.state }.getOrNull()
            if (state != Environment.MEDIA_MOUNTED) return null
            if (Build.VERSION.SDK_INT >= 30) {
                val directory = runCatching { volume.directory?.absolutePath }.getOrNull()
                if (!directory.isNullOrBlank()) return directory
            }
        }
        return environmentFallback()
    }

    @Suppress("DEPRECATION")
    private fun environmentFallback(): String? {
        val path = Environment.getExternalStorageDirectory()?.absolutePath ?: return null
        val state = Environment.getExternalStorageState()
        return if (state == Environment.MEDIA_MOUNTED) path else null
    }

    private companion object {
        /** Derived from [Variables] so the published keys can never drift from the contract. */
        val SYSTEM_PREFIX = Variables.DISK_SYSTEM_TOTAL_BYTES.removeSuffix("total_bytes")
        val DATA_PREFIX = Variables.DISK_DATA_TOTAL_BYTES.removeSuffix("total_bytes")
    }
}
