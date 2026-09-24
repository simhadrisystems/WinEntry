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
import com.simhadri.winentry.data.dao.DailyStockDao
import com.simhadri.winentry.data.repository.BaselineMath
import com.simhadri.winentry.data.repository.DailyStockRepository
import com.simhadri.winentry.utils.AppDialogs
import com.simhadri.winentry.utils.DailyStockImportHelper
import com.simhadri.winentry.ui.dailystock.DailyStockDataViewModel
import com.simhadri.winentry.sync.SyncCoordinator
import com.simhadri.winentry.utils.exportToDownloadsAndShare
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
    private lateinit var bottomBar: LinearLayout
    private lateinit var headerPanel: LinearLayout

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
            importHelper?.let { dataViewModel.prepareClosingImport(uri, it, treatAsOpeningStock = true) }
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
        val MENU_TEMPLATE     = 1000
        val MENU_IMPORT       = 1001
        val MENU_DELETE       = 1002
        val MENU_UPLOAD       = 1003
        val MENU_RESTORE      = 1004
        val MENU_NEW_BASELINE = 1005
        toolbar.menu.add(0, MENU_TEMPLATE, 0, "📋 Download Template")
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        toolbar.menu.add(0, MENU_IMPORT,  1, "📥 Import from Excel")
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        toolbar.menu.add(0, MENU_NEW_BASELINE, 2, "🆕 Start New Opening Balance")
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        toolbar.menu.add(0, MENU_DELETE,  4, "🗑 Delete Opening Stock")
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        toolbar.menu.add(0, MENU_UPLOAD,  5, "☁ Upload to Cloud")
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        toolbar.menu.add(0, MENU_RESTORE, 6, "☁ Restore from Cloud")
            .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
        // Tint the overflow (3-dot) icon white after all items are added
        toolbar.overflowIcon?.setTint(android.graphics.Color.WHITE)

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_TEMPLATE     -> { downloadTemplate(); true }
                MENU_IMPORT  -> {
                    importLauncher.launch(Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }); true
                }
                MENU_NEW_BASELINE -> { startNewOpeningBalance(); true }
                MENU_DELETE  -> { deleteOpeningStock(); true }
                MENU_UPLOAD  -> { uploadOpeningStock(); true }
                MENU_RESTORE -> { restoreFromCloud();   true }
                else         -> false
            }
        }

        root.addView(toolbar)

        // ── Fixed header panel — 3 equal-weight coloured info rows ──────────────
        headerPanel = LinearLayout(requireContext()).apply {
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
        bottomBar = LinearLayout(requireContext()).apply {
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
            val bars = windowInsets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            toolbar.setPadding(bars.left, bars.top, bars.right, 0)
            // headerPanel and recyclerView are full-width siblings of the toolbar/bottomBar,
            // not inside either, so they need their own side padding or their content extends
            // under a side nav bar in landscape.
            headerPanel.setPadding(bars.left, 0, bars.right, 0)
            recyclerView.setPadding(bars.left, 0, bars.right, 0)
            recyclerView.clipToPadding = false
            (fabTop.layoutParams as android.widget.FrameLayout.LayoutParams).marginEnd = dp(8) + bars.right
            (fabBottom.layoutParams as android.widget.FrameLayout.LayoutParams).marginEnd = dp(8) + bars.right
            fabTop.requestLayout()
            fabBottom.requestLayout()
            bottomBar.setPadding(bars.left, 0, bars.right, bars.bottom)
            (bottomBar.layoutParams as LinearLayout.LayoutParams).height = dp(52) + bars.bottom
            bottomBar.requestLayout()
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

            val fileDates = result.closingData.values.map { it.date }.toSortedSet()
            if (fileDates.size > 1) {
                dataViewModel.clearPendingImport()
                AppDialogs.info(requireContext(), "Import from Excel",
                    "The file has ${fileDates.size} dates (" +
                    fileDates.joinToString(", ") { formatDisplay(it) } + ").\n\n" +
                    "Opening stock is for one date only. Import a file with a single date.")
                return@observe
            }
            val fileDate = fileDates.firstOrNull()

            val summaryMsg = buildString {
                appendLine("${result.closingData.size} product(s) in the file.")
                if (fileDate != null && fileDate != selectedDate)
                    appendLine("\nThe file is dated ${formatDisplay(fileDate)}; quantities will be used for ${formatDisplay(selectedDate)}.")
                appendLine()
                appendLine("Quantities are filled into the grid for ${formatDisplay(selectedDate)}. Nothing is saved until you tap Save/Update.")
                if (isCurrentDateLocked)
                    appendLine("\nProducts not in the file keep their current opening stock.")
                else
                    appendLine("\nProducts not in the file are set to 0.")
                if (result.unknownProducts.isNotEmpty())
                    appendLine("\n${result.unknownProducts.size} unknown product(s) skipped.")
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Import as Opening Stock")
                .setMessage(summaryMsg.trim())
                .setPositiveButton("Fill Grid") { _, _ ->
                    dataViewModel.clearPendingImport()
                    fillGridFromImport(result, prods)
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

    /** Import only fills the grid; the normal Save/Update path decides what is written. */
    private fun fillGridFromImport(result: DailyStockImportHelper.ImportResult, allProducts: List<Product>) {
        val byCode = result.closingData.values.mapNotNull { ci ->
            allProducts.find { it.productType == ci.productType && it.brandCode == ci.brandCode }
                ?.let { it.stockCode to ci }
        }.toMap()
        val keepMissing = isCurrentDateLocked
        var filled = 0
        var missing = 0
        products.forEach { p ->
            val ci = byCode[p.stockCode]
            if (ci != null) {
                quantities[p.id] = intArrayOf(ci.qqClosing, ci.ppClosing, ci.nnClosing, ci.ddClosing)
                filled++
            } else {
                missing++
                if (!keepMissing) quantities[p.id] = IntArray(4)
            }
        }
        val notActive = byCode.size - filled
        isEditMode        = true
        hasUnsavedChanges = true
        adapter.updateProducts(products)
        updateSummary()
        updateDateLockUI()
        val msg = buildString {
            append("Filled $filled product(s). ")
            append(if (keepMissing) "$missing not in file, kept unchanged. " else "$missing not in file, set to 0. ")
            if (notActive > 0) append("$notActive inactive product(s) in file ignored. ")
            append("Review, then tap ${if (isCurrentDateLocked) "Update" else "Save"}.")
        }
        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
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
            var candidates = dataViewModel.getBaselineCandidates()
            if (candidates.isEmpty()) {
                // Legacy data with no marker at all: heuristic fallback, persisted
                dataViewModel.getLatestOpeningStockDateOrHeal()
                candidates = dataViewModel.getBaselineCandidates()
            }
            if (!isAdded) return@launch
            if (candidates.isEmpty()) showFreshStartDialog() else showBaselinePicker(candidates)
        }
    }

    /** Case A: lists every baseline date, newest (active) preselected. */
    private fun showBaselinePicker(candidates: List<DailyStockDao.BaselineCandidate>) {
        val items = candidates.mapIndexed { i, c ->
            buildString {
                append(formatDisplay(c.date))
                append("  ·  ${c.committedCount} products  ·  ")
                append(if (i == 0) "Active" else "History")
                if (c.markedCount < c.committedCount) append("\n(marker incomplete, will be repaired)")
            }
        }.toTypedArray()
        AppDialogs.singleChoice(
            requireContext(),
            "Opening Stock baselines",
            items,
            checkedIndex = 0,
            actionLabel  = "Use Selected",
            cancelLabel  = "Close",
            onCancel     = { findNavController().navigateUp() }
        ) { idx ->
            val chosen = candidates[idx]
            if (idx == 0) {
                useBaseline(chosen)
            } else {
                val later = candidates.take(idx).joinToString(", ") { formatDisplay(it.date) }
                AppDialogs.destructive(
                    requireContext(),
                    "Use an older baseline?",
                    "${formatDisplay(chosen.date)} becomes the active baseline.\n\n" +
                    "$later will no longer be a baseline. Its quantities are kept as ordinary " +
                    "Daily Stock days, so a change on the day before may then carry into it.",
                    "Use ${formatDisplay(chosen.date)}"
                ) { useBaseline(chosen) }
            }
        }
    }

    private fun useBaseline(c: DailyStockDao.BaselineCandidate) {
        lifecycleScope.launch {
            val cascade = dataViewModel.setActiveBaseline(c.date)
            if (!isAdded) return@launch
            if (cascade.hasNegatives) {
                Toast.makeText(requireContext(),
                    "⚠ ${cascade.negativeSaleDates.joinToString(", ")} now shows " +
                    "negative sales — please review and correct manually.",
                    Toast.LENGTH_LONG).show()
            }
            selectedDate      = c.date
            isEditMode        = false
            hasUnsavedChanges = false
            loadProducts()
            Toast.makeText(requireContext(),
                "Tap EDIT to correct quantities. Use ⋮ Start New Opening Balance for a new date.",
                Toast.LENGTH_LONG).show()
        }
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

    /**
     * ⋮ → Start New Opening Balance — re-baseline from a new date while keeping
     * all existing history intact.
     *
     * The chosen date must have no committed daily_stock data on or after it,
     * otherwise it can never become the "nearest earlier committed row" for
     * anything entered after it and the re-baseline would silently not take
     * effect. Enforced two ways: the date picker's minDate excludes invalid
     * dates outright, and the same check runs again on confirm as a safety net.
     */
    private fun startNewOpeningBalance() {
        lifecycleScope.launch {
            val latestCommitted = dataViewModel.getLatestCommittedDate()
            if (!isAdded) return@launch
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val ctx = requireContext()

            val minCal = latestCommitted?.let {
                Calendar.getInstance().apply {
                    time = sdf.parse(it) ?: Date()
                    add(Calendar.DAY_OF_MONTH, 1)
                }
            }
            val maxCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 7) }

            var chosenDate = minCal?.let { sdf.format(it.time) } ?: defaultDate()
            // If today falls within the allowed range, default to today instead —
            // re-baselining "from today" is the common case.
            val todayStr = sdf.format(Calendar.getInstance().time)
            if (minCal == null || todayStr >= sdf.format(minCal.time)) chosenDate = todayStr

            val panel = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(16), dp(24), dp(8))
            }
            panel.addView(TextView(ctx).apply {
                text = "This sets a fresh opening balance from the date you choose. " +
                       "All existing Daily Stock history before it stays intact — it " +
                       "just stops being used for future opening balances.\n\n" +
                       "The date must have no committed Daily Stock data on or after it."
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
                .setTitle("🆕 Start New Opening Balance")
                .setView(panel)
                .setPositiveButton("Begin Entry") { _, _ ->
                    lifecycleScope.launch {
                        // Safety-net re-check in case DB state changed since the dialog opened.
                        val stillLatest = dataViewModel.getLatestCommittedDate()
                        if (!isAdded) return@launch
                        if (stillLatest != null && chosenDate <= stillLatest) {
                            MaterialAlertDialogBuilder(ctx)
                                .setTitle("Date not available")
                                .setMessage(
                                    "Committed Daily Stock data already exists on or after " +
                                    "${formatDisplay(chosenDate)}. Pick an earlier date, or " +
                                    "clear that data first from Daily Stock."
                                )
                                .setPositiveButton("OK", null)
                                .show()
                            return@launch
                        }
                        selectedDate        = chosenDate
                        quantities.keys.forEach { quantities[it] = IntArray(4) }
                        isEditMode          = true
                        hasUnsavedChanges   = false
                        isCurrentDateLocked = false
                        updateDateRow()
                        loadProducts()
                    }
                }
                .setNegativeButton("Cancel", null)
                .create()

            btnDate.setOnClickListener {
                val c = Calendar.getInstance().apply { time = sdf.parse(chosenDate) ?: Date() }
                android.app.DatePickerDialog(ctx, { _, y, m, d ->
                    c.set(y, m, d)
                    chosenDate = sdf.format(c.time)
                    btnDate.text = formatDisplay(chosenDate)
                }, c.get(Calendar.YEAR),
                   c.get(Calendar.MONTH),
                   c.get(Calendar.DAY_OF_MONTH)
                ).apply {
                    setTitle("Select new opening balance date")
                    datePicker.maxDate = maxCal.timeInMillis
                    minCal?.let { datePicker.minDate = it.timeInMillis }
                }.show()
            }

            dialog.show()
        }
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

            // Lock if this is the active (most recent) opening stock date and data
            // exists for it. All other dates (from saved-dates Edit) are read-only
            // display only — date picker is hidden so user cannot navigate there directly.
            val activeDate = dataViewModel.getLatestOpeningStockDate()
            isCurrentDateLocked = (activeDate != null) &&
                                   (selectedDate == activeDate) &&
                                   products.any { p ->
                                       repository.getDailyStockRaw(selectedDate, p.stockCode)
                                           ?.isCommitted == true
                                   }

            if (!isAdded) return@launch
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
            val active = dataViewModel.getLatestOpeningStockDate()
            if (!isAdded) return@launch
            if (active == null) {
                setSyncChip(SYNC_NONE)
                return@launch
            }
            val statuses = repository.getOpeningStockSyncStatuses(active)
            if (!isAdded) return@launch
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
            val active = dataViewModel.getLatestOpeningStockDate()
            if (!isAdded) return@launch
            if (active == null) {
                Toast.makeText(requireContext(),
                    "No opening stock data to delete.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val count = repository.getAllDailyStockForDate(active).count { it.isCommitted }
            if (!isAdded) return@launch
            confirmDelete(active, count)
        }
    }

    private fun confirmDelete(date: String, productCount: Int) {
        AppDialogs.withTextInput(
            requireContext(),
            "Delete Opening Stock",
            "Deletes the baseline ${formatDisplay(date)} for $productCount product(s), " +
            "including the Daily Stock closing entries on that date.\n\n" +
            "The previous baseline becomes active again, and the next day's opening " +
            "balance falls back to the day before ${formatDisplay(date)}.\n\n" +
            "Type DELETE to confirm.",
            requiredText = "DELETE",
            actionLabel  = "Delete"
        ) {
            lifecycleScope.launch {
                dataViewModel.clearOpeningStockWithCascadeAwait(date, products)
                if (!isAdded) return@launch
                isEditMode        = false
                hasUnsavedChanges = false
                Toast.makeText(requireContext(),
                    "Opening stock for ${formatDisplay(date)} deleted.",
                    Toast.LENGTH_SHORT).show()
                initialiseScreen()
            }
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
                    "To correct a mistake, tap EDIT, change only the wrong products, then UPDATE. " +
                    "Only those products are changed; closing entries already made in Daily Stock are kept.\n\n" +
                    "To start a fresh opening balance for a different date without " +
                    "losing this data, use ⋮ → Start New Opening Balance."
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
        lifecycleScope.launch {
            val isNew = repository.getAllDailyStockForDate(selectedDate).none { it.isCommitted }
            val newOb = products.associate { it.stockCode to (quantities[it.id] ?: IntArray(4)).copyOf() }
            val changes = dataViewModel.planOpeningStockSave(selectedDate, products, newOb, isNew)
            if (!isAdded) return@launch
            if (isNew && newOb.values.all { q -> q.all { it == 0 } }) {
                Toast.makeText(requireContext(), "No quantities entered.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (changes.isEmpty()) {
                AppDialogs.info(requireContext(), "Nothing to update",
                    "The opening stock for ${formatDisplay(selectedDate)} already matches what is entered.")
                return@launch
            }
            val msg = if (isNew) newBaselineSummary(changes) else correctionSummary(changes)
            AppDialogs.confirm(
                requireContext(),
                if (isNew) "Save Opening Stock" else "Update Opening Stock",
                msg,
                if (isNew) "Save" else "Update"
            ) { saveOpeningStock(changes) }
        }
    }

    private fun newBaselineSummary(changes: List<DailyStockRepository.BaselineChange>): String {
        val withQty = changes.count { BaselineMath.open(it.after).sum() > 0 }
        val units = changes.sumOf { BaselineMath.open(it.after).sum() }
        val value = products.sumOf { p ->
            val q = quantities[p.id] ?: IntArray(4)
            q[0] * p.qqSalePrice + q[1] * p.ppSalePrice + q[2] * p.nnSalePrice + q[3] * p.ddSalePrice
        }
        return "Baseline date: ${formatDisplay(selectedDate)}\n\n" +
            "$withQty product(s) · $units units\n" +
            "Total value: ₹${String.format("%,.2f", value)}\n\n" +
            "All ${changes.size} active products get an opening balance for this date " +
            "(blank = 0). Daily Stock history before this date is kept and stops here."
    }

    private fun correctionSummary(changes: List<DailyStockRepository.BaselineChange>): String {
        val names = products.associate { it.stockCode to it.displayName }
        val sizes = arrayOf("QQ", "PP", "NN", "DD")
        val zeroFills = changes.count { c -> c.before == null && BaselineMath.open(c.after).all { it == 0 } }
        val lines = changes.filterNot { c -> c.before == null && BaselineMath.open(c.after).all { it == 0 } }.map { c ->
            val after = c.after
            val obA = c.before?.let { BaselineMath.open(it) } ?: IntArray(4)
            val cbA = c.before?.let { BaselineMath.close(it) } ?: IntArray(4)
            val sA  = c.before?.let { BaselineMath.sale(it) } ?: IntArray(4)
            val obB = BaselineMath.open(after); val cbB = BaselineMath.close(after); val sB = BaselineMath.sale(after)
            val parts = (0..3).filter { obA[it] != obB[it] }.map { i ->
                val rest = if (c.cbKept) "CB kept · Sale ${sA[i]}→${sB[i]}" else "CB ${cbA[i]}→${cbB[i]}"
                "  ${sizes[i]}: OB ${obA[i]}→${obB[i]} · $rest"
            }
            (names[after.productCode] ?: after.productCode) + "\n" + parts.joinToString("\n")
        }
        val negatives = changes.filter { c -> BaselineMath.sale(c.after).any { it < 0 } }
            .map { names[it.after.productCode] ?: it.after.productCode }
        val cascades = changes.count { !it.cbKept }
        val unchanged = products.size - changes.size
        return buildString {
            appendLine("${formatDisplay(selectedDate)}: ${changes.size} product(s) change, $unchanged unchanged and not touched.")
            appendLine()
            lines.take(15).forEach { appendLine(it) }
            if (lines.size > 15) appendLine("…and ${lines.size - 15} more")
            if (zeroFills > 0) {
                appendLine()
                appendLine("$zeroFills product(s) with no entry on this date start from a zero opening balance.")
            }
            if (cascades > 0) {
                appendLine()
                appendLine("Products without a closing entry move their CB with the OB; the next day's opening follows.")
            }
            if (negatives.isNotEmpty()) {
                appendLine()
                appendLine("Warning: negative sale for ${negatives.joinToString(", ")}. Check the closing in Daily Stock.")
            }
        }.trim()
    }

    private fun saveOpeningStock(changes: List<DailyStockRepository.BaselineChange>) {
        btnSave.isEnabled = false
        btnSave.text = "Saving…"
        lifecycleScope.launch {
            try {
                val cascadeResult = dataViewModel.applyOpeningStockSave(selectedDate, changes, products)
                if (!isAdded) return@launch
                isEditMode        = false
                hasUnsavedChanges = false
                Toast.makeText(requireContext(),
                    "✓ Opening stock saved — ${changes.size} product(s) updated.",
                    Toast.LENGTH_LONG).show()
                if (cascadeResult.updatedCount > 0) {
                    Toast.makeText(requireContext(),
                        "Also updated the next day's opening balance for ${cascadeResult.updatedCount} product(s).",
                        Toast.LENGTH_LONG).show()
                }
                if (cascadeResult.hasNegatives) {
                    Toast.makeText(requireContext(),
                        "⚠ ${cascadeResult.negativeSaleDates.joinToString(", ")} now shows " +
                        "negative sales — please review and correct manually.",
                        Toast.LENGTH_LONG).show()
                }
                loadProducts()
            } catch (e: Exception) {
                if (isAdded) {
                    updateDateLockUI()
                    Toast.makeText(requireContext(),
                        "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
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
        // Captured up front — the IO block below runs off the main thread and must
        // not call requireContext() itself, since the fragment can detach mid-write.
        val appCtx = requireContext().applicationContext
        lifecycleScope.launch {
            try {
                Toast.makeText(appCtx, "Generating template…", Toast.LENGTH_SHORT).show()
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

                    val file = File(appCtx.getExternalFilesDir(null),
                        "OpeningStock_Template.xlsx")
                    FileOutputStream(file).use { workbook.write(it) }
                    workbook.close()
                    FileProvider.getUriForFile(appCtx,
                        "${appCtx.packageName}.fileprovider", file)
                }

                // exportToDownloadsAndShare() itself calls requireContext()/startActivity(),
                // so it still needs the fragment attached even though we avoided that above.
                if (isAdded) {
                    exportToDownloadsAndShare(uri, "OpeningStock_Template.xlsx", "Share Opening Stock Template")
                }

            } catch (e: Exception) {
                Toast.makeText(appCtx,
                    "Template generation failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun uploadOpeningStock() {
        val toast = Toast.makeText(requireContext(),
            "Uploading opening stock to cloud…", Toast.LENGTH_LONG)
        toast.show()
        lifecycleScope.launch {
            val result = try {
                SyncCoordinator(requireContext()).performFullSync()
            } catch (e: Exception) {
                toast.cancel()
                if (isAdded) {
                    Toast.makeText(requireContext(),
                        "✗ Upload error: ${e.message}", Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            toast.cancel()
            if (!isAdded) return@launch
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
                "This will download the stock for $selectedDate from Google Sheets and " +
                "restore it to this device.\n\n" +
                "Rows on this device with changes not yet synced are kept.\n\n" +
                "Continue?"
            )
            .setPositiveButton("Restore") { _, _ ->
                val toast = Toast.makeText(requireContext(),
                    "Downloading from cloud…", Toast.LENGTH_LONG)
                toast.show()
                lifecycleScope.launch {
                    val result = try {
                        SyncCoordinator(requireContext()).downloadDailyStockFromCloud(selectedDate, selectedDate)
                    } catch (e: Exception) {
                        toast.cancel()
                        if (isAdded) {
                            Toast.makeText(requireContext(),
                                "✗ Restore error: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }
                    toast.cancel()
                    if (!isAdded) return@launch
                    when (result) {
                        is SyncCoordinator.SyncResult.DailyStockDownSync -> {
                            if (result.count > 0 || result.kept > 0) {
                                Toast.makeText(requireContext(),
                                    "✓ Restored ${result.count} row(s) from cloud${if (result.kept > 0) ", ${result.kept} kept (unsynced changes on this device)" else ""}.",
                                    Toast.LENGTH_LONG).show()
                                initialiseScreen()
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
