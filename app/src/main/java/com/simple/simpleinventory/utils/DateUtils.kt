package com.simple.simpleinventory.util

import android.util.Log

/**
 * Date utility functions shared across the sync layer and any other code
 * that needs to convert between yyyy-MM-dd strings and Google Sheets
 * date serial numbers.
 *
 * Google Sheets stores dates as days elapsed since its epoch: 30 Dec 1899.
 * When a cell is written with valueInputOption = USER_ENTERED, Sheets parses
 * the string as a date and stores the serial internally.  Reading back with
 * valueRenderOption = UNFORMATTED_VALUE returns the serial (e.g. 46087.0).
 *
 * Using the serial as the composite key (instead of converting back to a
 * date string) is locale-independent and format-independent — it does not
 * matter how the user has formatted the date column in Sheets.
 */
object DateUtils {

    private const val TAG = "DateUtils"

    // Sheets epoch in milliseconds (30 Dec 1899, UTC)
    private val SHEETS_EPOCH_MS: Long by lazy {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.set(1899, 11, 30, 0, 0, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        cal.timeInMillis
    }

    /**
     * Convert a yyyy-MM-dd string to a Google Sheets date serial number string.
     *
     * Example: "2026-03-06" → "46087"
     *
     * Returns the original string unchanged if parsing fails — this provides
     * safe fallback for cells written as plain text (e.g. before the RAW→
     * USER_ENTERED migration), so key lookups still work during the transition.
     */
    fun dateStringToSerial(dateStr: String): String {
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val date   = sdf.parse(dateStr) ?: return dateStr
            val serial = (date.time - SHEETS_EPOCH_MS) / 86_400_000L
            serial.toString()
        } catch (e: Exception) {
            Log.w(TAG, "Could not convert date $dateStr to serial: ${e.message}")
            dateStr
        }
    }

    /**
     * Normalise the raw value returned by UNFORMATTED_VALUE for a Sheets date cell.
     * Returns the integer serial as a string (e.g. "46087.0" → "46087").
     * If the value is not a number (plain-text cell), returns it unchanged.
     */
    fun normaliseSheetDateKey(raw: String): String =
        raw.toDoubleOrNull()?.toLong()?.toString() ?: raw
}
