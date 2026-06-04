package com.simhadri.winentry.ui.settings

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
import androidx.core.content.ContextCompat
import com.simhadri.winentry.R
import com.simhadri.winentry.data.AppDatabase
import com.simhadri.winentry.data.entity.DailyStock
import com.simhadri.winentry.data.entity.Product
import com.simhadri.winentry.data.entity.SyncStatus
import com.simhadri.winentry.data.entity.stockCode
import com.simhadri.winentry.data.repository.DailyStockRepository
import com.simhadri.winentry.utils.AppDialogs
import com.simhadri.winentry.utils.DailyStockImportHelper
import com.simhadri.winentry.ui.dailystock.DailyStockDataViewModel
import com.simhadri.winentry.sync.SyncCoordinator
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.io.File
import java.io.FileOutputStream
import androidx.core.content.FileProvider
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFWorkbook

/**
 * Opening Stock Setup — enter initial stock quantities for a new period.
 *
 * Uses RecyclerView so 100 products scroll smoothly. The toolbar is fixed
 * at the top (non-scrolling) and the product list scrolls independently.
 */
class OpeningStockFragment : Fragment() {

    private lateinit var repository: DailyStockRepository
    private var products: List<Product> = emptyList()

    private val quantities = mutableMapOf<Long, IntArray>()

    private lateinit var toolbar: androidx.appcompat.widget.Toolbar
    // ── Header info rows (3 coloured summary lines) ───────────────────────────
    private lateinit var tvDateInfo:    TextView   // row 1: date / trading day
    private lateinit var tvValueInfo:   TextView   // row 2: stock value
    private lateinit var tvProductInfo: TextView   // row 3: products + units
    // ── Bottom bar sync indicator ─────────────────────────────────────────────
    private lateinit var tvSyncStatus:  TextView   // static status chip
    private lateinit var btnSave: Button
    private lateinit var btnEdit: Button
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: OpeningStockAdapter
    private lateinit var fabTop: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var fabBottom: com.google.android.material.floatingactionbutton.FloatingActionButton

    private var selectedDate: String = defaultDate()

    private lateinit var dataViewModel: DailyStockDataViewModel
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
        // Sync chip states
        const val SYNC_SAVED   = "SAVED"
        const val SYNC_PENDING = "PENDING"
        const val SYNC_ERROR   = "ERROR"
        const val SYNC_NONE    = "NONE"

        fun defaultDate(): String {
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
            setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.app_surface))
        }

        // ── Fixed toolbar ─────────────────────────────────────────────────────
        toolbar = Toolbar(requireContext()).apply {
            title = "Opening Stock Setup"
            setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.app_toolbar))
            setTitleTextColor(android.graphics.Color.WHITE)
            setNavigationIcon(R.drawable.ic_chevron_left)
            setNavigationOnClickListener { findNavController().navigateUp() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        // Search — added to toolbar as a collapsible action view
        val searchItem = toolbar.menu.add(0, android.R.id.edit, 0, "Search")
        searchItem.setIcon(android.R.drawable.ic_menu_search)
        searchItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM or
                android.view.MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
        val searchView = androidx.appcompat.widget.SearchView(requireContext()).apply {
            queryHint = "Search products…"
            val searchText = findViewById<androidx.appcompat.widget.SearchView.SearchAutoComplete>(
                androidx.appcompat.R.id.search_src_text)
            searchText?.setTextColor(android.graphics.Color.WHITE)
            searchText?.setHintTextColor(android.graphics.Color.parseColor("#B3FFFFFF"))
            setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(q: String?): Boolean {
                    // Collapse the search bar and restore the search icon when user submits
                    searchItem.collapseActionView()
                    return true
                }
                override fun onQueryTextChange(q: String?): Boolean {
                    filterProducts(q ?: "")
                    return true
                }
            })
        }
        searchItem.actionView = searchView

        // Tint the SearchView's own internal icons white — the MenuItem icon tint
        // has no effect once an actionView is attached; each internal ImageView needs
        // its own ColorFilter.
        val white = android.graphics.Color.WHITE
        // Collapsed search icon (shown when search bar is not open)
        searchView.findViewById<android.widget.ImageView>(androidx.appcompat.R.id.search_button)
            ?.setColorFilter(white)
        // Clear (×) button shown while typing
        searchView.findViewById<android.widget.ImageView>(androidx.appcompat.R.id.search_close_btn)
            ?.setColorFilter(white)
        // Inline magnifier icon inside the open search field
        searchView.findViewById<android.widget.ImageView>(androidx.appcompat.R.id.search_mag_icon)
            ?.setColorFilter(white)

        // When search collapses (back pressed or X tapped to close the bar),
        // clear the product filter so the full list is shown again.
        searchItem.setOnActionExpandListener(object : android.view.MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: android.view.MenuItem) = true
            override fun onMenuItemActionCollapse(item: android.view.MenuItem): Boolean {
                filterProducts("")
                return true
            }
        })

        // ── Overflow (3-dot) menu ─────────────────────────────────────────────
        val MENU_TEMPLATE = 1000
        val MENU_IMPORT  = 1001
        val MENU_DELETE  = 1002
        val MENU_UPLOAD  = 1003
        val MENU_RESTORE = 1004
        toolbar.menu.add(0, MENU_TEMPLATE, 0, "📋 Download Template")
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        toolbar.menu.add(0, MENU_IMPORT,  1, "📥 Import from Excel")
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        toolbar.menu.add(0, MENU_DELETE,  2, "🗑 Delete Opening Stock")
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        toolbar.menu.add(0, MENU_UPLOAD,  3, "☁ Upload to Cloud")
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        toolbar.menu.add(0, MENU_RESTORE, 4, "☁ Restore from Cloud")
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        // Tint the overflow (3-dot) icon white after all items are added
        toolbar.overflowIcon?.setTint(android.graphics.Color.WHITE)

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_TEMPLATE -> { downloadTemplate(); true }
                MENU_IMPORT  -> {
                    importLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }); true
                }
                MENU_DELETE  -> { deleteOpeningStock(); true }
                MENU_UPLOAD  -> { uploadOpeningStock(); true }
                MENU_RESTORE -> { restoreFromCloud();   true }
                else         -> false
            }
        }

        root.addView(toolbar)

        // ── Fixed header panel — 3 equal-weight coloured info rows ──────────────
        val headerPanel = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            elevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val rowPad = dp(8)

        // Row 1 (blue) — date / first trading day; tappable in fresh-setup mode
        tvDateInfo = TextView(requireContext()).apply {
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.parseColor("#1565C0"))
            setPadding(dp(12), rowPad, dp(12), rowPad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        headerPanel.addView(tvDateInfo)

        // Row 2 (green) — opening stock value
        tvValueInfo = TextView(requireContext()).apply {
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#1B5E20"))
            setBackgroundColor(android.graphics.Color.parseColor("#E8F5E9"))
            setPadding(dp(12), rowPad, dp(12), rowPad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        headerPanel.addView(tvValueInfo)

        // Row 3 (amber) — product count + units
        tvProductInfo = TextView(requireContext()).apply {
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#E65100"))
            setBackgroundColor(android.graphics.Color.parseColor("#FFF8E1"))
            setPadding(dp(12), rowPad, dp(12), rowPad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        headerPanel.addView(tvProductInfo)

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

        // ── Fixed bottom bar: SyncStatus | Edit | Save (equal weights) ──────────
        val bottomBar = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            )
        }

        // Sync status — non-interactive display chip (weight 1, equal to buttons)
        tvSyncStatus = TextView(requireContext()).apply {
            text = "☁ SAVED IN CLOUD"
            textSize = 11f
            gravity = android.view.Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#1B5E20"))
            setBackgroundColor(android.graphics.Color.parseColor("#E8F5E9"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                .also { it.marginEnd = dp(2) }
        }

        btnEdit = Button(requireContext()).apply {
            text = "✎ EDIT"
            textSize = 12f
            setBackgroundColor(android.graphics.Color.parseColor("#E65100"))
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                .also { it.marginEnd = dp(2) }
            setOnClickListener { enterEditMode() }
        }

        btnSave = Button(requireContext()).apply {
            text = "💾 SAVE"
            textSize = 12f
            setBackgroundColor(android.graphics.Color.parseColor("#1565C0"))
            setTextColor(android.graphics.Color.WHITE)
            isEnabled = false
            alpha = 0.4f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener { confirmSave() }
        }

        bottomBar.addView(tvSyncStatus)
        bottomBar.addView(btnEdit)
        bottomBar.addView(btnSave)
        root.addView(bottomBar)

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.hide()
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            val topPx = windowInsets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
            toolbar.setPadding(0, topPx, 0, 0)
            windowInsets
        }

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
        recyclerView.adapter = adapter

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
                        updateDateRow()
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
                "• Tap ✎ EDIT to correct quantities\n" +
                "• Use ⋮ menu to Import, Delete, Upload or Restore"
            )
            .setPositiveButton("View") { _, _ ->
                selectedDate      = earliest
                isEditMode        = false
                hasUnsavedChanges = false
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
                selectedDate        = chosenDate
                isEditMode          = true    // fresh DB — start in edit mode
                hasUnsavedChanges   = false
                isCurrentDateLocked = false
                updateDateRow()
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
            products = repository.getActiveProductsSortedSync()
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

    /** Updates the bottom-bar sync status chip from the DB. */
    private fun refreshSavedDates() {
        lifecycleScope.launch {
            val earliest = dataViewModel.getEarliestCommittedDate()
            if (earliest == null) {
                setSyncChip(SYNC_NONE)
                return@launch
            }
            val statuses = repository.getOpeningStockSyncStatuses(earliest)
            val state = when {
                statuses.isEmpty()                                -> SYNC_SAVED
                statuses.any { it == SyncStatus.SYNC_ERROR }    -> SYNC_ERROR
                statuses.any { it == SyncStatus.PENDING_UPSERT }-> SYNC_PENDING
                else                                             -> SYNC_SAVED
            }
            setSyncChip(state)
        }
    }

    private fun setSyncChip(state: String) {
        when (state) {
            SYNC_PENDING -> {
                tvSyncStatus.text = "⏳ PENDING SYNC"
                tvSyncStatus.setTextColor(android.graphics.Color.parseColor("#BF360C"))
                tvSyncStatus.setBackgroundColor(android.graphics.Color.parseColor("#FFF3E0"))
            }
            SYNC_ERROR -> {
                tvSyncStatus.text = "⚠ SYNC ERROR"
                tvSyncStatus.setTextColor(android.graphics.Color.parseColor("#B71C1C"))
                tvSyncStatus.setBackgroundColor(android.graphics.Color.parseColor("#FFEBEE"))
            }
            SYNC_NONE -> {
                tvSyncStatus.text = "— NO DATA"
                tvSyncStatus.setTextColor(android.graphics.Color.parseColor("#546E7A"))
                tvSyncStatus.setBackgroundColor(android.graphics.Color.parseColor("#ECEFF1"))
            }
            else -> {  // SYNC_SAVED
                tvSyncStatus.text = "☁ SAVED IN CLOUD"
                tvSyncStatus.setTextColor(android.graphics.Color.parseColor("#1B5E20"))
                tvSyncStatus.setBackgroundColor(android.graphics.Color.parseColor("#E8F5E9"))
            }
        }
    }

    /** Called from the ⋮ overflow menu Delete item. */
    private fun deleteOpeningStock() {
        lifecycleScope.launch {
            val earliest = dataViewModel.getEarliestCommittedDate() ?: run {
                Toast.makeText(requireContext(),
                    "No opening stock data to delete.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val count = repository.getAllDailyStockForDate(earliest).count { it.isCommitted }
            confirmDelete(earliest, count)
        }
    }

    private fun confirmDelete(date: String, productCount: Int) {
        AppDialogs.destructive(
            requireContext(),
            "Delete Opening Stock",
            "Delete all opening stock entries for\n${formatDisplay(date)}?\n\n" +
            "$productCount product(s) will be removed.\n\n" +
            "⚠ Any Daily Stock data that used these as opening balances " +
            "will no longer have prior history."
        ) {
            dataViewModel.clearDateData(date)
            quantities.keys.forEach { quantities[it] = IntArray(4) }
            if (::adapter.isInitialized) adapter.updateProducts(products)
            updateSummary()
            refreshSavedDates()
            Toast.makeText(requireContext(),
                "Opening stock for ${formatDisplay(date)} deleted.",
                Toast.LENGTH_SHORT).show()
        }
    }

    // ── Header info helpers ───────────────────────────────────────────────────

    /**
     * Row 1 (blue): date + lock indicator.
     * In fresh-setup mode (not locked) the row is tappable to change the date.
     */
    private fun updateDateRow() {
        val lockMark = if (isCurrentDateLocked) "  🔒" else "  (tap to change)"
        tvDateInfo.text = "Opening Stock  •  1st Trading Day: ${formatDisplay(selectedDate)}$lockMark"
        tvDateInfo.setOnClickListener(
            if (!isCurrentDateLocked) View.OnClickListener { pickDate() } else null
        )
        // Slightly lighter background in fresh (unlocked) mode to hint it's tappable
        tvDateInfo.setBackgroundColor(
            android.graphics.Color.parseColor(
                if (isCurrentDateLocked) "#1565C0" else "#1976D2"
            )
        )
    }

    /** Rows 2 + 3: stock value and product/unit counts. */
    private fun updateSummary() {
        val productCount = quantities.values.count { it.sum() > 0 }
        val totalUnits   = quantities.values.sumOf { it.sum() }
        val totalValue   = products.sumOf { p ->
            val qty = quantities[p.id] ?: IntArray(4)
            qty[0] * p.qqSalePrice + qty[1] * p.ppSalePrice +
            qty[2] * p.nnSalePrice + qty[3] * p.ddSalePrice
        }

        if (totalUnits == 0) {
            tvValueInfo.text   = "Opening Stock Value:  —"
            tvProductInfo.text = "No quantities entered yet"
        } else {
            tvValueInfo.text   = "Opening Stock Value:  ₹${String.format("%,.2f", totalValue)}"
            tvProductInfo.text = "$productCount Products  •  $totalUnits Units"
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
                updateDateRow()
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
        updateDateRow()
        loadProducts()
    }

    /** Apply UI state based on isEditMode and hasUnsavedChanges. */
    private fun updateDateLockUI() {
        updateDateRow()

        // Edit button — hidden when already in edit mode
        btnEdit.visibility = if (isEditMode) View.GONE else View.VISIBLE

        // Save button — active only when in edit mode AND changes exist
        val saveActive = isEditMode && hasUnsavedChanges
        btnSave.isEnabled = saveActive
        btnSave.alpha     = if (saveActive) 1.0f else 0.4f
        btnSave.text      = if (isCurrentDateLocked) "💾 UPDATE" else "💾 SAVE"

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

    // ── Cloud sync ────────────────────────────────────────────────────────────

    /**
     * Pushes any PENDING_UPSERT opening stock rows to Google Sheets.
     * Runs a full sync (purchases + daily stock + day summary) — opening stock
     * rows are included because they are standard daily_stock rows with PENDING_UPSERT.
     */
    private fun downloadTemplate() {
        if (products.isEmpty()) {
            Toast.makeText(requireContext(),
                "No products found. Download the product list first.", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            try {
                Toast.makeText(requireContext(), "Generating template…", Toast.LENGTH_SHORT).show()
                val uri = withContext(Dispatchers.IO) {
                    val workbook = XSSFWorkbook()
                    val sheet = workbook.createSheet("Closing")

                    val headerStyle = workbook.createCellStyle().apply {
                        val font = workbook.createFont()
                        font.bold = true
                        setFont(font)
                        alignment = HorizontalAlignment.CENTER
                        fillForegroundColor = IndexedColors.LIGHT_BLUE.index
                        fillPattern = FillPatternType.SOLID_FOREGROUND
                    }

                    val header = sheet.createRow(0)
                    listOf("DATE_CLOSING","PRODUCT_TYPE","BRAND_CODE","PRODUCT_NAME",
                           "QQ_CLOSING","PP_CLOSING","NN_CLOSING","DD_CLOSING")
                        .forEachIndexed { i, name ->
                            header.createCell(i).also { it.setCellValue(name); it.cellStyle = headerStyle }
                        }

                    products.forEachIndexed { idx, p ->
                        val row = sheet.createRow(idx + 1)
                        row.createCell(0).setCellValue(selectedDate)
                        row.createCell(1).setCellValue(p.productType)
                        row.createCell(2).setCellValue(p.brandCode)
                        row.createCell(3).setCellValue(p.displayName)
                        row.createCell(4).setCellValue(0.0)
                        row.createCell(5).setCellValue(0.0)
                        row.createCell(6).setCellValue(0.0)
                        row.createCell(7).setCellValue(0.0)
                    }

                    sheet.setColumnWidth(0, 14 * 256)
                    sheet.setColumnWidth(1, 10 * 256)
                    sheet.setColumnWidth(2, 12 * 256)
                    sheet.setColumnWidth(3, 30 * 256)
                    for (i in 4..7) sheet.setColumnWidth(i, 12 * 256)

                    val file = File(requireContext().getExternalFilesDir(null),
                        "OpeningStock_Template.xlsx")
                    FileOutputStream(file).use { workbook.write(it) }
                    workbook.close()
                    FileProvider.getUriForFile(requireContext(),
                        "${requireContext().packageName}.fileprovider", file)
                }

                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Template Ready")
                    .setMessage(
                        "Opening Stock template created with ${products.size} products.\n\n" +
                        "Date pre-filled: ${formatDisplay(selectedDate)}\n\n" +
                        "Instructions:\n" +
                        "1. Open in Google Sheets or Excel\n" +
                        "2. Fill QQ / PP / NN / DD quantities\n" +
                        "3. Do NOT change PRODUCT_TYPE or BRAND_CODE columns\n" +
                        "4. Save as .xlsx and import using ⋮ → Import from Excel"
                    )
                    .setPositiveButton("Share") { _, _ ->
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(shareIntent, "Share Template"))
                    }
                    .setNegativeButton("Done", null)
                    .show()

            } catch (e: Exception) {
                Toast.makeText(requireContext(),
                    "Template generation failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun uploadOpeningStock() {
        val toast = Toast.makeText(requireContext(),
            "Uploading opening stock to cloud…", Toast.LENGTH_LONG)
        toast.show()
        lifecycleScope.launch {
            try {
                val result = SyncCoordinator(requireContext()).performFullSync()
                toast.cancel()
                when (result) {
                    is SyncCoordinator.SyncResult.Success ->
                        Toast.makeText(requireContext(),
                            "✓ Opening stock uploaded to cloud.", Toast.LENGTH_SHORT).show()
                    is SyncCoordinator.SyncResult.Error ->
                        Toast.makeText(requireContext(),
                            "✗ Upload failed: ${result.message}", Toast.LENGTH_LONG).show()
                    else ->
                        Toast.makeText(requireContext(),
                            "✓ Sync complete.", Toast.LENGTH_SHORT).show()
                }
                refreshSavedDates()
            } catch (e: Exception) {
                toast.cancel()
                Toast.makeText(requireContext(),
                    "✗ Upload error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Downloads all daily stock rows from Google Sheets (including opening stock)
     * and upserts them into the local Room database.
     * Used after a package rename or fresh install to recover cloud data.
     */
    private fun restoreFromCloud() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Restore Opening Stock from Cloud")
            .setMessage(
                "This will download your opening stock from Google Sheets and " +
                "restore it to this device.\n\n" +
                "Existing local data for the same date will be overwritten.\n\n" +
                "Continue?"
            )
            .setPositiveButton("Restore") { _, _ ->
                val toast = Toast.makeText(requireContext(),
                    "Downloading from cloud…", Toast.LENGTH_LONG)
                toast.show()
                lifecycleScope.launch {
                    try {
                        val result = SyncCoordinator(requireContext())
                            .downloadDailyStockFromCloud()
                        toast.cancel()
                        when (result) {
                            is SyncCoordinator.SyncResult.DailyStockDownSync -> {
                                if (result.count > 0) {
                                    Toast.makeText(requireContext(),
                                        "✓ Restored ${result.count} row(s) from cloud.",
                                        Toast.LENGTH_LONG).show()
                                    loadProducts()
                                } else {
                                    Toast.makeText(requireContext(),
                                        "No opening stock found in cloud.",
                                        Toast.LENGTH_SHORT).show()
                                }
                            }
                            is SyncCoordinator.SyncResult.Error ->
                                Toast.makeText(requireContext(),
                                    "✗ Restore failed: ${result.message}",
                                    Toast.LENGTH_LONG).show()
                            else ->
                                Toast.makeText(requireContext(),
                                    "Restore complete.", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        toast.cancel()
                        Toast.makeText(requireContext(),
                            "✗ Restore error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
                setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) (v as EditText).post { v.selectAll() }
                }
                // gravity=CENTER + TYPE_CLASS_NUMBER resets cursor to 0 after each
                // keystroke, causing digits to insert in reverse. Always keep cursor
                // at end so typing flows left-to-right normally.
                // IMPORTANT: re-read text.length inside the lambda — RecyclerView may
                // rebind this view (clearing text to length 0) before the post runs,
                // which would make setSelection(capturedLen) throw IndexOutOfBounds.
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                    override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        val len = s?.length ?: 0
                        if (selectionStart != len) post {
                            val current = text?.length ?: 0
                            setSelection(current)
                        }
                    }
                })
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
