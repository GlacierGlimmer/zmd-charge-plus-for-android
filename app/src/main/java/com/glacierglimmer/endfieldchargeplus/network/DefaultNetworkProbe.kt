package com.glacierglimmer.endfieldchargeplus.network

import com.glacierglimmer.endfieldchargeplus.core.model.ProbeProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Public-API packet probe: ICMP-style, TCP connect and UDP send/receive.
 *
 * Android specifics the desktop editions do not have:
 *  * an unprivileged app cannot send raw ICMP. [InetAddress.isReachable] is documented as
 *    unreliable and may silently fall back to a TCP echo on port 7, so a negative ICMP result means
 *    "no answer", not "host down". TCP connect is therefore the trustworthy path; ICMP is kept for
 *    compatibility and reported honestly ([isProtocolLikelySupported] documents the caveat).
 *  * UDP receive is the only place where an ICMP port-unreachable from the peer's stack reaches us
 *    as [PortUnreachableException]. That kernel message proves the host is alive, so it is counted
 *    as reachable (with the measured round trip), which is what an Android HUD wants to show.
 *
 * All blocking IO runs on [Dispatchers.IO] inside [runInterruptible] so a cancelled collection
 * interrupts the blocked thread, and every socket is closed through `use { }`.
 */
class DefaultNetworkProbe : NetworkProbe {

    override suspend fun probe(
        target: String,
        protocol: ProbeProtocol,
        port: Int,
        timeoutMs: Int,
    ): ProbeResult {
        val startedAt = System.currentTimeMillis()
        val normalized = target.trim()
        if (normalized.isEmpty()) {
            return failure(startedAt, normalized, protocol, ERROR_INVALID)
        }
        val timeout = timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        val effectivePort = if (port in 1..65535) port else DEFAULT_PORT

        return try {
            runInterruptible(Dispatchers.IO) {
                val address = try {
                    InetAddress.getByName(normalized)
                } catch (_: UnknownHostException) {
                    return@runInterruptible failure(startedAt, normalized, protocol, ERROR_DNS)
                } catch (_: IOException) {
                    return@runInterruptible failure(startedAt, normalized, protocol, ERROR_DNS)
                }
                when (protocol) {
                    ProbeProtocol.ICMP -> probeIcmp(startedAt, normalized, protocol, address, timeout)
                    ProbeProtocol.TCP -> probeTcp(startedAt, normalized, protocol, address, effectivePort, timeout)
                    ProbeProtocol.UDP -> probeUdp(startedAt, normalized, protocol, address, effectivePort, timeout)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: InterruptedException) {
            failure(startedAt, normalized, protocol, ERROR_CANCELLED)
        } catch (_: Exception) {
            failure(startedAt, normalized, protocol, ERROR_UNREACHABLE)
        }
    }

    /**
     * ICMP is best-effort on Android: raw sockets are not available to a normal app, so this uses
     * the public [InetAddress.isReachable] helper. A `false` answer is reported as a timeout because
     * the platform API cannot distinguish "no echo reply" from "network unreachable".
     */
    private fun probeIcmp(
        startedAt: Long,
        target: String,
        protocol: ProbeProtocol,
        address: InetAddress,
        timeoutMs: Int,
    ): ProbeResult {
        val start = System.nanoTime()
        val reachable = try {
            address.isReachable(timeoutMs)
        } catch (_: Exception) {
            false
        }
        val elapsed = elapsedMs(start)
        return if (reachable) {
            ProbeResult(startedAt, target, protocol, success = true, latencyMs = elapsed)
        } else {
            ProbeResult(
                timestampMs = startedAt,
                target = target,
                protocol = protocol,
                success = false,
                latencyMs = null,
                timeout = true,
                error = ERROR_TIMEOUT,
            )
        }
    }

    private fun probeTcp(
        startedAt: Long,
        target: String,
        protocol: ProbeProtocol,
        address: InetAddress,
        port: Int,
        timeoutMs: Int,
    ): ProbeResult {
        return try {
            Socket().use { socket ->
                val start = System.nanoTime()
                socket.connect(InetSocketAddress(address, port), timeoutMs)
                ProbeResult(startedAt, target, protocol, success = true, latencyMs = elapsedMs(start))
            }
        } catch (_: SocketTimeoutException) {
            ProbeResult(startedAt, target, protocol, false, null, timeout = true, error = ERROR_TIMEOUT)
        } catch (_: Exception) {
            failure(startedAt, target, protocol, ERROR_UNREACHABLE)
        }
    }

    private fun probeUdp(
        startedAt: Long,
        target: String,
        protocol: ProbeProtocol,
        address: InetAddress,
        port: Int,
        timeoutMs: Int,
    ): ProbeResult {
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMs
                val payload = byteArrayOf(0x00)
                val request = DatagramPacket(payload, payload.size, address, port)
                val start = System.nanoTime()
                socket.send(request)
                val reply = DatagramPacket(ByteArray(MAX_UDP_REPLY_BYTES), MAX_UDP_REPLY_BYTES)
                try {
                    socket.receive(reply)
                    ProbeResult(startedAt, target, protocol, success = true, latencyMs = elapsedMs(start))
                } catch (_: PortUnreachableException) {
                    // The peer's stack answered with ICMP port-unreachable: the host is alive.
                    ProbeResult(startedAt, target, protocol, success = true, latencyMs = elapsedMs(start))
                } catch (e: SocketException) {
                    if (isConnectionRefused(e)) {
                        ProbeResult(startedAt, target, protocol, success = true, latencyMs = elapsedMs(start))
                    } else {
                        failure(startedAt, target, protocol, ERROR_UNREACHABLE)
                    }
                }
            }
        } catch (_: SocketTimeoutException) {
            ProbeResult(startedAt, target, protocol, false, null, timeout = true, error = ERROR_TIMEOUT)
        } catch (_: Exception) {
            failure(startedAt, target, protocol, ERROR_UNREACHABLE)
        }
    }

    /**
     * TCP and UDP are ordinary public APIs and are expected to work. ICMP returns `true` because a
     * probe is still attempted, but callers must not treat it as a guarantee: see the class KDoc.
     * Many mobile networks and vendor ROMs drop UDP entirely, so a negative UDP result is weak
     * evidence about the target.
     */
    override fun isProtocolLikelySupported(protocol: ProbeProtocol): Boolean = when (protocol) {
        ProbeProtocol.ICMP, ProbeProtocol.TCP, ProbeProtocol.UDP -> true
    }

    private fun failure(
        startedAt: Long,
        target: String,
        protocol: ProbeProtocol,
        error: String,
    ): ProbeResult = ProbeResult(
        timestampMs = startedAt,
        target = target,
        protocol = protocol,
        success = false,
        latencyMs = null,
        error = error,
    )

    private fun isConnectionRefused(e: SocketException): Boolean {
        val text = e.message?.lowercase() ?: return false
        return "econnrefused" in text || "connection refused" in text || "port unreachable" in text
    }

    private fun elapsedMs(startNanos: Long): Double =
        (System.nanoTime() - startNanos) / NANOS_PER_MILLI

    companion object {
        /** Log tag for the rare warnings this probe emits. */
        const val TAG = "NetworkProbe"

        /** Port used when a target does not carry a usable one (HTTPS default). */
        const val DEFAULT_PORT = 443

        /** Machine readable [ProbeResult.error] codes; the collector maps them to status keys. */
        const val ERROR_DNS = "dns"
        const val ERROR_TIMEOUT = "timeout"
        const val ERROR_UNREACHABLE = "unreachable"
        const val ERROR_UNSUPPORTED = "unsupported"
        const val ERROR_INVALID = "invalid"
        const val ERROR_CANCELLED = "cancelled"

        private const val MIN_TIMEOUT_MS = 100
        private const val MAX_TIMEOUT_MS = 30_000
        private const val MAX_UDP_REPLY_BYTES = 512
        private const val NANOS_PER_MILLI = 1_000_000.0
    }
}
