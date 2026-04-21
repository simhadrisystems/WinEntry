package com.simhadri.winentry.ui.products

import android.view.LayoutInflater
import android.view.View  // ← ADD THIS LINE
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.simhadri.winentry.data.entity.Product
import com.simhadri.winentry.databinding.ItemProductBinding

class ProductAdapter(
    private val onEditClick: (Product) -> Unit
) : ListAdapter<Product, ProductAdapter.ProductViewHolder>(ProductDiffCallback()) {
    
    private var allProducts = listOf<Product>()
    private var filteredProducts = listOf<Product>()
    
    /**
     * Submit full list and apply current filter
     */
    fun submitFullList(products: List<Product>) {
        allProducts = products
        filteredProducts = products
        super.submitList(products)
    }
    
    /**
     * Filter products by search query
     * Searches: displayName, productName, brandCode, category, aliases
     */
    fun filter(query: String) {
        filteredProducts = if (query.isEmpty()) {
            allProducts
        } else {
            allProducts.filter { product ->
                product.displayName.contains(query, ignoreCase = true) ||
                product.productName.contains(query, ignoreCase = true) ||
                product.brandCode.contains(query, ignoreCase = true) ||
                product.category.contains(query, ignoreCase = true) ||
                product.aliases.contains(query, ignoreCase = true)  // Search aliases too!
            }
        }
        super.submitList(filteredProducts)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProductViewHolder {
        val binding = ItemProductBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ProductViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: ProductViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    inner class ProductViewHolder(
        private val binding: ItemProductBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(product: Product) {
            binding.apply {
                // Line 1: Serial No and Name
                textProductHeader.text = "${product.serialNo}. ${product.displayName}"

                // Line 2: Type, Brand, Category, Aliases
                val category = if (product.category.isEmpty()) "—" else product.category
                val aliasesText = if (product.aliases.isNotBlank()) {
                    "  |  Aliases: ${product.aliases}"
                } else ""
                textProductInfo.text = "Type: ${product.productType}  |  Brand: ${product.brandCode}  |  Cat: $category$aliasesText"

                // Line 3: Size Codes (no prefixes)
                textQqCode.text = product.qqCode
                textPpCode.text = product.ppCode
                textNnCode.text = product.nnCode
                textDdCode.text = product.ddCode

                // Line 4: Sale Prices (show — if 0)
                textQqPrice.text = if (product.qqSalePrice > 0) "₹${product.qqSalePrice.toInt()}" else "—"
                textPpPrice.text = if (product.ppSalePrice > 0) "₹${product.ppSalePrice.toInt()}" else "—"
                textNnPrice.text = if (product.nnSalePrice > 0) "₹${product.nnSalePrice.toInt()}" else "—"
                textDdPrice.text = if (product.ddSalePrice > 0) "₹${product.ddSalePrice.toInt()}" else "—"

                // Line 5: Status (UPPERCASE)
                textStatus.text = if (product.isActive) "ACTIVE" else "INACTIVE"
                textStatus.setTextColor(
                    if (product.isActive)
                        android.graphics.Color.parseColor("#FF4CAF50")
                    else
                        android.graphics.Color.parseColor("#FFF44336")
                )

                // Dim inactive products
                root.alpha = if (product.isActive) 1.0f else 0.7f

                buttonEdit.setOnClickListener {
                    onEditClick(product)
                }

            }
        }
    }
    
    private class ProductDiffCallback : DiffUtil.ItemCallback<Product>() {
        override fun areItemsTheSame(oldItem: Product, newItem: Product): Boolean {
            return oldItem.id == newItem.id
        }
        
        override fun areContentsTheSame(oldItem: Product, newItem: Product): Boolean {
            return oldItem == newItem
        }
    }
}
