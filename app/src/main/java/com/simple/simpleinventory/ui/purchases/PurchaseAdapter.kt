package com.simple.simpleinventory.ui.purchases

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.simple.simpleinventory.R
import com.simple.simpleinventory.data.entity.Purchase
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

class PurchaseAdapter(
    private val onItemClick: (Purchase) -> Unit,
    private val onDeleteClick: (Purchase) -> Unit
) : ListAdapter<Purchase, PurchaseAdapter.PurchaseViewHolder>(PurchaseDiffCallback()) {

    private val currencyFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
    private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PurchaseViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_purchase, parent, false)
        return PurchaseViewHolder(view)
    }

    override fun onBindViewHolder(holder: PurchaseViewHolder, position: Int) {
        val purchase = getItem(position)
        holder.bind(purchase)
    }

    inner class PurchaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textDate: TextView = itemView.findViewById(R.id.textDate)
        private val textProduct: TextView = itemView.findViewById(R.id.textProduct)
        private val textQuantities: TextView = itemView.findViewById(R.id.textQuantities)
        private val textTotal: TextView = itemView.findViewById(R.id.textTotal)
        private val textSupplier: TextView = itemView.findViewById(R.id.textSupplier)

        fun bind(purchase: Purchase) {
            try {
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(purchase.purchaseDate)
                textDate.text = if (date != null) dateFormat.format(date) else purchase.purchaseDate
            } catch (e: Exception) {
                textDate.text = purchase.purchaseDate
            }

            textProduct.text = purchase.productName

            // Show quantities summary
            val quantities = buildList {
                if (purchase.qqTotalUnits > 0) add("QQ: ${purchase.qqTotalUnits}")
                if (purchase.ppTotalUnits > 0) add("PP: ${purchase.ppTotalUnits}")
                if (purchase.nnTotalUnits > 0) add("NN: ${purchase.nnTotalUnits}")
                if (purchase.ddTotalUnits > 0) add("DD: ${purchase.ddTotalUnits}")
            }.joinToString(" • ")
            
            textQuantities.text = quantities.ifEmpty { "No quantities" }

            textTotal.text = currencyFormat.format(purchase.totalCost)

            textSupplier.text = purchase.supplierName.ifEmpty { "No supplier" }
            textSupplier.visibility = if (purchase.supplierName.isEmpty()) View.GONE else View.VISIBLE

            itemView.setOnClickListener {
                onItemClick(purchase)
            }

            itemView.setOnLongClickListener {
                onDeleteClick(purchase)
                true
            }
        }
    }

    class PurchaseDiffCallback : DiffUtil.ItemCallback<Purchase>() {
        override fun areItemsTheSame(oldItem: Purchase, newItem: Purchase): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Purchase, newItem: Purchase): Boolean {
            return oldItem == newItem
        }
    }
}
