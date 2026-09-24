package com.simhadri.winentry.utils

import java.time.LocalDate

/**
 * The one date parser for imported and cloud dates. Returns yyyy-MM-dd, or null when the
 * value is blank or not a real date; a blank or bad date never silently becomes today.
 *
 * Accepts ISO (yyyy-M-d), day-first d/M/yyyy, d-M-yyyy, d.M.yyyy (and 2-digit years),
 * d-MMM-yyyy / d MMM yyyy, and Sheets/Excel serials for 2000–2049. Never month-first.
 */
object StrictDate {

    private const val SERIAL_MIN = 36526.0   // 2000-01-01
    private const val SERIAL_MAX = 54787.0   // 2049-12-31
    private val EXCEL_EPOCH: LocalDate = LocalDate.of(1899, 12, 30)
    private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN",
        "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    private val ISO = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})$""")
    private val DAY_FIRST = Regex("""^(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{2}|\d{4})$""")
    private val DAY_MON_NAME = Regex("""^(\d{1,2})[\s\-]([A-Za-z]{3})[A-Za-z]*[\s\-,]+(\d{4})$""")

    fun parse(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        ISO.matchEntire(s)?.let { m ->
            val (y, mo, d) = m.destructured
            return of(y.toInt(), mo.toInt(), d.toInt())
        }
        DAY_FIRST.matchEntire(s)?.let { m ->
            val (d, mo, y) = m.destructured
            val year = if (y.length == 2) 2000 + y.toInt() else y.toInt()
            return of(year, mo.toInt(), d.toInt())
        }
        DAY_MON_NAME.matchEntire(s)?.let { m ->
            val (d, mon, y) = m.destructured
            val month = MONTHS.indexOf(mon.uppercase()) + 1
            return if (month == 0) null else of(y.toInt(), month, d.toInt())
        }
        val serial = s.toDoubleOrNull() ?: return null
        if (serial < SERIAL_MIN || serial > SERIAL_MAX) return null
        return EXCEL_EPOCH.plusDays(kotlin.math.floor(serial).toLong()).toString()
    }

    private fun of(y: Int, m: Int, d: Int): String? {
        if (y !in 2000..2099) return null
        return try { LocalDate.of(y, m, d).toString() } catch (_: Exception) { null }
    }
}
