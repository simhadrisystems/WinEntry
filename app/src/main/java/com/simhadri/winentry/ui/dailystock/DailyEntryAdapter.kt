package com.simhadri.winentry.ui.dailystock

import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.simhadri.winentry.data.entity.DailyEntry
import com.simhadri.winentry.databinding.ItemDailyEntryBinding
import com.simhadri.winentry.R
import android.view.View

class DailyEntryAdapter(
    private val onPurchaseChanged:   (productId: Long, size: String, value: Int) -> Unit,
    private val onClosingChanged:    (productId: Long, size: String, value: Int) -> Unit,
    private val getEntryMode:        () -> DailyStockViewModel.EntryMode,
    private val onNextFromLastField: (adapterPosition: Int) -> Unit,
    private val onDataChanged:       (productId: Long) -> Unit,
    private val onEditClosing:       (entry: DailyEntry) -> Unit,
    private val onSaveProduct:       (productId: Long) -> Unit = {},
    private val onDiscardProduct:    (productId: Long) -> Unit = {},
    private val isProductDirty:      (productId: Long) -> Boolean = { false }
) : ListAdapter<DailyEntry, DailyEntryAdapter.ViewHolder>(DailyEntryDiffCallback()) {

    private val colorQQ             = Color.parseColor("#FF4CAF50")
    private val colorPP             = Color.parseColor("#FF2196F3")
    private val colorNN             = Color.parseColor("#FFFF9800")
    private val colorDD             = Color.parseColor("#FF9C27B0")
    private val colorDisabledBorder = Color.parseColor("#44888888")
    private val colorError          = Color.parseColor("#FFF44336")

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDailyEntryBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            holder.bind(getItem(position), position)
            return
        }
        // Payloads can be merged into a single call by RecyclerView — handle each independently
        // so that DIRTY_CHANGED never suppresses VALUES_CHANGED (e.g. after dialog CB save).
        if (payloads.contains(PAYLOAD_VALUES_CHANGED)) holder.bindValuesOnly(getItem(position))
        if (payloads.contains(PAYLOAD_DIRTY_CHANGED))  holder.bindDirtyIndicatorOnly(getItem(position))
        // Unknown payload → full bind
        if (!payloads.contains(PAYLOAD_VALUES_CHANGED) && !payloads.contains(PAYLOAD_DIRTY_CHANGED))
            holder.bind(getItem(position), position)
    }

    companion object {
        const val PAYLOAD_DIRTY_CHANGED   = "DIRTY_CHANGED"
        /** Only numeric values changed (OB/PQ/CB/sale) — skip full bind. */
        const val PAYLOAD_VALUES_CHANGED  = "VALUES_CHANGED"
    }

    inner class ViewHolder(
        private val binding: ItemDailyEntryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val watchers   = mutableMapOf<EditText, TextWatcher>()
        private var freshFocus = false

        // Guard flag — true while bind()/bindValuesOnly() is calling setText().
        // TextWatchers check this and return immediately if set, preventing
        // LiveData.setValue() from being called while RecyclerView is in a layout pass.
        var isBinding = false

        fun bind(entry: DailyEntry, position: Int) {

            isBinding = true
            watchers.forEach { (et, w) -> et.removeTextChangedListener(w) }
            watchers.clear()

            binding.apply {

                // Product code in column-header label cell (fine print, top-left)
                textProductCode.text = entry.product.brandCode

                // Product name prefixed with its display position number
                val posLabel = if (entry.product.dailySortKey in 1..998) "${entry.product.dailySortKey}. " else ""
                textProductName.text = "$posLabel${entry.product.displayName}"

                // ── Dirty (unsaved) indicator ─────────────────────
                val dirty = isProductDirty(entry.product.id)
                val hasNegativeSale = entry.sale.qq < 0 || entry.sale.pp < 0 ||
                                      entry.sale.nn < 0 || entry.sale.dd < 0
                when {
                    hasNegativeSale -> {
                        // Red row — CB exceeds OB+PQ, user must correct closing balance
                        rowProductName.setBackgroundColor(android.graphics.Color.parseColor("#FFEBEE"))
                        textProductName.text = "⚠ $posLabel${entry.product.displayName}"
                        textProductName.setTextColor(android.graphics.Color.parseColor("#C62828"))
                    }
                    dirty -> {
                        rowProductName.setBackgroundColor(android.graphics.Color.parseColor("#FFF3E0"))
                        textProductName.text = "$posLabel${entry.product.displayName}"
                        textProductName.setTextColor(android.graphics.Color.parseColor("#E65100"))
                    }
                    else -> {
                        rowProductName.setBackgroundColor(android.graphics.Color.parseColor("#DDEEFF"))
                        textProductName.text = "$posLabel${entry.product.displayName}"
                        textProductName.setTextColor(android.graphics.Color.parseColor("#0D47A1"))
                    }
                }

                // Sale amount — zero with red colour when any size has negative sale
                if (hasNegativeSale) {
                    textSaleAmount.text = "₹0.00 ⚠"
                    textSaleAmount.setTextColor(android.graphics.Color.parseColor("#C62828"))
                } else {
                    textSaleAmount.text = String.format("₹%.2f", entry.saleAmount)
                    textSaleAmount.setTextColor(android.graphics.Color.parseColor("#1B5E20"))
                }

                // ── Static rows ───────────────────────────────────
                textOpeningQQ.text = if (entry.opening.qq == 0) "–" else entry.opening.qq.toString()
                textOpeningPP.text = if (entry.opening.pp == 0) "–" else entry.opening.pp.toString()
                textOpeningNN.text = if (entry.opening.nn == 0) "–" else entry.opening.nn.toString()
                textOpeningDD.text = if (entry.opening.dd == 0) "–" else entry.opening.dd.toString()

                // Sale values: 25% dimmed accent colour (read-only), red if negative
                val negativeColor = android.graphics.Color.parseColor("#C62828")
                val sqQQ = dimColor(colorQQ, 0.75f)
                val sqPP = dimColor(colorPP, 0.75f)
                val sqNN = dimColor(colorNN, 0.75f)
                val sqDD = dimColor(colorDD, 0.75f)

                textSaleQQ.text = if (entry.sale.qq == 0) "–" else entry.sale.qq.toString()
                textSaleQQ.setTextColor(if (entry.sale.qq < 0) negativeColor else sqQQ)

                textSalePP.text = if (entry.sale.pp == 0) "–" else entry.sale.pp.toString()
                textSalePP.setTextColor(if (entry.sale.pp < 0) negativeColor else sqPP)

                textSaleNN.text = if (entry.sale.nn == 0) "–" else entry.sale.nn.toString()
                textSaleNN.setTextColor(if (entry.sale.nn < 0) negativeColor else sqNN)

                textSaleDD.text = if (entry.sale.dd == 0) "–" else entry.sale.dd.toString()
                textSaleDD.setTextColor(if (entry.sale.dd < 0) negativeColor else sqDD)

                // ── Editable rows ─────────────────────────────────
                editPurchaseQQ.setText(if (entry.purchase.qq == 0) "" else entry.purchase.qq.toString())
                editPurchasePP.setText(if (entry.purchase.pp == 0) "" else entry.purchase.pp.toString())
                editPurchaseNN.setText(if (entry.purchase.nn == 0) "" else entry.purchase.nn.toString())
                editPurchaseDD.setText(if (entry.purchase.dd == 0) "" else entry.purchase.dd.toString())

                editClosingQQ.setText(entry.closing.qq.toString())
                editClosingPP.setText(entry.closing.pp.toString())
                editClosingNN.setText(entry.closing.nn.toString())
                editClosingDD.setText(entry.closing.dd.toString())

                // ── Visual states ─────────────────────────────────
                val mode            = getEntryMode()
                val purchaseEnabled = false   // PURCHASE mode removed; PQ row is read-only display
                val closingEnabled  = mode == DailyStockViewModel.EntryMode.BALANCE
                val viewMode        = mode == DailyStockViewModel.EntryMode.VIEW

                // Smart closing: only enable if there's stock (opening + purchase > 0)
                val qqHasStock = (entry.opening.qq + entry.purchase.qq) > 0
                val ppHasStock = (entry.opening.pp + entry.purchase.pp) > 0
                val nnHasStock = (entry.opening.nn + entry.purchase.nn) > 0
                val ddHasStock = (entry.opening.dd + entry.purchase.dd) > 0

                // In VIEW mode, purchase fields have no border (transparent); CB fields dimmed
                if (viewMode) {
                    applyFieldState(editPurchaseQQ, false, colorQQ, febleBorder = true)
                    applyFieldState(editPurchasePP, false, colorPP, febleBorder = true)
                    applyFieldState(editPurchaseNN, false, colorNN, febleBorder = true)
                    applyFieldState(editPurchaseDD, false, colorDD, febleBorder = true)
                    applyFieldState(editClosingQQ,  false, colorQQ, alwaysAccentText = true)
                    applyFieldState(editClosingPP,  false, colorPP, alwaysAccentText = true)
                    applyFieldState(editClosingNN,  false, colorNN, alwaysAccentText = true)
                    applyFieldState(editClosingDD,  false, colorDD, alwaysAccentText = true)
                } else {
                    // febleBorder=true → 1dp 40%-opacity stroke so PQ boxes recede
                    applyFieldState(editPurchaseQQ, purchaseEnabled, colorQQ, febleBorder = true)
                    applyFieldState(editPurchasePP, purchaseEnabled, colorPP, febleBorder = true)
                    applyFieldState(editPurchaseNN, purchaseEnabled, colorNN, febleBorder = true)
                    applyFieldState(editPurchaseDD, purchaseEnabled, colorDD, febleBorder = true)
                    // Closing only enabled if in BALANCE mode AND has stock
                    applyFieldState(editClosingQQ,  closingEnabled && qqHasStock,  colorQQ, alwaysAccentText = true)
                    applyFieldState(editClosingPP,  closingEnabled && ppHasStock,  colorPP, alwaysAccentText = true)
                    applyFieldState(editClosingNN,  closingEnabled && nnHasStock,  colorNN, alwaysAccentText = true)
                    applyFieldState(editClosingDD,  closingEnabled && ddHasStock,  colorDD, alwaysAccentText = true)
                }

                // ── CB label area (btnEditClosing is a LinearLayout) ──────
                // Always visible. In BALANCE mode: green background,
                // "entry" sub-label shown, whole area clickable.
                // In VIEW/PURCHASE: transparent background, not clickable.
                if (closingEnabled) {
                    binding.btnEditClosing.setBackgroundColor(android.graphics.Color.parseColor("#A5D6A7"))
                    binding.btnEditClosing.isClickable = true
                    binding.btnEditClosing.isFocusable = true
                    binding.btnEditClosing.setOnClickListener { onEditClosing(entry) }
                    binding.textCbEntryLabel.visibility = View.VISIBLE
                } else {
                    binding.btnEditClosing.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    binding.btnEditClosing.isClickable = false
                    binding.btnEditClosing.isFocusable = false
                    binding.btnEditClosing.setOnClickListener(null)
                    binding.textCbEntryLabel.visibility = View.INVISIBLE
                }

                // ── Per-product save / cancel buttons in name row ──
                // Both appear together when this product has unsaved edits.
                // 💾 tap save = commit just this product immediately.
                // ✖  tap cancel = discard edits, revert to last saved values.
                val btnVis = if (dirty) View.VISIBLE else View.GONE
                binding.btnSaveProduct.visibility   = btnVis
                binding.btnCancelProduct.visibility = btnVis
                binding.btnSaveProduct.setOnClickListener {
                    // Hide buttons immediately on tap — don't wait for LiveData observer
                    // which may arrive before or after the new list is committed.
                    binding.btnSaveProduct.visibility   = View.GONE
                    binding.btnCancelProduct.visibility = View.GONE
                    binding.rowProductName.setBackgroundColor(android.graphics.Color.parseColor("#DDEEFF"))
                    binding.textProductName.setTextColor(android.graphics.Color.parseColor("#0D47A1"))
                    onSaveProduct(entry.product.id)
                }
                binding.btnCancelProduct.setOnClickListener {
                    binding.btnSaveProduct.visibility   = View.GONE
                    binding.btnCancelProduct.visibility = View.GONE
                    onDiscardProduct(entry.product.id)
                }

                // ── IME Next navigation ───────────────────────────
                // Build an ordered list of (field, isEnabled) for the active mode.
                // wireImeChain() connects each field to the next enabled one in sequence;
                // the last enabled field calls onNextFromLastField(position) to cross
                // into the next card (which also skips cards with no editable fields).
                if (purchaseEnabled) {
                    wireImeChain(
                        listOf(
                            editPurchaseQQ to true,
                            editPurchasePP to true,
                            editPurchaseNN to true,
                            editPurchaseDD to true
                        ),
                        position
                    )
                }
                if (closingEnabled) {
                    wireImeChain(
                        listOf(
                            editClosingQQ to qqHasStock,
                            editClosingPP to ppHasStock,
                            editClosingNN to nnHasStock,
                            editClosingDD to ddHasStock
                        ),
                        position
                    )
                }

                // ── Watchers ──────────────────────────────────────
                if (purchaseEnabled) {
                    attachWatcher(editPurchaseQQ, entry, "QQ", isPurchase = true)
                    attachWatcher(editPurchasePP, entry, "PP", isPurchase = true)
                    attachWatcher(editPurchaseNN, entry, "NN", isPurchase = true)
                    attachWatcher(editPurchaseDD, entry, "DD", isPurchase = true)
                }
                if (closingEnabled) {
                    attachWatcher(editClosingQQ, entry, "QQ", isPurchase = false)
                    attachWatcher(editClosingPP, entry, "PP", isPurchase = false)
                    attachWatcher(editClosingNN, entry, "NN", isPurchase = false)
                    attachWatcher(editClosingDD, entry, "DD", isPurchase = false)
                }
            }
            isBinding = false
        }


        // ── Visual state ─────────────────────────────────────────
        private fun applyFieldState(et: EditText, enabled: Boolean, accent: Int,
                                       alwaysAccentText: Boolean = false,
                                       febleBorder: Boolean = false) {
            et.isEnabled              = enabled
            et.isFocusable            = enabled
            et.isFocusableInTouchMode = enabled
            // Alpha always 1.0 — dimming is applied selectively below:
            //   text colour uses 25% dim (75% opacity) when passive
            //   border uses 50% dim (50% opacity) always when disabled
            et.alpha = 1.0f

            // Text: PQ row (febleBorder) always red; otherwise accent when enabled
            val textColor = when {
                febleBorder      -> Color.parseColor("#C62828")
                enabled          -> accent
                alwaysAccentText -> dimColor(accent, 0.75f)
                else             -> android.graphics.Color.parseColor("#1A1A1A")
            }
            et.setTextColor(textColor)

            // Border: full 2dp accent when enabled normally (StateListDrawable handles focus
            //         highlight automatically — no focus listener needed)
            //         febleBorder=true (PQ row): always transparent — no box, BG color alone
            //         disabled: 1dp at 50% opacity always
            et.background = when {
                febleBorder -> android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                enabled     -> buildEnabledBackground(accent)
                else        -> buildBorder(dimColor(accent, 0.50f), 1)
            }
        }

        /** Return [color] at [opacity] (0.0 fully transparent … 1.0 fully opaque). */
        private fun dimColor(color: Int, opacity: Float): Int {
            val alpha = (opacity * 255).toInt().coerceIn(0, 255)
            return (color and 0x00FFFFFF) or (alpha shl 24)
        }

        private fun buildBorder(color: Int, dp: Int)
                : android.graphics.drawable.GradientDrawable {
            val density = itemView.resources.displayMetrics.density
            return android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(Color.TRANSPARENT)
                setStroke((dp * density).toInt(), color)
                cornerRadius = 4f * density
            }
        }

        /** StateListDrawable for an enabled CB cell.
         *  Android applies the focused state automatically — no listener needed.
         *  Focused  → 30% accent fill + 2dp stroke (cell lights up distinctly)
         *  Default  → transparent fill + 2dp stroke (cell recedes) */
        private fun buildEnabledBackground(accent: Int): android.graphics.drawable.StateListDrawable {
            val density  = itemView.resources.displayMetrics.density
            val fillColor = (accent and 0x00FFFFFF) or (0x4D shl 24) // 30% opacity
            val focused  = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(fillColor)
                setStroke((2 * density).toInt(), accent)
                cornerRadius = 4f * density
            }
            val normal   = buildBorder(accent, 2)
            return android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focused)
                addState(intArrayOf(), normal)
            }
        }

        // ── IME Next ─────────────────────────────────────────────
        /**
         * Wire IME "Next" through a sequence of (EditText, isEnabled) pairs.
         *
         * For each enabled field, Next jumps to the next enabled field in the list.
         * Disabled fields are skipped entirely — no focusSearch(), no layout guessing.
         * The last enabled field in the list triggers onNextFromLastField(position)
         * so the Fragment can find the first enabled field of the next product card.
         *
         * If no fields are enabled at all, nothing is wired (card has no editable cells).
         */
        private fun wireImeChain(fields: List<Pair<EditText, Boolean>>, position: Int) {
            val enabled = fields.filter { it.second }.map { it.first }
            if (enabled.isEmpty()) return

            for (i in enabled.indices) {
                val current = enabled[i]
                current.imeOptions = EditorInfo.IME_ACTION_NEXT
                if (i < enabled.lastIndex) {
                    val next = enabled[i + 1]
                    current.setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_NEXT) {
                            next.requestFocus()
                            next.post { next.selectAll() }
                            true
                        } else false
                    }
                } else {
                    // Last enabled field in this card → cross to next card
                    current.setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_NEXT) {
                            onNextFromLastField(position)
                            true
                        } else false
                    }
                }
            }
        }

        // ── Values only (date navigation — no layout changes) ────────
        // Called when DiffUtil detects only numeric values changed.
        // Updates OB/PQ/CB/sale text and sale amount — leaves card structure,
        // backgrounds, and watcher setup completely untouched.
        fun bindValuesOnly(entry: DailyEntry) {
            isBinding = true
            binding.apply {
                textProductCode.text = entry.product.brandCode
                // Opening
                textOpeningQQ.text = if (entry.opening.qq == 0) "–" else entry.opening.qq.toString()
                textOpeningPP.text = if (entry.opening.pp == 0) "–" else entry.opening.pp.toString()
                textOpeningNN.text = if (entry.opening.nn == 0) "–" else entry.opening.nn.toString()
                textOpeningDD.text = if (entry.opening.dd == 0) "–" else entry.opening.dd.toString()

                // Purchase (read-only display — watchers handle edits)
                editPurchaseQQ.setText(if (entry.purchase.qq == 0) "" else entry.purchase.qq.toString())
                editPurchasePP.setText(if (entry.purchase.pp == 0) "" else entry.purchase.pp.toString())
                editPurchaseNN.setText(if (entry.purchase.nn == 0) "" else entry.purchase.nn.toString())
                editPurchaseDD.setText(if (entry.purchase.dd == 0) "" else entry.purchase.dd.toString())

                // Closing
                editClosingQQ.setText(entry.closing.qq.toString())
                editClosingPP.setText(entry.closing.pp.toString())
                editClosingNN.setText(entry.closing.nn.toString())
                editClosingDD.setText(entry.closing.dd.toString())

                // Sale qty with negative colour
                val negativeColor = android.graphics.Color.parseColor("#C62828")
                val sqQQ = dimColor(colorQQ, 0.75f); val sqPP = dimColor(colorPP, 0.75f)
                val sqNN = dimColor(colorNN, 0.75f); val sqDD = dimColor(colorDD, 0.75f)
                textSaleQQ.text = if (entry.sale.qq == 0) "–" else entry.sale.qq.toString()
                textSaleQQ.setTextColor(if (entry.sale.qq < 0) negativeColor else sqQQ)
                textSalePP.text = if (entry.sale.pp == 0) "–" else entry.sale.pp.toString()
                textSalePP.setTextColor(if (entry.sale.pp < 0) negativeColor else sqPP)
                textSaleNN.text = if (entry.sale.nn == 0) "–" else entry.sale.nn.toString()
                textSaleNN.setTextColor(if (entry.sale.nn < 0) negativeColor else sqNN)
                textSaleDD.text = if (entry.sale.dd == 0) "–" else entry.sale.dd.toString()
                textSaleDD.setTextColor(if (entry.sale.dd < 0) negativeColor else sqDD)

                // Sale amount
                val hasNeg = entry.sale.qq < 0 || entry.sale.pp < 0 ||
                             entry.sale.nn < 0 || entry.sale.dd < 0
                if (hasNeg) {
                    textSaleAmount.text = "₹0.00 ⚠"
                    textSaleAmount.setTextColor(android.graphics.Color.parseColor("#C62828"))
                } else {
                    textSaleAmount.text = String.format("₹%.2f", entry.saleAmount)
                    textSaleAmount.setTextColor(android.graphics.Color.parseColor("#1B5E20"))
                }

                // Negative-sale product row flag (updates without full rebind)
                if (hasNeg) {
                    rowProductName.setBackgroundColor(android.graphics.Color.parseColor("#FFEBEE"))
                    val posLabel = if (entry.product.dailySortKey in 1..998)
                        "${entry.product.dailySortKey}. " else ""
                    textProductName.text = "⚠ $posLabel${entry.product.displayName}"
                    textProductName.setTextColor(android.graphics.Color.parseColor("#C62828"))
                }
            }
            isBinding = false
        }

        // ── Dirty indicator only (no EditText reset) ─────────────
        // Called when only the dirty state changed — avoids destroying
        // in-progress text edits by skipping all EditText.setText() calls.
        fun bindDirtyIndicatorOnly(entry: DailyEntry) {
            val posLabel = if (entry.product.dailySortKey in 1..998) "${entry.product.dailySortKey}. " else ""
            val dirty = isProductDirty(entry.product.id)
            val hasNegativeSale = entry.sale.qq < 0 || entry.sale.pp < 0 ||
                                  entry.sale.nn < 0 || entry.sale.dd < 0
            binding.apply {
                when {
                    hasNegativeSale -> {
                        rowProductName.setBackgroundColor(android.graphics.Color.parseColor("#FFEBEE"))
                        textProductName.text = "⚠ $posLabel${entry.product.displayName}"
                        textProductName.setTextColor(android.graphics.Color.parseColor("#C62828"))
                        btnSaveProduct.visibility   = View.VISIBLE
                        btnCancelProduct.visibility = View.VISIBLE
                        btnSaveProduct.setOnClickListener {
                            btnSaveProduct.visibility   = View.GONE
                            btnCancelProduct.visibility = View.GONE
                            rowProductName.setBackgroundColor(android.graphics.Color.parseColor("#DDEEFF"))
                            textProductName.setTextColor(android.graphics.Color.parseColor("#0D47A1"))
                            onSaveProduct(entry.product.id)
                        }
                        btnCancelProduct.setOnClickListener {
                            btnSaveProduct.visibility   = View.GONE
                            btnCancelProduct.visibility = View.GONE
                            onDiscardProduct(entry.product.id)
                        }
                    }
                    dirty -> {
                        rowProductName.setBackgroundColor(android.graphics.Color.parseColor("#FFF3E0"))
                        textProductName.text = "$posLabel${entry.product.displayName}"
                        textProductName.setTextColor(android.graphics.Color.parseColor("#E65100"))
                        btnSaveProduct.visibility   = View.VISIBLE
                        btnCancelProduct.visibility = View.VISIBLE
                        btnSaveProduct.setOnClickListener {
                            btnSaveProduct.visibility   = View.GONE
                            btnCancelProduct.visibility = View.GONE
                            rowProductName.setBackgroundColor(android.graphics.Color.parseColor("#DDEEFF"))
                            textProductName.setTextColor(android.graphics.Color.parseColor("#0D47A1"))
                            onSaveProduct(entry.product.id)
                        }
                        btnCancelProduct.setOnClickListener {
                            btnSaveProduct.visibility   = View.GONE
                            btnCancelProduct.visibility = View.GONE
                            onDiscardProduct(entry.product.id)
                        }
                    }
                    else -> {
                        rowProductName.setBackgroundColor(android.graphics.Color.parseColor("#DDEEFF"))
                        textProductName.text = "$posLabel${entry.product.displayName}"
                        textProductName.setTextColor(android.graphics.Color.parseColor("#0D47A1"))
                        btnSaveProduct.visibility   = View.GONE
                        btnCancelProduct.visibility = View.GONE
                    }
                }
            }
        }

        // ── TextWatcher with validation ───────────────────────────
        private fun attachWatcher(
            editText: EditText,
            entry: DailyEntry,
            size: String,
            isPurchase: Boolean
        ) {
            val accent = when (size) {
                "QQ" -> colorQQ; "PP" -> colorPP; "NN" -> colorNN; else -> colorDD
            }

            val watcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { freshFocus = false }
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (isBinding) return   // setText() called during bind — ignore
                    val value = s?.toString()?.toIntOrNull() ?: 0

                    if (isPurchase) {
                        // PQ row has no borders — keep transparent so typing doesn't restore box
                        editText.background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                        editText.error = null
                        onPurchaseChanged(entry.product.id, size, value)
                        onDataChanged(entry.product.id)
                        
                        // Update Closing and Sale fields immediately in the UI
                        // without triggering a full rebind (which would destroy focus)
                        val opening = when (size) {
                            "QQ" -> entry.opening.qq
                            "PP" -> entry.opening.pp
                            "NN" -> entry.opening.nn
                            else -> entry.opening.dd
                        }
                        val newClosing = opening + value
                        val newSale = 0  // opening + purchase - (opening + purchase) = 0
                        
                        when (size) {
                            "QQ" -> {
                                binding.editClosingQQ.setText(newClosing.toString())
                                binding.textSaleQQ.text = if (newSale == 0) "–" else newSale.toString()
                            }
                            "PP" -> {
                                binding.editClosingPP.setText(newClosing.toString())
                                binding.textSalePP.text = if (newSale == 0) "–" else newSale.toString()
                            }
                            "NN" -> {
                                binding.editClosingNN.setText(newClosing.toString())
                                binding.textSaleNN.text = if (newSale == 0) "–" else newSale.toString()
                            }
                            "DD" -> {
                                binding.editClosingDD.setText(newClosing.toString())
                                binding.textSaleDD.text = if (newSale == 0) "–" else newSale.toString()
                            }
                        }
                        updateSaleAmount(entry)
                    } else {
                        val opening = when (size) {
                            "QQ" -> entry.opening.qq; "PP" -> entry.opening.pp
                            "NN" -> entry.opening.nn; else -> entry.opening.dd
                        }
                        val purchase = when (size) {
                            "QQ" -> binding.editPurchaseQQ.text.toString().toIntOrNull() ?: entry.purchase.qq
                            "PP" -> binding.editPurchasePP.text.toString().toIntOrNull() ?: entry.purchase.pp
                            "NN" -> binding.editPurchaseNN.text.toString().toIntOrNull() ?: entry.purchase.nn
                            else -> binding.editPurchaseDD.text.toString().toIntOrNull() ?: entry.purchase.dd
                        }
                        val max = opening + purchase
                        val newSale = opening + purchase - value

                        // Only block if CB is negative (physically impossible)
                        // or if it would exceed OB+PQ AND the current displayed sale
                        // is already a valid positive number (not a data correction scenario).
                        // We read the current sale from the UI text (not entry) so we
                        // always see the live value, not the stale closure-captured value.
                        val currentDisplayedSale = when (size) {
                            "QQ" -> binding.textSaleQQ.text.toString().toIntOrNull() ?: 0
                            "PP" -> binding.textSalePP.text.toString().toIntOrNull() ?: 0
                            "NN" -> binding.textSaleNN.text.toString().toIntOrNull() ?: 0
                            else -> binding.textSaleDD.text.toString().toIntOrNull() ?: 0
                        }
                        // Block only if: new CB > OB+PQ (sale goes negative)
                        //            AND current sale is already a valid positive value
                        //            AND CB itself is not negative
                        val isCorrection = currentDisplayedSale < 0 || value < 0
                        val wouldCreateInvalidSale = newSale < 0 && !isCorrection

                        if (value < 0) {
                            // CB can never be negative
                            editText.background = buildBorder(colorError, 2)
                            editText.error = "CB cannot be negative"
                        } else if (wouldCreateInvalidSale) {
                            editText.background = buildBorder(colorError, 2)
                            editText.error = "Max: $max  (OB $opening + PQ $purchase)"
                        } else {
                            editText.background = buildBorder(accent, 2)
                            editText.error = null
                            onClosingChanged(entry.product.id, size, value)
                            onDataChanged(entry.product.id)

                            // Update Sale field immediately in UI
                            when (size) {
                                "QQ" -> binding.textSaleQQ.text = if (newSale == 0) "–" else newSale.toString()
                                "PP" -> binding.textSalePP.text = if (newSale == 0) "–" else newSale.toString()
                                "NN" -> binding.textSaleNN.text = if (newSale == 0) "–" else newSale.toString()
                                "DD" -> binding.textSaleDD.text = if (newSale == 0) "–" else newSale.toString()
                            }
                            updateSaleAmount(entry)
                        }
                    }
                }
            }
            editText.addTextChangedListener(watcher)
            watchers[editText] = watcher

            // Smart tap: selectAll ONLY on initial focus
            // Flag is reset on first keystroke via the TextWatcher
            editText.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    freshFocus = true
                    editText.post { 
                        if (freshFocus) {
                            editText.selectAll()
                        }
                    }
                } else {
                    freshFocus = false
                }
            }
        }
        
        /**
         * Recalculate and update the sale amount display
         * Called whenever sale quantities change
         */
        private fun updateSaleAmount(entry: DailyEntry) {
            // "–" is displayed for zero-sale sizes — treat as 0
            val qqSale = binding.textSaleQQ.text.toString().let { if (it == "–") 0 else it.toIntOrNull() ?: 0 }
            val ppSale = binding.textSalePP.text.toString().let { if (it == "–") 0 else it.toIntOrNull() ?: 0 }
            val nnSale = binding.textSaleNN.text.toString().let { if (it == "–") 0 else it.toIntOrNull() ?: 0 }
            val ddSale = binding.textSaleDD.text.toString().let { if (it == "–") 0 else it.toIntOrNull() ?: 0 }
            
            val totalAmount = (qqSale * entry.product.qqSalePrice) +
                            (ppSale * entry.product.ppSalePrice) +
                            (nnSale * entry.product.nnSalePrice) +
                            (ddSale * entry.product.ddSalePrice)
            
            binding.textSaleAmount.text = String.format("₹%.2f", totalAmount)
        }
    }

    class DailyEntryDiffCallback : DiffUtil.ItemCallback<DailyEntry>() {
        override fun areItemsTheSame(o: DailyEntry, n: DailyEntry) =
            o.product.id == n.product.id
        override fun areContentsTheSame(o: DailyEntry, n: DailyEntry) =
            o == n
        /**
         * When only the numeric values changed (date navigation), return
         * PAYLOAD_VALUES_CHANGED so the adapter skips a full bind and only
         * updates the number cells — card structure, EditTexts and backgrounds stay.
         */
        override fun getChangePayload(o: DailyEntry, n: DailyEntry): Any? {
            val productUnchanged = o.product == n.product
            val valuesChanged    = o.opening  != n.opening  ||
                                   o.purchase != n.purchase ||
                                   o.closing  != n.closing  ||
                                   o.sale     != n.sale
            // If both values AND product changed, force a full bind (return null).
            // If only values changed, use VALUES_CHANGED payload — bindValuesOnly() runs.
            // If product metadata changed (e.g. dirty indicator embedded in product),
            // use DIRTY_CHANGED so bindDirtyIndicatorOnly() runs and hides save/cancel buttons.
            // Note: dirty state is tracked externally via dirtyProductIds LiveData,
            // so DiffUtil alone cannot detect it — we always do a full bind when
            // product data is unchanged but values also unchanged (pure dirty toggle).
            return if (productUnchanged && valuesChanged) PAYLOAD_VALUES_CHANGED else null
        }
    }
}

/**
 * Single-item adapter for the Total Sale header row.
 * Used with ConcatAdapter so the total scrolls with the list —
 * visible at the top, disappears under the sticky bar on scroll-down.
 */
class SaleTotalAdapter : RecyclerView.Adapter<SaleTotalAdapter.ViewHolder>() {

    private var totalSale: Double = 0.0

    fun updateTotal(total: Double) {
        totalSale = total
        notifyItemChanged(0)
    }

    override fun getItemCount() = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sale_total, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(totalSale)
    }

    class ViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        private val textTotal =
            itemView.findViewById<android.widget.TextView>(R.id.textTotalSaleAmount)
        fun bind(total: Double) {
            val fmt = java.text.NumberFormat.getNumberInstance(java.util.Locale("en", "IN"))
            fmt.minimumFractionDigits = 2
            fmt.maximumFractionDigits = 2
            textTotal.text = "₹${fmt.format(total)}"
        }
    }
}
