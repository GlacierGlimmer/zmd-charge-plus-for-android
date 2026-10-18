package com.glacierglimmer.endfieldchargeplus.core.metrics

/** A stored choice applies only to the exact data selected in one notice. */
class DataWarningPreferences(private val read: () -> Set<String>,private val write: (Set<String>) -> Boolean) {
    private fun key(profileId: String,variable: String): String =
        if(Regex("[A-Za-z_][A-Za-z0-9_.]*").matches(variable)) variable.lowercase(java.util.Locale.ROOT)
        else "expression:$profileId:$variable"
    fun filter(profileId: String,missing: List<String>): List<String> {
        val ignored=read()
        return missing.filterNot { key(profileId,it) in ignored }
    }
    fun ignore(profileId: String,selected: List<String>): Boolean =
        write(read().toSet()+selected.map { key(profileId,it) })
}
