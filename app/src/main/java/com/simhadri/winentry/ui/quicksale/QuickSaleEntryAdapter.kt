package com.simhadri.winentry.ui.quicksale

import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.simhadri.winentry.R
import com.simhadri.winentry.databinding.ItemQuickSaleEntryBinding

/**
 * ListAdapter for the Quick Sale Check scratchpad. Mode-aware: OB/CB mode shows
 * editable Opening/Purchase/Closing rows plus a computed read-only Sale Qty row;
 * Direct Qty mode hides those and shows one editable Sale Qty row instead.
 *
 * Mirrors DailyEntryAdapter's visual language (colours, borders, CB bar) and its
 * two business rules for the Closing field:
 *   - a size's Closing cell is only editable once that size has stock (Opening + Purchase > 0)
 *   - Closing can never exceed Opening + Purchase (would create a negative sale)
 *
 * No per-row save/cancel or dirty tracking — there is nothing to save here.
 */
class QuickSaleEntryAdapter(
    private val getMode: () -> QuickSaleMode,
    private val getShowPurchase: () -> Boolean,
    private val onOpeningChanged: (productId: Long, size: String, value: Int) -> Unit,
    private val onPurchaseChanged: (productId: Long, size: String, value: Int) -> Unit,
    private val onClosingChanged: (productId: Long, size: String, value: Int) -> Unit,
    private val onDirectSaleChanged: (productId: Long, size: String, value: Int) -> Unit
) : ListAdapter<QuickSaleRow, QuickSaleEntryAdapter.ViewHolder>(QuickSaleRowDiffCallback()) {

    companion object {
        const val PAYLOAD_VALUES_CHANGED = "VALUES_CHANGED"
    }

    private val colorQQ = Color.parseColor("#FF4CAF50")
    private val colorPP = Color.parseColor("#FF2196F3")
    private val colorNN = Color.parseColor("#FFFF9800")
    private var colorDD = Color.parseColor("#CE93D8")
    private val colorError = Color.parseColor("#FFF44336")

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        colorDD = ContextCompat.getColor(parent.context, R.color.app_color_dd)
        val binding = ItemQuickSaleEntryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), getMode(), getShowPurchase())
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty() || !payloads.contains(PAYLOAD_VALUES_CHANGED)) {
            holder.bind(getItem(position), getMode(), getShowPurchase())
        } else {
            holder.bindComputedOnly(getItem(position), getMode())
        }
    }

    inner class ViewHolder(
        private val binding: ItemQuickSaleEntryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val watchers = mutableMapOf<EditText, TextWatcher>()
        private var isBinding = false

        fun bind(row: QuickSaleRow, mode: QuickSaleMode, showPurchase: Boolean) {
            isBinding = true
            watchers.forEach { (et, w) -> et.removeTextChangedListener(w) }
            watchers.clear()

            binding.apply {
                textProductCode.text = row.product.brandCode
                val posLabel = if (row.product.dailySortKey in 1..998) "${row.product.dailySortKey}. " else ""
                textProductName.text = "$posLabel${row.product.displayName}"

                val isObCb = mode == QuickSaleMode.OB_CB
                groupObCb.visibility = if (isObCb) View.VISIBLE else View.GONE
                groupDirect.visibility = if (isObCb) View.GONE else View.VISIBLE

                if (isObCb) {
                    rowPurchase.visibility = if (showPurchase) View.VISIBLE else View.GONE
                    dividerAfterPurchase.visibility = if (showPurchase) View.VISIBLE else View.GONE

                    editOpeningQQ.setText(if (row.opening.qq == 0) "" else row.opening.qq.toString())
                    editOpeningPP.setText(if (row.opening.pp == 0) "" else row.opening.pp.toString())
                    editOpeningNN.setText(if (row.opening.nn == 0) "" else row.opening.nn.toString())
                    editOpeningDD.setText(if (row.opening.dd == 0) "" else row.opening.dd.toString())

                    editPurchaseQQ.setText(if (row.purchase.qq == 0) "" else row.purchase.qq.toString())
                    editPurchasePP.setText(if (row.purchase.pp == 0) "" else row.purchase.pp.toString())
                    editPurchaseNN.setText(if (row.purchase.nn == 0) "" else row.purchase.nn.toString())
                    editPurchaseDD.setText(if (row.purchase.dd == 0) "" else row.purchase.dd.toString())

                    editClosingQQ.setText(row.closing.qq.toString())
                    editClosingPP.setText(row.closing.pp.toString())
                    editClosingNN.setText(row.closing.nn.toString())
                    editClosingDD.setText(row.closing.dd.toString())

                    applyClosingFieldState(editClosingQQ, row.opening.qq + row.purchase.qq > 0, colorQQ)
                    applyClosingFieldState(editClosingPP, row.opening.pp + row.purchase.pp > 0, colorPP)
                    applyClosingFieldState(editClosingNN, row.opening.nn + row.purchase.nn > 0, colorNN)
                    applyClosingFieldState(editClosingDD, row.opening.dd + row.purchase.dd > 0, colorDD)

                    attachOpeningWatcher(editOpeningQQ, row, "QQ", colorQQ)
                    attachOpeningWatcher(editOpeningPP, row, "PP", colorPP)
                    attachOpeningWatcher(editOpeningNN, row, "NN", colorNN)
                    attachOpeningWatcher(editOpeningDD, row, "DD", colorDD)

                    attachPurchaseWatcher(editPurchaseQQ, row, "QQ", colorQQ)
                    attachPurchaseWatcher(editPurchasePP, row, "PP", colorPP)
                    attachPurchaseWatcher(editPurchaseNN, row, "NN", colorNN)
                    attachPurchaseWatcher(editPurchaseDD, row, "DD", colorDD)

                    attachClosingWatcher(editClosingQQ, row, "QQ", colorQQ)
                    attachClosingWatcher(editClosingPP, row, "PP", colorPP)
                    attachClosingWatcher(editClosingNN, row, "NN", colorNN)
                    attachClosingWatcher(editClosingDD, row, "DD", colorDD)
                } else {
                    editDirectQQ.setText(row.directSale.qq.toString())
                    editDirectPP.setText(row.directSale.pp.toString())
                    editDirectNN.setText(row.directSale.nn.toString())
                    editDirectDD.setText(row.directSale.dd.toString())

                    attachWatcher(editDirectQQ, row, "QQ") { id, s, v -> onDirectSaleChanged(id, s, v) }
                    attachWatcher(editDirectPP, row, "PP") { id, s, v -> onDirectSaleChanged(id, s, v) }
                    attachWatcher(editDirectNN, row, "NN") { id, s, v -> onDirectSaleChanged(id, s, v) }
                    attachWatcher(editDirectDD, row, "DD") { id, s, v -> onDirectSaleChanged(id, s, v) }
                }

                bindComputedText(row, mode)
            }
            isBinding = false
        }

        /** Payload path — only updates the read-only computed cells, never touches an EditText. */
        fun bindComputedOnly(row: QuickSaleRow, mode: QuickSaleMode) {
            bindComputedText(row, mode)
        }

        private fun bindComputedText(row: QuickSaleRow, mode: QuickSaleMode) {
            val sale = row.sale(mode)
            binding.textSaleQQ.text = if (sale.qq == 0) "–" else sale.qq.toString()
            binding.textSalePP.text = if (sale.pp == 0) "–" else sale.pp.toString()
            binding.textSaleNN.text = if (sale.nn == 0) "–" else sale.nn.toString()
            binding.textSaleDD.text = if (sale.dd == 0) "–" else sale.dd.toString()

            val negative = sale.qq < 0 || sale.pp < 0 || sale.nn < 0 || sale.dd < 0
            if (negative) {
                binding.textSaleAmount.text = "₹0.00 ⚠"
                binding.textSaleAmount.setTextColor(Color.parseColor("#C62828"))
            } else {
                binding.textSaleAmount.text = String.format("₹%.2f", row.saleAmount(mode))
                binding.textSaleAmount.setTextColor(Color.parseColor("#1B5E20"))
            }
        }

        // ── Opening / Purchase watchers — also re-gate the sibling Closing field live ──

        private fun attachOpeningWatcher(editText: EditText, row: QuickSaleRow, size: String, accent: Int) {
            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (isBinding) return
                    val value = s?.toString()?.toIntOrNull() ?: 0
                    onOpeningChanged(row.product.id, size, value)
                    val purchase = currentPurchase(size, row)
                    regateClosing(size, value + purchase, accent, row)
                }
            }
            editText.addTextChangedListener(watcher)
            watchers[editText] = watcher
        }

        private fun attachPurchaseWatcher(editText: EditText, row: QuickSaleRow, size: String, accent: Int) {
            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (isBinding) return
                    val value = s?.toString()?.toIntOrNull() ?: 0
                    onPurchaseChanged(row.product.id, size, value)
                    val opening = currentOpening(size, row)
                    regateClosing(size, opening + value, accent, row)
                }
            }
            editText.addTextChangedListener(watcher)
            watchers[editText] = watcher
        }

        /**
         * Re-enable/disable + re-validate a size's Closing field after OB or PQ changes.
         * Setting text (when needed) re-triggers that field's own watcher, which is what
         * actually reports the corrected value to the ViewModel — kept in one place.
         */
        private fun regateClosing(size: String, newStock: Int, accent: Int, row: QuickSaleRow) {
            val closingField = closingFieldFor(size)
            val hasStock = newStock > 0
            applyClosingFieldState(closingField, hasStock, accent)
            val currentClosing = closingField.text.toString().toIntOrNull() ?: 0
            if (!hasStock && currentClosing != 0) {
                closingField.setText("0")
            } else if (hasStock && currentClosing > newStock) {
                closingField.setText(newStock.toString())
            }
        }

        private fun closingFieldFor(size: String): EditText = when (size) {
            "QQ" -> binding.editClosingQQ; "PP" -> binding.editClosingPP
            "NN" -> binding.editClosingNN; else -> binding.editClosingDD
        }

        private fun currentOpening(size: String, row: QuickSaleRow): Int = when (size) {
            "QQ" -> binding.editOpeningQQ.text.toString().toIntOrNull() ?: row.opening.qq
            "PP" -> binding.editOpeningPP.text.toString().toIntOrNull() ?: row.opening.pp
            "NN" -> binding.editOpeningNN.text.toString().toIntOrNull() ?: row.opening.nn
            else -> binding.editOpeningDD.text.toString().toIntOrNull() ?: row.opening.dd
        }

        private fun currentPurchase(size: String, row: QuickSaleRow): Int = when (size) {
            "QQ" -> binding.editPurchaseQQ.text.toString().toIntOrNull() ?: row.purchase.qq
            "PP" -> binding.editPurchasePP.text.toString().toIntOrNull() ?: row.purchase.pp
            "NN" -> binding.editPurchaseNN.text.toString().toIntOrNull() ?: row.purchase.nn
            else -> binding.editPurchaseDD.text.toString().toIntOrNull() ?: row.purchase.dd
        }

        /** Closing editable only once OB+PQ>0 for this size — read-only/dimmed otherwise. */
        private fun applyClosingFieldState(et: EditText, enabled: Boolean, accent: Int) {
            et.isEnabled = enabled
            et.isFocusable = enabled
            et.isFocusableInTouchMode = enabled
            et.setTextColor(if (enabled) accent else dimColor(accent, 0.5f))
            et.background = if (enabled) buildEnabledBackground(accent) else buildBorder(dimColor(accent, 0.4f), 1)
        }

        // ── Closing watcher — validates CB ≤ OB+PQ, never lets it go negative ──
        private fun attachClosingWatcher(editText: EditText, row: QuickSaleRow, size: String, accent: Int) {
            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (isBinding) return
                    val value = s?.toString()?.toIntOrNull() ?: 0
                    val opening = currentOpening(size, row)
                    val purchase = currentPurchase(size, row)
                    val max = opening + purchase

                    when {
                        value < 0 -> {
                            editText.background = buildBorder(colorError, 2)
                            editText.error = "CB cannot be negative"
                        }
                        value > max -> {
                            editText.background = buildBorder(colorError, 2)
                            editText.error = "Max: $max  (OB $opening + PQ $purchase)"
                        }
                        else -> {
                            editText.background = buildEnabledBackground(accent)
                            editText.error = null
                            onClosingChanged(row.product.id, size, value)
                        }
                    }
                }
            }
            editText.addTextChangedListener(watcher)
            watchers[editText] = watcher
        }

        private fun attachWatcher(
            editText: EditText,
            row: QuickSaleRow,
            size: String,
            onChanged: (productId: Long, size: String, value: Int) -> Unit
        ) {
            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (isBinding) return
                    val value = s?.toString()?.toIntOrNull() ?: 0
                    onChanged(row.product.id, size, value)
                }
            }
            editText.addTextChangedListener(watcher)
            watchers[editText] = watcher
        }

        // ── Visual helpers (mirrors DailyEntryAdapter's border/dim builders) ──
        private fun dimColor(color: Int, opacity: Float): Int {
            val alpha = (opacity * 255).toInt().coerceIn(0, 255)
            return (color and 0x00FFFFFF) or (alpha shl 24)
        }

        private fun buildBorder(color: Int, dp: Int): android.graphics.drawable.GradientDrawable {
            val density = itemView.resources.displayMetrics.density
            return android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(Color.TRANSPARENT)
                setStroke((dp * density).toInt(), color)
                cornerRadius = 4f * density
            }
        }

        private fun buildEnabledBackground(accent: Int): android.graphics.drawable.StateListDrawable {
            val density = itemView.resources.displayMetrics.density
            val fillColor = (accent and 0x00FFFFFF) or (0x4D shl 24)
            val focused = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(fillColor)
                setStroke((2 * density).toInt(), accent)
                cornerRadius = 4f * density
            }
            val normal = buildBorder(accent, 2)
            return android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focused)
                addState(intArrayOf(), normal)
            }
        }
    }

    class QuickSaleRowDiffCallback : DiffUtil.ItemCallback<QuickSaleRow>() {
        override fun areItemsTheSame(o: QuickSaleRow, n: QuickSaleRow) =
            o.product.id == n.product.id
        override fun areContentsTheSame(o: QuickSaleRow, n: QuickSaleRow) = o == n
        override fun getChangePayload(o: QuickSaleRow, n: QuickSaleRow): Any? {
            val productUnchanged = o.product == n.product
            val valuesChanged = o.opening != n.opening || o.purchase != n.purchase ||
                                o.closing != n.closing || o.directSale != n.directSale
            return if (productUnchanged && valuesChanged) PAYLOAD_VALUES_CHANGED else null
        }
    }
}
