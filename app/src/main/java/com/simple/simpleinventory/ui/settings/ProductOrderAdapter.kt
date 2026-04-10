package com.simple.simpleinventory.ui.settings

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.simple.simpleinventory.data.entity.Product
import com.simple.simpleinventory.databinding.ItemInactiveProductBinding
import com.simple.simpleinventory.databinding.ItemProductOrderBinding
import com.simple.simpleinventory.databinding.ItemSectionHeaderBinding

/**
 * Adapter for the Product Display Order screen.
 *
 * Flat list structure:
 *   Header("Active (N)")
 *   Active items  — draggable, nudgeable, badge-editable
 *   Header("Inactive (M)")   ← omitted when no inactive products
 *   Inactive items — read-only, long-press to activate
 *
 * EDIT ON:
 *   Active items: drag / nudge / type badge. Inactive items: visible but static.
 *   Long-press drag on active items only.
 *
 * EDIT OFF (read mode):
 *   Long-press active   → deactivate dialog
 *   Long-press inactive → activate dialog
 */
class ProductOrderAdapter(
    private val onVisualReorder: (List<Product>) -> Unit,
    private val onLabelTyped:    (productId: Long, typedNumber: Int) -> Unit,
    private val getDraftLabel:   (productId: Long) -> Int,
    private val onDeactivate:    (Product) -> Unit,
    private val onActivate:      (Product) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val VT_HEADER   = 0
        const val VT_ACTIVE   = 1
        const val VT_INACTIVE = 2
    }

    sealed class DisplayItem {
        data class Header(val title: String)      : DisplayItem()
        data class Active(val product: Product)   : DisplayItem()
        data class Inactive(val product: Product) : DisplayItem()
    }

    var touchHelper: ItemTouchHelper? = null
    var editEnabled: Boolean = false
    var recyclerView: RecyclerView? = null

    private val items = mutableListOf<DisplayItem>()

    fun submitLists(active: List<Product>, inactive: List<Product>) {
        val newItems = mutableListOf<DisplayItem>()
        newItems.add(DisplayItem.Header("Active  (${active.size})"))
        active.forEach { newItems.add(DisplayItem.Active(it)) }
        if (inactive.isNotEmpty()) {
            newItems.add(DisplayItem.Header("Inactive  (${inactive.size})"))
            inactive.forEach { newItems.add(DisplayItem.Inactive(it)) }
        }
        val apply = Runnable {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }
        val rv = recyclerView
        if (rv != null && rv.isComputingLayout) rv.post(apply) else apply.run()
    }

    /** Called by ItemTouchHelper during drag — only moves between Active rows. */
    fun onItemMoved(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices) return
        if (items[from] !is DisplayItem.Active || items[to] !is DisplayItem.Active) return
        val item = items.removeAt(from)
        items.add(to, item)
        notifyItemMoved(from, to)
    }

    /** Called when drag is released — extracts active products in visual order. */
    fun onDragFinished() {
        onVisualReorder(items.filterIsInstance<DisplayItem.Active>().map { it.product })
    }

    fun isActiveAt(position: Int): Boolean =
        position in items.indices && items[position] is DisplayItem.Active

    override fun getItemCount() = items.size
    override fun getItemViewType(position: Int) = when (items[position]) {
        is DisplayItem.Header   -> VT_HEADER
        is DisplayItem.Active   -> VT_ACTIVE
        is DisplayItem.Inactive -> VT_INACTIVE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            VT_ACTIVE   -> ActiveVH(ItemProductOrderBinding.inflate(inf, parent, false))
            VT_INACTIVE -> InactiveVH(ItemInactiveProductBinding.inflate(inf, parent, false))
            else        -> HeaderVH(ItemSectionHeaderBinding.inflate(inf, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is DisplayItem.Header   -> (holder as HeaderVH).bind(item.title)
            is DisplayItem.Active   -> (holder as ActiveVH).bind(item.product)
            is DisplayItem.Inactive -> (holder as InactiveVH).bind(item.product)
        }
    }

    // ── Header ViewHolder ─────────────────────────────────────────

    inner class HeaderVH(private val b: ItemSectionHeaderBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(title: String) { b.textSectionHeader.text = title }
    }

    // ── Inactive ViewHolder ───────────────────────────────────────

    inner class InactiveVH(private val b: ItemInactiveProductBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun bind(product: Product) {
            b.textInactiveName.text = product.displayName
            b.textInactiveCode.text = "${product.productType}${product.brandCode}"
            b.root.setOnLongClickListener {
                // Only offer activation in read mode; edit mode is for ordering active products
                if (!editEnabled) { onActivate(product); true } else false
            }
        }
    }

    // ── Active ViewHolder ─────────────────────────────────────────

    inner class ActiveVH(private val b: ItemProductOrderBinding) :
        RecyclerView.ViewHolder(b.root) {

        private var watcher: TextWatcher? = null

        fun bind(product: Product) {
            b.textProductName.text = product.displayName

            val label = getDraftLabel(product.id)
            watcher?.let { b.editPosition.removeTextChangedListener(it) }
            b.editPosition.setText(label.toString())

            b.editPosition.isEnabled              = editEnabled
            b.editPosition.isFocusable            = editEnabled
            b.editPosition.isFocusableInTouchMode = editEnabled
            b.editPosition.alpha                  = if (editEnabled) 1f else 0.65f

            if (editEnabled) {
                b.editPosition.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) b.editPosition.post { b.editPosition.selectAll() }
                    else commitLabel(product)
                }
                b.editPosition.setOnEditorActionListener { v, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_DONE) {
                        commitLabel(product)
                        v.clearFocus()
                        true
                    } else false
                }
            } else {
                b.editPosition.setOnFocusChangeListener(null)
                b.editPosition.setOnEditorActionListener(null)
            }

            val newWatcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {}
            }
            watcher = newWatcher
            b.editPosition.addTextChangedListener(newWatcher)

            // Nudge — the item above/below must also be an Active row
            val pos = bindingAdapterPosition
            val canUp   = editEnabled && pos > 0 && items.getOrNull(pos - 1) is DisplayItem.Active
            val canDown = editEnabled && items.getOrNull(pos + 1) is DisplayItem.Active
            b.btnMoveUp.isEnabled   = canUp
            b.btnMoveDown.isEnabled = canDown
            b.btnMoveUp.alpha   = if (editEnabled) 1f else 0.25f
            b.btnMoveDown.alpha = if (editEnabled) 1f else 0.25f

            b.btnMoveUp.setOnClickListener {
                val p = bindingAdapterPosition
                if (editEnabled && p > 0 && items.getOrNull(p - 1) is DisplayItem.Active) {
                    onItemMoved(p, p - 1)
                    onDragFinished()
                }
            }
            b.btnMoveDown.setOnClickListener {
                val p = bindingAdapterPosition
                if (editEnabled && items.getOrNull(p + 1) is DisplayItem.Active) {
                    onItemMoved(p, p + 1)
                    onDragFinished()
                }
            }

            // Long-press in read mode → deactivate
            b.root.setOnLongClickListener {
                if (!editEnabled) { onDeactivate(product); true } else false
            }
        }

        private fun commitLabel(product: Product) {
            val typed = b.editPosition.text.toString().toIntOrNull() ?: return
            onLabelTyped(product.id, typed)
        }
    }
}
