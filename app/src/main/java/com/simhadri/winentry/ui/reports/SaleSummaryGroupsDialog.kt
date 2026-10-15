package com.simhadri.winentry.ui.reports

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.simhadri.winentry.utils.AppDialogs
import com.simhadri.winentry.utils.SaleSummaryGroups
import com.simhadri.winentry.utils.SaleSummaryGroups.Slot
import com.simhadri.winentry.utils.TypeLabels

/**
 * Lets the user assign each product type to IML / RTD / the extra group (or leave it
 * out), name the extra group, and choose which groups break up by size.
 * Beer is fixed to type B and shown read-only.
 */
object SaleSummaryGroupsDialog {

    private val CHOICES = listOf(Slot.IML, Slot.RTD, Slot.EXTRA, Slot.NONE)

    fun show(context: Context, productTypes: Collection<String>, onSaved: () -> Unit = {}) {
        val current = SaleSummaryGroups.load(context)
        val dp = { v: Int -> (v * context.resources.displayMetrics.density).toInt() }

        val types = (TypeLabels.typeCodes + productTypes.map { it.uppercase() } + current.assignment.keys)
            .filter { it.isNotBlank() && it != SaleSummaryGroups.BEER_TYPE }
            .distinct()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        fun header(text: String) = TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(4))
        }

        root.addView(header("4th group name (leave blank if not used)"))
        val extraInput = EditText(context).apply {
            setText(current.extraName)
            hint = "e.g. WINE"
            isSingleLine = true
        }
        root.addView(extraInput)

        root.addView(header("Product types"))
        root.addView(TextView(context).apply {
            text = "B  —  ${TypeLabels.getType("B")}   →   BEER (fixed)"
            setPadding(0, dp(6), 0, dp(6))
        })

        val labels = CHOICES.map { current.copy(extraName = extraInput.text.toString()).label(it) }.toMutableList()
        val adapters = mutableListOf<ArrayAdapter<String>>()
        val spinners = linkedMapOf<String, Spinner>()
        for (type in types) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(context).apply { text = TypeLabels.typeDisplay(type) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, labels)
            adapters += adapter
            val spinner = Spinner(context).apply {
                this.adapter = adapter
                setSelection(CHOICES.indexOf(current.slotFor(type)).coerceAtLeast(0))
            }
            row.addView(spinner)
            spinners[type] = spinner
            root.addView(row)
        }

        extraInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                labels[CHOICES.indexOf(Slot.EXTRA)] = current.copy(extraName = s.toString()).label(Slot.EXTRA)
                adapters.forEach { it.notifyDataSetChanged() }
            }
        })

        root.addView(header("Show size break-up (QQ / PP / NN / DD)"))
        val sizeBoxes = SaleSummaryGroups.REPORT_ORDER.associateWith { slot ->
            CheckBox(context).apply {
                text = if (slot == Slot.EXTRA) "4th group" else current.label(slot)
                isChecked = current.showsSizes(slot)
            }.also { root.addView(it) }
        }

        val scroll = ScrollView(context).apply { addView(root) }
        AppDialogs.custom(context, "Daily Sale Summary Groups", scroll, "Save") {
            val assignment = spinners.mapValues { (_, sp) -> CHOICES[sp.selectedItemPosition] }
            SaleSummaryGroups.save(context, SaleSummaryGroups(
                extraName  = extraInput.text.toString(),
                assignment = assignment,
                bySize     = sizeBoxes.mapValues { it.value.isChecked }
            ))
            onSaved()
        }
    }
}
