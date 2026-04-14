package com.simple.simpleinventory.utils

/**
 * Display names for product type codes and size codes.
 *
 * To update a label: edit the value in [typeLabels] or [sizeLabels] below and rebuild.
 * Codes themselves (W, QQ, etc.) are never changed — they remain the DB and logic keys.
 * If a code has no entry here, the raw code is returned as-is (safe fallback).
 */
object TypeLabels {

    // ── Edit these values to match your business ──────────────────────────────
    private val typeLabels = mapOf(
        "W" to "Whisky",
        "Y" to "Brandy",
        "R" to "Rum",
        "B" to "Beer",
        "V" to "Vodka",
        "M" to "Mixer",
        "E" to "Wine",
        "S" to "Scotch",
        "G" to "Gin"
    )

    private val sizeLabels = mapOf(
        "QQ" to "QQ",
        "PP" to "PP",
        "NN" to "NN",
        "DD" to "DD"
    )
    // ─────────────────────────────────────────────────────────────────────────

    /** Ordered list of all type codes. */
    val typeCodes = listOf("W", "Y", "R", "B", "V", "M", "E", "S", "G")

    /**
     * Returns the display label for a type code.
     * Falls back to the raw code if no label is defined.
     */
    fun getType(code: String): String =
        typeLabels[code.uppercase()] ?: code.uppercase()

    /**
     * Returns the display label for a size code.
     * Falls back to the raw code if no label is defined.
     */
    fun getSize(code: String): String =
        sizeLabels[code.uppercase()] ?: code.uppercase()

    /**
     * Dropdown display string: "W  —  Whisky"
     * Shows code alongside label so the user can still see the underlying code.
     */
    fun typeDisplay(code: String): String {
        val label = getType(code)
        return if (label == code.uppercase()) code else "$code  —  $label"
    }

    /**
     * All type codes as display strings, ready for an ArrayAdapter.
     * e.g. ["W  —  Whisky", "Y  —  Brandy", ...]
     */
    val typeDisplayItems: Array<String>
        get() = typeCodes.map { typeDisplay(it) }.toTypedArray()

    /**
     * Given a display string from the dropdown ("W  —  Whisky"),
     * returns just the code character ("W").
     */
    fun codeFromDisplay(display: String): String =
        display.trim().take(1).uppercase()
}
