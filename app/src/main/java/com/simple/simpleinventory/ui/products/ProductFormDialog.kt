package com.simple.simpleinventory.ui.products

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.simple.simpleinventory.R
import com.simple.simpleinventory.data.entity.Product
import android.content.res.ColorStateList
import android.graphics.Color
import com.simple.simpleinventory.databinding.DialogProductFormBinding
import com.simple.simpleinventory.utils.TypeLabels
import kotlinx.coroutines.launch

class ProductFormDialog : DialogFragment() {

    private var _binding: DialogProductFormBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ProductViewModel by viewModels()
    private var editingProduct: Product? = null

    companion object {
        private const val ARG_PRODUCT = "product"

        fun newInstance(product: Product?): ProductFormDialog {
            return ProductFormDialog().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_PRODUCT, product)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.FullScreenDialog)
        @Suppress("DEPRECATION")
        editingProduct = arguments?.getSerializable(ARG_PRODUCT) as? Product
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogProductFormBinding.inflate(inflater, container, false)
        dialog?.setCanceledOnTouchOutside(false)
        isCancelable = false
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupProductTypeDropdown()
        setupAutoGeneration()
        setupClearOnFocus()
        populateFields()
        setupButtons()
        setupActiveSwitchColour()
        setupBackPress()
        observeSaveResult()
    }
    
    private fun observeSaveResult() {
        viewModel.saveResult.observe(viewLifecycleOwner) { result ->
            when (result) {
                is ProductViewModel.SaveResult.Success -> {
                    val message = if (editingProduct != null) "Product updated" else "Product added"
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                    viewModel.clearSaveResult()
                    dismiss()
                }
                is ProductViewModel.SaveResult.DuplicateBrandCode -> {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("❌ Duplicate Brand Code")
                        .setMessage(
                            "Cannot save: Brand code '${result.brandCode}' is already in use.\n\n" +
                            "Another product was created with this code. " +
                            "Please choose a different brand code."
                        )
                        .setPositiveButton("OK") { _, _ ->
                            binding.editBrandCode.error = "Duplicate - choose another"
                            binding.editBrandCode.requestFocus()
                        }
                        .show()
                    viewModel.clearSaveResult()
                }
                is ProductViewModel.SaveResult.Error -> {
                    Toast.makeText(
                        requireContext(),
                        "Error: ${result.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    viewModel.clearSaveResult()
                }
                null -> {
                    // No result yet
                }
            }
        }
    }

    private fun setupProductTypeDropdown() {
        val adapter = android.widget.ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            TypeLabels.typeDisplayItems          // e.g. ["W  —  Whisky", "Y  —  Brandy", ...]
        )
        binding.spinnerProductType.setAdapter(adapter)
        binding.spinnerProductType.setText(TypeLabels.typeDisplay("W"), false) // default W
        
        // Trigger code generation when type changes
        binding.spinnerProductType.setOnItemClickListener { _, _, _, _ ->
            updateGeneratedCodes()
        }
    }

    private fun setupAutoGeneration() {
        // Auto-generate codes when brand code changes
        binding.editBrandCode.addTextChangedListener {
            updateGeneratedCodes()
        }
        
        // Note: Product type dropdown listener is set up in setupProductTypeDropdown()
    }

    private fun updateGeneratedCodes() {
        val brandCode = binding.editBrandCode.text.toString().trim().uppercase()
        val productType = TypeLabels.codeFromDisplay(binding.spinnerProductType.text.toString())

        if (brandCode.isNotEmpty() && productType.isNotEmpty()) {
            binding.textQqCode.text = "QQ: ${productType}${brandCode}QQ"
            binding.textPpCode.text = "PP: ${productType}${brandCode}PP"
            binding.textNnCode.text = "NN: ${productType}${brandCode}NN"
            binding.textDdCode.text = "DD: ${productType}${brandCode}DD"
        }
    }

    private fun setupClearOnFocus() {
        val clearOnFocus = { view: View, defaultValue: String ->
            view.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    val editText = v as? android.widget.EditText
                    if (editText?.text.toString() == defaultValue) {
                        editText?.setText("")
                    }
                }
            }
        }

        clearOnFocus(binding.editSerialNo, "1")
        clearOnFocus(binding.editQqPurchase, "0.0")
        clearOnFocus(binding.editQqSale, "0.0")
        clearOnFocus(binding.editPpPurchase, "0.0")
        clearOnFocus(binding.editPpSale, "0.0")
        clearOnFocus(binding.editNnPurchase, "0.0")
        clearOnFocus(binding.editNnSale, "0.0")
        clearOnFocus(binding.editDdPurchase, "0.0")
        clearOnFocus(binding.editDdSale, "0.0")
    }

    private fun populateFields() {
        editingProduct?.let { product ->
            binding.apply {
                editProductName.setText(product.productName)
                spinnerProductType.setText(TypeLabels.typeDisplay(product.productType), false)
                editCategory.setText(product.category)
                editBrandCode.setText(product.brandCode)
                editAliases.setText(product.aliases)
                editQuantity.setText(product.dailySortKey.toString())  // repurposed as Sort Key
                editDisplayName.setText(product.displayName)
                editSerialNo.setText(product.serialNo.toString())
                switchActive.isChecked = product.isActive  // triggers colour listener below

                textQqCode.text = "QQ: ${product.qqCode}"
                textPpCode.text = "PP: ${product.ppCode}"
                textNnCode.text = "NN: ${product.nnCode}"
                textDdCode.text = "DD: ${product.ddCode}"

                editQqPurchase.setText(if (product.qqPurchasePrice > 0) product.qqPurchasePrice.toString() else "0.0")
                editQqSale.setText(if (product.qqSalePrice > 0) product.qqSalePrice.toString() else "0.0")
                editPpPurchase.setText(if (product.ppPurchasePrice > 0) product.ppPurchasePrice.toString() else "0.0")
                editPpSale.setText(if (product.ppSalePrice > 0) product.ppSalePrice.toString() else "0.0")
                editNnPurchase.setText(if (product.nnPurchasePrice > 0) product.nnPurchasePrice.toString() else "0.0")
                editNnSale.setText(if (product.nnSalePrice > 0) product.nnSalePrice.toString() else "0.0")
                editDdPurchase.setText(if (product.ddPurchasePrice > 0) product.ddPurchasePrice.toString() else "0.0")
                editDdSale.setText(if (product.ddSalePrice > 0) product.ddSalePrice.toString() else "0.0")

                // Units per box — read from DB, fall back to entity defaults
                editQqUnitsPerBox.setText(product.qqUnitsPerBox.toString())
                editPpUnitsPerBox.setText(product.ppUnitsPerBox.toString())
                editNnUnitsPerBox.setText(product.nnUnitsPerBox.toString())
                editDdUnitsPerBox.setText(product.ddUnitsPerBox.toString())
            }
        }
    }

    private fun setupActiveSwitchColour() {
        val updateColour = { isChecked: Boolean ->
            val thumbCol  = if (isChecked) Color.parseColor("#FF388E3C") else Color.parseColor("#FFC62828")
            val trackCol  = if (isChecked) Color.parseColor("#6638893C") else Color.parseColor("#66C62828")
            binding.switchActive.text = if (isChecked) "Active Product" else "Inactive Product"
            binding.switchActive.setTextColor(thumbCol)
            binding.switchActive.thumbTintList = ColorStateList.valueOf(thumbCol)
            binding.switchActive.trackTintList = ColorStateList.valueOf(trackCol)
        }

        binding.switchActive.setOnCheckedChangeListener { _, isChecked ->
            updateColour(isChecked)
        }

        // Apply correct colour immediately for initial state
        updateColour(binding.switchActive.isChecked)
    }

    private fun setupButtons() {
        binding.buttonSave.setOnClickListener {
            saveProduct()
        }

        binding.buttonCancel.setOnClickListener {
            confirmCancel()
        }

        // Delete — only visible when editing an existing product
        if (editingProduct != null) {
            binding.buttonDelete.visibility = android.view.View.VISIBLE
            binding.buttonDelete.setOnClickListener {
                confirmDelete()
            }
        }
    }

    private fun confirmDelete() {
        val name = editingProduct?.displayName ?: "this product"
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Product")
            .setMessage("Permanently delete $name?\n\nThis cannot be undone.")
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton("Delete") { _, _ ->
                editingProduct?.let { product ->
                    viewModel.delete(product)
                    Toast.makeText(requireContext(), "$name deleted", Toast.LENGTH_SHORT).show()
                    dismiss()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupBackPress() {
        requireDialog().setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                event.action == android.view.KeyEvent.ACTION_UP) {
                confirmCancel()
                true
            } else {
                false
            }
        }
    }

    private fun confirmCancel() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Discard Changes?")
            .setMessage("Are you sure you want to discard your changes?")
            .setPositiveButton("Discard") { _, _ ->
                dismiss()
            }
            .setNegativeButton("Continue Editing", null)
            .show()
    }

    private fun saveProduct() {
        val productName = binding.editProductName.text.toString().trim()
        // Dropdown shows "W  —  Whisky"; extract just the code character
        val productType = TypeLabels.codeFromDisplay(
            binding.spinnerProductType.text.toString())
        val brandCode = binding.editBrandCode.text.toString().trim().uppercase()

        // Validation
        if (productName.isEmpty()) {
            binding.editProductName.error = "Required"
            return
        }

        if (productType.isEmpty()) {
            binding.spinnerProductType.error = "Required"
            return
        }

        val validTypes = TypeLabels.typeCodes
        if (!validTypes.contains(productType)) {
            binding.spinnerProductType.error = "Select a valid product type"
            return
        }

        if (brandCode.isEmpty()) {
            binding.editBrandCode.error = "Required"
            return
        }

        if (brandCode.length > 5) {
            binding.editBrandCode.error = "Max 5 characters"
            return
        }
        
        // Check for duplicate brandCode (only if adding new or changing code)
        // In saveProduct(), after brandCode validation:
        lifecycleScope.launch {
            // Check for duplicate brandCode
            val existingProduct = viewModel.allProducts.value?.find {
                it.brandCode == brandCode && it.id != (editingProduct?.id ?: 0)
            }

            if (existingProduct != null) {
                binding.editBrandCode.error = "Brand code already used by ${existingProduct.displayName}"
                return@launch
            }

            // Warn if changing brandCode on existing product
            val currentProduct = editingProduct  // Capture to local variable for smart cast
            if (currentProduct != null && currentProduct.brandCode != brandCode) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("⚠️ Warning: Changing Brand Code")
                    .setMessage(
                        "Changing brand code will orphan:\n\n" +
                                "• Existing purchases\n" +
                                "• Existing daily stock entries\n" +
                                "• Historical data\n\n" +
                                "⚠️ Use Aliases instead!\n\n" +
                                "Continue anyway?"
                    )
                    .setPositiveButton("Continue") { _, _ -> completeSave() }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                completeSave()
            }
        }
    }
    
    private fun completeSave() {
        val productName = binding.editProductName.text.toString().trim()
        val productType = TypeLabels.codeFromDisplay(
            binding.spinnerProductType.text.toString())
        val brandCode = binding.editBrandCode.text.toString().trim().uppercase()
        val category = binding.editCategory.text.toString().trim()
        val aliases = binding.editAliases.text.toString().trim()
        
        // Validate aliases format
        if (aliases.isNotEmpty()) {
            val aliasList = aliases.split(",").map { it.trim() }
            for (alias in aliasList) {
                if (alias.isEmpty()) {
                    binding.editAliases.error = "Remove empty aliases (trailing commas)"
                    return
                }
                if (alias.contains(" ")) {
                    binding.editAliases.error = "No spaces in alias codes: '$alias'"
                    return
                }
                if (alias.length > 5) {
                    binding.editAliases.error = "Alias '$alias' too long (max 5 chars)"
                    return
                }
            }
        }
        
        val dailySortKey = binding.editQuantity.text.toString().toIntOrNull()?.coerceIn(1, 999) ?: 999
        val displayName = binding.editDisplayName.text.toString().trim()
        val serialNo = binding.editSerialNo.text.toString().toIntOrNull() ?: 1
        val isActive = binding.switchActive.isChecked

        val qqPurchase = binding.editQqPurchase.text.toString().toDoubleOrNull() ?: 0.0
        val qqSale = binding.editQqSale.text.toString().toDoubleOrNull() ?: 0.0
        val ppPurchase = binding.editPpPurchase.text.toString().toDoubleOrNull() ?: 0.0
        val ppSale = binding.editPpSale.text.toString().toDoubleOrNull() ?: 0.0
        val nnPurchase = binding.editNnPurchase.text.toString().toDoubleOrNull() ?: 0.0
        val nnSale = binding.editNnSale.text.toString().toDoubleOrNull() ?: 0.0
        val ddPurchase = binding.editDdPurchase.text.toString().toDoubleOrNull() ?: 0.0
        val ddSale = binding.editDdSale.text.toString().toDoubleOrNull() ?: 0.0

        // Units per box — fall back to entity defaults if field left blank
        val qqUnitsPerBox = binding.editQqUnitsPerBox.text.toString().toIntOrNull() ?: 12
        val ppUnitsPerBox = binding.editPpUnitsPerBox.text.toString().toIntOrNull() ?: 24
        val nnUnitsPerBox = binding.editNnUnitsPerBox.text.toString().toIntOrNull() ?: 48
        val ddUnitsPerBox = binding.editDdUnitsPerBox.text.toString().toIntOrNull() ?: 96

        val product = if (editingProduct != null) {
            editingProduct!!.copy(
                productName = productName,
                productType = productType,
                category = category,
                brandCode = brandCode,
                aliases = aliases,
                qqCode = "${productType}${brandCode}QQ",
                ppCode = "${productType}${brandCode}PP",
                nnCode = "${productType}${brandCode}NN",
                ddCode = "${productType}${brandCode}DD",
                qqPurchasePrice = qqPurchase,
                qqSalePrice = qqSale,
                ppPurchasePrice = ppPurchase,
                ppSalePrice = ppSale,
                nnPurchasePrice = nnPurchase,
                nnSalePrice = nnSale,
                ddPurchasePrice = ddPurchase,
                ddSalePrice = ddSale,
                qqUnitsPerBox = qqUnitsPerBox,
                ppUnitsPerBox = ppUnitsPerBox,
                nnUnitsPerBox = nnUnitsPerBox,
                ddUnitsPerBox = ddUnitsPerBox,
                displayName = displayName.ifEmpty { "$productName $productType" },
                serialNo = serialNo,
                isActive = isActive,
                dailySortKey = dailySortKey
            )
        } else {
            Product(
                productName = productName,
                productType = productType,
                category = category,
                brandCode = brandCode,
                aliases = aliases,
                qqCode = "${productType}${brandCode}QQ",
                ppCode = "${productType}${brandCode}PP",
                nnCode = "${productType}${brandCode}NN",
                ddCode = "${productType}${brandCode}DD",
                qqPurchasePrice = qqPurchase,
                qqSalePrice = qqSale,
                ppPurchasePrice = ppPurchase,
                ppSalePrice = ppSale,
                nnPurchasePrice = nnPurchase,
                nnSalePrice = nnSale,
                ddPurchasePrice = ddPurchase,
                ddSalePrice = ddSale,
                qqUnitsPerBox = qqUnitsPerBox,
                ppUnitsPerBox = ppUnitsPerBox,
                nnUnitsPerBox = nnUnitsPerBox,
                ddUnitsPerBox = ddUnitsPerBox,
                displayName = displayName.ifEmpty { "$productName $productType" },
                serialNo = serialNo,
                isActive = isActive,
                dailySortKey = dailySortKey
            )
        }

        // ViewModel handles errors and posts result to saveResult LiveData
        if (editingProduct != null) {
            viewModel.update(product)
        } else {
            viewModel.insert(product)
        }
        // Observer will handle success/error and dismiss dialog
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}