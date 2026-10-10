package com.glacierglimmer.endfieldchargeplus.metrics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.SystemClock
import com.glacierglimmer.endfieldchargeplus.core.metrics.Variables
import com.glacierglimmer.endfieldchargeplus.core.model.MetricValue
import com.glacierglimmer.endfieldchargeplus.core.model.NetworkDisplayUnit
import com.glacierglimmer.endfieldchargeplus.core.model.NetworkPercentMode
import com.glacierglimmer.endfieldchargeplus.core.model.SamplingTier
import com.glacierglimmer.endfieldchargeplus.core.model.UnavailableReason

/**
 * System-wide network throughput and connection state.
 *
 * The rate uses `TrafficStats.getTotalRxBytes()/getTotalTxBytes()` deltas divided by the real
 * elapsed interval measured with `SystemClock.elapsedRealtime()`, with the same monotonic/reset
 * guard as the Linux and macOS editions: on a counter reset the sample is skipped and re-primed
 * instead of producing a negative or absurd rate. Per-UID/per-interface counters were removed for
 * third-party apps in API 31+, so the totals are the honest system-wide numbers.
 *
 * `TrafficStats.UNSUPPORTED` (-1) becomes `Unavailable`, never `0`, and a first sample has no rate
 * yet (also `Unavailable`).
 */
class NetworkCollector(
    private val context: Context,
    private val environment: MetricEnvironment,
) : MetricCollector {

    override val id: String = "network"

    override val tier: SamplingTier = SamplingTier.NORMAL

    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var previousRxBytes: Long? = null

    private var previousTxBytes: Long? = null

    private var previousAtMs: Long = 0L

    override suspend fun collect(into: MutableMap<String, MetricValue>) {
        val nowMs = SystemClock.elapsedRealtime()
        val rxBytes = TrafficStats.getTotalRxBytes()
        val txBytes = TrafficStats.getTotalTxBytes()
        val rxSupported = rxBytes >= 0L
        val txSupported = txBytes >= 0L

        if (rxSupported) {
            into[Variables.NETWORK_DOWNLOAD_TOTAL_BYTES] = MetricValue.Number(rxBytes.toDouble())
        } else {
            into.putUnavailable(
                Variables.NETWORK_DOWNLOAD_TOTAL_BYTES,
                UnavailableReason.NOT_SUPPORTED,
                "TrafficStats.getTotalRxBytes() returned UNSUPPORTED (-1)",
            )
        }
        if (txSupported) {
            into[Variables.NETWORK_UPLOAD_TOTAL_BYTES] = MetricValue.Number(txBytes.toDouble())
        } else {
            into.putUnavailable(
                Variables.NETWORK_UPLOAD_TOTAL_BYTES,
                UnavailableReason.NOT_SUPPORTED,
                "TrafficStats.getTotalTxBytes() returned UNSUPPORTED (-1)",
            )
        }

        val downloadBps: Double?
        val uploadBps: Double?
        val rateUnavailableDetail: String?
        val elapsedMs = nowMs - previousAtMs
        val previousRx = previousRxBytes
        val previousTx = previousTxBytes
        when {
            !rxSupported && !txSupported -> {
                downloadBps = null
                uploadBps = null
                rateUnavailableDetail = "TrafficStats totals unsupported on this device"
            }

            previousRx == null || previousTx == null -> {
                downloadBps = null
                uploadBps = null
                rateUnavailableDetail =
                    "first TrafficStats sample; a throughput rate requires two samples"
            }

            rxBytes < previousRx || txBytes < previousTx -> {
                downloadBps = null
                uploadBps = null
                rateUnavailableDetail =
                    "TrafficStats counters decreased (device reboot?); re-priming instead of reporting a bogus rate"
            }

            else -> {
                downloadBps = if (rxSupported) TrafficRateMath.rateBps(previousRx, rxBytes, elapsedMs) else null
                uploadBps = if (txSupported) TrafficRateMath.rateBps(previousTx, txBytes, elapsedMs) else null
                rateUnavailableDetail = null
            }
        }
        if (rxSupported) previousRxBytes = rxBytes
        if (txSupported) previousTxBytes = txBytes
        previousAtMs = nowMs

        if (downloadBps == null) {
            into.putUnavailable(
                Variables.NETWORK_DOWNLOAD_BPS,
                UnavailableReason.NO_DATA,
                rateUnavailableDetail ?: "download rate unavailable",
            )
        } else {
            into[Variables.NETWORK_DOWNLOAD_BPS] = MetricValue.Number(downloadBps)
        }
        if (uploadBps == null) {
            into.putUnavailable(
                Variables.NETWORK_UPLOAD_BPS,
                UnavailableReason.NO_DATA,
                rateUnavailableDetail ?: "upload rate unavailable",
            )
        } else {
            into[Variables.NETWORK_UPLOAD_BPS] = MetricValue.Number(uploadBps)
        }

        publishProfilePresentation(into, downloadBps, uploadBps, rateUnavailableDetail)
        publishConnection(into)
    }

    private fun publishProfilePresentation(
        into: MutableMap<String, MetricValue>,
        downloadBps: Double?,
        uploadBps: Double?,
        rateUnavailableDetail: String?,
    ) {
        val profile = environment.activeProfile()
        val displayUnit = profile?.let { NetworkDisplayUnit.fromWire(it.networkDisplayUnit) }
            ?: NetworkDisplayUnit.AUTO_BYTES
        val percentMode = profile?.let { NetworkPercentMode.fromWire(it.networkPercentMode) }
            ?: NetworkPercentMode.TOTAL

        if (downloadBps == null) {
            into.putUnavailable(
                Variables.NETWORK_DISPLAY_DOWNLOAD,
                UnavailableReason.NO_DATA,
                rateUnavailableDetail ?: "download rate unavailable",
            )
        } else {
            into[Variables.NETWORK_DISPLAY_DOWNLOAD] = MetricValue.Text(NetworkFormat.display(downloadBps, displayUnit))
        }
        if (uploadBps == null) {
            into.putUnavailable(
                Variables.NETWORK_DISPLAY_UPLOAD,
                UnavailableReason.NO_DATA,
                rateUnavailableDetail ?: "upload rate unavailable",
            )
        } else {
            into[Variables.NETWORK_DISPLAY_UPLOAD] = MetricValue.Text(NetworkFormat.display(uploadBps, displayUnit))
        }

        val measured = NetworkFormat.measuredBytesPerSecond(percentMode, downloadBps, uploadBps)
        if (measured == null) {
            val detail = rateUnavailableDetail ?: "no measured rate for the ${percentMode.wire} percentage mode"
            into.putUnavailable(Variables.NETWORK_PROFILE_PERCENT, UnavailableReason.NO_DATA, detail)
            into.putUnavailable(Variables.NETWORK_PROFILE_PERCENT_TEXT, UnavailableReason.NO_DATA, detail)
            return
        }
        val reference = profile?.let {
            NetworkFormat.referenceBytesPerSecond(it.networkReferenceValue, it.networkReferenceUnit)
        } ?: DEFAULT_REFERENCE_BYTES_PER_SECOND
        if (reference <= 0.0) {
            val detail = "profile NetworkReferenceValue/Unit are invalid " +
                "(value=${profile?.networkReferenceValue}, unit=${profile?.networkReferenceUnit})"
            into.putUnavailable(Variables.NETWORK_PROFILE_PERCENT, UnavailableReason.DISABLED, detail)
            into.putUnavailable(Variables.NETWORK_PROFILE_PERCENT_TEXT, UnavailableReason.DISABLED, detail)
            return
        }
        val percent = NetworkFormat.percent(measured, reference)
        into[Variables.NETWORK_PROFILE_PERCENT] = MetricValue.Number(percent)
        into[Variables.NETWORK_PROFILE_PERCENT_TEXT] = MetricValue.Text(NetworkFormat.percentText(percent, percentMode))
    }

    private fun publishConnection(into: MutableMap<String, MetricValue>) {
        val manager = connectivityManager
        val network = manager?.activeNetwork
        val capabilities = network?.let { manager.getNetworkCapabilities(it) }
        val linkProperties = network?.let { manager.getLinkProperties(it) }

        val type = when {
            capabilities == null -> "NONE"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "BLUETOOTH"
            else -> "OTHER"
        }
        into[Variables.NETWORK_TYPE] = MetricValue.Text(type)

        val connected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        into[Variables.NETWORK_CONNECTED] = MetricValue.Number(if (connected) 1.0 else 0.0)

        val interfaceName = linkProperties?.interfaceName
        if (interfaceName.isNullOrBlank()) {
            into.putUnavailable(
                Variables.NETWORK_INTERFACE,
                UnavailableReason.NOT_AVAILABLE_ON_DEVICE,
                "ConnectivityManager.getLinkProperties(activeNetwork) exposes no interface name",
            )
        } else {
            into[Variables.NETWORK_INTERFACE] = MetricValue.Text(interfaceName)
        }

    }

    private companion object {
        /** ECP's `HudProfile` default reference: 100 MB/s, used when no scheme is active yet. */
        const val DEFAULT_REFERENCE_BYTES_PER_SECOND = 100.0 * 1_000_000.0
    }
}
