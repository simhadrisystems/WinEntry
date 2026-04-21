package com.simhadri.winentry.utils

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.simhadri.winentry.data.entity.DailyEntry
import org.apache.poi.ss.usermodel.*
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFColor
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class DailyStockExcelHelper(private val context: Context) {

    private val dateFormat = SimpleDateFormat("dd-MMM-yyyy", Locale.getDefault())
    private val dateFormatDb = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /**
     * Export daily stock with custom title and enhanced formatting
     */
    fun exportDailySaleSheet(
        date: String,
        entries: List<DailyEntry>,
        customTitle: String,
        fileName: String
    ): Uri {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Day Sale Sheet")

        // Create all styles
        val titleStyle = createTitleStyle(workbook)
        val headerStyle = createHeaderStyle(workbook)
        val subHeaderStyle = createSubHeaderStyle(workbook)
        val dataStyle = createDataStyle(workbook)
        val openingStyle = createOpeningStyle(workbook)
        val purchaseStyle = createPurchaseStyle(workbook)
        val closingStyle = createClosingStyle(workbook)
        val salesStyle = createSalesStyle(workbook)
        val amountStyle = createAmountStyle(workbook)
        val totalLabelStyle = createTotalLabelStyle(workbook)
        val totalValueStyle = createTotalValueStyle(workbook)

        var currentRow = 0

        // Row 1: Custom Title (merged across all columns)
        val titleRow = sheet.createRow(currentRow++)
        val titleCell = titleRow.createCell(1)
        titleCell.setCellValue(customTitle)
        titleCell.cellStyle = titleStyle
        sheet.addMergedRegion(CellRangeAddress(0, 0, 1, 17))

        // Row 2: Sheet Title + Date
        val sheetTitleRow = sheet.createRow(currentRow++)
        val sheetTitleCell = sheetTitleRow.createCell(1)
        sheetTitleCell.setCellValue("DAILY SALE SHEET")
        sheetTitleCell.cellStyle = titleStyle
        sheet.addMergedRegion(CellRangeAddress(1, 1, 1, 9))

        val formattedDate = try {
            dateFormat.format(dateFormatDb.parse(date)!!)
        } catch (e: Exception) {
            date
        }
        val dateCell = sheetTitleRow.createCell(10)
        dateCell.setCellValue("DATE: $formattedDate")
        dateCell.cellStyle = titleStyle
        sheet.addMergedRegion(CellRangeAddress(1, 1, 10, 17))

        // Row 3: Main Headers (Sl. No, Product Name, Category headers)
        val mainHeaderRow = sheet.createRow(currentRow++)
        
        // Sl. No (A3:A4 merged)
        val slNoCell = mainHeaderRow.createCell(0)
        slNoCell.setCellValue("Sl. No")
        slNoCell.cellStyle = headerStyle
        
        // Product Name (B3:B4 merged)
        val prodNameCell = mainHeaderRow.createCell(1)
        prodNameCell.setCellValue("PRODUCT NAME")
        prodNameCell.cellStyle = headerStyle
        
        // Opening (C3:F3 merged)
        val openingCell = mainHeaderRow.createCell(2)
        openingCell.setCellValue("Opening")
        openingCell.cellStyle = headerStyle
        sheet.addMergedRegion(CellRangeAddress(2, 2, 2, 5))
        
        // Purchases (G3:J3 merged)
        val purchaseCell = mainHeaderRow.createCell(6)
        purchaseCell.setCellValue("Purchases")
        purchaseCell.cellStyle = headerStyle
        sheet.addMergedRegion(CellRangeAddress(2, 2, 6, 9))
        
        // Closing (K3:N3 merged)
        val closingCell = mainHeaderRow.createCell(10)
        closingCell.setCellValue("Closing")
        closingCell.cellStyle = headerStyle
        sheet.addMergedRegion(CellRangeAddress(2, 2, 10, 13))
        
        // Sales (O3:R3 merged)
        val salesCell = mainHeaderRow.createCell(14)
        salesCell.setCellValue("Sales")
        salesCell.cellStyle = headerStyle
        sheet.addMergedRegion(CellRangeAddress(2, 2, 14, 17))
        
        // Sale Amount (S3:S4 merged)
        val saleAmtCell = mainHeaderRow.createCell(18)
        saleAmtCell.setCellValue("Sale Amount")
        saleAmtCell.cellStyle = headerStyle

        // Merge Sl. No and Product Name vertically
        sheet.addMergedRegion(CellRangeAddress(2, 3, 0, 0))
        sheet.addMergedRegion(CellRangeAddress(2, 3, 1, 1))
        sheet.addMergedRegion(CellRangeAddress(2, 3, 18, 18))

        // Row 4: Size Sub-headers (QQ, PP, NN, DD for each category)
        val subHeaderRow = sheet.createRow(currentRow++)
        val sizes = arrayOf("QQ", "PP", "NN", "DD")
        
        // Opening sizes (C4:F4)
        for (i in sizes.indices) {
            val cell = subHeaderRow.createCell(2 + i)
            cell.setCellValue(sizes[i])
            cell.cellStyle = subHeaderStyle
        }
        
        // Purchase sizes (G4:J4)
        for (i in sizes.indices) {
            val cell = subHeaderRow.createCell(6 + i)
            cell.setCellValue(sizes[i])
            cell.cellStyle = subHeaderStyle
        }
        
        // Closing sizes (K4:N4)
        for (i in sizes.indices) {
            val cell = subHeaderRow.createCell(10 + i)
            cell.setCellValue(sizes[i])
            cell.cellStyle = subHeaderStyle
        }
        
        // Sale sizes (O4:R4)
        for (i in sizes.indices) {
            val cell = subHeaderRow.createCell(14 + i)
            cell.setCellValue(sizes[i])
            cell.cellStyle = subHeaderStyle
        }

        // Data rows (starting from row 5)
        entries.forEachIndexed { index, entry ->
            val row = sheet.createRow(currentRow++)
            var col = 0

            // Sl. No
            val slCell = row.createCell(col++)
            slCell.setCellValue((index + 1).toDouble())
            slCell.cellStyle = dataStyle

            // Product Name
            val nameCell = row.createCell(col++)
            nameCell.setCellValue(entry.product.displayName)
            nameCell.cellStyle = dataStyle

            // Opening (QQ, PP, NN, DD) - Light green
            row.createCell(col++).apply { setCellValue(entry.opening.qq.toDouble()); cellStyle = openingStyle }
            row.createCell(col++).apply { setCellValue(entry.opening.pp.toDouble()); cellStyle = openingStyle }
            row.createCell(col++).apply { setCellValue(entry.opening.nn.toDouble()); cellStyle = openingStyle }
            row.createCell(col++).apply { setCellValue(entry.opening.dd.toDouble()); cellStyle = openingStyle }

            // Purchase (QQ, PP, NN, DD) - Light blue
            row.createCell(col++).apply { setCellValue(entry.purchase.qq.toDouble()); cellStyle = purchaseStyle }
            row.createCell(col++).apply { setCellValue(entry.purchase.pp.toDouble()); cellStyle = purchaseStyle }
            row.createCell(col++).apply { setCellValue(entry.purchase.nn.toDouble()); cellStyle = purchaseStyle }
            row.createCell(col++).apply { setCellValue(entry.purchase.dd.toDouble()); cellStyle = purchaseStyle }

            // Closing (QQ, PP, NN, DD) - Light orange
            row.createCell(col++).apply { setCellValue(entry.closing.qq.toDouble()); cellStyle = closingStyle }
            row.createCell(col++).apply { setCellValue(entry.closing.pp.toDouble()); cellStyle = closingStyle }
            row.createCell(col++).apply { setCellValue(entry.closing.nn.toDouble()); cellStyle = closingStyle }
            row.createCell(col++).apply { setCellValue(entry.closing.dd.toDouble()); cellStyle = closingStyle }

            // Sale (QQ, PP, NN, DD) - Light yellow
            row.createCell(col++).apply { setCellValue(entry.sale.qq.toDouble()); cellStyle = salesStyle }
            row.createCell(col++).apply { setCellValue(entry.sale.pp.toDouble()); cellStyle = salesStyle }
            row.createCell(col++).apply { setCellValue(entry.sale.nn.toDouble()); cellStyle = salesStyle }
            row.createCell(col++).apply { setCellValue(entry.sale.dd.toDouble()); cellStyle = salesStyle }

            // Sale Amount - Indian format with grouping
            row.createCell(col).apply { 
                setCellValue(entry.saleAmount)
                cellStyle = amountStyle 
            }
        }

        // Total row
        val totalRow = sheet.createRow(currentRow)
        val totalLabelCell = totalRow.createCell(0)
        totalLabelCell.setCellValue("Days Total Sale Amount:")
        totalLabelCell.cellStyle = totalLabelStyle
        sheet.addMergedRegion(CellRangeAddress(currentRow, currentRow, 0, 10))

        val totalFormulaCell = totalRow.createCell(11)
        totalFormulaCell.cellFormula = "SUM(S5:S${currentRow})"
        totalFormulaCell.cellStyle = totalValueStyle
        sheet.addMergedRegion(CellRangeAddress(currentRow, currentRow, 11, 17))

        // Set column widths (matching your template)
        sheet.setColumnWidth(0, 7 * 256)      // Sl. No
        sheet.setColumnWidth(1, 34 * 256)     // Product Name
        sheet.setColumnWidth(2, 6 * 256)      // QQ
        sheet.setColumnWidth(3, 5 * 256)      // PP
        sheet.setColumnWidth(4, 6 * 256)      // NN
        sheet.setColumnWidth(5, 5 * 256)      // DD
        for (i in 6..17) {
            sheet.setColumnWidth(i, 6 * 256)  // All other data columns
        }
        sheet.setColumnWidth(18, 12 * 256)    // Sale Amount

        // Save file
        val file = File(context.getExternalFilesDir(null), fileName)
        FileOutputStream(file).use { workbook.write(it) }
        workbook.close()

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    // Styling functions
    private fun createTitleStyle(workbook: XSSFWorkbook): XSSFCellStyle {
        return (workbook.createCellStyle() as XSSFCellStyle).apply {
            val font = workbook.createFont()
            font.bold = true
            font.fontHeightInPoints = 14
            setFont(font)
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
        }
    }

    private fun createHeaderStyle(workbook: XSSFWorkbook): XSSFCellStyle {
        return (workbook.createCellStyle() as XSSFCellStyle).apply {
            val font = workbook.createFont()
            font.bold = true
            font.fontHeightInPoints = 12
            setFont(font)
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            fillForegroundColor = IndexedColors.LIGHT_BLUE.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            borderTop = BorderStyle.THIN
            borderBottom = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
        }
    }

    private fun createSubHeaderStyle(workbook: XSSFWorkbook): XSSFCellStyle {
        return (workbook.createCellStyle() as XSSFCellStyle).apply {
            val font = workbook.createFont()
            font.bold = true
            font.fontHeightInPoints = 12
            setFont(font)
            alignment = HorizontalAlignment.CENTER
            fillForegroundColor = IndexedColors.PALE_BLUE.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            borderTop = BorderStyle.THIN
            borderBottom = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
        }
    }

    private fun createDataStyle(workbook: XSSFWorkbook): XSSFCellStyle {
        return (workbook.createCellStyle() as XSSFCellStyle).apply {
            val font = workbook.createFont()
            font.fontHeightInPoints = 12
            setFont(font)
            // Custom number format: show dash for zero
            dataFormat = workbook.createDataFormat().getFormat("0;-0;\"-\"")
            borderTop = BorderStyle.THIN
            borderBottom = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
        }
    }

    // Opening data style - Very light green background
    private fun createOpeningStyle(workbook: XSSFWorkbook): XSSFCellStyle {
        return (workbook.createCellStyle() as XSSFCellStyle).apply {
            val font = workbook.createFont()
            font.fontHeightInPoints = 12
            setFont(font)
            dataFormat = workbook.createDataFormat().getFormat("0;-0;\"-\"")
            fillForegroundColor = IndexedColors.LIGHT_GREEN.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            // Center align horizontally and vertically
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            borderTop = BorderStyle.THIN
            borderBottom = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
        }
    }

    // Purchase data style - Very light blue background
    private fun createPurchaseStyle(workbook: XSSFWorkbook): XSSFCellStyle {
        return (workbook.createCellStyle() as XSSFCellStyle).apply {
            val font = workbook.createFont()
            font.fontHeightInPoints = 12
            setFont(font)
            dataFormat = workbook.createDataFormat().getFormat("0;-0;\"-\"")
            fillForegroundColor = IndexedColors.LIGHT_TURQUOISE.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            borderTop = BorderStyle.THIN
            borderBottom = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
        }
    }

    // Closing data style - Very light orange background
    private fun createClosingStyle(workbook: XSSFWorkbook): XSSFCellStyle {
        return (workbook.createCellStyle() as XSSFCellStyle).apply {
            val font = workbook.createFont()
            font.fontHeightInPoints = 12
            setFont(font)
            dataFormat = workbook.createDataFormat().getFormat("0;-0;\"-\"")
            fillForegroundColor = IndexedColors.LIGHT_ORANGE.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            borderTop = BorderStyle.THIN
            borderBottom = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
        }
    }

    // Sales data style - Very light yellow background
    private fun createSalesStyle(workbook: XSSFWorkbook): XSSFCellStyle {
        return (workbook.createCellStyle() as XSSFCellStyle).apply {
            val font = workbook.createFont()
            font.fontHeightInPoints = 12
            setFont(font)
            dataFormat = workbook.createDataFormat().getFormat("0;-0;\"-\"")
            fillForegroundColor = IndexedColors.LIGHT_YELLOW.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            borderTop = BorderStyle.THIN
            borderBottom = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
        }
    }

    // Sale amount style - Indian number format without decimals
    private fun createAmountStyle(workbook: XSSFWorkbook): XSSFCellStyle {
        return (workbook.createCellStyle() as XSSFCellStyle).apply {
            val font = workbook.createFont()
            font.fontHeightInPoints = 12
            setFont(font)
            // Indian number format without decimals: ##,##,##0
            dataFormat = workbook.createDataFormat().getFormat("##,##,##0")
            alignment = HorizontalAlignment.RIGHT
            verticalAlignment = VerticalAlignment.CENTER
            borderTop = BorderStyle.THIN
            borderBottom = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
        }
    }

    private fun createTotalLabelStyle(workbook: XSSFWorkbook): XSSFCellStyle {
        return (workbook.createCellStyle() as XSSFCellStyle).apply {
            val font = workbook.createFont()
            font.bold = true
            font.fontHeightInPoints = 12
            setFont(font)
            alignment = HorizontalAlignment.RIGHT
            verticalAlignment = VerticalAlignment.CENTER
            borderTop = BorderStyle.MEDIUM
            borderBottom = BorderStyle.MEDIUM
            borderLeft = BorderStyle.MEDIUM
        }
    }

    private fun createTotalValueStyle(workbook: XSSFWorkbook): XSSFCellStyle {
        return (workbook.createCellStyle() as XSSFCellStyle).apply {
            val font = workbook.createFont()
            font.bold = true
            font.fontHeightInPoints = 12
            setFont(font)
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            borderTop = BorderStyle.MEDIUM
            borderBottom = BorderStyle.MEDIUM
            borderRight = BorderStyle.MEDIUM
        }
    }
}
