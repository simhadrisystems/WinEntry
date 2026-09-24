package com.simhadri.winentry.sync

import com.simhadri.winentry.utils.StrictDate
import android.util.Log
import com.simhadri.winentry.data.entity.DailyStock
import com.simhadri.winentry.data.entity.DayReconciliation
import com.simhadri.winentry.data.entity.Product
import com.simhadri.winentry.data.entity.Purchase
import com.simhadri.winentry.data.entity.SyncStatus
import com.simhadri.winentry.data.entity.stockCode

/**
 * Row-parsing utilities and entity serializers for cloud sync data.
 *
 * All live Google Sheets API calls are handled server-side by Firebase Cloud
 * Functions (via CloudFunctionClient → SyncCoordinator). This object holds
 * only the row-to-entity parsers and entity-to-row serializers used by the
 * sync layer after data is returned from CF.
 *
 * ── Purchases sheet column layout (A–AB) ─────────────────────────────────────
 * A=TxnId  B=Date  C=ProductCode  D=ProductName  E=InvoiceNo  F=Supplier
 * G=QQ_Boxes  H=QQ_Loose  I=QQ_Total  J=QQ_Price  K=QQ_Cost
 * L=PP_Boxes  M=PP_Loose  N=PP_Total  O=PP_Price  P=PP_Cost
 * Q=NN_Boxes  R=NN_Loose  S=NN_Total  T=NN_Price  U=NN_Cost
 * V=DD_Boxes  W=DD_Loose  X=DD_Total  Y=DD_Price  Z=DD_Cost
 * AA=TotalCost  AB=Notes  AC=ReceivedDate
 *
 * ── DailyStock sheet column layout (A–Y) ─────────────────────────────────────
 * A=date          B=productCode
 * C=openQq        D=openPp        E=openNn        F=openDd
 * G=closeQq       H=closePp       I=closeNn       J=closeDd
 * K=saleQq        L=salePp        M=saleNn        N=saleDd
 * O=priceQq       P=pricePp       Q=priceNn       R=priceDd
 * S=amountQq      T=amountPp      U=amountNn      V=amountDd
 * W=saleAmount    X=isCommitted   Y=isOpeningStock
 */
object CloudSyncManager {

    private const val TAG = "CloudSyncManager"

    /** yyyy-MM-dd, or null when the cell is blank or not a real date. */
    internal fun parseDateStr(raw: String): String? = StrictDate.parse(raw)

    internal fun buildProductLookupMap(products: List<Product>): Map<String, Product> {
        val map = mutableMapOf<String, Product>()
        for (p in products) {
            map[p.stockCode] = p
            if (p.aliases.isNotBlank()) {
                p.aliases.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { alias ->
                    map["${p.productType}$alias"] = p
                }
            }
        }
        return map
    }

    /**
     * Parse raw rows from the PurchaseImport sheet tab into Purchase objects.
     * Accepts pre-loaded rows (from CF read_all) — no Sheets API call needed.
     *
     * Row format (shipment block):
     *   Row N+0:  INVOICE NUMBER | TP08726
     *   Row N+1:  DATE | 5/2/26 | RECEIVED DATE | 6/2/26 | Invoice Amount | ...
     *   Row N+2:  Header row
     *   Row N+3+: Data rows (col 1=brand, 3=type, 5=size, 7=boxes, 8=units, 9=price)
     *
     * Deduplicates by invoiceNumber|productCode|date against [existingKeys].
     */
    internal fun parseImportSheetRows(
        rows: List<List<Any>>,
        products: List<Product>,
        existingKeys: Set<String>
    ): Pair<List<Purchase>, Set<String>> {
        if (rows.isEmpty()) return Pair(emptyList(), emptySet())

        val productMap    = buildProductLookupMap(products)
        val newPurchases  = mutableListOf<Purchase>()
        val notFoundCodes = mutableSetOf<String>()

        val maxDate = run {
            val cal = java.util.Calendar.getInstance()
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)
        }

        data class Shipment(
            val invoiceNumber: String,
            val date: String,
            val receivedDate: String,
            val dataStartIdx: Int,
            val headerRowNum: Int
        )

        val shipments = mutableListOf<Shipment>()
        var i = 0
        while (i < rows.size) {
            val cell0 = rows[i].getOrNull(0)?.toString()?.trim() ?: ""
            if (cell0.uppercase().contains("INVOICE")) {
                val invoice = rows[i].getOrNull(1)?.toString()?.trim() ?: ""
                val dateRow = if (i + 1 < rows.size) rows[i + 1] else null
                val dateRaw = dateRow?.getOrNull(1)?.toString()?.trim() ?: ""
                val parsedDate = parseDateStr(dateRaw)
                if (parsedDate == null || parsedDate > maxDate) {
                    android.util.Log.w("CloudSyncManager", "PurchaseImport: invoice $invoice has no valid date ('$dateRaw') — skipped")
                    i += 3
                    continue
                }
                val recvRaw = dateRow?.getOrNull(3)?.toString()?.trim() ?: ""
                val looksLikeDate = recvRaw.contains('/') || recvRaw.contains('-')
                val parsedRecv = if (looksLikeDate) parseDateStr(recvRaw).orEmpty() else ""
                val receivedDate = if (parsedRecv.isNotBlank() && parsedRecv > parsedDate && parsedRecv <= maxDate) parsedRecv else ""
                shipments.add(Shipment(invoice, parsedDate, receivedDate, i + 3, i + 1))
                i += 3
            } else { i++ }
        }

        for ((sIdx, shipment) in shipments.withIndex()) {
            val dataEnd = if (sIdx + 1 < shipments.size)
                shipments[sIdx + 1].headerRowNum - 2 else rows.size

            val groups = mutableMapOf<String, MutableList<Map<String, Any>>>()

            for (ri in shipment.dataStartIdx until dataEnd) {
                if (ri >= rows.size) break
                val row   = rows[ri]
                val brand = row.getOrNull(1)?.toString()?.trim() ?: continue
                if (brand.isBlank()) continue
                val type    = row.getOrNull(3)?.toString()?.trim() ?: ""
                val rawCode = "$type$brand"
                val product = productMap[rawCode]
                if (product == null) { notFoundCodes.add(rawCode); continue }
                groups.getOrPut(product.stockCode) { mutableListOf() }.add(mapOf(
                    "size"    to (row.getOrNull(5)?.toString()?.trim()?.uppercase() ?: ""),
                    "boxes"   to (row.getOrNull(7)?.toString()?.toDoubleOrNull()?.toInt() ?: 0),
                    "units"   to (row.getOrNull(8)?.toString()?.toDoubleOrNull()?.toInt() ?: 0),
                    "price"   to (row.getOrNull(9)?.toString()?.toDoubleOrNull() ?: 0.0),
                    "product" to product
                ))
            }

            for ((_, sizeRows) in groups) {
                val product  = sizeRows.first()["product"] as Product
                var qqB = 0; var qqU = 0; var qqP = 0.0
                var ppB = 0; var ppU = 0; var ppP = 0.0
                var nnB = 0; var nnU = 0; var nnP = 0.0
                var ddB = 0; var ddU = 0; var ddP = 0.0
                for (r in sizeRows) {
                    val b = r["boxes"] as Int; val u = r["units"] as Int; val p = r["price"] as Double
                    when (r["size"] as String) {
                        "QQ" -> { qqB += b; qqU += u; if (p > 0) qqP = p }
                        "PP" -> { ppB += b; ppU += u; if (p > 0) ppP = p }
                        "NN" -> { nnB += b; nnU += u; if (p > 0) nnP = p }
                        "DD" -> { ddB += b; ddU += u; if (p > 0) ddP = p }
                    }
                }
                val qqTu = Purchase.calculateTotalUnits(qqB, qqU, product.qqUnitsPerBox)
                val ppTu = Purchase.calculateTotalUnits(ppB, ppU, product.ppUnitsPerBox)
                val nnTu = Purchase.calculateTotalUnits(nnB, nnU, product.nnUnitsPerBox)
                val ddTu = Purchase.calculateTotalUnits(ddB, ddU, product.ddUnitsPerBox)
                val qqFP = if (qqP > 0) qqP else product.qqPurchasePrice
                val ppFP = if (ppP > 0) ppP else product.ppPurchasePrice
                val nnFP = if (nnP > 0) nnP else product.nnPurchasePrice
                val ddFP = if (ddP > 0) ddP else product.ddPurchasePrice
                val dedupKey = "${shipment.invoiceNumber}|${product.stockCode}|${shipment.date}"
                if (dedupKey in existingKeys) continue
                newPurchases.add(Purchase(
                    purchaseDate   = shipment.date,
                    receivedDate   = shipment.receivedDate,
                    productId      = product.id,
                    productCode    = product.stockCode,
                    productName    = product.displayName,
                    qqBoxes = qqB, qqLoose = qqU, qqUnitsPerBox = product.qqUnitsPerBox,
                    qqTotalUnits = qqTu, qqUnitPrice = qqFP, qqTotalCost = qqTu * qqFP,
                    ppBoxes = ppB, ppLoose = ppU, ppUnitsPerBox = product.ppUnitsPerBox,
                    ppTotalUnits = ppTu, ppUnitPrice = ppFP, ppTotalCost = ppTu * ppFP,
                    nnBoxes = nnB, nnLoose = nnU, nnUnitsPerBox = product.nnUnitsPerBox,
                    nnTotalUnits = nnTu, nnUnitPrice = nnFP, nnTotalCost = nnTu * nnFP,
                    ddBoxes = ddB, ddLoose = ddU, ddUnitsPerBox = product.ddUnitsPerBox,
                    ddTotalUnits = ddTu, ddUnitPrice = ddFP, ddTotalCost = ddTu * ddFP,
                    totalCost      = qqTu*qqFP + ppTu*ppFP + nnTu*nnFP + ddTu*ddFP,
                    invoiceNumber  = shipment.invoiceNumber,
                    supplierName   = "",
                    notes          = "Imported from cloud sheet",
                    syncStatus     = SyncStatus.PENDING_INSERT,
                    isProcessed    = false,
                    isDeleted      = false
                ))
            }
        }
        Log.d(TAG, "parseImportSheetRows: ${newPurchases.size} new, ${notFoundCodes.size} not found")
        return Pair(newPurchases, notFoundCodes)
    }

    /**
     * Parse rows from the master Products sheet into Product objects.
     *
     * Column layout (0-based): 0=productName 1=productType 2=category 3=brandCode
     * 4=displayName 5=serialNo 6-13=prices(qqPurchase qqSale ppPurchase ppSale
     * nnPurchase nnSale ddPurchase ddSale) 14-17=unitsPerBox(qq pp nn dd)
     * 18=dailySortKey 19=aliases 20=isActive
     */
    fun parseProductRows(rows: List<List<Any>>): List<Product> {
        return rows.mapNotNull { row ->
            fun cell(col: Int) = row.getOrNull(col)?.toString().orEmpty()
            val productType = cell(1)
            val brandCode   = cell(3)
            if (brandCode.isBlank()) return@mapNotNull null
            Product(
                productName     = cell(0),
                productType     = productType,
                category        = cell(2),
                brandCode       = brandCode,
                qqCode          = "${productType}${brandCode}QQ",
                ppCode          = "${productType}${brandCode}PP",
                nnCode          = "${productType}${brandCode}NN",
                ddCode          = "${productType}${brandCode}DD",
                displayName     = cell(4),
                serialNo        = cell(5).toIntOrNull() ?: 1,
                qqPurchasePrice = cell(6).toDoubleOrNull() ?: 0.0,
                qqSalePrice     = cell(7).toDoubleOrNull() ?: 0.0,
                ppPurchasePrice = cell(8).toDoubleOrNull() ?: 0.0,
                ppSalePrice     = cell(9).toDoubleOrNull() ?: 0.0,
                nnPurchasePrice = cell(10).toDoubleOrNull() ?: 0.0,
                nnSalePrice     = cell(11).toDoubleOrNull() ?: 0.0,
                ddPurchasePrice = cell(12).toDoubleOrNull() ?: 0.0,
                ddSalePrice     = cell(13).toDoubleOrNull() ?: 0.0,
                qqUnitsPerBox   = cell(14).toIntOrNull() ?: 12,
                ppUnitsPerBox   = cell(15).toIntOrNull() ?: 24,
                nnUnitsPerBox   = cell(16).toIntOrNull() ?: 48,
                ddUnitsPerBox   = cell(17).toIntOrNull() ?: 96,
                isActive        = (cell(20).toIntOrNull() ?: 1) == 1,
                dailySortKey    = cell(18).toIntOrNull() ?: 999,
                aliases         = cell(19)
            )
        }
    }

    /**
     * Parse rows from the Purchases sheet tab (normalised app format, A–AB columns)
     * into Purchase objects. Used for down-syncing purchases to a device that doesn't
     * have them locally (fresh install or second device).
     *
     * Rows whose txnId is in [existingTxnIds] are skipped (dedup by txnId).
     * Rows are marked SYNCED — the cloud row is authoritative, no re-upload needed.
     */
    /** Column AC, kept only when it is a real later date (blank = same as invoice date). */
    private fun parseReceivedDate(row: List<Any>, purchaseDate: String, maxDate: String): String {
        val raw = row.getOrNull(28)?.toString()?.trim().orEmpty()
        if (raw.isBlank()) return ""
        val d = parseDateStr(raw) ?: return ""
        return if (d > purchaseDate && d <= maxDate) d else ""
    }

    /** txnId to cloud ReceivedDate for rows that carry one. */
    internal fun parseReceivedDates(rows: List<List<Any>>): Map<String, String> {
        val maxDate = run {
            val cal = java.util.Calendar.getInstance()
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)
        }
        val out = mutableMapOf<String, String>()
        for (row in rows) {
            val txnId = row.getOrNull(0)?.toString()?.trim().orEmpty()
            if (txnId.isBlank() || txnId.equals("TxnId", ignoreCase = true)) continue
            val purchaseDate = parseDateStr(row.getOrNull(1)?.toString()?.trim().orEmpty()) ?: continue
            val rd = parseReceivedDate(row, purchaseDate, maxDate)
            if (rd.isNotBlank()) out[txnId] = rd
        }
        return out
    }

    /** One purchase line: the same product, invoice and invoice date. */
    internal fun lineKey(productCode: String, invoice: String, date: String) =
        "$productCode|$invoice|$date"

    /** [lineKey] with the stored code resolved to the product's stockCode (aliases included). */
    internal fun Purchase.lineKey(productMap: Map<String, Product>) =
        lineKey(productMap[productCode]?.stockCode ?: productCode, invoiceNumber, purchaseDate)

    internal data class CloudLine(val txnId: String, val receivedDate: String)

    /**
     * Newest Purchases-tab row per line. The tab can hold several copies of a line under
     * different TxnIds (left by older app versions); TxnIds start with a yyyyMMdd-HHmmss
     * timestamp, so the largest one is the most recent copy.
     */
    internal fun cloudLineIndex(rows: List<List<Any>>, products: List<Product>): Map<String, CloudLine> {
        val productMap = buildProductLookupMap(products)
        val receivedDates = parseReceivedDates(rows)
        val out = mutableMapOf<String, CloudLine>()
        for (row in rows) {
            val txnId = row.getOrNull(0)?.toString()?.trim().orEmpty()
            if (txnId.isBlank() || txnId.equals("TxnId", ignoreCase = true)) continue
            val code = row.getOrNull(2)?.toString()?.trim().orEmpty()
            if (code.isBlank()) continue
            val key = lineKey(
                productMap[code]?.stockCode ?: code,
                row.getOrNull(4)?.toString()?.trim().orEmpty(),
                parseDateStr(row.getOrNull(1)?.toString()?.trim().orEmpty()) ?: continue
            )
            val current = out[key]
            if (current == null || txnId > current.txnId)
                out[key] = CloudLine(txnId, receivedDates[txnId].orEmpty())
        }
        return out
    }

    /** Keeps only the newest copy (largest txnId) of each line. */
    internal fun newestPerLine(purchases: List<Purchase>, productMap: Map<String, Product>): List<Purchase> =
        purchases.groupBy { it.lineKey(productMap) }.values.map { copies -> copies.maxBy { it.txnId } }

    internal fun parsePurchasesTabRows(
        rows: List<List<Any>>,
        products: List<Product>,
        existingTxnIds: Set<String>
    ): List<Purchase> {
        if (rows.isEmpty()) return emptyList()
        val productMap = buildProductLookupMap(products)
        val result = mutableListOf<Purchase>()

        fun Any?.int() = this?.toString()?.toIntOrNull() ?: 0
        fun Any?.dbl() = this?.toString()?.toDoubleOrNull() ?: 0.0
        fun Any?.str() = this?.toString()?.trim() ?: ""

        val maxDate = run {
            val cal = java.util.Calendar.getInstance()
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)
        }

        for (row in rows) {
            val txnId = row.getOrNull(0).str()
            if (txnId.isBlank() || txnId.equals("TxnId", ignoreCase = true)) continue
            if (txnId in existingTxnIds) continue

            val productCode = row.getOrNull(2).str()
            if (productCode.isBlank()) continue
            val product = productMap[productCode]

            val purchaseDate = parseDateStr(row.getOrNull(1).str())
            if (purchaseDate == null || purchaseDate > maxDate) {
                android.util.Log.w("CloudSyncManager", "Purchases tab: $txnId has no valid date ('${row.getOrNull(1).str()}') — skipped")
                continue
            }
            val receivedDate = parseReceivedDate(row, purchaseDate, maxDate)

            result.add(Purchase(
                txnId         = txnId,
                syncStatus    = SyncStatus.SYNCED,
                purchaseDate  = purchaseDate,
                productId     = product?.id ?: 0L,
                productCode   = productCode,
                productName   = row.getOrNull(3).str(),
                invoiceNumber = row.getOrNull(4).str(),
                supplierName  = row.getOrNull(5).str(),
                qqBoxes       = row.getOrNull(6).int(),
                qqLoose       = row.getOrNull(7).int(),
                qqUnitsPerBox = product?.qqUnitsPerBox ?: 12,
                qqTotalUnits  = row.getOrNull(8).int(),
                qqUnitPrice   = row.getOrNull(9).dbl(),
                qqTotalCost   = row.getOrNull(10).dbl(),
                ppBoxes       = row.getOrNull(11).int(),
                ppLoose       = row.getOrNull(12).int(),
                ppUnitsPerBox = product?.ppUnitsPerBox ?: 24,
                ppTotalUnits  = row.getOrNull(13).int(),
                ppUnitPrice   = row.getOrNull(14).dbl(),
                ppTotalCost   = row.getOrNull(15).dbl(),
                nnBoxes       = row.getOrNull(16).int(),
                nnLoose       = row.getOrNull(17).int(),
                nnUnitsPerBox = product?.nnUnitsPerBox ?: 48,
                nnTotalUnits  = row.getOrNull(18).int(),
                nnUnitPrice   = row.getOrNull(19).dbl(),
                nnTotalCost   = row.getOrNull(20).dbl(),
                ddBoxes       = row.getOrNull(21).int(),
                ddLoose       = row.getOrNull(22).int(),
                ddUnitsPerBox = product?.ddUnitsPerBox ?: 96,
                ddTotalUnits  = row.getOrNull(23).int(),
                ddUnitPrice   = row.getOrNull(24).dbl(),
                ddTotalCost   = row.getOrNull(25).dbl(),
                totalCost     = row.getOrNull(26).dbl(),
                notes         = row.getOrNull(27).str(),
                receivedDate  = receivedDate,
                isProcessed   = false,
                isDeleted     = false
            ))
        }
        Log.d(TAG, "parsePurchasesTabRows: ${result.size} new")
        return result
    }
}

// ── Extension: Purchase -> sheet row ─────────────────────────────────────────

internal fun Purchase.toSheetRow(): List<Any> = listOf(
    txnId, purchaseDate, productCode, productName, invoiceNumber, supplierName,
    qqBoxes, qqLoose, qqTotalUnits, qqUnitPrice, qqTotalCost,
    ppBoxes, ppLoose, ppTotalUnits, ppUnitPrice, ppTotalCost,
    nnBoxes, nnLoose, nnTotalUnits, nnUnitPrice, nnTotalCost,
    ddBoxes, ddLoose, ddTotalUnits, ddUnitPrice, ddTotalCost,
    totalCost, notes, receivedDate
)

// ── Extension: DailyStock -> sheet row ───────────────────────────────────────

internal fun DailyStock.toSheetRow(): List<Any> = listOf(
    date, productCode,
    openQq, openPp, openNn, openDd,
    closeQq, closePp, closeNn, closeDd,
    saleQq, salePp, saleNn, saleDd,
    priceQq, pricePp, priceNn, priceDd,
    amountQq, amountPp, amountNn, amountDd,
    saleAmount,
    if (isCommitted) "YES" else "NO",
    if (isOpeningStock) "YES" else "NO"
)
// Sheet columns A..Y (25 cols):
// A=date          B=productCode
// C=openQq        D=openPp        E=openNn        F=openDd
// G=closeQq       H=closePp       I=closeNn       J=closeDd
// K=saleQq        L=salePp        M=saleNn        N=saleDd
// O=priceQq       P=pricePp       Q=priceNn       R=priceDd
// S=amountQq      T=amountPp      U=amountNn      V=amountDd
// W=saleAmount    X=isCommitted   Y=isOpeningStock

// ── Extension: DayReconciliation -> sheet row ─────────────────────────────────

internal fun DayReconciliation.toDaySummaryRow(): List<Any> = listOf(
    date, totalDaySales, upiReceipts, dayExpenses, cashForDeposit, deposits, notes
)
