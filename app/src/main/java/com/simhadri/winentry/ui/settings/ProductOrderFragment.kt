package com.simhadri.winentry.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.simhadri.winentry.data.entity.Product
import com.simhadri.winentry.databinding.FragmentProductOrderBinding
import com.simhadri.winentry.ui.util.ScrollNavigationHelper

class ProductOrderFragment : Fragment() {

    private var _binding: FragmentProductOrderBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ProductOrderViewModel by viewModels()
    private lateinit var adapter: ProductOrderAdapter

    // Cache latest lists so we can re-submit whenever either changes
    private var currentActive:   List<Product> = emptyList()
    private var currentInactive: List<Product> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProductOrderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        setupRecycler()
        setupSearch()
        setupEditControls()
        observeViewModel()
    }

    // ── RecyclerView ──────────────────────────────────────────────

    private fun setupRecycler() {
        adapter = ProductOrderAdapter(
            onVisualReorder = { viewModel.onVisualReorder(it) },
            onLabelTyped    = { id, num -> viewModel.onLabelTyped(id, num) },
            getDraftLabel   = { id -> viewModel.getDraftLabel(id) },
            onDeactivate    = { product -> showDeactivateDialog(product) },
            onActivate      = { product -> showActivateDialog(product) }
        )

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            // Long-press drag only when editing AND the item is an active product
            override fun isLongPressDragEnabled() = adapter.editEnabled

            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                return if (adapter.editEnabled && adapter.isActiveAt(vh.bindingAdapterPosition))
                    makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
                else 0
            }

            override fun onMove(
                rv:   RecyclerView,
                from: RecyclerView.ViewHolder,
                to:   RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = from.bindingAdapterPosition
                val toPos   = to.bindingAdapterPosition
                if (!adapter.editEnabled ||
                    !adapter.isActiveAt(fromPos) ||
                    !adapter.isActiveAt(toPos)) return false
                adapter.onItemMoved(fromPos, toPos)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}

            override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, state: Int) {
                super.onSelectedChanged(vh, state)
                if (state == ItemTouchHelper.ACTION_STATE_DRAG) vh?.itemView?.alpha = 0.85f
            }

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                vh.itemView.alpha = 1f
                adapter.onDragFinished()
            }
        })

        adapter.touchHelper = touchHelper
        touchHelper.attachToRecyclerView(binding.recyclerProducts)
        binding.recyclerProducts.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerProducts.adapter = adapter
        adapter.recyclerView = binding.recyclerProducts

        ScrollNavigationHelper.setup(
            recyclerView   = binding.recyclerProducts,
            fabTop         = binding.fabScrollTop,
            fabBottom      = binding.fabScrollBottom,
            lifecycleOwner = viewLifecycleOwner,
            dataReady      = viewModel.displayList
        )
    }

    // ── Search ────────────────────────────────────────────────────

    private fun setupSearch() {
        binding.searchIcon.setOnClickListener {
            binding.searchBar.visibility = View.VISIBLE
            binding.searchInput.requestFocus()
            showKeyboard(binding.searchInput)
        }

        binding.searchClear.setOnClickListener { closeSearch() }

        binding.searchInput.addTextChangedListener { text ->
            viewModel.setSearchQuery(text?.toString() ?: "")
        }

        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false
        }
    }

    private fun closeSearch() {
        binding.searchInput.text?.clear()
        binding.searchBar.visibility = View.GONE
        hideKeyboard()
        viewModel.clearSearch()
    }

    // ── Edit toggle + Renumber & Save ─────────────────────────────

    private fun setupEditControls() {
        binding.switchEditOrder.setOnCheckedChangeListener { _, isChecked ->
            closeSearch()
            viewModel.setEditEnabled(isChecked)
        }

        binding.btnRenumberSave.setOnClickListener {
            hideKeyboard()
            viewModel.renumberAndSave()
        }

        binding.btnDiscard.setOnClickListener {
            hideKeyboard()
            viewModel.setEditEnabled(false)
            binding.switchEditOrder.isChecked = false
        }
    }

    // ── Observe ───────────────────────────────────────────────────

    private fun observeViewModel() {

        viewModel.displayList.observe(viewLifecycleOwner) { list ->
            currentActive = list
            adapter.submitLists(currentActive, currentInactive)
        }

        viewModel.inactiveList.observe(viewLifecycleOwner) { list ->
            currentInactive = list
            adapter.submitLists(currentActive, currentInactive)
        }

        viewModel.editEnabled.observe(viewLifecycleOwner) { editing ->
            adapter.editEnabled = editing
            binding.editControlsRow.visibility = if (editing) View.VISIBLE else View.GONE
            binding.searchIcon.isEnabled = true
            binding.searchIcon.alpha = 1f
            binding.textHint.text = if (editing)
                "Type any # in badge · ▲▼ nudge · long-press drag — duplicates OK, just get order right"
            else
                "Active: long-press to deactivate · Inactive: long-press to re-activate · toggle ✏ to edit order"

            if (binding.switchEditOrder.isChecked != editing)
                binding.switchEditOrder.isChecked = editing
        }

        viewModel.saveStatus.observe(viewLifecycleOwner) { status ->
            when (status) {
                is ProductOrderViewModel.SaveStatus.Saving ->
                    binding.btnRenumberSave.isEnabled = false
                is ProductOrderViewModel.SaveStatus.Success -> {
                    binding.btnRenumberSave.isEnabled = true
                    Snackbar.make(binding.root, "Order saved ✓", Snackbar.LENGTH_SHORT).show()
                    viewModel.clearSaveStatus()
                }
                is ProductOrderViewModel.SaveStatus.Error -> {
                    binding.btnRenumberSave.isEnabled = true
                    Snackbar.make(binding.root, "Save failed: ${status.message}", Snackbar.LENGTH_LONG).show()
                    viewModel.clearSaveStatus()
                }
                null -> binding.btnRenumberSave.isEnabled = true
            }
        }

        viewModel.scrollToTop.observe(viewLifecycleOwner) { shouldScroll ->
            if (shouldScroll) {
                binding.recyclerProducts.scrollToPosition(0)
                viewModel.clearScrollToTop()
            }
        }

        viewModel.deactivateBlocked.observe(viewLifecycleOwner) { name ->
            if (name == null) return@observe
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Cannot Deactivate")
                .setMessage(
                    "\"$name\" has a non-zero closing balance in Daily Stock.\n\n" +
                    "Reduce the closing balance to zero on the most recent committed date " +
                    "before deactivating this product."
                )
                .setPositiveButton("OK", null)
                .show()
            viewModel.clearDeactivateBlocked()
        }
    }

    // ── Activate / Deactivate dialogs ─────────────────────────────

    private fun showDeactivateDialog(product: Product) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Deactivate product?")
            .setMessage("\"${product.displayName}\" will be hidden from Daily Stock and moved to the Inactive section below.")
            .setPositiveButton("Deactivate") { _, _ -> viewModel.deactivateProduct(product) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showActivateDialog(product: Product) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Re-activate product?")
            .setMessage("\"${product.displayName}\" will appear in Daily Stock again and be added to the end of the active list.")
            .setPositiveButton("Re-activate") { _, _ -> viewModel.activateProduct(product) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Keyboard helpers ──────────────────────────────────────────

    private fun showKeyboard(view: View) {
        val imm = ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
        imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
