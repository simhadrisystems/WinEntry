package com.simhadri.winentry.utils

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.simhadri.winentry.data.entity.Product
import com.simhadri.winentry.data.entity.getAllBrandCodes
import com.simhadri.winentry.ui.quicksale.QuickSaleMode
import com.simhadri.winentry.ui.quicksale.QuickSaleRow
import com.simhadri.winentry.ui.quicksale.SizeQty
import com.simhadri.winentry.util.ProductCodeResolver
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Export/import for the Quick Sale Check scratchpad screen. Entirely standalone —
 * does not touch DailyStockExcelHelper or PurchaseExcelHelper. Data here is never
 * written to Room; this only moves it in and out of a local Excel file.
 */
class QuickSaleExcelHelper(private val context: Context) {

    companion object {
        // Label cells written/read for the reconciliation block below the report —
        // kept as constants so export and import can never drift apart.
        private const val RECON_UPI_LABEL = "UPI / ONLINE RECEIPTS"
        private const val RECON_EXPENSES_LABEL = "DAY EXPENSES"
        private const val RECON_DEPOSITS_LABEL = "BANK DEPOSITS"
        private const val RECON_CASH_LABEL = "CASH FOR DEPOSIT"
        private const val RECON_NOTES_LABEL = "NOTES"
    }

    /**
     * Sl.No, Code, Name, OB×4, PQ×4, CB×4, SQ×4, Sale Amount = 20 columns (A..T), followed
     * by the day-end reconciliation figures below the total row so re-importing this same
     * file can restore the whole scratchpad, not just the product rows.
     */
    fun exportQuickSaleSheet(
        rows: List<QuickSaleRow>,
        mode: QuickSaleMode,
        workingDate: String,
        fileName: String,
        upi: Double = 0.0,
        expenses: Double = 0.0,
        deposits: Double = 0.0,
        cashForDeposit: Double = 0.0,
        notes: String = ""
    ): Uri {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Quick Sale Check")

        val titleStyle = workbook.createCellStyle().apply {
            setFont(workbook.createFont().apply { bold = true; fontHeightInPoints = 12 })
        }
        val headerStyle = workbook.createCellStyle().apply {
            setFont(workbook.createFont().apply { bold = true; color = IndexedColors.WHITE.index })
            fillForegroundColor = IndexedColors.GREY_50_PERCENT.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            alignment = HorizontalAlignment.CENTER
        }
        val dataStyle = workbook.createCellStyle().apply { alignment = HorizontalAlignment.CENTER }
        val totalStyle = workbook.createCellStyle().apply {
            setFont(workbook.createFont().apply { bold = true })
        }

        var r = 0
        val titleRow = sheet.createRow(r++)
        titleRow.createCell(0).apply {
            // "[MODE:...]" is a machine-readable marker read back on import — it lets
            // re-importing this exact file restore Direct Qty mode data correctly
            // instead of reading the (empty) OB/PQ/CB columns.
            setCellValue("QUICK SALE CHECK — $workingDate  [MODE:${mode.name}]  (scratchpad — not saved)")
            cellStyle = titleStyle
        }
        sheet.addMergedRegion(CellRangeAddress(0, 0, 0, 19))

        val headerRow1 = sheet.createRow(r++)
        headerRow1.createCell(3).apply { setCellValue("Opening"); cellStyle = headerStyle }
        sheet.addMergedRegion(CellRangeAddress(r - 1, r - 1, 3, 6))
        headerRow1.createCell(7).apply { setCellValue("Purchase"); cellStyle = headerStyle }
        sheet.addMergedRegion(CellRangeAddress(r - 1, r - 1, 7, 10))
        headerRow1.createCell(11).apply { setCellValue("Closing"); cellStyle = headerStyle }
        sheet.addMergedRegion(CellRangeAddress(r - 1, r - 1, 11, 14))
        headerRow1.createCell(15).apply { setCellValue("Sale Qty"); cellStyle = headerStyle }
        sheet.addMergedRegion(CellRangeAddress(r - 1, r - 1, 15, 18))

        val headerRow2 = sheet.createRow(r++)
        val fixedHeaders = listOf("Sl.No", "Code", "Product Name")
        fixedHeaders.forEachIndexed { i, h -> headerRow2.createCell(i).apply { setCellValue(h); cellStyle = headerStyle } }
        for (groupStart in listOf(3, 7, 11, 15)) {
            listOf("QQ", "PP", "NN", "DD").forEachIndexed { i, s ->
                headerRow2.createCell(groupStart + i).apply { setCellValue(s); cellStyle = headerStyle }
            }
        }
        headerRow2.createCell(19).apply { setCellValue("Sale Amount"); cellStyle = headerStyle }
        sheet.addMergedRegion(CellRangeAddress(r - 2, r - 1, 0, 0))
        sheet.addMergedRegion(CellRangeAddress(r - 2, r - 1, 1, 1))
        sheet.addMergedRegion(CellRangeAddress(r - 2, r - 1, 2, 2))
        sheet.addMergedRegion(CellRangeAddress(r - 2, r - 1, 19, 19))

        var totalAmount = 0.0
        rows.forEachIndexed { index, row ->
            val sale = row.sale(mode)
            val amount = row.saleAmount(mode)
            totalAmount += amount
            val dataRow = sheet.createRow(r++)
            dataRow.createCell(0).apply { setCellValue((index + 1).toDouble()); cellStyle = dataStyle }
            dataRow.createCell(1).apply { setCellValue(row.product.brandCode); cellStyle = dataStyle }
            dataRow.createCell(2).apply { setCellValue(row.product.displayName); cellStyle = dataStyle }
            val values = listOf(
                row.opening.qq, row.opening.pp, row.opening.nn, row.opening.dd,
                row.purchase.qq, row.purchase.pp, row.purchase.nn, row.purchase.dd,
                row.closing.qq, row.closing.pp, row.closing.nn, row.closing.dd,
                sale.qq, sale.pp, sale.nn, sale.dd
            )
            values.forEachIndexed { i, v -> dataRow.createCell(3 + i).apply { setCellValue(v.toDouble()); cellStyle = dataStyle } }
            dataRow.createCell(19).apply { setCellValue(amount); cellStyle = dataStyle }
        }

        val totalRow = sheet.createRow(r++)
        totalRow.createCell(0).apply { setCellValue("TOTAL SALE AMOUNT"); cellStyle = totalStyle }
        sheet.addMergedRegion(CellRangeAddress(r - 1, r - 1, 0, 18))
        totalRow.createCell(19).apply { setCellValue(totalAmount); cellStyle = totalStyle }

        // ── Day-end reconciliation, below the report ─────────────────────────────
        r++ // spacer row
        fun reconRow(label: String, value: String) {
            val row = sheet.createRow(r++)
            row.createCell(0).apply { setCellValue(label); cellStyle = totalStyle }
            sheet.addMergedRegion(CellRangeAddress(r - 1, r - 1, 0, 18))
            row.createCell(19).apply { setCellValue(value); cellStyle = dataStyle }
        }
        val reconHeaderRow = sheet.createRow(r++)
        reconHeaderRow.createCell(0).apply { setCellValue("DAY-END RECONCILIATION"); cellStyle = headerStyle }
        sheet.addMergedRegion(CellRangeAddress(r - 1, r - 1, 0, 19))
        reconRow(RECON_UPI_LABEL, upi.toString())
        reconRow(RECON_EXPENSES_LABEL, expenses.toString())
        reconRow(RECON_DEPOSITS_LABEL, deposits.toString())
        reconRow(RECON_CASH_LABEL, cashForDeposit.toString())
        reconRow(RECON_NOTES_LABEL, notes)

        sheet.setColumnWidth(0, 6 * 256)
        sheet.setColumnWidth(1, 10 * 256)
        sheet.setColumnWidth(2, 34 * 256)
        for (i in 3..18) sheet.setColumnWidth(i, 6 * 256)
        sheet.setColumnWidth(19, 14 * 256)

        val file = File(context.getExternalFilesDir(null), fileName)
        FileOutputStream(file).use { workbook.write(it) }
        workbook.close()

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /**
     * Writes a Closing-Balances file in the exact layout Daily Stock's own
     * `DailyStockImportHelper.importClosingOnly()` already reads — DATE_CLOSING | Type |
     * Brand Code | Product Name | QQ_CB | PP_CB | NN_CB | DD_CB — so a Closing figure
     * sanity-checked here can be carried over via Reports → Daily Stock → ⋮ → Import Closing
     * Balances, without any change to that module.
     *
     * Only OB/CB-mode makes sense here (Direct Qty mode has no Closing concept at all), and
     * only rows the user actually touched are written — a product left fully at zero across
     * Opening/Purchase/Closing is skipped so re-importing this file can't silently zero out
     * every other product's real closing balance for that date.
     */
    fun exportClosingForDailyStock(rows: List<QuickSaleRow>, workingDate: String, fileName: String): Uri {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Closing_Balances")
        val headerStyle = workbook.createCellStyle().apply {
            setFont(workbook.createFont().apply { bold = true; color = IndexedColors.WHITE.index })
            fillForegroundColor = IndexedColors.GREY_50_PERCENT.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
        }

        val headerRow = sheet.createRow(0)
        listOf("DATE_CLOSING", "PRODUCT_TYPE", "BRAND_CODE", "PRODUCT_NAME", "QQ_CB", "PP_CB", "NN_CB", "DD_CB")
            .forEachIndexed { i, h -> headerRow.createCell(i).apply { setCellValue(h); cellStyle = headerStyle } }

        var r = 1
        rows.forEach { row ->
            val touched = row.opening.qq != 0 || row.opening.pp != 0 || row.opening.nn != 0 || row.opening.dd != 0 ||
                row.purchase.qq != 0 || row.purchase.pp != 0 || row.purchase.nn != 0 || row.purchase.dd != 0 ||
                row.closing.qq != 0 || row.closing.pp != 0 || row.closing.nn != 0 || row.closing.dd != 0
            if (!touched) return@forEach
            val dataRow = sheet.createRow(r++)
            dataRow.createCell(0).setCellValue(workingDate)
            dataRow.createCell(1).setCellValue(row.product.productType)
            dataRow.createCell(2).setCellValue(row.product.brandCode)   // written as text — keeps leading zeros intact
            dataRow.createCell(3).setCellValue(row.product.displayName)
            dataRow.createCell(4).setCellValue(row.closing.qq.toDouble())
            dataRow.createCell(5).setCellValue(row.closing.pp.toDouble())
            dataRow.createCell(6).setCellValue(row.closing.nn.toDouble())
            dataRow.createCell(7).setCellValue(row.closing.dd.toDouble())
        }

        listOf(4000, 3000, 3000, 8000, 2500, 2500, 2500, 2500).forEachIndexed { i, w -> sheet.setColumnWidth(i, w) }

        val file = File(context.getExternalFilesDir(null), fileName)
        FileOutputStream(file).use { workbook.write(it) }
        workbook.close()

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /**
     * Reads back OB/PQ/CB columns (Sale Qty is only read for this screen's own export
     * format, to restore Direct Qty mode data — the other two formats have no Direct Qty
     * concept and always recompute Sale on screen from OB+PQ-CB instead).
     * Understands three layouts, auto-detected by scanning the first few rows for a header
     * marker cell:
     *   - This screen's own export ("Code" header) — matched against the *plain*
     *     brandCode/alias (no productType prefix), exactly what that column holds.
     *   - The real Daily Stock Sheet export from Reports/Daily Stock ("PRODUCT NAME" header,
     *     no code column at all) — matched by exact product display name instead, since
     *     that file never had a code column to begin with.
     *   - Daily Stock's Closing-Balances template/export ("DATE_CLOSING" header) — Date,
     *     Product Type, Brand Code, Product Name, then Closing QQ/PP/NN/DD only (no OB/PQ
     *     columns at all). Matched by Type+BrandCode, trying the exact/zero-stripped/
     *     zero-padded variants the same way DailyStockImportHelper does, since Excel silently
     *     drops leading zeros from numeric-looking brand codes.
     *     Note: a row from this format lands with Opening=0 — since this screen only allows
     *     editing Closing once Opening+Purchase>0 for that size, the imported Closing value
     *     will show but stay read-only until Opening (or Purchase) is filled in for it — by
     *     design, not a bug, matching the "CB needs OB first" rule.
     *
     * Also detects the date the source file was built for (from its title/date cell, or from
     * a data row's own DATE_CLOSING value) so the caller can set the working-date label to
     * match what was actually imported, rather than leaving it on whatever date happened to
     * be showing before the import.
     */
    fun importQuickSaleSheet(
        inputStream: InputStream,
        products: List<Product>
    ): ImportOutcome {
        // Keyed by the *plain* brandCode/alias (e.g. "1181"), matching exactly what the
        // "Code" column holds on export (`row.product.brandCode`, no productType prefix).
        // NOTE: this is deliberately NOT `ProductCodeResolver.buildFullLookupMap` — that
        // one keys by the prefixed `stockCode` (e.g. "W1181"), which this column never
        // contains, and every plain-numeric brand code would silently fail to resolve.
        val plainCodeLookup = mutableMapOf<String, Product>()
        products.forEach { p ->
            p.getAllBrandCodes().forEach { code -> plainCodeLookup.putIfAbsent(code.trim().uppercase(), p) }
        }
        val nameLookup = products.associateBy { it.displayName.trim().lowercase() }
        val zeroTolerantLookup = mutableMapOf<String, Product>()
        products.forEach { p ->
            p.getAllBrandCodes().forEach { code ->
                val stripped = code.trimStart('0').ifEmpty { "0" }
                val padded = code.padStart(4, '0')
                listOf("${p.productType}$code", "${p.productType}$stripped", "${p.productType}$padded")
                    .forEach { key -> zeroTolerantLookup.putIfAbsent(key, p) }
            }
        }
        val workbook = XSSFWorkbook(inputStream)
        val sheet = workbook.getSheetAt(0)
        val results = mutableListOf<Quadruple>()
        var detectedDate: String? = null
        var detectedMode: QuickSaleMode? = null
        var reconUpi: Double? = null
        var reconExpenses: Double? = null
        var reconDeposits: Double? = null
        var reconNotes: String? = null

        fun cellInt(row: org.apache.poi.ss.usermodel.Row, col: Int): Int = row.getCell(col)?.let {
            if (it.cellType == CellType.NUMERIC) it.numericCellValue.toInt()
            else it.toStringSafe().trim().toIntOrNull() ?: 0
        } ?: 0

        fun readSizes(row: org.apache.poi.ss.usermodel.Row, openingStart: Int):
                Triple<SizeQty, SizeQty, SizeQty> {
            val opening = SizeQty(
                cellInt(row, openingStart), cellInt(row, openingStart + 1),
                cellInt(row, openingStart + 2), cellInt(row, openingStart + 3)
            )
            val purchase = SizeQty(
                cellInt(row, openingStart + 4), cellInt(row, openingStart + 5),
                cellInt(row, openingStart + 6), cellInt(row, openingStart + 7)
            )
            val closing = SizeQty(
                cellInt(row, openingStart + 8), cellInt(row, openingStart + 9),
                cellInt(row, openingStart + 10), cellInt(row, openingStart + 11)
            )
            return Triple(opening, purchase, closing)
        }

        // Detect format — scan the first few rows for this screen's "Code" header,
        // Daily Stock's "PRODUCT NAME" header (its merged label cell, no code column),
        // or the Closing-Balances template's "DATE_CLOSING" header.
        var quickSaleCodeCol = -1; var quickSaleHeaderRow = -1
        var dailyStockNameCol = -1; var dailyStockHeaderRow = -1
        var closingDateCol = -1; var closingHeaderRow = -1
        for (rIdx in 0..minOf(6, sheet.lastRowNum)) {
            val row = sheet.getRow(rIdx) ?: continue
            for (cIdx in 0 until row.lastCellNum) {
                val text = row.getCell(cIdx)?.toStringSafe()?.trim() ?: continue
                if (quickSaleCodeCol == -1 && text.equals("Code", ignoreCase = true)) {
                    quickSaleCodeCol = cIdx; quickSaleHeaderRow = rIdx
                }
                if (dailyStockNameCol == -1 && text.equals("PRODUCT NAME", ignoreCase = true)) {
                    dailyStockNameCol = cIdx; dailyStockHeaderRow = rIdx
                }
                if (closingDateCol == -1 && text.equals("DATE_CLOSING", ignoreCase = true)) {
                    closingDateCol = cIdx; closingHeaderRow = rIdx
                }
            }
        }

        if (quickSaleCodeCol >= 0) {
            val nameCol = quickSaleCodeCol + 1
            val openingStart = nameCol + 1
            // Title row: "QUICK SALE CHECK — yyyy-MM-dd  [MODE:...]  (scratchpad — not saved)"
            val titleText = sheet.getRow(0)?.getCell(0)?.toStringSafe().orEmpty()
            detectedDate = Regex("(\\d{4}-\\d{2}-\\d{2})").find(titleText)?.groupValues?.get(1)
            detectedMode = Regex("\\[MODE:(OB_CB|DIRECT_QTY)]").find(titleText)
                ?.groupValues?.get(1)?.let { runCatching { QuickSaleMode.valueOf(it) }.getOrNull() }
            for (rowIndex in (quickSaleHeaderRow + 1)..sheet.lastRowNum) {
                val row = sheet.getRow(rowIndex) ?: continue
                val label = row.getCell(0)?.toStringSafe()?.trim().orEmpty()
                when {
                    label.equals(RECON_UPI_LABEL, ignoreCase = true) ->
                        reconUpi = row.getCell(19)?.toStringSafe()?.toDoubleOrNull()
                    label.equals(RECON_EXPENSES_LABEL, ignoreCase = true) ->
                        reconExpenses = row.getCell(19)?.toStringSafe()?.toDoubleOrNull()
                    label.equals(RECON_DEPOSITS_LABEL, ignoreCase = true) ->
                        reconDeposits = row.getCell(19)?.toStringSafe()?.toDoubleOrNull()
                    label.equals(RECON_NOTES_LABEL, ignoreCase = true) ->
                        reconNotes = row.getCell(19)?.toStringSafe()
                }
                val code = row.getCell(quickSaleCodeCol)?.toStringSafe()?.trim().orEmpty()
                if (code.isEmpty()) continue
                val product = plainCodeLookup[code.uppercase()] ?: continue
                val (opening, purchase, closing) = readSizes(row, openingStart)
                val directSale = SizeQty(
                    cellInt(row, openingStart + 12), cellInt(row, openingStart + 13),
                    cellInt(row, openingStart + 14), cellInt(row, openingStart + 15)
                )
                results.add(Quadruple(product.id, opening, purchase, closing, directSale))
            }
        } else if (dailyStockNameCol >= 0) {
            val openingStart = dailyStockNameCol + 1
            // The row above the header carries "... DATE: dd-MMM-yyyy" in one of its cells.
            val dateRow = sheet.getRow(dailyStockHeaderRow - 1)
            if (dateRow != null) {
                for (cIdx in 0 until dateRow.lastCellNum) {
                    val text = dateRow.getCell(cIdx)?.toStringSafe()?.trim() ?: continue
                    if (text.startsWith("DATE:", ignoreCase = true)) {
                        detectedDate = parseFlexibleDate(text.substringAfter(":").trim())
                        break
                    }
                }
            }
            for (rowIndex in (dailyStockHeaderRow + 2)..sheet.lastRowNum) {
                val row = sheet.getRow(rowIndex) ?: continue
                val name = row.getCell(dailyStockNameCol)?.toStringSafe()?.trim().orEmpty()
                if (name.isEmpty()) continue
                val product = nameLookup[name.lowercase()] ?: continue
                val (opening, purchase, closing) = readSizes(row, openingStart)
                results.add(Quadruple(product.id, opening, purchase, closing))
            }
        } else if (closingDateCol >= 0) {
            val typeCol = closingDateCol + 1
            val brandCol = closingDateCol + 2
            val closingStart = closingDateCol + 4
            for (rowIndex in (closingHeaderRow + 1)..sheet.lastRowNum) {
                val row = sheet.getRow(rowIndex) ?: continue
                val rawDate = row.getCell(closingDateCol)?.toStringSafe()?.trim().orEmpty()
                if (rawDate.isEmpty()) continue
                val productType = row.getCell(typeCol)?.toStringSafe()?.trim().orEmpty()
                val rawBrand = row.getCell(brandCol)?.toStringSafe()?.trim().orEmpty()
                if (productType.isEmpty() || rawBrand.isEmpty()) continue
                if (detectedDate == null) detectedDate = parseFlexibleDate(rawDate)
                val stripped = rawBrand.trimStart('0').ifEmpty { "0" }
                val padded = rawBrand.padStart(4, '0')
                val product = zeroTolerantLookup["$productType$rawBrand"]
                    ?: zeroTolerantLookup["$productType$stripped"]
                    ?: zeroTolerantLookup["$productType$padded"]
                    ?: ProductCodeResolver.resolve("$productType$rawBrand", products)
                    ?: continue
                val closing = SizeQty(
                    cellInt(row, closingStart), cellInt(row, closingStart + 1),
                    cellInt(row, closingStart + 2), cellInt(row, closingStart + 3)
                )
                // No OB/PQ in this format — Closing lands read-only until OB/PQ is filled in.
                results.add(Quadruple(product.id, SizeQty(), SizeQty(), closing))
            }
        }

        workbook.close()
        return ImportOutcome(results, detectedDate, detectedMode, reconUpi, reconExpenses, reconDeposits, reconNotes)
    }

    data class Quadruple(
        val productId: Long,
        val opening: SizeQty,
        val purchase: SizeQty,
        val closing: SizeQty,
        /** Only populated for this screen's own export format; null for the other two. */
        val directSale: SizeQty? = null
    )

    data class ImportOutcome(
        val rows: List<Quadruple>,
        /** yyyy-MM-dd, or null if no date could be found/parsed in the source file. */
        val detectedDate: String?,
        /** Only set when re-importing this screen's own export format. */
        val detectedMode: QuickSaleMode? = null,
        val upi: Double? = null,
        val expenses: Double? = null,
        val deposits: Double? = null,
        val notes: String? = null
    )

    private fun org.apache.poi.ss.usermodel.Cell.toStringSafe(): String = when (cellType) {
        CellType.STRING -> stringCellValue
        CellType.NUMERIC -> numericCellValue.toLong().toString()
        else -> ""
    }

    /** Parses yyyy-MM-dd, dd/MM/yyyy, dd-MM-yyyy, dd-MMM-yyyy, or an Excel date serial. */
    private fun parseFlexibleDate(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return s
        if (s.matches(Regex("\\d{1,2}/\\d{1,2}/\\d{4}"))) {
            val p = s.split("/")
            return "${p[2]}-${p[1].padStart(2, '0')}-${p[0].padStart(2, '0')}"
        }
        if (s.matches(Regex("\\d{1,2}-\\d{1,2}-\\d{4}"))) {
            val p = s.split("-")
            return "${p[2]}-${p[1].padStart(2, '0')}-${p[0].padStart(2, '0')}"
        }
        try {
            val fmt = java.text.SimpleDateFormat("dd-MMM-yyyy", java.util.Locale.US)
            val out = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            return out.format(fmt.parse(s)!!)
        } catch (_: Exception) {}
        val num = s.toDoubleOrNull()
        if (num != null && num > 40000 && num < 60000) {
            val cal = java.util.Calendar.getInstance().apply {
                set(1899, 11, 30, 0, 0, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            cal.add(java.util.Calendar.DATE, num.toInt())
            return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)
        }
        return null
    }
}
