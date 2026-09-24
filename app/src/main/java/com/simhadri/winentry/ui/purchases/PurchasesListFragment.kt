package com.simhadri.winentry.ui.purchases

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.simhadri.winentry.R
import com.simhadri.winentry.ui.util.ScrollNavigationHelper
import com.simhadri.winentry.sync.SyncCoordinator
import com.simhadri.winentry.sync.SyncHelper
import com.simhadri.winentry.data.entity.Purchase
import com.simhadri.winentry.databinding.FragmentPurchasesListBinding
import com.simhadri.winentry.helpers.PurchaseExcelHelper
import com.simhadri.winentry.utils.AppDialogs
import com.simhadri.winentry.utils.AppStrings
import com.simhadri.winentry.utils.LangPrefs
import com.simhadri.winentry.utils.exportToDownloadsAndShare
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

class PurchasesListFragment : Fragment() {

    private var _binding: FragmentPurchasesListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: PurchaseViewModel by viewModels()
    private lateinit var adapter: GroupedPurchasesAdapter
    private var isMultiSelectMode = false
    private lateinit var excelHelper: PurchaseExcelHelper
    
    private var allPurchases: List<Purchase> = emptyList()
    
    // File picker for import
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { importFromExcel(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPurchasesListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            insets
        }
        val initialBottom = binding.recyclerView.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.recyclerView) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = initialBottom + bars.bottom, left = bars.left, right = bars.right)
            insets
        }

        excelHelper = PurchaseExcelHelper(requireContext().applicationContext)
        
        setupToolbar()
        setupRecyclerView()
        setupFab()
        observePurchases()
        ScrollNavigationHelper.setup(
            recyclerView   = binding.recyclerView,
            fabTop         = binding.fabScrollTop,
            fabBottom      = binding.fabScrollBottom,
            lifecycleOwner = viewLifecycleOwner,
            dataReady      = viewModel.allPurchases
        )
        setupImportObserver()   // ← rotation-safe import progress observer
    }

    private fun setupToolbar() {
        val lang = LangPrefs.get(requireContext())
        binding.textToolbarTitle.text = AppStrings.purchasesToolbarTitle.get(lang)
        binding.toolbar.setNavigationOnClickListener {
            if (isMultiSelectMode) {
                endMultiSelectMode()
            } else {
                findNavController().navigateUp()
            }
        }
        
        // Inflate menu directly into toolbar
        binding.toolbar.inflateMenu(R.menu.menu_purchases_list)
        
        // Setup search
        val searchItem = binding.toolbar.menu.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? SearchView
        searchView?.apply {
            queryHint = "Search by name, code, date..."
            
            // Set text color to dark for better visibility
            val searchEditText = findViewById<androidx.appcompat.widget.SearchView.SearchAutoComplete>(
                androidx.appcompat.R.id.search_src_text
            )
            searchEditText?.setTextColor(android.graphics.Color.BLACK)
            searchEditText?.setHintTextColor(android.graphics.Color.GRAY)
            
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false
                
                override fun onQueryTextChange(newText: String?): Boolean {
                    filterPurchases(newText ?: "")
                    return true
                }
            })
        }
        
        // Handle menu item clicks
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_import -> {
                    importLauncher.launch("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    true
                }
                R.id.action_export -> {
                    requireInvitation { exportToExcelDirect() }
                    true
                }
                R.id.action_export_filtered -> {
                    showFilteredExportDialog()
                    true
                }
                R.id.action_download_template -> {
                    downloadTemplateDirect()
                    true
                }
                R.id.action_delete_by_date -> {
                    showDeleteByDateRange()
                    true
                }
                R.id.action_multi_select -> {
                    startMultiSelectMode()
                    true
                }
                R.id.action_select_all -> {
                    adapter.selectAll()
                    true
                }
                R.id.action_delete_selected -> {
                    deleteSelectedPurchases()
                    true
                }
                R.id.action_cancel_selection -> {
                    endMultiSelectMode()
                    true
                }
                R.id.action_download_purchases_cloud -> {
                    downloadPurchasesFromCloud()
                    true
                }
                R.id.action_restore_purchases_cloud -> {
                    restorePurchasesFromCloud()
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Pull new purchase rows from the Purchases tab in Google Sheets.
     * Only rows with txnIds not already in the local DB are inserted.
     * Uses SyncHelper which resolves alias product codes to primary codes.
     */
    private fun downloadPurchasesFromCloud() {
        lifecycleScope.launch {
            val products = viewModel.getProductsForImport()
            SyncHelper.downloadPurchasesFromCloud(
                context    = requireContext(),
                scope      = lifecycleScope,
                anchorView = binding.root,
                products   = products,
                activity   = requireActivity(),
                onRefresh  = { /* allPurchases LiveData refreshes automatically via Room */ }
            )
        }
    }

    private fun restorePurchasesFromCloud() {
        lifecycleScope.launch {
            val products = viewModel.getProductsForImport()
            SyncHelper.restorePurchasesFromCloud(
                context    = requireContext(),
                scope      = lifecycleScope,
                anchorView = binding.root,
                products   = products,
                onRefresh  = { /* allPurchases LiveData refreshes automatically via Room */ }
            )
        }
    }


// Replaced old with new code
    private fun setupRecyclerView() {
        adapter = GroupedPurchasesAdapter(
            onItemClick = { purchase ->
                if (!isMultiSelectMode) {
                    showPurchaseActionDialog(purchase)
                }
            },
            onItemLongClick = { _, _ ->
                // Long press intentionally unused — multi-select via menu only
            },
            onSelectionChanged = { count ->
                updateMultiSelectMode(count)
            },
            onReceivedDateTap = { invoiceNumber, purchaseDate, currentReceivedDate ->
                showReceivedDatePicker(invoiceNumber, purchaseDate, currentReceivedDate)
            }
        )
        
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
    }
    
    private fun showPurchaseActionDialog(purchase: Purchase) {
        val currencyFormat = java.text.NumberFormat.getCurrencyInstance(java.util.Locale("en", "IN"))
        val dispDate = try {
            val inFmt  = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            val outFmt = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault())
            inFmt.parse(purchase.purchaseDate)?.let { outFmt.format(it) } ?: purchase.purchaseDate
        } catch (e: Exception) { purchase.purchaseDate }
        val invLine = if (purchase.invoiceNumber.isNotBlank()) "\nInv: ${purchase.invoiceNumber}" else ""

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(purchase.productName)
            .setMessage("${purchase.productCode}  ·  $dispDate$invLine\nTotal: ${currencyFormat.format(purchase.totalCost)}")
            .setPositiveButton("Edit") { _, _ ->
                val bundle = Bundle().apply { putLong("purchaseId", purchase.id) }
                findNavController().navigate(
                    R.id.action_purchasesList_to_purchaseEntry, bundle
                )
            }
            .setNegativeButton("Delete") { _, _ ->
                showDeleteConfirmation(purchase)
            }
            .setNeutralButton("Insert Here") { _, _ ->
                insertPurchaseAt(purchase)
            }
            .show()
    }
    
    private fun showReceivedDatePicker(
        invoiceNumber:       String,
        purchaseDate:        String,
        currentReceivedDate: String
    ) {
        val dbFmt   = SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val dispFmt = SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault())

        val cal = java.util.Calendar.getInstance()
        try { dbFmt.parse(currentReceivedDate)?.let { cal.time = it } } catch (_: Exception) {}

        val invDisp = invoiceNumber.ifBlank { "this invoice" }
        val pdDisp  = try { dbFmt.parse(purchaseDate)?.let { dispFmt.format(it) } ?: purchaseDate }
                      catch (_: Exception) { purchaseDate }

        // min = invoice date; max = today + 1
        val minCal = java.util.Calendar.getInstance()
        try { dbFmt.parse(purchaseDate)?.let { minCal.time = it } } catch (_: Exception) {}
        val maxCal = java.util.Calendar.getInstance().also {
            it.add(java.util.Calendar.DAY_OF_MONTH, 1)
        }

        android.app.DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                val selected = java.util.Calendar.getInstance().also { it.set(year, month, day) }
                val newDate  = dbFmt.format(selected.time)
                viewModel.updateInvoiceReceivedDate(invoiceNumber, purchaseDate, newDate)
                android.widget.Toast.makeText(
                    requireContext(),
                    "Received date updated to ${dispFmt.format(selected.time)}",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            },
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH),
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        ).apply {
            setTitle("Date Received — $invDisp ($pdDisp)")
            datePicker.minDate = minCal.timeInMillis
            datePicker.maxDate = maxCal.timeInMillis
        }.show()
    }

    private fun showDeleteConfirmation(purchase: Purchase) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Purchase?")
            .setMessage("Delete ${purchase.productName} purchase from ${purchase.purchaseDate}?\n\nThis cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deletePurchase(purchase)
                android.widget.Toast.makeText(
                    requireContext(),
                    "Purchase deleted",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupFab() {
        binding.fabAdd.setOnClickListener {
            findNavController().navigate(R.id.action_purchasesList_to_purchaseEntry)
        }
    }
    

    private fun observePurchases() {
        viewModel.allPurchases.observe(viewLifecycleOwner) { purchases ->
            allPurchases = purchases
            // Group purchases by date
            val groupedItems = GroupedPurchasesAdapter.groupPurchasesByDateAndInvoice(purchases)
            adapter.submitList(groupedItems)
            binding.emptyView.visibility = if (purchases.isEmpty()) View.VISIBLE else View.GONE
        }
    }
    

    /**
     * Registers the import observer once in onViewCreated.
     * Re-registers automatically after rotation — receives current Running/Success/Error state.
     */
    private fun setupImportObserver() {
        var progressDialog: android.app.Dialog? = null
        fun getOrCreateDialog(): android.app.Dialog =
            progressDialog ?: com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Importing Purchases...")
                .setMessage("Saving records. Please wait…")
                .setCancelable(false)
                .create().also { progressDialog = it }

        viewModel.importStatus.observe(viewLifecycleOwner) { status ->
            when (status) {
                is PurchaseViewModel.ImportStatus.Idle -> {
                    progressDialog?.dismiss()
                }
                is PurchaseViewModel.ImportStatus.Running -> {
                    val dlg = getOrCreateDialog()
                    if (!dlg.isShowing) dlg.show()
                }
                is PurchaseViewModel.ImportStatus.Success -> {
                    progressDialog?.dismiss()
                    showImportResultDialog(
                        newCount     = status.newCount,
                        skippedCount = status.skippedCount,
                        failCount    = status.failCount,
                        warnings     = status.warnings,
                        errors       = status.errors
                    )
                    viewModel.clearImportStatus()
                }
                is PurchaseViewModel.ImportStatus.Error -> {
                    progressDialog?.dismiss()
                    Toast.makeText(requireContext(),
                        "Import failed: ${status.message}", Toast.LENGTH_LONG).show()
                    viewModel.clearImportStatus()
                }
            }
        }
    }

    private fun importFromExcel(uri: android.net.Uri) {
        // Observer already registered in setupImportObserver() — just trigger the work
        viewModel.importFromExcel(uri, excelHelper)
    }

    private fun showImportResultDialog(
        newCount:     Int,
        skippedCount: Int,
        failCount:    Int,
        warnings:     List<String>,
        errors:       List<String>
    ) {
        val msg = buildString {
            append("NEW: $newCount  ")
            append("SKIPPED: $skippedCount  ")
            appendLine("FAILED: $failCount")

            val skippedWarnings = warnings.filter { it.contains("Skipped") }
            if (skippedWarnings.isNotEmpty()) {
                appendLine()
                appendLine("─── SKIPPED (already imported) ───")
                appendLine("Row  Code       Invoice")
                skippedWarnings.forEachIndexed { i, w ->
                    val code = w.substringAfter("Skipped: ").substringBefore(" (").take(10)
                    val inv  = w.substringAfter("invoice ").substringBefore(" on").take(12)
                    appendLine("${(i+1).toString().padEnd(4)} ${code.padEnd(10)} $inv")
                }
            }

            val notFoundErrors = errors.filter {
                it.contains("Product not found") || it.contains("not found") }
            if (notFoundErrors.isNotEmpty()) {
                appendLine()
                appendLine("─── NOT FOUND (check product codes) ───")
                appendLine("Row  Code")
                notFoundErrors.forEachIndexed { i, e ->
                    val code = e.substringAfter("found: ").substringBefore(" (").take(15)
                    appendLine("${(i+1).toString().padEnd(4)} $code")
                }
            }

            val otherErrors = errors.filter {
                !it.contains("Product not found") && !it.contains("not found") }
            if (otherErrors.isNotEmpty()) {
                appendLine()
                appendLine("─── OTHER ERRORS ───")
                otherErrors.forEach { appendLine("• $it") }
            }
        }

        val scrollView = android.widget.ScrollView(requireContext())
        scrollView.setPadding(40, 16, 40, 8)
        val textView = android.widget.TextView(requireContext()).apply {
            text = msg
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(android.graphics.Color.parseColor("#212121"))
        }
        scrollView.addView(textView)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (newCount > 0) "Import Complete" else "Import Result")
            .setView(scrollView)
            .setPositiveButton("OK", null)
            .show()
    }
    
    private fun exportToExcelDirect() {
        lifecycleScope.launch {
            try {
                val purchases = viewModel.allPurchases.value ?: emptyList()
                
                if (purchases.isEmpty()) {
                    Toast.makeText(requireContext(), "No purchases to export", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                Toast.makeText(requireContext(), "Exporting purchases...", Toast.LENGTH_SHORT).show()
                
                val fileName = "Purchases_Export_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.xlsx"
                
                // Export - automatically groups by invoice and date
                val uri = excelHelper.exportPurchases(purchases, fileName = fileName)
                
                // Count unique shipments
                val shipments = purchases.groupBy { Pair(it.invoiceNumber, it.purchaseDate) }.size
                
                Toast.makeText(
                    requireContext(),
                    "✓ Exported ${purchases.size} purchases ($shipments shipment${if(shipments > 1) "s" else ""}) successfully",
                    Toast.LENGTH_SHORT
                ).show()
                
                exportToDownloadsAndShare(uri, fileName, "Share Purchase Export")
                
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Export failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                android.util.Log.e("PurchasesListFragment", "Export error", e)
            }
        }
    }
    
    private fun downloadTemplateDirect() {
        lifecycleScope.launch {
            try {
                Toast.makeText(requireContext(), "Generating template...", Toast.LENGTH_SHORT).show()
                
                val products = viewModel.allProducts.value ?: emptyList()
                val fileName = "Purchase_Template.xlsx"
                
                val uri = excelHelper.generateTemplate(products, fileName)
                
                exportToDownloadsAndShare(uri, fileName, "Share Purchase Template")
                
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Template generation failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                android.util.Log.e("PurchasesListFragment", "Template error", e)
            }
        }
    }
    
    private fun filterPurchases(query: String) {
        val filtered = if (query.isEmpty()) {
            allPurchases
        } else {
            allPurchases.filter { purchase ->
                // Standard text searches
                purchase.productName.contains(query, ignoreCase = true) ||
                purchase.productCode.contains(query, ignoreCase = true) ||
                purchase.invoiceNumber.contains(query, ignoreCase = true) ||
                purchase.purchaseDate.contains(query, ignoreCase = true) ||
                // Format date to dd/MM/yyyy for search
                formatDateForSearch(purchase.purchaseDate).contains(query, ignoreCase = true) ||
                // Format date to d/M/yy for search (short format)
                formatDateForSearchShort(purchase.purchaseDate).contains(query, ignoreCase = true) ||
                // Search in product aliases
                searchInAliases(purchase.productId, query)
            }
        }
        
        val groupedItems = GroupedPurchasesAdapter.groupPurchasesByDateAndInvoice(filtered)
        adapter.submitList(groupedItems)
        binding.emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }
    
    /**
     * Check if query matches any alias of the product
     */
    private fun searchInAliases(productId: Long, query: String): Boolean {
        val product = viewModel.allProducts.value?.find { it.id == productId }
        if (product != null && product.aliases.isNotBlank()) {
            return product.aliases.split(",").any { alias ->
                alias.trim().contains(query, ignoreCase = true)
            }
        }
        return false
    }
    
    /**
     * Format date from yyyy-MM-dd to dd/MM/yyyy for search
     */
    private fun formatDateForSearch(dateStr: String): String {
        return try {
            val dbFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val displayFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            val date = dbFormat.parse(dateStr)
            if (date != null) displayFormat.format(date) else dateStr
        } catch (e: Exception) {
            dateStr
        }
    }
    
    /**
     * Format date from yyyy-MM-dd to d/M/yy for short format search
     */
    private fun formatDateForSearchShort(dateStr: String): String {
        return try {
            val dbFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val shortFormat = SimpleDateFormat("d/M/yy", Locale.getDefault())
            val date = dbFormat.parse(dateStr)
            if (date != null) shortFormat.format(date) else dateStr
        } catch (e: Exception) {
            dateStr
        }
    }
    
    private fun showFilteredExportDialog() {
        val dialog = FilteredExportDialog.newInstance()
        dialog.setOnExportListener { filteredPurchases ->
            exportFilteredPurchases(filteredPurchases)
        }
        dialog.show(childFragmentManager, "FilteredExport")
    }
    
    private fun exportFilteredPurchases(purchases: List<Purchase>) {
        lifecycleScope.launch {
            try {
                Toast.makeText(requireContext(), "Exporting filtered purchases...", Toast.LENGTH_SHORT).show()
                
                val fileName = "Purchases_Filtered_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.xlsx"
                
                val uri = excelHelper.exportPurchases(purchases, fileName = fileName)
                
                val shipments = purchases.groupBy { Pair(it.invoiceNumber, it.purchaseDate) }.size
                
                Toast.makeText(
                    requireContext(),
                    "✓ Exported ${purchases.size} filtered purchases",
                    Toast.LENGTH_SHORT
                ).show()
                
                val message = buildString {
                    appendLine("Filtered export complete")
                    appendLine()
                    appendLine("${purchases.size} purchases")
                    appendLine("$shipments shipment${if(shipments > 1) "s" else ""}")
                    appendLine()
                    appendLine("File: $fileName")
                    appendLine()
                    appendLine("Would you like to share?")
                }
                
                exportToDownloadsAndShare(uri, fileName, "Share Filtered Export")
                
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Export failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                android.util.Log.e("PurchasesListFragment", "Filtered export error", e)
            }
        }
    }
    private fun showContextMenu(purchase: Purchase, view: View) {
        val popup = PopupMenu(requireContext(), view)
        popup.inflate(R.menu.menu_purchase_context)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_edit -> {
                    showPurchaseActionDialog(purchase)
                    true
                }
                R.id.action_insert -> {
                    insertPurchaseAt(purchase)
                    true
                }
                R.id.action_delete -> {
                    confirmDelete(purchase)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun insertPurchaseAt(afterPurchase: Purchase) {
        // Navigate to purchase entry with date/invoice pre-filled
        val bundle = Bundle().apply {
            putString("invoiceNumber", afterPurchase.invoiceNumber)
            putString("purchaseDate", afterPurchase.purchaseDate)
        }
        findNavController().navigate(
            R.id.action_purchasesList_to_purchaseEntry,
            bundle
        )
    }

    private fun confirmDelete(purchase: Purchase) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Purchase?")
            .setMessage("Delete ${purchase.productName}?")
            .setPositiveButton("Delete") { _, _ ->
                deletePurchase(purchase)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deletePurchase(purchase: Purchase) {
        viewModel.deletePurchase(purchase)
        Toast.makeText(requireContext(), "Purchase deleted", Toast.LENGTH_SHORT).show()
    }

    private fun startMultiSelectMode() {
        isMultiSelectMode = true
        adapter.isMultiSelectMode = true
        binding.textToolbarTitle.text = AppStrings.purchasesSelectMode.get(LangPrefs.get(requireContext()))

        // Hide FAB during selection
        binding.fabAdd.visibility = android.view.View.INVISIBLE
        
        // Update menu
        binding.toolbar.menu.findItem(R.id.action_select_all)?.isVisible = true
        binding.toolbar.menu.findItem(R.id.action_delete_selected)?.isVisible = true
        binding.toolbar.menu.findItem(R.id.action_cancel_selection)?.isVisible = true
        
        // Show instructions
        Toast.makeText(
            requireContext(),
            "Tap purchases to select",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun endMultiSelectMode() {
        isMultiSelectMode = false
        adapter.clearSelection()
        binding.textToolbarTitle.text = AppStrings.purchasesToolbarTitle.get(LangPrefs.get(requireContext()))

        // Show FAB again
        binding.fabAdd.visibility = android.view.View.VISIBLE
        
        // Update menu
        binding.toolbar.menu.findItem(R.id.action_select_all)?.isVisible = false
        binding.toolbar.menu.findItem(R.id.action_delete_selected)?.isVisible = false
        binding.toolbar.menu.findItem(R.id.action_cancel_selection)?.isVisible = false
    }

    private fun updateMultiSelectMode(count: Int) {
        if (isMultiSelectMode) {
            if (count > 0) {
                binding.textToolbarTitle.text = "$count selected"
                // Show menu options for delete and select all
            } else {
                binding.textToolbarTitle.text = AppStrings.purchasesSelectMode.get(LangPrefs.get(requireContext()))
            }
        }
    }
    
    private fun showMultiSelectMenu() {
        val selected = adapter.getSelectedPurchases()
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("${selected.size} Selected")
            .setItems(arrayOf("Select All", "Delete Selected", "Cancel Selection")) { _, which ->
                when (which) {
                    0 -> adapter.selectAll()
                    1 -> if (selected.isNotEmpty()) deleteSelectedPurchases()
                    2 -> endMultiSelectMode()
                }
            }
            .show()
    }

    private fun deleteSelectedPurchases() {
        val selected = adapter.getSelectedPurchases()
        if (selected.isEmpty()) return

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete ${selected.size} Purchase(s)?")
            .setMessage("This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    viewModel.deletePurchases(selected)
                    Toast.makeText(
                        requireContext(),
                        "${selected.size} purchase(s) deleted",
                        Toast.LENGTH_SHORT
                    ).show()
                    endMultiSelectMode()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteByDateRange() {
        val dialog = DeleteDateRangeDialog.newInstance()
        dialog.setOnDeleteListener { startDate, endDate ->
            confirmDeleteDateRange(startDate, endDate)
        }
        dialog.show(childFragmentManager, "DeleteDateRange")
    }

    private fun confirmDeleteDateRange(startDate: String, endDate: String) {
        val purchases = viewModel.allPurchases.value ?: emptyList()
        val inRange = purchases.filter {
            it.purchaseDate >= startDate && it.purchaseDate <= endDate
        }

        if (inRange.isEmpty()) {
            Toast.makeText(
                requireContext(),
                "No purchases found in date range",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete ${inRange.size} Purchase(s)?")
            .setMessage("Delete all purchases from $startDate to $endDate?\n\nThis cannot be undone!")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    viewModel.deletePurchases(inRange)
                    Toast.makeText(
                        requireContext(),
                        "${inRange.size} purchase(s) deleted",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    private fun requireInvitation(onReady: () -> Unit) {
        if (SyncCoordinator(requireContext()).isUserSheetReady()) {
            onReady()
        } else {
            AppDialogs.confirm(
                context     = requireContext(),
                title       = "Drive Backup Required",
                message     = "This feature is available only after your cloud workspace is set up.\n\n" +
                    "Go to Settings → Drive Backup and request activation from the admin.",
                actionLabel = "Go to Settings"
            ) { findNavController().navigate(R.id.settingsFragment) }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
