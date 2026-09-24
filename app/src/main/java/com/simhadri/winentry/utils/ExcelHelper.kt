package com.simhadri.winentry.utils

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.simhadri.winentry.data.entity.Product
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*


private fun getCellStringValue(cell: Cell?): String {
    if (cell == null) return ""

    return when (cell.cellType) {
        CellType.STRING -> cell.stringCellValue.trim()

        CellType.NUMERIC -> {
            // Check if it's a date
            if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                cell.dateCellValue.toString()
            } else {
                // Return number as string, remove decimal if it's a whole number
                val numValue = cell.numericCellValue
                if (numValue == numValue.toLong().toDouble()) {
                    numValue.toLong().toString()
                } else {
                    numValue.toString()
                }
            }
        }

        CellType.BOOLEAN -> if (cell.booleanCellValue) "YES" else "NO"

        CellType.FORMULA -> {
            // IMPORTANT: Evaluate formula and get the cached result
            try {
                when (cell.cachedFormulaResultType) {
                    CellType.NUMERIC -> {
                        val numValue = cell.numericCellValue
                        if (numValue == numValue.toLong().toDouble()) {
                            numValue.toLong().toString()
                        } else {
                            numValue.toString()
                        }
                    }
                    CellType.STRING -> cell.stringCellValue.trim()
                    CellType.BOOLEAN -> if (cell.booleanCellValue) "YES" else "NO"
                    else -> ""
                }
            } catch (e: Exception) {
                // If cached result fails, try to evaluate
                try {
                    val evaluator = cell.sheet.workbook.creationHelper.createFormulaEvaluator()
                    val cellValue = evaluator.evaluate(cell)
                    when (cellValue.cellType) {
                        CellType.NUMERIC -> {
                            val numValue = cellValue.numberValue
                            if (numValue == numValue.toLong().toDouble()) {
                                numValue.toLong().toString()
                            } else {
                                numValue.toString()
                            }
                        }
                        CellType.STRING -> cellValue.stringValue.trim()
                        CellType.BOOLEAN -> if (cellValue.booleanValue) "YES" else "NO"
                        else -> ""
                    }
                } catch (ex: Exception) {
                    ""
                }
            }
        }

        CellType.BLANK -> ""

        else -> ""
    }
}

class ExcelHelper(private val context: Context) {

    /**
     * Import products from Excel
     * Columns: Product Name, Product Type, Category, Brand Code, Quantity, Display Name, Reorder Level, Serial No, Status
     */
    data class ProductImport(val products: List<Product>, val notes: List<String>)

    /**
     * Products already on the device keep their id (purchases and stock refer to it), their
     * product type, and any value whose cell is blank in the file.
     */
    fun importProducts(uri: Uri, existing: List<Product>): ProductImport {
        val products = mutableListOf<Product>()
        val notes = mutableListOf<String>()
        val existingByCode = existing.associateBy { it.brandCode.trim().uppercase() }

        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val workbook = XSSFWorkbook(inputStream)
                val sheet = workbook.getSheetAt(0)

                // Skip header row, start from row 1
                // lastRowNum, not physicalNumberOfRows: a blank row mid-sheet would cut off the end
                for (rowIndex in 1..sheet.lastRowNum) {
                    val row = sheet.getRow(rowIndex) ?: continue

                    try {
                        // Read columns: Name, Type, Category, Brand, Aliases, Prices(8), Qty, Display, Reorder, Serial, Active
                        val productName = getCellStringValue(row.getCell(0))
                        val productType = getCellStringValue(row.getCell(1))
                        val category = getCellStringValue(row.getCell(2))
                        val brandCode = getCellStringValue(row.getCell(3))
                        val aliases = getCellStringValue(row.getCell(4))  // NEW: Aliases column

                        val e = existingByCode[brandCode.trim().uppercase()]
                        fun dbl(col: Int, old: Double?) = getCellStringValue(row.getCell(col)).toDoubleOrNull() ?: old ?: 0.0

                        // Purchase Prices (shifted by 1)
                        val qqPurchase = dbl(5, e?.qqPurchasePrice)
                        val ppPurchase = dbl(6, e?.ppPurchasePrice)
                        val nnPurchase = dbl(7, e?.nnPurchasePrice)
                        val ddPurchase = dbl(8, e?.ddPurchasePrice)

                        // Sale Prices (shifted by 1)
                        val qqSale = dbl(9, e?.qqSalePrice)
                        val ppSale = dbl(10, e?.ppSalePrice)
                        val nnSale = dbl(11, e?.nnSalePrice)
                        val ddSale = dbl(12, e?.ddSalePrice)

                        // Units Per Box (shifted by 1, with smart defaults)
                        val qqUnitsPerBoxStr = getCellStringValue(row.getCell(13))
                        val ppUnitsPerBoxStr = getCellStringValue(row.getCell(14))
                        val nnUnitsPerBoxStr = getCellStringValue(row.getCell(15))
                        val ddUnitsPerBoxStr = getCellStringValue(row.getCell(16))
                        
                        val qqUnitsPerBox = qqUnitsPerBoxStr.toIntOrNull() ?: e?.qqUnitsPerBox ?: 12
                        val ppUnitsPerBox = ppUnitsPerBoxStr.toIntOrNull() ?: e?.ppUnitsPerBox ?: 24
                        val nnUnitsPerBox = nnUnitsPerBoxStr.toIntOrNull() ?: e?.nnUnitsPerBox ?: 48
                        val ddUnitsPerBox = ddUnitsPerBoxStr.toIntOrNull() ?: e?.ddUnitsPerBox ?: 96

                        val displayName  = getCellStringValue(row.getCell(17))
                        val serialNoStr  = getCellStringValue(row.getCell(18))
                        val isActiveStr  = getCellStringValue(row.getCell(19))
                        val positionStr  = getCellStringValue(row.getCell(20))

                        // Validate required fields
                        if (productName.isBlank() || productType.isBlank() || brandCode.isBlank()) {
                            continue
                        }

                        // Parse values; a blank cell keeps what the device already has
                        val serialNo = serialNoStr.toIntOrNull() ?: e?.serialNo ?: 1
                        val isActive = if (isActiveStr.isBlank()) e?.isActive ?: true
                            else isActiveStr.uppercase() in listOf("YES", "Y", "TRUE", "1", "ACTIVE")
                        val position = positionStr.toIntOrNull() ?: e?.dailySortKey ?: 999

                        // A type change would re-key the product's stock history
                        var typeUpper = productType.trim().uppercase()
                        if (e != null && e.productType != typeUpper) {
                            notes += "${e.displayName}: type kept as ${e.productType} (file says $typeUpper)"
                            typeUpper = e.productType
                        }
                        val brandUpper = e?.brandCode ?: brandCode.trim().uppercase()

                        val product = (e ?: Product(
                            productName = productName, productType = typeUpper, brandCode = brandUpper,
                            qqCode = "", ppCode = "", nnCode = "", ddCode = "", displayName = ""
                        )).copy(
                            productName = productName,
                            productType = typeUpper,
                            category = category.ifBlank { e?.category ?: "" },
                            brandCode = brandUpper,
                            aliases = aliases.ifBlank { e?.aliases ?: "" },
                            qqCode = "${typeUpper}${brandUpper}QQ",
                            ppCode = "${typeUpper}${brandUpper}PP",
                            nnCode = "${typeUpper}${brandUpper}NN",
                            ddCode = "${typeUpper}${brandUpper}DD",
                            qqPurchasePrice = qqPurchase,
                            qqSalePrice = qqSale,
                            ppPurchasePrice = ppPurchase,
                            ppSalePrice = ppSale,
                            nnPurchasePrice = nnPurchase,
                            nnSalePrice = nnSale,
                            ddPurchasePrice = ddPurchase,
                            ddSalePrice = ddSale,
                            qqUnitsPerBox = qqUnitsPerBox,
                            ppUnitsPerBox = ppUnitsPerBox,
                            nnUnitsPerBox = nnUnitsPerBox,
                            ddUnitsPerBox = ddUnitsPerBox,
                            displayName = displayName.ifBlank { e?.displayName ?: "$productName $typeUpper" },
                            serialNo = serialNo,
                            isActive = isActive,
                            dailySortKey = position
                        )

                        products.add(product)

                    } catch (e: Exception) {
                        continue
                    }
                }

                workbook.close()
            }
        } catch (e: Exception) {
            throw Exception("Failed to import products: ${e.message}")
        }

        return ProductImport(products, notes)
    }

    private fun getCellStringValue(cell: Cell?): String {
        if (cell == null) return ""

        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue.trim()

            CellType.NUMERIC -> {
                // Check if it's a date
                if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                    cell.dateCellValue.toString()
                } else {
                    // Return number as string, remove decimal if it's a whole number
                    val numValue = cell.numericCellValue
                    if (numValue == numValue.toLong().toDouble()) {
                        numValue.toLong().toString()
                    } else {
                        numValue.toString()
                    }
                }
            }

            CellType.BOOLEAN -> if (cell.booleanCellValue) "YES" else "NO"

            CellType.FORMULA -> {
                // IMPORTANT: Evaluate formula and get the cached result
                try {
                    when (cell.cachedFormulaResultType) {
                        CellType.NUMERIC -> {
                            val numValue = cell.numericCellValue
                            if (numValue == numValue.toLong().toDouble()) {
                                numValue.toLong().toString()
                            } else {
                                numValue.toString()
                            }
                        }
                        CellType.STRING -> cell.stringCellValue.trim()
                        CellType.BOOLEAN -> if (cell.booleanCellValue) "YES" else "NO"
                        else -> ""
                    }
                } catch (e: Exception) {
                    // If cached result fails, try to evaluate
                    try {
                        val evaluator = cell.sheet.workbook.creationHelper.createFormulaEvaluator()
                        val cellValue = evaluator.evaluate(cell)
                        when (cellValue.cellType) {
                            CellType.NUMERIC -> {
                                val numValue = cellValue.numberValue
                                if (numValue == numValue.toLong().toDouble()) {
                                    numValue.toLong().toString()
                                } else {
                                    numValue.toString()
                                }
                            }
                            CellType.STRING -> cellValue.stringValue.trim()
                            CellType.BOOLEAN -> if (cellValue.booleanValue) "YES" else "NO"
                            else -> ""
                        }
                    } catch (ex: Exception) {
                        ""
                    }
                }
            }

            CellType.BLANK -> ""

            else -> ""
        }
    }

    /**
     * Export products to Excel
     */
    fun exportProducts(products: List<Product>, fileName: String): Uri {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Products")

        // Create header row
        val headerRow = sheet.createRow(0)
        val headers = arrayOf(
            "Product Name", "Type", "Category", "Brand Code", "Aliases",  // ← Aliases added
            "QQ Purchase", "PP Purchase", "NN Purchase", "DD Purchase",
            "QQ Sale", "PP Sale", "NN Sale", "DD Sale",
            "QQ Units/Box", "PP Units/Box", "NN Units/Box", "DD Units/Box",
            "Display Name", "Serial No", "Active", "Sort Key"
        )

        // Bold header style
        val headerCellStyle = workbook.createCellStyle()
        val font = workbook.createFont()
        font.bold = true
        headerCellStyle.setFont(font)

        headers.forEachIndexed { index, header ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(header)
            cell.cellStyle = headerCellStyle
        }

        // Write data rows
        products.forEachIndexed { index, product ->
            val row = sheet.createRow(index + 1)
            row.createCell(0).setCellValue(product.productName)
            row.createCell(1).setCellValue(product.productType)
            row.createCell(2).setCellValue(product.category)
            row.createCell(3).setCellValue(product.brandCode)
            row.createCell(4).setCellValue(product.aliases)  // NEW: Aliases column

            // Purchase Prices (shifted by 1)
            row.createCell(5).setCellValue(product.qqPurchasePrice)
            row.createCell(6).setCellValue(product.ppPurchasePrice)
            row.createCell(7).setCellValue(product.nnPurchasePrice)
            row.createCell(8).setCellValue(product.ddPurchasePrice)

            // Sale Prices (shifted by 1)
            row.createCell(9).setCellValue(product.qqSalePrice)
            row.createCell(10).setCellValue(product.ppSalePrice)
            row.createCell(11).setCellValue(product.nnSalePrice)
            row.createCell(12).setCellValue(product.ddSalePrice)

            // Units Per Box (shifted by 1)
            row.createCell(13).setCellValue(product.qqUnitsPerBox.toDouble())
            row.createCell(14).setCellValue(product.ppUnitsPerBox.toDouble())
            row.createCell(15).setCellValue(product.nnUnitsPerBox.toDouble())
            row.createCell(16).setCellValue(product.ddUnitsPerBox.toDouble())

            row.createCell(17).setCellValue(product.displayName)
            row.createCell(18).setCellValue(product.serialNo.toDouble())
            row.createCell(19).setCellValue(if (product.isActive) "YES" else "NO")
            row.createCell(20).setCellValue(product.dailySortKey.toDouble())
        }

        // Set column widths
        for (i in 0 until headers.size) {
            sheet.setColumnWidth(i, 4000)
        }

        // Save file
        val file = File(context.getExternalFilesDir(null), fileName)
        FileOutputStream(file).use { outputStream ->
            workbook.write(outputStream)
        }
        workbook.close()

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    /**
     * Create product template
     */
    fun createTemplate(fileName: String): Uri {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Products")

        // Create header row
        val headerRow = sheet.createRow(0)
        val headers = arrayOf(
            "Product Name", "Type", "Category", "Brand Code", "Aliases",  // ← Added Aliases
            "QQ Purchase", "PP Purchase", "NN Purchase", "DD Purchase",
            "QQ Sale", "PP Sale", "NN Sale", "DD Sale",
            "QQ Units/Box", "PP Units/Box", "NN Units/Box", "DD Units/Box",
            "Display Name", "Serial No", "Active", "Sort Key"
        )

        // Bold header style
        val headerCellStyle = workbook.createCellStyle()
        val font = workbook.createFont()
        font.bold = true
        headerCellStyle.setFont(font)

        headers.forEachIndexed { index, header ->
            val cell = headerRow.createCell(index)
            cell.setCellValue(header)
            cell.cellStyle = headerCellStyle
        }

        // Add sample data row
        val sampleRow = sheet.createRow(1)
        sampleRow.createCell(0).setCellValue("SAMPLE PRODUCT")
        sampleRow.createCell(1).setCellValue("W")
        sampleRow.createCell(2).setCellValue("Category")
        sampleRow.createCell(3).setCellValue("1177")
        sampleRow.createCell(4).setCellValue("A538,C538")  // ← Sample aliases

        // Sample Purchase Prices (shifted by 1)
        sampleRow.createCell(5).setCellValue(100.0)
        sampleRow.createCell(6).setCellValue(150.0)
        sampleRow.createCell(7).setCellValue(200.0)
        sampleRow.createCell(8).setCellValue(250.0)

        // Sample Sale Prices (shifted by 1)
        sampleRow.createCell(9).setCellValue(120.0)
        sampleRow.createCell(10).setCellValue(180.0)
        sampleRow.createCell(11).setCellValue(240.0)
        sampleRow.createCell(12).setCellValue(300.0)

        // Sample Units Per Box (shifted by 1, defaults)
        sampleRow.createCell(13).setCellValue(12.0)  // QQ default
        sampleRow.createCell(14).setCellValue(24.0)  // PP default
        sampleRow.createCell(15).setCellValue(48.0)  // NN default
        sampleRow.createCell(16).setCellValue(96.0)  // DD default

        sampleRow.createCell(17).setCellValue("1")
        sampleRow.createCell(18).setCellValue("") // Empty - will auto-generate
        sampleRow.createCell(19).setCellValue("10")
        sampleRow.createCell(20).setCellValue("1")
        sampleRow.createCell(21).setCellValue("YES")
        sampleRow.createCell(22).setCellValue("1")  // Position - leave empty or set number

        // Set column widths
        for (i in 0 until headers.size) {
            sheet.setColumnWidth(i, 4000)
        }

        // Save file
        val file = File(context.getExternalFilesDir(null), fileName)
        FileOutputStream(file).use { outputStream ->
            workbook.write(outputStream)
        }
        workbook.close()

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }
}
