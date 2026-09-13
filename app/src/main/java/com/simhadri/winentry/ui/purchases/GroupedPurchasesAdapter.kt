package com.simhadri.winentry.ui.purchases

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.simhadri.winentry.data.entity.Purchase
import com.simhadri.winentry.databinding.ItemPurchaseGroupedBinding
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

data class GroupedItem(
    val purchase: Purchase,
    val displayDate: String,
    val showDateHeader: Boolean,
    val dateTotal: Double,
    val showInvoiceHeader: Boolean,
    val invoiceTotal: Double,
    val invoiceTotalBoxes: Int,      // NEW
    val invoiceTotalLoose: Int,      // NEW
    val invoiceTotalUnits: Int,      // NEW
    val invoiceReceivedDate: String, // NEW — blank means same as purchaseDate
    val showInvoiceFooter: Boolean   // NEW — true for last item in each invoice
)

class GroupedPurchasesAdapter(
    private val onItemClick: (Purchase) -> Unit,
    private val onItemLongClick: (Purchase, View) -> Unit,
    private val onSelectionChanged: (Int) -> Unit,
    private val onReceivedDateTap: (invoiceNumber: String, purchaseDate: String, currentReceivedDate: String) -> Unit = { _, _, _ -> }
) : ListAdapter<GroupedItem, GroupedPurchasesAdapter.ViewHolder>(DIFF) {

    var isMultiSelectMode: Boolean = false
        set(value) { field = value; notifyDataSetChanged() }

    private val selectedIds = mutableSetOf<Long>()

    inner class ViewHolder(val binding: ItemPurchaseGroupedBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: GroupedItem) {
            val p = item.purchase
            val currencyFmt = NumberFormat.getCurrencyInstance(Locale("en", "IN"))

            // Date header
            if (item.showDateHeader) {
                binding.cardDateHeader.visibility = View.VISIBLE
                binding.textDate.text = item.displayDate
                binding.textDateTotal.text = "Total: ${currencyFmt.format(item.dateTotal)}"
            } else {
                binding.cardDateHeader.visibility = View.GONE
            }

            // Invoice sub-header
            if (item.showInvoiceHeader) {
                binding.layoutInvoiceHeader.visibility = View.VISIBLE
                binding.textInvoiceNumber.text = p.invoiceNumber.ifBlank { "No Invoice" }
                binding.textInvoiceTotal.text = currencyFmt.format(item.invoiceTotal)
            } else {
                binding.layoutInvoiceHeader.visibility = View.GONE
            }

            // Invoice stats row — shown below invoice header
            if (item.showInvoiceHeader && (item.invoiceTotalBoxes > 0 || item.invoiceTotalLoose > 0)) {
                binding.layoutInvoiceStats.visibility = View.VISIBLE
                binding.textInvoiceBoxes.text = "${item.invoiceTotalBoxes} Boxes"
                binding.textInvoiceLoose.text = "${item.invoiceTotalLoose} Loose"
                binding.textInvoiceUnits.text = "${item.invoiceTotalUnits} Units"
            } else {
                binding.layoutInvoiceStats.visibility = View.GONE
            }

            // Invoice received-date row — shown below stats on the first product of each invoice
            if (item.showInvoiceHeader) {
                binding.layoutInvoiceFooter.visibility = View.VISIBLE
                val effDate = item.invoiceReceivedDate.ifBlank { item.purchase.purchaseDate }
                val displayReceived = try {
                    DB_FMT.parse(effDate)?.let { DISP_FMT.format(it) } ?: effDate
                } catch (_: Exception) { effDate }
                binding.textInvoiceReceivedDate.text = "Received: $displayReceived  •  Double-tap to change"

                // Double-tap opens date picker to set/change received date
                binding.layoutInvoiceFooter.setOnClickListener(
                    object : android.view.View.OnClickListener {
                        private var lastClickTime = 0L
                        override fun onClick(v: android.view.View) {
                            val now = System.currentTimeMillis()
                            if (now - lastClickTime < 350L) {
                                onReceivedDateTap(
                                    p.invoiceNumber,
                                    p.purchaseDate,
                                    item.invoiceReceivedDate.ifBlank { p.purchaseDate }
                                )
                                lastClickTime = 0L
                            } else {
                                lastClickTime = now
                            }
                        }
                    }
                )
            } else {
                binding.layoutInvoiceFooter.visibility = View.GONE
                binding.layoutInvoiceFooter.setOnClickListener(null)
            }

            // Day totals row hidden
            binding.layoutDayTotals.visibility = View.GONE

            // Product name and total
            binding.textProductName.text = "${p.productName} (${p.productCode})"
            binding.textProductTotal.text = currencyFmt.format(p.totalCost)

            // Size detail rows built fully programmatically
            binding.layoutSizes.removeAllViews()
            val ctx = binding.root.context
            val dp4 = (4 * ctx.resources.displayMetrics.density).toInt()

            // Header row for size columns (only if any sizes have data)
            val sizesWithData = listOf(
                listOf(p.qqTotalUnits, p.qqBoxes, p.qqLoose, p.qqTotalCost, "QQ"),
                listOf(p.ppTotalUnits, p.ppBoxes, p.ppLoose, p.ppTotalCost, "PP"),
                listOf(p.nnTotalUnits, p.nnBoxes, p.nnLoose, p.nnTotalCost, "NN"),
                listOf(p.ddTotalUnits, p.ddBoxes, p.ddLoose, p.ddTotalCost, "DD")
            ).filter { (it[0] as Int) > 0 }



            sizesWithData.forEach { row ->
                val units = row[0] as Int
                val boxes = row[1] as Int
                val loose = row[2] as Int
                val cost  = row[3] as Double
                val size  = row[4] as String
                val dataRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp4, 0, 0)
                }
                dataRow.addView(TextView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.12f)
                    text = size; textSize = 12f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(android.graphics.Color.parseColor("#1565C0"))
                })
                dataRow.addView(TextView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.15f)
                    text = boxes.toString(); textSize = 12f; gravity = android.view.Gravity.CENTER
                    setTextColor(android.graphics.Color.parseColor("#424242"))
                })
                dataRow.addView(TextView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.15f)
                    text = loose.toString(); textSize = 12f; gravity = android.view.Gravity.CENTER
                    setTextColor(android.graphics.Color.parseColor("#424242"))
                })
                dataRow.addView(TextView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.23f)
                    text = units.toString(); textSize = 12f; gravity = android.view.Gravity.CENTER
                    setTextColor(android.graphics.Color.parseColor("#424242"))
                })
                dataRow.addView(TextView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.35f)
                    text = currencyFmt.format(cost); textSize = 12f; gravity = android.view.Gravity.END
                    setTextColor(android.graphics.Color.parseColor("#2E7D32"))
                })
                binding.layoutSizes.addView(dataRow)
            }

            // Checkbox shown only in multi-select mode
            binding.checkboxSelect.visibility = if (isMultiSelectMode) View.VISIBLE else View.GONE
            binding.checkboxSelect.isChecked = selectedIds.contains(p.id)

            // Double-tap opens action dialog; in multi-select mode single tap toggles selection
            binding.cardProduct.setOnClickListener(object : android.view.View.OnClickListener {
                private var lastClickTime = 0L
                override fun onClick(v: android.view.View) {
                    if (isMultiSelectMode) {
                        toggleSelection(p.id)
                        binding.checkboxSelect.isChecked = selectedIds.contains(p.id)
                        return
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastClickTime < 350L) {
                        onItemClick(p)
                        lastClickTime = 0L
                    } else {
                        lastClickTime = now
                    }
                }
            })
            binding.cardProduct.setOnLongClickListener(null)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPurchaseGroupedBinding.inflate(
            android.view.LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
        // Alternating very-light card backgrounds — white / pale green
        val bgColor = if (position % 2 == 0) 0xFFFFFFFF.toInt() else 0xFFF0FDF4.toInt()
        holder.binding.cardProduct.setCardBackgroundColor(bgColor)
    }

    private fun toggleSelection(id: Long) {
        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
        onSelectionChanged(selectedIds.size)
        notifyDataSetChanged()
    }

    fun selectAll() {
        currentList.forEach { selectedIds.add(it.purchase.id) }
        onSelectionChanged(selectedIds.size)
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedIds.clear()
        onSelectionChanged(0)
        notifyDataSetChanged()
    }

    fun getSelectedPurchases(): List<Purchase> =
        currentList.filter { selectedIds.contains(it.purchase.id) }.map { it.purchase }

    /** Called externally (e.g. from long-press in Fragment) to toggle selection on an item */
    fun toggleSelectionPublic(id: Long) {
        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
        onSelectionChanged(selectedIds.size)
        notifyDataSetChanged()
    }

    companion object {

        private val DB_FMT = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        private val DISP_FMT = SimpleDateFormat("dd MMM yyyy (EEE)", Locale.getDefault())

        private fun formatDate(d: String): String = try {
            DB_FMT.parse(d)?.let { DISP_FMT.format(it) } ?: d
        } catch (e: Exception) { d }

        fun groupPurchasesByDate(purchases: List<Purchase>): List<GroupedItem> =
            groupPurchasesByDateAndInvoice(purchases)

        fun groupPurchasesByDateAndInvoice(purchases: List<Purchase>): List<GroupedItem> {
            if (purchases.isEmpty()) return emptyList()

            val sorted = purchases.sortedWith(
                compareByDescending<Purchase> { it.purchaseDate }
                    .thenBy { it.invoiceNumber }
                    .thenBy { it.id }
            )

            val dateTotals = sorted.groupBy { it.purchaseDate }
                .mapValues { (_, ps) -> ps.sumOf { it.totalCost } }

            val invoiceKey = { p: Purchase -> "${p.purchaseDate}|${p.invoiceNumber}" }
            val invoiceTotals = sorted.groupBy(invoiceKey)
                .mapValues { (_, ps) -> ps.sumOf { it.totalCost } }
            val invoiceBoxes = sorted.groupBy(invoiceKey)
                .mapValues { (_, ps) -> ps.sumOf { it.qqBoxes + it.ppBoxes + it.nnBoxes + it.ddBoxes } }
            val invoiceLoose = sorted.groupBy(invoiceKey)
                .mapValues { (_, ps) -> ps.sumOf { it.qqLoose + it.ppLoose + it.nnLoose + it.ddLoose } }
            val invoiceUnits = sorted.groupBy(invoiceKey)
                .mapValues { (_, ps) -> ps.sumOf { it.qqTotalUnits + it.ppTotalUnits + it.nnTotalUnits + it.ddTotalUnits } }
            val invoiceReceived = sorted.groupBy(invoiceKey)
                .mapValues { (_, ps) -> ps.first().receivedDate }

            // Track last index for each invoice key
            val lastIndexByInvoice = mutableMapOf<String, Int>()
            sorted.forEachIndexed { i, p -> lastIndexByInvoice[invoiceKey(p)] = i }

            var lastDate = ""
            var lastInvoiceKeyStr = ""
            val result = mutableListOf<GroupedItem>()

            sorted.forEachIndexed { i, p ->
                val ik = invoiceKey(p)
                result.add(
                    GroupedItem(
                        purchase             = p,
                        displayDate          = formatDate(p.purchaseDate),
                        showDateHeader       = p.purchaseDate != lastDate,
                        dateTotal            = dateTotals[p.purchaseDate] ?: 0.0,
                        showInvoiceHeader    = ik != lastInvoiceKeyStr,
                        invoiceTotal         = invoiceTotals[ik] ?: 0.0,
                        invoiceTotalBoxes    = invoiceBoxes[ik] ?: 0,
                        invoiceTotalLoose    = invoiceLoose[ik] ?: 0,
                        invoiceTotalUnits    = invoiceUnits[ik] ?: 0,
                        invoiceReceivedDate  = invoiceReceived[ik] ?: "",
                        showInvoiceFooter    = lastIndexByInvoice[ik] == i
                    )
                )
                lastDate          = p.purchaseDate
                lastInvoiceKeyStr = ik
            }

            return result
        }

        private val DIFF = object : DiffUtil.ItemCallback<GroupedItem>() {
            override fun areItemsTheSame(a: GroupedItem, b: GroupedItem) =
                a.purchase.id == b.purchase.id
            override fun areContentsTheSame(a: GroupedItem, b: GroupedItem) = a == b
        }
    }
}
// Extension to allow external toggle (e.g. from long-press in Fragment)
