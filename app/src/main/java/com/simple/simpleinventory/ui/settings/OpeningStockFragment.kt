package com.simple.simpleinventory.ui.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.simple.simpleinventory.data.AppDatabase
import com.simple.simpleinventory.data.entity.DailyStock
import com.simple.simpleinventory.data.entity.Product
import com.simple.simpleinventory.data.entity.SyncStatus
import com.simple.simpleinventory.data.entity.stockCode
import com.simple.simpleinventory.data.repository.DailyStockRepository
import com.simple.simpleinventory.utils.DailyStockImportHelper
import com.simple.simpleinventory.ui.dailystock.DailyStockDataViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Opening Stock Setup — enter initial stock quantities for a new period.
 *
 * Uses RecyclerView so 100 products scroll smoothly. The toolbar is fixed
 * at the top (non-scrolling) and the product list scrolls independently.
 */
class OpeningStockFragment : Fragment() {

    private lateinit var repository: DailyStockRepository
    private lateinit var products: List<Product>

    private val quantities = mutableMapOf<Long, IntArray>()

    private lateinit var tvSummary: TextView
    private lateinit var tvTotalValue: TextView
    private lateinit var btnSave: Button
    private lateinit var btnEdit: Button
    private lateinit var btnDatePick: Button
    private lateinit var tvDateDisplay: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: OpeningStockAdapter
    private lateinit var fabTop: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var fabBottom: com.google.android.material.floatingactionbutton.FloatingActionButton

    private var selectedDate: String = defaultDate()

    private lateinit var dataViewModel: DailyStockDataViewModel
    private lateinit var savedDatesAdapter: SavedDatesAdapter
    /**
     * True when the currently selected date already has committed opening stock
     * in the DB. Derived from DB on every loadProducts() — survives app restarts.
     * Cleared when user explicitly unlocks via confirmation dialog.
     */
    private var isCurrentDateLocked: Boolean = false
    /** True when user has tapped Edit or imported — enables EditTexts and Save. */
    private var isEditMode: Boolean = false
    /** True when quantities have been changed from the committed values. */
    private var hasUnsavedChanges: Boolean = false

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            importHelper?.let { dataViewModel.prepareClosingImport(uri, it) }
        }
    }
    private var importHelper: DailyStockImportHelper? = null

    companion object {
        fun defaultDate(): String {
            // Neutral fallback — initialiseScreen() overrides this with the
            // earliest committed date (or user-chosen date from the start dialog)
            val cal = Calendar.getInstance()
            cal.set(Calendar.DAY_OF_MONTH, 1)
            return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
        }
    }

    // ── View construction ─────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val db = AppDatabase.getInstance(requireContext())
        repository = DailyStockRepository(db.productDao(), db.dailyStockDao())

        // Root = vertical LinearLayout (toolbar fixed, content scrolls below)
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(androidx.core.content.ContextCompat.getColor(requireContext(), com.simple.simpleinventory.R.color.app_surface))
        }

        // ── Fixed toolbar ─────────────────────────────────────────────────────
        val toolbar = Toolbar(requireContext()).apply {
            title = "Opening Stock Setup"
            setBackgroundColor(androidx.core.content.ContextCompat.getColor(requireContext(), com.simple.simpleinventory.R.color.app_toolbar))
            setTitleTextColor(android.graphics.Color.WHITE)
            setNavigationIcon(com.simple.simpleinventory.R.drawable.ic_chevron_left)
            setNavigationOnClickListener { findNavController().navigateUp() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        // Search icon in toolbar
        val searchItem = toolbar.menu.add(0, android.R.id.edit, 0, "Search")
        searchItem.setIcon(android.R.drawable.ic_menu_search)
        searchItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
        val searchView = androidx.appcompat.widget.SearchView(requireContext()).apply {
            queryHint = "Search products…"
            val searchText = findViewById<androidx.appcompat.widget.SearchView.SearchAutoComplete>(
                androidx.appcompat.R.id.search_src_text)
            searchText?.setTextColor(android.graphics.Color.WHITE)
            searchText?.setHintTextColor(android.graphics.Color.parseColor("#B3FFFFFF"))
            setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(q: String?) = false
                override fun onQueryTextChange(q: String?): Boolean {
                    filterProducts(q ?: "")
                    return true
                }
            })
        }
        searchItem.actionView = searchView
        root.addView(toolbar)

        // ── Fixed header panel (date + summary) ──────────────────────────────
        val headerPanel = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.WHITE)
            elevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Date row
        val dateRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        dateRow.addView(TextView(requireContext()).apply {
            text = "First trading day:"
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#475569"))
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        // Date shown as plain text — not a picker button when data is committed
        tvDateDisplay = TextView(requireContext()).apply {
            text = formatDisplay(selectedDate)
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#2563EB"))
            gravity = android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        // btnDatePick kept for fresh setup flow — hidden when DB has data
        btnDatePick = Button(requireContext()).apply {
            text = formatDisplay(selectedDate)
            textSize = 12f
            visibility = View.GONE   // shown only for fresh DB setup
            setOnClickListener { pickDate() }
        }
        dateRow.addView(tvDateDisplay)
        dateRow.addView(btnDatePick)
        headerPanel.addView(dateRow)

        // Summary bar — product count + total units + total value
        tvSummary = TextView(requireContext()).apply {
            text = "No quantities entered"
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#1B5E20"))
            setBackgroundColor(android.graphics.Color.parseColor("#F1F8E9"))
            setPadding(dp(12), dp(6), dp(12), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        headerPanel.addView(tvSummary)

        tvTotalValue = TextView(requireContext()).apply {
            text = ""
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#1B5E20"))
            setBackgroundColor(android.graphics.Color.parseColor("#F1F8E9"))
            setPadding(dp(12), dp(2), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        headerPanel.addView(tvTotalValue)

        // Column header row
        headerPanel.addView(buildColumnHeader())
        root.addView(headerPanel)

        // ── RecyclerView in FrameLayout with scroll FABs ──────────────────────
        val rvFrame = android.widget.FrameLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        recyclerView = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(requireContext())
            setHasFixedSize(false)
            (itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
                ?.supportsChangeAnimations = false
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT)
        }
        rvFrame.addView(recyclerView)

        // Scroll FABs — top-right and bottom-right corners
        val fabSize = dp(36)
        val fabMargin = dp(8)

        fabTop = com.google.android.material.floatingactionbutton.FloatingActionButton(
            requireContext()).apply {
            setImageResource(android.R.drawable.arrow_up_float)
            size = com.google.android.material.floatingactionbutton.FloatingActionButton.SIZE_MINI
            visibility = View.GONE
            alpha = 0.4f
            val lp = android.widget.FrameLayout.LayoutParams(fabSize, fabSize)
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.END
            lp.topMargin = fabMargin; lp.marginEnd = fabMargin
            layoutParams = lp
            setOnClickListener { recyclerView.smoothScrollToPosition(0) }
        }

        fabBottom = com.google.android.material.floatingactionbutton.FloatingActionButton(
            requireContext()).apply {
            setImageResource(android.R.drawable.arrow_down_float)
            size = com.google.android.material.floatingactionbutton.FloatingActionButton.SIZE_MINI
            visibility = View.GONE
            alpha = 0.4f
            val lp = android.widget.FrameLayout.LayoutParams(fabSize, fabSize)
            lp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            lp.bottomMargin = fabMargin; lp.marginEnd = fabMargin
            layoutParams = lp
            setOnClickListener {
                val last = (recyclerView.adapter?.itemCount ?: 1) - 1
                recyclerView.smoothScrollToPosition(last)
            }
        }

        rvFrame.addView(fabTop)
        rvFrame.addView(fabBottom)
        root.addView(rvFrame)

        // ── Fixed bottom bar: Import + Save ───────────────────────────────────
        val bottomBar = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(android.graphics.Color.parseColor("#F5F5F5"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            )
        }

        val btnImport = Button(requireContext()).apply {
            text = "📥 Import"
            textSize = 12f
            setBackgroundColor(android.graphics.Color.parseColor("#37474F"))
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                .also { it.marginEnd = dp(2) }
            setOnClickListener {
                importLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    addCategory(Intent.CATEGORY_OPENABLE)
                })
            }
        }

        btnEdit = Button(requireContext()).apply {
            text = "✎ Edit"
            textSize = 12f
            setBackgroundColor(android.graphics.Color.parseColor("#E65100"))
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                .also { it.marginEnd = dp(2) }
            setOnClickListener { enterEditMode() }
        }

        btnSave = Button(requireContext()).apply {
            text = "💾 Save"
            textSize = 12f
            setBackgroundColor(android.graphics.Color.parseColor("#1565C0"))
            setTextColor(android.graphics.Color.WHITE)
            isEnabled = false
            alpha = 0.4f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener { confirmSave() }
        }

        bottomBar.addView(btnImport)
        bottomBar.addView(btnEdit)
        bottomBar.addView(btnSave)
        root.addView(bottomBar)

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.hide()

        dataViewModel = ViewModelProvider(this)[DailyStockDataViewModel::class.java]
        importHelper  = DailyStockImportHelper(requireContext().applicationContext)

        // Create adapters ONCE — loadProducts() only updates data, never recreates these
        adapter = OpeningStockAdapter(
            products    = emptyList(),
            quantities  = quantities,
            onChanged   = {
                hasUnsavedChanges = true
                updateSummary()
                updateDateLockUI()
            },
            onNextFromLast = { position ->
                val nextPos = position + 1
                if (nextPos < products.size) {
                    recyclerView.scrollToPosition(nextPos)
                    recyclerView.post {
                        val vh = recyclerView.findViewHolderForAdapterPosition(nextPos)
                                as? OpeningStockAdapter.VH
                        vh?.edits?.firstOrNull()?.let { et ->
                            et.requestFocus(); et.post { et.selectAll() }
                        }
                    }
                }
            }
        )
        savedDatesAdapter = SavedDatesAdapter(
            onDelete = { date, count -> confirmDelete(date, count) }
        )
        recyclerView.adapter = androidx.recyclerview.widget.ConcatAdapter(
            adapter, savedDatesAdapter)

        setupImportObserver()
        setupScrollFabs()
        initialiseScreen()
    }

    private fun setupScrollFabs() {
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val first  = lm.findFirstVisibleItemPosition()
                val last   = lm.findLastVisibleItemPosition()
                val total  = rv.adapter?.itemCount ?: 0
                val atTop    = first <= 1
                val atBottom = last >= total - 1
                fabTop.visibility    = if (atTop)    View.GONE else View.VISIBLE
                fabBottom.visibility = if (atBottom) View.GONE else View.VISIBLE
            }
        })
    }

    // ── Search / filter ───────────────────────────────────────────────────────

    private var searchQuery: String = ""

    private fun filterProducts(query: String) {
        searchQuery = query.trim().lowercase()
        val filtered = if (searchQuery.isEmpty()) products
        else products.filter { p ->
            p.displayName.lowercase().contains(searchQuery) ||
            p.stockCode.lowercase().contains(searchQuery) ||
            p.brandCode.lowercase().contains(searchQuery)
        }
        adapter.updateProducts(filtered)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        (activity as? AppCompatActivity)?.supportActionBar?.show()
    }

    // ── Import observer — reuses DailyStockDataViewModel import flow ──────────

    private fun setupImportObserver() {
        var progressDialog: android.app.Dialog? = null
        fun getOrCreateDialog() = progressDialog
            ?: MaterialAlertDialogBuilder(requireContext())
                .setTitle("Importing…")
                .setMessage("Parsing Excel and checking data. Please wait.")
                .setCancelable(false)
                .create().also { progressDialog = it }

        // Stage 1: parsed and analysed — show confirmation dialog
        dataViewModel.pendingImport.observe(viewLifecycleOwner) { pending ->
            if (pending == null) return@observe
            val result      = pending.result
            val prods       = pending.products
            val anomalyList = pending.anomalyList

            val dateBreakdown = result.closingData.values
                .groupBy { it.date }.toSortedMap()
                .entries.joinToString("\n") { (d, recs) ->
                    "  ${formatDisplay(d)}: ${recs.size} product(s)"
                }

            val summaryMsg = buildString {
                appendLine("Dates found in file:")
                appendLine(dateBreakdown)
                appendLine()
                appendLine("These quantities will be saved as opening stock.")
                if (result.unknownProducts.isNotEmpty())
                    appendLine("\n⚠️ ${result.unknownProducts.size} unknown product(s) skipped.")
                if (anomalyList.isNotEmpty())
                    appendLine("\n🔴 ${anomalyList.size} anomaly(ies) detected.")
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Import as Opening Stock")
                .setMessage(summaryMsg.trim())
                .setPositiveButton("Import") { _, _ ->
                    // Auto-navigate to first imported date so grid shows it after import
                    val firstImportedDate = result.closingData.values
                        .minByOrNull { it.date }?.date
                    if (firstImportedDate != null) {
                        selectedDate = firstImportedDate
                        btnDatePick.text = formatDisplay(firstImportedDate)
                    }
                    dataViewModel.clearPendingImport()
                    // Use opening-stock-specific save: OB = CB = imported qty, sale = 0
                    dataViewModel.applyImportedAsOpeningStock(result, prods)
                }
                .setNegativeButton("Cancel") { _, _ ->
                    dataViewModel.clearPendingImport()
                }
                .show()
        }

        // Stage 2: saving progress / result
        dataViewModel.importStatus.observe(viewLifecycleOwner) { status ->
            when (status) {
                is DailyStockDataViewModel.ImportStatus.Idle ->
                    progressDialog?.dismiss()
                is DailyStockDataViewModel.ImportStatus.Running ->
                    getOrCreateDialog().also { if (!it.isShowing) it.show() }
                is DailyStockDataViewModel.ImportStatus.Success -> {
                    progressDialog?.dismiss()
                    dataViewModel.clearImportStatus()
                    // Import = changes exist — activate Save button
                    isEditMode        = true
                    hasUnsavedChanges = true
                    loadProducts()
                    Toast.makeText(requireContext(),
                        "✓ Imported ${status.count} product(s) as opening stock.",
                        Toast.LENGTH_LONG).show()
                }
                is DailyStockDataViewModel.ImportStatus.Error -> {
                    progressDialog?.dismiss()
                    Toast.makeText(requireContext(),
                        "✗ ${status.message}", Toast.LENGTH_LONG).show()
                    dataViewModel.clearImportStatus()
                }
            }
        }
    }

    // ── Column header ─────────────────────────────────────────────────────────

    private fun buildColumnHeader(): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(android.graphics.Color.parseColor("#1565C0"))
            setPadding(dp(8), dp(5), dp(8), dp(5))
            addView(headerCell("Product / Code", 0, 2.8f))
            addView(headerCell("QQ", android.view.Gravity.CENTER))
            addView(headerCell("PP", android.view.Gravity.CENTER))
            addView(headerCell("NN", android.view.Gravity.CENTER))
            addView(headerCell("DD", android.view.Gravity.CENTER))
            addView(headerCell("Units\n₹Value", android.view.Gravity.CENTER))
        }
    }

    private fun headerCell(text: String, gravity: Int, weight: Float = 1f) =
        TextView(requireContext()).apply {
            this.text = text
            textSize = 11f
            setTextColor(android.graphics.Color.WHITE)
            this.gravity = gravity
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        }

    // ── Screen initialisation — checks DB before showing entry grid ──────────

    /**
     * Entry point on screen open.
     *
     * 1. Query DB for the earliest committed date (= true opening stock date).
     * 2a. DB has data → navigate to that date, show it locked.
     * 2b. DB is empty → show "Choose start date" dialog before loading grid.
     */
    // ── Screen initialisation ─────────────────────────────────────────────────

    /**
     * Always called on screen open. Shows a status dialog before loading grid:
     *
     * Case A — DB has committed data:
     *   Dialog shows earliest committed date (read-only info).
     *   Only that date is loaded into the grid. Date picker is hidden — user
     *   cannot navigate to any other date from this screen.
     *
     * Case B — DB is empty (fresh install / after full clear):
     *   Dialog says "no committed data" and offers a date picker restricted to
     *   any date from (today - 2 years) up to (today + 7 days).
     *   User picks start date and taps Begin → grid loads for that date.
     */
    private fun initialiseScreen() {
        lifecycleScope.launch {
            val earliest = dataViewModel.getEarliestCommittedDate()
            if (earliest != null) {
                showExistingDataDialog(earliest)
            } else {
                showFreshStartDialog()
            }
        }
    }

    /** Case A: DB has data — show earliest date, load read-only. */
    private fun showExistingDataDialog(earliest: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("📦 Opening Stock")
            .setMessage(
                "Opening stock date on record: ${formatDisplay(earliest)}\n\n" +
                "Quantities shown in read-only mode.\n\n" +
                "• Tap ✎ Edit to correct quantities\n" +
                "• Tap 📥 Import to load from Excel\n" +
                "• Scroll down to delete this record"
            )
            .setPositiveButton("View") { _, _ ->
                selectedDate      = earliest
                isEditMode        = false
                hasUnsavedChanges = false
                tvDateDisplay.text       = formatDisplay(earliest)
                tvDateDisplay.visibility = View.VISIBLE
                btnDatePick.visibility   = View.GONE
                loadProducts()
            }
            .setNegativeButton("Cancel") { _, _ ->
                findNavController().navigateUp()
            }
            .setCancelable(false)
            .show()
    }

    /** Case B: DB is empty — let user pick a start date (past or up to today+7). */
    private fun showFreshStartDialog() {
        val sdf  = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val ctx  = requireContext()
        fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

        // Default = 1st of current month
        val cal = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
        var chosenDate = sdf.format(cal.time)

        // Max allowed = today + 7
        val maxCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 7) }
        val maxDate = maxCal.timeInMillis

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        panel.addView(TextView(ctx).apply {
            text = "No opening stock data found in this device.\n\n" +
                   "Select your first trading day below, then:\n" +
                   "• Enter quantities manually in the grid, or\n" +
                   "• Use Import Excel to load from your closing balance file\n\n" +
                   "Opening balances will be set for the chosen date."
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#212121"))
        })

        val btnDate = Button(ctx).apply {
            text = formatDisplay(chosenDate)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(16) }
        }
        panel.addView(btnDate)

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle("📦 Opening Stock — New Setup")
            .setView(panel)
            .setPositiveButton("Begin Entry") { _, _ ->
                selectedDate             = chosenDate
                isEditMode               = true    // fresh DB — start in edit mode
                hasUnsavedChanges        = false
                tvDateDisplay.text       = formatDisplay(chosenDate)
                tvDateDisplay.visibility = View.VISIBLE
                btnDatePick.text         = formatDisplay(chosenDate)
                btnDatePick.visibility   = View.VISIBLE   // allow date change for fresh setup
                isCurrentDateLocked      = false
                loadProducts()
            }
            .setNegativeButton("Cancel") { _, _ ->
                findNavController().navigateUp()
            }
            .setCancelable(false)
            .create()

        btnDate.setOnClickListener {
            val c = Calendar.getInstance().apply {
                time = sdf.parse(chosenDate) ?: Date()
            }
            android.app.DatePickerDialog(ctx, { _, y, m, d ->
                c.set(y, m, d)
                chosenDate = sdf.format(c.time)
                btnDate.text = formatDisplay(chosenDate)
            }, c.get(Calendar.YEAR),
               c.get(Calendar.MONTH),
               c.get(Calendar.DAY_OF_MONTH)
            ).apply {
                setTitle("Select first trading day")
                datePicker.maxDate = maxDate  // restrict to today + 7
            }.show()
        }

        dialog.show()
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private fun loadProducts() {
        lifecycleScope.launch {
            products = repository.getAllProductsSync()  // all products — inactive still need opening stock
            quantities.clear()
            products.forEach { quantities[it.id] = IntArray(4) }

            products.forEach { product ->
                val existing = repository.getDailyStockRaw(selectedDate, product.stockCode)
                if (existing?.isCommitted == true) {
                    quantities[product.id] = intArrayOf(
                        existing.openQq, existing.openPp,
                        existing.openNn, existing.openDd
                    )
                }
            }

            // Lock if this is the earliest committed date and data exists for it.
            // All other dates (from saved-dates Edit) are read-only display only —
            // date picker is hidden so user cannot navigate there directly.
            val earliestDate = dataViewModel.getEarliestCommittedDate()
            isCurrentDateLocked = (earliestDate != null) &&
                                   (selectedDate == earliestDate) &&
                                   products.any { p ->
                                       repository.getDailyStockRaw(selectedDate, p.stockCode)
                                           ?.isCommitted == true
                                   }

            adapter.updateProducts(products)
            // Reset dirty state whenever date changes or data reloads
            if (!isEditMode) hasUnsavedChanges = false
            updateSummary()
            updateDateLockUI()
            refreshSavedDates()
        }
    }

    private fun refreshSavedDates() {
        lifecycleScope.launch {
            // Show exactly one entry — the earliest committed date (= the opening stock date).
            // No complex query needed; this is the one date the user can view/correct/delete.
            val earliest = dataViewModel.getEarliestCommittedDate()
            if (earliest == null) {
                savedDatesAdapter.submitEntries(emptyList())
                return@launch
            }
            val count = repository.getAllDailyStockForDate(earliest).count { it.isCommitted }
            savedDatesAdapter.submitEntries(listOf(
                SavedDatesAdapter.Entry(earliest, count, earliest == selectedDate)
            ))
        }
    }

    private fun confirmDelete(date: String, productCount: Int) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Opening Stock")
            .setMessage(
                "Delete all opening stock entries for\n${formatDisplay(date)}?\n\n" +
                "$productCount product(s) will be removed.\n\n" +
                "⚠ Any Daily Stock data that used these as opening balances " +
                "will no longer have prior history."
            )
            .setPositiveButton("Delete") { _, _ ->
                dataViewModel.clearDateData(date)
                // If deleted date was selected, reset quantities
                if (date == selectedDate) {
                    quantities.keys.forEach { quantities[it] = IntArray(4) }
                    if (::adapter.isInitialized) adapter.updateProducts(products)
                    updateSummary()
                }
                refreshSavedDates()
                Toast.makeText(requireContext(),
                    "Opening stock for ${formatDisplay(date)} deleted.",
                    Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Summary ───────────────────────────────────────────────────────────────

    private fun updateSummary() {
        val productCount = quantities.values.count { it.sum() > 0 }
        val totalUnits   = quantities.values.sumOf { it.sum() }

        // Total value = qty × sale price per size, summed across all products
        val totalValue = products.sumOf { p ->
            val qty = quantities[p.id] ?: IntArray(4)
            qty[0] * p.qqSalePrice + qty[1] * p.ppSalePrice +
            qty[2] * p.nnSalePrice + qty[3] * p.ddSalePrice
        }

        if (totalUnits == 0) {
            tvSummary.text    = "No quantities entered — leave zero for products not in stock"
            tvTotalValue.text = ""
        } else {
            tvSummary.text    = "✓ $productCount product(s) · $totalUnits total units"
            tvTotalValue.text = "Total opening stock value: ₹${String.format("%,.2f", totalValue)}"
        }
    }

    // ── Date navigation ───────────────────────────────────────────────────────

    /**
     * Date picker button — only shown when DB is empty (fresh setup).
     * Once data is committed the button is hidden; user cannot navigate to
     * other dates from this screen.
     */
    private fun pickDate() {
        if (isCurrentDateLocked) {
            // Inform user — no unlock here, this is the permanent record
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("🔒 Opening Stock Committed")
                .setMessage(
                    "Opening stock for ${formatDisplay(selectedDate)} is committed.\n\n" +
                    "To correct quantities, use the Save button below — " +
                    "it will overwrite the existing record."
                )
                .setPositiveButton("OK", null)
                .show()
            return
        }
        // Fresh setup — allow picking any date up to today + 7
        val sdf    = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val cal    = Calendar.getInstance().apply { time = sdf.parse(selectedDate) ?: Date() }
        val maxCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 7) }
        android.app.DatePickerDialog(
            requireContext(),
            { _, y, m, d ->
                cal.set(y, m, d)
                selectedDate = sdf.format(cal.time)
                btnDatePick.text = formatDisplay(selectedDate)
                loadProducts()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).apply {
            setTitle("Select first trading day")
            datePicker.maxDate = maxCal.timeInMillis
        }.show()
    }

    /**
     * Called from saved-dates Edit button.
     * Non-earliest dates are display-only — shown with quantities but Save disabled.
     * Earliest date is shown locked but Save is enabled for corrections.
     */
    private fun requestDateChange(date: String) {
        selectedDate = date
        btnDatePick.text = formatDisplay(date)
        loadProducts()
    }

    /** Apply UI state based on isEditMode and hasUnsavedChanges. */
    private fun updateDateLockUI() {
        // Date display
        tvDateDisplay.text = if (isCurrentDateLocked)
            "${formatDisplay(selectedDate)}  🔒"
        else
            formatDisplay(selectedDate)

        // Edit button — hidden when already in edit mode
        btnEdit.visibility = if (isEditMode) View.GONE else View.VISIBLE

        // Save button — active only when in edit mode AND changes exist
        val saveActive = isEditMode && hasUnsavedChanges
        btnSave.isEnabled = saveActive
        btnSave.alpha     = if (saveActive) 1.0f else 0.4f
        btnSave.text      = if (isCurrentDateLocked) "💾 Update Opening Stock"
                            else                     "💾 Save Opening Stock"

        // Apply read-only / editable state to all product EditText fields
        adapter.setEditMode(isEditMode)
    }

    private fun enterEditMode() {
        isEditMode = true
        updateDateLockUI()
        Toast.makeText(requireContext(),
            "Edit mode — modify quantities then tap Save.",
            Toast.LENGTH_SHORT).show()
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    private fun confirmSave() {
        val totalUnits = quantities.values.sumOf { it.sum() }
        if (totalUnits == 0) {
            Toast.makeText(requireContext(),
                "No quantities entered.", Toast.LENGTH_SHORT).show()
            return
        }
        val productCount = quantities.values.count { it.sum() > 0 }
        val totalValue = products.sumOf { p ->
            val qty = quantities[p.id] ?: IntArray(4)
            qty[0] * p.qqSalePrice + qty[1] * p.ppSalePrice +
            qty[2] * p.nnSalePrice + qty[3] * p.ddSalePrice
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Save Opening Stock")
            .setMessage(
                "First trading day: ${formatDisplay(selectedDate)}\n\n" +
                "$productCount product(s) · $totalUnits units\n" +
                "Total value: ₹${String.format("%,.2f", totalValue)}\n\n" +
                "Opening balances for $selectedDate will be set to these quantities."
            )
            .setPositiveButton("Save") { _, _ -> saveOpeningStock() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveOpeningStock() {
        btnSave.isEnabled = false
        btnSave.text = "Saving…"
        lifecycleScope.launch {
            try {
                val rows = products.mapNotNull { product ->
                    val qty = quantities[product.id] ?: return@mapNotNull null
                    if (qty.sum() == 0) return@mapNotNull null
                    DailyStock(
                        date        = selectedDate,
                        productCode = product.stockCode,
                        // OB = CB = entered qty, sale = 0
                        // This is the FIRST day of trading — opening stock IS the opening balance
                        openQq = qty[0], openPp = qty[1], openNn = qty[2], openDd = qty[3],
                        closeQq = qty[0], closePp = qty[1],
                        closeNn = qty[2], closeDd = qty[3],
                        saleQq = 0, salePp = 0, saleNn = 0, saleDd = 0,
                        priceQq = product.qqSalePrice, pricePp = product.ppSalePrice,
                        priceNn = product.nnSalePrice, priceDd = product.ddSalePrice,
                        amountQq = 0.0, amountPp = 0.0, amountNn = 0.0, amountDd = 0.0,
                        saleAmount  = 0.0,
                        isCommitted = true,
                        syncStatus  = SyncStatus.PENDING_UPSERT
                    )
                }
                repository.saveAllEntries(rows)
                // Reset to read-only mode after successful save
                isEditMode        = false
                hasUnsavedChanges = false
                Toast.makeText(requireContext(),
                    "✓ Opening stock saved — ${rows.size} product(s) committed.",
                    Toast.LENGTH_LONG).show()
                // Reload — isCurrentDateLocked re-derived from DB, UI back to read-only
                loadProducts()
            } catch (e: Exception) {
                updateDateLockUI()   // restore correct button state
                Toast.makeText(requireContext(),
                    "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun formatDisplay(date: String): String {
        return try {
            val sdf  = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val disp = SimpleDateFormat("dd MMM yyyy (EEE)", Locale.getDefault())
            disp.format(sdf.parse(date)!!)
        } catch (_: Exception) { date }
    }

    private fun nextDayDisplay(): String {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val disp = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            val cal = Calendar.getInstance().apply {
                time = sdf.parse(selectedDate)!!
                add(Calendar.DAY_OF_MONTH, 1)
            }
            disp.format(cal.time)
        } catch (_: Exception) { "the following day" }
    }

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).toInt()
}

// ── RecyclerView Adapter ──────────────────────────────────────────────────────

private class OpeningStockAdapter(
    products:                List<Product>,
    private val quantities:  MutableMap<Long, IntArray>,
    private val onChanged:   () -> Unit,
    private val onNextFromLast: (position: Int) -> Unit = {}
) : RecyclerView.Adapter<OpeningStockAdapter.VH>() {

    private var products: List<Product> = products
    private var editMode: Boolean = false

    fun setEditMode(enabled: Boolean) {
        editMode = enabled
        notifyItemRangeChanged(0, products.size)
    }

    /**
     * Update product list in-place. The quantities map is a shared reference
     * between Fragment and adapter — Fragment already updated it before calling
     * this, so we only need to refresh the product list and rebind.
     */
    fun updateProducts(newProducts: List<Product>) {
        val oldCount = products.size
        products = newProducts
        val newCount = products.size
        when {
            oldCount == 0 && newCount > 0 -> notifyItemRangeInserted(0, newCount)
            newCount == 0 && oldCount > 0 -> notifyItemRangeRemoved(0, oldCount)
            oldCount == newCount          -> notifyItemRangeChanged(0, newCount)
            oldCount < newCount           -> {
                notifyItemRangeChanged(0, oldCount)
                notifyItemRangeInserted(oldCount, newCount - oldCount)
            }
            else                          -> {
                notifyItemRangeChanged(0, newCount)
                notifyItemRangeRemoved(newCount, oldCount - newCount)
            }
        }
    }

    override fun getItemCount() = products.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

        // Row = horizontal LinearLayout
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // ── Product name + code + prices column ───────────────────────────────
        val nameCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 2.8f)
        }
        val tvName = TextView(ctx).apply {
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#212121"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val tvCodePrices = TextView(ctx).apply {
            textSize = 10f
            setTextColor(android.graphics.Color.parseColor("#757575"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(1) }
        }
        nameCol.addView(tvName)
        nameCol.addView(tvCodePrices)
        row.addView(nameCol)

        // ── Four EditText columns (QQ/PP/NN/DD) ───────────────────────────────
        val edits = Array(4) {
            EditText(ctx).apply {
                hint = "0"
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                background = null
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }.also { row.addView(it) }
        }

        // ── Total units + value column ────────────────────────────────────────
        val tvTotal = TextView(ctx).apply {
            textSize = 11f
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.parseColor("#1565C0"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(tvTotal)

        return VH(row, tvName, tvCodePrices, edits, tvTotal)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val product = products[position]

        // Always read qty fresh from map — never capture a stale snapshot.
        // updateProducts() replaces array instances; a captured val would be stale.
        fun liveQty() = quantities[product.id] ?: IntArray(4)

        holder.itemView.setBackgroundColor(
            if (position % 2 == 0) android.graphics.Color.WHITE
            else android.graphics.Color.parseColor("#F5F7FF")
        )

        holder.tvName.text = product.displayName
        holder.tvCodePrices.text = buildString {
            append(product.stockCode)
            append("  |  ")
            if (product.qqSalePrice > 0) append("QQ:₹${product.qqSalePrice.toInt()} ")
            if (product.ppSalePrice > 0) append("PP:₹${product.ppSalePrice.toInt()} ")
            if (product.nnSalePrice > 0) append("NN:₹${product.nnSalePrice.toInt()} ")
            if (product.ddSalePrice > 0) append("DD:₹${product.ddSalePrice.toInt()}")
        }

        // Apply read-only / editable state
        val editable = editMode
        holder.edits.forEach { et ->
            et.isEnabled              = editable
            et.isFocusable            = editable
            et.isFocusableInTouchMode = editable
            et.alpha                  = if (editable) 1.0f else 0.7f
        }

        // Remove old watchers, set current values from live map
        holder.edits.forEachIndexed { i, et ->
            et.removeTextChangedListener(holder.watchers[i])
            val v = liveQty()[i]
            et.setText(if (v > 0) v.toString() else "")
        }

        // Attach watchers only when editable
        if (editable) {
            holder.edits.forEachIndexed { i, et ->
                val watcher = object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                    override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        quantities[product.id]?.set(i, s.toString().toIntOrNull() ?: 0)
                        refreshTotal(holder, product, liveQty())
                        onChanged()
                    }
                }
                holder.watchers[i] = watcher
                et.addTextChangedListener(watcher)
            }
            wireImeChain(holder.edits, position)
        }

        refreshTotal(holder, product, liveQty())
    }

    private fun wireImeChain(edits: Array<EditText>, position: Int) {
        for (i in edits.indices) {
            val current = edits[i]
            current.imeOptions = EditorInfo.IME_ACTION_NEXT
            current.setSingleLine(true)
            if (i < edits.lastIndex) {
                val next = edits[i + 1]
                current.setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_NEXT) {
                        next.requestFocus()
                        next.post { next.selectAll() }
                        true
                    } else false
                }
            } else {
                // Last field (DD) — move to first field of next row
                current.setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_NEXT) {
                        onNextFromLast(position)
                        true
                    } else false
                }
            }
        }
    }

    private fun refreshTotal(holder: VH, product: Product, qty: IntArray) {
        val units = qty.sum()
        val value = qty[0] * product.qqSalePrice + qty[1] * product.ppSalePrice +
                    qty[2] * product.nnSalePrice + qty[3] * product.ddSalePrice
        if (units > 0) {
            holder.tvTotal.text = "$units\n₹${formatVal(value)}"
            holder.tvTotal.setTextColor(android.graphics.Color.parseColor("#1565C0"))
        } else {
            holder.tvTotal.text = ""
        }
    }

    private fun formatVal(v: Double): String {
        return if (v >= 1000) String.format("%,.0f", v) else String.format("%.0f", v)
    }

    class VH(
        itemView:           View,
        val tvName:         TextView,
        val tvCodePrices:   TextView,
        val edits:          Array<EditText>,
        val tvTotal:        TextView,
        val watchers:       Array<TextWatcher?> = arrayOfNulls(4)
    ) : RecyclerView.ViewHolder(itemView)
}
// ── Saved Dates Footer Adapter ────────────────────────────────────────────────

private class SavedDatesAdapter(
    private val onDelete: (date: String, count: Int) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    data class Entry(val date: String, val count: Int, val isSelected: Boolean)

    private val ITEM_HEADER = 0
    private val ITEM_DATE   = 1

    private var entries: List<Entry> = emptyList()

    fun submitEntries(newEntries: List<Entry>) {
        val oldCount = getItemCount()
        entries = newEntries
        val newCount = getItemCount()
        when {
            oldCount == 0 && newCount > 0 -> notifyItemRangeInserted(0, newCount)
            newCount == 0 && oldCount > 0 -> notifyItemRangeRemoved(0, oldCount)
            oldCount == newCount          -> notifyItemRangeChanged(0, newCount)
            oldCount < newCount           -> {
                notifyItemRangeChanged(0, oldCount)
                notifyItemRangeInserted(oldCount, newCount - oldCount)
            }
            else                          -> {
                notifyItemRangeChanged(0, newCount)
                notifyItemRangeRemoved(newCount, oldCount - newCount)
            }
        }
    }

    // header row + one row per entry; hidden entirely when empty
    override fun getItemCount() = if (entries.isEmpty()) 0 else entries.size + 1

    override fun getItemViewType(position: Int) =
        if (position == 0) ITEM_HEADER else ITEM_DATE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val ctx = parent.context
        fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

        return if (viewType == ITEM_HEADER) {
            val tv = TextView(ctx).apply {
                text = "  SAVED OPENING STOCK DATES"
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.parseColor("#78909C"))
                setBackgroundColor(android.graphics.Color.parseColor("#ECEFF1"))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            object : RecyclerView.ViewHolder(tv) {}
        } else {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(8), dp(10))
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val tvDate = TextView(ctx).apply {
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btnDel = Button(ctx).apply {
                text = "🗑 Delete"
                textSize = 11f
                setBackgroundColor(android.graphics.Color.parseColor("#C62828"))
                setTextColor(android.graphics.Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(dp(88), dp(36))
            }
            row.addView(tvDate)
            row.addView(btnDel)
            object : RecyclerView.ViewHolder(row) {
                val dateText = tvDate
                val delBtn   = btnDel
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (getItemViewType(position) == ITEM_HEADER) return
        val entry = entries[position - 1]

        val tvDate = holder.itemView.let {
            (it as? LinearLayout)?.getChildAt(0) as? TextView
        } ?: return
        val btnDel = (holder.itemView as? LinearLayout)?.getChildAt(1) as? Button ?: return

        val disp = try {
            val sdf  = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            val disp = java.text.SimpleDateFormat("dd MMM yyyy (EEE)", java.util.Locale.getDefault())
            disp.format(sdf.parse(entry.date)!!)
        } catch (_: Exception) { entry.date }

        tvDate.text = "$disp\n${entry.count} products"
        tvDate.setTextColor(
            if (entry.isSelected) android.graphics.Color.parseColor("#0D47A1")
            else android.graphics.Color.parseColor("#212121")
        )
        tvDate.setTypeface(null,
            if (entry.isSelected) android.graphics.Typeface.BOLD
            else android.graphics.Typeface.NORMAL)

        holder.itemView.setBackgroundColor(
            if (entry.isSelected) android.graphics.Color.parseColor("#E3F2FD")
            else if (position % 2 == 0) android.graphics.Color.WHITE
            else android.graphics.Color.parseColor("#FAFAFA")
        )

        btnDel.setOnClickListener { onDelete(entry.date, entry.count) }
    }
}
