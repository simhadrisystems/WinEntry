package com.simhadri.winentry.ui.purchases

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updatePadding
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.simhadri.winentry.data.entity.Product
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
    private var isInsertMode = false        // true when launched via "Insert Here" from list
    private var isDateInvoiceFrozen = false // true after first "Add Another" — locks date+invoice visually but still shows "Add Another" dialog
    private var lockedInvoice: String = ""
    private var lockedDate: String = ""

    // Units per box from product master — read-only in form, used for total calculation
    private var qqUnitsPerBox = 12
    private var ppUnitsPerBox = 24
    private var nnUnitsPerBox = 48
    private var ddUnitsPerBox = 96

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

        // Lift action bar above system nav bar if insets reach this fragment
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            if (navBar > 0) binding.bottomActionBar.updatePadding(bottom = navBar)
            insets
        }
        // After the bar is fully measured, set scroll padding = bar height + 16 dp.
        // doOnLayout fires post-measure so this adapts to actual density and text scale.
        binding.bottomActionBar.doOnLayout { bar ->
            val extra = (16 * resources.displayMetrics.density).toInt()
            binding.nestedScrollView.updatePadding(bottom = bar.height + extra)
        }

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
        binding.autoCompleteProduct.setText("${purchase.productName} (${purchase.productCode})", false)
        // Disable product selector in edit mode - can't change product of existing purchase
        binding.autoCompleteProduct.isEnabled = false
        binding.layoutProduct.isEnabled = false
        
        // Populate QQ from Purchase data (uses stored snapshot)
        if (purchase.qqUnitsPerBox > 0) {
            qqUnitsPerBox = purchase.qqUnitsPerBox
            binding.textQQUnitsPerBox.text = "${qqUnitsPerBox}/box"
        }
        if (purchase.qqTotalUnits > 0 || purchase.qqUnitsPerBox > 0) {
            binding.editQQBoxes.setText(purchase.qqBoxes.toString())
            binding.editQQLoose.setText(purchase.qqLoose.toString())
            binding.editQQUnitPrice.setText(purchase.qqUnitPrice.toString())
            binding.cardQQ.alpha = 1.0f
            binding.editQQBoxes.isEnabled = true
            binding.editQQLoose.isEnabled = true
        }

        // Populate PP from Purchase data
        if (purchase.ppUnitsPerBox > 0) {
            ppUnitsPerBox = purchase.ppUnitsPerBox
            binding.textPPUnitsPerBox.text = "${ppUnitsPerBox}/box"
        }
        if (purchase.ppTotalUnits > 0 || purchase.ppUnitsPerBox > 0) {
            binding.editPPBoxes.setText(purchase.ppBoxes.toString())
            binding.editPPLoose.setText(purchase.ppLoose.toString())
            binding.editPPUnitPrice.setText(purchase.ppUnitPrice.toString())
            binding.cardPP.alpha = 1.0f
            binding.editPPBoxes.isEnabled = true
            binding.editPPLoose.isEnabled = true
        }

        // Populate NN from Purchase data
        if (purchase.nnUnitsPerBox > 0) {
            nnUnitsPerBox = purchase.nnUnitsPerBox
            binding.textNNUnitsPerBox.text = "${nnUnitsPerBox}/box"
        }
        if (purchase.nnTotalUnits > 0 || purchase.nnUnitsPerBox > 0) {
            binding.editNNBoxes.setText(purchase.nnBoxes.toString())
            binding.editNNLoose.setText(purchase.nnLoose.toString())
            binding.editNNUnitPrice.setText(purchase.nnUnitPrice.toString())
            binding.cardNN.alpha = 1.0f
            binding.editNNBoxes.isEnabled = true
            binding.editNNLoose.isEnabled = true
        }

        // Populate DD from Purchase data
        if (purchase.ddUnitsPerBox > 0) {
            ddUnitsPerBox = purchase.ddUnitsPerBox
            binding.textDDUnitsPerBox.text = "${ddUnitsPerBox}/box"
        }
        if (purchase.ddTotalUnits > 0 || purchase.ddUnitsPerBox > 0) {
            binding.editDDBoxes.setText(purchase.ddBoxes.toString())
            binding.editDDLoose.setText(purchase.ddLoose.toString())
            binding.editDDUnitPrice.setText(purchase.ddUnitPrice.toString())
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
    
    private fun populateUIForEdit(purchase: Purchase, @Suppress("UNUSED_PARAMETER") product: Product) {
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
            val noProducts = products.isEmpty()

            val productAdapter = buildProductAdapter(products, productStrings, binding.autoCompleteProduct)
            binding.autoCompleteProduct.setAdapter(productAdapter)
            binding.autoCompleteProduct.threshold = 1

            // Show hint in the field when no products are downloaded
            binding.layoutProduct.hint = if (noProducts)
                "No products — download from Home screen first"
            else
                "Select Product (type to filter)"

            val noProductsMsg = "No products available. Go to Home screen → Getting Started → Import Product List."

            binding.autoCompleteProduct.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && noProducts) {
                    Toast.makeText(requireContext(), noProductsMsg, Toast.LENGTH_LONG).show()
                    binding.autoCompleteProduct.clearFocus()
                }
            }

            binding.layoutProduct.setStartIconOnClickListener {
                if (noProducts) {
                    Toast.makeText(requireContext(), noProductsMsg, Toast.LENGTH_LONG).show()
                    return@setStartIconOnClickListener
                }
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
                val selectedText = parent.getItemAtPosition(position) as String
                val selectedProduct = products.find {
                    "${it.displayName} (${it.productType}${it.brandCode})" == selectedText
                }
                if (selectedProduct != null) {
                    viewModel.selectProduct(selectedProduct)
                    binding.root.post { updateProductInfo(selectedProduct) }
                }
            }
        }
    }

    private fun buildProductAdapter(
        products: List<Product>,
        productStrings: List<String>,
        actv: android.widget.AutoCompleteTextView
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
                    // The internal AutoCompleteTextView onFilterComplete→showDropDown chain can
                    // silently skip when hasWindowFocus() is false at filter-complete time (a
                    // transient state caused by concurrent doOnLayout/inset layout passes).
                    // Calling showDropDown() here, after the adapter is populated, is reliable.
                    if (results != null && results.count > 0) {
                        actv.post { if (actv.isFocused) actv.showDropDown() }
                    }
                }
            }
            override fun getFilter(): Filter = cachedFilter
        }
    }

    private fun updateProductInfo(product: Product) {
        // Disable text watcher callbacks temporarily
        isTextWatchersActive = false
        
        // Update units per box member vars and read-only badge
        qqUnitsPerBox = if (product.qqUnitsPerBox > 0) product.qqUnitsPerBox else 12
        ppUnitsPerBox = if (product.ppUnitsPerBox > 0) product.ppUnitsPerBox else 24
        nnUnitsPerBox = if (product.nnUnitsPerBox > 0) product.nnUnitsPerBox else 48
        ddUnitsPerBox = if (product.ddUnitsPerBox > 0) product.ddUnitsPerBox else 96

        binding.textQQUnitsPerBox.text = "${qqUnitsPerBox}/box"
        binding.textPPUnitsPerBox.text = "${ppUnitsPerBox}/box"
        binding.textNNUnitsPerBox.text = "${nnUnitsPerBox}/box"
        binding.textDDUnitsPerBox.text = "${ddUnitsPerBox}/box"

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
            binding.editQQUnitPrice.isEnabled = isChecked
            binding.editQQUnitPrice.alpha = if (isChecked) 1.0f else 0.6f
            val qqPrice = viewModel.selectedProduct.value?.qqPurchasePrice ?: 0.0
            binding.editQQBoxes.isEnabled = isChecked || qqPrice > 0
            binding.editQQLoose.isEnabled = isChecked || qqPrice > 0
            binding.cardQQ.alpha = if (isChecked || qqPrice > 0) 1.0f else 0.4f
            wireImeChain()
        }
        binding.editQQBoxes.addTextChangedListener { calculateQQ() }
        binding.editQQLoose.addTextChangedListener { calculateQQ() }
        binding.editQQUnitPrice.addTextChangedListener { calculateQQ() }

        // PP Size
        binding.editPPUnitPrice.isEnabled = false
        binding.editPPUnitPrice.alpha = 0.6f
        binding.switchPPEdit.setOnCheckedChangeListener { _, isChecked ->
            binding.editPPUnitPrice.isEnabled = isChecked
            binding.editPPUnitPrice.alpha = if (isChecked) 1.0f else 0.6f
            val ppPrice = viewModel.selectedProduct.value?.ppPurchasePrice ?: 0.0
            binding.editPPBoxes.isEnabled = isChecked || ppPrice > 0
            binding.editPPLoose.isEnabled = isChecked || ppPrice > 0
            binding.cardPP.alpha = if (isChecked || ppPrice > 0) 1.0f else 0.4f
            wireImeChain()
        }
        binding.editPPBoxes.addTextChangedListener { calculatePP() }
        binding.editPPLoose.addTextChangedListener { calculatePP() }
        binding.editPPUnitPrice.addTextChangedListener { calculatePP() }

        // NN Size
        binding.editNNUnitPrice.isEnabled = false
        binding.editNNUnitPrice.alpha = 0.6f
        binding.switchNNEdit.setOnCheckedChangeListener { _, isChecked ->
            binding.editNNUnitPrice.isEnabled = isChecked
            binding.editNNUnitPrice.alpha = if (isChecked) 1.0f else 0.6f
            val nnPrice = viewModel.selectedProduct.value?.nnPurchasePrice ?: 0.0
            binding.editNNBoxes.isEnabled = isChecked || nnPrice > 0
            binding.editNNLoose.isEnabled = isChecked || nnPrice > 0
            binding.cardNN.alpha = if (isChecked || nnPrice > 0) 1.0f else 0.4f
            wireImeChain()
        }
        binding.editNNBoxes.addTextChangedListener { calculateNN() }
        binding.editNNLoose.addTextChangedListener { calculateNN() }
        binding.editNNUnitPrice.addTextChangedListener { calculateNN() }

        // DD Size
        binding.editDDUnitPrice.isEnabled = false
        binding.editDDUnitPrice.alpha = 0.6f
        binding.switchDDEdit.setOnCheckedChangeListener { _, isChecked ->
            binding.editDDUnitPrice.isEnabled = isChecked
            binding.editDDUnitPrice.alpha = if (isChecked) 1.0f else 0.6f
            val ddPrice = viewModel.selectedProduct.value?.ddPurchasePrice ?: 0.0
            binding.editDDBoxes.isEnabled = isChecked || ddPrice > 0
            binding.editDDLoose.isEnabled = isChecked || ddPrice > 0
            binding.cardDD.alpha = if (isChecked || ddPrice > 0) 1.0f else 0.4f
            wireImeChain()
        }
        binding.editDDBoxes.addTextChangedListener { calculateDD() }
        binding.editDDLoose.addTextChangedListener { calculateDD() }
        binding.editDDUnitPrice.addTextChangedListener { calculateDD() }

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
        val unitsPerBox = qqUnitsPerBox
        
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
        
        val unitsPerBox = ppUnitsPerBox
        
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
        
        val unitsPerBox = nnUnitsPerBox
        
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
        
        val unitsPerBox = ddUnitsPerBox
        
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
        // In Insert Here mode or after Add Another, date and invoice are locked
        if (isInsertMode || isDateInvoiceFrozen) {
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
            binding.editQQBoxes, binding.editQQLoose, binding.editQQUnitPrice,
            binding.editPPBoxes, binding.editPPLoose, binding.editPPUnitPrice,
            binding.editNNBoxes, binding.editNNLoose, binding.editNNUnitPrice,
            binding.editDDBoxes, binding.editDDLoose, binding.editDDUnitPrice
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

                    val message = if (isEditMode) "Purchase updated" else "Purchase saved"
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()

                    if (status.activatedProductName.isNotEmpty()) {
                        com.google.android.material.snackbar.Snackbar.make(
                            binding.root,
                            "'${status.activatedProductName}' activated — it will now appear in Daily Stock",
                            com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                        ).show()
                    }

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
                                val keepInvoice = binding.editInvoiceNumber.text.toString()
                                val keepDateDisplay = binding.textPurchaseDate.text.toString()
                                lockedDate = try {
                                    displayDateFormat.parse(keepDateDisplay)
                                        ?.let { dateFormat.format(it) }
                                        ?: dateFormat.format(Calendar.getInstance().time)
                                } catch (e: Exception) { dateFormat.format(Calendar.getInstance().time) }
                                lockedInvoice = keepInvoice
                                isDateInvoiceFrozen = true
                                resetForm()
                                binding.editInvoiceNumber.isEnabled = false
                                binding.editInvoiceNumber.alpha = 0.6f
                                binding.textPurchaseDate.isEnabled = false
                                binding.textPurchaseDate.alpha = 0.6f
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
                                R.id.action_purchasesList_to_purchaseEntry,
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

        // Reset edit switches to OFF — switch listener handles price lock
        binding.switchQQEdit.isChecked = false
        binding.switchPPEdit.isChecked = false
        binding.switchNNEdit.isChecked = false
        binding.switchDDEdit.isChecked = false

        // Lock and gray out prices explicitly (switch listener may not fire if already unchecked)
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
