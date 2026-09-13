package com.simhadri.winentry.helpers

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.simhadri.winentry.data.entity.Product
import com.simhadri.winentry.util.ProductCodeResolver
import com.simhadri.winentry.data.entity.Purchase
import com.simhadri.winentry.data.repository.PurchaseRepository
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * ENHANCED Purchase Excel Helper with MULTI-SHIPMENT support
 * 
 * Features:
 * - Multiple shipments in ONE sheet
 * - Each shipment has own INVOICE NUMBER and DATE
 * - Automatic shipment detection
 * - Duplicate prevention per shipment
 * 
 * Excel Structure:
 * Row 1: INVOICE NUMBER | TP08726
 * Row 2: DATE | 5/2/26
 * Row 3: Headers
 * Rows 4+: Products for Shipment 1
 * 
 * [Empty rows]
 * 
 * Row 48: INVOICE NUMBER | TP08726
 * Row 49: DATE | 7/2/26
 * Row 50: Headers
 * Rows 51+: Products for Shipment 2
 * 
 * ... and so on
 */
class PurchaseExcelHelper(private val context: Context) {

    companion object {
        // Excel column indices
        const val COL_SNO = 0
        const val COL_BRAND_CODE = 1
        const val COL_PRODUCT_NAME = 2
        const val COL_PRODUCT_TYPE = 3
        const val COL_PRODUCT_CATEGORY = 4
        const val COL_SIZE_CODE = 5
        const val COL_SIZE = 6
        const val COL_QTY_BOXES = 7
        const val COL_QTY_UNITS = 8
        const val COL_PURCHASE_PRICE = 9
        
        // Date formats — Indian (DD/MM) and ISO only. Never MM/DD (US format).
        val DATE_FORMATS = listOf(
            SimpleDateFormat("d/M/yyyy", Locale.US),
            SimpleDateFormat("dd/MM/yyyy", Locale.US),
            SimpleDateFormat("d/M/yy", Locale.US),
            SimpleDateFormat("dd/MM/yy", Locale.US),
            SimpleDateFormat("yyyy-MM-dd", Locale.US)
        )
        val DB_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        
        // Valid size codes
        val VALID_SIZE_CODES = setOf("QQ", "PP", "NN", "DD")
    }
    
    /**
     * Import purchases with MULTI-SHIPMENT support
     * Automatically detects multiple shipments in single sheet
     */
    suspend fun importPurchases(
        uri: Uri, 
        products: List<Product>,
        purchaseRepository: PurchaseRepository
    ): ImportResult {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: return ImportResult(0, 0, 0, listOf("Failed to open file"))
        
        return try {
            val workbook = XSSFWorkbook(inputStream)
            val sheet = workbook.getSheetAt(0)
            
            android.util.Log.d("PurchaseExcelHelper", "=== MULTI-SHIPMENT IMPORT ===")
            
            // Detect all shipments in the sheet
            val shipments = detectShipments(sheet)
            
            android.util.Log.d("PurchaseExcelHelper", "Detected ${shipments.size} shipment(s) in Excel")
            
            if (shipments.isEmpty()) {
                return ImportResult(0, 0, 0, listOf("No valid shipments found in Excel"))
            }
            
            // Create product lookup map - includes primary code AND all aliases
            val productMap = buildProductLookupMap(products)
            
            android.util.Log.d("PurchaseExcelHelper", "Product lookup map size: ${productMap.size} (includes aliases)")
            
            val allPurchases = mutableListOf<Purchase>()
            val allErrors = mutableListOf<String>()
            val allWarnings = mutableListOf<String>()
            var totalSkipped = 0
            
            // Process each shipment
            for ((index, shipment) in shipments.withIndex()) {
                android.util.Log.d("PurchaseExcelHelper", "Processing Shipment ${index + 1}: Invoice=${shipment.invoiceNumber}, Date=${shipment.date}")
                
                val result = processShipment(
                    sheet,
                    shipment,
                    productMap,
                    purchaseRepository
                )
                
                allPurchases.addAll(result.purchases)
                allErrors.addAll(result.errors.map { "Shipment ${index + 1}: $it" })
                allWarnings.addAll(result.warnings.map { "Shipment ${index + 1}: $it" })
                totalSkipped += result.skippedCount
                
                android.util.Log.d("PurchaseExcelHelper", "Shipment ${index + 1} result: ${result.successCount} imported, ${result.skippedCount} skipped, ${result.failCount} failed")
            }
            
            workbook.close()
            inputStream.close()
            
            android.util.Log.d("PurchaseExcelHelper", "Total: ${allPurchases.size} new, $totalSkipped skipped, ${allErrors.size} errors")
            
            ImportResult(
                successCount = allPurchases.size,
                failCount = allErrors.size,
                skippedCount = totalSkipped,
                errors = allErrors,
                warnings = allWarnings,
                purchases = allPurchases
            )
            
        } catch (e: Exception) {
            inputStream.close()
            android.util.Log.e("PurchaseExcelHelper", "Import error", e)
            ImportResult(0, 0, 0, listOf("Import failed: ${e.message}"))
        }
    }
    
    /**
     * Detect all shipments in the sheet
     * A shipment starts with "INVOICE NUMBER" in column A
     */
    private fun detectShipments(sheet: Sheet): List<ShipmentInfo> {
        val shipments = mutableListOf<ShipmentInfo>()
        val maxDate = run {
            val cal = java.util.Calendar.getInstance()
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)
        }
        var rowNum = 0
        
        while (rowNum <= sheet.lastRowNum) {
            val row = sheet.getRow(rowNum)
            if (row == null) {
                rowNum++
                continue
            }
            
            val cellValue = getCellValue(row.getCell(0))
            
            // Check if this row starts a shipment
            if (cellValue?.uppercase()?.contains("INVOICE") == true) {
                // Found shipment header
                val invoiceNumber = getCellValue(row.getCell(1)) ?: ""
                
                // Next row should be DATE
                val dateRow = sheet.getRow(rowNum + 1)
                val dateCell = dateRow?.getCell(1)
                val dateStr = getCellValueAsDateString(dateCell)
                val parsedDate = parseDateWithFormats(dateStr)

                // Col 3 on the DATE row = received date (col 2 is the label "RECEIVED DATE").
                // Guard: only parse if the raw string looks like a date (contains '/' or '-').
                // Old files have the invoice amount (e.g. "12345") at col 3 — without this
                // guard parseDateWithFormats would fall back to today's date for those numbers.
                val receivedDateStr = getCellValueAsDateString(dateRow?.getCell(3))
                val looksLikeDate = receivedDateStr.contains('/') || receivedDateStr.contains('-')
                val parsedReceivedDate = if (looksLikeDate)
                    parseDateWithFormats(receivedDateStr) else ""

                // Headers should be at rowNum + 2
                // Data starts at rowNum + 3
                val dataStartRow = rowNum + 3

                shipments.add(ShipmentInfo(
                    startRow      = rowNum,
                    invoiceNumber = invoiceNumber,
                    date          = parsedDate,
                    dateStr       = dateStr,
                    receivedDate  = if (parsedReceivedDate.isBlank() || parsedReceivedDate <= parsedDate || parsedReceivedDate > maxDate) "" else parsedReceivedDate,
                    dataStartRow  = dataStartRow
                ))
                
                android.util.Log.d("PurchaseExcelHelper", "Found shipment at row $rowNum: Invoice=$invoiceNumber, Date=$dateStr->$parsedDate")
                
                // Skip past the header rows
                rowNum += 3
            } else {
                rowNum++
            }
        }
        
        // Set end row for each shipment
        for (i in 0 until shipments.size) {
            val endRow = if (i < shipments.size - 1) {
                shipments[i + 1].startRow - 1
            } else {
                sheet.lastRowNum
            }
            shipments[i] = shipments[i].copy(dataEndRow = endRow)
        }
        
        return shipments
    }
    
    /**
     * Process a single shipment
     */
    private suspend fun processShipment(
        sheet: Sheet,
        shipment: ShipmentInfo,
        productMap: Map<String, Product>,
        purchaseRepository: PurchaseRepository
    ): ImportResult {
        val purchases = mutableListOf<Purchase>()
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var skippedCount = 0
        
        // Group rows by product code
        val productGroups = mutableMapOf<String, MutableList<ExcelRow>>()
        
        var rowNum = shipment.dataStartRow
        while (rowNum <= shipment.dataEndRow) {
            val row = sheet.getRow(rowNum)
            if (row == null) {
                rowNum++
                continue
            }
            
            // Check if we hit another shipment header
            val cellValue = getCellValue(row.getCell(0))
            if (cellValue?.uppercase()?.contains("INVOICE") == true) {
                break
            }
            
            try {
                val excelRow = parseRow(row, rowNum + 1)
                if (excelRow != null) {
                    // Validate size code
                    if (!VALID_SIZE_CODES.contains(excelRow.sizeCode.uppercase())) {
                        errors.add("Row ${rowNum + 1}: Invalid size code '${excelRow.sizeCode}' for ${excelRow.productName}. Must be QQ, PP, NN, or DD")
                        rowNum++
                        continue
                    }
                    
                    // Check if quantity is valid
                    if (excelRow.qtyBoxes == 0 && excelRow.qtyUnits == 0) {
                        warnings.add("Row ${rowNum + 1}: ${excelRow.productName} ${excelRow.sizeCode} has 0 quantity - skipped")
                        rowNum++
                        continue
                    }
                    
                    // Resolve alias → primary code before grouping.
                    // Rows with different alias codes for the same product
                    // (e.g. WC360 QQ and WA360 PP) must land in the same group
                    // so all their sizes end up in one Purchase object.
                    val rawCode  = "${excelRow.productType}${excelRow.brandCode}"
                    val product  = productMap[rawCode]
                    if (product == null) {
                        errors.add("Row ${rowNum + 1}: Product not found: $rawCode (${excelRow.productName})")
                        rowNum++
                        continue
                    }
                    val groupKey = ProductCodeResolver.primaryCode(product)
                    productGroups.getOrPut(groupKey) { mutableListOf() }.add(excelRow)
                }
            } catch (e: Exception) {
                errors.add("Row ${rowNum + 1}: ${e.message}")
            }
            
            rowNum++
        }
        
        // Check for existing purchases with same invoice and date
        val existingPurchases = purchaseRepository.getPurchasesByInvoiceAndDate(shipment.invoiceNumber, shipment.date)
        val existingProductIds = existingPurchases.map { it.productId }.toSet()
        
        // Create Purchase objects — productCode is already the primary code
        for ((productCode, rows) in productGroups) {
            // product was already resolved when building productGroups;
            // re-resolve here for safety (productCode is the primary code)
            val product = productMap[productCode]
                ?: productMap.values.firstOrNull {
                    ProductCodeResolver.primaryCode(it) == productCode
                }
            if (product == null) {
                errors.add("Product not found: $productCode (${rows.first().productName})")
                continue
            }
            
            // Check if already imported
            if (existingProductIds.contains(product.id)) {
                skippedCount++
                warnings.add("Skipped: ${product.displayName} (already imported with invoice ${shipment.invoiceNumber} on ${shipment.date})")
                continue
            }
            
            // Aggregate quantities by size
            var qqBoxes = 0; var qqLoose = 0; var qqUnitPrice = 0.0
            var ppBoxes = 0; var ppLoose = 0; var ppUnitPrice = 0.0
            var nnBoxes = 0; var nnLoose = 0; var nnUnitPrice = 0.0
            var ddBoxes = 0; var ddLoose = 0; var ddUnitPrice = 0.0
            
            for (row in rows) {
                when (row.sizeCode.uppercase()) {
                    "QQ" -> { qqBoxes += row.qtyBoxes; qqLoose += row.qtyUnits; if (row.purchasePrice > 0) qqUnitPrice = row.purchasePrice }
                    "PP" -> { ppBoxes += row.qtyBoxes; ppLoose += row.qtyUnits; if (row.purchasePrice > 0) ppUnitPrice = row.purchasePrice }
                    "NN" -> { nnBoxes += row.qtyBoxes; nnLoose += row.qtyUnits; if (row.purchasePrice > 0) nnUnitPrice = row.purchasePrice }
                    "DD" -> { ddBoxes += row.qtyBoxes; ddLoose += row.qtyUnits; if (row.purchasePrice > 0) ddUnitPrice = row.purchasePrice }
                }
            }
            
            // Create Purchase object with shipment date
            val purchase = Purchase(
                purchaseDate = shipment.date,
                receivedDate = shipment.receivedDate,
                productId = product.id,
                productCode = ProductCodeResolver.primaryCode(product),  // always primary
                productName = product.displayName,
                
                qqBoxes = qqBoxes, qqLoose = qqLoose, qqUnitsPerBox = product.qqUnitsPerBox,
                qqTotalUnits = Purchase.calculateTotalUnits(qqBoxes, qqLoose, product.qqUnitsPerBox),
                qqUnitPrice = if (qqUnitPrice > 0) qqUnitPrice else product.qqPurchasePrice,
                qqTotalCost = Purchase.calculateTotalCost(
                    Purchase.calculateTotalUnits(qqBoxes, qqLoose, product.qqUnitsPerBox),
                    if (qqUnitPrice > 0) qqUnitPrice else product.qqPurchasePrice
                ),
                
                ppBoxes = ppBoxes, ppLoose = ppLoose, ppUnitsPerBox = product.ppUnitsPerBox,
                ppTotalUnits = Purchase.calculateTotalUnits(ppBoxes, ppLoose, product.ppUnitsPerBox),
                ppUnitPrice = if (ppUnitPrice > 0) ppUnitPrice else product.ppPurchasePrice,
                ppTotalCost = Purchase.calculateTotalCost(
                    Purchase.calculateTotalUnits(ppBoxes, ppLoose, product.ppUnitsPerBox),
                    if (ppUnitPrice > 0) ppUnitPrice else product.ppPurchasePrice
                ),
                
                nnBoxes = nnBoxes, nnLoose = nnLoose, nnUnitsPerBox = product.nnUnitsPerBox,
                nnTotalUnits = Purchase.calculateTotalUnits(nnBoxes, nnLoose, product.nnUnitsPerBox),
                nnUnitPrice = if (nnUnitPrice > 0) nnUnitPrice else product.nnPurchasePrice,
                nnTotalCost = Purchase.calculateTotalCost(
                    Purchase.calculateTotalUnits(nnBoxes, nnLoose, product.nnUnitsPerBox),
                    if (nnUnitPrice > 0) nnUnitPrice else product.nnPurchasePrice
                ),
                
                ddBoxes = ddBoxes, ddLoose = ddLoose, ddUnitsPerBox = product.ddUnitsPerBox,
                ddTotalUnits = Purchase.calculateTotalUnits(ddBoxes, ddLoose, product.ddUnitsPerBox),
                ddUnitPrice = if (ddUnitPrice > 0) ddUnitPrice else product.ddPurchasePrice,
                ddTotalCost = Purchase.calculateTotalCost(
                    Purchase.calculateTotalUnits(ddBoxes, ddLoose, product.ddUnitsPerBox),
                    if (ddUnitPrice > 0) ddUnitPrice else product.ddPurchasePrice
                ),
                
                totalCost = 0.0,
                supplierName = "",
                invoiceNumber = shipment.invoiceNumber,
                notes = "Imported from Excel - Multi-shipment"
            )
            
            val totalCost = purchase.qqTotalCost + purchase.ppTotalCost + purchase.nnTotalCost + purchase.ddTotalCost
            purchases.add(purchase.copy(totalCost = totalCost))
        }
        
        return ImportResult(
            successCount = purchases.size,
            failCount = errors.size,
            skippedCount = skippedCount,
            errors = errors,
            warnings = warnings,
            purchases = purchases
        )
    }
    
    private fun parseDateWithFormats(dateStr: String): String {
        if (dateStr.isBlank()) return DB_DATE_FORMAT.format(Date())
        
        for (format in DATE_FORMATS) {
            try {
                val parsed = format.parse(dateStr) ?: continue
                val cal = java.util.Calendar.getInstance()
                cal.time = parsed
                val year = cal.get(java.util.Calendar.YEAR)
                if (year < 2000 || year > 2100) continue
                return DB_DATE_FORMAT.format(parsed)
            } catch (e: Exception) { }
        }
        
        android.util.Log.w("PurchaseExcelHelper", "Could not parse date '$dateStr', using today")
        return DB_DATE_FORMAT.format(Date())
    }
    
    /**
     * Export purchases to Excel file - GROUPED BY INVOICE AND DATE
     * Creates multi-shipment format matching import structure
     */
    fun exportPurchases(
        purchases: List<Purchase>,
        @Suppress("UNUSED_PARAMETER") invoiceNumber: String = "",
        @Suppress("UNUSED_PARAMETER") date: String = "",
        fileName: String
    ): Uri {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Purchases")
        
        val headerStyle = workbook.createCellStyle().apply {
            val font = workbook.createFont()
            font.bold = true
            setFont(font)
        }
        
        // Group purchases by invoice + date
        val grouped = purchases.groupBy { 
            Pair(it.invoiceNumber, it.purchaseDate) 
        }.toSortedMap(compareBy({ it.second }, { it.first })) // Sort by date, then invoice
        
        var currentRow = 0
        
        for ((invoiceDatePair, shipmentPurchases) in grouped) {
            val shipmentInvoice = invoiceDatePair.first
            val shipmentDate = invoiceDatePair.second
            
            // Calculate total invoice amount
            val invoiceAmount = shipmentPurchases.sumOf { it.totalCost }
            
            // Format date for display (convert from yyyy-MM-dd to dd/MM/yyyy)
            val displayDate = try {
                val dbFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val displayFormat = SimpleDateFormat("dd/MM/yyyy", Locale.US)
                val parsedDate = dbFormat.parse(shipmentDate)
                if (parsedDate != null) displayFormat.format(parsedDate) else shipmentDate
            } catch (e: Exception) {
                shipmentDate
            }
            
            // Shipment header - Row 1: INVOICE NUMBER
            val invoiceRow = sheet.createRow(currentRow++)
            invoiceRow.createCell(0).apply {
                setCellValue("INVOICE NUMBER")
                cellStyle = headerStyle
            }
            invoiceRow.createCell(1).setCellValue(shipmentInvoice)
            
            // Shipment header - Row 2: DATE, RECEIVED DATE, Invoice Amount
            val dateRow = sheet.createRow(currentRow++)
            dateRow.createCell(0).apply { setCellValue("DATE"); cellStyle = headerStyle }
            dateRow.createCell(1).setCellValue(displayDate)
            dateRow.createCell(2).apply { setCellValue("RECEIVED DATE"); cellStyle = headerStyle }
            // Received date value — show invoice date when blank (same day)
            val effectiveReceived = shipmentPurchases.firstOrNull()
                ?.receivedDate?.ifBlank { shipmentDate } ?: shipmentDate
            val displayReceived = try {
                val dbFmt   = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val dispFmt = SimpleDateFormat("dd/MM/yyyy", Locale.US)
                dbFmt.parse(effectiveReceived)?.let { dispFmt.format(it) } ?: effectiveReceived
            } catch (_: Exception) { effectiveReceived }
            dateRow.createCell(3).setCellValue(displayReceived)
            dateRow.createCell(4).apply { setCellValue("Invoice Amount"); cellStyle = headerStyle }
            dateRow.createCell(5).setCellValue(invoiceAmount)
            
            // Column Headers - Row 3
            val headerRow = sheet.createRow(currentRow++)
            val headers = arrayOf(
                "S. NO", "Brand Code", "Product Name", "Product Type", 
                "Product Category", "Size Code", "Size", "Qty Boxes", 
                "Qty Loose", "Qty Units", "Purchase Price", "Purchase Amount"
            )
            headers.forEachIndexed { index, header ->
                headerRow.createCell(index).apply {
                    setCellValue(header)
                    cellStyle = headerStyle
                }
            }
            
            // Data rows for this shipment
            var sno = 1
            
            for (purchase in shipmentPurchases) {
                val productCode = purchase.productCode
                val brandCode = if (productCode.length > 1) productCode.substring(1) else productCode
                val productType = if (productCode.isNotEmpty()) productCode.substring(0, 1) else ""
                
                // QQ rows
                if (purchase.qqBoxes > 0 || purchase.qqLoose > 0) {
                    val row = sheet.createRow(currentRow++)
                    row.createCell(0).setCellValue(sno++.toDouble())
                    row.createCell(1).setCellValue(brandCode)
                    row.createCell(2).setCellValue(purchase.productName)
                    row.createCell(3).setCellValue(productType)
                    row.createCell(4).setCellValue("G")
                    row.createCell(5).setCellValue("QQ")
                    row.createCell(6).setCellValue("750")
                    row.createCell(7).setCellValue(purchase.qqBoxes.toDouble())
                    row.createCell(8).setCellValue(purchase.qqLoose.toDouble())
                    row.createCell(9).setCellValue(purchase.qqTotalUnits.toDouble())
                    row.createCell(10).setCellValue(purchase.qqUnitPrice)
                    row.createCell(11).setCellValue(purchase.qqTotalCost)
                }
                
                // PP rows
                if (purchase.ppBoxes > 0 || purchase.ppLoose > 0) {
                    val row = sheet.createRow(currentRow++)
                    row.createCell(0).setCellValue(sno++.toDouble())
                    row.createCell(1).setCellValue(brandCode)
                    row.createCell(2).setCellValue(purchase.productName)
                    row.createCell(3).setCellValue(productType)
                    row.createCell(4).setCellValue("G")
                    row.createCell(5).setCellValue("PP")
                    row.createCell(6).setCellValue("375")
                    row.createCell(7).setCellValue(purchase.ppBoxes.toDouble())
                    row.createCell(8).setCellValue(purchase.ppLoose.toDouble())
                    row.createCell(9).setCellValue(purchase.ppTotalUnits.toDouble())
                    row.createCell(10).setCellValue(purchase.ppUnitPrice)
                    row.createCell(11).setCellValue(purchase.ppTotalCost)
                }
                
                // NN rows
                if (purchase.nnBoxes > 0 || purchase.nnLoose > 0) {
                    val row = sheet.createRow(currentRow++)
                    row.createCell(0).setCellValue(sno++.toDouble())
                    row.createCell(1).setCellValue(brandCode)
                    row.createCell(2).setCellValue(purchase.productName)
                    row.createCell(3).setCellValue(productType)
                    row.createCell(4).setCellValue("G")
                    row.createCell(5).setCellValue("NN")
                    row.createCell(6).setCellValue("180")
                    row.createCell(7).setCellValue(purchase.nnBoxes.toDouble())
                    row.createCell(8).setCellValue(purchase.nnLoose.toDouble())
                    row.createCell(9).setCellValue(purchase.nnTotalUnits.toDouble())
                    row.createCell(10).setCellValue(purchase.nnUnitPrice)
                    row.createCell(11).setCellValue(purchase.nnTotalCost)
                }
                
                // DD rows
                if (purchase.ddBoxes > 0 || purchase.ddLoose > 0) {
                    val row = sheet.createRow(currentRow++)
                    row.createCell(0).setCellValue(sno++.toDouble())
                    row.createCell(1).setCellValue(brandCode)
                    row.createCell(2).setCellValue(purchase.productName)
                    row.createCell(3).setCellValue(productType)
                    row.createCell(4).setCellValue("P")
                    row.createCell(5).setCellValue("DD")
                    row.createCell(6).setCellValue("90")
                    row.createCell(7).setCellValue(purchase.ddBoxes.toDouble())
                    row.createCell(8).setCellValue(purchase.ddLoose.toDouble())
                    row.createCell(9).setCellValue(purchase.ddTotalUnits.toDouble())
                    row.createCell(10).setCellValue(purchase.ddUnitPrice)
                    row.createCell(11).setCellValue(purchase.ddTotalCost)
                }
            }
            
            // Add empty rows between shipments (except after last one)
            if (invoiceDatePair != grouped.keys.last()) {
                currentRow += 2
            }
        }
        
        // Set column widths (data cols 0-11; header DATE row also uses cols 4-5 for Invoice Amount)
        sheet.setColumnWidth(0, 2000)   // S.NO
        sheet.setColumnWidth(1, 3500)   // Brand Code / invoice date
        sheet.setColumnWidth(2, 4000)   // Product Name / RECEIVED DATE label
        sheet.setColumnWidth(3, 3500)   // Product Type / received date value
        sheet.setColumnWidth(4, 3000)   // Product Category / Invoice Amount label
        sheet.setColumnWidth(5, 4000)   // Size Code / invoice amount value
        sheet.setColumnWidth(6, 2500)   // Size
        sheet.setColumnWidth(7, 3000)   // Qty Boxes
        sheet.setColumnWidth(8, 3000)   // Qty Loose
        sheet.setColumnWidth(9, 3000)   // Qty Units
        sheet.setColumnWidth(10, 4000)  // Purchase Price
        sheet.setColumnWidth(11, 4500)  // Purchase Amount
        
        val file = File(context.getExternalFilesDir(null), fileName)
        FileOutputStream(file).use { workbook.write(it) }
        workbook.close()
        
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }
    
    /**
     * Generate template with multi-shipment example
     */
    fun generateTemplate(products: List<Product>, fileName: String): Uri {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Multi-Shipment Template")
        
        val headerStyle = workbook.createCellStyle().apply {
            val font = workbook.createFont()
            font.bold = true
            setFont(font)
        }
        
        val noteStyle = workbook.createCellStyle().apply {
            val font = workbook.createFont()
            font.italic = true
            font.color = IndexedColors.BLUE.index
            setFont(font)
        }
        
        var currentRow = 0
        
        // Shipment 1
        sheet.createRow(currentRow++).apply {
            createCell(0).apply { setCellValue("INVOICE NUMBER"); cellStyle = headerStyle }
            createCell(1).setCellValue("TP08726")
            createCell(3).apply { setCellValue("← Shipment 1"); cellStyle = noteStyle }
        }
        sheet.createRow(currentRow++).apply {
            createCell(0).apply { setCellValue("DATE"); cellStyle = headerStyle }
            createCell(1).setCellValue("5/2/26")
            createCell(2).apply { setCellValue("RECEIVED DATE"); cellStyle = headerStyle }
            createCell(3).setCellValue("7/2/26")   // example: received 2 days later
        }
        
        val headers = arrayOf("S. NO", "Brand Code", "Product Name", "Product Type", 
            "Product Category", "Size Code", "Size", "Qty Boxes", "Qty Units", "Purchase Price")
        sheet.createRow(currentRow++).apply {
            headers.forEachIndexed { index, header ->
                createCell(index).apply { setCellValue(header); cellStyle = headerStyle }
            }
        }
        
        var sno = 1
        products.take(2).forEach { product ->
            sheet.createRow(currentRow++).apply {
                createCell(0).setCellValue(sno++.toDouble())
                createCell(1).setCellValue(product.brandCode)
                createCell(2).setCellValue(product.displayName)
                createCell(3).setCellValue(product.productType)
                createCell(4).setCellValue("G")
                createCell(5).setCellValue("QQ")
                createCell(6).setCellValue("750")
                createCell(7).setCellValue(1.0)
                createCell(8).setCellValue(0.0)
                createCell(9).setCellValue(product.qqPurchasePrice)
            }
        }
        
        // Empty rows
        currentRow += 3
        
        // Shipment 2
        sheet.createRow(currentRow++).apply {
            createCell(0).apply { setCellValue("INVOICE NUMBER"); cellStyle = headerStyle }
            createCell(1).setCellValue("TP08727")
            createCell(3).apply { setCellValue("← Shipment 2 (Different invoice & date)"); cellStyle = noteStyle }
        }
        sheet.createRow(currentRow++).apply {
            createCell(0).apply { setCellValue("DATE"); cellStyle = headerStyle }
            createCell(1).setCellValue("7/2/26")
            createCell(2).apply { setCellValue("RECEIVED DATE"); cellStyle = headerStyle }
            createCell(3).setCellValue("7/2/26")   // same day in this example
        }

        sheet.createRow(currentRow++).apply {
            headers.forEachIndexed { index, header ->
                createCell(index).apply { setCellValue(header); cellStyle = headerStyle }
            }
        }
        
        sno = 1
        products.drop(2).take(2).forEach { product ->
            sheet.createRow(currentRow++).apply {
                createCell(0).setCellValue(sno++.toDouble())
                createCell(1).setCellValue(product.brandCode)
                createCell(2).setCellValue(product.displayName)
                createCell(3).setCellValue(product.productType)
                createCell(4).setCellValue("G")
                createCell(5).setCellValue("NN")
                createCell(6).setCellValue("180")
                createCell(7).setCellValue(2.0)
                createCell(8).setCellValue(0.0)
                createCell(9).setCellValue(product.nnPurchasePrice)
            }
        }
        
        sheet.setColumnWidth(0, 2000); sheet.setColumnWidth(1, 3500); sheet.setColumnWidth(2, 8000)
        sheet.setColumnWidth(3, 2500); sheet.setColumnWidth(4, 3000); sheet.setColumnWidth(5, 3000)
        sheet.setColumnWidth(6, 2500); sheet.setColumnWidth(7, 3000); sheet.setColumnWidth(8, 3000)
        sheet.setColumnWidth(9, 4000)
        
        val file = File(context.getExternalFilesDir(null), fileName)
        FileOutputStream(file).use { workbook.write(it) }
        workbook.close()
        
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
    
    private fun parseRow(row: Row, rowNumber: Int): ExcelRow? {
        if (getCellValue(row.getCell(COL_BRAND_CODE)).isNullOrBlank()) return null
        
        return try {
            ExcelRow(
                sno = getCellValueAsInt(row.getCell(COL_SNO)),
                brandCode = getCellValue(row.getCell(COL_BRAND_CODE)) ?: "",
                productName = getCellValue(row.getCell(COL_PRODUCT_NAME)) ?: "",
                productType = getCellValue(row.getCell(COL_PRODUCT_TYPE)) ?: "",
                productCategory = getCellValue(row.getCell(COL_PRODUCT_CATEGORY)) ?: "",
                sizeCode = getCellValue(row.getCell(COL_SIZE_CODE)) ?: "",
                size = getCellValue(row.getCell(COL_SIZE)) ?: "",
                qtyBoxes = getCellValueAsInt(row.getCell(COL_QTY_BOXES)),
                qtyUnits = getCellValueAsInt(row.getCell(COL_QTY_UNITS)),
                purchasePrice = getCellValueAsDouble(row.getCell(COL_PURCHASE_PRICE))
            )
        } catch (e: Exception) {
            throw Exception("Error parsing row $rowNumber: ${e.message}")
        }
    }
    
    private fun getCellValue(cell: Cell?): String? {
        return when (cell?.cellType) {
            CellType.STRING -> cell.stringCellValue
            CellType.NUMERIC -> cell.numericCellValue.toInt().toString()
            CellType.BLANK -> null
            else -> null
        }
    }
    
    private fun getCellValueAsDateString(cell: Cell?): String {
        if (cell == null) return ""
        
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue
            CellType.NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    try {
                        val date = cell.dateCellValue
                        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
                    } catch (e: Exception) {
                        cell.numericCellValue.toInt().toString()
                    }
                } else {
                    cell.numericCellValue.toInt().toString()
                }
            }
            else -> ""
        }
    }
    
    private fun getCellValueAsInt(cell: Cell?): Int {
        return when (cell?.cellType) {
            CellType.NUMERIC -> cell.numericCellValue.toInt()
            CellType.STRING -> cell.stringCellValue.toIntOrNull() ?: 0
            else -> 0
        }
    }
    
    private fun getCellValueAsDouble(cell: Cell?): Double {
        return when (cell?.cellType) {
            CellType.NUMERIC -> cell.numericCellValue
            CellType.STRING -> cell.stringCellValue.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
    }
    
    /**
     * Build product lookup map that includes primary brand code AND all aliases
     * Example: Product with brandCode="1177" and aliases="A538,C538"
     * Creates map entries: W1177→Product, WA538→Product, WC538→Product
     */
    private fun buildProductLookupMap(products: List<Product>): Map<String, Product> {
        val map = mutableMapOf<String, Product>()
        
        for (product in products) {
            val productType = product.productType
            
            // Add primary brand code
            val primaryCode = "$productType${product.brandCode}"
            map[primaryCode] = product
            
            // Add all aliases
            if (product.aliases.isNotBlank()) {
                val aliasList = product.aliases.split(",").map { it.trim() }
                for (alias in aliasList) {
                    if (alias.isNotEmpty()) {
                        val aliasCode = "$productType$alias"
                        map[aliasCode] = product
                        android.util.Log.d("PurchaseExcelHelper", 
                            "Alias mapping: $aliasCode → ${product.displayName} (primary: $primaryCode)")
                    }
                }
            }
        }
        
        return map
    }
    
    data class ShipmentInfo(
        val startRow: Int,
        val invoiceNumber: String,
        val date: String,
        val dateStr: String,
        val receivedDate: String = "",  // blank = same as date
        val dataStartRow: Int,
        val dataEndRow: Int = Int.MAX_VALUE
    )
    
    data class ExcelRow(
        val sno: Int,
        val brandCode: String,
        val productName: String,
        val productType: String,
        val productCategory: String,
        val sizeCode: String,
        val size: String,
        val qtyBoxes: Int,
        val qtyUnits: Int,
        val purchasePrice: Double
    )
    
    data class ImportResult(
        val successCount: Int,
        val failCount: Int,
        val skippedCount: Int,
        val errors: List<String>,
        val warnings: List<String> = emptyList(),
        val purchases: List<Purchase> = emptyList()
    )
}
