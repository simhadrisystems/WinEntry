package com.simple.simpleinventory.ui.dailystock

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.simple.simpleinventory.R
import com.simple.simpleinventory.data.entity.DailyEntry

/**
 * Pop-up dialog for entering closing balances by Boxes + Loose units.
 *
 * Formula:  Total Units = (Boxes × unitsPerBox) + Loose
 *
 * Pre-fills from current CB values:
 *   Boxes  = currentCB / unitsPerBox
 *   Loose  = currentCB % unitsPerBox
 *
 * Only sizes where OB + PQ > 0 are editable.
 * Sizes with no stock are shown greyed-out (read-only).
 *
 * isCancelable = false — prevents accidental dismiss by outside touch.
 * User must explicitly tap Save or Cancel.
 *
 * On Save → fires FragmentResult "closingEntry" with new totals.
 * On Cancel → no change (existing CB preserved as-is).
 */
class ClosingEntryDialog : DialogFragment() {

    // ── Size row data ─────────────────────────────────────────────
    private data class SizeRow(
        val label: String,           // "NN", "PP", "QQ", "DD"
        val unitsPerBox: Int,
        val currentTotal: Int,       // existing CB
        val maxTotal: Int,           // OB + PQ (hard upper limit)
        val hasStock: Boolean,       // OB + PQ > 0 → editable
        val salePrice: Double        // sale price per unit from product
    )

    companion object {
        const val TAG            = "ClosingEntryDialog"
        const val REQUEST_KEY    = "closingEntry"
        const val KEY_PRODUCT_ID = "productId"
        const val KEY_QQ         = "qq"
        const val KEY_PP         = "pp"
        const val KEY_NN         = "nn"
        const val KEY_DD         = "dd"

        fun newInstance(entry: DailyEntry): ClosingEntryDialog {
            val p = entry.product
            return ClosingEntryDialog().apply {
                arguments = bundleOf(
                    "productId"   to p.id,
                    "productName" to p.displayName,
                    "brandCode"   to p.brandCode,
                    // Current CB
                    "qqCB" to entry.closing.qq,
                    "ppCB" to entry.closing.pp,
                    "nnCB" to entry.closing.nn,
                    "ddCB" to entry.closing.dd,
                    // Max = OB + PQ
                    "qqMax" to (entry.opening.qq + entry.purchase.qq),
                    "ppMax" to (entry.opening.pp + entry.purchase.pp),
                    "nnMax" to (entry.opening.nn + entry.purchase.nn),
                    "ddMax" to (entry.opening.dd + entry.purchase.dd),
                    // Units per box
                    "qqUpb" to p.qqUnitsPerBox,
                    "ppUpb" to p.ppUnitsPerBox,
                    "nnUpb" to p.nnUnitsPerBox,
                    "ddUpb" to p.ddUnitsPerBox,
                    // Sale prices
                    "qqSalePrice" to p.qqSalePrice,
                    "ppSalePrice" to p.ppSalePrice,
                    "nnSalePrice" to p.nnSalePrice,
                    "ddSalePrice" to p.ddSalePrice
                )
            }
        }
    }

    // ── State: computed totals (updated live as user types) ───────
    private val totals = mutableMapOf("QQ" to 0, "PP" to 0, "NN" to 0, "DD" to 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // EDIT PROTECTION: prevent accidental dismiss by tapping outside
        isCancelable = false
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = buildView()
        return MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .create()
            .also { it.window?.setBackgroundDrawableResource(android.R.color.transparent) }
    }

    private fun buildView(): View {
        val inflater = LayoutInflater.from(requireContext())
        val root = inflater.inflate(R.layout.dialog_closing_entry, null)

        val args = requireArguments()

        // Header
        root.findViewById<TextView>(R.id.textProductName).text =
            args.getString("productName", "")
        root.findViewById<TextView>(R.id.textBrandCode).text =
            args.getString("brandCode", "")

        // Build size rows — stock-first order: NN, PP, QQ, DD
        val sizes = listOf(
            SizeRow("NN", args.getInt("nnUpb"), args.getInt("nnCB"),
                args.getInt("nnMax"), args.getInt("nnMax") > 0,
                args.getDouble("nnSalePrice")),
            SizeRow("PP", args.getInt("ppUpb"), args.getInt("ppCB"),
                args.getInt("ppMax"), args.getInt("ppMax") > 0,
                args.getDouble("ppSalePrice")),
            SizeRow("QQ", args.getInt("qqUpb"), args.getInt("qqCB"),
                args.getInt("qqMax"), args.getInt("qqMax") > 0,
                args.getDouble("qqSalePrice")),
            SizeRow("DD", args.getInt("ddUpb"), args.getInt("ddCB"),
                args.getInt("ddMax"), args.getInt("ddMax") > 0,
                args.getDouble("ddSalePrice"))
        )

        // Initialise totals map with current CB values
        sizes.forEach { totals[it.label] = it.currentTotal }

        // Wire up each size row
        bindSizeRow(root, "NN", sizes[0])
        bindSizeRow(root, "PP", sizes[1])
        bindSizeRow(root, "QQ", sizes[2])
        bindSizeRow(root, "DD", sizes[3])

        // Cancel — dismiss with no changes
        root.findViewById<MaterialButton>(R.id.btnCancel).setOnClickListener {
            dismiss()
        }

        // Save — validate then fire result
        root.findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            val errors = sizes.filter { row ->
                row.hasStock && totals[row.label]!! > row.maxTotal
            }
            if (errors.isNotEmpty()) {
                val msg = errors.joinToString("\n") { row ->
                    "${row.label}: ${totals[row.label]} > max ${row.maxTotal}"
                }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Closing Balance Exceeds Stock")
                    .setMessage("Cannot save — closing exceeds opening + purchase:\n\n$msg")
                    .setPositiveButton("Fix", null)
                    .show()
                return@setOnClickListener
            }

            setFragmentResult(REQUEST_KEY, bundleOf(
                KEY_PRODUCT_ID to args.getLong("productId"),
                KEY_QQ to totals["QQ"]!!,
                KEY_PP to totals["PP"]!!,
                KEY_NN to totals["NN"]!!,
                KEY_DD to totals["DD"]!!
            ))
            dismiss()
        }

        return root
    }

    /**
     * Wire up one size row:
     *  - Pre-fill Boxes and Loose from currentTotal
     *  - Live-update Total as user types
     *  - Show sale price in the last column (replaces old Max hint)
     *  - Grey out and disable if no stock
     */
    private fun bindSizeRow(root: View, size: String, row: SizeRow) {
        val res = root.context.packageName

        val rowContainer = root.findViewById<View>(
            root.resources.getIdentifier("row$size", "id", res))
        val editBoxes = root.findViewById<EditText>(
            root.resources.getIdentifier("edit${size}Boxes", "id", res))
        val editLoose = root.findViewById<EditText>(
            root.resources.getIdentifier("edit${size}Loose", "id", res))
        val textTotal = root.findViewById<TextView>(
            root.resources.getIdentifier("text${size}Total", "id", res))
        val textSalePrice = root.findViewById<TextView>(
            root.resources.getIdentifier("text${size}Max", "id", res))   // reusing Max view ID
        val textUpb = root.findViewById<TextView>(
            root.resources.getIdentifier("text${size}Upb", "id", res))

        // Show units-per-box hint
        textUpb?.text = "×${row.unitsPerBox}"

        // Show sale price instead of Max — format as ₹N or ₹N.NN
        val priceDisplay = if (row.salePrice % 1.0 == 0.0)
            "₹${row.salePrice.toInt()}"
        else
            "₹${String.format("%.2f", row.salePrice)}"
        textSalePrice?.text = priceDisplay

        if (!row.hasStock) {
            // No stock — grey out entire row
            rowContainer?.alpha = 0.35f
            editBoxes?.isEnabled = false
            editLoose?.isEnabled = false
            editBoxes?.setText("0")
            editLoose?.setText("0")
            textTotal?.text = "0"
            totals[size] = 0
            return
        }

        // Pre-fill from current CB
        val initBoxes = if (row.unitsPerBox > 0) row.currentTotal / row.unitsPerBox else 0
        val initLoose = if (row.unitsPerBox > 0) row.currentTotal % row.unitsPerBox else row.currentTotal
        editBoxes?.setText(initBoxes.toString())
        editLoose?.setText(initLoose.toString())
        textTotal?.text = row.currentTotal.toString()

        // Live total calculation
        fun recalc() {
            val boxes = editBoxes?.text.toString().toIntOrNull() ?: 0
            val loose = editLoose?.text.toString().toIntOrNull() ?: 0
            val total = (boxes * row.unitsPerBox) + loose
            totals[size] = total
            textTotal?.text = total.toString()

            // Colour feedback on total
            val color = when {
                total > row.maxTotal  -> 0xFFF44336.toInt()   // red — over stock
                total == row.maxTotal -> 0xFF4CAF50.toInt()   // green — exact (no sale)
                else                  -> 0xFF2196F3.toInt()   // blue — partial sale
            }
            textTotal?.setTextColor(color)
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { recalc() }
        }
        editBoxes?.addTextChangedListener(watcher)
        editLoose?.addTextChangedListener(watcher)

        // Select all on focus for fast overtype
        listOf(editBoxes, editLoose).forEach { et ->
            et?.setOnFocusChangeListener { _, has ->
                if (has) et.post { et.selectAll() }
            }
        }
    }
}
