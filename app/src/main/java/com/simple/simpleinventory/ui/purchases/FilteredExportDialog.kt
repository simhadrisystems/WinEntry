package com.simple.simpleinventory.ui.purchases

import android.app.DatePickerDialog
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.simple.simpleinventory.data.entity.Product
import com.simple.simpleinventory.data.entity.Purchase
import com.simple.simpleinventory.databinding.DialogFilteredExportBinding
import com.simple.simpleinventory.utils.TypeLabels
import java.text.SimpleDateFormat
import java.util.*

/**
 * Dialog for filtered purchase export
 * Allows filtering by:
 * - Date range (from/to)
 * - Product (all/specific)
 * - Product type (W/Y/B/All)
 */
class FilteredExportDialog : DialogFragment() {

    private var _binding: DialogFilteredExportBinding? = null
    private val binding get() = _binding!!
    
    private val viewModel: PurchaseViewModel by viewModels({ requireParentFragment() })
    
    private var startDate: String = ""
    private var endDate: String = ""
    private var selectedProduct: Product? = null
    private var selectedProductType: String = "All"
    
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    private val dbDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    
    private var onExportListener: ((List<Purchase>) -> Unit)? = null
    
    fun setOnExportListener(listener: (List<Purchase>) -> Unit) {
        onExportListener = listener
    }
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogFilteredExportBinding.inflate(layoutInflater)
        
        // Initialize with last 30 days
        val calendar = Calendar.getInstance()
        endDate = dbDateFormat.format(calendar.time)
        binding.etEndDate.setText(dateFormat.format(calendar.time))
        
        calendar.add(Calendar.DAY_OF_MONTH, -30)
        startDate = dbDateFormat.format(calendar.time)
        binding.etStartDate.setText(dateFormat.format(calendar.time))
        
        setupDatePickers()
        setupProductFilter()
        setupTypeFilter()
        setupButtons()
        
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle("Export Purchases")
            .setView(binding.root)
            .create()
    }
    
    private fun setupDatePickers() {
        binding.etStartDate.setOnClickListener {
            showDatePicker(currentDate = startDate, onDateSelected = { date ->
                startDate = date
                binding.etStartDate.setText(dateFormat.format(dbDateFormat.parse(date)!!))
            })
        }
        
        binding.etEndDate.setOnClickListener {
            showDatePicker(currentDate = endDate, onDateSelected = { date ->
                endDate = date
                binding.etEndDate.setText(dateFormat.format(dbDateFormat.parse(date)!!))
            })
        }
    }
    
    private fun showDatePicker(currentDate: String, onDateSelected: (String) -> Unit) {
        val calendar = Calendar.getInstance()
        if (currentDate.isNotEmpty()) {
            calendar.time = dbDateFormat.parse(currentDate)!!
        }
        
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                calendar.set(year, month, day)
                onDateSelected(dbDateFormat.format(calendar.time))
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }
    
    private fun setupProductFilter() {
        viewModel.allProducts.observe(this) { products ->
            val productNames = mutableListOf("All Products")
            productNames.addAll(products.map { "${it.displayName} (${TypeLabels.typeDisplay(it.productType)}${it.brandCode})" })
            
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, productNames)
            binding.acProduct.setAdapter(adapter)
            
            binding.acProduct.setOnItemClickListener { _, _, position, _ ->
                selectedProduct = if (position == 0) null else products[position - 1]
            }
        }
    }
    
    private fun setupTypeFilter() {
        val displayItems = arrayOf("All Types") + TypeLabels.typeDisplayItems
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, displayItems)
        binding.acProductType.setAdapter(adapter)
        binding.acProductType.setText("All Types", false)

        binding.acProductType.setOnItemClickListener { _, _, position, _ ->
            selectedProductType = if (position == 0) "All" else TypeLabels.codeFromDisplay(displayItems[position])
        }
    }
    
    private fun setupButtons() {
        binding.btnExport.setOnClickListener {
            exportFiltered()
        }
        
        binding.btnCancel.setOnClickListener {
            dismiss()
        }
    }
    
    private fun exportFiltered() {
        // Validate dates
        if (startDate.isEmpty() || endDate.isEmpty()) {
            binding.tilStartDate.error = "Select date range"
            return
        }
        
        // Get all purchases in date range
        val allPurchases = viewModel.allPurchases.value ?: emptyList()
        
        var filtered = allPurchases.filter { purchase ->
            purchase.purchaseDate >= startDate && purchase.purchaseDate <= endDate
        }
        
        // Filter by product if selected
        if (selectedProduct != null) {
            filtered = filtered.filter { it.productId == selectedProduct!!.id }
        }
        
        // Filter by product type if selected
        if (selectedProductType != "All") {
            filtered = filtered.filter { 
                it.productCode.startsWith(selectedProductType) 
            }
        }
        
        if (filtered.isEmpty()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("No Data")
                .setMessage("No purchases found matching the selected filters.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        
        onExportListener?.invoke(filtered)
        dismiss()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
    
    companion object {
        fun newInstance() = FilteredExportDialog()
    }
}
