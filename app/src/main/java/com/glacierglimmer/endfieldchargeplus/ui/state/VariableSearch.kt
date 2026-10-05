package com.glacierglimmer.endfieldchargeplus.ui.state

/**
 * One row of the Variable Library, already flattened out of the core registry.
 *
 * The UI keeps its own lightweight projection so that the pure search/grouping logic stays
 * unit-testable on the JVM without depending on the engine module.
 */
data class VariableRow(
    val name: String,
    val categoryKey: String,
    /** Short human label from the catalog ("CPU Usage" / "CPU 使用率"). */
    val label: String = "",
    /** Localized type label ("数值" / "Number", …). */
    val typeLabel: String,
    val unit: String,
    val description: String,
    val formats: String,
    val androidNote: String = "",
    /** True when the current snapshot has a real reading for this variable. */
    val available: Boolean = false,
    /** False when Android has no honest way to collect this metric at all. */
    val supportedOnAndroid: Boolean = true,
    /** Localized reason when the variable has no reading. */
    val unavailableReason: String = "",
)

/**
 * Search and grouping for the Variable Library.
 *
 * Mirrors the behaviour of the desktop library (`HudCustomizerView.RefreshVariableList`): the query
 * matches the key, the display text or the category, the category filter is exact, and the
 * original registry order is preserved.
 */
object VariableSearch {

    const val ALL_CATEGORIES = ""

    fun filter(rows: List<VariableRow>, query: String, category: String?): List<VariableRow> {
        val normalized = query.trim()
        return rows.filter { row ->
            (category.isNullOrBlank() || row.categoryKey == category) &&
                (normalized.isEmpty() || row.matches(normalized))
        }
    }

    /** Distinct category keys in first-seen order. */
    fun categories(rows: List<VariableRow>): List<String> =
        rows.map { it.categoryKey }.distinct()

    /** Groups [rows] by category, keeping the registry order inside each group. */
    fun grouped(rows: List<VariableRow>): List<Pair<String, List<VariableRow>>> =
        rows.groupBy { it.categoryKey }.map { (category, items) -> category to items }

    private fun VariableRow.matches(query: String): Boolean =
        name.contains(query, ignoreCase = true) ||
            categoryKey.contains(query, ignoreCase = true) ||
            description.contains(query, ignoreCase = true) ||
            typeLabel.contains(query, ignoreCase = true)

    /** The canonical template token of a variable, as inserted into a template field. */
    fun token(row: VariableRow): String = "{" + row.name + "}"
}
