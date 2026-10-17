package com.simhadri.winentry.utils

import android.content.Context

/**
 * Grouping used by the Daily Sale Summary report: four fixed slots
 * (IML, BEER, RTD, and one user-named extra group) to which product type codes
 * are assigned. Beer always holds exactly "B" and cannot be reassigned.
 *
 * A type with no stored assignment falls into IML (Mixer, M, into RTD), so a newly
 * imported product type never silently drops out of a report that has to be submitted daily.
 */
data class SaleSummaryGroups(
    val extraName: String,
    val assignment: Map<String, Slot>,
    val bySize: Map<Slot, Boolean>
) {
    enum class Slot { IML, BEER, RTD, EXTRA, NONE }

    fun slotFor(type: String): Slot {
        val t = type.uppercase()
        if (t == BEER_TYPE) return Slot.BEER
        return assignment[t] ?: DEFAULT_ASSIGNMENT[t] ?: Slot.IML
    }

    fun label(slot: Slot): String = when (slot) {
        Slot.IML   -> "IML"
        Slot.BEER  -> "BEER"
        Slot.RTD   -> "RTD"
        Slot.EXTRA -> extraName.ifBlank { "OTHERS" }
        Slot.NONE  -> "Not reported"
    }

    fun showsSizes(slot: Slot): Boolean = bySize[slot] ?: (slot == Slot.IML)

    companion object {
        const val BEER_TYPE = "B"
        val REPORT_ORDER = listOf(Slot.IML, Slot.BEER, Slot.RTD, Slot.EXTRA)

        private const val PREFS        = "sale_summary_groups"
        private const val KEY_EXTRA    = "extra_name"
        private const val KEY_ASSIGN   = "assignment"
        private const val KEY_BY_SIZE  = "by_size"

        private val DEFAULT_ASSIGNMENT = mapOf("M" to Slot.RTD)

        fun load(context: Context): SaleSummaryGroups {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val assignment = p.getString(KEY_ASSIGN, null)?.let(::parsePairs)
                ?.mapNotNull { (k, v) -> runCatching { k to Slot.valueOf(v) }.getOrNull() }
                ?.toMap()
                ?: emptyMap()
            val bySize = p.getString(KEY_BY_SIZE, null)?.let(::parsePairs)
                ?.mapNotNull { (k, v) -> runCatching { Slot.valueOf(k) to (v == "1") }.getOrNull() }
                ?.toMap()
                ?: emptyMap()
            return SaleSummaryGroups(p.getString(KEY_EXTRA, "") ?: "", assignment, bySize)
        }

        fun save(context: Context, g: SaleSummaryGroups) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_EXTRA, g.extraName.trim())
                .putString(KEY_ASSIGN, g.assignment.entries.joinToString(",") { "${it.key}=${it.value.name}" })
                .putString(KEY_BY_SIZE, g.bySize.entries.joinToString(",") { "${it.key.name}=${if (it.value) 1 else 0}" })
                .apply()
        }

        private fun parsePairs(s: String): List<Pair<String, String>> =
            s.split(',').mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
            }
    }
}
