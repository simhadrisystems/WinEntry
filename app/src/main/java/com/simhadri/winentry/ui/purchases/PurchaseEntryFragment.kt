package com.simhadri.winentry.ui.purchases

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.simhadri.winentry.data.entity.Purchase
import com.simhadri.winentry.R
import com.simhadri.winentry.data.entity.getAllBrandCodes
import com.simhadri.winentry.databinding.FragmentPurchaseEntryBinding
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

class PurchaseEntryFragment : Fragment() {

    private var _binding: FragmentPurchaseEntryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PurchaseViewModel by viewModels()
    private val currencyFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN"))
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val displayDateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    
    // Store text watchers so we can remove them
    private var isTextWatchersActive = false
    
    // Edit mode tracking
    private var editingPurchaseId: Long? = null
    private var isEditMode = false
    private var isInsertMode = false      // true when launched via "Insert Here"
    private var lockedInvoice: String = "" // invoice locked in Insert Here mode
    private var lockedDate: String = ""    // date locked in Insert Here mode

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPurchaseEntryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Setup UI components first
        setupToolbar()
        setupDatePicker()
        setupProductSelector()
        setupSizeInputs()
        setupSelectAllOnFocus()
        setupButtons()
        observeViewModel()

        // Check if we're in edit mode - get purchaseId from arguments manually
        arguments?.let {
            editingPurchaseId = it.getLong("purchaseId", 0L)
            isEditMode = editingPurchaseId != null && editingPurchaseId != 0L
            
            // Check for pre-filled invoice and date (from "Insert Here")
            if (!isEditMode) {
                val prefilledInvoice = it.getString("invoiceNumber")
                val prefilledDate = it.getString("purchaseDate")
                isInsertMode = !prefilledDate.isNullOrEmpty() || !prefilledInvoice.isNullOrEmpty()

                // CRITICAL: store locked values NOW, before any setText() or updateMetadata() calls
                // so that the text watcher on editInvoiceNumber always sees them correctly
                if (isInsertMode) {
                    lockedInvoice = prefilledInvoice ?: ""
                    lockedDate    = prefilledDate ?: ""
                }

                if (!prefilledInvoice.isNullOrEmpty()) {
                    binding.editInvoiceNumber.setText(prefilledInvoice)
                }

                if (!prefilledDate.isNullOrEmpty()) {
                    try {
                        val date = dateFormat.parse(prefilledDate)
                        if (date != null) {
                            binding.textPurchaseDate.setText(displayDateFormat.format(date))
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("PurchaseEntry", "Error parsing prefilled date", e)
                    }
                }

                // Lock fields and sync ViewModel with locked values
                if (isInsertMode) {
                    binding.toolbar.title = "Insert Purchase"
                    binding.textPurchaseDate.isEnabled = false
                    binding.textPurchaseDate.alpha = 0.6f
                    binding.editInvoiceNumber.isEnabled = false
                    binding.editInvoiceNumber.alpha = 0.6f
                    // Force ViewModel to the locked values — overrides anything setupDatePicker set
                    viewModel.updateMetadata(
                        date     = lockedDate,
                        supplier = "",
                        invoice  = lockedInvoice,
                        notes    = ""
                    )
                }
            }
        }
        
        // Load purchase for editing if in edit mode
        if (isEditMode) {
            loadPurchaseForEditing(editingPurchaseId!!)
        }
    }
    
    private fun loadPurchaseForEditing(purchaseId: Long) {
        android.util.Log.d("PurchaseEntry", "loadPurchaseForEditing called with ID: $purchaseId")
        
        lifecycleScope.launch {
            val purchase = viewModel.getPurchaseById(purchaseId)
            if (purchase == null) {
                android.util.Log.e("PurchaseEntry", "Purchase not found for ID: $purchaseId")
                Toast.makeText(requireContext(), "Purchase not found", Toast.LENGTH_SHORT).show()
                findNavController().navigateUp()
                return@launch
            }
            
            android.util.Log.d("PurchaseEntry", "Purchase loaded for EDIT: ID=${purchase.id}, Product=${purchase.productName}")
            
            // CRITICAL: In edit mode, we load ALL data from the Purchase record itself
            // We do NOT depend on the Product master table
            // This ensures historical purchases remain editable even if product is deleted
            
            // Load purchase data into ViewModel directly (without product selection)
            viewModel.loadPurchaseForEditDirectly(purchase)
            
            // Update toolbar and button
            binding.toolbar.title = "Edit Purchase"
            binding.btnSave.text = "Update Purchase"
            
            // Populate UI from Purchase data (not Product master)
            binding.root.post {
                populateUIFromPurchaseData(purchase)
            }
        }
    }
    
    private fun populateUIFromPurchaseData(purchase: Purchase) {
        android.util.Log.d("PurchaseEntry", "Populating UI from PURCHASE DATA (not product master)")
        
        // Disable text watchers during population
        isTextWatchersActive = false
        
        // Set date
        try {
            val date = dateFormat.parse(purchase.purchaseDate)
            if (date != null) {
                binding.textPurchaseDate.setText(displayDateFormat.format(date))
            }
        } catch (e: Exception) {
            binding.textPurchaseDate.setText(purchase.purchaseDate)
        }
        
        // Set product name (read-only in edit mode - shows historical product name)
        binding.autoCompleteProduct.setText(
            "${purchase.productName} (${purchase.productCode})",
            false
        )
        // Disable product selector in edit mode - can't change product of existing purchase
        binding.autoCompleteProduct.isEnabled = false
        binding.layoutProduct.isEnabled = false
        
        // Populate QQ from Purchase data (uses stored snapshot)
        if (purchase.qqTotalUnits > 0 || purchase.qqUnitsPerBox > 0) {
            binding.editQQBoxes.setText(purchase.qqBoxes.toString())
            binding.editQQLoose.setText(purchase.qqLoose.toString())
            binding.editQQUnitPrice.setText(purchase.qqUnitPrice.toString())
            binding.editQQUnitsPerBox.setText(purchase.qqUnitsPerBox.toString())
            if (purchase.qqUnitsPerBox > 0) {
                binding.textQQUnitsPerBox.text = "${purchase.qqUnitsPerBox} units/box"
                binding.textQQUnitsPerBox.visibility = View.VISIBLE
            }
            // Enable fields and show edit controls
            binding.cardQQ.alpha = 1.0f
            binding.editQQBoxes.isEnabled = true
            binding.editQQLoose.isEnabled = true
        }
        
        // Populate PP from Purchase data
        if (purchase.ppTotalUnits > 0 || purchase.ppUnitsPerBox > 0) {
            binding.editPPBoxes.setText(purchase.ppBoxes.toString())
            binding.editPPLoose.setText(purchase.ppLoose.toString())
            binding.editPPUnitPrice.setText(purchase.ppUnitPrice.toString())
            binding.editPPUnitsPerBox.setText(purchase.ppUnitsPerBox.toString())
            if (purchase.ppUnitsPerBox > 0) {
                binding.textPPUnitsPerBox.text = "${purchase.ppUnitsPerBox} units/box"
                binding.textPPUnitsPerBox.visibility = View.VISIBLE
            }
            binding.cardPP.alpha = 1.0f
            binding.editPPBoxes.isEnabled = true
            binding.editPPLoose.isEnabled = true
        }
        
        // Populate NN from Purchase data
        if (purchase.nnTotalUnits > 0 || purchase.nnUnitsPerBox > 0) {
            binding.editNNBoxes.setText(purchase.nnBoxes.toString())
            binding.editNNLoose.setText(purchase.nnLoose.toString())
            binding.editNNUnitPrice.setText(purchase.nnUnitPrice.toString())
            binding.editNNUnitsPerBox.setText(purchase.nnUnitsPerBox.toString())
            if (purchase.nnUnitsPerBox > 0) {
                binding.textNNUnitsPerBox.text = "${purchase.nnUnitsPerBox} units/box"
                binding.textNNUnitsPerBox.visibility = View.VISIBLE
            }
            binding.cardNN.alpha = 1.0f
            binding.editNNBoxes.isEnabled = true
            binding.editNNLoose.isEnabled = true
        }
        
        // Populate DD from Purchase data
        if (purchase.ddTotalUnits > 0 || purchase.ddUnitsPerBox > 0) {
            binding.editDDBoxes.setText(purchase.ddBoxes.toString())
            binding.editDDLoose.setText(purchase.ddLoose.toString())
            binding.editDDUnitPrice.setText(purchase.ddUnitPrice.toString())
            binding.editDDUnitsPerBox.setText(purchase.ddUnitsPerBox.toString())
            if (purchase.ddUnitsPerBox > 0) {
                binding.textDDUnitsPerBox.text = "${purchase.ddUnitsPerBox} units/box"
                binding.textDDUnitsPerBox.visibility = View.VISIBLE
            }
            binding.cardDD.alpha = 1.0f
            binding.editDDBoxes.isEnabled = true
            binding.editDDLoose.isEnabled = true
        }
        
        // Populate metadata
        binding.editSupplierName.setText(purchase.supplierName)
        binding.editInvoiceNumber.setText(purchase.invoiceNumber)
        binding.editNotes.setText(purchase.notes)
        
        // Re-enable text watchers - this will trigger calculations
        isTextWatchersActive = true
        
        // Manually trigger calculations to ensure totals are calculated
        binding.root.post {
            calculateQQ()
            calculatePP()
            calculateNN()
            calculateDD()
        }
        
        android.util.Log.d("PurchaseEntry", "UI populated from purchase data - Product can be deleted, edit still works!")
    }
    
    private fun populateUIForEdit(purchase: Purchase, @Suppress("UNUSED_PARAMETER") product: com.simhadri.winentry.data.entity.Product) {
        // This method is now OBSOLETE - kept for backwards compatibility
        // New edit mode uses populateUIFromPurchaseData instead
        populateUIFromPurchaseData(purchase)
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            handleBackPress()
        }
    }

    private fun setupDatePicker() {
        val today = Calendar.getInstance()
        val todayFormatted = dateFormat.format(today.time)
        
        // Set initial date to today
        binding.textPurchaseDate.setText(displayDateFormat.format(today.time))
        
        // CRITICAL: Set initial date in ViewModel
        android.util.Log.d("PurchaseEntry", "Setting initial date: $todayFormatted")
        // Only set the date — leave invoice/supplier/notes untouched at this stage
        viewModel.updateMetadata(
            date     = todayFormatted,
            supplier = viewModel.currentPurchase.value?.supplierName ?: "",
            invoice  = viewModel.currentPurchase.value?.invoiceNumber ?: "",
            notes    = viewModel.currentPurchase.value?.notes ?: ""
        )

        binding.textPurchaseDate.setOnClickListener {
            // Get current date from ViewModel to show in picker
            val currentCalendar = Calendar.getInstance()
            val currentPurchase = viewModel.currentPurchase.value
            if (currentPurchase != null && currentPurchase.purchaseDate.isNotEmpty()) {
                try {
                    val date = dateFormat.parse(currentPurchase.purchaseDate)
                    if (date != null) {
                        currentCalendar.time = date
                    }
                } catch (e: Exception) {
                    // Use today if parsing fails
                }
            }
            
            DatePickerDialog(
                requireContext(),
                { _, year, month, day ->
                    val selectedCalendar = Calendar.getInstance()
                    selectedCalendar.set(year, month, day)
                    val selectedDate = dateFormat.format(selectedCalendar.time)
                    
                    // Update UI
                    binding.textPurchaseDate.setText(displayDateFormat.format(selectedCalendar.time))
                    
                    // Update ViewModel immediately
                    android.util.Log.d("PurchaseEntry", "Date selected: $selectedDate")
                    viewModel.updateMetadata(
                        date     = selectedDate,
                        supplier = binding.editSupplierName.text.toString(),
                        invoice  = if (isInsertMode) lockedInvoice else binding.editInvoiceNumber.text.toString(),
                        notes    = binding.editNotes.text.toString()
                    )
                },
                currentCalendar.get(Calendar.YEAR),
                currentCalendar.get(Calendar.MONTH),
                currentCalendar.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
    }

    private fun setupProductSelector() {
        viewModel.allProducts.observe(viewLifecycleOwner) { products ->
            val productStrings = products.map { "${it.displayName} (${it.productType}${it.brandCode})" }
            val productAdapter = buildProductAdapter(products, productStrings)

            binding.autoCompleteProduct.setAdapter(productAdapter)
            binding.autoCompleteProduct.threshold = 1

            binding.layoutProduct.setStartIconOnClickListener {
                productAdapter.setNotifyOnChange(false)
                productAdapter.clear()
                productAdapter.addAll(productStrings)
                productAdapter.notifyDataSetChanged()
                binding.autoCompleteProduct.requestFocus()
                binding.autoCompleteProduct.post {
                    binding.autoCompleteProduct.showDropDown()
                }
            }

            binding.autoCompleteProduct.setOnItemClickListener { parent, _, position, _ ->
                // Get the actual text that was clicked
                val selectedText = parent.getItemAtPosition(position) as String
                
                // Find the matching product by display text
                val selectedProduct = products.find {
                    "${it.displayName} (${it.productType}${it.brandCode})" == selectedText
                }
                
                if (selectedProduct != null) {
                    // Update ViewModel first
                    viewModel.selectProduct(selectedProduct)
                    
                    // Wait for ViewModel to update, then update UI
                    binding.root.post {
                        // Verify ViewModel was updated correctly
                        val currentCalc = viewModel.currentPurchase.value
                        android.util.Log.d("PurchaseEntry", "Selected: ${selectedProduct.displayName}, QQ units/box: ${currentCalc?.qqUnitsPerBox}")
                        
                        updateProductInfo(selectedProduct)
                    }
                }
            }
        }
    }

    private fun buildProductAdapter(
        products: List<com.simhadri.winentry.data.entity.Product>,
        productStrings: List<String>
    ): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(requireContext(), R.layout.dropdown_item, productStrings.toMutableList()) {
            private val cachedFilter: Filter = object : Filter() {
                override fun performFiltering(constraint: CharSequence?): FilterResults {
                    val results = FilterResults()
                    if (constraint.isNullOrEmpty()) {
                        results.values = productStrings
                        results.count = productStrings.size
                    } else {
                        val query = constraint.toString().lowercase()
                        val filtered = products.filter { p ->
                            p.displayName.lowercase().contains(query) ||
                            p.brandCode.lowercase().contains(query) ||
                            "${p.productType}${p.brandCode}".lowercase().contains(query) ||
                            p.getAllBrandCodes().any { code ->
                                code.lowercase().contains(query) ||
                                "${p.productType}$code".lowercase().contains(query)
                            }
                        }.map { p -> "${p.displayName} (${p.productType}${p.brandCode})" }
                        results.values = filtered
                        results.count = filtered.size
                    }
                    return results
                }

                @Suppress("UNCHECKED_CAST")
                override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                    setNotifyOnChange(false)
                    clear()
                    if (results != null && results.count > 0) addAll(results.values as List<String>)
                    notifyDataSetChanged()
                }
            }

            override fun getFilter(): Filter = cachedFilter
        }
    }

    private fun updateProductInfo(product: com.simhadri.winentry.data.entity.Product) {
        // Disable text watcher callbacks temporarily
        isTextWatchersActive = false
        
        // Update units per box - only show if value is > 0
        if (product.qqUnitsPerBox > 0) {
            binding.textQQUnitsPerBox.text = "${product.qqUnitsPerBox} units/box"
            binding.textQQUnitsPerBox.visibility = View.VISIBLE
        } else {
            binding.textQQUnitsPerBox.visibility = View.GONE
        }
        
        if (product.ppUnitsPerBox > 0) {
            binding.textPPUnitsPerBox.text = "${product.ppUnitsPerBox} units/box"
            binding.textPPUnitsPerBox.visibility = View.VISIBLE
        } else {
            binding.textPPUnitsPerBox.visibility = View.GONE
        }
        
        if (product.nnUnitsPerBox > 0) {
            binding.textNNUnitsPerBox.text = "${product.nnUnitsPerBox} units/box"
            binding.textNNUnitsPerBox.visibility = View.VISIBLE
        } else {
            binding.textNNUnitsPerBox.visibility = View.GONE
        }
        
        if (product.ddUnitsPerBox > 0) {
            binding.textDDUnitsPerBox.text = "${product.ddUnitsPerBox} units/box"
            binding.textDDUnitsPerBox.visibility = View.VISIBLE
        } else {
            binding.textDDUnitsPerBox.visibility = View.GONE
        }

        // Update the editable units per box fields (actual values)
        binding.editQQUnitsPerBox.setText(product.qqUnitsPerBox.toString())
        binding.editPPUnitsPerBox.setText(product.ppUnitsPerBox.toString())
        binding.editNNUnitsPerBox.setText(product.nnUnitsPerBox.toString())
        binding.editDDUnitsPerBox.setText(product.ddUnitsPerBox.toString())

        // Set unit prices from product
        binding.editQQUnitPrice.setText(product.qqPurchasePrice.toString())
        binding.editPPUnitPrice.setText(product.ppPurchasePrice.toString())
        binding.editNNUnitPrice.setText(product.nnPurchasePrice.toString())
        binding.editDDUnitPrice.setText(product.ddPurchasePrice.toString())
        
        // Disable entire size card if price is 0
        val qqHasPrice = product.qqPurchasePrice > 0
        binding.cardQQ.alpha = if (qqHasPrice) 1.0f else 0.4f
        binding.editQQBoxes.isEnabled = qqHasPrice || binding.switchQQEdit.isChecked
        binding.editQQLoose.isEnabled = qqHasPrice || binding.switchQQEdit.isChecked
        
        val ppHasPrice = product.ppPurchasePrice > 0
        binding.cardPP.alpha = if (ppHasPrice) 1.0f else 0.4f
        binding.editPPBoxes.isEnabled = ppHasPrice || binding.switchPPEdit.isChecked
        binding.editPPLoose.isEnabled = ppHasPrice || binding.switchPPEdit.isChecked
        
        val nnHasPrice = product.nnPurchasePrice > 0
        binding.cardNN.alpha = if (nnHasPrice) 1.0f else 0.4f
        binding.editNNBoxes.isEnabled = nnHasPrice || binding.switchNNEdit.isChecked
        binding.editNNLoose.isEnabled = nnHasPrice || binding.switchNNEdit.isChecked
        
        val ddHasPrice = product.ddPurchasePrice > 0
        binding.cardDD.alpha = if (ddHasPrice) 1.0f else 0.4f
        binding.editDDBoxes.isEnabled = ddHasPrice || binding.switchDDEdit.isChecked
        binding.editDDLoose.isEnabled = ddHasPrice || binding.switchDDEdit.isChecked
        
        // Re-enable text watcher callbacks
        isTextWatchersActive = true

        wireImeChain()
        android.util.Log.d("PurchaseEntry", "Product loaded: ${product.displayName}")
        android.util.Log.d("PurchaseEntry", "Units/box: QQ=${product.qqUnitsPerBox}, PP=${product.ppUnitsPerBox}, NN=${product.nnUnitsPerBox}, DD=${product.ddUnitsPerBox}")
    }

    private fun setupSizeInputs() {
        // QQ Size - Initialize disabled state and set switch handler
        binding.editQQUnitPrice.isEnabled = false
        binding.editQQUnitPrice.alpha = 0.6f
        
        binding.switchQQEdit.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutQQUnitsPerBox.visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.editQQUnitPrice.isEnabled = isChecked
            binding.editQQUnitPrice.alpha = if (isChecked) 1.0f else 0.6f
            
            // Enable boxes/loose fields when edit is ON (even if price is 0)
            binding.editQQBoxes.isEnabled = isChecked || (viewModel.selectedProduct.value?.qqPurchasePrice ?: 0.0) > 0
            binding.editQQLoose.isEnabled = isChecked || (viewModel.selectedProduct.value?.qqPurchasePrice ?: 0.0) > 0
            binding.cardQQ.alpha = if (isChecked || (viewModel.selectedProduct.value?.qqPurchasePrice ?: 0.0) > 0) 1.0f else 0.4f
            
            // Show units/box badge when edit is ON
            binding.textQQUnitsPerBox.visibility = if (isChecked || (viewModel.selectedProduct.value?.qqPurchasePrice ?: 0.0) > 0) View.VISIBLE else View.GONE
            wireImeChain()
            android.util.Log.d("PurchaseEntry", "QQ Edit switch: $isChecked, Price enabled: ${binding.editQQUnitPrice.isEnabled}")
        }
        
        binding.editQQUnitsPerBox.addTextChangedListener {
            if (binding.switchQQEdit.isChecked) {
                val newValue = it.toString().toIntOrNull() ?: 12
                binding.textQQUnitsPerBox.text = "$newValue units/box"
                calculateQQ()
            }
        }
        
        binding.editQQBoxes.addTextChangedListener { 
            calculateQQ()
        }
        binding.editQQLoose.addTextChangedListener { 
            calculateQQ()
        }
        binding.editQQUnitPrice.addTextChangedListener { 
            calculateQQ()
        }

        // PP Size - Initialize disabled state
        binding.editPPUnitPrice.isEnabled = false
        binding.editPPUnitPrice.alpha = 0.6f
        
        binding.switchPPEdit.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutPPUnitsPerBox.visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.editPPUnitPrice.isEnabled = isChecked
            binding.editPPUnitPrice.alpha = if (isChecked) 1.0f else 0.6f
            
            binding.editPPBoxes.isEnabled = isChecked || (viewModel.selectedProduct.value?.ppPurchasePrice ?: 0.0) > 0
            binding.editPPLoose.isEnabled = isChecked || (viewModel.selectedProduct.value?.ppPurchasePrice ?: 0.0) > 0
            binding.cardPP.alpha = if (isChecked || (viewModel.selectedProduct.value?.ppPurchasePrice ?: 0.0) > 0) 1.0f else 0.4f
            binding.textPPUnitsPerBox.visibility = if (isChecked || (viewModel.selectedProduct.value?.ppPurchasePrice ?: 0.0) > 0) View.VISIBLE else View.GONE
            wireImeChain()
        }

        binding.editPPUnitsPerBox.addTextChangedListener {
            if (binding.switchPPEdit.isChecked) {
                val newValue = it.toString().toIntOrNull() ?: 24
                binding.textPPUnitsPerBox.text = "$newValue units/box"
                calculatePP()
            }
        }
        
        binding.editPPBoxes.addTextChangedListener { 
            calculatePP()
        }
        binding.editPPLoose.addTextChangedListener { 
            calculatePP()
        }
        binding.editPPUnitPrice.addTextChangedListener { 
            calculatePP()
        }

        // NN Size - Initialize disabled state
        binding.editNNUnitPrice.isEnabled = false
        binding.editNNUnitPrice.alpha = 0.6f
        
        binding.switchNNEdit.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutNNUnitsPerBox.visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.editNNUnitPrice.isEnabled = isChecked
            binding.editNNUnitPrice.alpha = if (isChecked) 1.0f else 0.6f
            
            binding.editNNBoxes.isEnabled = isChecked || (viewModel.selectedProduct.value?.nnPurchasePrice ?: 0.0) > 0
            binding.editNNLoose.isEnabled = isChecked || (viewModel.selectedProduct.value?.nnPurchasePrice ?: 0.0) > 0
            binding.cardNN.alpha = if (isChecked || (viewModel.selectedProduct.value?.nnPurchasePrice ?: 0.0) > 0) 1.0f else 0.4f
            binding.textNNUnitsPerBox.visibility = if (isChecked || (viewModel.selectedProduct.value?.nnPurchasePrice ?: 0.0) > 0) View.VISIBLE else View.GONE
            wireImeChain()
        }

        binding.editNNUnitsPerBox.addTextChangedListener {
            if (binding.switchNNEdit.isChecked) {
                val newValue = it.toString().toIntOrNull() ?: 48
                binding.textNNUnitsPerBox.text = "$newValue units/box"
                calculateNN()
            }
        }
        
        binding.editNNBoxes.addTextChangedListener { 
            calculateNN()
        }
        binding.editNNLoose.addTextChangedListener { 
            calculateNN()
        }
        binding.editNNUnitPrice.addTextChangedListener { 
            calculateNN()
        }

        // DD Size - Initialize disabled state
        binding.editDDUnitPrice.isEnabled = false
        binding.editDDUnitPrice.alpha = 0.6f
        
        binding.switchDDEdit.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutDDUnitsPerBox.visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.editDDUnitPrice.isEnabled = isChecked
            binding.editDDUnitPrice.alpha = if (isChecked) 1.0f else 0.6f
            
            binding.editDDBoxes.isEnabled = isChecked || (viewModel.selectedProduct.value?.ddPurchasePrice ?: 0.0) > 0
            binding.editDDLoose.isEnabled = isChecked || (viewModel.selectedProduct.value?.ddPurchasePrice ?: 0.0) > 0
            binding.cardDD.alpha = if (isChecked || (viewModel.selectedProduct.value?.ddPurchasePrice ?: 0.0) > 0) 1.0f else 0.4f
            binding.textDDUnitsPerBox.visibility = if (isChecked || (viewModel.selectedProduct.value?.ddPurchasePrice ?: 0.0) > 0) View.VISIBLE else View.GONE
            wireImeChain()
        }

        binding.editDDUnitsPerBox.addTextChangedListener {
            if (binding.switchDDEdit.isChecked) {
                val newValue = it.toString().toIntOrNull() ?: 96
                binding.textDDUnitsPerBox.text = "$newValue units/box"
                calculateDD()
            }
        }
        
        binding.editDDBoxes.addTextChangedListener { 
            calculateDD()
        }
        binding.editDDLoose.addTextChangedListener { 
            calculateDD()
        }
        binding.editDDUnitPrice.addTextChangedListener { 
            calculateDD()
        }

        // Metadata
        binding.editSupplierName.addTextChangedListener { updateMetadata() }
        binding.editInvoiceNumber.addTextChangedListener { updateMetadata() }
        binding.editNotes.addTextChangedListener { updateMetadata() }
        
        // Enable text watcher callbacks
        isTextWatchersActive = true
    }

    private fun calculateQQ() {
        if (!isTextWatchersActive) return  // Skip if updating product info
        
        val boxes = binding.editQQBoxes.text.toString().toIntOrNull() ?: 0
        val loose = binding.editQQLoose.text.toString().toIntOrNull() ?: 0
        val unitPrice = binding.editQQUnitPrice.text.toString().toDoubleOrNull() ?: 0.0
        
        // Get units per box from edit field (updated by switch or product selection)
        val unitsPerBox = binding.editQQUnitsPerBox.text.toString().toIntOrNull() ?: 12
        
        // VALIDATION: Loose units must be less than units per box
        if (loose >= unitsPerBox && unitsPerBox > 0) {
            binding.editQQLoose.error = "Max ${unitsPerBox - 1}"
            
            // Calculate conversion suggestion
            val additionalBoxes = loose / unitsPerBox
            val remainingLoose = loose % unitsPerBox
            Toast.makeText(
                requireContext(),
                "QQ: $loose loose = $additionalBoxes box(es) + $remainingLoose loose",
                Toast.LENGTH_LONG
            ).show()
            return
        } else {
            binding.editQQLoose.error = null
        }
        
        viewModel.updateQQ(boxes, loose, unitPrice, unitsPerBox)
    }

    private fun calculatePP() {
        if (!isTextWatchersActive) return  // Skip if updating product info
        
        val boxes = binding.editPPBoxes.text.toString().toIntOrNull() ?: 0
        val loose = binding.editPPLoose.text.toString().toIntOrNull() ?: 0
        val unitPrice = binding.editPPUnitPrice.text.toString().toDoubleOrNull() ?: 0.0
        
        val unitsPerBox = binding.editPPUnitsPerBox.text.toString().toIntOrNull() ?: 24
        
        // VALIDATION: Loose units must be less than units per box
        if (loose >= unitsPerBox && unitsPerBox > 0) {
            binding.editPPLoose.error = "Max ${unitsPerBox - 1}"
            
            val additionalBoxes = loose / unitsPerBox
            val remainingLoose = loose % unitsPerBox
            Toast.makeText(
                requireContext(),
                "PP: $loose loose = $additionalBoxes box(es) + $remainingLoose loose",
                Toast.LENGTH_LONG
            ).show()
            return
        } else {
            binding.editPPLoose.error = null
        }
        
        viewModel.updatePP(boxes, loose, unitPrice, unitsPerBox)
    }

    private fun calculateNN() {
        if (!isTextWatchersActive) return  // Skip if updating product info
        
        val boxes = binding.editNNBoxes.text.toString().toIntOrNull() ?: 0
        val loose = binding.editNNLoose.text.toString().toIntOrNull() ?: 0
        val unitPrice = binding.editNNUnitPrice.text.toString().toDoubleOrNull() ?: 0.0
        
        val unitsPerBox = binding.editNNUnitsPerBox.text.toString().toIntOrNull() ?: 48
        
        // VALIDATION: Loose units must be less than units per box
        if (loose >= unitsPerBox && unitsPerBox > 0) {
            binding.editNNLoose.error = "Max ${unitsPerBox - 1}"
            
            val additionalBoxes = loose / unitsPerBox
            val remainingLoose = loose % unitsPerBox
            Toast.makeText(
                requireContext(),
                "NN: $loose loose = $additionalBoxes box(es) + $remainingLoose loose",
                Toast.LENGTH_LONG
            ).show()
            return
        } else {
            binding.editNNLoose.error = null
        }
        
        viewModel.updateNN(boxes, loose, unitPrice, unitsPerBox)
    }

    private fun calculateDD() {
        if (!isTextWatchersActive) return  // Skip if updating product info
        
        val boxes = binding.editDDBoxes.text.toString().toIntOrNull() ?: 0
        val loose = binding.editDDLoose.text.toString().toIntOrNull() ?: 0
        val unitPrice = binding.editDDUnitPrice.text.toString().toDoubleOrNull() ?: 0.0
        
        val unitsPerBox = binding.editDDUnitsPerBox.text.toString().toIntOrNull() ?: 96
        
        // VALIDATION: Loose units must be less than units per box
        if (loose >= unitsPerBox && unitsPerBox > 0) {
            binding.editDDLoose.error = "Max ${unitsPerBox - 1}"
            
            val additionalBoxes = loose / unitsPerBox
            val remainingLoose = loose % unitsPerBox
            Toast.makeText(
                requireContext(),
                "DD: $loose loose = $additionalBoxes box(es) + $remainingLoose loose",
                Toast.LENGTH_LONG
            ).show()
            return
        } else {
            binding.editDDLoose.error = null
        }
        
        viewModel.updateDD(boxes, loose, unitPrice, unitsPerBox)
    }

    private fun updateMetadata() {
        // In Insert Here mode, date and invoice are locked — never let text watchers overwrite them
        if (isInsertMode) {
            viewModel.updateMetadata(
                date     = lockedDate,
                supplier = binding.editSupplierName.text.toString(),
                invoice  = lockedInvoice,
                notes    = binding.editNotes.text.toString()
            )
            return
        }

        val calendar = Calendar.getInstance()
        val dateText = binding.textPurchaseDate.text.toString()
        try {
            val displayDate = displayDateFormat.parse(dateText)
            if (displayDate != null) {
                calendar.time = displayDate
            }
        } catch (e: Exception) {
            // Use current date if parsing fails
        }

        viewModel.updateMetadata(
            date = dateFormat.format(calendar.time),
            supplier = binding.editSupplierName.text.toString(),
            invoice = binding.editInvoiceNumber.text.toString(),
            notes = binding.editNotes.text.toString()
        )
    }

    private fun setupSelectAllOnFocus() {
        listOf(
            binding.editQQBoxes, binding.editQQLoose, binding.editQQUnitPrice, binding.editQQUnitsPerBox,
            binding.editPPBoxes, binding.editPPLoose, binding.editPPUnitPrice, binding.editPPUnitsPerBox,
            binding.editNNBoxes, binding.editNNLoose, binding.editNNUnitPrice, binding.editNNUnitsPerBox,
            binding.editDDBoxes, binding.editDDLoose, binding.editDDUnitPrice, binding.editDDUnitsPerBox
        ).forEach { it.setSelectAllOnFocus(true) }
    }

    /**
     * Wires IME "Next" traversal across the 8 quantity fields (Boxes/Loose for each size).
     * Mirrors the DailyEntryAdapter wireImeChain pattern:
     *  - Build a flat list of currently-enabled fields in left-to-right, top-to-bottom order.
     *  - Each field's Next jumps to the next enabled field; disabled fields are skipped entirely.
     *  - Last enabled field jumps to Supplier Name.
     * Must be called whenever the enabled state of any quantity field changes
     * (product selection, switch toggles).
     */
    fun wireImeChain() {
        val candidates = listOf(
            binding.editQQBoxes, binding.editQQLoose,
            binding.editPPBoxes, binding.editPPLoose,
            binding.editNNBoxes, binding.editNNLoose,
            binding.editDDBoxes, binding.editDDLoose
        )
        val enabled = candidates.filter { it.isEnabled }
        if (enabled.isEmpty()) return

        for (i in enabled.indices) {
            val current = enabled[i]
            current.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
            if (i < enabled.lastIndex) {
                val next = enabled[i + 1]
                current.setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) {
                        next.requestFocus()
                        next.post { next.selectAll() }
                        true
                    } else false
                }
            } else {
                current.setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT) {
                        binding.editSupplierName.requestFocus()
                        true
                    } else false
                }
            }
        }
    }

    private fun setupButtons() {
        binding.btnSave.setOnClickListener {
            if (isEditMode && editingPurchaseId != null) {
                viewModel.updatePurchase(editingPurchaseId!!)
            } else {
                // In Insert Here mode, pass locked values directly — bypasses any watcher wipe
                if (isInsertMode && lockedInvoice.isNotEmpty()) {
                    viewModel.savePurchase(
                        forcedInvoice = lockedInvoice,
                        forcedDate    = lockedDate
                    )
                } else {
                    viewModel.savePurchase()
                }
            }
        }

        binding.btnCancel.setOnClickListener {
            handleBackPress()
        }
    }

    private fun observeViewModel() {
        // Observe current purchase calculations
        viewModel.currentPurchase.observe(viewLifecycleOwner) { calc ->
            // Update units per box displays - hide if 0
            if (calc.qqUnitsPerBox > 0) {
                binding.textQQUnitsPerBox.text = "${calc.qqUnitsPerBox} units/box"
                binding.textQQUnitsPerBox.visibility = View.VISIBLE
            } else {
                binding.textQQUnitsPerBox.visibility = View.GONE
            }
            
            if (calc.ppUnitsPerBox > 0) {
                binding.textPPUnitsPerBox.text = "${calc.ppUnitsPerBox} units/box"
                binding.textPPUnitsPerBox.visibility = View.VISIBLE
            } else {
                binding.textPPUnitsPerBox.visibility = View.GONE
            }
            
            if (calc.nnUnitsPerBox > 0) {
                binding.textNNUnitsPerBox.text = "${calc.nnUnitsPerBox} units/box"
                binding.textNNUnitsPerBox.visibility = View.VISIBLE
            } else {
                binding.textNNUnitsPerBox.visibility = View.GONE
            }
            
            if (calc.ddUnitsPerBox > 0) {
                binding.textDDUnitsPerBox.text = "${calc.ddUnitsPerBox} units/box"
                binding.textDDUnitsPerBox.visibility = View.VISIBLE
            } else {
                binding.textDDUnitsPerBox.visibility = View.GONE
            }
            
            // Update editable fields to keep them in sync (if not being manually edited)
            if (!binding.switchQQEdit.isChecked) {
                binding.editQQUnitsPerBox.setText(calc.qqUnitsPerBox.toString())
            }
            if (!binding.switchPPEdit.isChecked) {
                binding.editPPUnitsPerBox.setText(calc.ppUnitsPerBox.toString())
            }
            if (!binding.switchNNEdit.isChecked) {
                binding.editNNUnitsPerBox.setText(calc.nnUnitsPerBox.toString())
            }
            if (!binding.switchDDEdit.isChecked) {
                binding.editDDUnitsPerBox.setText(calc.ddUnitsPerBox.toString())
            }
            
            // Update totals and costs
            binding.textQQTotal.text = calc.qqTotalUnits.toString()
            binding.textQQCost.text = currencyFormat.format(calc.qqTotalCost)

            binding.textPPTotal.text = calc.ppTotalUnits.toString()
            binding.textPPCost.text = currencyFormat.format(calc.ppTotalCost)

            binding.textNNTotal.text = calc.nnTotalUnits.toString()
            binding.textNNCost.text = currencyFormat.format(calc.nnTotalCost)

            binding.textDDTotal.text = calc.ddTotalUnits.toString()
            binding.textDDCost.text = currencyFormat.format(calc.ddTotalCost)

            binding.textGrandTotal.text = currencyFormat.format(calc.grandTotal)
        }

        // Observe save status
        viewModel.saveStatus.observe(viewLifecycleOwner) { status ->
            when (status) {
                is PurchaseViewModel.SaveStatus.Saving -> {
                    binding.btnSave.isEnabled = false
                    binding.btnSave.text = if (isEditMode) "Updating..." else "Saving..."
                }
                is PurchaseViewModel.SaveStatus.Success -> {
                    binding.btnSave.isEnabled = true
                    binding.btnSave.text = if (isEditMode) "Update Purchase" else "Save Purchase"
                    
                    val message = if (isEditMode) "✓ Purchase updated successfully" else "✓ Purchase saved successfully"
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                    viewModel.clearSaveStatus()
                    
                    if (isEditMode || isInsertMode) {
                        // Edit and Insert Here both go straight back — no "Add Another"
                        findNavController().navigateUp()
                    } else {
                        // Normal new purchase — offer to add another
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Purchase Saved")
                            .setMessage("Add another purchase or go back?")
                            .setCancelable(false)
                            .setPositiveButton("Add Another") { _, _ ->
                                resetForm()
                            }
                            .setNegativeButton("Go Back") { _, _ ->
                                findNavController().navigateUp()
                            }
                            .show()
                    }
                }
                is PurchaseViewModel.SaveStatus.Error -> {
                    binding.btnSave.isEnabled = true
                    binding.btnSave.text = if (isEditMode) "Update Purchase" else "Save Purchase"
                    Toast.makeText(
                        requireContext(),
                        status.message,
                        Toast.LENGTH_LONG
                    ).show()
                    viewModel.clearSaveStatus()
                }
                is PurchaseViewModel.SaveStatus.DuplicateFound -> {
                    binding.btnSave.isEnabled = true
                    binding.btnSave.text = "Save Purchase"
                    viewModel.clearSaveStatus()

                    // Format date for display
                    val dispDate = try {
                        val inFmt  = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                        val outFmt = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault())
                        inFmt.parse(status.date)?.let { outFmt.format(it) } ?: status.date
                    } catch (e: Exception) { status.date }

                    val invoiceInfo = if (status.invoice.isNotBlank()) "Invoice: ${status.invoice}\n" else ""

                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Duplicate Purchase")
                        .setMessage(
                            "${status.productName} already has a purchase entry for:\n\n" +
                            "Date: $dispDate\n" +
                            invoiceInfo +
                            "\nPlease edit the existing entry instead of adding a duplicate."
                        )
                        .setPositiveButton("Edit Existing") { _, _ ->
                            // We are currently ON purchaseEntryFragment, so we must pop back
                            // to purchasesList first, then navigate forward to a new entry screen.
                            val bundle = Bundle().apply {
                                putLong("purchaseId", status.existingPurchaseId)
                            }
                            val navController = findNavController()
                            navController.navigateUp()
                            navController.navigate(
                                com.simhadri.winentry.R.id.action_purchasesList_to_purchaseEntry,
                                bundle
                            )
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                null -> {
                    binding.btnSave.isEnabled = true
                    binding.btnSave.text = "Save Purchase"
                }
            }
        }
    }

    private fun resetForm() {
        // Preserve date and invoice so "Add another" continues the same session
        val retainedDate    = binding.textPurchaseDate.text.toString()
        val retainedInvoice = binding.editInvoiceNumber.text.toString()

        // Disable text watchers during reset
        isTextWatchersActive = false

        // Clear all quantity inputs
        binding.editQQBoxes.setText("0")
        binding.editQQLoose.setText("0")
        binding.editPPBoxes.setText("0")
        binding.editPPLoose.setText("0")
        binding.editNNBoxes.setText("0")
        binding.editNNLoose.setText("0")
        binding.editDDBoxes.setText("0")
        binding.editDDLoose.setText("0")

        // Clear prices
        binding.editQQUnitPrice.setText("0")
        binding.editPPUnitPrice.setText("0")
        binding.editNNUnitPrice.setText("0")
        binding.editDDUnitPrice.setText("0")

        // Clear product, supplier and notes — retain date and invoice
        binding.editSupplierName.setText("")
        binding.editNotes.setText("")
        binding.autoCompleteProduct.setText("", false)

        // Restore date and invoice
        binding.textPurchaseDate.setText(retainedDate)
        binding.editInvoiceNumber.setText(retainedInvoice)

        // Reset edit switches to OFF and lock prices
        binding.switchQQEdit.isChecked = false
        binding.switchPPEdit.isChecked = false
        binding.switchNNEdit.isChecked = false
        binding.switchDDEdit.isChecked = false

        // Hide units per box edit fields
        binding.layoutQQUnitsPerBox.visibility = View.GONE
        binding.layoutPPUnitsPerBox.visibility = View.GONE
        binding.layoutNNUnitsPerBox.visibility = View.GONE
        binding.layoutDDUnitsPerBox.visibility = View.GONE

        // Lock and gray out prices
        binding.editQQUnitPrice.isEnabled = false
        binding.editQQUnitPrice.alpha = 0.6f
        binding.editPPUnitPrice.isEnabled = false
        binding.editPPUnitPrice.alpha = 0.6f
        binding.editNNUnitPrice.isEnabled = false
        binding.editNNUnitPrice.alpha = 0.6f
        binding.editDDUnitPrice.isEnabled = false
        binding.editDDUnitPrice.alpha = 0.6f

        // Reset ViewModel
        viewModel.resetForm()

        // Restore retained values into ViewModel too
        updateMetadata()

        // Re-enable text watchers
        isTextWatchersActive = true
    }

    private fun handleBackPress() {
        val hasData = viewModel.currentPurchase.value?.let {
            it.qqTotalUnits > 0 || it.ppTotalUnits > 0 || 
            it.nnTotalUnits > 0 || it.ddTotalUnits > 0
        } ?: false

        if (hasData) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Discard Changes?")
                .setMessage("You have unsaved purchase data. Discard and go back?")
                .setPositiveButton("Discard") { _, _ ->
                    findNavController().navigateUp()
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            findNavController().navigateUp()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
