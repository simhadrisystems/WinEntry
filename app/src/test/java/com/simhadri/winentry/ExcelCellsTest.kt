package com.simhadri.winentry

import com.simhadri.winentry.utils.ExcelCells
import com.simhadri.winentry.utils.xlsx.XlsxWorkbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExcelCellsTest {

    private val wb = XlsxWorkbook()
    private val row = wb.createSheet().createRow(0)

    @Test fun formulaReadsCachedValue() {
        val poi = XSSFWorkbook()
        poi.createSheet().createRow(0).createCell(0).cellFormula = "2*6"
        poi.creationHelper.createFormulaEvaluator().evaluateAll()
        val bytes = java.io.ByteArrayOutputStream().also { poi.write(it) }.toByteArray()
        val c = XlsxWorkbook(java.io.ByteArrayInputStream(bytes)).getSheetAt(0).getRow(0)!!.getCell(0)
        assertEquals(12, ExcelCells.quantity(c))
        assertEquals("12", ExcelCells.text(c))
    }

    @Test fun numericIdsStayWhole() {
        val c = row.createCell(1)
        c.setCellValue(9876543210.0)
        assertEquals("9876543210", ExcelCells.text(c))
        c.setCellValue(12345.0)
        assertEquals("12345", ExcelCells.text(c))
    }

    @Test fun quantityRejectsNegativeAndFractional() {
        val c = row.createCell(2)
        c.setCellValue(-3.0)
        assertNull(ExcelCells.quantity(c))
        c.setCellValue(2.5)
        assertNull(ExcelCells.quantity(c))
        c.setCellValue("abc")
        assertNull(ExcelCells.quantity(c))
        assertEquals(0, ExcelCells.quantity(row.createCell(3)))
        assertEquals(0, ExcelCells.quantity(null))
    }

    @Test fun dateCells() {
        val c = row.createCell(4)
        c.setCellValue(46142.0)
        assertEquals("2026-04-30", ExcelCells.date(c))
        c.setCellValue("05/09/2026")
        assertEquals("2026-09-05", ExcelCells.date(c))
        c.setCellValue("")
        assertNull(ExcelCells.date(c))
    }
}
