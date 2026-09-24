package com.simhadri.winentry.ui.products

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.widget.SearchView
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.simhadri.winentry.utils.AppDialogs
import kotlinx.coroutines.withContext
import com.simhadri.winentry.R
import com.simhadri.winentry.data.entity.Product
import com.simhadri.winentry.ui.util.ScrollNavigationHelper
import com.simhadri.winentry.sync.SyncHelper
import com.simhadri.winentry.databinding.FragmentProductListBinding
import com.simhadri.winentry.utils.AppStrings
import com.simhadri.winentry.utils.ExcelHelper
import com.simhadri.winentry.utils.LangPrefs
import com.simhadri.winentry.utils.exportToDownloadsAndShare
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class ProductListFragment : Fragment() {

    private var _binding: FragmentProductListBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ProductViewModel by viewModels()
    private lateinit var adapter: ProductAdapter
    private lateinit var excelHelper: ExcelHelper

    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                importFromExcel(uri)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProductListBinding.inflate(inflater, container, false)
        excelHelper = ExcelHelper(requireContext())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val initialBottom = binding.recyclerView.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.recyclerView) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = initialBottom + bars.bottom, left = bars.left, right = bars.right)
            insets
        }

        setupRecyclerView()
        setupFab()
        observeProducts()
        setupMenu()
        ScrollNavigationHelper.setup(
            recyclerView   = binding.recyclerView,
            fabTop         = binding.fabScrollTop,
            fabBottom      = binding.fabScrollBottom,
            lifecycleOwner = viewLifecycleOwner,
            dataReady      = viewModel.allProducts
        )
    }
    


    private fun setupMenu() {
        // Back navigation — returns to Home
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
        binding.textToolbarTitle.text = AppStrings.productListToolbarTitle.get(LangPrefs.get(requireContext()))

        // Use toolbar menu instead of activity menu to avoid conflicts with Daily Stock
        binding.toolbar.inflateMenu(R.menu.menu_product)

        // Search — expands inline in the toolbar, white text on blue background
        val searchItem = binding.toolbar.menu.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? SearchView
        searchView?.apply {
            queryHint = "Search products..."
            maxWidth = Int.MAX_VALUE  // expand to full toolbar width when active

            // White text/hint to contrast against the blue toolbar
            val searchEditText = findViewById<androidx.appcompat.widget.SearchView.SearchAutoComplete>(
                androidx.appcompat.R.id.search_src_text
            )
            searchEditText?.setTextColor(android.graphics.Color.WHITE)
            searchEditText?.setHintTextColor(0xCCFFFFFF.toInt())

            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false
                override fun onQueryTextChange(newText: String?): Boolean {
                    adapter.filter(newText ?: "")
                    return true
                }
            })
        }
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                    R.id.action_sort_serial -> {
                        viewModel.setSortOrder(ProductViewModel.SortOrder.SERIAL)
                        Toast.makeText(context, "Sorted by Serial Number", Toast.LENGTH_SHORT).show()
                        true
                    }
                    R.id.action_sort_name -> {
                        viewModel.setSortOrder(ProductViewModel.SortOrder.NAME)
                        Toast.makeText(context, "Sorted by Product Name", Toast.LENGTH_SHORT).show()
                        true
                    }
                    R.id.action_sort_type -> {
                        viewModel.setSortOrder(ProductViewModel.SortOrder.TYPE)
                        Toast.makeText(context, "Sorted by Product Type", Toast.LENGTH_SHORT).show()
                        true
                    }
                    R.id.action_import -> {
                        pickExcelFile()
                        true
                    }
                    R.id.action_export -> {
                        exportToExcel()
                        true
                    }
                    R.id.action_export_active -> {
                        exportActiveOnly()
                        true
                    }
                    R.id.action_template -> {
                        downloadTemplate()
                        true
                    }
                    R.id.action_delete_inactive -> {
                        deleteInactiveProducts()
                        true
                    }
                    R.id.action_delete_active -> {
                        deleteActiveProducts()
                        true
                    }
                    R.id.action_delete_all -> {
                        deleteAllProducts()
                        true
                    }
                    else -> false
                }
        }
    }

    private fun setupRecyclerView() {
        adapter = ProductAdapter(
            onEditClick = { product ->
                showProductDialog(product)
            }
        )

        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ProductListFragment.adapter
        }
    }

    private fun setupFab() {
        binding.fabAdd.setOnClickListener {
            showProductDialog(null)
        }
    }

    private fun observeProducts() {
        viewModel.allProducts.observe(viewLifecycleOwner) { products ->
            adapter.submitFullList(products)
            binding.emptyView.visibility = if (products.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun exportActiveOnly() {
        lifecycleScope.launch {
            try {
                val allProducts = viewModel.allProducts.value
                val activeProducts = allProducts?.filter { it.isActive }

                if (activeProducts.isNullOrEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        "No active products to export",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                val fileName = "Active_Products_${dateFormat.format(Date())}.xlsx"
                val uri = excelHelper.exportProducts(activeProducts, fileName)

                shareFile(uri, fileName, "Share Active Products")

            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Export failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun deleteInactiveProducts() {
        val inactiveProducts = viewModel.allProducts.value?.filter { !it.isActive } ?: emptyList()

        if (inactiveProducts.isEmpty()) {
            Toast.makeText(requireContext(), "No inactive products to delete", Toast.LENGTH_SHORT).show()
            return
        }

        AppDialogs.destructive(
            requireContext(),
            "Delete Inactive Products",
            "Delete ${inactiveProducts.size} inactive products? This cannot be undone."
        ) {
            lifecycleScope.launch {
                val kept = viewModel.deleteProducts(inactiveProducts)
                Toast.makeText(requireContext(),
                    "${inactiveProducts.size - kept.size} inactive products deleted", Toast.LENGTH_SHORT).show()
                reportKept(kept)
            }
        }
    }

    private fun showProductDialog(product: Product?) {
        ProductFormDialog.newInstance(product).show(
            childFragmentManager,
            "ProductFormDialog"
        )
    }

    private fun pickExcelFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        importLauncher.launch(intent)
    }

    private fun importFromExcel(uri: android.net.Uri) {
        lifecycleScope.launch {
            try {
                val existing = viewModel.getAllProductsSync()
                val imported = withContext(kotlinx.coroutines.Dispatchers.IO) { excelHelper.importProducts(uri, existing) }
                val products = imported.products

                if (products.isNotEmpty()) {
                    viewModel.insertAllAwait(products)
                    Toast.makeText(
                        requireContext(),
                        "Successfully imported ${products.size} products",
                        Toast.LENGTH_LONG
                    ).show()
                    if (imported.notes.isNotEmpty()) AppDialogs.info(requireContext(), "Import Notes",
                        imported.notes.take(20).joinToString("\n") +
                            if (imported.notes.size > 20) "\n… and ${imported.notes.size - 20} more" else "")
                } else {
                    Toast.makeText(
                        requireContext(),
                        "No valid products found in file",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Import failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun deleteActiveProducts() {
        val activeProducts = viewModel.allProducts.value?.filter { it.isActive } ?: emptyList()

        if (activeProducts.isEmpty()) {
            Toast.makeText(requireContext(), "No active products to delete", Toast.LENGTH_SHORT).show()
            return
        }

        AppDialogs.destructive(
            requireContext(),
            "Delete All Active Products",
            "Delete ${activeProducts.size} active products? This cannot be undone."
        ) {
            lifecycleScope.launch {
                val kept = viewModel.deleteProducts(activeProducts)
                Toast.makeText(requireContext(),
                    "${activeProducts.size - kept.size} active products deleted", Toast.LENGTH_SHORT).show()
                reportKept(kept)
            }
        }
    }

    private fun deleteAllProducts() {
        val allProducts = viewModel.allProducts.value ?: emptyList()

        if (allProducts.isEmpty()) {
            Toast.makeText(requireContext(), "No products to delete", Toast.LENGTH_SHORT).show()
            return
        }

        AppDialogs.destructive(
            requireContext(),
            "Delete ALL Products",
            "Delete ALL ${allProducts.size} products?\n\nThis will erase your entire inventory and cannot be undone.",
            actionLabel = "Continue…"
        ) {
            AppDialogs.withTextInput(
                context      = requireContext(),
                title        = "Confirm — Delete All Products",
                message      = "Type  DELETE  to confirm permanently removing all ${allProducts.size} products:",
                requiredText = "DELETE",
                actionLabel  = "Delete All"
            ) {
                lifecycleScope.launch {
                    val kept = viewModel.deleteProducts(allProducts)
                    Toast.makeText(requireContext(),
                        "${allProducts.size - kept.size} products deleted", Toast.LENGTH_LONG).show()
                    reportKept(kept)
                }
            }
        }
    }
    private fun exportToExcel() {
        lifecycleScope.launch {
            try {
                val products = viewModel.allProducts.value

                if (products.isNullOrEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        "No products to export",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                val fileName = "Products_${dateFormat.format(Date())}.xlsx"
                val uri = excelHelper.exportProducts(products, fileName)

                shareFile(uri, fileName, "Share Products")

            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Export failed: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun downloadTemplate() {
        lifecycleScope.launch {
            try {
                val fileName = "Product_Template.xlsx"
                val uri = excelHelper.createTemplate(fileName)
                shareFile(uri, fileName, "Share Product Template")
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Failed to create template: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun shareFile(uri: android.net.Uri, fileName: String, title: String = "Share Excel File") {
        exportToDownloadsAndShare(uri, fileName, title)
    }

    private fun confirmDelete(product: Product) {
        AppDialogs.destructive(
            requireContext(),
            "Delete Product",
            "Delete ${product.displayName}? This cannot be undone."
        ) {
            lifecycleScope.launch {
                val kept = viewModel.deleteProducts(listOf(product))
                if (kept.isEmpty()) Toast.makeText(requireContext(), "Product deleted", Toast.LENGTH_SHORT).show()
                reportKept(kept)
            }
        }
    }

    private fun reportKept(kept: List<Product>) {
        if (kept.isEmpty() || !isAdded) return
        AppDialogs.info(requireContext(), "Not Deleted",
            "${kept.size} product(s) have purchases or stock history and were kept, so that " +
                "history stays linked. Deactivate them instead:\n\n" +
                kept.take(15).joinToString("\n") { it.displayName } +
                if (kept.size > 15) "\n… and ${kept.size - 15} more" else "")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}