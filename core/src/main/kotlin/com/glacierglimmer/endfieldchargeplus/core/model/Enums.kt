package com.glacierglimmer.endfieldchargeplus.core.model

/**
 * Enumerations shared by the configuration model, the HUD renderer and the island providers.
 *
 * The on-disk names are kept identical to the Windows/Linux/macOS editions wherever the concept
 * exists there, so that an exported ECP configuration keeps its meaning after import.
 */

/** Full animation (`Full`) or simple animation (`Simple`). */
enum class AnimationMode(val wire: String) {
    FULL("Full"),
    SIMPLE("Simple"),
    ;

    companion object {
        fun fromWire(value: String?): AnimationMode =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: FULL
    }
}

/** Windows preset anchor positions, reused by the Android overlay where meaningful. */
enum class HudPosition {
    TOP_LEFT,
    TOP_CENTER,
    TOP_RIGHT,
    CENTER_LEFT,
    CENTER,
    CENTER_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_CENTER,
    BOTTOM_RIGHT,
    ;

    companion object {
        /** Accepts the Windows `TopCenter` style spelling as well as `TOP_CENTER`. */
        fun fromWire(value: String?): HudPosition {
            val normalized = value?.replace("_", "")?.replace("-", "")?.trim()
            return entries.firstOrNull { it.name.replace("_", "").equals(normalized, ignoreCase = true) }
                ?: TOP_CENTER
        }
    }

    /** The Windows spelling (`TopCenter`) used when exporting a cross-platform config. */
    val wire: String
        get() = name.split("_").joinToString(separator = "") { part ->
            part.lowercase().replaceFirstChar { it.uppercaseChar() }
        }
}

/** Preset anchor or free X/Y coordinates. */
enum class HudPositionMode(val wire: String) {
    PRESET("Preset"),
    CUSTOM_COORDINATES("CustomCoordinates"),
    ;

    companion object {
        fun fromWire(value: String?): HudPositionMode =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: PRESET
    }
}

/** The two top-level output modes of the Android edition. */
enum class DisplayMode(val wire: String) {
    /** A real `TYPE_APPLICATION_OVERLAY` window managed by a foreground service. */
    OVERLAY("Overlay"),

    /** A platform island / live update provider. */
    ISLAND("Island"),
    ;

    companion object {
        fun fromWire(value: String?): DisplayMode =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: OVERLAY
    }
}

/** Which island backend the user selected. */
enum class IslandProviderKind(val wire: String) {
    /** Pick the best available provider for this device. */
    AUTO("Auto"),

    /** The official Android promoted ongoing notification / live update path. */
    ANDROID_SYSTEM("AndroidSystem"),

    /** Xiaomi HyperOS HyperIsland (超级岛). */
    XIAOMI_HYPER_ISLAND("XiaomiHyperIsland"),

    /** No island output. */
    NONE("None"),
    ;

    companion object {
        fun fromWire(value: String?): IslandProviderKind =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: AUTO
    }
}

/** Interface used by the network packet probe. */
enum class ProbeProtocol(val wire: String) {
    ICMP("ICMP"),
    TCP("TCP"),
    UDP("UDP"),
    ;

    companion object {
        fun fromWire(value: String?): ProbeProtocol =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: ICMP
    }
}

/** How the network profile renders traffic: automatic byte units or Mbps. */
enum class NetworkDisplayUnit(val wire: String) {
    AUTO_BYTES("AutoBytes"),
    MBPS("Mbps"),
    ;

    companion object {
        fun fromWire(value: String?): NetworkDisplayUnit =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: AUTO_BYTES
    }
}

/** Which traffic direction feeds the network progress bar. */
enum class NetworkPercentMode(val wire: String) {
    TOTAL("Total"),
    DOWNLOAD("Download"),
    UPLOAD("Upload"),
    MAX("Max"),
    ;

    companion object {
        fun fromWire(value: String?): NetworkPercentMode =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: TOTAL
    }
}

/** User interface language. `AUTO` follows the Android system locale. */
enum class AppLanguage(val wire: String) {
    AUTO("Auto"),
    SIMPLIFIED_CHINESE("zh-CN"),
    ENGLISH("en-US"),
    ;

    companion object {
        fun fromWire(value: String?): AppLanguage =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: AUTO
    }
}

/** Sampling tiers used by the unified sampler. Intervals are milliseconds. */
enum class SamplingTier(val intervalMs: Long) {
    /** Latency-style data. */
    FAST(500L),

    /** Ordinary system data. */
    NORMAL(1_000L),

    /** Slow counters, storage, and remote APIs. */
    SLOW(5_000L),

    /** Very slow data such as DeepSeek balance. */
    IDLE(30_000L),
}
