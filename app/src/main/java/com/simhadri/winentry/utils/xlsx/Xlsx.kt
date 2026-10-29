package com.simhadri.winentry.utils.xlsx

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.Calendar
import java.util.Date
import java.util.TreeMap

/*
 * A small XLSX reader/writer that replaced Apache POI in vc22. POI and its
 * xmlbeans schemas were half of the app's DEX and had to be kept whole for
 * reflection, which held Play's optimisation score under 25%.
 *
 * The API mirrors the part of POI's usermodel the app used (same class,
 * property and enum names), so call sites only changed their imports.
 * It is deliberately not a general POI clone: no formula evaluation, no
 * rich text, no images, no .xls.
 */

enum class CellType { NUMERIC, STRING, FORMULA, BLANK, BOOLEAN, ERROR }

enum class HorizontalAlignment(internal val xml: String?) {
    GENERAL(null), LEFT("left"), CENTER("center"), RIGHT("right")
}

enum class VerticalAlignment(internal val xml: String?) {
    TOP("top"), CENTER("center"), BOTTOM(null)
}

enum class FillPatternType { NO_FILL, SOLID_FOREGROUND }

enum class BorderStyle(internal val xml: String?) { NONE(null), THIN("thin"), MEDIUM("medium") }

/** Excel's legacy palette; written as `indexed` colours, as POI did. */
enum class IndexedColors(val index: Short) {
    BLACK(8), WHITE(9), BLUE(12), PALE_BLUE(44), LIGHT_BLUE(48), LIGHT_GREEN(42),
    LIGHT_TURQUOISE(41), LIGHT_YELLOW(43), LIGHT_ORANGE(52), GREY_25_PERCENT(22), GREY_50_PERCENT(23),
    AUTOMATIC(64)
}

data class CellRangeAddress(val firstRow: Int, val lastRow: Int, val firstColumn: Int, val lastColumn: Int) {
    internal fun ref() = CellRef.name(firstRow, firstColumn) + ":" + CellRef.name(lastRow, lastColumn)
}

class Font internal constructor(internal val index: Int) {
    var bold = false
    var italic = false
    var fontHeightInPoints: Short = 11
    var color: Short = IndexedColors.BLACK.index
    var fontName = "Calibri"
}

class CellStyle internal constructor(internal val index: Int, internal val workbook: XlsxWorkbook) {
    internal var font: Font? = null
    var alignment = HorizontalAlignment.GENERAL
    var verticalAlignment = VerticalAlignment.BOTTOM
    var fillForegroundColor: Short = IndexedColors.AUTOMATIC.index
    var fillPattern = FillPatternType.NO_FILL
    var borderTop = BorderStyle.NONE
    var borderBottom = BorderStyle.NONE
    var borderLeft = BorderStyle.NONE
    var borderRight = BorderStyle.NONE
    var dataFormat: Short = 0
    var wrapText = false

    fun setFont(font: Font) { this.font = font }

    val dataFormatString: String get() = workbook.formatCode(dataFormat.toInt())
}

class DataFormat internal constructor(private val workbook: XlsxWorkbook) {
    fun getFormat(code: String): Short = workbook.formatId(code).toShort()
}

class XlsxWorkbook() : Closeable {
    internal val sheets = mutableListOf<Sheet>()
    internal val styles = mutableListOf<CellStyle>()
    internal val fonts = mutableListOf<Font>()
    internal val customFormats = LinkedHashMap<Int, String>()
    internal var date1904 = false

    init {
        fonts += Font(0)
        styles += CellStyle(0, this)
    }

    /** Reads an .xlsx stream (the whole file is buffered; see [XlsxReader]). */
    constructor(input: InputStream) : this() {
        XlsxReader.read(input, this)
    }

    val numberOfSheets: Int get() = sheets.size

    fun createSheet(name: String = "Sheet${sheets.size}"): Sheet =
        Sheet(this, name).also { sheets += it }

    fun getSheetAt(index: Int): Sheet = sheets[index]

    fun getSheet(name: String): Sheet? = sheets.firstOrNull { it.sheetName.equals(name, ignoreCase = true) }

    fun createFont(): Font = Font(fonts.size).also { fonts += it }

    fun createCellStyle(): CellStyle = CellStyle(styles.size, this).also { styles += it }

    fun createDataFormat() = DataFormat(this)

    fun write(out: OutputStream) = XlsxWriter.write(this, out)

    override fun close() {}

    internal fun styleAt(index: Int): CellStyle = styles.getOrElse(index) { styles[0] }

    internal fun formatCode(id: Int): String = customFormats[id] ?: BUILT_IN_FORMATS[id] ?: "General"

    internal fun formatId(code: String): Int {
        BUILT_IN_FORMATS.entries.firstOrNull { it.value == code }?.let { return it.key }
        customFormats.entries.firstOrNull { it.value == code }?.let { return it.key }
        val id = maxOf(FIRST_CUSTOM_FORMAT, (customFormats.keys.maxOrNull() ?: 0) + 1)
        customFormats[id] = code
        return id
    }

    internal companion object {
        const val FIRST_CUSTOM_FORMAT = 164
        val BUILT_IN_FORMATS = mapOf(
            0 to "General", 1 to "0", 2 to "0.00", 3 to "#,##0", 4 to "#,##0.00",
            9 to "0%", 10 to "0.00%", 11 to "0.00E+00", 12 to "# ?/?", 13 to "# ??/??",
            14 to "m/d/yy", 15 to "d-mmm-yy", 16 to "d-mmm", 17 to "mmm-yy",
            18 to "h:mm AM/PM", 19 to "h:mm:ss AM/PM", 20 to "h:mm", 21 to "h:mm:ss",
            22 to "m/d/yy h:mm", 37 to "#,##0 ;(#,##0)", 38 to "#,##0 ;[Red](#,##0)",
            39 to "#,##0.00;(#,##0.00)", 40 to "#,##0.00;[Red](#,##0.00)",
            45 to "mm:ss", 46 to "[h]:mm:ss", 47 to "mmss.0", 48 to "##0.0E+0", 49 to "@"
        )
    }
}

class Sheet internal constructor(val workbook: XlsxWorkbook, val sheetName: String) {
    internal val rows = TreeMap<Int, Row>()
    internal val columnWidths = TreeMap<Int, Int>()
    internal val mergedRegions = mutableListOf<CellRangeAddress>()

    fun createRow(index: Int): Row = Row(this, index).also { rows[index] = it }

    fun getRow(index: Int): Row? = rows[index]

    /** Index of the last row, or -1 when the sheet has none (as in POI 5). */
    val lastRowNum: Int get() = if (rows.isEmpty()) -1 else rows.lastKey()

    val physicalNumberOfRows: Int get() = rows.size

    /** [width] is in 1/256ths of a character, as in POI. */
    fun setColumnWidth(column: Int, width: Int) { columnWidths[column] = width }

    fun getColumnWidth(column: Int): Int = columnWidths[column] ?: (8 * 256)

    fun addMergedRegion(region: CellRangeAddress): Int {
        mergedRegions += region
        return mergedRegions.size - 1
    }

    val numMergedRegions: Int get() = mergedRegions.size
}

class Row internal constructor(val sheet: Sheet, val rowNum: Int) {
    internal val cells = TreeMap<Int, Cell>()

    fun createCell(column: Int): Cell = Cell(this, column).also { cells[column] = it }

    fun getCell(column: Int): Cell? = cells[column]

    /** One past the last cell's index, or -1 when the row is empty (as in POI). */
    val lastCellNum: Short get() = if (cells.isEmpty()) -1 else (cells.lastKey() + 1).toShort()

    val physicalNumberOfCells: Int get() = cells.size
}

/**
 * Value getters follow POI: reading a number as text, or text as a number, throws
 * IllegalStateException; a blank cell reads as "" or 0. A formula cell holds the
 * result Excel cached when the file was saved ([cachedFormulaResultType]).
 */
class Cell internal constructor(val row: Row, val columnIndex: Int) {
    val sheet: Sheet get() = row.sheet
    val rowIndex: Int get() = row.rowNum

    var cellType = CellType.BLANK
        private set
    var cachedFormulaResultType = CellType.BLANK
        private set
    private var text: String? = null
    private var number = 0.0
    private var flag = false
    private var formula: String? = null

    var cellStyle: CellStyle = row.sheet.workbook.styles[0]

    private val valueType get() = if (cellType == CellType.FORMULA) cachedFormulaResultType else cellType

    fun setCellValue(value: String?) {
        if (value == null) return setBlank()
        setValue(CellType.STRING); text = value
    }

    fun setCellValue(value: Double) { setValue(CellType.NUMERIC); number = value }

    fun setCellValue(value: Boolean) { setValue(CellType.BOOLEAN); flag = value }

    fun setCellValue(value: Date?) {
        if (value == null) return setBlank()
        setCellValue(ExcelDates.toSerial(value, sheet.workbook.date1904))
    }

    fun setBlank() { setValue(CellType.BLANK); text = null }

    private fun setValue(type: CellType) {
        if (cellType == CellType.FORMULA) cachedFormulaResultType = type else cellType = type
    }

    var cellFormula: String?
        get() = formula
        set(value) {
            formula = value
            if (value == null) {
                cellType = cachedFormulaResultType
            } else if (cellType != CellType.FORMULA) {
                cachedFormulaResultType = CellType.BLANK
                cellType = CellType.FORMULA
            }
        }

    val stringCellValue: String
        get() = when (valueType) {
            CellType.STRING -> text.orEmpty()
            CellType.BLANK -> ""
            else -> throw IllegalStateException("Cannot get a STRING value from a $valueType cell")
        }

    val numericCellValue: Double
        get() = when (valueType) {
            CellType.NUMERIC -> number
            CellType.BLANK -> 0.0
            else -> throw IllegalStateException("Cannot get a NUMERIC value from a $valueType cell")
        }

    val booleanCellValue: Boolean
        get() = when (valueType) {
            CellType.BOOLEAN -> flag
            CellType.BLANK -> false
            else -> throw IllegalStateException("Cannot get a BOOLEAN value from a $valueType cell")
        }

    /** The number as a date in the device's time zone, as POI does; null when blank. */
    val dateCellValue: Date?
        get() = if (valueType == CellType.BLANK) null
        else ExcelDates.fromSerial(numericCellValue, sheet.workbook.date1904)

    internal fun setRead(type: CellType, value: String?, num: Double, bool: Boolean) {
        if (cellType == CellType.FORMULA) cachedFormulaResultType = type else cellType = type
        text = value; number = num; flag = bool
    }

    internal val writeType get() = valueType
    internal val rawText get() = text
    internal val rawNumber get() = number
    internal val rawBoolean get() = flag
}

object DateUtil {
    /** True when [cell] holds a number and its number format shows a date or time. */
    fun isCellDateFormatted(cell: Cell?): Boolean {
        if (cell == null) return false
        val type = if (cell.cellType == CellType.FORMULA) cell.cachedFormulaResultType else cell.cellType
        if (type != CellType.NUMERIC) return false
        val n = cell.numericCellValue
        if (n < 0 || n.isNaN() || n.isInfinite()) return false
        val id = cell.cellStyle.dataFormat.toInt()
        return ExcelDates.isDateFormat(id, cell.cellStyle.dataFormatString)
    }
}

internal object ExcelDates {
    private const val MS_PER_DAY = 86_400_000L

    private val BUILT_IN_DATE_IDS = (14..22) + (27..36) + (45..47) + (50..58)

    fun isDateFormat(id: Int, code: String): Boolean {
        if (id in BUILT_IN_DATE_IDS) return true
        if (id < XlsxWorkbook.FIRST_CUSTOM_FORMAT && XlsxWorkbook.BUILT_IN_FORMATS.containsKey(id)) return false
        return isDateFormatCode(code)
    }

    /**
     * Looks at the first section of a format code for date or time tokens after removing
     * quoted literals, escaped characters and bracketed parts ([Red], [$-409], [h]).
     * "0.00", "#,##0" and "@" are not dates; "yyyy-mm-dd", "dd/mm/yyyy" and "mmm-yy" are.
     */
    fun isDateFormatCode(code: String): Boolean {
        if (code.isBlank() || code.equals("General", ignoreCase = true)) return false
        val elapsed = Regex("""\[(h+|m+|s+)]""", RegexOption.IGNORE_CASE).containsMatchIn(code)
        val sb = StringBuilder()
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                c == '"' -> { i = code.indexOf('"', i + 1).let { if (it < 0) code.length else it } }
                c == '\\' -> i++
                c == '[' -> { i = code.indexOf(']', i + 1).let { if (it < 0) code.length else it } }
                c == ';' -> break
                c == '_' || c == '*' -> i++
                else -> sb.append(c)
            }
            i++
        }
        val s = sb.toString().lowercase()
        if (Regex("""[0#?]""").containsMatchIn(s.replace(Regex("""ss\.0+"""), "ss"))) return elapsed
        return elapsed || Regex("""[dymhs]""").containsMatchIn(s.replace("am/pm", "").replace("a/p", ""))
    }

    fun fromSerial(serial: Double, date1904: Boolean): Date {
        var days = kotlin.math.floor(serial).toInt()
        val ms = kotlin.math.round((serial - days) * MS_PER_DAY)
        val cal = Calendar.getInstance()
        cal.clear()
        if (date1904) {
            cal.set(1904, Calendar.JANUARY, 1)
        } else {
            // Excel counts a fictional 29 Feb 1900 (serial 60); serial 1 is 1 Jan 1900.
            if (days < 61) days += 1
            cal.set(1899, Calendar.DECEMBER, 30)
        }
        cal.add(Calendar.DAY_OF_MONTH, days)
        cal.add(Calendar.MILLISECOND, ms.toInt())
        return cal.time
    }

    fun toSerial(date: Date, date1904: Boolean): Double {
        val cal = Calendar.getInstance().apply { time = date }
        val utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH),
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND))
            set(Calendar.MILLISECOND, cal.get(Calendar.MILLISECOND))
        }
        val base = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear()
            if (date1904) set(1904, Calendar.JANUARY, 1) else set(1899, Calendar.DECEMBER, 30)
        }
        var serial = (utc.timeInMillis - base.timeInMillis).toDouble() / MS_PER_DAY
        if (!date1904 && serial < 61) serial -= 1
        return serial
    }
}

internal object CellRef {
    fun name(row: Int, column: Int): String = columnName(column) + (row + 1)

    fun columnName(column: Int): String {
        val sb = StringBuilder()
        var n = column + 1
        while (n > 0) {
            val r = (n - 1) % 26
            sb.append('A' + r)
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    /** "B12" to (11, 1); null when [ref] is not a cell reference. */
    fun parse(ref: String): Pair<Int, Int>? {
        var col = 0
        var i = 0
        while (i < ref.length && ref[i].isLetter()) {
            col = col * 26 + (ref[i].uppercaseChar() - 'A' + 1)
            i++
        }
        if (i == 0 || i == ref.length) return null
        val row = ref.substring(i).replace("$", "").toIntOrNull() ?: return null
        return (row - 1) to (col - 1)
    }
}
