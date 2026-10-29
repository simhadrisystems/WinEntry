package com.simhadri.winentry

import com.simhadri.winentry.utils.ExcelCells
import com.simhadri.winentry.utils.xlsx.BorderStyle
import com.simhadri.winentry.utils.xlsx.CellRangeAddress
import com.simhadri.winentry.utils.xlsx.CellType
import com.simhadri.winentry.utils.xlsx.DateUtil
import com.simhadri.winentry.utils.xlsx.FillPatternType
import com.simhadri.winentry.utils.xlsx.HorizontalAlignment
import com.simhadri.winentry.utils.xlsx.IndexedColors
import com.simhadri.winentry.utils.xlsx.VerticalAlignment
import com.simhadri.winentry.utils.xlsx.XlsxWorkbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Locale

/** The in-house xlsx package, checked against Apache POI (test classpath only) and an openpyxl file. */
class XlsxTest {

    private fun XlsxWorkbook.bytes() = ByteArrayOutputStream().also { write(it) }.toByteArray()
    private fun XSSFWorkbook.bytes() = ByteArrayOutputStream().also { write(it) }.toByteArray()
    private val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun sampleWorkbook(): XlsxWorkbook {
        val wb = XlsxWorkbook()
        val sheet = wb.createSheet("Day Sale Sheet")
        val header = wb.createCellStyle().apply {
            setFont(wb.createFont().apply { bold = true; fontHeightInPoints = 14; color = IndexedColors.WHITE.index })
            fillForegroundColor = IndexedColors.LIGHT_BLUE.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            borderTop = BorderStyle.THIN; borderBottom = BorderStyle.MEDIUM
            borderLeft = BorderStyle.THIN; borderRight = BorderStyle.THIN
        }
        val dash = wb.createCellStyle().apply { dataFormat = wb.createDataFormat().getFormat("0;-0;\"-\"") }
        val date = wb.createCellStyle().apply { dataFormat = wb.createDataFormat().getFormat("dd-mmm-yyyy") }

        sheet.createRow(0).createCell(0).apply { setCellValue("Shop & Co <test> \"quoted\" సరుకు"); cellStyle = header }
        sheet.addMergedRegion(CellRangeAddress(0, 0, 0, 3))
        sheet.setColumnWidth(0, 30 * 256)
        val r = sheet.createRow(2)
        r.createCell(0).setCellValue("W1249")
        r.createCell(1).apply { setCellValue(12.0); cellStyle = dash }
        r.createCell(2).setCellValue(450.5)
        r.createCell(3).setCellValue(true)
        r.createCell(4).apply { setCellValue(ymd.parse("2026-04-30")); cellStyle = date }
        r.createCell(5).cellFormula = "SUM(B3:C3)"
        r.createCell(6).setCellValue(9876543210.0)
        wb.createSheet("Purchase").createRow(0).createCell(0).setCellValue("second")
        return wb
    }

    @Test fun writerOutputOpensInPoi() {
        val poi = XSSFWorkbook(ByteArrayInputStream(sampleWorkbook().bytes()))
        assertEquals(2, poi.numberOfSheets)
        val s = poi.getSheet("Day Sale Sheet")
        val title = s.getRow(0).getCell(0)
        assertEquals("Shop & Co <test> \"quoted\" సరుకు", title.stringCellValue)
        assertTrue(poi.getFontAt(title.cellStyle.fontIndex).bold)
        assertEquals(14.toShort(), poi.getFontAt(title.cellStyle.fontIndex).fontHeightInPoints)
        assertEquals(IndexedColors.LIGHT_BLUE.index, title.cellStyle.fillForegroundColor)
        assertEquals(org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER, title.cellStyle.alignment)
        assertEquals(org.apache.poi.ss.usermodel.BorderStyle.MEDIUM, title.cellStyle.borderBottom)
        assertEquals("A1:D1", s.getMergedRegion(0).formatAsString())
        assertEquals(30 * 256, s.getColumnWidth(0))

        val r = s.getRow(2)
        assertEquals("W1249", r.getCell(0).stringCellValue)
        assertEquals(12.0, r.getCell(1).numericCellValue, 0.0)
        assertEquals("0;-0;\"-\"", r.getCell(1).cellStyle.dataFormatString)
        assertEquals(450.5, r.getCell(2).numericCellValue, 0.0)
        assertTrue(r.getCell(3).booleanCellValue)
        assertTrue(org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(r.getCell(4)))
        assertEquals("2026-04-30", ymd.format(r.getCell(4).dateCellValue))
        assertEquals("SUM(B3:C3)", r.getCell(5).cellFormula)
        assertEquals(9876543210.0, r.getCell(6).numericCellValue, 0.0)
        assertEquals("second", poi.getSheetAt(1).getRow(0).getCell(0).stringCellValue)
    }

    @Test fun roundTripThroughOwnReader() {
        val wb = XlsxWorkbook(ByteArrayInputStream(sampleWorkbook().bytes()))
        assertEquals(2, wb.numberOfSheets)
        val s = wb.getSheet("day sale sheet")!!
        assertEquals("Shop & Co <test> \"quoted\" సరుకు", s.getRow(0)!!.getCell(0)!!.stringCellValue)
        assertNull(s.getRow(1))
        assertEquals(2, s.lastRowNum)
        val r = s.getRow(2)!!
        assertEquals(7.toShort(), r.lastCellNum)
        assertEquals("W1249", ExcelCells.text(r.getCell(0)))
        assertEquals(12, ExcelCells.quantity(r.getCell(1)))
        assertFalse(DateUtil.isCellDateFormatted(r.getCell(1)))
        assertEquals(450.5, r.getCell(2)!!.numericCellValue, 0.0)
        assertTrue(r.getCell(3)!!.booleanCellValue)
        assertTrue(DateUtil.isCellDateFormatted(r.getCell(4)))
        assertEquals("2026-04-30", ExcelCells.date(r.getCell(4)))
        assertEquals(CellType.FORMULA, r.getCell(5)!!.cellType)
        assertEquals("9876543210", ExcelCells.text(r.getCell(6)))
    }

    @Test fun readsPoiWrittenFile() {
        val poi = XSSFWorkbook()
        val s = poi.createSheet("Closing")
        val dateStyle = poi.createCellStyle().apply { dataFormat = 14 }
        val isoStyle = poi.createCellStyle().apply { dataFormat = poi.createDataFormat().getFormat("yyyy-mm-dd") }
        val r0 = s.createRow(0)
        r0.createCell(0).setCellValue("Brand")
        r0.createCell(1).setCellValue("Brand")   // repeated text: one shared string
        r0.createCell(2).setCellValue(ymd.parse("2026-09-05")); r0.getCell(2).cellStyle = dateStyle
        r0.createCell(3).setCellValue(ymd.parse("2026-01-31")); r0.getCell(3).cellStyle = isoStyle
        r0.createCell(4).setCellValue(46142.0)
        val r3 = s.createRow(3)
        r3.createCell(1).cellFormula = "2*6"
        r3.createCell(2).cellFormula = "\"AB\"&\"C\""
        r3.createCell(5).setBlank()
        poi.creationHelper.createFormulaEvaluator().evaluateAll()
        poi.createSheet("Second")

        val wb = XlsxWorkbook(ByteArrayInputStream(poi.bytes()))
        assertEquals(listOf("Closing", "Second"), (0 until wb.numberOfSheets).map { wb.getSheetAt(it).sheetName })
        val sheet = wb.getSheet("Closing")!!
        val row0 = sheet.getRow(0)!!
        assertEquals("Brand", row0.getCell(1)!!.stringCellValue)
        assertEquals("2026-09-05", ExcelCells.date(row0.getCell(2)))
        assertEquals("2026-01-31", ExcelCells.date(row0.getCell(3)))
        assertFalse(DateUtil.isCellDateFormatted(row0.getCell(4)))
        assertEquals("2026-04-30", ExcelCells.date(row0.getCell(4)))   // bare serial, via StrictDate
        val row3 = sheet.getRow(3)!!
        assertEquals(12, ExcelCells.quantity(row3.getCell(1)))
        assertEquals("ABC", ExcelCells.text(row3.getCell(2)))
        assertEquals(CellType.BLANK, row3.getCell(5)!!.cellType)
        assertEquals("", row3.getCell(5)!!.stringCellValue)
        assertEquals(3, sheet.lastRowNum)
        assertEquals(-1, wb.getSheet("Second")!!.lastRowNum)
    }

    @Test fun readsOpenpyxlFile() {
        val wb = javaClass.getResourceAsStream("/xlsx/openpyxl_sample.xlsx")!!.use { XlsxWorkbook(it) }
        val p = wb.getSheet("Products")!!
        assertEquals("W1249", ExcelCells.text(p.getRow(1)!!.getCell(1)))
        assertEquals(12, ExcelCells.quantity(p.getRow(1)!!.getCell(2)))
        assertEquals("450.5", ExcelCells.text(p.getRow(1)!!.getCell(3)))
        assertEquals("true", ExcelCells.text(p.getRow(1)!!.getCell(4)))
        assertEquals("00123", ExcelCells.text(p.getRow(3)!!.getCell(1)))
        assertEquals("Telugu name సరుకు", ExcelCells.text(p.getRow(3)!!.getCell(0)))
        assertNull(p.getRow(3)!!.getCell(2))
        // openpyxl saves formulas without a cached result: reads as blank, never throws.
        assertEquals(CellType.FORMULA, p.getRow(4)!!.getCell(2)!!.cellType)
        assertNull(ExcelCells.text(p.getRow(4)!!.getCell(2)))

        val d = wb.getSheet("Dates")!!
        for (i in 0..d.lastRowNum) {
            val row = d.getRow(i)!!
            val fmt = row.getCell(0)!!.stringCellValue
            val cell = row.getCell(1)!!
            val expectDate = fmt != "0.00" && fmt != "General"
            assertEquals(fmt, expectDate, DateUtil.isCellDateFormatted(cell))
            assertEquals(fmt, "2026-04-30", ExcelCells.date(cell))
        }
    }

    @Test fun dateFormatCodes() {
        val wb = XlsxWorkbook()
        val fmt = wb.createDataFormat()
        val row = wb.createSheet().createRow(0)
        fun isDate(code: String): Boolean {
            val c = row.createCell(0)
            c.setCellValue(46142.0)
            c.cellStyle = wb.createCellStyle().apply { dataFormat = fmt.getFormat(code) }
            return DateUtil.isCellDateFormatted(c)
        }
        for (code in listOf("yyyy-mm-dd", "dd/mm/yyyy", "d-mmm-yy", "[\$-409]d-mmm-yyyy", "dd.mm.yyyy",
                "mmm-yy", "[\$-4009]dd mmmm yyyy", "h:mm:ss", "[h]:mm:ss", "mm:ss.0", "dd-mm-yyyy;@",
                "m/d/yy", "dddd, mmmm d, yyyy")) {
            assertTrue(code, isDate(code))
        }
        for (code in listOf("0.00", "General", "#,##0", "0;-0;\"-\"", "##,##,##0", "@", "0%",
                "[Red]0.00", "#,##0 \"days\"", "[\$₹-4009] #,##0.00", "0.00E+00", "\"Rs\" 0")) {
            assertFalse(code, isDate(code))
        }
    }
}
