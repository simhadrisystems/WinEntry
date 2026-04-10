package com.simple.simpleinventory.ui.products

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.widget.SearchView
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.simple.simpleinventory.R
import com.simple.simpleinventory.ui.util.ScrollNavigationHelper
import com.simple.simpleinventory.sync.SyncHelper
import com.simple.simpleinventory.databinding.FragmentProductListBinding
import com.simple.simpleinventory.utils.ExcelHelper
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
                    R.id.action_sync_products -> {
                        SyncHelper.syncProductsFromCloud(
                            context     = requireContext(),
                            scope       = lifecycleScope,
                            anchorView  = binding.root
                        )
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

                shareFile(uri, "Active products exported successfully")

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

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Inactive Products")
            .setMessage("Delete ${inactiveProducts.size} inactive products? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    inactiveProducts.forEach { product ->
                        viewModel.delete(product)
                    }
                    Toast.makeText(
                        requireContext(),
                        "${inactiveProducts.size} inactive products deleted",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showProductDialog(product: com.simple.simpleinventory.data.entity.Product?) {
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
                val products = excelHelper.importProducts(uri)

                if (products.isNotEmpty()) {
                    viewModel.insertAll(products)
                    Toast.makeText(
                        requireContext(),
                        "Successfully imported ${products.size} products",
                        Toast.LENGTH_LONG
                    ).show()
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

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete All Active Products")
            .setMessage("Delete ${activeProducts.size} active products? This cannot be undone.")
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    activeProducts.forEach { product ->
                        viewModel.delete(product)
                    }
                    Toast.makeText(
                        requireContext(),
                        "${activeProducts.size} active products deleted",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteAllProducts() {
        val allProducts = viewModel.allProducts.value ?: emptyList()

        if (allProducts.isEmpty()) {
            Toast.makeText(requireContext(), "No products to delete", Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete ALL Products")
            .setMessage("Delete ALL ${allProducts.size} products? This will erase your entire inventory and cannot be undone!")
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton("Delete All") { _, _ ->
                // Ask for confirmation again
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Are You Sure?")
                    .setMessage("This will permanently delete ${allProducts.size} products. Type DELETE to confirm.")
                    .setPositiveButton("Confirm Delete") { _, _ ->
                        lifecycleScope.launch {
                            allProducts.forEach { product ->
                                viewModel.delete(product)
                            }
                            Toast.makeText(
                                requireContext(),
                                "All products deleted",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
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

                shareFile(uri, "Products exported successfully")

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
                shareFile(uri, "Template downloaded successfully")
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Failed to create template: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun shareFile(uri: android.net.Uri, successMessage: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivity(Intent.createChooser(shareIntent, "Share Excel File"))
        Toast.makeText(requireContext(), successMessage, Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete(product: com.simple.simpleinventory.data.entity.Product) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Product")
            .setMessage("Are you sure you want to delete ${product.displayName}?")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.delete(product)
                Toast.makeText(
                    requireContext(),
                    "Product deleted",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}