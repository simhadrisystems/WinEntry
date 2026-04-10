package com.simple.simpleinventory.ui.dailystock

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.simple.simpleinventory.R
import com.simple.simpleinventory.data.entity.DailyEntry
import com.simple.simpleinventory.data.entity.stockCode
import com.simple.simpleinventory.databinding.FragmentDailyStockBinding
import com.simple.simpleinventory.utils.DailyStockExcelHelper
import com.simple.simpleinventory.utils.DailyStockImportHelper
import com.simple.simpleinventory.ui.dailystock.DailyStockDataViewModel
import com.simple.simpleinventory.ui.dailystock.DayReconciliationViewModel  // ← NEW: import for reconciliation dialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class DailyStockFragment : Fragment() {

    private var _binding: FragmentDailyStockBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DailyStockViewModel by viewModels()
    private val dataViewModel: DailyStockDataViewModel by viewModels()
    private lateinit var adapter: DailyEntryAdapter
    private lateinit var saleTotalAdapter: SaleTotalAdapter
    private lateinit var reconciliationFooterAdapter: StaticFooterAdapter
    private var currentTotalSale: Double = 0.0
    // Direct reference to footer views — set in wireFooter, used to push
    // live total immediately without waiting for LiveData re-emission.
    private var footerTextTotal:           android.widget.TextView? = null
    private var footerTextCash:            android.widget.TextView? = null
    private var footerFormatRupee:         ((Double) -> String)?    = null
    private var footerTextPurchaseSummary: android.widget.TextView? = null
    private var footerTextSoldUnits:       android.widget.TextView? = null
    private lateinit var excelHelper: DailyStockExcelHelper
    private lateinit var importHelper: DailyStockImportHelper

    private val reconciliationViewModel: DayReconciliationViewModel by viewModels() // ← NEW: ViewModel for the reconciliation dialog

    // ── Animation-aware first load ────────────────────────────────
    // DB load starts immediately (parallel with the enter animation).
    // Results are buffered here and applied only after the animation
    // completes — so RecyclerView layout never competes with the transition.
    private var holdForAnimation = false
    private var pendingEntries: List<com.simple.simpleinventory.data.entity.DailyEntry>? = null
    private lateinit var concatAdapter: ConcatAdapter
    private var footerAdded = false

    private val repository by lazy {
        val db = com.simple.simpleinventory.data.AppDatabase.getInstance(requireContext())
        com.simple.simpleinventory.data.repository.DailyStockRepository(
            productDao    = db.productDao(),
            dailyStockDao = db.dailyStockDao()
        )
    }

    // ── Activity result launchers ─────────────────────────────────
    private val importClosingLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK)
            result.data?.data?.let { uri -> importClosingFromExcel(uri) }
    }

    // ── Lifecycle ─────────────────────────────────────────────────
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding    = FragmentDailyStockBinding.inflate(inflater, container, false)
        excelHelper = DailyStockExcelHelper(requireContext())
        importHelper = DailyStockImportHelper(requireContext())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.hide()
        setupToolbarMenu()
        setupRecyclerView()
        setupEntryModeSelector()
        setupDateButton()
        setupDateNavArrows()     // ← NEW: ‹ › arrows beside date button
        setupSearchBar()
        setupSaveButton()
        setupBackPressGuard()
        setupClosingDialogResult()   // ← NEW: register FragmentResult listener
        observeViewModel()
        setupReconciliationFooter() // ← NEW: setup for the reconciliation footer included at the bottom of the product list
        setupScrollNavigation()
        setupImportObserver()   // ← rotation-safe import progress observer
    }

    override fun onDestroyView() {
        (activity as? AppCompatActivity)?.supportActionBar?.show()
        _binding = null
        super.onDestroyView()
    }

    // ═══════════════════════════════════════════════════════════════
    // TOOLBAR MENU
    // ═══════════════════════════════════════════════════════════════
    private fun setupToolbarMenu() {
        // Back / home navigation arrow on the toolbar
        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener {
            if (viewModel.hasUnsavedChanges.value == true) {
                showUnsavedChangesDialog(
                    onSave    = { viewModel.saveAllEntries(); findNavController().navigateUp() },
                    onDiscard = { findNavController().navigateUp() }
                )
            } else {
                findNavController().navigateUp()
            }
        }

        // Overflow ⋮ button — shows popup menu with all actions + Purchase Entry mode
        binding.menuButton.setOnClickListener { anchor ->
            val popup = androidx.appcompat.widget.PopupMenu(requireContext(), anchor)
            popup.menuInflater.inflate(R.menu.menu_daily_stock, popup.menu)
            // Add Purchase Entry as a menu item at the top
            val PURCHASE_MODE_ID       = 0x7fff0001
            val RESTORE_FROM_CLOUD_ID  = 0x7fff0002
            popup.menu.add(0, PURCHASE_MODE_ID,      0, "📦 Purchase Entry")
            popup.menu.add(0, RESTORE_FROM_CLOUD_ID, 1, "☁️ Restore Daily Stock from Cloud")
            // Total sale shown as scrollable header above the product list
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    PURCHASE_MODE_ID -> {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("📦 Purchase Entry")
                            .setMessage(
                                "Purchase entries are managed in the Purchases module.\n\n" +
                                "Purchases are reflected automatically in the PQ row as soon as they are entered."
                            )
                            .setPositiveButton("OK", null)
                            .show()
                        true
                    }
                    RESTORE_FROM_CLOUD_ID -> {
                        com.simple.simpleinventory.sync.SyncHelper.downloadDailyStockFromCloud(
                            context    = requireContext(),
                            scope      = lifecycleScope,
                            anchorView = binding.root
                        ) {
                            // Refresh Daily Stock screen after restore
                            viewModel.loadEntriesForDate()
                        }
                        true
                    }
                    else -> {
                        if (viewModel.hasUnsavedChanges.value == true) {
                            showUnsavedChangesDialog(
                                onSave    = { viewModel.saveAllEntries() },
                                onDiscard = { performMenuAction(item.itemId) }
                            )
                            return@setOnMenuItemClickListener true
                        }
                        performMenuAction(item.itemId)
                        true
                    }
                }
            }
            popup.show()
        }
    }

    private fun performMenuAction(itemId: Int): Boolean {
        return when (itemId) {
            R.id.action_export_daily_stock        -> { exportDailyStock();         true }
            R.id.action_export_current_date       -> { exportCurrentDate();        true }
            R.id.action_export_date_range         -> { exportDateRange();          true }
            R.id.action_download_closing_template -> { downloadClosingTemplate();  true }
            R.id.action_import_closing            -> { importClosing();            true }
            R.id.action_clear_date_data           -> { clearCurrentDateData();     true }
            R.id.action_clear_all_daily_stock     -> { clearAllDailyStock();       true }
            else -> false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // BACK PRESS GUARD
    // ═══════════════════════════════════════════════════════════════
    private fun setupBackPressGuard() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            if (viewModel.hasUnsavedChanges.value == true) {
                showUnsavedChangesDialog(
                    onSave    = { viewModel.saveAllEntries() },
                    onDiscard = {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                )
            } else {
                isEnabled = false
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    private fun showUnsavedChangesDialog(onSave: () -> Unit, onDiscard: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Unsaved Changes")
            .setMessage("You have unsaved data entry changes.\nWhat would you like to do?")
            .setPositiveButton("Save Now")    { _, _ -> onSave() }
            .setNegativeButton("Discard")     { _, _ -> onDiscard() }
            .setNeutralButton("Keep Editing", null)
            .setCancelable(true)
            .show()
    }

    // ═══════════════════════════════════════════════════════════════
    // RECYCLERVIEW + ADAPTER
    // ═══════════════════════════════════════════════════════════════
    private fun setupRecyclerView() {
        adapter = DailyEntryAdapter(
            onPurchaseChanged = { productId, size, value ->
                viewModel.updatePurchase(productId, size, value)
            },
            onClosingChanged = { productId, size, value ->
                viewModel.updateClosing(productId, size, value)
            },
            getEntryMode = {
                viewModel.entryMode.value ?: DailyStockViewModel.EntryMode.BALANCE
            },
            onNextFromLastField = { currentPosition ->
                val recycler = binding.productRecyclerView
                val mode     = viewModel.entryMode.value ?: DailyStockViewModel.EntryMode.VIEW
                if (mode == DailyStockViewModel.EntryMode.VIEW) return@DailyEntryAdapter

                // Find the next card (from currentPosition+1 onwards) that has at least
                // one enabled field. Skip cards with no editable stock entirely.
                val list = adapter.currentList
                var targetPosition = -1
                for (pos in (currentPosition + 1)..list.lastIndex) {
                    val entry = list[pos]
                    val hasEditableField = when (mode) {
                        DailyStockViewModel.EntryMode.PURCHASE -> true  // purchase fields always enabled
                        DailyStockViewModel.EntryMode.BALANCE  ->
                            (entry.opening.qq + entry.purchase.qq) > 0 ||
                            (entry.opening.pp + entry.purchase.pp) > 0 ||
                            (entry.opening.nn + entry.purchase.nn) > 0 ||
                            (entry.opening.dd + entry.purchase.dd) > 0
                        else -> false
                    }
                    if (hasEditableField) { targetPosition = pos; break }
                }
                if (targetPosition < 0) return@DailyEntryAdapter  // no more editable cards

                // ConcatAdapter offset: saleTotalAdapter sits before DailyEntryAdapter
                // so RecyclerView global position = targetPosition + saleTotalAdapter.itemCount
                val rvPosition = targetPosition + saleTotalAdapter.itemCount

                val fieldIds = when (mode) {
                    DailyStockViewModel.EntryMode.PURCHASE ->
                        listOf(R.id.editPurchaseQQ, R.id.editPurchasePP,
                               R.id.editPurchaseNN, R.id.editPurchaseDD)
                    DailyStockViewModel.EntryMode.BALANCE  ->
                        listOf(R.id.editClosingQQ, R.id.editClosingPP,
                               R.id.editClosingNN, R.id.editClosingDD)
                    else -> emptyList()
                }

                fun focusFirstField() {
                    val holder = recycler.findViewHolderForAdapterPosition(rvPosition)
                    val item   = holder?.itemView ?: return
                    val target = fieldIds.mapNotNull { item.findViewById<EditText>(it) }
                                         .firstOrNull { it.isEnabled && it.isFocusable }
                                         ?: return
                    target.requestFocus()
                    target.selectAll()
                }

                // Clear focus from current field first so keyboard doesn't snap back
                recycler.clearFocus()

                val lm = recycler.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                val firstVisible = lm?.findFirstCompletelyVisibleItemPosition() ?: 0
                val lastVisible  = lm?.findLastCompletelyVisibleItemPosition() ?: 0

                if (rvPosition in firstVisible..lastVisible) {
                    // Card already fully visible — focus immediately
                    recycler.post { focusFirstField() }
                } else {
                    // Card off-screen — scroll then focus once idle
                    recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                        override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                            if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                                rv.removeOnScrollListener(this)
                                rv.post { focusFirstField() }
                            }
                        }
                    })
                    recycler.smoothScrollToPosition(rvPosition)
                }
            },
            onDataChanged     = { productId -> viewModel.markProductDirty(productId) },
            onEditClosing     = { entry -> launchClosingDialog(entry) },
            onSaveProduct     = { productId -> viewModel.saveProductEntry(productId) },
            onDiscardProduct  = { productId -> confirmDiscardProduct(productId) },
            isProductDirty    = { productId -> viewModel.isProductDirty(productId) }
        )

        saleTotalAdapter = SaleTotalAdapter()

        reconciliationFooterAdapter = StaticFooterAdapter(layoutInflater)

        concatAdapter = ConcatAdapter(
            saleTotalAdapter,
            this@DailyStockFragment.adapter
            // footer added in applyEntries on first non-empty result
        )
        binding.productRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = concatAdapter
            // Disable the change animation — it causes card shadow/border to flash
            // when values are updated via DiffUtil payloads. Add/remove animations
            // (for search filter) still work because we only suppress changeDuration.
            (itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
                ?.supportsChangeAnimations = false
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CLOSING ENTRY DIALOG  ← NEW SECTION
    // ═══════════════════════════════════════════════════════════════

    /** Launch the boxes+loose dialog for a single product card. */
    private fun launchClosingDialog(entry: DailyEntry) {
        // Always fetch the live entry from entriesCache — the adapter's entry
        // may be stale if the user has typed a new CB value since the last
        // LiveData emission (especially on red-flagged cards where imports
        // left erroneous values that haven't been saved yet).
        val liveEntry = viewModel.entriesCache[entry.product.id] ?: entry
        ClosingEntryDialog.newInstance(liveEntry)
            .show(childFragmentManager, ClosingEntryDialog.TAG)
    }

    /**
     * Listen for the result fired by ClosingEntryDialog on Save.
     * Updates all four CB sizes in the ViewModel and saves immediately —
     * no separate global Save tap needed.
     */
    private fun setupClosingDialogResult() {
        childFragmentManager.setFragmentResultListener(
            ClosingEntryDialog.REQUEST_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            val productId = bundle.getLong(ClosingEntryDialog.KEY_PRODUCT_ID)
            viewModel.updateClosing(productId, "QQ", bundle.getInt(ClosingEntryDialog.KEY_QQ))
            viewModel.updateClosing(productId, "PP", bundle.getInt(ClosingEntryDialog.KEY_PP))
            viewModel.updateClosing(productId, "NN", bundle.getInt(ClosingEntryDialog.KEY_NN))
            viewModel.updateClosing(productId, "DD", bundle.getInt(ClosingEntryDialog.KEY_DD))
            // Save only this one product — not all 380 records
            viewModel.saveProductEntry(productId)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ENTRY MODE SELECTOR
    // ═══════════════════════════════════════════════════════════════
    private fun setupEntryModeSelector() {
        // Mode toggle button cycles: View → CB Entry → View
        updateModeToggleButton(viewModel.entryMode.value ?: DailyStockViewModel.EntryMode.VIEW)
        binding.btnModeToggle.setOnClickListener {
            val current = viewModel.entryMode.value ?: DailyStockViewModel.EntryMode.VIEW
            val next = if (current == DailyStockViewModel.EntryMode.VIEW)
                DailyStockViewModel.EntryMode.BALANCE
            else
                DailyStockViewModel.EntryMode.VIEW

            if (next == DailyStockViewModel.EntryMode.VIEW &&
                viewModel.hasUnsavedChanges.value == true) {
                showUnsavedChangesDialog(
                    onSave    = { viewModel.saveAllEntries(); performModeSwitch(next) },
                    onDiscard = { performModeSwitch(next) }
                )
            } else {
                performModeSwitch(next)
            }
        }
    }

    private fun performModeSwitch(mode: DailyStockViewModel.EntryMode) {
        viewModel.setEntryMode(mode)
        updateModeToggleButton(mode)
        adapter.notifyDataSetChanged()
    }

    private fun updateModeToggleButton(mode: DailyStockViewModel.EntryMode) {
        // Button is exactly 42dp wide (fixed in XML) — never reflows regardless of text.
        binding.btnModeToggle.apply {
            when (mode) {
                DailyStockViewModel.EntryMode.BALANCE -> {
                    text = "CB Entry"
                    backgroundTintList = android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#FF388E3C"))
                }
                else -> {
                    text = "View Only"
                    backgroundTintList = android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#44FFFFFF"))
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // DATE BUTTON
    // ═══════════════════════════════════════════════════════════════
    private fun setupDateButton() {
        binding.dateButton.setOnClickListener {
            if (viewModel.hasUnsavedChanges.value == true) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Unsaved Changes")
                    .setMessage("Changing the date will discard unsaved changes.\nSave first?")
                    .setPositiveButton("Save First")        { _, _ -> viewModel.saveAllEntries() }
                    .setNegativeButton("Discard & Change")  { _, _ -> openDatePicker() }
                    .setNeutralButton("Cancel", null)
                    .show()
            } else {
                openDatePicker()
            }
        }
    }

    private fun setupDateNavArrows() {
        binding.btnPrevDate.setOnClickListener {
            if (viewModel.hasUnsavedChanges.value == true) {
                showUnsavedChangesDialog(
                    onSave    = { viewModel.saveAllEntries() },
                    onDiscard = { viewModel.goToPreviousDate() }
                )
            } else {
                viewModel.goToPreviousDate()
            }
        }
        binding.btnNextDate.setOnClickListener {
            if (viewModel.hasUnsavedChanges.value == true) {
                showUnsavedChangesDialog(
                    onSave    = { viewModel.saveAllEntries() },
                    onDiscard = { viewModel.goToNextDate() }
                )
            } else {
                viewModel.goToNextDate()
            }
        }

        // Grey out Next button when at or beyond the max allowed date
        viewModel.selectedDate.observe(viewLifecycleOwner) { date ->
            val sdf    = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val maxCal = Calendar.getInstance().also {
                it.add(Calendar.DAY_OF_MONTH, DailyStockViewModel.MAX_FUTURE_DAYS)
            }
            val maxDate = sdf.format(maxCal.time)
            val atMax   = date >= maxDate
            binding.btnNextDate.isEnabled = !atMax
            binding.btnNextDate.alpha     = if (atMax) 0.35f else 1.0f
        }
    }

    private fun openDatePicker() {
        val calendar    = Calendar.getInstance()
        val currentDate = viewModel.selectedDate.value ?: ""
        if (currentDate.isNotEmpty()) {
            try {
                val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(currentDate)
                if (date != null) calendar.time = date
            } catch (e: Exception) { e.printStackTrace() }
        }

        val dlg = DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                viewModel.setDate(String.format("%04d-%02d-%02d", year, month + 1, day))
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        // Cap the maximum selectable date to today + MAX_FUTURE_DAYS
        val maxCal = Calendar.getInstance().also {
            it.add(Calendar.DAY_OF_MONTH, DailyStockViewModel.MAX_FUTURE_DAYS)
        }
        dlg.datePicker.maxDate = maxCal.timeInMillis
        dlg.setButton(DatePickerDialog.BUTTON_NEUTRAL, "Today") { _, which ->
            if (which == DatePickerDialog.BUTTON_NEUTRAL)
                viewModel.setDate(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()))
        }
        dlg.show()
    }

    // ═══════════════════════════════════════════════════════════════
    // SEARCH BAR
    // ═══════════════════════════════════════════════════════════════
    private fun setupSearchBar() {
        binding.searchIcon.setOnClickListener {
            if (binding.searchContainer.visibility == View.GONE) {
                expandSearch()
            } else {
                collapseSearch()
            }
        }

        binding.searchClear.setOnClickListener {
            binding.searchInput.setText("")
            binding.searchClear.visibility = View.GONE
        }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString() ?: ""
                binding.searchClear.visibility = if (q.isNotEmpty()) View.VISIBLE else View.GONE
                viewModel.setSearchQuery(q)
            }
        })

        binding.searchInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) binding.searchInput.post { binding.searchInput.selectAll() }
        }
        binding.searchInput.setOnClickListener { binding.searchInput.selectAll() }

        // Collapse search when user presses back
        binding.searchInput.setOnEditorActionListener { _, _, _ ->
            collapseSearch(); true
        }
    }

    private fun expandSearch() {
        // Slide the search container over the date + arrows using weight animation.
        // 1. Make the container visible and weight=1 so it fills the date area.
        // 2. Hide date button and prev/next arrows so search gets full space.
        binding.searchContainer.visibility = View.VISIBLE

        // Give searchContainer weight=1 so it expands into the space freed by hiding dateButton
        val containerParams = binding.searchContainer.layoutParams as android.widget.LinearLayout.LayoutParams
        containerParams.weight = 1f
        containerParams.width  = 0
        binding.searchContainer.layoutParams = containerParams

        // Also zero out the spacer weight so it doesn't steal space
        val spacerParams = binding.searchSpacer.layoutParams as android.widget.LinearLayout.LayoutParams
        spacerParams.weight = 0f
        spacerParams.width  = 0
        binding.searchSpacer.layoutParams = spacerParams

        // Hide date navigation to give search the full toolbar width
        binding.dateButton.visibility  = View.GONE
        binding.btnPrevDate.visibility = View.GONE
        binding.btnNextDate.visibility = View.GONE

        // Slide in from right: start off-screen right, animate to position
        binding.searchContainer.translationX = binding.searchContainer.width.toFloat().coerceAtLeast(300f)
        binding.searchContainer.animate()
            .translationX(0f)
            .setDuration(200)
            .start()

        binding.searchInput.requestFocus()
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun collapseSearch() {
        // Slide out to the right then hide
        binding.searchContainer.animate()
            .translationX(binding.searchContainer.width.toFloat().coerceAtLeast(300f))
            .setDuration(180)
            .withEndAction {
                binding.searchContainer.visibility = View.GONE
                binding.searchContainer.translationX = 0f

                // Restore weight=0 so it takes no space
                val containerParams = binding.searchContainer.layoutParams
                    as android.widget.LinearLayout.LayoutParams
                containerParams.weight = 0f
                binding.searchContainer.layoutParams = containerParams

                // Restore date + arrows
                binding.dateButton.visibility  = View.VISIBLE
                binding.btnPrevDate.visibility = View.VISIBLE
                binding.btnNextDate.visibility = View.VISIBLE
            }
            .start()

        binding.searchInput.setText("")
        viewModel.setSearchQuery("")
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }

    // ═══════════════════════════════════════════════════════════════
    // PER-PRODUCT DISCARD
    // ═══════════════════════════════════════════════════════════════
    private fun confirmDiscardProduct(productId: Long) {
        // Look up display name from the adapter's current list — always up to date
        val name = adapter.currentList
            .find { it.product.id == productId }?.product?.displayName ?: "this product"
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Discard Changes")
            .setMessage("Discard unsaved CB edits for $name?")
            .setPositiveButton("Discard") { _, _ -> viewModel.discardProductEdits(productId) }
            .setNegativeButton("Keep", null)
            .show()
    }

    // ═══════════════════════════════════════════════════════════════
    // SAVE BUTTON
    // ═══════════════════════════════════════════════════════════════
    private fun setupSaveButton() {
        binding.saveButton.setOnClickListener {
            binding.productRecyclerView.clearFocus()
            binding.saveButton.postDelayed({ viewModel.saveAllEntries() }, 100)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // VIEWMODEL OBSERVERS
    // ═══════════════════════════════════════════════════════════════
    private fun observeViewModel() {
        viewModel.allProducts.observe(viewLifecycleOwner) { products ->
            if (products.isNotEmpty()) {
                if (viewModel.isCacheEmpty()) {
                    // First navigation to this screen:
                    // Start DB load immediately (runs in parallel with the 300ms animation),
                    // but hold LiveData results until the animation is done so the RecyclerView
                    // never lays out during the slide transition.
                    holdForAnimation = true
                    viewModel.initializeEntries(products)
                    viewLifecycleOwner.lifecycleScope.launch {
                        delay(320)
                        holdForAnimation = false
                        pendingEntries?.let { applyEntries(it); pendingEntries = null }
                    }
                } else {
                    // Rotation or product list change — cache exists, deliver immediately
                    viewModel.initializeEntries(products)
                }
            }
        }

        viewModel.dailyEntries.observe(viewLifecycleOwner) { entries ->
            if (holdForAnimation) {
                pendingEntries = entries   // DB finished early — hold until animation done
            } else {
                applyEntries(entries)
            }
        }

        // When a product becomes dirty (or clean after save), update only that card's
        // name row to show/hide the amber highlight.
        // IMPORTANT: never call notifyDataSetChanged() here — that would rebind every
        // card and destroy any EditText the user is currently typing in.
        var previousDirtyIds = emptySet<Long>()
        viewModel.dirtyProductIds.observe(viewLifecycleOwner) { newDirtyIds ->
            val changed = (newDirtyIds - previousDirtyIds) + (previousDirtyIds - newDirtyIds)
            previousDirtyIds = newDirtyIds
            changed.forEach { productId ->
                binding.productRecyclerView.post {
                    val pos = adapter.currentList.indexOfFirst { it.product.id == productId }
                    if (pos >= 0) adapter.notifyItemChanged(pos, DailyEntryAdapter.PAYLOAD_DIRTY_CHANGED)
                }
            }
        }

        // Force full rebind of a card after discard, so EditText fields revert to DB values.
        // We need notifyItemChanged WITHOUT payload — payload path skips EditText.setText().
        viewModel.discardedProductId.observe(viewLifecycleOwner) { productId ->
            if (productId == null) return@observe
            val pos = adapter.currentList.indexOfFirst { it.product.id == productId }
            if (pos >= 0) adapter.notifyItemChanged(pos)   // no payload = full bind()
        }

        // Red banner removed — Global Save button colour conveys unsaved state instead.
        // Keep banner always gone.
        binding.unsavedBanner.visibility = android.view.View.GONE

        // Global Save button: green + enabled when dirty, grey + disabled when clean
        viewModel.hasUnsavedChanges.observe(viewLifecycleOwner) { hasChanges ->
            binding.saveButton.isEnabled = hasChanges
            binding.saveButton.backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    if (hasChanges)
                        android.graphics.Color.parseColor("#FF388E3C")   // green — unsaved
                    else
                        android.graphics.Color.parseColor("#FF9E9E9E")   // grey  — all saved
                )
        }

        viewModel.selectedDate.observe(viewLifecycleOwner) { date ->
            binding.dateButton.text = formatDateForDisplay(date)
            viewModel.loadDateStatus()
        }

        viewModel.dateStatus.observe(viewLifecycleOwner) { status ->
            updateDateButtonStatus(status)
        }

        viewModel.saveStatus.observe(viewLifecycleOwner) { status ->
            when (status) {
                null -> {
                    // Button enabled state is owned by hasUnsavedChanges observer
                    binding.saveButton.text = "SAVE"
                }
                is DailyStockViewModel.SaveStatus.Saving -> {
                    binding.saveButton.isEnabled = false   // temporarily disable during save
                    binding.saveButton.text = "Saving..."
                }
                is DailyStockViewModel.SaveStatus.Success -> {
                    // button state is driven by hasUnsavedChanges observer — don't override here
                    binding.saveButton.text = "SAVE"
                    Toast.makeText(requireContext(), "✓ Saved ${status.count} records", Toast.LENGTH_SHORT).show()
                    viewModel.clearSaveStatus()
                }
                is DailyStockViewModel.SaveStatus.Error -> {
                    binding.saveButton.text = "SAVE"
                    Toast.makeText(requireContext(), "Error: ${status.message}", Toast.LENGTH_LONG).show()
                    viewModel.clearSaveStatus()
                }


                is DailyStockViewModel.SaveStatus.CascadeComplete -> {
                    val msg = "${status.updatedCount} subsequent records recalculated"
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    viewModel.clearSaveStatus()
                }
                is DailyStockViewModel.SaveStatus.BlockedByIntegrity -> {
                    // Parse violation format: "yyyy-MM-dd|dd/MM/yy|QQ(-5), PP(-2)"
                    val parts     = status.violations.firstOrNull()?.split("|")
                    val nextDate  = parts?.getOrNull(0) ?: ""
                    val nextDisp  = parts?.getOrNull(1) ?: nextDate
                    val sizes     = parts?.getOrNull(2) ?: ""
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("⛔ Save Blocked")
                        .setMessage(
                            "This change worsens the sale on $nextDisp ($sizes).\n\n" +
                            "Increase $nextDisp's closing balance first to reduce the negative, then return here."
                        )
                        .setPositiveButton("Go to $nextDisp") { _, _ ->
                            if (nextDate.isNotEmpty()) viewModel.setDate(nextDate)
                            viewModel.clearSaveStatus()
                        }
                        .setNeutralButton("Cancel Edit") { _, _ ->
                            viewModel.discardAllEdits()
                            viewModel.clearSaveStatus()
                        }
                        .setCancelable(false)
                        .show()
                }
                is DailyStockViewModel.SaveStatus.CascadeWithWarnings -> {
                    val dateList = status.problemDates.joinToString("\n  • ") {
                        try {
                            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                            val disp = java.text.SimpleDateFormat("dd/MM/yy", java.util.Locale.getDefault())
                            disp.format(sdf.parse(it)!!)
                        } catch (_: Exception) { it }
                    }
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("⚠ Closing Exceeds Stock on ${status.problemDates.size} Day(s)")
                        .setMessage(
                            "${status.updatedCount} record(s) recalculated.\n\n" +
                            "The following day(s) now have a closing balance that exceeds " +
                            "the available stock (opening + purchases). Sale shows as negative " +
                            "which is not valid — please open each day and correct the " +
                            "closing balance:\n\n  • $dateList"
                        )
                        .setPositiveButton("Go to First Problem Day") { _, _ ->
                            // Navigate to the first problem date so user sees the issue directly
                            status.problemDates.firstOrNull()?.let { date ->
                                viewModel.setDate(date)
                            }
                            viewModel.clearSaveStatus()
                        }
                        .setNeutralButton("Fix Later") { _, _ ->
                            viewModel.clearSaveStatus()
                        }
                        .show()
                }
            }
        }

        // Cascade confirmation dialog — registered once, outside saveStatus observer
        viewModel.cascadeRequest.observe(viewLifecycleOwner) { request ->
            if (request == null) return@observe
            val preview = request.preview
            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Update affected dates?")
                .setMessage(preview.dialogMessage)
                .setPositiveButton("Update") { _, _ ->
                    viewModel.confirmCascade(true)
                }
                .setNegativeButton("Skip") { _, _ ->
                    viewModel.confirmCascade(false)
                }
                .setCancelable(false)
                .show()
        }
    }

    /** Apply a batch of daily entries to the UI — called from the dailyEntries observer
     *  directly (date changes, saves) or after the animation guard releases on first load. */
    private fun applyEntries(entries: List<com.simple.simpleinventory.data.entity.DailyEntry>) {
        // Add footer once, here — never in a raw dailyEntries observer — so it
        // only appears after the animation guard releases, together with the list.
        if (!footerAdded && entries.isNotEmpty()) {
            concatAdapter.addAdapter(reconciliationFooterAdapter)
            footerAdded = true
        }
        adapter.submitList(entries)

        val total = entries.sumOf { entry ->
            entry.sale.qq * entry.product.qqSalePrice +
            entry.sale.pp * entry.product.ppSalePrice +
            entry.sale.nn * entry.product.nnSalePrice +
            entry.sale.dd * entry.product.ddSalePrice
        }
        currentTotalSale = total
        saleTotalAdapter.updateTotal(total)
        reconciliationViewModel.setTotalDaySales(total)
        footerFormatRupee?.let { fmt -> footerTextTotal?.text = fmt(total) }

        val pUnits = entries.sumOf { e ->
            e.purchase.qq + e.purchase.pp + e.purchase.nn + e.purchase.dd }
        val pValue = entries.sumOf { e ->
            e.purchase.qq * e.product.qqPurchasePrice +
            e.purchase.pp * e.product.ppPurchasePrice +
            e.purchase.nn * e.product.nnPurchasePrice +
            e.purchase.dd * e.product.ddPurchasePrice }
        footerTextPurchaseSummary?.let { tv ->
            if (pUnits > 0) {
                val valStr = footerFormatRupee?.invoke(pValue) ?: "₹${pValue.toLong()}"
                tv.text = "Purchase: $pUnits units  $valStr"
                tv.visibility = android.view.View.VISIBLE
            } else {
                tv.visibility = android.view.View.GONE
            }
        }

        val sUnits = entries.sumOf { e ->
            e.sale.qq + e.sale.pp + e.sale.nn + e.sale.dd }
        footerTextSoldUnits?.text =
            if (sUnits > 0) "$sUnits units sold today" else "System calculated"
    }

    /**
     * Wire the reconciliation footer card directly — no adapter, no RecyclerView lifecycle.
     * The footer is an <include>d plain View. LiveData observers update its child views
     * directly. TextWatchers are set once here and never re-attached. This eliminates
     * the notifyItemChanged → re-measure → shake cycle that plagued the adapter approach.
     */
    private fun setupReconciliationFooter() {
        reconciliationFooterAdapter.onViewReady = { b ->
            wireFooter(b)
        }
    }

    private fun wireFooter(footer: com.simple.simpleinventory.databinding.FooterDayReconciliationBinding) {
        val textDate            = footer.textReconciliationDate
        val textTotal           = footer.textTotalDaySales
        val textCash            = footer.textCashForDeposit
        val textPurchaseSummary = footer.textPurchaseSummary
        val textSoldUnits       = footer.textSoldUnitsSummary
        // Store refs so dailyEntries observer can push values directly
        footerTextTotal           = textTotal
        footerTextCash            = textCash
        footerTextPurchaseSummary = textPurchaseSummary
        footerTextSoldUnits       = textSoldUnits
        val editUpi        = footer.editUpiReceipts
        val editExpenses   = footer.editDayExpenses
        val editNotes      = footer.editNotes
        val btnSave        = footer.buttonSaveReconciliation
        val textLastSaved  = footer.textLastSaved

        var defaultCashColor = textCash.currentTextColor

        // ── Format helpers ────────────────────────────────────────────────────
        fun formatRupee(amount: Double): String {
            val abs    = Math.abs(amount).toLong()
            val prefix = if (amount < 0) "-₹ " else "₹ "
            if (abs == 0L) return "₹ 0"
            val s      = abs.toString()
            if (s.length <= 3) return prefix + s
            val last3  = s.takeLast(3)
            var rest   = s.dropLast(3)
            val groups = mutableListOf<String>()
            while (rest.length > 2) { groups.add(0, rest.takeLast(2)); rest = rest.dropLast(2) }
            groups.add(0, rest)
            return prefix + groups.joinToString(",") + ",$last3"
        }
        // Store formatter reference so dailyEntries observer can use it
        footerFormatRupee = ::formatRupee

        // Push current values immediately — LiveData may already have emitted
        currentTotalSale.takeIf { it > 0.0 }?.let { textTotal.text = formatRupee(it) }
        viewModel.dailyEntries.value?.let { entries ->
            val pu = entries.sumOf { it.purchase.qq + it.purchase.pp + it.purchase.nn + it.purchase.dd }
            val pv = entries.sumOf { it.purchase.qq * it.product.qqPurchasePrice +
                it.purchase.pp * it.product.ppPurchasePrice +
                it.purchase.nn * it.product.nnPurchasePrice +
                it.purchase.dd * it.product.ddPurchasePrice }
            if (pu > 0) {
                textPurchaseSummary.text = "Purchase: $pu units  ${formatRupee(pv)}"
                textPurchaseSummary.visibility = android.view.View.VISIBLE
            }
            val su = entries.sumOf { it.sale.qq + it.sale.pp + it.sale.nn + it.sale.dd }
            textSoldUnits.text = if (su > 0) "$su units sold today" else "System calculated"
        }

        // ── One-time listener setup — never re-attached ───────────────────────

        // Currency watcher: formats as ₹ 1,50,000 while typing, passes raw Double up
        fun makeCurrencyWatcher(field: android.widget.EditText, onChange: (Double) -> Unit) =
            object : android.text.TextWatcher {
                private var formatting = false
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (formatting) return
                    formatting = true
                    val raw    = s?.toString()?.replace("₹", "")?.replace(",", "")?.trim() ?: ""
                    val number = raw.toLongOrNull()
                    onChange(number?.toDouble() ?: 0.0)
                    if (number != null && number > 0) {
                        val fmt = formatRupee(number.toDouble())
                        field.setText(fmt)
                        field.setSelection(fmt.length)
                    } else if (raw.isEmpty() && s?.isNotEmpty() == true) {
                        field.setText("")
                    }
                    formatting = false
                }
            }

        editUpi.addTextChangedListener(makeCurrencyWatcher(editUpi) { reconciliationViewModel.updateUpi(it) })
        editExpenses.addTextChangedListener(makeCurrencyWatcher(editExpenses) { reconciliationViewModel.updateExpenses(it) })
        editNotes.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                reconciliationViewModel.updateNotes(s?.toString() ?: "")
            }
        })
        btnSave.setOnClickListener { reconciliationViewModel.save() }

        // ── LiveData observers — update views directly, no RecyclerView involved ──

        // Date display in header
        viewModel.selectedDate.observe(viewLifecycleOwner) { date ->
            textDate.text = try {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                val out = java.text.SimpleDateFormat("dd MMM yyyy (EEEE)", java.util.Locale.getDefault())
                out.format(sdf.parse(date) ?: java.util.Date())
            } catch (e: Exception) { date }
            reconciliationViewModel.loadForDate(date)
        }

        // Row A: total sales display.
        // observe() only delivers future emissions. If the value was already emitted
        // before wireFooter() ran (user scrolled to footer after data loaded), the
        // observer would miss it. We push the current value immediately after wiring.
        reconciliationViewModel.totalDaySales.observe(viewLifecycleOwner) { total ->
            textTotal.text = formatRupee(total)
        }

        reconciliationViewModel.cashForDeposit.observe(viewLifecycleOwner) { cash ->
            textCash.text = formatRupee(cash)
            if (defaultCashColor == 0) defaultCashColor = textCash.currentTextColor
            if (cash < 0) {
                val tv = android.util.TypedValue()
                requireContext().theme.resolveAttribute(
                    com.google.android.material.R.attr.colorError, tv, true
                )
                textCash.setTextColor(tv.data)
            } else {
                textCash.setTextColor(defaultCashColor)
            }
        }

        // Populate input fields when saved record loads or date changes
        reconciliationViewModel.reconciliation.observe(viewLifecycleOwner) { record ->
            val forceUpdate = reconciliationViewModel.consumeDateChanged()
            fun setAmount(field: android.widget.EditText, value: Double) {
                if (forceUpdate || !field.isFocused)
                    field.setText(if (value > 0) formatRupee(value) else "")
            }
            fun setText(field: android.widget.EditText, value: String) {
                if (forceUpdate || !field.isFocused) field.setText(value)
            }
            if (record != null) {
                setAmount(editUpi,      record.upiReceipts)
                setAmount(editExpenses, record.dayExpenses)
                setText(editNotes,      record.notes)
                val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                textLastSaved.text = "Last saved ${sdf.format(java.util.Date(record.lastModified))}"
            } else {
                if (forceUpdate || !editUpi.isFocused)      editUpi.setText("")
                if (forceUpdate || !editExpenses.isFocused) editExpenses.setText("")
                if (forceUpdate || !editNotes.isFocused)    editNotes.setText("")
                textLastSaved.text = ""
            }
        }

        // Save button state + toast
        reconciliationViewModel.saveStatus.observe(viewLifecycleOwner) { status ->
            when (status) {
                is DayReconciliationViewModel.SaveStatus.Saving -> {
                    btnSave.isEnabled = false
                    btnSave.text = "Saving..."
                }
                is DayReconciliationViewModel.SaveStatus.Success -> {
                    btnSave.isEnabled = true
                    btnSave.text = "Save reconciliation"
                    android.widget.Toast.makeText(
                        requireContext(), "Reconciliation saved",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    reconciliationViewModel.clearSaveStatus()
                }
                is DayReconciliationViewModel.SaveStatus.Error -> {
                    btnSave.isEnabled = true
                    btnSave.text = "Save reconciliation"
                    android.widget.Toast.makeText(
                        requireContext(), "Error: ${status.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    reconciliationViewModel.clearSaveStatus()
                }
                null -> {
                    btnSave.isEnabled = true
                    btnSave.text = "Save reconciliation"
                }
            }
        }
    }


    // ── Scroll navigation ────────────────────────────────────────────────────────

    private fun setupScrollNavigation() {
        com.simple.simpleinventory.ui.util.ScrollNavigationHelper.setup(
            recyclerView   = binding.productRecyclerView,
            fabTop         = binding.fabScrollTop,
            fabBottom      = binding.fabScrollBottom,
            lifecycleOwner = viewLifecycleOwner,
            dataReady      = viewModel.dailyEntries
        )

    }
        /** Short form "10-MAR" used in the toolbar title where space is tight. */
    private fun formatDateForDisplay(dateString: String): String {
        return try {
            val inFmt  = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val outFmt = SimpleDateFormat("dd-MMM", Locale.getDefault())
            inFmt.parse(dateString)?.let { outFmt.format(it).uppercase() } ?: dateString
        } catch (e: Exception) { dateString }
    }

    /** Long form "10 Mar 2026" used in dialog messages and export labels. */
    private fun formatDateLong(dateString: String): String {
        return try {
            val inFmt  = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val outFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            inFmt.parse(dateString)?.let { outFmt.format(it) } ?: dateString
        } catch (e: Exception) { dateString }
    }

    private fun updateDateButtonStatus(@Suppress("UNUSED_PARAMETER") status: DailyStockViewModel.DateStatus) {
        binding.dateButton.apply {
            // Subtle tint: transparent base with a hint of the status colour as text colour
            setTextColor(android.graphics.Color.WHITE)
            // Show status as a tiny suffix so the date stays the dominant text
            val dateStr = viewModel.selectedDate.value?.let { formatDateForDisplay(it) } ?: "Select Date"
            text = dateStr
            // No compound drawable — the date IS the title
            setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // EXPORT — DAILY STOCK (full sheet)
    // ═══════════════════════════════════════════════════════════════
    private fun exportDailyStock() {
        val date    = viewModel.selectedDate.value ?: return
        val entries = viewModel.dailyEntries.value ?: return
        if (entries.isEmpty()) {
            Toast.makeText(requireContext(), "No data to export for this date", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(requireContext()).apply { hint = "Enter title (e.g., Shop Name)" }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Export Daily Stock")
            .setMessage("Enter a custom title for the Excel sheet:")
            .setView(input)
            .setPositiveButton("Export") { _, _ ->
                val title = input.text.toString().ifBlank { "DAILY STOCK REPORT" }
                performExport(date, entries, title)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performExport(date: String, entries: List<DailyEntry>, customTitle: String) {
        try {
            val fileName = "DailyStock_${date.replace("-", "")}.xlsx"
            val uri = excelHelper.exportDailySaleSheet(date, entries, customTitle, fileName)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Export Daily Stock"))
            Toast.makeText(requireContext(), "Exported: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // EXPORT — CLOSING BALANCES FOR CURRENT DATE
    // ═══════════════════════════════════════════════════════════════
    private fun exportCurrentDate() {
        val date    = viewModel.selectedDate.value ?: return
        val entries = viewModel.dailyEntries.value ?: return

        if (entries.isEmpty()) {
            Toast.makeText(requireContext(), "No data for $date", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val workbook = XSSFWorkbook()
            val sheet    = workbook.createSheet("Closing")

            val headerStyle = workbook.createCellStyle()
            val headerFont  = workbook.createFont()
            headerFont.bold = true
            headerStyle.setFont(headerFont)

            val headerRow = sheet.createRow(0)
            arrayOf("DATE_CLOSING","PRODUCT_TYPE","BRAND_CODE","PRODUCT_NAME",
                "QQ_CLOSING","PP_CLOSING","NN_CLOSING","DD_CLOSING")
                .forEachIndexed { col, h ->
                    headerRow.createCell(col).apply { setCellValue(h); cellStyle = headerStyle }
                }

            var rowIndex = 1
            entries.forEach { entry ->
                val type        = entry.product.productType
                val brand       = entry.product.brandCode
                val productName = entry.product.displayName

                val qqClosing = entry.closing.qq
                val ppClosing = entry.closing.pp
                val nnClosing = entry.closing.nn
                val ddClosing = entry.closing.dd

                if (qqClosing > 0 || ppClosing > 0 || nnClosing > 0 || ddClosing > 0) {
                    val row = sheet.createRow(rowIndex++)
                    row.createCell(0).setCellValue(date)
                    row.createCell(1).setCellValue(type)
                    row.createCell(2).setCellValue(brand)
                    row.createCell(3).setCellValue(productName)
                    row.createCell(4).setCellValue(qqClosing.toDouble())
                    row.createCell(5).setCellValue(ppClosing.toDouble())
                    row.createCell(6).setCellValue(nnClosing.toDouble())
                    row.createCell(7).setCellValue(ddClosing.toDouble())
                }
            }

            sheet.setColumnWidth(0, 3200)
            sheet.setColumnWidth(1, 2800)
            sheet.setColumnWidth(2, 3200)
            sheet.setColumnWidth(3, 7000)
            sheet.setColumnWidth(4, 2800)
            sheet.setColumnWidth(5, 2800)
            sheet.setColumnWidth(6, 2800)
            sheet.setColumnWidth(7, 2800)

            val fileName = "ClosingBalance_${date.replace("-", "")}.xlsx"
            val file = File(requireContext().getExternalFilesDir(null), fileName)
            FileOutputStream(file).use { workbook.write(it) }
            workbook.close()

            val uri = FileProvider.getUriForFile(requireContext(),
                "${requireContext().packageName}.fileprovider", file)

            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Save Closing Balances"))

            Toast.makeText(requireContext(), "✓ Exported ${rowIndex - 1} products", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            android.util.Log.e("DailyStock", "exportCurrentDate error", e)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // EXPORT — CLOSING BALANCES DATE RANGE
    // ═══════════════════════════════════════════════════════════════
    private fun exportDateRange() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Export Date Range")
            .setMessage("Select start and end dates for export")
            .setPositiveButton("Select Start Date") { dialog, _ ->
                dialog.dismiss()
                showDatePickerForExport("Start Date") { startDate ->
                    showDatePickerForExport("End Date") { endDate ->
                        confirmDateRangeExport(startDate, endDate)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDatePickerForExport(title: String, onDateSelected: (String) -> Unit) {
        val cal = Calendar.getInstance()
        val dlg = DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                onDateSelected(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day))
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        )
        dlg.setTitle(title)
        dlg.setButton(DatePickerDialog.BUTTON_NEUTRAL, "Today") { _, which ->
            if (which == DatePickerDialog.BUTTON_NEUTRAL)
                onDateSelected(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()))
        }
        dlg.show()
    }

    private fun confirmDateRangeExport(startDate: String, endDate: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Confirm Export")
            .setMessage("Export closing balances from:\n${formatDateLong(startDate)} to ${formatDateLong(endDate)}?")
            .setPositiveButton("Export") { _, _ -> performDateRangeExport(startDate, endDate) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performDateRangeExport(startDate: String, endDate: String) {
        lifecycleScope.launch {
            try {
                // Read all closing data directly from DB — no LiveData, no delay(), no race condition.
                val rows = dataViewModel.getClosingRowsForExport(startDate, endDate)

                if (rows.isEmpty()) {
                    Toast.makeText(requireContext(), "No data in range", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val workbook = XSSFWorkbook()
                val sheet    = workbook.createSheet("Closing_Balances")

                val headerStyle = workbook.createCellStyle()
                val headerFont  = workbook.createFont()
                headerFont.bold = true
                headerStyle.setFont(headerFont)

                val headerRow = sheet.createRow(0)
                arrayOf("DATE_CLOSING","PRODUCT_TYPE","BRAND_CODE","PRODUCT_NAME",
                    "QQ_CLOSING","PP_CLOSING","NN_CLOSING","DD_CLOSING")
                    .forEachIndexed { col, h ->
                        headerRow.createCell(col).apply { setCellValue(h); cellStyle = headerStyle }
                    }

                var rowIndex   = 1
                var lastDate   = ""
                rows.forEach { row ->
                    // Blank separator row between dates for readability
                    if (lastDate.isNotEmpty() && row.date != lastDate) {
                        sheet.createRow(rowIndex++)  // blank separator
                    }
                    lastDate = row.date

                    val excelRow = sheet.createRow(rowIndex++)
                    excelRow.createCell(0).setCellValue(row.date)
                    excelRow.createCell(1).setCellValue(row.productType)
                    excelRow.createCell(2).setCellValue(row.brandCode)
                    excelRow.createCell(3).setCellValue(row.productName)
                    excelRow.createCell(4).setCellValue(row.qq.toDouble())
                    excelRow.createCell(5).setCellValue(row.pp.toDouble())
                    excelRow.createCell(6).setCellValue(row.nn.toDouble())
                    excelRow.createCell(7).setCellValue(row.dd.toDouble())
                }

                listOf(3200, 2800, 3200, 7000, 2800, 2800, 2800, 2800)
                    .forEachIndexed { i, w -> sheet.setColumnWidth(i, w) }

                val fileName = "ClosingBalance_${startDate.replace("-","")}_to_${endDate.replace("-","")}.xlsx"
                val file = File(requireContext().getExternalFilesDir(null), fileName)
                FileOutputStream(file).use { workbook.write(it) }
                workbook.close()

                val uri = FileProvider.getUriForFile(requireContext(),
                    "${requireContext().packageName}.fileprovider", file)

                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Save Closing Balances"))

                val dateCount = rows.map { it.date }.toSet().size
                Toast.makeText(requireContext(),
                    "✓ Exported ${rows.size} entries across $dateCount date(s)",
                    Toast.LENGTH_LONG).show()

            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                android.util.Log.e("DailyStock", "performDateRangeExport error", e)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // TEMPLATES
    // ═══════════════════════════════════════════════════════════════
    private fun downloadClosingTemplate() {
        val products = viewModel.allProducts.value
        if (products.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "No products available", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = importHelper.generateClosingTemplate(products, "DailyStock_Closing_Template.xlsx")
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Save Closing Template"))
            Toast.makeText(requireContext(), "✓ Closing template ready", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // IMPORT — CLOSING BALANCE
    // ═══════════════════════════════════════════════════════════════
    private fun importClosing() {
        importClosingLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            addCategory(Intent.CATEGORY_OPENABLE)
        })
    }

    private fun importClosingFromExcel(uri: Uri) {
        // All parsing, DB anomaly analysis, and confirmation handled in ViewModel
        // Fragment observes pendingImport LiveData to show the confirmation dialog
        dataViewModel.prepareClosingImport(uri, importHelper)
    }

    private fun showImportIssuesDialog(
        unknownList: List<String>,
        anomalyList: List<String>,
        result:      DailyStockImportHelper.ImportResult,
        products:    List<com.simple.simpleinventory.data.entity.Product>,
        @Suppress("UNUSED_PARAMETER") dateRange: String
    ) {
        val sb = StringBuilder()

        if (unknownList.isNotEmpty()) {
            sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            sb.appendLine("⚠️  UNKNOWN PRODUCTS — ${unknownList.size} will be skipped")
            sb.appendLine("No matching product found in app.")
            sb.appendLine("Check PRODUCT_TYPE + BRAND_CODE in your Excel file.")
            sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            unknownList.forEachIndexed { i, entry -> sb.appendLine("${i + 1}. $entry") }
            sb.appendLine()
        }

        if (anomalyList.isNotEmpty()) {
            sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            sb.appendLine("🔴  NEGATIVE SALES — ${anomalyList.size} anomaly(ies)")
            sb.appendLine("CB > OB + PQ for these sizes. Values will be imported as-is.")
            sb.appendLine("These will show as RED sales in Daily Stock for your review.")
            sb.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            anomalyList.forEachIndexed { i, entry -> sb.appendLine("${i + 1}. $entry") }
        }

        val scrollView = android.widget.ScrollView(requireContext())
        val textView = android.widget.TextView(requireContext()).apply {
            text = sb.toString()
            setPadding(48, 32, 48, 32)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        scrollView.addView(textView)

        val title = buildString {
            if (unknownList.isNotEmpty()) append("⚠️ ${unknownList.size} Unknown")
            if (unknownList.isNotEmpty() && anomalyList.isNotEmpty()) append("  •  ")
            if (anomalyList.isNotEmpty()) append("🔴 ${anomalyList.size} Anomaly")
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton("Proceed with Import") { dialog, _ ->
                dialog.dismiss()
                dataViewModel.clearPendingImport()
                dataViewModel.applyImportedClosingData(result, products)
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Copy List") { _, _ ->
                val clipboard = requireContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("Import Issues", sb.toString())
                )
                Toast.makeText(requireContext(), "List copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ═══════════════════════════════════════════════════════════════
    // IMPORT — CLOSING BALANCE (CB)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Registers the import observer once in onViewCreated.
     * Re-registers automatically after rotation — receives current Running/Success/Error state.
     */
    private fun setupImportObserver() {
        // Lazy progress dialog — shown while ViewModel is Running
        var progressDialog: android.app.Dialog? = null
        fun getOrCreateDialog(): android.app.Dialog =
            progressDialog ?: MaterialAlertDialogBuilder(requireContext())
                .setTitle("Importing...")
                .setMessage("Please wait…")
                .setCancelable(false)
                .create().also { progressDialog = it }

        // ── Stage 1: pending import ready — show confirmation dialog ──────────
        // Re-shows on rotation if user hadn't confirmed yet
        dataViewModel.pendingImport.observe(viewLifecycleOwner) { pending ->
            if (pending == null) return@observe
            val result      = pending.result
            val products    = pending.products
            val anomalyList = pending.anomalyList
            val dateRange   = pending.dateRange

            val dateBreakdown = result.closingData.values
                .groupBy { it.date }.toSortedMap()
                .entries.joinToString("\n") { (date, recs) ->
                    "  ${formatDateForDisplay(date)}: ${recs.size} product(s)"
                }
            val hasIssues = result.unknownProducts.isNotEmpty() || anomalyList.isNotEmpty()
            val summaryMsg = buildString {
                if (pending.isFirstUse) {
                    appendLine("💡 No prior stock history found.")
                    appendLine("Tip: For a cleaner setup, use Settings → Opening Stock")
                    appendLine("to enter initial quantities without an Excel file.")
                    appendLine()
                }
                appendLine("Dates found in file:")
                appendLine(dateBreakdown)
                appendLine()
                appendLine("Existing CB values will be overwritten.")
                if (result.unknownProducts.isNotEmpty())
                    appendLine("\n⚠️ ${result.unknownProducts.size} unknown product(s) will be skipped.")
                if (anomalyList.isNotEmpty())
                    appendLine("\n🔴 ${anomalyList.size} anomaly(ies): CB would produce negative sales.")
                if (hasIssues) append("\nTap 'Show Issues' to review before importing.")
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Import Closing Balances")
                .setMessage(summaryMsg.trim())
                .setPositiveButton("Import") { dialog, _ ->
                    dialog.dismiss()
                    dataViewModel.clearPendingImport()
                    dataViewModel.applyImportedClosingData(result, products)
                }
                .setNegativeButton("Cancel") { _, _ ->
                    dataViewModel.clearPendingImport()
                }
                .apply {
                    if (hasIssues) {
                        setNeutralButton("Show Issues") { dialog, _ ->
                            dialog.dismiss()
                            showImportIssuesDialog(result.unknownProducts, anomalyList,
                                result, products, dateRange)
                        }
                    }
                }
                .show()
        }

        // ── Stage 2: import running/done — show progress / success / error ────
        dataViewModel.importStatus.observe(viewLifecycleOwner) { status ->
            when (status) {
                is DailyStockDataViewModel.ImportStatus.Idle -> {
                    progressDialog?.dismiss()
                }
                is DailyStockDataViewModel.ImportStatus.Running -> {
                    val dlg = getOrCreateDialog()
                    if (!dlg.isShowing) dlg.show()
                }
                is DailyStockDataViewModel.ImportStatus.Success -> {
                    progressDialog?.dismiss()
                    Toast.makeText(requireContext(),
                        "✓ Imported ${status.count} record(s) across ${status.dates} date(s)",
                        Toast.LENGTH_LONG).show()
                    viewModel.loadEntriesForDate()
                    dataViewModel.clearImportStatus()
                }
                is DailyStockDataViewModel.ImportStatus.Error -> {
                    progressDialog?.dismiss()
                    Toast.makeText(requireContext(), "✗ ${status.message}",
                        Toast.LENGTH_LONG).show()
                    dataViewModel.clearImportStatus()
                }
            }
        }
    }

    private fun showProgressAndApplyImport(result: DailyStockImportHelper.ImportResult) {
        // Legacy entry point — delegates directly to ViewModel
        val products = viewModel.allProducts.value ?: emptyList()
        dataViewModel.applyImportedClosingData(result, products)
    }


    // ═══════════════════════════════════════════════════════════════
    // IMPORT — PURCHASE / SALE (generic)
    // ═══════════════════════════════════════════════════════════════
    private fun performImport(uri: Uri, importType: String) {
        val products = viewModel.allProducts.value
        if (products.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "No products available.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val result = when (importType) {
                "purchase" -> importHelper.importPurchaseOnly(uri, products)
                "sale"     -> importHelper.importSaleOnly(uri, products)
                else       -> importHelper.importFromExcel(uri, products)
            }
            if (!result.success) {
                Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                return
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Import Daily Stock")
                .setMessage("${result.message}\n\nThis will update the current date's data. Continue?")
                .setPositiveButton("Import") { _, _ -> applyImportedData(result) }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun applyImportedData(result: DailyStockImportHelper.ImportResult) {
        // Observer already registered in setupImportObserver() — just trigger the work
        val products = viewModel.allProducts.value ?: emptyList()
        dataViewModel.applyImportedData(result, products)
    }


    // ═══════════════════════════════════════════════════════════════
    // CLEAR DATA
    // ═══════════════════════════════════════════════════════════════
    private fun clearCurrentDateData() {
        val date = viewModel.selectedDate.value ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Clear Current Date Data")
            .setMessage("Delete all stock entries for $date?\nThis cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                dataViewModel.clearDateData(date)
                Toast.makeText(requireContext(), "Cleared data for $date", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearAllDailyStock() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Clear ALL Daily Stock Data")
            .setMessage("Delete the ENTIRE daily stock history?\nThis cannot be undone!")
            .setPositiveButton("Delete All") { _, _ ->
                dataViewModel.clearAllData()
                Toast.makeText(requireContext(), "Cleared all daily stock data", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
