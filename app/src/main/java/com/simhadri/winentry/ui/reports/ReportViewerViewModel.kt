package com.simhadri.winentry.ui.reports

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.simhadri.winentry.data.AppDatabase
import com.simhadri.winentry.data.entity.DailyEntry
import com.simhadri.winentry.data.entity.DayReconciliation
import com.simhadri.winentry.data.entity.ProductSizeQty
import com.simhadri.winentry.data.entity.stockCode
import com.simhadri.winentry.data.repository.DailyStockRepository
import com.simhadri.winentry.data.repository.PurchaseQuantities
import com.simhadri.winentry.data.repository.PurchaseRepository
import com.simhadri.winentry.utils.TypeLabels
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * ViewModel for ReportViewerFragment.
 *
 * Owns all data access and HTML generation. Survives rotation — the fragment
 * simply re-loads [reportHtml] into the WebView without re-hitting the DB.
 *
 * Flow:
 *   1. Fragment calls [loadReport] with args bundle on first attach
 *   2. ViewModel checks if HTML is already cached — no-op if so
 *   3. Fragment observes [reportHtml] and loads it into WebView whenever it posts
 *   4. On rotation: fragment re-attaches, observes, immediately gets cached value
 */
class ReportViewerViewModel(app: Application) : AndroidViewModel(app) {

    private val db                = AppDatabase.getInstance(app)
    private val repository        = DailyStockRepository(db.productDao(), db.dailyStockDao())
    private val purchaseRepository = PurchaseRepository(db.purchaseDao())

    sealed class ReportState {
        object Loading                     : ReportState()
        data class Ready(val html: String) : ReportState()
        data class Empty(val date: String) : ReportState()
        data class Error(val msg: String)  : ReportState()
    }

    private val _state = MutableLiveData<ReportState>()
    val state: LiveData<ReportState> = _state

    /** Cached so print/share can access the HTML without re-querying. */
    var cachedHtml: String = ""
        private set

    /** True once loadReport has been called — prevents double-load on rotation. */
    private var loaded = false

    // ── Entry point ────────────────────────────────────────────────────────────

    fun loadReport(args: Bundle?) {
        if (loaded) return   // ← rotation guard: HTML already cached, nothing to do
        loaded = true
        _state.value = ReportState.Loading

        val reportType  = args?.getString(ReportViewerFragment.ARG_REPORT_TYPE) ?: return
        val date        = args.getString(ReportViewerFragment.ARG_DATE) ?: return
        val dateTo      = args.getString(ReportViewerFragment.ARG_DATE_TO) ?: date
        val title       = args.getString(ReportViewerFragment.ARG_TITLE) ?: "Report"
        val includeZero = args.getBoolean(ReportViewerFragment.ARG_INCLUDE_ZERO, false)

        viewModelScope.launch {
            try {
                val html = when (reportType) {
                    ReportViewerFragment.TYPE_PURCHASE_REPORT ->
                        buildPurchaseReportHtml(date, dateTo, title)
                    ReportViewerFragment.TYPE_SALES_MARGIN_REPORT ->
                        buildSalesMarginHtml(date, dateTo, title)
                    ReportViewerFragment.TYPE_BRAND_WISE_REPORT -> {
                        val entries = loadEntriesAll(date)
                        buildBrandWiseHtml(date, entries, title, includeZero)
                    }
                    else -> {
                        val entries = loadEntries(date)
                        if (entries.isEmpty()) {
                            _state.postValue(ReportState.Empty(date))
                            return@launch
                        }
                        when (reportType) {
                            ReportViewerFragment.TYPE_DAILY_SHEET      -> {
                                val recon = db.dayReconciliationDao().getByDate(date)
                                buildDailySheetHtml(date, entries, title, recon)
                            }
                            ReportViewerFragment.TYPE_CLOSING_BALANCES ->
                                buildClosingBalancesHtml(date, entries, title)
                            else -> ""
                        }
                    }
                }
                if (html.isEmpty()) {
                    _state.postValue(ReportState.Empty(date))
                } else {
                    cachedHtml = html
                    _state.postValue(ReportState.Ready(html))
                }
            } catch (e: Exception) {
                _state.postValue(ReportState.Error(e.message ?: "Unknown error"))
            }
        }
    }

    // ── Data loaders ───────────────────────────────────────────────────────────

    /**
     * Loads entries for reports that show only products with activity (daily sheet,
     * closing balances). Products with zero OB and zero PQ are excluded.
     */
    private suspend fun loadEntries(date: String): List<DailyEntry> {
        return try {
            val products     = repository.getAllProductsByDailySortKeySync()
            val productCodes = products.map { it.stockCode }
            val pqByCode     = purchaseRepository.getAllPurchaseQuantitiesByCodeForDate(date, products)
            val prevByCode   = repository.getBulkPreviousRows(productCodes, date)
            val stockByCode  = repository.getAllDailyStockForDate(date).associateBy { it.productCode }

            products.mapNotNull { product ->
                try {
                    val prev  = prevByCode[product.stockCode]
                    val stock = stockByCode[product.stockCode]

                    val qqOb = if (stock?.isCommitted == true) stock.openQq else prev?.closeQq ?: 0
                    val ppOb = if (stock?.isCommitted == true) stock.openPp else prev?.closePp ?: 0
                    val nnOb = if (stock?.isCommitted == true) stock.openNn else prev?.closeNn ?: 0
                    val ddOb = if (stock?.isCommitted == true) stock.openDd else prev?.closeDd ?: 0

                    val base  = product.stockCode
                    val pqQQ  = pqByCode["${base}QQ"] ?: 0
                    val pqPP  = pqByCode["${base}PP"] ?: 0
                    val pqNN  = pqByCode["${base}NN"] ?: 0
                    val pqDD  = pqByCode["${base}DD"] ?: 0

                    val qqCb = stock?.closeQq ?: (qqOb + pqQQ)
                    val ppCb = stock?.closePp ?: (ppOb + pqPP)
                    val nnCb = stock?.closeNn ?: (nnOb + pqNN)
                    val ddCb = stock?.closeDd ?: (ddOb + pqDD)

                    // Skip products with zero activity
                    if (qqOb == 0 && ppOb == 0 && nnOb == 0 && ddOb == 0 &&
                        pqQQ  == 0 && pqPP  == 0 && pqNN  == 0 && pqDD  == 0)
                        return@mapNotNull null

                    DailyEntry(
                        product  = product,
                        date     = date,
                        opening  = ProductSizeQty(qqOb, ppOb, nnOb, ddOb),
                        purchase = ProductSizeQty(pqQQ, pqPP, pqNN, pqDD),
                        sale     = ProductSizeQty(
                            (qqOb + pqQQ - qqCb).coerceAtLeast(0),
                            (ppOb + pqPP - ppCb).coerceAtLeast(0),
                            (nnOb + pqNN - nnCb).coerceAtLeast(0),
                            (ddOb + pqDD - ddCb).coerceAtLeast(0)
                        ),
                        closing  = ProductSizeQty(qqCb, ppCb, nnCb, ddCb)
                    )
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) { emptyList() }
    }

    /**
     * Loads entries including products with zero activity — used by Brand-Wise report
     * which wants a full stock position picture.
     */
    private suspend fun loadEntriesAll(date: String): List<DailyEntry> {
        return try {
            val products     = repository.getAllProductsByDailySortKeySync()
            val productCodes = products.map { it.stockCode }
            val pqByCode     = purchaseRepository.getAllPurchaseQuantitiesByCodeForDate(date, products)
            val prevByCode   = repository.getBulkPreviousRows(productCodes, date)
            val stockByCode  = repository.getAllDailyStockForDate(date).associateBy { it.productCode }

            products.mapNotNull { product ->
                try {
                    val prev  = prevByCode[product.stockCode]
                    val stock = stockByCode[product.stockCode]

                    val qqOb = if (stock?.isCommitted == true) stock.openQq else prev?.closeQq ?: 0
                    val ppOb = if (stock?.isCommitted == true) stock.openPp else prev?.closePp ?: 0
                    val nnOb = if (stock?.isCommitted == true) stock.openNn else prev?.closeNn ?: 0
                    val ddOb = if (stock?.isCommitted == true) stock.openDd else prev?.closeDd ?: 0

                    val base = product.stockCode
                    val pqQQ = pqByCode["${base}QQ"] ?: 0
                    val pqPP = pqByCode["${base}PP"] ?: 0
                    val pqNN = pqByCode["${base}NN"] ?: 0
                    val pqDD = pqByCode["${base}DD"] ?: 0

                    val qqCb = stock?.closeQq ?: (qqOb + pqQQ)
                    val ppCb = stock?.closePp ?: (ppOb + pqPP)
                    val nnCb = stock?.closeNn ?: (nnOb + pqNN)
                    val ddCb = stock?.closeDd ?: (ddOb + pqDD)

                    DailyEntry(
                        product  = product,
                        date     = date,
                        opening  = ProductSizeQty(qqOb, ppOb, nnOb, ddOb),
                        purchase = ProductSizeQty(pqQQ, pqPP, pqNN, pqDD),
                        sale     = ProductSizeQty(
                            (qqOb + pqQQ - qqCb).coerceAtLeast(0),
                            (ppOb + pqPP - ppCb).coerceAtLeast(0),
                            (nnOb + pqNN - nnCb).coerceAtLeast(0),
                            (ddOb + pqDD - ddCb).coerceAtLeast(0)
                        ),
                        closing  = ProductSizeQty(qqCb, ppCb, nnCb, ddCb)
                    )
                } catch (e: Exception) { null }
            }
        } catch (e: Exception) { emptyList() }
    }

    // ── Purchase report ────────────────────────────────────────────────────────

    private suspend fun buildPurchaseReportHtml(
        fromDate: String, toDate: String, title: String
    ): String {
        val allPurchases = try {
            db.purchaseDao().getPurchasesByDateRangeSync(fromDate, toDate)
        } catch (e: Exception) { return "" }

        if (allPurchases.isEmpty()) return ""

        // Match by productCode (stable) not productId (volatile after re-sync)
        val products       = repository.getAllProductsByDailySortKeySync()  // all products — inactive may have purchases
        val byCode         = allPurchases.groupBy { it.productCode }
        val activeProducts = products.filter { byCode.containsKey(it.stockCode) }
        if (activeProducts.isEmpty()) return ""

        val prefs        = getApp().getSharedPreferences("business_info",
            android.content.Context.MODE_PRIVATE)
        val businessName = prefs.getString("business_name", "") ?: ""
        val location     = prefs.getString("location",      "") ?: ""
        val generatedOn  = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())

        val inFmt  = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val outFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        fun fmtDay(d: String) = try { inFmt.parse(d)?.let { outFmt.format(it) } ?: d } catch (e: Exception) { d }

        val inLocale = Locale("en", "IN")
        fun qty(n: Int): String = if (n == 0) "-"
            else java.text.NumberFormat.getIntegerInstance(inLocale).format(n.toLong())
        fun cur(v: Double): String = if (v == 0.0) "-"
            else "₹" + java.text.NumberFormat.getNumberInstance(inLocale)
                .apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(v)

        var serialNo = 0
        val bodyRows = StringBuilder()
        var grandQQ = 0; var grandPP = 0; var grandNN = 0; var grandDD = 0
        var grandCost = 0.0

        for (product in activeProducts) {
            val purchases = byCode[product.stockCode] ?: continue

            var totQQ = 0; var totPP = 0; var totNN = 0; var totDD = 0
            var totCost = 0.0

            val dateRows = StringBuilder()
            purchases.sortedBy { it.purchaseDate }.forEach { p ->
                val dayQQ = p.qqTotalUnits; val dayPP = p.ppTotalUnits
                val dayNN = p.nnTotalUnits; val dayDD = p.ddTotalUnits
                val dayCost = p.totalCost
                totQQ += dayQQ; totPP += dayPP; totNN += dayNN; totDD += dayDD; totCost += dayCost
                val invoiceLine = if (p.invoiceNumber.isNotBlank())
                    "<br><span class='inv'>${p.invoiceNumber}</span>" else ""
                dateRows.append("""<tr class="drow">
                        <td></td>
                        <td class="dl">${fmtDay(p.purchaseDate)}$invoiceLine</td>
                        <td class="n">${qty(dayQQ)}</td>
                        <td class="n">${qty(dayPP)}</td>
                        <td class="n">${qty(dayNN)}</td>
                        <td class="n">${qty(dayDD)}</td>
                        <td class="r">${cur(dayCost)}</td>
                    </tr>""")
            }
            grandQQ += totQQ; grandPP += totPP; grandNN += totNN; grandDD += totDD; grandCost += totCost
            serialNo++
            bodyRows.append("""<tr class="prow">
                    <td class="n">$serialNo</td>
                    <td colspan="5" style="font-weight:bold">${product.displayName}</td>
                    <td class="r" style="font-weight:bold">${cur(totCost)}</td>
                </tr>
                $dateRows
                <tr class="srow">
                    <td colspan="2"></td>
                    <td class="n" style="font-weight:bold">${qty(totQQ)}</td>
                    <td class="n" style="font-weight:bold">${qty(totPP)}</td>
                    <td class="n" style="font-weight:bold">${qty(totNN)}</td>
                    <td class="n" style="font-weight:bold">${qty(totDD)}</td>
                    <td class="r" style="font-weight:bold">${cur(totCost)}</td>
                </tr>""")
        }

        val locationLine = if (location.isNotEmpty()) "<div class='loc'>$location</div>" else ""

        return """<!DOCTYPE html><html><head><meta charset="UTF-8">
<style>
  @page  { margin:15mm 17mm 15mm 22mm }
  body   { font-family:Arial,sans-serif; font-size:13px; margin:0 }
  .hdr   { text-align:center; margin-bottom:10px }
  .biz   { font-size:15px; font-weight:bold; color:#1a237e }
  .loc   { font-size:12px; color:#444; margin-top:2px }
  .rep   { font-size:12px; font-weight:bold; color:#1a237e; margin-top:4px }
  .gen   { font-size:9px; color:#888; margin-top:3px }
  table  { border-collapse:collapse; width:100%; border:1px solid #b0b8d4 }
  th     { background:#1a237e; color:white; padding:4px 5px; font-size:11px;
           border-right:1px solid #3949ab; white-space:nowrap }
  th:last-child { border-right:none }
  td     { padding:3px 5px; border-bottom:1px solid #e0e0e0;
           border-right:1px solid #d0d5e8 }
  td:last-child { border-right:none }
  .n     { text-align:center; white-space:nowrap }
  .r     { text-align:right;  white-space:nowrap }
  .dl    { color:#444 }
  .inv   { font-size:11px; color:#888 }
  tr     { page-break-inside:avoid; break-inside:avoid }
  tr.prow td { background:#e8eaf6; font-size:13px }
  tr.drow td { background:#f9f9ff }
  tr.srow td { background:#c5cae9; font-size:12px; border-top:1px solid #9fa8da }
  tr.totrow td { background:#1a237e; color:white; font-weight:bold; padding:4px 5px;
                 border-right:1px solid #3949ab; white-space:nowrap }
  tr.totrow td:last-child { border-right:none }
  .wmark     { position:fixed; top:50%; left:50%; transform:translate(-50%,-50%) rotate(-40deg); text-align:center; color:rgba(26,35,126,0.07); white-space:nowrap; pointer-events:none; z-index:999 }
  .wmark-app { display:block; font-family:Arial,sans-serif; font-size:68px; font-weight:900; line-height:1 }
  .wmark-co  { display:block; font-family:Arial,sans-serif; font-size:26px; font-weight:600; letter-spacing:3px; margin-top:4px }
  .wmt       { font-size:8px; color:#bbb; margin-top:3px; letter-spacing:0.3px }
</style></head><body>
<div class="hdr">
  <div class="biz">$businessName</div>
  $locationLine
  <div class="rep">$title</div>
  <div class="gen">Generated on $generatedOn</div>
  <div class="wmt"><b>WinEntry</b> &middot; Simhadri Systems</div>
</div>
<table>
  <thead><tr>
    <th>#</th>
    <th style="text-align:left">Product / Date</th>
    <th>QQ</th><th>PP</th><th>NN</th><th>DD</th>
    <th>Cost</th>
  </tr></thead>
  <tbody>
    $bodyRows
    <tr class="totrow">
      <td colspan="2">GRAND TOTAL</td>
      <td class="n">${qty(grandQQ)}</td>
      <td class="n">${qty(grandPP)}</td>
      <td class="n">${qty(grandNN)}</td>
      <td class="n">${qty(grandDD)}</td>
      <td class="r">${cur(grandCost)}</td>
    </tr>
  </tbody>
</table>
<div class="wmark"><span class="wmark-app">WinEntry</span><span class="wmark-co">Simhadri Systems</span></div>
</body></html>"""
    }

    // ── Sales & Profit Margin Report ──────────────────────────────────────────

    private suspend fun buildSalesMarginHtml(
        fromDate: String, toDate: String, title: String
    ): String {
        val stockRows = try {
            repository.getAllDailyStockInRange(fromDate, toDate)
                .filter { it.isCommitted }
        } catch (e: Exception) { return "" }

        if (stockRows.isEmpty()) return ""

        val products = repository.getAllProductsByDailySortKeySync()
        val productMap = products.associateBy { it.stockCode }

        // Aggregate sale quantities and amounts per product code
        data class ProductSales(
            var saleQq: Int = 0, var salePp: Int = 0,
            var saleNn: Int = 0, var saleDd: Int = 0,
            var amtQq: Double = 0.0, var amtPp: Double = 0.0,
            var amtNn: Double = 0.0, var amtDd: Double = 0.0
        )
        val salesByCode = mutableMapOf<String, ProductSales>()
        for (row in stockRows) {
            val s = salesByCode.getOrPut(row.productCode) { ProductSales() }
            s.saleQq += maxOf(row.saleQq, 0); s.salePp += maxOf(row.salePp, 0)
            s.saleNn += maxOf(row.saleNn, 0); s.saleDd += maxOf(row.saleDd, 0)
            s.amtQq += maxOf(row.amountQq, 0.0); s.amtPp += maxOf(row.amountPp, 0.0)
            s.amtNn += maxOf(row.amountNn, 0.0); s.amtDd += maxOf(row.amountDd, 0.0)
        }

        // Keep only products that actually had sales
        val activeCodes = salesByCode.keys
        val activeProducts = products.filter { it.stockCode in activeCodes }
        if (activeProducts.isEmpty()) return ""

        val prefs        = getApp().getSharedPreferences("business_info",
            android.content.Context.MODE_PRIVATE)
        val businessName = prefs.getString("business_name", "") ?: ""
        val location     = prefs.getString("location",      "") ?: ""
        val generatedOn  = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())

        val inFmt  = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val outFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        fun fmtDay(d: String) = try { inFmt.parse(d)?.let { outFmt.format(it) } ?: d } catch (_: Exception) { d }

        val inLocale = Locale("en", "IN")
        fun qty(n: Int): String = if (n == 0) "-"
            else java.text.NumberFormat.getIntegerInstance(inLocale).format(n.toLong())
        fun cur(v: Double): String = if (v == 0.0) "-"
            else "₹" + java.text.NumberFormat.getNumberInstance(inLocale)
                .apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(v)
        fun pct(v: Double): String = String.format(Locale.US, "%.1f%%", v)

        var serialNo = 0
        var grandSale = 0.0; var grandCost = 0.0; var grandMargin = 0.0
        var grandQQ = 0; var grandPP = 0; var grandNN = 0; var grandDD = 0
        val bodyRows = StringBuilder()

        for (product in activeProducts) {
            val s = salesByCode[product.stockCode] ?: continue
            if (s.saleQq + s.salePp + s.saleNn + s.saleDd == 0) continue
            serialNo++

            val costQq = s.saleQq * product.qqPurchasePrice
            val costPp = s.salePp * product.ppPurchasePrice
            val costNn = s.saleNn * product.nnPurchasePrice
            val costDd = s.saleDd * product.ddPurchasePrice
            val totalCost   = costQq + costPp + costNn + costDd
            val totalSale   = s.amtQq + s.amtPp + s.amtNn + s.amtDd
            val totalMargin = totalSale - totalCost
            val marginPct   = if (totalSale > 0) totalMargin / totalSale * 100 else 0.0

            grandSale   += totalSale;   grandCost   += totalCost
            grandMargin += totalMargin; grandQQ += s.saleQq; grandPP += s.salePp
            grandNN += s.saleNn; grandDD += s.saleDd

            // Product header
            bodyRows.append("""<tr class="prow">
                <td class="n">$serialNo</td>
                <td colspan="5" style="font-weight:bold">${product.displayName}</td>
                <td class="r" style="font-weight:bold">${cur(totalSale)}</td>
                <td class="r" style="font-weight:bold">${cur(totalCost)}</td>
                <td class="r" style="font-weight:bold;color:#16a34a">${cur(totalMargin)}</td>
                <td class="n" style="font-weight:bold;color:#16a34a">${pct(marginPct)}</td>
            </tr>""")

            // Per-size detail rows — only for sizes with sales
            data class SizeRow(val label: String, val qty: Int, val saleAmt: Double,
                               val buyP: Double, val sellP: Double)
            listOf(
                SizeRow("QQ", s.saleQq, s.amtQq, product.qqPurchasePrice, product.qqSalePrice),
                SizeRow("PP", s.salePp, s.amtPp, product.ppPurchasePrice, product.ppSalePrice),
                SizeRow("NN", s.saleNn, s.amtNn, product.nnPurchasePrice, product.nnSalePrice),
                SizeRow("DD", s.saleDd, s.amtDd, product.ddPurchasePrice, product.ddSalePrice)
            ).filter { it.qty > 0 }.forEach { sz ->
                val szCost   = sz.qty * sz.buyP
                val szMargin = sz.saleAmt - szCost
                val szMgnPct = if (sz.saleAmt > 0) szMargin / sz.saleAmt * 100 else 0.0
                val mgnPerUnit = if (sz.qty > 0) szMargin / sz.qty else sz.sellP - sz.buyP
                bodyRows.append("""<tr class="drow">
                    <td></td>
                    <td class="sl">${sz.label}</td>
                    <td class="n">${qty(sz.qty)}</td>
                    <td class="r">${cur(sz.buyP)}</td>
                    <td class="r">${cur(sz.sellP)}</td>
                    <td class="r" style="color:#6b7280">${cur(mgnPerUnit)}</td>
                    <td class="r">${cur(sz.saleAmt)}</td>
                    <td class="r">${cur(szCost)}</td>
                    <td class="r" style="color:#16a34a">${cur(szMargin)}</td>
                    <td class="n" style="color:#16a34a">${pct(szMgnPct)}</td>
                </tr>""")
            }
        }

        if (serialNo == 0) return ""

        val grandMgnPct = if (grandSale > 0) grandMargin / grandSale * 100 else 0.0
        val dateRange   = if (fromDate == toDate) fmtDay(fromDate)
                          else "${fmtDay(fromDate)} – ${fmtDay(toDate)}"
        val locationLine = if (location.isNotEmpty()) "<div class='loc'>$location</div>" else ""

        return """<!DOCTYPE html><html><head><meta charset="UTF-8">
<style>
  @page  { margin:15mm 17mm 15mm 22mm }
  body   { font-family:Arial,sans-serif; font-size:12px; margin:0 }
  .hdr   { text-align:center; margin-bottom:10px }
  .biz   { font-size:15px; font-weight:bold; color:#1a237e }
  .loc   { font-size:12px; color:#444; margin-top:2px }
  .rep   { font-size:12px; font-weight:bold; color:#ea580c; margin-top:4px }
  .gen   { font-size:9px;  color:#888; margin-top:3px }
  table  { border-collapse:collapse; width:100%; border:1px solid #fed7aa }
  th     { background:#ea580c; color:white; padding:4px 5px; font-size:10px;
           border-right:1px solid #fb923c; white-space:nowrap }
  th:last-child { border-right:none }
  td     { padding:3px 5px; border-bottom:1px solid #e0e0e0;
           border-right:1px solid #fed7aa }
  td:last-child { border-right:none }
  .n     { text-align:center; white-space:nowrap }
  .r     { text-align:right;  white-space:nowrap }
  .sl    { color:#9a3412; font-weight:bold; font-size:11px; padding-left:18px }
  tr     { page-break-inside:avoid; break-inside:avoid }
  tr.prow td { background:#fff7ed; font-size:12px; border-top:2px solid #fdba74 }
  tr.drow td { background:#fefcfa; font-size:11px }
  tr.totrow td { background:#ea580c; color:white; font-weight:bold; padding:5px;
                 border-right:1px solid #fb923c; white-space:nowrap }
  tr.totrow td:last-child { border-right:none }
  .wmark     { position:fixed; top:50%; left:50%; transform:translate(-50%,-50%) rotate(-40deg); text-align:center; color:rgba(26,35,126,0.07); white-space:nowrap; pointer-events:none; z-index:999 }
  .wmark-app { display:block; font-family:Arial,sans-serif; font-size:68px; font-weight:900; line-height:1 }
  .wmark-co  { display:block; font-family:Arial,sans-serif; font-size:26px; font-weight:600; letter-spacing:3px; margin-top:4px }
  .wmt       { font-size:8px; color:#bbb; margin-top:3px; letter-spacing:0.3px }
</style></head><body>
<div class="hdr">
  <div class="biz">$businessName</div>
  $locationLine
  <div class="rep">Sales &amp; Profit Margin Report</div>
  <div style="font-size:11px;color:#ea580c;margin-top:2px">$dateRange</div>
  <div class="gen">Generated on $generatedOn</div>
  <div class="wmt"><b>WinEntry</b> &middot; Simhadri Systems</div>
</div>
<table>
  <thead>
    <tr>
      <th rowspan="2" style="vertical-align:middle">#</th>
      <th rowspan="2" style="text-align:left;vertical-align:middle">Product / Size</th>
      <th rowspan="2" style="vertical-align:middle">Sale Qty</th>
      <th rowspan="2" style="vertical-align:middle">Buy Price</th>
      <th rowspan="2" style="vertical-align:middle">Sell Price</th>
      <th rowspan="2" style="vertical-align:middle">Margin/Unit</th>
      <th colspan="4">Totals</th>
    </tr>
    <tr>
      <th>Sale Amt</th>
      <th>Cost</th>
      <th>Margin</th>
      <th>Margin%</th>
    </tr>
  </thead>
  <tbody>
    $bodyRows
    <tr class="totrow">
      <td colspan="2">GRAND TOTAL</td>
      <td class="n">${qty(grandQQ + grandPP + grandNN + grandDD)}</td>
      <td colspan="3"></td>
      <td class="r">${cur(grandSale)}</td>
      <td class="r">${cur(grandCost)}</td>
      <td class="r">${cur(grandMargin)}</td>
      <td class="n">${pct(grandMgnPct)}</td>
    </tr>
  </tbody>
</table>
<div style="font-size:9px;color:#9ca3af;margin-top:6px;text-align:right">
  * Buy &amp; Sell prices from current product master. Sale amounts from committed daily stock entries. Margin/Unit is actual average for the period.
</div>
<div class="wmark"><span class="wmark-app">WinEntry</span><span class="wmark-co">Simhadri Systems</span></div>
</body></html>"""
    }

    // ── HTML builders (moved from Fragment) ───────────────────────────────────

    fun buildDailySheetHtml(
        date: String, entries: List<DailyEntry>, @Suppress("UNUSED_PARAMETER") title: String,
        recon: DayReconciliation? = null
    ): String {
        val displayDate = formatDate(date)
        val generatedOn = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
        val prefs        = getApp().getSharedPreferences("business_info",
            android.content.Context.MODE_PRIVATE)
        val businessName = prefs.getString("business_name", "") ?: ""
        val location     = prefs.getString("location",      "") ?: ""

        val inLocale = Locale("en", "IN")
        fun qty(n: Int): String = if (n == 0) "-"
            else java.text.NumberFormat.getIntegerInstance(inLocale).format(n.toLong())
        fun cur(v: Double): String = if (v == 0.0) "-"
            else "₹" + java.text.NumberFormat.getNumberInstance(inLocale)
                .apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(v)

        // Totals
        var totObQQ = 0; var totObPP = 0; var totObNN = 0; var totObDD = 0
        var totPqQQ = 0; var totPqPP = 0; var totPqNN = 0; var totPqDD = 0
        var totCbQQ = 0; var totCbPP = 0; var totCbNN = 0; var totCbDD = 0
        var totSqQQ = 0; var totSqPP = 0; var totSqNN = 0; var totSqDD = 0
        var totSale  = 0.0

        var rowSerial = 0
        val rows = entries.joinToString("") { e ->
            rowSerial++
            val p       = e.product
            val saleAmt = e.sale.qq * p.qqSalePrice + e.sale.pp * p.ppSalePrice +
                          e.sale.nn * p.nnSalePrice  + e.sale.dd * p.ddSalePrice
            totObQQ += e.opening.qq;  totObPP += e.opening.pp
            totObNN += e.opening.nn;  totObDD += e.opening.dd
            totPqQQ += e.purchase.qq; totPqPP += e.purchase.pp
            totPqNN += e.purchase.nn; totPqDD += e.purchase.dd
            totCbQQ += e.closing.qq;  totCbPP += e.closing.pp
            totCbNN += e.closing.nn;  totCbDD += e.closing.dd
            totSqQQ += e.sale.qq;     totSqPP += e.sale.pp
            totSqNN += e.sale.nn;     totSqDD += e.sale.dd
            totSale += saleAmt
            """<tr>
                <td class="n">$rowSerial</td>
                <td>${p.displayName.take(18)}</td>
                <td class="n">${qty(e.opening.qq)}</td><td class="n">${qty(e.opening.pp)}</td>
                <td class="n">${qty(e.opening.nn)}</td><td class="n">${qty(e.opening.dd)}</td>
                <td class="n">${qty(e.purchase.qq)}</td><td class="n">${qty(e.purchase.pp)}</td>
                <td class="n">${qty(e.purchase.nn)}</td><td class="n">${qty(e.purchase.dd)}</td>
                <td class="n">${qty(e.closing.qq)}</td><td class="n">${qty(e.closing.pp)}</td>
                <td class="n">${qty(e.closing.nn)}</td><td class="n">${qty(e.closing.dd)}</td>
                <td class="n">${qty(e.sale.qq)}</td><td class="n">${qty(e.sale.pp)}</td>
                <td class="n">${qty(e.sale.nn)}</td><td class="n">${qty(e.sale.dd)}</td>
                <td class="r">${cur(saleAmt)}</td>
            </tr>"""
        }

        val locationLine = if (location.isNotEmpty())
            "<div class='loc'>$location</div>" else ""

        return """<!DOCTYPE html><html><head><meta charset="UTF-8">
<style>
  @page  { margin:15mm 17mm 15mm 22mm }
  body   { font-family:Arial,sans-serif; font-size:11px; margin:0 }
  .hdr   { text-align:center; margin-bottom:10px }
  .biz   { font-size:15px; font-weight:bold; color:#1a237e }
  .loc   { font-size:12px; color:#444; margin-top:2px }
  .rep   { font-size:12px; font-weight:bold; color:#1a237e; margin-top:4px }
  .gen   { font-size:9px; color:#888; margin-top:3px }
  table  { border-collapse:collapse; width:100%; border:1px solid #b0b8d4 }
  th     { background:#1a237e; color:white; padding:4px 5px; font-size:10px;
           border-right:1px solid #3949ab; white-space:nowrap }
  th:last-child { border-right:none }
  td     { padding:3px 5px; border-bottom:1px solid #e0e0e0;
           border-right:1px solid #d0d5e8 }
  td:last-child { border-right:none }
  .n     { text-align:center; white-space:nowrap }
  .r     { text-align:right;  white-space:nowrap }
  tr     { page-break-inside:avoid; break-inside:avoid }
  tr:nth-child(even) td { background:#eef0f8 }
  tr.totrow td { background:#1a237e; color:white; font-weight:bold; padding:4px 5px;
                 border-right:1px solid #3949ab; white-space:nowrap }
  tr.totrow td:last-child { border-right:none }
  .wmark     { position:fixed; top:50%; left:50%; transform:translate(-50%,-50%) rotate(-40deg); text-align:center; color:rgba(26,35,126,0.07); white-space:nowrap; pointer-events:none; z-index:999 }
  .wmark-app { display:block; font-family:Arial,sans-serif; font-size:68px; font-weight:900; line-height:1 }
  .wmark-co  { display:block; font-family:Arial,sans-serif; font-size:26px; font-weight:600; letter-spacing:3px; margin-top:4px }
  .wmt       { font-size:8px; color:#bbb; margin-top:3px; letter-spacing:0.3px }
</style></head><body>
<div class="hdr">
  <div class="biz">$businessName</div>
  $locationLine
  <div class="rep">Daily Stock Sheet &mdash; $displayDate</div>
  <div class="gen">Generated on $generatedOn</div>
  <div class="wmt"><b>WinEntry</b> &middot; Simhadri Systems</div>
</div>
<table>
  <thead>
    <tr>
      <th rowspan="2" style="text-align:center;vertical-align:middle">#</th>
      <th rowspan="2" style="text-align:left;vertical-align:middle">Product</th>
      <th colspan="4">Opening Balance</th>
      <th colspan="4">Purchase</th>
      <th colspan="4">Closing Balance</th>
      <th colspan="4">Sale Qty</th>
      <th rowspan="2" style="vertical-align:middle">Sale Amt</th>
    </tr>
    <tr>
      <th>QQ</th><th>PP</th><th>NN</th><th>DD</th>
      <th>QQ</th><th>PP</th><th>NN</th><th>DD</th>
      <th>QQ</th><th>PP</th><th>NN</th><th>DD</th>
      <th>QQ</th><th>PP</th><th>NN</th><th>DD</th>
    </tr>
  </thead>
  <tbody>
    $rows
    <tr class="totrow">
      <td colspan="2">TOTAL</td>
      <td class="n">${qty(totObQQ)}</td><td class="n">${qty(totObPP)}</td>
      <td class="n">${qty(totObNN)}</td><td class="n">${qty(totObDD)}</td>
      <td class="n">${qty(totPqQQ)}</td><td class="n">${qty(totPqPP)}</td>
      <td class="n">${qty(totPqNN)}</td><td class="n">${qty(totPqDD)}</td>
      <td class="n">${qty(totCbQQ)}</td><td class="n">${qty(totCbPP)}</td>
      <td class="n">${qty(totCbNN)}</td><td class="n">${qty(totCbDD)}</td>
      <td class="n">${qty(totSqQQ)}</td><td class="n">${qty(totSqPP)}</td>
      <td class="n">${qty(totSqNN)}</td><td class="n">${qty(totSqDD)}</td>
      <td class="r">${cur(totSale)}</td>
    </tr>
  </tbody>
</table>
${if (recon != null) """
<div style="display:flex;justify-content:flex-end;margin-top:14px">
  <table style="border-collapse:collapse;width:auto;min-width:260px;border:1px solid #b0b8d4;font-size:11px">
    <thead>
      <tr>
        <th colspan="2" style="background:#1a237e;color:white;padding:5px 10px;
            text-align:center;font-size:11px;border-right:none">
          Day End Summary &mdash; $displayDate
        </th>
      </tr>
    </thead>
    <tbody>
      <tr>
        <td style="padding:4px 10px;border-bottom:1px solid #e0e0e0;border-right:1px solid #d0d5e8;color:#444">Day Total Sales</td>
        <td style="padding:4px 10px;border-bottom:1px solid #e0e0e0;text-align:right;font-weight:bold">${cur(recon.totalDaySales)}</td>
      </tr>
      <tr style="background:#eef0f8">
        <td style="padding:4px 10px;border-bottom:1px solid #e0e0e0;border-right:1px solid #d0d5e8;color:#444">UPI / Online Receipts</td>
        <td style="padding:4px 10px;border-bottom:1px solid #e0e0e0;text-align:right">${cur(recon.upiReceipts)}</td>
      </tr>
      <tr>
        <td style="padding:4px 10px;border-bottom:1px solid #e0e0e0;border-right:1px solid #d0d5e8;color:#444">Expenses</td>
        <td style="padding:4px 10px;border-bottom:1px solid #e0e0e0;text-align:right">${cur(recon.dayExpenses)}</td>
      </tr>
      <tr style="background:#eef0f8">
        <td style="padding:4px 10px;border-right:1px solid #d0d5e8;font-weight:bold;color:#1a237e">Cash for Bank Deposit</td>
        <td style="padding:4px 10px;text-align:right;font-weight:bold;color:#1a237e">${cur(recon.cashForDeposit)}</td>
      </tr>
      ${if (recon.notes.isNotBlank()) """
      <tr>
        <td style="padding:4px 10px;border-top:1px solid #e0e0e0;border-right:1px solid #d0d5e8;color:#444">Notes</td>
        <td style="padding:4px 10px;border-top:1px solid #e0e0e0;color:#555">${recon.notes}</td>
      </tr>""" else ""}
    </tbody>
  </table>
</div>""" else ""}
<div class="wmark"><span class="wmark-app">WinEntry</span><span class="wmark-co">Simhadri Systems</span></div>
</body></html>"""
    }

    fun buildClosingBalancesHtml(date: String, entries: List<DailyEntry>, @Suppress("UNUSED_PARAMETER") title: String): String {
        val displayDate  = formatDate(date)
        val generatedOn  = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
        val prefs        = getApp().getSharedPreferences("business_info",
            android.content.Context.MODE_PRIVATE)
        val businessName = prefs.getString("business_name", "") ?: ""
        val location     = prefs.getString("location",      "") ?: ""

        val inLocale = Locale("en", "IN")
        fun qty(n: Int): String = java.text.NumberFormat.getIntegerInstance(inLocale).format(n.toLong())
        fun cur(v: Double): String = "₹" + java.text.NumberFormat.getNumberInstance(inLocale)
            .apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(v)

        // Totals accumulators
        var totQQ = 0; var totPP = 0; var totNN = 0; var totDD = 0
        var totValue = 0.0

        var rowSerial = 0
        val rows = entries.joinToString("") { e ->
            rowSerial++
            val p     = e.product
            val value = e.closing.qq * p.qqSalePrice +
                        e.closing.pp * p.ppSalePrice +
                        e.closing.nn * p.nnSalePrice +
                        e.closing.dd * p.ddSalePrice
            totQQ   += e.closing.qq;  totPP += e.closing.pp
            totNN   += e.closing.nn;  totDD += e.closing.dd
            totValue += value
            """<tr>
                <td class="n">$rowSerial</td>
                <td>${p.displayName}</td>
                <td class="n">${qty(e.closing.qq)}</td>
                <td class="n">${qty(e.closing.pp)}</td>
                <td class="n">${qty(e.closing.nn)}</td>
                <td class="n">${qty(e.closing.dd)}</td>
                <td class="r">${cur(value)}</td>
            </tr>"""
        }

        val locationLine = if (location.isNotEmpty())
            "<div class='loc'>$location</div>" else ""

        return """<!DOCTYPE html><html><head><meta charset="UTF-8">
<style>
  @page { margin: 15mm 22mm }
  body  { font-family:Arial,sans-serif; font-size:12px; margin:0 }
  .hdr  { text-align:center; margin-bottom:10px }
  .biz  { font-size:16px; font-weight:bold; color:#1a237e }
  .loc  { font-size:13px; color:#444; margin-top:2px }
  .rep  { font-size:13px; font-weight:bold; color:#1a237e; margin-top:4px }
  .gen  { font-size:10px; color:#888; margin-top:3px }
  table { border-collapse:collapse; width:100%; border:1px solid #b0b8d4 }
  th    { background:#1a237e; color:white; padding:5px 8px; font-size:11px;
          border-right:1px solid #3949ab }
  th:last-child { border-right:none }
  td    { padding:4px 8px; border-bottom:1px solid #e0e0e0;
          border-right:1px solid #d0d5e8 }
  td:last-child { border-right:none }
  .n    { text-align:center }
  .r    { text-align:right }
  tr:nth-child(even) td { background:#eef0f8 }
  tfoot td { background:#1a237e; color:white; font-weight:bold; padding:5px 8px;
             border-right:1px solid #3949ab }
  tfoot td:last-child { border-right:none }
  .wmark     { position:fixed; top:50%; left:50%; transform:translate(-50%,-50%) rotate(-40deg); text-align:center; color:rgba(26,35,126,0.07); white-space:nowrap; pointer-events:none; z-index:999 }
  .wmark-app { display:block; font-family:Arial,sans-serif; font-size:68px; font-weight:900; line-height:1 }
  .wmark-co  { display:block; font-family:Arial,sans-serif; font-size:26px; font-weight:600; letter-spacing:3px; margin-top:4px }
  .wmt       { font-size:8px; color:#bbb; margin-top:3px; letter-spacing:0.3px }
</style></head><body>
<div class="hdr">
  <div class="biz">$businessName</div>
  $locationLine
  <div class="rep">Closing Balances as on $displayDate</div>
  <div class="gen">Generated on $generatedOn</div>
  <div class="wmt"><b>WinEntry</b> &middot; Simhadri Systems</div>
</div>
<table>
  <thead><tr>
    <th>#</th>
    <th style="text-align:left">Product</th>
    <th>QQ</th><th>PP</th><th>NN</th><th>DD</th>
    <th>Value</th>
  </tr></thead>
  <tbody>$rows</tbody>
  <tfoot><tr>
    <td colspan="2">TOTAL</td>
    <td class="n">${qty(totQQ)}</td>
    <td class="n">${qty(totPP)}</td>
    <td class="n">${qty(totNN)}</td>
    <td class="n">${qty(totDD)}</td>
    <td class="r">${cur(totValue)}</td>
  </tr></tfoot>
</table>
<div class="wmark"><span class="wmark-app">WinEntry</span><span class="wmark-co">Simhadri Systems</span></div>
</body></html>"""
    }

    fun buildBrandWiseHtml(
        date: String, entries: List<DailyEntry>, title: String, includeZero: Boolean = false
    ): String {
        val displayDate  = formatDate(date)
        val generatedOn  = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
        val prefs        = getApp().getSharedPreferences("business_info",
            android.content.Context.MODE_PRIVATE)
        val businessName = prefs.getString("business_name", "") ?: ""
        val location     = prefs.getString("location",      "") ?: ""

        val inLocale = Locale("en", "IN")
        fun qty(n: Int): String = if (n == 0) "-"
            else java.text.NumberFormat.getIntegerInstance(inLocale).format(n.toLong())

        val filtered = if (includeZero) entries
            else entries.filter { e ->
                e.opening.qq + e.opening.pp + e.opening.nn + e.opening.dd +
                e.purchase.qq + e.purchase.pp + e.purchase.nn + e.purchase.dd > 0
            }

        // Group by first letter of stockCode in prescribed order
        val groupOrder  = listOf("W", "R", "Y", "E", "V", "M")
        val byGroup     = filtered.groupBy { it.product.stockCode.firstOrNull()?.uppercase() ?: "?" }
        val orderedKeys = groupOrder.filter { byGroup.containsKey(it) } +
                          byGroup.keys.filter { it !in groupOrder }.sorted()

        var gndObQQ = 0; var gndObPP = 0; var gndObNN = 0; var gndObDD = 0
        var gndPqQQ = 0; var gndPqPP = 0; var gndPqNN = 0; var gndPqDD = 0
        var gndCbQQ = 0; var gndCbPP = 0; var gndCbNN = 0; var gndCbDD = 0

        var serialNo = 0
        val bodyRows = StringBuilder()
        for (group in orderedKeys) {
            val groupEntries = byGroup[group] ?: continue

            var grpObQQ = 0; var grpObPP = 0; var grpObNN = 0; var grpObDD = 0
            var grpPqQQ = 0; var grpPqPP = 0; var grpPqNN = 0; var grpPqDD = 0
            var grpCbQQ = 0; var grpCbPP = 0; var grpCbNN = 0; var grpCbDD = 0

            // Group header — show code + name e.g. "W — Whisky"
            val groupLabel = TypeLabels.getType(group)
            val groupHeading = if (groupLabel != group) "$group — $groupLabel" else group
            bodyRows.append("""<tr class="grow">
                <td colspan="14">$groupHeading</td>
            </tr>""")

            for (e in groupEntries) {
                serialNo++
                val p = e.product
                grpObQQ += e.opening.qq; grpObPP += e.opening.pp
                grpObNN += e.opening.nn; grpObDD += e.opening.dd
                grpPqQQ += e.purchase.qq; grpPqPP += e.purchase.pp
                grpPqNN += e.purchase.nn; grpPqDD += e.purchase.dd
                grpCbQQ += e.closing.qq; grpCbPP += e.closing.pp
                grpCbNN += e.closing.nn; grpCbDD += e.closing.dd
                bodyRows.append("""<tr>
                    <td class="n">$serialNo</td>
                    <td>${p.displayName.take(18)}</td>
                    <td class="n">${qty(e.opening.qq)}</td><td class="n">${qty(e.opening.pp)}</td>
                    <td class="n">${qty(e.opening.nn)}</td><td class="n">${qty(e.opening.dd)}</td>
                    <td class="n">${qty(e.purchase.qq)}</td><td class="n">${qty(e.purchase.pp)}</td>
                    <td class="n">${qty(e.purchase.nn)}</td><td class="n">${qty(e.purchase.dd)}</td>
                    <td class="n">${qty(e.closing.qq)}</td><td class="n">${qty(e.closing.pp)}</td>
                    <td class="n">${qty(e.closing.nn)}</td><td class="n">${qty(e.closing.dd)}</td>
                </tr>""")
            }

            // Group total
            bodyRows.append("""<tr class="srow">
                <td colspan="2" style="font-weight:bold">$groupHeading &nbsp;Total</td>
                <td class="n">${qty(grpObQQ)}</td><td class="n">${qty(grpObPP)}</td>
                <td class="n">${qty(grpObNN)}</td><td class="n">${qty(grpObDD)}</td>
                <td class="n">${qty(grpPqQQ)}</td><td class="n">${qty(grpPqPP)}</td>
                <td class="n">${qty(grpPqNN)}</td><td class="n">${qty(grpPqDD)}</td>
                <td class="n">${qty(grpCbQQ)}</td><td class="n">${qty(grpCbPP)}</td>
                <td class="n">${qty(grpCbNN)}</td><td class="n">${qty(grpCbDD)}</td>
            </tr>""")

            gndObQQ += grpObQQ; gndObPP += grpObPP; gndObNN += grpObNN; gndObDD += grpObDD
            gndPqQQ += grpPqQQ; gndPqPP += grpPqPP; gndPqNN += grpPqNN; gndPqDD += grpPqDD
            gndCbQQ += grpCbQQ; gndCbPP += grpCbPP; gndCbNN += grpCbNN; gndCbDD += grpCbDD
        }

        val locationLine = if (location.isNotEmpty()) "<div class='loc'>$location</div>" else ""

        return """<!DOCTYPE html><html><head><meta charset="UTF-8">
<style>
  @page  { margin:15mm 17mm 15mm 22mm }
  body   { font-family:Arial,sans-serif; font-size:11px; margin:0 }
  .hdr   { text-align:center; margin-bottom:10px }
  .biz   { font-size:15px; font-weight:bold; color:#1a237e }
  .loc   { font-size:12px; color:#444; margin-top:2px }
  .rep   { font-size:12px; font-weight:bold; color:#1a237e; margin-top:4px }
  .gen   { font-size:9px; color:#888; margin-top:3px }
  table  { border-collapse:collapse; width:100%; border:1px solid #b0b8d4 }
  th     { background:#1a237e; color:white; padding:4px 5px; font-size:10px;
           border-right:1px solid #3949ab; white-space:nowrap }
  th:last-child { border-right:none }
  td     { padding:3px 5px; border-bottom:1px solid #e0e0e0;
           border-right:1px solid #d0d5e8 }
  td:last-child { border-right:none }
  .n     { text-align:center; white-space:nowrap }
  tr     { page-break-inside:avoid; break-inside:avoid }
  tr:nth-child(even) td { background:#eef0f8 }
  tr.grow td { background:#3949ab; color:white; font-weight:bold;
               font-size:12px; padding:5px 6px; border-right:none;
               page-break-after:avoid }
  tr.srow td { background:#c5cae9; font-weight:bold; font-size:10px;
               border-top:1px solid #9fa8da; white-space:nowrap }
  tr.totrow td { background:#1a237e; color:white; font-weight:bold; padding:4px 5px;
                 border-right:1px solid #3949ab; white-space:nowrap }
  tr.totrow td:last-child { border-right:none }
  .wmark     { position:fixed; top:50%; left:50%; transform:translate(-50%,-50%) rotate(-40deg); text-align:center; color:rgba(26,35,126,0.07); white-space:nowrap; pointer-events:none; z-index:999 }
  .wmark-app { display:block; font-family:Arial,sans-serif; font-size:68px; font-weight:900; line-height:1 }
  .wmark-co  { display:block; font-family:Arial,sans-serif; font-size:26px; font-weight:600; letter-spacing:3px; margin-top:4px }
  .wmt       { font-size:8px; color:#bbb; margin-top:3px; letter-spacing:0.3px }
</style></head><body>
<div class="hdr">
  <div class="biz">$businessName</div>
  $locationLine
  <div class="rep">$title &mdash; $displayDate</div>
  <div class="gen">Generated on $generatedOn</div>
  <div class="wmt"><b>WinEntry</b> &middot; Simhadri Systems</div>
</div>
<table>
  <thead>
    <tr>
      <th rowspan="2" style="text-align:center;vertical-align:middle">#</th>
      <th rowspan="2" style="text-align:left;vertical-align:middle">Product</th>
      <th colspan="4">Opening Balance</th>
      <th colspan="4">Purchase</th>
      <th colspan="4">Closing Balance</th>
    </tr>
    <tr>
      <th>QQ</th><th>PP</th><th>NN</th><th>DD</th>
      <th>QQ</th><th>PP</th><th>NN</th><th>DD</th>
      <th>QQ</th><th>PP</th><th>NN</th><th>DD</th>
    </tr>
  </thead>
  <tbody>
    $bodyRows
    <tr class="totrow">
      <td colspan="2">GRAND TOTAL</td>
      <td class="n">${qty(gndObQQ)}</td><td class="n">${qty(gndObPP)}</td>
      <td class="n">${qty(gndObNN)}</td><td class="n">${qty(gndObDD)}</td>
      <td class="n">${qty(gndPqQQ)}</td><td class="n">${qty(gndPqPP)}</td>
      <td class="n">${qty(gndPqNN)}</td><td class="n">${qty(gndPqDD)}</td>
      <td class="n">${qty(gndCbQQ)}</td><td class="n">${qty(gndCbPP)}</td>
      <td class="n">${qty(gndCbNN)}</td><td class="n">${qty(gndCbDD)}</td>
    </tr>
  </tbody>
</table>
<div class="wmark"><span class="wmark-app">WinEntry</span><span class="wmark-co">Simhadri Systems</span></div>
</body></html>"""
    }

    fun buildNoDataHtml(date: String) = """<!DOCTYPE html>
<html><body style="font-family:Arial;text-align:center;padding:40px">
<h3 style="color:#888">No data found for ${formatDate(date)}</h3>
</body></html>"""

    fun formatDate(dateString: String): String {
        return try {
            val inFmt  = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val outFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            outFmt.format(inFmt.parse(dateString)!!)
        } catch (e: Exception) { dateString }
    }

    private fun getApp() = getApplication<Application>()
}
