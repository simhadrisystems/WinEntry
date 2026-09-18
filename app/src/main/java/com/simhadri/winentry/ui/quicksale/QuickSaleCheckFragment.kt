package com.simhadri.winentry.ui.quicksale

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.simhadri.winentry.R
import com.simhadri.winentry.databinding.FooterQuickSaleReconciliationBinding
import com.simhadri.winentry.databinding.FragmentQuickSaleCheckBinding
import com.simhadri.winentry.ui.util.ScrollNavigationHelper
import com.simhadri.winentry.utils.AppDialogs
import com.simhadri.winentry.utils.QuickSaleExcelHelper
import com.simhadri.winentry.utils.exportToDownloadsAndShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Quick Sale Check — scratchpad screen from the Reports menu, styled and laid out to
 * match Daily Stock as closely as possible. Everything typed here lives only in
 * [QuickSaleViewModel]'s in-memory map, which is Activity-scoped so navigating away
 * and back keeps the data (see `by activityViewModels()` below) — it is lost only when
 * the app itself is closed. Nothing is ever written to Room, and no other module
 * (Daily Stock, Purchases, sync) is read from or touched — only the active product
 * list and current sale prices are read (read-only).
 */
class QuickSaleCheckFragment : Fragment() {

    private var _binding: FragmentQuickSaleCheckBinding? = null
    private val binding get() = _binding!!

    private val viewModel: QuickSaleViewModel by activityViewModels()
    private lateinit var adapter: QuickSaleEntryAdapter
    private lateinit var footerAdapter: QuickSaleFooterAdapter
    private lateinit var concatAdapter: ConcatAdapter
    private lateinit var excelHelper: QuickSaleExcelHelper
    private var footerAdded = false
    private var footerBinding: FooterQuickSaleReconciliationBinding? = null

    private val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val displayFmt = SimpleDateFormat("dd MMM yyyy (EEEE)", Locale.getDefault())
    private val shortFmt = SimpleDateFormat("dd-MMM", Locale.getDefault())

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK)
            result.data?.data?.let { uri -> importFromExcel(uri) }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentQuickSaleCheckBinding.inflate(inflater, container, false)
        excelHelper = QuickSaleExcelHelper(requireContext())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.hide()

        setupToolbar()
        setupDateButton()
        setupModeToggle()
        setupRecyclerView()
        setupReconciliationFooter()
        setupSearchBar()
        observeViewModel()
        setupScrollNavigation()
        setupWindowInsets()
    }

    override fun onResume() {
        super.onResume()
        // Picks up product-master edits (e.g. a sale price change) made elsewhere
        // in the app while this screen's data was sitting in memory.
        viewModel.refreshProducts()
    }

    override fun onDestroyView() {
        (activity as? AppCompatActivity)?.supportActionBar?.show()
        // The search bar UI always rebuilds collapsed/empty next time, so leaving a
        // stale query in the (Activity-scoped) ViewModel would silently keep the row
        // list filtered with no visible search box to explain why.
        viewModel.setSearchQuery("")
        footerBinding = null
        _binding = null
        super.onDestroyView()
    }

    // ── Toolbar ──────────────────────────────────────────────────
    private fun setupToolbar() {
        binding.btnBack.setOnClickListener { findNavController().navigateUp() }

        binding.menuButton.setOnClickListener { anchor ->
            val popup = PopupMenu(requireContext(), anchor)
            popup.menuInflater.inflate(R.menu.menu_quick_sale_check, popup.menu)
            val showPurchase = viewModel.showPurchase.value ?: true
            popup.menu.findItem(R.id.action_quick_sale_toggle_purchase).title =
                if (showPurchase) "Hide Purchase Row" else "Show Purchase Row"
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_quick_sale_toggle_purchase -> {
                        viewModel.setShowPurchase(!(viewModel.showPurchase.value ?: true)); true
                    }
                    R.id.action_quick_sale_refresh_prices -> {
                        viewModel.refreshProducts()
                        Toast.makeText(requireContext(), "Prices refreshed", Toast.LENGTH_SHORT).show()
                        true
                    }
                    R.id.action_quick_sale_export     -> { exportSheet();     true }
                    R.id.action_quick_sale_export_for_daily_stock -> { exportClosingForDailyStock(); true }
                    R.id.action_quick_sale_export_closing_as_opening -> { exportClosingAsOpeningBalances(); true }
                    R.id.action_quick_sale_import     -> { launchImport();   true }
                    R.id.action_quick_sale_print       -> { printSheet();     true }
                    R.id.action_quick_sale_print_closing_stock -> { printClosingStock(); true }
                    R.id.action_quick_sale_clear_all  -> { confirmClearAll(); true }
                    else -> false
                }
            }
            popup.show()
        }
    }

    // ── Date button (label only — a single ongoing scratchpad, not per-date storage) ──
    private fun setupDateButton() {
        binding.dateButton.setOnClickListener { openDatePicker() }
    }

    private fun openDatePicker() {
        val calendar = Calendar.getInstance()
        val current = viewModel.workingDate.value ?: sdf.format(Date())
        try { sdf.parse(current)?.let { calendar.time = it } } catch (_: Exception) {}

        val dlg = DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                viewModel.setWorkingDate(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day))
            },
            calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)
        )
        dlg.setButton(DatePickerDialog.BUTTON_NEUTRAL, "Today") { _, which ->
            if (which == DatePickerDialog.BUTTON_NEUTRAL) viewModel.setWorkingDate(sdf.format(Date()))
        }
        dlg.show()
    }

    // ── Mode toggle ──────────────────────────────────────────────
    private fun setupModeToggle() {
        binding.btnModeToggle.setOnClickListener {
            val next = if (viewModel.mode.value == QuickSaleMode.OB_CB)
                QuickSaleMode.DIRECT_QTY else QuickSaleMode.OB_CB
            viewModel.setMode(next)
        }
    }

    private fun updateModeToggleButton(mode: QuickSaleMode) {
        binding.btnModeToggle.apply {
            when (mode) {
                QuickSaleMode.OB_CB -> {
                    text = "Direct\nQty"
                    backgroundTintList = android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#44FFFFFF"))
                }
                QuickSaleMode.DIRECT_QTY -> {
                    text = "OB/PQ\n/CB"
                    backgroundTintList = android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#FF388E3C"))
                }
            }
        }
    }

    // ── RecyclerView ─────────────────────────────────────────────
    private fun setupRecyclerView() {
        adapter = QuickSaleEntryAdapter(
            getMode = { viewModel.mode.value ?: QuickSaleMode.OB_CB },
            getShowPurchase = { viewModel.showPurchase.value ?: true },
            onOpeningChanged = { id, size, value -> viewModel.updateOpening(id, size, value) },
            onPurchaseChanged = { id, size, value -> viewModel.updatePurchase(id, size, value) },
            onClosingChanged = { id, size, value -> viewModel.updateClosing(id, size, value) },
            onDirectSaleChanged = { id, size, value -> viewModel.updateDirectSale(id, size, value) }
        )
        footerAdapter = QuickSaleFooterAdapter(layoutInflater)
        concatAdapter = ConcatAdapter(adapter)

        binding.productRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = concatAdapter
            (itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
                ?.supportsChangeAnimations = false
        }
    }

    // ── Reconciliation footer ────────────────────────────────────
    private fun setupReconciliationFooter() {
        footerAdapter.onViewReady = { b -> wireFooter(b) }
    }

    private fun formatRupee(amount: Double): String {
        val abs = Math.abs(amount).toLong()
        val prefix = if (amount < 0) "-₹ " else "₹ "
        if (abs == 0L) return "₹ 0"
        val s = abs.toString()
        if (s.length <= 3) return prefix + s
        val last3 = s.takeLast(3)
        var rest = s.dropLast(3)
        val groups = mutableListOf<String>()
        while (rest.length > 2) { groups.add(0, rest.takeLast(2)); rest = rest.dropLast(2) }
        groups.add(0, rest)
        return prefix + groups.joinToString(",") + ",$last3"
    }

    private fun wireFooter(footer: FooterQuickSaleReconciliationBinding) {
        footerBinding = footer

        fun makeCurrencyWatcher(field: android.widget.EditText, onChange: (Double) -> Unit) =
            object : TextWatcher {
                private var formatting = false
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (formatting) return
                    formatting = true
                    val raw = s?.toString()?.replace("₹", "")?.replace(",", "")?.trim() ?: ""
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

        footer.editUpiReceipts.addTextChangedListener(makeCurrencyWatcher(footer.editUpiReceipts) { viewModel.updateUpi(it) })
        footer.editDayExpenses.addTextChangedListener(makeCurrencyWatcher(footer.editDayExpenses) { viewModel.updateExpenses(it) })
        footer.editDeposits.addTextChangedListener(makeCurrencyWatcher(footer.editDeposits) { viewModel.updateDeposits(it) })
        footer.editNotes.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { viewModel.updateNotes(s?.toString() ?: "") }
        })

        viewModel.workingDate.observe(viewLifecycleOwner) { date ->
            footer.textReconciliationDate.text = try {
                displayFmt.format(sdf.parse(date) ?: Date())
            } catch (_: Exception) { date }
        }

        viewModel.totalSaleAmount.observe(viewLifecycleOwner) { total ->
            footer.textTotalDaySales.text = formatRupee(total ?: 0.0)
        }

        viewModel.cashForDeposit.observe(viewLifecycleOwner) { cash ->
            footer.textCashForDeposit.text = formatRupee(cash ?: 0.0)
        }

        // Purchase cross-check + sold-units summary lines, recomputed whenever the
        // total changes — that LiveData already fires on every row or mode change.
        viewModel.totalSaleAmount.observe(viewLifecycleOwner) {
            val rows = viewModel.currentRows()
            val mode = viewModel.mode.value ?: QuickSaleMode.OB_CB
            val pUnits = rows.sumOf { it.purchase.qq + it.purchase.pp + it.purchase.nn + it.purchase.dd }
            if (pUnits > 0) {
                val pValue = rows.sumOf {
                    it.purchase.qq * it.product.qqPurchasePrice + it.purchase.pp * it.product.ppPurchasePrice +
                    it.purchase.nn * it.product.nnPurchasePrice + it.purchase.dd * it.product.ddPurchasePrice
                }
                footer.textPurchaseSummary.text = "Purchase: $pUnits units  ${formatRupee(pValue)}"
                footer.textPurchaseSummary.visibility = View.VISIBLE
            } else {
                footer.textPurchaseSummary.visibility = View.GONE
            }
            val sUnits = rows.sumOf { it.sale(mode).qq + it.sale(mode).pp + it.sale(mode).nn + it.sale(mode).dd }
            footer.textSoldUnitsSummary.text = if (sUnits > 0) "$sUnits units sold" else "System calculated"

            // Closing Stock Value — only meaningful in OB/PQ/CB mode; Direct Qty mode
            // never touches the Closing field so there is nothing to show for it.
            if (mode == QuickSaleMode.OB_CB) {
                val closingValue = rows.sumOf { it.closingStockValue() }
                footer.textClosingStockValue.text = formatRupee(closingValue)
                footer.rowClosingStockValue.visibility = View.VISIBLE
            } else {
                footer.rowClosingStockValue.visibility = View.GONE
            }
        }

        refreshFooterReconciliationFields()
    }

    /** (Re)fills the UPI/Expenses/Deposits/Notes fields from the ViewModel — used at
     *  first bind and again after an Excel import restores those values. */
    private fun refreshFooterReconciliationFields() {
        val footer = footerBinding ?: return
        footer.editUpiReceipts.setText(viewModel.upiReceipts.value?.takeIf { it > 0 }?.let { formatRupee(it) } ?: "")
        footer.editDayExpenses.setText(viewModel.dayExpenses.value?.takeIf { it > 0 }?.let { formatRupee(it) } ?: "")
        footer.editDeposits.setText(viewModel.deposits.value?.takeIf { it > 0 }?.let { formatRupee(it) } ?: "")
        footer.editNotes.setText(viewModel.notes.value.orEmpty())
    }

    // ── Search ───────────────────────────────────────────────────
    private fun setupSearchBar() {
        binding.searchIcon.setOnClickListener {
            if (binding.searchContainer.visibility == View.GONE) expandSearch() else collapseSearch()
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
        binding.searchInput.setOnEditorActionListener { _, _, _ -> collapseSearch(); true }
    }

    private fun expandSearch() {
        binding.searchContainer.visibility = View.VISIBLE
        val containerParams = binding.searchContainer.layoutParams as android.widget.LinearLayout.LayoutParams
        containerParams.weight = 1f
        containerParams.width = 0
        binding.searchContainer.layoutParams = containerParams
        binding.dateButton.visibility = View.GONE

        binding.searchContainer.translationX = binding.searchContainer.width.toFloat().coerceAtLeast(300f)
        binding.searchContainer.animate().translationX(0f).setDuration(200).start()

        binding.searchInput.requestFocus()
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun collapseSearch() {
        binding.searchContainer.animate()
            .translationX(binding.searchContainer.width.toFloat().coerceAtLeast(300f))
            .setDuration(180)
            .withEndAction {
                binding.searchContainer.visibility = View.GONE
                binding.searchContainer.translationX = 0f
                val containerParams = binding.searchContainer.layoutParams as android.widget.LinearLayout.LayoutParams
                containerParams.weight = 0f
                binding.searchContainer.layoutParams = containerParams
                binding.dateButton.visibility = View.VISIBLE
            }
            .start()
        binding.searchInput.setText("")
        viewModel.setSearchQuery("")
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }

    // ── ViewModel observers ──────────────────────────────────────
    private fun observeViewModel() {
        viewModel.filteredRows.observe(viewLifecycleOwner) { rows ->
            if (!footerAdded && rows.isNotEmpty()) {
                concatAdapter.addAdapter(footerAdapter)
                footerAdded = true
            }
            adapter.submitList(rows)
        }
        val indFmt = NumberFormat.getInstance(Locale("en", "IN")).apply {
            minimumFractionDigits = 2; maximumFractionDigits = 2
        }
        viewModel.totalSaleAmount.observe(viewLifecycleOwner) { total ->
            binding.textTotalSaleStrip.text = "₹${indFmt.format(total ?: 0.0)}"
        }
        viewModel.mode.observe(viewLifecycleOwner) { mode ->
            updateModeToggleButton(mode)
            adapter.notifyDataSetChanged()
        }
        viewModel.showPurchase.observe(viewLifecycleOwner) {
            adapter.notifyDataSetChanged()
        }
        viewModel.workingDate.observe(viewLifecycleOwner) { date ->
            binding.dateButton.text = try {
                shortFmt.format(sdf.parse(date) ?: Date()).uppercase()
            } catch (_: Exception) { date }
        }
    }

    // ── Scroll navigation / insets ───────────────────────────────
    private fun setupScrollNavigation() {
        ScrollNavigationHelper.setup(
            recyclerView   = binding.productRecyclerView,
            fabTop         = binding.fabScrollTop,
            fabBottom      = binding.fabScrollBottom,
            lifecycleOwner = viewLifecycleOwner,
            dataReady      = viewModel.filteredRows
        )
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.stickyHeaders) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = bars.left, right = bars.right)
            insets
        }
        val basePx = (32 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.productRecyclerView) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime  = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            v.updatePadding(bottom = maxOf(bars.bottom, ime) + basePx, left = bars.left, right = bars.right)
            insets
        }
    }

    // ── Clear All ────────────────────────────────────────────────
    private fun confirmClearAll() {
        AppDialogs.destructive(
            context     = requireContext(),
            title       = "Clear All",
            message     = "Reset every product's Opening, Purchase, Closing, Sale Qty and the reconciliation figures to zero?\n\nThis only clears this scratchpad — no saved data is affected.",
            actionLabel = "Clear All"
        ) {
            viewModel.clearAll()
            refreshFooterReconciliationFields()
            forceFullRebind()
            Toast.makeText(requireContext(), "Cleared", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Forces every visible row to go through a full [QuickSaleEntryAdapter.ViewHolder.bind]
     * instead of the payload-only computed-cell path. The adapter's DiffUtil callback
     * deliberately skips re-setting a row's EditTexts when only opening/purchase/closing/
     * directSale changed (so typing in one field doesn't fight the cursor in another) — but
     * that same skip means a bulk reset (Clear All) or a bulk repopulate (Import), where every
     * field changes at once for many rows, leaves the on-screen EditTexts showing stale values
     * even though the ViewModel's data is already correct. Clearing the adapter's list first
     * makes the next submission look like a fresh insert of every row, which ListAdapter always
     * binds in full (payloads only ever apply to a changed *existing* item).
     */
    private fun forceFullRebind() {
        adapter.submitList(null) {
            adapter.submitList(viewModel.filteredRows.value.orEmpty())
        }
    }

    // ── Export ───────────────────────────────────────────────────
    private fun exportSheet() {
        val rows = viewModel.currentRows()
        if (rows.isEmpty()) {
            Toast.makeText(requireContext(), "Nothing to export yet", Toast.LENGTH_SHORT).show()
            return
        }
        val mode = viewModel.mode.value ?: QuickSaleMode.OB_CB
        val date = viewModel.workingDate.value ?: sdf.format(Date())
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "QuickSaleCheck_$stamp.xlsx"
        val uri = excelHelper.exportQuickSaleSheet(
            rows, mode, date, fileName,
            upi = viewModel.upiReceipts.value ?: 0.0,
            expenses = viewModel.dayExpenses.value ?: 0.0,
            deposits = viewModel.deposits.value ?: 0.0,
            cashForDeposit = viewModel.cashForDeposit.value ?: 0.0,
            notes = viewModel.notes.value.orEmpty()
        )
        exportToDownloadsAndShare(uri, fileName, "Share Quick Sale Check")
    }

    /**
     * Writes a Closing-Balances file Daily Stock's own Import Closing Balances menu item
     * can already read as-is — lets a value sanity-checked here be carried over to the real
     * module without this feature ever writing to Room itself.
     */
    private fun exportClosingForDailyStock() {
        if (viewModel.mode.value != QuickSaleMode.OB_CB) {
            Toast.makeText(
                requireContext(),
                "Switch to OB/PQ/CB mode first — Direct Qty mode has no Closing value to export",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val rows = viewModel.currentRows()
        val date = viewModel.workingDate.value ?: sdf.format(Date())
        val hasAnyData = rows.any {
            it.opening.qq != 0 || it.opening.pp != 0 || it.opening.nn != 0 || it.opening.dd != 0 ||
            it.purchase.qq != 0 || it.purchase.pp != 0 || it.purchase.nn != 0 || it.purchase.dd != 0 ||
            it.closing.qq != 0 || it.closing.pp != 0 || it.closing.nn != 0 || it.closing.dd != 0
        }
        if (!hasAnyData) {
            Toast.makeText(requireContext(), "Nothing entered yet", Toast.LENGTH_SHORT).show()
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "ClosingBalances_${date}_$stamp.xlsx"
        val uri = excelHelper.exportClosingForDailyStock(rows, date, fileName)
        exportToDownloadsAndShare(uri, fileName, "Share Closing Balances")
        Toast.makeText(
            requireContext(),
            "Import this via Reports → Daily Stock → ⋮ → Import Closing Balances",
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * Carries today's Closing forward as tomorrow's (or any chosen day's) Opening Balance —
     * writes a file the regular Import action already understands as-is (see
     * `QuickSaleExcelHelper.exportClosingAsOpeningBalances`), so no separate "import as
     * opening" action is needed: just Clear All (or move to a fresh day) and Import this file.
     */
    private fun exportClosingAsOpeningBalances() {
        if (viewModel.mode.value != QuickSaleMode.OB_CB) {
            Toast.makeText(
                requireContext(),
                "Switch to OB/PQ/CB mode first — Direct Qty mode has no Closing value to carry forward",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val rows = viewModel.currentRows()
        val hasAnyClosing = rows.any {
            it.closing.qq != 0 || it.closing.pp != 0 || it.closing.nn != 0 || it.closing.dd != 0
        }
        if (!hasAnyClosing) {
            Toast.makeText(requireContext(), "No Closing values entered yet", Toast.LENGTH_SHORT).show()
            return
        }
        val date = viewModel.workingDate.value ?: sdf.format(Date())
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "OpeningBalances_from_${date}_$stamp.xlsx"
        val uri = excelHelper.exportClosingAsOpeningBalances(rows, date, fileName)
        exportToDownloadsAndShare(uri, fileName, "Share Opening Balances")
        Toast.makeText(
            requireContext(),
            "Import this file (⋮ > Import) on the day you want these as Opening Balances",
            Toast.LENGTH_LONG
        ).show()
    }

    // ── Import ───────────────────────────────────────────────────
    private fun launchImport() {
        importLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            addCategory(Intent.CATEGORY_OPENABLE)
        })
    }

    private fun importFromExcel(uri: Uri) {
        val products = viewModel.currentRows().map { it.product }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                requireContext().contentResolver.openInputStream(uri)?.use { stream ->
                    runCatching { excelHelper.importQuickSaleSheet(stream, products) }
                }
            }
            val outcome = result?.getOrNull()
            if (outcome == null) {
                Toast.makeText(requireContext(), "Import failed — check the file format", Toast.LENGTH_LONG).show()
                return@launch
            }
            val quadruples = outcome.rows
            if (quadruples.isEmpty()) {
                Toast.makeText(
                    requireContext(),
                    "No matching rows found — check this is a Quick Sale Check, Daily Stock Sheet, or Closing Balances export",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }
            outcome.detectedDate?.let { viewModel.setWorkingDate(it) }
            outcome.detectedMode?.let { viewModel.setMode(it) }
            outcome.upi?.let { viewModel.updateUpi(it) }
            outcome.expenses?.let { viewModel.updateExpenses(it) }
            outcome.deposits?.let { viewModel.updateDeposits(it) }
            outcome.notes?.let { viewModel.updateNotes(it) }
            if (outcome.upi != null || outcome.expenses != null || outcome.deposits != null || outcome.notes != null) {
                refreshFooterReconciliationFields()
            }
            var closingOnlyCount = 0
            quadruples.forEach { q ->
                viewModel.applyImportedRow(q.productId, q.opening, q.purchase, q.closing, q.directSale)
                val hasNoOpeningOrPurchase = q.opening.qq == 0 && q.opening.pp == 0 && q.opening.nn == 0 && q.opening.dd == 0 &&
                    q.purchase.qq == 0 && q.purchase.pp == 0 && q.purchase.nn == 0 && q.purchase.dd == 0
                val hasClosing = q.closing.qq != 0 || q.closing.pp != 0 || q.closing.nn != 0 || q.closing.dd != 0
                if (hasNoOpeningOrPurchase && hasClosing) closingOnlyCount++
            }
            forceFullRebind()
            val dateNote = outcome.detectedDate?.let { " Date set to $it." } ?: ""
            val msg = "Imported ${quadruples.size} product row(s).$dateNote"
            if (closingOnlyCount > 0) {
                Toast.makeText(
                    requireContext(),
                    "$msg $closingOnlyCount had only a Closing value (no Opening/Purchase) — " +
                    "those Closing cells stay read-only until you fill in an Opening for that size.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Print — mirrors Reports → Daily Stock Sheet's exact table/colour scheme ──
    private fun printSheet() {
        val rows = viewModel.currentRows()
        if (rows.isEmpty()) {
            Toast.makeText(requireContext(), "Nothing to print yet", Toast.LENGTH_SHORT).show()
            return
        }
        val mode = viewModel.mode.value ?: QuickSaleMode.OB_CB
        printHtml("Quick Sale Check", buildDailySheetStyleHtml(rows, mode))
    }

    /**
     * Closing Stock Value report — same visual style as the sale report above, but
     * lists Closing Balance qty/value instead of Sale. OB/PQ/CB mode only: Direct Qty
     * mode never touches the Closing field, so there would be nothing to show.
     */
    private fun printClosingStock() {
        if (viewModel.mode.value != QuickSaleMode.OB_CB) {
            Toast.makeText(
                requireContext(),
                "Switch to OB/PQ/CB mode first — Direct Qty mode has no Closing value to print",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val rows = viewModel.currentRows()
        val hasAnyClosing = rows.any {
            it.closing.qq != 0 || it.closing.pp != 0 || it.closing.nn != 0 || it.closing.dd != 0
        }
        if (!hasAnyClosing) {
            Toast.makeText(requireContext(), "No Closing values entered yet", Toast.LENGTH_SHORT).show()
            return
        }
        printHtml("Closing Stock Value", buildClosingStockHtml(rows))
    }

    private fun printHtml(jobName: String, html: String) {
        val wv = WebView(requireContext())
        wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                val pm = requireContext().getSystemService(Context.PRINT_SERVICE) as PrintManager
                pm.print(jobName, view.createPrintDocumentAdapter(jobName), PrintAttributes.Builder().build())
            }
        }
    }

    /**
     * Same table structure/colours as ReportViewerViewModel.buildDailySheetHtml (the
     * "Daily Stock Sheet" report under Reports), plus a Day-End Summary box fed from
     * this screen's in-memory reconciliation figures. Clearly labelled as a scratchpad
     * so it's never mistaken for the real committed report.
     */
    private fun buildDailySheetStyleHtml(rows: List<QuickSaleRow>, mode: QuickSaleMode): String {
        val date = viewModel.workingDate.value ?: sdf.format(Date())
        val displayDate = try { displayFmt.format(sdf.parse(date) ?: Date()) } catch (_: Exception) { date }
        val generatedOn = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
        val prefs = requireContext().getSharedPreferences("business_info", Context.MODE_PRIVATE)
        val businessName = prefs.getString("business_name", "").orEmpty()
        val location = prefs.getString("location", "").orEmpty()

        val inLocale = Locale("en", "IN")
        fun qty(n: Int): String = if (n == 0) "-" else NumberFormat.getIntegerInstance(inLocale).format(n.toLong())
        fun cur(v: Double): String = if (v == 0.0) "-" else "₹" + NumberFormat.getNumberInstance(inLocale)
            .apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(v)

        var totObQQ = 0; var totObPP = 0; var totObNN = 0; var totObDD = 0
        var totPqQQ = 0; var totPqPP = 0; var totPqNN = 0; var totPqDD = 0
        var totCbQQ = 0; var totCbPP = 0; var totCbNN = 0; var totCbDD = 0
        var totSqQQ = 0; var totSqPP = 0; var totSqNN = 0; var totSqDD = 0
        var totSale = 0.0
        var rowSerial = 0

        val rowsHtml = rows.joinToString("") { row ->
            val sale = row.sale(mode)
            val saleAmt = row.saleAmount(mode)
            val skip = row.opening.qq == 0 && row.opening.pp == 0 && row.opening.nn == 0 && row.opening.dd == 0 &&
                       row.purchase.qq == 0 && row.purchase.pp == 0 && row.purchase.nn == 0 && row.purchase.dd == 0 &&
                       sale.qq == 0 && sale.pp == 0 && sale.nn == 0 && sale.dd == 0
            if (skip) return@joinToString ""
            rowSerial++
            totObQQ += row.opening.qq; totObPP += row.opening.pp; totObNN += row.opening.nn; totObDD += row.opening.dd
            totPqQQ += row.purchase.qq; totPqPP += row.purchase.pp; totPqNN += row.purchase.nn; totPqDD += row.purchase.dd
            totCbQQ += row.closing.qq; totCbPP += row.closing.pp; totCbNN += row.closing.nn; totCbDD += row.closing.dd
            totSqQQ += sale.qq; totSqPP += sale.pp; totSqNN += sale.nn; totSqDD += sale.dd
            totSale += saleAmt
            """<tr>
                <td class="n">$rowSerial</td>
                <td>${row.product.displayName.take(18)}</td>
                <td class="n">${qty(row.opening.qq)}</td><td class="n">${qty(row.opening.pp)}</td>
                <td class="n">${qty(row.opening.nn)}</td><td class="n">${qty(row.opening.dd)}</td>
                <td class="n">${qty(row.purchase.qq)}</td><td class="n">${qty(row.purchase.pp)}</td>
                <td class="n">${qty(row.purchase.nn)}</td><td class="n">${qty(row.purchase.dd)}</td>
                <td class="n">${qty(row.closing.qq)}</td><td class="n">${qty(row.closing.pp)}</td>
                <td class="n">${qty(row.closing.nn)}</td><td class="n">${qty(row.closing.dd)}</td>
                <td class="n">${qty(sale.qq)}</td><td class="n">${qty(sale.pp)}</td>
                <td class="n">${qty(sale.nn)}</td><td class="n">${qty(sale.dd)}</td>
                <td class="r">${cur(saleAmt)}</td>
            </tr>"""
        }

        val locationLine = if (location.isNotEmpty()) "<div class='loc'>$location</div>" else ""

        val upi = viewModel.upiReceipts.value ?: 0.0
        val expenses = viewModel.dayExpenses.value ?: 0.0
        val cash = viewModel.cashForDeposit.value ?: (totSale - upi - expenses)
        val notes = viewModel.notes.value.orEmpty()
        val reconHtml = if (upi != 0.0 || expenses != 0.0 || notes.isNotBlank()) """
<div style="display:flex;justify-content:flex-end;margin-top:14px">
  <table style="border-collapse:collapse;width:auto;min-width:260px;border:1px solid #b0b8d4;font-size:11px">
    <thead><tr><th colspan="2" style="background:#1a237e;color:white;padding:5px 10px;text-align:center;font-size:11px;border-right:none">Day End Summary &mdash; $displayDate</th></tr></thead>
    <tbody>
      <tr><td style="padding:4px 10px;border-bottom:1px solid #e0e0e0;border-right:1px solid #d0d5e8;color:#444">Day Total Sales</td><td style="padding:4px 10px;border-bottom:1px solid #e0e0e0;text-align:right;font-weight:bold">${cur(totSale)}</td></tr>
      <tr style="background:#eef0f8"><td style="padding:4px 10px;border-bottom:1px solid #e0e0e0;border-right:1px solid #d0d5e8;color:#444">UPI / Online Receipts</td><td style="padding:4px 10px;border-bottom:1px solid #e0e0e0;text-align:right">${cur(upi)}</td></tr>
      <tr><td style="padding:4px 10px;border-bottom:1px solid #e0e0e0;border-right:1px solid #d0d5e8;color:#444">Expenses</td><td style="padding:4px 10px;border-bottom:1px solid #e0e0e0;text-align:right">${cur(expenses)}</td></tr>
      <tr style="background:#eef0f8"><td style="padding:4px 10px;border-right:1px solid #d0d5e8;font-weight:bold;color:#1a237e">Cash for Bank Deposit</td><td style="padding:4px 10px;text-align:right;font-weight:bold;color:#1a237e">${cur(cash)}</td></tr>
      ${if (notes.isNotBlank()) """<tr><td style="padding:4px 10px;border-top:1px solid #e0e0e0;border-right:1px solid #d0d5e8;color:#444">Notes</td><td style="padding:4px 10px;border-top:1px solid #e0e0e0;color:#555">$notes</td></tr>""" else ""}
    </tbody>
  </table>
</div>""" else ""

        return """<!DOCTYPE html><html><head><meta charset="UTF-8">
<style>
  @page  { margin:15mm 17mm 15mm 22mm }
  body   { font-family:Arial,sans-serif; font-size:11px; margin:0 }
  .hdr   { text-align:center; margin-bottom:10px }
  .biz   { font-size:15px; font-weight:bold; color:#1a237e }
  .loc   { font-size:12px; color:#444; margin-top:2px }
  .rep   { font-size:12px; font-weight:bold; color:#1a237e; margin-top:4px }
  .tag   { font-size:10px; font-weight:bold; color:#0D9488; margin-top:2px }
  .gen   { font-size:9px; color:#888; margin-top:3px }
  table  { border-collapse:collapse; width:100%; border:1px solid #b0b8d4 }
  th     { background:#1a237e; color:white; padding:4px 5px; font-size:10px; border-right:1px solid #3949ab; white-space:nowrap }
  th:last-child { border-right:none }
  td     { padding:3px 5px; border-bottom:1px solid #e0e0e0; border-right:1px solid #d0d5e8 }
  td:last-child { border-right:none }
  .n     { text-align:center; white-space:nowrap }
  .r     { text-align:right;  white-space:nowrap }
  tr     { page-break-inside:avoid; break-inside:avoid }
  tr:nth-child(even) td { background:#eef0f8 }
  tr.totrow td { background:#1a237e; color:white; font-weight:bold; padding:4px 5px; border-right:1px solid #3949ab; white-space:nowrap }
  tr.totrow td:last-child { border-right:none }
  .wmark     { position:fixed; top:50%; left:50%; transform:translate(-50%,-50%) rotate(-40deg); text-align:center; color:rgba(26,35,126,0.07); white-space:nowrap; pointer-events:none; z-index:999 }
  .wmark-app { display:block; font-family:Arial,sans-serif; font-size:68px; font-weight:900; line-height:1 }
  .wmark-co  { display:block; font-family:Arial,sans-serif; font-size:26px; font-weight:600; letter-spacing:3px; margin-top:4px }
  .wmt       { font-size:8px; color:#bbb; margin-top:3px; letter-spacing:0.3px }
</style></head><body>
<div class="hdr">
  <div class="biz">$businessName</div>
  $locationLine
  <div class="rep">Quick Sale Check &mdash; $displayDate</div>
  <div class="tag">SCRATCHPAD &middot; not linked to saved data</div>
  <div class="gen">Generated on $generatedOn</div>
  <div class="wmt"><b>WinEntry</b> &middot; Simhadri Systems</div>
</div>
<table>
  <thead>
    <tr>
      <th rowspan="2" style="text-align:center;vertical-align:middle">#</th>
      <th rowspan="2" style="text-align:left;vertical-align:middle">Product</th>
      <th colspan="4">Opening Balance</th>
      <th colspan="4">Purchase</th>
      <th colspan="4">Closing Balance</th>
      <th colspan="4">Sale Qty</th>
      <th rowspan="2" style="vertical-align:middle">Sale Amt</th>
    </tr>
    <tr>
      <th>QQ</th><th>PP</th><th>NN</th><th>DD</th>
      <th>QQ</th><th>PP</th><th>NN</th><th>DD</th>
      <th>QQ</th><th>PP</th><th>NN</th><th>DD</th>
      <th>QQ</th><th>PP</th><th>NN</th><th>DD</th>
    </tr>
  </thead>
  <tbody>
    $rowsHtml
    <tr class="totrow">
      <td colspan="2">TOTAL</td>
      <td class="n">${qty(totObQQ)}</td><td class="n">${qty(totObPP)}</td>
      <td class="n">${qty(totObNN)}</td><td class="n">${qty(totObDD)}</td>
      <td class="n">${qty(totPqQQ)}</td><td class="n">${qty(totPqPP)}</td>
      <td class="n">${qty(totPqNN)}</td><td class="n">${qty(totPqDD)}</td>
      <td class="n">${qty(totCbQQ)}</td><td class="n">${qty(totCbPP)}</td>
      <td class="n">${qty(totCbNN)}</td><td class="n">${qty(totCbDD)}</td>
      <td class="n">${qty(totSqQQ)}</td><td class="n">${qty(totSqPP)}</td>
      <td class="n">${qty(totSqNN)}</td><td class="n">${qty(totSqDD)}</td>
      <td class="r">${cur(totSale)}</td>
    </tr>
  </tbody>
</table>
$reconHtml
<div class="wmark"><span class="wmark-app">WinEntry</span><span class="wmark-co">Simhadri Systems</span></div>
</body></html>"""
    }

    /**
     * Full Opening/Purchase/Closing/Sale breakdown per product (same table shape as the sale
     * report above), but with a Closing Stock Value column instead of Sale Amount, and a grand
     * total Closing Stock Value row — showing what's left in stock and its worth as of the
     * working date, not just what sold.
     */
    private fun buildClosingStockHtml(rows: List<QuickSaleRow>): String {
        val date = viewModel.workingDate.value ?: sdf.format(Date())
        val displayDate = try { displayFmt.format(sdf.parse(date) ?: Date()) } catch (_: Exception) { date }
        val generatedOn = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date())
        val prefs = requireContext().getSharedPreferences("business_info", Context.MODE_PRIVATE)
        val businessName = prefs.getString("business_name", "").orEmpty()
        val location = prefs.getString("location", "").orEmpty()

        val inLocale = Locale("en", "IN")
        fun qty(n: Int): String = if (n == 0) "-" else NumberFormat.getIntegerInstance(inLocale).format(n.toLong())
        fun cur(v: Double): String = if (v == 0.0) "-" else "₹" + NumberFormat.getNumberInstance(inLocale)
            .apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(v)
        val rateFmt = NumberFormat.getIntegerInstance(inLocale)
        fun rate(v: Double): String = if (v <= 0.0) "-" else rateFmt.format(v.toLong())

        var totCbQQ = 0; var totCbPP = 0; var totCbNN = 0; var totCbDD = 0
        var totValueSale = 0.0
        var totValuePurchase = 0.0
        var rowSerial = 0

        val rowsHtml = rows.joinToString("") { row ->
            val skip = row.closing.qq == 0 && row.closing.pp == 0 && row.closing.nn == 0 && row.closing.dd == 0
            if (skip) return@joinToString ""
            val valueSale = row.closingStockValue()
            val valuePurchase = row.closingStockValueAtPurchasePrice()
            rowSerial++
            totCbQQ += row.closing.qq; totCbPP += row.closing.pp; totCbNN += row.closing.nn; totCbDD += row.closing.dd
            totValueSale += valueSale
            totValuePurchase += valuePurchase
            """<tr>
                <td class="n">$rowSerial</td>
                <td>${row.product.displayName.take(28)}</td>
                <td class="n">${qty(row.closing.qq)}</td><td class="n">${qty(row.closing.pp)}</td>
                <td class="n">${qty(row.closing.nn)}</td><td class="n">${qty(row.closing.dd)}</td>
                <td class="n">${if (row.closing.qq > 0) rate(row.product.qqPurchasePrice) else ""}</td><td class="n">${if (row.closing.pp > 0) rate(row.product.ppPurchasePrice) else ""}</td>
                <td class="n">${if (row.closing.nn > 0) rate(row.product.nnPurchasePrice) else ""}</td><td class="n">${if (row.closing.dd > 0) rate(row.product.ddPurchasePrice) else ""}</td>
                <td class="n">${if (row.closing.qq > 0) rate(row.product.qqSalePrice) else ""}</td><td class="n">${if (row.closing.pp > 0) rate(row.product.ppSalePrice) else ""}</td>
                <td class="n">${if (row.closing.nn > 0) rate(row.product.nnSalePrice) else ""}</td><td class="n">${if (row.closing.dd > 0) rate(row.product.ddSalePrice) else ""}</td>
                <td class="r">${cur(valuePurchase)}</td>
                <td class="r">${cur(valueSale)}</td>
            </tr>"""
        }

        val locationLine = if (location.isNotEmpty()) "<div class='loc'>$location</div>" else ""

        return """<!DOCTYPE html><html><head><meta charset="UTF-8">
<style>
  @page  { margin:15mm 17mm 15mm 22mm }
  body   { font-family:Arial,sans-serif; font-size:11px; margin:0 }
  .hdr   { text-align:center; margin-bottom:10px }
  .biz   { font-size:15px; font-weight:bold; color:#1a237e }
  .loc   { font-size:12px; color:#444; margin-top:2px }
  .rep   { font-size:12px; font-weight:bold; color:#1a237e; margin-top:4px }
  .tag   { font-size:10px; font-weight:bold; color:#0D9488; margin-top:2px }
  .gen   { font-size:9px; color:#888; margin-top:3px }
  table  { border-collapse:collapse; width:100%; border:1px solid #b0b8d4 }
  th     { background:#1a237e; color:white; padding:4px 5px; font-size:10px; border-right:1px solid #3949ab; white-space:nowrap }
  th:last-child { border-right:none }
  td     { padding:3px 5px; border-bottom:1px solid #e0e0e0; border-right:1px solid #d0d5e8 }
  td:last-child { border-right:none }
  .n     { text-align:center; white-space:nowrap }
  .r     { text-align:right;  white-space:nowrap }
  tr     { page-break-inside:avoid; break-inside:avoid }
  tr:nth-child(even) td { background:#eef0f8 }
  tr.totrow td { background:#1a237e; color:white; font-weight:bold; padding:4px 5px; border-right:1px solid #3949ab; white-space:nowrap }
  tr.totrow td:last-child { border-right:none }
  .wmark     { position:fixed; top:50%; left:50%; transform:translate(-50%,-50%) rotate(-40deg); text-align:center; color:rgba(26,35,126,0.07); white-space:nowrap; pointer-events:none; z-index:999 }
  .wmark-app { display:block; font-family:Arial,sans-serif; font-size:68px; font-weight:900; line-height:1 }
  .wmark-co  { display:block; font-family:Arial,sans-serif; font-size:26px; font-weight:600; letter-spacing:3px; margin-top:4px }
  .wmt       { font-size:8px; color:#bbb; margin-top:3px; letter-spacing:0.3px }
</style></head><body>
<div class="hdr">
  <div class="biz">$businessName</div>
  $locationLine
  <div class="rep">Closing Stock Report &mdash; $displayDate</div>
  <div class="tag">SCRATCHPAD &middot; not linked to saved data</div>
  <div class="gen">Generated on $generatedOn</div>
  <div class="wmt"><b>WinEntry</b> &middot; Simhadri Systems</div>
</div>
<table>
  <thead>
    <tr>
      <th rowspan="2" style="text-align:center;vertical-align:middle">#</th>
      <th rowspan="2" style="text-align:left;vertical-align:middle">Product</th>
      <th colspan="4">Closing Balance</th>
      <th colspan="4">Purchase Price</th>
      <th colspan="4">Sale Price</th>
      <th rowspan="2" style="vertical-align:middle">Closing Value<br>(Purchase Price)</th>
      <th rowspan="2" style="vertical-align:middle">Closing Value<br>(Sale Price)</th>
    </tr>
    <tr>
      <th>QQ</th><th>PP</th><th>NN</th><th>DD</th>
      <th>QQ</th><th>PP</th><th>NN</th><th>DD</th>
      <th>QQ</th><th>PP</th><th>NN</th><th>DD</th>
    </tr>
  </thead>
  <tbody>
    $rowsHtml
    <tr class="totrow">
      <td colspan="2">TOTAL</td>
      <td class="n">${qty(totCbQQ)}</td><td class="n">${qty(totCbPP)}</td>
      <td class="n">${qty(totCbNN)}</td><td class="n">${qty(totCbDD)}</td>
      <td class="n"></td><td class="n"></td><td class="n"></td><td class="n"></td>
      <td class="n"></td><td class="n"></td><td class="n"></td><td class="n"></td>
      <td class="r">${cur(totValuePurchase)}</td>
      <td class="r">${cur(totValueSale)}</td>
    </tr>
  </tbody>
</table>
<div style="display:flex;justify-content:flex-end;margin-top:14px">
  <table style="border-collapse:collapse;width:auto;min-width:320px;border:1px solid #b0b8d4;font-size:11px">
    <tbody>
      <tr><td style="padding:6px 12px;font-weight:bold;border-bottom:1px solid #e0e0e0">CLOSING STOCK VALUE (AT PURCHASE PRICE)</td><td style="padding:6px 12px;font-weight:bold;text-align:right;border-bottom:1px solid #e0e0e0">${cur(totValuePurchase)}</td></tr>
      <tr><td style="padding:6px 12px;font-weight:bold;border-bottom:1px solid #e0e0e0">CLOSING STOCK VALUE (AT SALE PRICE)</td><td style="padding:6px 12px;font-weight:bold;text-align:right;border-bottom:1px solid #e0e0e0">${cur(totValueSale)}</td></tr>
      <tr style="background:#1a237e"><td style="padding:6px 12px;color:white;font-weight:bold">MARGIN (POTENTIAL PROFIT)</td><td style="padding:6px 12px;color:white;font-weight:bold;text-align:right">${cur(totValueSale - totValuePurchase)}</td></tr>
    </tbody>
  </table>
</div>
<div class="wmark"><span class="wmark-app">WinEntry</span><span class="wmark-co">Simhadri Systems</span></div>
</body></html>"""
    }
}
