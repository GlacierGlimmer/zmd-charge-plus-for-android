package com.glacierglimmer.endfieldchargeplus.core.metrics

/** One alert per continuous incident; recovery re-arms it. The caller owns its clock and UI. */
class WarningIncidentGate(private val graceMs: Long=5000) {
    private val since=mutableMapOf<String,Long>()
    private val reported=mutableMapOf<String,String>()
    fun shouldPresent(profileId: String,missing: List<String>,nowMs: Long,canPresent: Boolean): Boolean {
        if(missing.isEmpty()) { since.remove(profileId); reported.remove(profileId); return false }
        val first=since.getOrPut(profileId) { nowMs }
        if(nowMs-first<graceMs || !canPresent) return false
        val signature=missing.sorted().joinToString("|")
        if(reported[profileId]==signature) return false
        reported[profileId]=signature; return true
    }
    fun clear() { since.clear(); reported.clear() }
}