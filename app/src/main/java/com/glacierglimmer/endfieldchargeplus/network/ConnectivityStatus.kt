package com.glacierglimmer.endfieldchargeplus.network

import android.content.Context
import android.net.ConnectivityManager

/**
 * Cheap "is there any active network?" check used to avoid pointless outbound requests.
 *
 * It reads only public APIs (no permission required), and it fails open: when the service is
 * missing or the platform refuses the call, the answer is `true` so a collector still attempts its
 * work instead of silently reporting a network error.
 */
object ConnectivityStatus {

    /** True when at least one network is currently active; fails open when the answer is unknown. */
    fun isOnline(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        manager.activeNetwork != null
    }.getOrDefault(true)
}
