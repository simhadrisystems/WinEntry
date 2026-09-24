package com.simhadri.winentry.utils

import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Shared Excel cell reader for imports. Formula cells read their cached result (a SUM or
 * =B2*12 cell used to read as blank/0), and numeric ids are kept whole (no Int overflow,
 * no "12345.0").
 */
object ExcelCells {

    private fun type(cell: Cell): CellType =
        if (cell.cellType == CellType.FORMULA) cell.cachedFormulaResultType else cell.cellType

    fun text(cell: Cell?): String? {
        if (cell == null) return null
        return when (type(cell)) {
            CellType.STRING -> cell.stringCellValue.trim().ifEmpty { null }
            CellType.NUMERIC -> plain(cell.numericCellValue)
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            else -> null
        }
    }

    fun number(cell: Cell?): Double? {
        if (cell == null) return null
        return when (type(cell)) {
            CellType.NUMERIC -> cell.numericCellValue
            CellType.STRING -> cell.stringCellValue.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
            else -> null
        }
    }

    /** Blank = 0; null when the cell holds something that is not a whole, non-negative number. */
    fun quantity(cell: Cell?): Int? {
        if (cell == null || text(cell) == null) return 0
        val n = number(cell) ?: return null
        if (n < 0 || n % 1.0 != 0.0 || n > Int.MAX_VALUE) return null
        return n.toInt()
    }

    /** yyyy-MM-dd or null; accepts real date cells, serials and text dates (see [StrictDate]). */
    fun date(cell: Cell?): String? {
        if (cell == null) return null
        if (type(cell) == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return try {
                StrictDate.parse(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cell.dateCellValue))
            } catch (_: Exception) { null }
        }
        return StrictDate.parse(text(cell))
    }

    private fun plain(d: Double): String =
        BigDecimal.valueOf(d).stripTrailingZeros().toPlainString()
}
