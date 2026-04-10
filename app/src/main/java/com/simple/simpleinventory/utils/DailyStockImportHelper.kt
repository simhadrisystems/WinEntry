package com.simple.simpleinventory.utils

import android.content.Context
import android.net.Uri
import com.simple.simpleinventory.data.entity.Product
import com.simple.simpleinventory.data.entity.getAllBrandCodes
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.InputStream

class DailyStockImportHelper(private val context: Context) {

    // ─────────────────────────────────────────────────────────────
    // Data classes
    // ─────────────────────────────────────────────────────────────

    data class ImportResult(
        val success: Boolean,
        val message: String,
        val importType: String = "",
        val purchaseData: Map<String, PurchaseImport> = emptyMap(),
        val closingData: Map<String, ClosingImport> = emptyMap(),
        val saleData: Map<String, SaleImport> = emptyMap(),
        val unknownProducts: List<String> = emptyList(), // non-zero CB, not in app
        val allZeroSkipped: List<String> = emptyList()   // all-zero CB rows skipped
    )

    data class PurchaseImport(
        val productCode: String,
        val productType: String,
        val brandCode: String,
        val date: String,
        val qqPurchase: Int,
        val ppPurchase: Int,
        val nnPurchase: Int,
        val ddPurchase: Int
    )

    data class SaleImport(
        val productCode: String,
        val productType: String,
        val brandCode: String,
        val date: String,
        val qqSale: Int,
        val ppSale: Int,
        val nnSale: Int,
        val ddSale: Int
    )

    data class ClosingImport(
        val productCode: String,
        val productType: String,
        val brandCode: String,
        val date: String,
        val qqClosing: Int,
        val ppClosing: Int,
        val nnClosing: Int,
        val ddClosing: Int
    )

    // ─────────────────────────────────────────────────────────────
    // importClosingOnly
    //
    // Accepts output of "Export CBs Current Date" / "Export CBs Date Range"
    // Column layout (0-based):
    //   0: DATE_CLOSING   1: PRODUCT_TYPE   2: BRAND_CODE
    //   3: PRODUCT_NAME   ← ignored
    //   4: QQ_CLOSING     5: PP_CLOSING     6: NN_CLOSING    7: DD_CLOSING
    //
    // Rules:
    //   • Blank rows (empty DATE_CLOSING) → skipped silently
    //   • All-zero CB rows → skipped, collected in allZeroSkipped
    //   • Unknown products (non-zero CB) → skipped, collected in unknownProducts
    //   • Existing CB overwritten (upsert)
    //   • Date comes from each row's DATE_CLOSING column (multi-date support)
    // ─────────────────────────────────────────────────────────────
    fun importClosingOnly(uri: Uri, products: List<Product>): ImportResult {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return ImportResult(false, "Cannot open file")

            val workbook = XSSFWorkbook(inputStream)
            val sheet = workbook.getSheet("Closing")
                ?: workbook.getSheet("Closing_Balances")
                ?: if (workbook.numberOfSheets > 0) workbook.getSheetAt(0) else null

            if (sheet == null) {
                workbook.close(); inputStream.close()
                return ImportResult(false, "No sheet found in file")
            }

            // ── Build lookup map ──────────────────────────────────
            // For each product, register ALL brand codes (primary + aliases)
            // with THREE key variants each to survive Excel's zero-stripping:
            //   1. exact       "W0239"  — as stored in DB
            //   2. stripped    "W239"   — leading zeros removed (what Excel produces)
            //   3. padded      "W0239"  — zero-padded to 4 chars
            // putIfAbsent keeps primary brandCode priority over aliases.
            val productsByKey = mutableMapOf<String, Product>()
            products.forEach { p ->
                p.getAllBrandCodes().forEach { code ->
                    val type = p.productType
                    val stripped = code.trimStart('0').ifEmpty { "0" }
                    val padded   = code.padStart(4, '0')
                    listOf("$type$code", "$type$stripped", "$type$padded")
                        .forEach { key -> productsByKey.putIfAbsent(key, p) }
                }
            }
            android.util.Log.d("ImportClosing",
                "Lookup map: ${productsByKey.size} keys for ${products.size} products. " +
                "Sample: ${productsByKey.keys.take(6).toList()}")

            val closingData     = mutableMapOf<String, ClosingImport>()
            val unknownProducts = mutableListOf<String>() // non-zero CB, not found
            var skippedBlank    = 0
            var skippedUnknown  = 0

            // Detect header row — scan first 3 rows for "DATE_CLOSING"
            var dataStartRow = 1
            for (i in 0..2) {
                val row = sheet.getRow(i) ?: continue
                if (getCellString(row, 0).trim().uppercase() == "DATE_CLOSING") {
                    dataStartRow = i + 1
                    break
                }
            }
            android.util.Log.d("ImportClosing",
                "Data starts at row $dataStartRow, lastRowNum=${sheet.lastRowNum}")

            for (rowIndex in dataStartRow..sheet.lastRowNum) {
                val row = sheet.getRow(rowIndex)
                if (row == null) { skippedBlank++; continue }

                // Column 0: DATE_CLOSING
                val rawDate = getCellString(row, 0).trim()
                if (rawDate.isBlank()) { skippedBlank++; continue }

                val parsedDate = parseDate(rawDate)
                if (parsedDate.isEmpty()) {
                    android.util.Log.w("ImportClosing",
                        "Row $rowIndex: unparseable date '$rawDate' — skipped")
                    skippedBlank++
                    continue
                }

                // Column 1: PRODUCT_TYPE
                val productType = getCellString(row, 1).trim()
                // Column 2: BRAND_CODE — use raw reader to preserve numeric value
                val rawBrand    = getCellStringRaw(row, 2).trim()
                if (productType.isBlank() || rawBrand.isBlank()) {
                    skippedBlank++; continue
                }

                // Column 3: PRODUCT_NAME — intentionally ignored

                // Columns 4-7: closing quantities
                val qqClosing = getCellInt(row, 4)
                val ppClosing = getCellInt(row, 5)
                val nnClosing = getCellInt(row, 6)
                val ddClosing = getCellInt(row, 7)

                // ── Resolve product — try all three key variants ──
                // Note: zero CB rows are valid (product sold out) and are NOT skipped.
                val exactKey    = "$productType$rawBrand"
                val strippedKey = "$productType${rawBrand.trimStart('0').ifEmpty { "0" }}"
                val paddedKey   = "$productType${rawBrand.padStart(4, '0')}"

                val resolvedProduct = productsByKey[exactKey]
                    ?: productsByKey[strippedKey]
                    ?: productsByKey[paddedKey]

                android.util.Log.d("ImportClosing",
                    "Row $rowIndex: trying exact=$exactKey stripped=$strippedKey " +
                    "padded=$paddedKey → ${if (resolvedProduct != null) "FOUND" else "NOT FOUND"}")

                if (resolvedProduct == null) {
                    val tried = listOf(exactKey, strippedKey, paddedKey)
                        .distinct().joinToString(" / ")
                    val label = "$tried  (row ${rowIndex + 1}, date $parsedDate)" +
                        " — CB: QQ=$qqClosing PP=$ppClosing NN=$nnClosing DD=$ddClosing"
                    if (!unknownProducts.contains(label)) unknownProducts.add(label)
                    skippedUnknown++
                    android.util.Log.w("ImportClosing",
                        "Row $rowIndex: tried [$tried] — none in ${productsByKey.size}-key map")
                    continue
                }

                // Use canonical key from resolved product
                val canonicalKey = "${resolvedProduct.productType}${resolvedProduct.brandCode}"
                val key          = "${canonicalKey}_$parsedDate"
                closingData[key] = ClosingImport(
                    productCode = canonicalKey,
                    productType = resolvedProduct.productType,
                    brandCode   = resolvedProduct.brandCode,
                    date        = parsedDate,
                    qqClosing   = qqClosing,
                    ppClosing   = ppClosing,
                    nnClosing   = nnClosing,
                    ddClosing   = ddClosing
                )
            }

            workbook.close()
            inputStream.close()

            if (closingData.isEmpty() && unknownProducts.isEmpty()) {
                return ImportResult(false,
                    "No valid closing records found. " +
                    "Check DATE_CLOSING, PRODUCT_TYPE and BRAND_CODE columns.")
            }

            val msg = buildString {
                append("Found ${closingData.size} closing record(s)")
                if (skippedBlank   > 0) append(", $skippedBlank blank row(s) skipped")
                if (skippedUnknown > 0) append(", $skippedUnknown unknown product(s) skipped")
            }

            return ImportResult(
                success         = closingData.isNotEmpty(),
                message         = msg,
                importType      = "closing",
                closingData     = closingData,
                unknownProducts = unknownProducts
            )

        } catch (e: Exception) {
            android.util.Log.e("ImportClosing", "importClosingOnly failed", e)
            return ImportResult(false, "Import failed: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // importFromExcel, importPurchaseOnly, importSaleOnly
    // ─────────────────────────────────────────────────────────────

    fun importFromExcel(uri: Uri, products: List<Product>): ImportResult {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: return ImportResult(false, "Cannot open file")
        return try {
            val workbook       = XSSFWorkbook(inputStream)
            val productsByCode = products.associateBy { "${it.productType}${it.brandCode}" }
            val purchaseSheet  = workbook.getSheet("Purchase")
                ?: if (workbook.numberOfSheets > 0) workbook.getSheetAt(0) else null
            val purchaseData   = purchaseSheet?.let { importPurchaseSheet(it, productsByCode) } ?: emptyMap()
            val saleSheet      = workbook.getSheet("Sale")
                ?: if (workbook.numberOfSheets > 1) workbook.getSheetAt(1) else null
            val saleData       = saleSheet?.let { importSaleSheet(it, productsByCode) } ?: emptyMap()
            workbook.close(); inputStream.close()
            ImportResult(true,
                "Imported ${purchaseData.size} purchase records, ${saleData.size} sale records",
                purchaseData = purchaseData, saleData = saleData)
        } catch (e: Exception) {
            inputStream.close()
            ImportResult(false, "Import failed: ${e.message}")
        }
    }

    fun importPurchaseOnly(uri: Uri, products: List<Product>): ImportResult {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: return ImportResult(false, "Cannot open file")
        return try {
            val workbook       = XSSFWorkbook(inputStream)
            val productsByCode = products.associateBy { "${it.productType}${it.brandCode}" }
            val sheet          = workbook.getSheet("Purchase")
                ?: if (workbook.numberOfSheets > 0) workbook.getSheetAt(0) else null
            val purchaseData   = sheet?.let { importPurchaseSheet(it, productsByCode) } ?: emptyMap()
            workbook.close(); inputStream.close()
            ImportResult(true, "Imported ${purchaseData.size} purchase records",
                importType = "purchase", purchaseData = purchaseData)
        } catch (e: Exception) {
            inputStream.close()
            ImportResult(false, "Import failed: ${e.message}")
        }
    }

    fun importSaleOnly(uri: Uri, products: List<Product>): ImportResult {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: return ImportResult(false, "Cannot open file")
        return try {
            val workbook       = XSSFWorkbook(inputStream)
            val productsByCode = products.associateBy { "${it.productType}${it.brandCode}" }
            val sheet          = workbook.getSheet("Sale")
                ?: if (workbook.numberOfSheets > 0) workbook.getSheetAt(0) else null
            val saleData       = sheet?.let { importSaleSheet(it, productsByCode) } ?: emptyMap()
            workbook.close(); inputStream.close()
            ImportResult(true, "Imported ${saleData.size} sale records",
                importType = "sale", saleData = saleData)
        } catch (e: Exception) {
            inputStream.close()
            ImportResult(false, "Import failed: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Private sheet parsers
    // ─────────────────────────────────────────────────────────────

    private fun importPurchaseSheet(
        sheet: org.apache.poi.ss.usermodel.Sheet,
        productsByCode: Map<String, Product>
    ): Map<String, PurchaseImport> {
        val result = mutableMapOf<String, PurchaseImport>()
        var headerRow: Row? = null; var headerRowIndex = -1
        for (i in 0..2) {
            val row = sheet.getRow(i) ?: continue
            if (row.getCell(0)?.toString()?.trim()
                    ?.contains("Product", ignoreCase = true) == true) {
                headerRow = row; headerRowIndex = i; break
            }
        }
        if (headerRow == null) return result
        val colDate   = findColumn(headerRow, "DATE_PURCHASE")
        val colType   = findColumn(headerRow, "Type")
        val colBrand  = findColumn(headerRow, "Brand Code")
        val colQQ     = findColumn(headerRow, "QQ_PQ")
        val colPP     = findColumn(headerRow, "PP_PQ")
        val colNN     = findColumn(headerRow, "NN_PQ")
        val colDD     = findColumn(headerRow, "DD_PQ")
        for (ri in (headerRowIndex + 1)..sheet.lastRowNum) {
            val row   = sheet.getRow(ri) ?: continue
            val type  = getCellString(row, colType)
            val brand = getCellString(row, colBrand)
            val date  = parseDate(getCellString(row, colDate))
            if (type.isEmpty() || brand.isEmpty() || date.isEmpty()) continue
            val code  = "$type$brand"
            if (!productsByCode.containsKey(code)) continue
            result["${code}_$date"] = PurchaseImport(code, type, brand, date,
                getCellInt(row, colQQ), getCellInt(row, colPP),
                getCellInt(row, colNN), getCellInt(row, colDD))
        }
        return result
    }

    private fun importSaleSheet(
        sheet: org.apache.poi.ss.usermodel.Sheet,
        productsByCode: Map<String, Product>
    ): Map<String, SaleImport> {
        val result = mutableMapOf<String, SaleImport>()
        var headerRow: Row? = null; var headerRowIndex = -1
        for (i in 0..2) {
            val row = sheet.getRow(i) ?: continue
            if (row.getCell(0)?.toString()?.trim()
                    ?.contains("Product", ignoreCase = true) == true) {
                headerRow = row; headerRowIndex = i; break
            }
        }
        if (headerRow == null) return result
        val colDate  = findColumn(headerRow, "DATE_SALE")
        val colType  = findColumn(headerRow, "Type")
        val colBrand = findColumn(headerRow, "Brand Code")
        val colQQ    = findColumn(headerRow, "QQ_SQ")
        val colPP    = findColumn(headerRow, "PP_SQ")
        val colNN    = findColumn(headerRow, "NN_SQ")
        val colDD    = findColumn(headerRow, "DD_SQ")
        for (ri in (headerRowIndex + 1)..sheet.lastRowNum) {
            val row   = sheet.getRow(ri) ?: continue
            val type  = getCellString(row, colType)
            val brand = getCellString(row, colBrand)
            val date  = parseDate(getCellString(row, colDate))
            if (type.isEmpty() || brand.isEmpty() || date.isEmpty()) continue
            val code  = "$type$brand"
            if (!productsByCode.containsKey(code)) continue
            result["${code}_$date"] = SaleImport(code, type, brand, date,
                getCellInt(row, colQQ), getCellInt(row, colPP),
                getCellInt(row, colNN), getCellInt(row, colDD))
        }
        return result
    }

    // ─────────────────────────────────────────────────────────────
    // Cell readers
    // ─────────────────────────────────────────────────────────────

    private fun findColumn(row: Row, name: String): Int {
        for (i in 0 until row.lastCellNum)
            if (row.getCell(i)?.toString()?.trim().equals(name, ignoreCase = true)) return i
        return -1
    }

    /** Standard reader — numeric cells → Int → String (loses leading zeros, fine for non-code cols) */
    private fun getCellString(row: Row, colIndex: Int): String {
        if (colIndex < 0) return ""
        val cell = row.getCell(colIndex) ?: return ""
        return when (cell.cellType) {
            CellType.STRING  -> cell.stringCellValue.trim()
            CellType.NUMERIC -> cell.numericCellValue.toLong().toString()
            else             -> ""
        }
    }

    /**
     * Raw reader for BRAND_CODE column.
     * Numeric cells are read as Long to avoid "239.0" and preserve full integer value.
     * Leading zeros were already lost by Excel saving as numeric; the lookup map
     * compensates by registering stripped variants of each product's brand code.
     */
    private fun getCellStringRaw(row: Row, colIndex: Int): String {
        if (colIndex < 0) return ""
        val cell = row.getCell(colIndex) ?: return ""
        return when (cell.cellType) {
            CellType.STRING  -> cell.stringCellValue.trim()
            CellType.NUMERIC -> cell.numericCellValue.toLong().toString()
            else             -> ""
        }
    }

    private fun getCellInt(row: Row, colIndex: Int): Int {
        if (colIndex < 0) return 0
        val cell = row.getCell(colIndex) ?: return 0
        return when (cell.cellType) {
            CellType.NUMERIC -> cell.numericCellValue.toInt()
            CellType.STRING  -> {
                val s = cell.stringCellValue.trim()
                if (s.isEmpty() || s == "-") 0 else s.toIntOrNull() ?: 0
            }
            else -> 0
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Date parser — supports yyyy-MM-dd, dd/MM/yyyy, dd-MM-yyyy,
    //               and Excel numeric date serials
    // ─────────────────────────────────────────────────────────────
    private fun parseDate(dateStr: String): String {
        if (dateStr.isBlank()) return ""
        try {
            if (dateStr.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return dateStr
            if (dateStr.matches(Regex("\\d{1,2}/\\d{1,2}/\\d{4}"))) {
                val p = dateStr.split("/")
                return "${p[2]}-${p[1].padStart(2,'0')}-${p[0].padStart(2,'0')}"
            }
            if (dateStr.matches(Regex("\\d{1,2}-\\d{1,2}-\\d{4}"))) {
                val p = dateStr.split("-")
                return "${p[2]}-${p[1].padStart(2,'0')}-${p[0].padStart(2,'0')}"
            }
            val num = dateStr.toDoubleOrNull()
            if (num != null && num > 40000 && num < 60000) {
                val cal = java.util.Calendar.getInstance().apply {
                    set(1899, 11, 30, 0, 0, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
                cal.add(java.util.Calendar.DATE, num.toInt())
                return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                    .format(cal.time)
            }
        } catch (_: Exception) {}
        return ""
    }

    // ─────────────────────────────────────────────────────────────
    // Template generators
    // ─────────────────────────────────────────────────────────────

    fun generateImportTemplate(products: List<Product>, fileName: String): Uri {
        val wb = XSSFWorkbook()
        createPurchaseTemplate(wb.createSheet("Purchase"), products)
        createSaleTemplate(wb.createSheet("Sale"), products)
        return saveAndShare(wb, fileName)
    }

    fun generatePurchaseTemplate(products: List<Product>, fileName: String): Uri {
        val wb = XSSFWorkbook()
        createPurchaseTemplate(wb.createSheet("Purchase"), products)
        return saveAndShare(wb, fileName)
    }

    fun generateClosingTemplate(products: List<Product>, fileName: String): Uri {
        val wb = XSSFWorkbook()
        createClosingTemplate(wb.createSheet("Closing"), products)
        return saveAndShare(wb, fileName)
    }

    fun generateSaleTemplate(products: List<Product>, fileName: String): Uri {
        val wb = XSSFWorkbook()
        createSaleTemplate(wb.createSheet("Sale"), products)
        return saveAndShare(wb, fileName)
    }

    private fun saveAndShare(workbook: XSSFWorkbook, fileName: String): Uri {
        val file = java.io.File(context.getExternalFilesDir(null), fileName)
        java.io.FileOutputStream(file).use { workbook.write(it) }
        workbook.close()
        return androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file)
    }

    private fun boldHeaderStyle(wb: XSSFWorkbook) = wb.createCellStyle().apply {
        val f = wb.createFont(); f.bold = true; setFont(f)
    }

    private fun createPurchaseTemplate(
        sheet: org.apache.poi.ss.usermodel.Sheet, products: List<Product>
    ) {
        val hs = boldHeaderStyle(sheet.workbook as XSSFWorkbook)
        val headers = arrayOf("Product Name","DATE_PURCHASE","Type","Brand Code",
            "QQ_PQ","PP_PQ","NN_PQ","DD_PQ")
        val hr = sheet.createRow(0)
        headers.forEachIndexed { i, h -> hr.createCell(i).apply { setCellValue(h); cellStyle = hs } }
        products.forEachIndexed { idx, p ->
            val row = sheet.createRow(idx + 1)
            row.createCell(0).setCellValue(p.displayName)
            row.createCell(1).setCellValue("")
            row.createCell(2).setCellValue(p.productType)
            row.createCell(3).setCellValue(p.brandCode)
            (4..7).forEach { row.createCell(it).setCellValue("") }
        }
        listOf(8000,4000,2000,3000,2500,2500,2500,2500)
            .forEachIndexed { i, w -> sheet.setColumnWidth(i, w) }
    }

    private fun createSaleTemplate(
        sheet: org.apache.poi.ss.usermodel.Sheet, products: List<Product>
    ) {
        val hs = boldHeaderStyle(sheet.workbook as XSSFWorkbook)
        val headers = arrayOf("Product Name","DATE_SALE","Type","Brand Code",
            "QQ_SQ","PP_SQ","NN_SQ","DD_SQ")
        val hr = sheet.createRow(0)
        headers.forEachIndexed { i, h -> hr.createCell(i).apply { setCellValue(h); cellStyle = hs } }
        products.forEachIndexed { idx, p ->
            val row = sheet.createRow(idx + 1)
            row.createCell(0).setCellValue(p.displayName)
            row.createCell(1).setCellValue("")
            row.createCell(2).setCellValue(p.productType)
            row.createCell(3).setCellValue(p.brandCode)
            (4..7).forEach { row.createCell(it).setCellValue("") }
        }
        listOf(8000,4000,2000,3000,2500,2500,2500,2500)
            .forEachIndexed { i, w -> sheet.setColumnWidth(i, w) }
    }

    private fun createClosingTemplate(
        sheet: org.apache.poi.ss.usermodel.Sheet, products: List<Product>
    ) {
        val hs = boldHeaderStyle(sheet.workbook as XSSFWorkbook)
        val headers = arrayOf("Product Name","DATE_CLOSING","Type","Brand Code",
            "QQ_CB","PP_CB","NN_CB","DD_CB")
        val hr = sheet.createRow(0)
        headers.forEachIndexed { i, h -> hr.createCell(i).apply { setCellValue(h); cellStyle = hs } }
        products.forEachIndexed { idx, p ->
            val row = sheet.createRow(idx + 1)
            row.createCell(0).setCellValue(p.displayName)
            row.createCell(1).setCellValue("")
            row.createCell(2).setCellValue(p.productType)
            row.createCell(3).setCellValue(p.brandCode)
            (4..7).forEach { row.createCell(it).setCellValue("") }
        }
        listOf(8000,4000,2000,3000,2500,2500,2500,2500)
            .forEachIndexed { i, w -> sheet.setColumnWidth(i, w) }
    }
}
