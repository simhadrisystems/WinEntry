package com.simhadri.winentry.ui.reports

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.simhadri.winentry.R
import com.simhadri.winentry.data.AppDatabase
import com.simhadri.winentry.sync.SyncCoordinator
import com.simhadri.winentry.ui.purchases.DeleteDateRangeDialog
import kotlinx.coroutines.launch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.FileWriter
import java.text.NumberFormat
import java.util.*

/**
 * Monthly Summary screen — UI built entirely in code, no XML layout.
 * This avoids the resource-linker crash (SAXParseException extractDeepLinks)
 * that occurred when a layout XML file was added to the project.
 */
class MonthlySummaryFragment : Fragment() {

    private val viewModel: MonthlySummaryViewModel by viewModels()

    private val rupee: NumberFormat by lazy {
        NumberFormat.getCurrencyInstance(Locale("en", "IN"))
    }

    // View references set during onCreateView
    private lateinit var toolbar: Toolbar
    private lateinit var monthLabel: TextView
    private lateinit var tvBizName:  TextView
    private lateinit var tvBizLoc:   TextView
    private lateinit var tvRepMonth: TextView
    private lateinit var progress:   ProgressBar
    private lateinit var tableRows:  LinearLayout
    private lateinit var totalsRow:  LinearLayout
    private lateinit var emptyText:  TextView
    private lateinit var tvTotDeposits: TextView
    private lateinit var tvTotSales: TextView
    private lateinit var tvTotUpi:   TextView
    private lateinit var tvTotExp:   TextView
    private lateinit var tvTotCash:  TextView

    // ── Build view programmatically ───────────────────────────────────────────

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val dp  = ctx.resources.displayMetrics.density

        // Root: vertical LinearLayout
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.app_surface))
        }

        // ── Toolbar ───────────────────────────────────────────────────────────
        toolbar = Toolbar(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (56 * dp).toInt()
            )
            setBackgroundColor(ContextCompat.getColor(ctx, R.color.app_toolbar))
            setTitleTextColor(Color.WHITE)
            title = "Monthly Summary"
            setNavigationIcon(R.drawable.ic_chevron_left)
            setNavigationOnClickListener { findNavController().navigateUp() }
            inflateMenu(R.menu.menu_monthly_summary)
            overflowIcon = ContextCompat.getDrawable(ctx, R.drawable.ic_more_vert)
            setOnMenuItemClickListener { item -> onMenuItemSelected(item) }
        }
        root.addView(toolbar)

        // ── Report header (business name, location, title + inline month nav) ─
        val reportHeader = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(Color.WHITE)
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
        }
        tvBizName = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1E293B"))
            visibility = View.GONE
        }
        tvBizLoc = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(Color.parseColor("#475569"))
            visibility = View.GONE
        }
        val tvRepTitle = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (6 * dp).toInt() }
            gravity = Gravity.CENTER
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            text = "Monthly Sale Data"
            setTextColor(Color.parseColor("#1E293B"))
        }
        // Inline month navigation row: [◀] [March 2026 (tap to pick)] [▶]
        val monthNavRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (4 * dp).toInt() }
            gravity = Gravity.CENTER_VERTICAL
        }
        val btnPrev = ImageButton(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt())
            setImageResource(android.R.drawable.ic_media_previous)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.parseColor("#2563EB"))
            setOnClickListener { viewModel.previousMonth() }
            contentDescription = "Previous month"
        }
        monthLabel = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#2563EB"))
            setOnClickListener { showMonthYearPicker() }
        }
        tvRepMonth = monthLabel  // same view — observer updates monthLabel
        val btnNext = ImageButton(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (36 * dp).toInt())
            setImageResource(android.R.drawable.ic_media_next)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.parseColor("#2563EB"))
            setOnClickListener { viewModel.nextMonth() }
            contentDescription = "Next month"
        }
        monthNavRow.addView(btnPrev)
        monthNavRow.addView(monthLabel)
        monthNavRow.addView(btnNext)

        reportHeader.addView(tvBizName)
        reportHeader.addView(tvBizLoc)
        reportHeader.addView(tvRepTitle)
        reportHeader.addView(monthNavRow)
        root.addView(reportHeader)

        // ── Progress ──────────────────────────────────────────────────────────
        progress = ProgressBar(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = Gravity.CENTER_HORIZONTAL; it.topMargin = (32 * dp).toInt() }
            visibility = View.GONE
        }
        root.addView(progress)

        // ── Scrollable table ──────────────────────────────────────────────────
        val nestedScroll = androidx.core.widget.NestedScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            isFillViewport = true
        }

        val tableOuter = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // HorizontalScrollView — table scrolls horizontally so no column wraps
        val hScroll = HorizontalScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isFillViewport = false
        }

        val tableInner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Header row
        tableInner.addView(buildHeaderRow(dp))
        tableInner.addView(dividerH(dp, Color.parseColor("#334155")))

        // Data rows container
        tableRows = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        tableInner.addView(tableRows)
        tableInner.addView(dividerH(dp, Color.parseColor("#334155")))

        // Totals row
        totalsRow = buildTotalsRowShell(dp).also { tableInner.addView(it) }

        hScroll.addView(tableInner)
        tableOuter.addView(hScroll)

        // Empty state
        emptyText = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = (32 * dp).toInt() }
            text = "No data for this month"
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.parseColor("#9E9E9E"))
            setPadding((32 * dp).toInt(), (16 * dp).toInt(), (32 * dp).toInt(), (16 * dp).toInt())
            visibility = View.GONE
        }
        tableOuter.addView(emptyText)

        nestedScroll.addView(tableOuter)
        root.addView(nestedScroll)

        return root
    }

    // ── Observe ───────────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        // Reload data whenever the user navigates back from Daily Stock or Reconciliation
        viewModel.refresh()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Load business info from SharedPreferences
        val prefs   = requireContext().getSharedPreferences("business_info", android.content.Context.MODE_PRIVATE)
        val bizName = prefs.getString("business_name", "").orEmpty()
        val bizLoc  = prefs.getString("location", "").orEmpty()
        if (bizName.isNotEmpty()) { tvBizName.text = bizName; tvBizName.visibility = View.VISIBLE }
        if (bizLoc.isNotEmpty())  { tvBizLoc.text  = bizLoc;  tvBizLoc.visibility  = View.VISIBLE }

        viewModel.monthLabel.observe(viewLifecycleOwner) {
            monthLabel.text = it   // tvRepMonth is the same view — one update covers both
            // toolbar title stays "Monthly Sale Data"
        }

        viewModel.isLoading.observe(viewLifecycleOwner) {
            progress.visibility = if (it) View.VISIBLE else View.GONE
        }

        viewModel.summary.observe(viewLifecycleOwner) { renderTable(it) }
    }

    // ── Table rendering ───────────────────────────────────────────────────────

    private fun renderTable(s: MonthSummary) {
        tableRows.removeAllViews()
        val dp = resources.displayMetrics.density

        if (s.rows.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            totalsRow.visibility = View.GONE
            return
        }
        emptyText.visibility = View.GONE
        totalsRow.visibility = View.VISIBLE

        // Remove old banners before re-adding
        listOf("no_rec_banner", "stale_rec_banner").forEach { tag ->
            tableRows.findViewWithTag<android.widget.TextView>(tag)
                ?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        }
        // Banner: days with no reconciliation saved at all
        if (s.daysWithoutReconciliation > 0) {
            val banner = android.widget.TextView(requireContext()).apply {
                tag = "no_rec_banner"
                text = "⚠ ${s.daysWithoutReconciliation} past day(s) this month have no saved" +
                       " day-end reconciliation — Sales shows 0. Open each day in Daily Stock" +
                       " and save reconciliation."
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#E65100"))
                setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            tableRows.addView(banner)
        }
        // Banner: days where CB was changed after reconciliation was saved
        if (s.daysStaleReconciliation > 0) {
            val banner = android.widget.TextView(requireContext()).apply {
                tag = "stale_rec_banner"
                text = "🔄 ${s.daysStaleReconciliation} day(s) have closing balances" +
                       " updated after reconciliation was saved — open each 🔄 day" +
                       " in Daily Stock and re-save day-end reconciliation."
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#1E293B"))
                setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            tableRows.addView(banner)
        }

        s.rows.forEachIndexed { i, row ->
            // Rows without reconciliation get a pale amber background to stand out
            val bg = when {
                row.noReconciliation    -> Color.parseColor("#FFF8E1")  // amber — no rec
                row.staleReconciliation -> Color.parseColor("#E3F2FD")  // light blue — stale
                i % 2 == 0             -> Color.WHITE
                else                   -> Color.parseColor("#F8F9FA")
            }
            val rowView = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(bg)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                )
                minimumHeight = (36 * dp).toInt()
            }
            // Date cell — flag for no reconciliation (*) or stale reconciliation (🔄)
            val dateText = when {
                row.noReconciliation    -> "${row.displayDate}*"
                row.staleReconciliation -> "${row.displayDate}🔄"
                else                    -> row.displayDate
            }
            val dateColor = when {
                row.noReconciliation    -> Color.parseColor("#E65100")  // orange — missing
                row.staleReconciliation -> Color.parseColor("#2563EB")  // blue — stale
                else                    -> Color.parseColor("#212121")
            }
            val cashCol = if (row.cashForDeposit < 0) Color.parseColor("#C62828")
                          else Color.parseColor("#1B5E20")
            rowView.addView(cell(dateText,                 72,  dp, dateColor, true, Gravity.START or Gravity.CENTER_VERTICAL))
            rowView.addView(dividerV(dp))
            rowView.addView(cell(fmt(row.sales),           80,  dp, Color.parseColor("#2E7D32")))
            rowView.addView(dividerV(dp))
            rowView.addView(cell(fmt(row.upiReceipts),     80,  dp, Color.parseColor("#4527A0")))
            rowView.addView(dividerV(dp))
            rowView.addView(cell(fmt(row.dayExpenses),     80,  dp, Color.parseColor("#BF360C")))
            rowView.addView(dividerV(dp))
            rowView.addView(cell(fmt(row.cashForDeposit),  80,  dp, cashCol, bold = true))
            rowView.addView(dividerV(dp))
            rowView.addView(cell(fmt(row.deposits),        80,  dp, Color.parseColor("#00695C")))
            rowView.addView(dividerV(dp))
            rowView.addView(cell(row.notes.take(40),       100, dp, Color.parseColor("#616161"), false, Gravity.START or Gravity.CENTER_VERTICAL))
            tableRows.addView(rowView)
            tableRows.addView(dividerH(dp, Color.parseColor("#E0E0E0")))
        }

        // Fill totals
        tvTotDeposits.text = fmt(s.totalDeposits)
        tvTotSales.text    = fmt(s.totalSales)
        tvTotUpi.text      = fmt(s.totalUpi)
        tvTotExp.text      = fmt(s.totalExpenses)
        tvTotCash.text     = fmt(s.totalCash)
        tvTotCash.setTextColor(
            if (s.totalCash < 0) Color.parseColor("#C62828") else Color.parseColor("#1B5E20")
        )
    }

    // ── Row/cell builders ─────────────────────────────────────────────────────

    private fun buildHeaderRow(dp: Float): LinearLayout {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#334155"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, (44 * dp).toInt()
            )
            gravity = Gravity.CENTER_VERTICAL
        }
        fun h(text: String, minDp: Int, grav: Int = Gravity.CENTER) =
            TextView(requireContext()).apply {
                this.text = text; textSize = 13f; setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT
                )
                minWidth = (minDp * dp).toInt()
                gravity = grav or Gravity.CENTER_VERTICAL
                isSingleLine = true
                setPadding((6 * dp).toInt(), 0, (6 * dp).toInt(), 0)
            }
        row.addView(h("Date",      72,  Gravity.START)); row.addView(dividerV(dp))
        row.addView(h("Sales",     80));                 row.addView(dividerV(dp))
        row.addView(h("UPI",       80));                 row.addView(dividerV(dp))
        row.addView(h("Expense",   80));                 row.addView(dividerV(dp))
        row.addView(h("Cash Dep.", 80));                 row.addView(dividerV(dp))
        row.addView(h("Bank Dep.", 80));                 row.addView(dividerV(dp))
        row.addView(h("Notes",     100, Gravity.START))
        return row
    }

    private fun buildTotalsRowShell(dp: Float): LinearLayout {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#E8F5E9"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, (44 * dp).toInt()
            )
            gravity = Gravity.CENTER_VERTICAL
        }
        fun t(minDp: Int, bold: Boolean = true) = TextView(requireContext()).apply {
            textSize = 13f; setTypeface(null, if (bold) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(Color.parseColor("#1B5E20"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT
            )
            minWidth = (minDp * dp).toInt()
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            isSingleLine = true
            setPadding((6 * dp).toInt(), 0, (6 * dp).toInt(), 0)
        }
        row.addView(TextView(requireContext()).apply {
            text = "TOTAL"; textSize = 13f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1B5E20"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT
            )
            minWidth = (72 * dp).toInt()
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            isSingleLine = true
            setPadding((6 * dp).toInt(), 0, (6 * dp).toInt(), 0)
        })
        row.addView(dividerV(dp))
        tvTotSales    = t(80).also { row.addView(it) }; row.addView(dividerV(dp))
        tvTotUpi      = t(80).also { row.addView(it) }; row.addView(dividerV(dp))
        tvTotExp      = t(80).also { row.addView(it) }; row.addView(dividerV(dp))
        tvTotCash     = t(80).also { row.addView(it) }; row.addView(dividerV(dp))
        tvTotDeposits = t(80).also { row.addView(it) }; row.addView(dividerV(dp))
        row.addView(t(100, false))
        return row
    }

    /**
     * Table cell — WRAP_CONTENT width with a minimum so columns stay consistent.
     * singleLine = true prevents any wrapping. Parent is inside HorizontalScrollView
     * so the table scrolls horizontally when content exceeds screen width.
     * [minDp] = minimum column width in dp.
     */
    private fun cell(
        text: String, minDp: Int, dp: Float,
        color: Int = Color.parseColor("#212121"),
        bold: Boolean = true,
        grav: Int = Gravity.END or Gravity.CENTER_VERTICAL
    ) = TextView(requireContext()).apply {
        this.text = text; textSize = 13f
        setTextColor(color)
        setTypeface(null, if (bold) Typeface.BOLD else Typeface.NORMAL)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.marginEnd = 0 }
        minWidth = (minDp * dp).toInt()
        isSingleLine = true
        this.gravity = grav
        setPadding((6 * dp).toInt(), (5 * dp).toInt(), (6 * dp).toInt(), (5 * dp).toInt())
    }

    private fun dividerV(dp: Float) = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(1, LinearLayout.LayoutParams.MATCH_PARENT)
            .also { it.topMargin = (4 * dp).toInt(); it.bottomMargin = (4 * dp).toInt() }
        setBackgroundColor(Color.parseColor("#E0E0E0"))
    }

    private fun dividerH(@Suppress("UNUSED_PARAMETER") dp: Float, color: Int) = View(requireContext()).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        )
        setBackgroundColor(color)
    }

    // ── Month / year picker ───────────────────────────────────────────────────

    private fun showMonthYearPicker() {
        val cal     = viewModel.currentCalendar()
        val years   = (2020..Calendar.getInstance().get(Calendar.YEAR) + 1).toList()
        val months  = arrayOf("January","February","March","April","May","June",
                              "July","August","September","October","November","December")
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Select Year")
            .setItems(years.map { it.toString() }.toTypedArray()) { _, yi ->
                val selYear = years[yi]
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Select Month")
                    .setItems(months) { _, mi -> viewModel.goToMonth(selYear, mi) }
                    .show()
            }
            .show()
    }

    // ── Menu ──────────────────────────────────────────────────────────────────

    private fun onMenuItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_share -> { shareAsText(); true }
            R.id.action_print -> { printOrPdf();  true }
            R.id.action_import_reconciliation -> {
                importReconciliationFromCloud(); true
            }
            R.id.action_delete_reconciliation_range -> {
                showDeleteReconciliationDialog(); true
            }
            else -> false
        }
    }

    private fun importReconciliationFromCloud() {
        val coordinator = SyncCoordinator(requireContext())
        if (!coordinator.isUserSheetReady()) {
            android.widget.Toast.makeText(
                requireContext(), "Not signed in. Please sign in first.",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        android.widget.Toast.makeText(
            requireContext(), "Importing reconciliation…",
            android.widget.Toast.LENGTH_SHORT
        ).show()
        lifecycleScope.launch {
            when (val result = coordinator.downloadReconciliationFromCloud()) {
                is SyncCoordinator.SyncResult.ReconciliationDownSync -> {
                    android.widget.Toast.makeText(
                        requireContext(),
                        "Imported ${result.count} reconciliation rows",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    viewModel.refresh()   // reload table with newly imported data
                }
                is SyncCoordinator.SyncResult.Error ->
                    android.widget.Toast.makeText(
                        requireContext(),
                        "Import failed: ${result.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                else -> { }
            }
        }
    }

    private fun showDeleteReconciliationDialog() {
        val dialog = DeleteDateRangeDialog.newInstance()
        dialog.setOnDeleteListener { startDate, endDate ->
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Confirm Delete")
                .setMessage("Delete all reconciliation records from $startDate to $endDate?\n\nThis cannot be undone.")
                .setPositiveButton("Delete") { _, _ ->
                    lifecycleScope.launch {
                        val db = AppDatabase.getInstance(requireContext())
                        db.dayReconciliationDao().deleteByDateRange(startDate, endDate)
                        android.widget.Toast.makeText(
                            requireContext(),
                            "Reconciliation deleted for $startDate → $endDate",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        viewModel.refresh()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        dialog.show(childFragmentManager, "DeleteReconciliationRange")
    }

    // ── Share ─────────────────────────────────────────────────────────────────

    /**
     * Share as an HTML file so the recipient sees the full colour report
     * with business header, coloured columns and totals row —
     * not a plain-text table. The HTML is written to the app's cache dir
     * and shared via FileProvider as text/html so browsers, Gmail etc. render it.
     */
    private fun shareAsText() {
        val s     = viewModel.summary.value ?: return
        val label = viewModel.monthLabel.value ?: ""
        val html  = buildHtml(s, label)

        try {
            // Write HTML to cache file
            val fileName = "monthly_summary_${label.replace(" ", "_")}.html"
            val file = java.io.File(requireContext().cacheDir, fileName)
            file.writeText(html, Charsets.UTF_8)

            // Share via FileProvider
            val uri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type    = "text/html"
                    putExtra(Intent.EXTRA_SUBJECT, "Monthly Sale Data — $label")
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share report via"
            ))
        } catch (e: Exception) {
            // Fallback to plain text if FileProvider not configured
            android.widget.Toast.makeText(
                requireContext(),
                "Share failed: ${e.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    // ── Print / PDF ───────────────────────────────────────────────────────────

    private fun printOrPdf() {
        val s     = viewModel.summary.value ?: return
        val label = viewModel.monthLabel.value ?: ""
        val html  = buildHtml(s, label)
        val wv    = WebView(requireContext())
        wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                val pm = requireContext().getSystemService(android.content.Context.PRINT_SERVICE)
                        as PrintManager
                pm.print("Monthly Summary $label",
                    view.createPrintDocumentAdapter("Monthly Summary $label"),
                    PrintAttributes.Builder().build())
            }
        }
    }

    private fun buildHtml(s: MonthSummary, label: String): String {
        val prefs3   = requireContext().getSharedPreferences("business_info", android.content.Context.MODE_PRIVATE)
        val bizName3 = prefs3.getString("business_name", "").orEmpty()
        val bizLoc3  = prefs3.getString("location", "").orEmpty()

        // Report header block — centred, above the table
        val headerHtml = buildString {
            append("<div style='text-align:center;margin-bottom:12px'>")
            if (bizName3.isNotEmpty())
                append("<div style='font-size:15px;font-weight:bold;color:#0D47A1'>$bizName3</div>")
            if (bizLoc3.isNotEmpty())
                append("<div style='font-size:12px;color:#555'>$bizLoc3</div>")
            append("<div style='font-size:14px;font-weight:bold;margin-top:6px'>Monthly Sale Data</div>")
            append("<div style='font-size:12px;color:#555'>$label</div>")
            append("</div>")
        }

        val rows = s.rows.mapIndexed { i, r ->
            val bg = if (i % 2 == 0) "#FFFFFF" else "#F5F5F5"
            val cs = if (r.cashForDeposit < 0) "color:#C62828" else "color:#1B5E20"
            "<tr style=\"background:$bg\"><td>${r.displayDate}</td>" +
            "<td class=n>${fmt(r.sales)}</td>" +
            "<td class=n>${fmt(r.upiReceipts)}</td><td class=n>${fmt(r.dayExpenses)}</td>" +
            "<td class=n style=\"$cs\">${fmt(r.cashForDeposit)}</td>" +
            "<td class=n>${fmt(r.deposits)}</td>" +
            "<td>${r.notes}</td></tr>"
        }.joinToString("")

        return "<!DOCTYPE html><html><head><meta charset=UTF-8>" +
            "<style>" +
            "@page{margin:1.5cm}" +
            "body{font-family:sans-serif;font-size:11px;margin:0}" +
            "table{border-collapse:collapse;width:100%}" +
            "th{background:#1565C0;color:white;padding:6px 8px;text-align:right}" +
            "th:first-child,th:last-child{text-align:left}" +
            "td{padding:5px 8px;border-bottom:1px solid #E0E0E0}" +
            "td.n{text-align:right;white-space:nowrap}" +
            ".tot{background:#E8F5E9!important;font-weight:bold}" +
            "</style></head><body>" +
            headerHtml +
            "<table><thead><tr>" +
            "<th>Date</th><th>Sales</th><th>UPI</th>" +
            "<th>Expense</th><th>Cash Dep.</th><th>Bank Dep.</th><th>Notes</th>" +
            "</tr></thead><tbody>$rows" +
            "<tr class=tot><td>TOTAL</td>" +
            "<td class=n>${fmt(s.totalSales)}</td><td class=n>${fmt(s.totalUpi)}</td>" +
            "<td class=n>${fmt(s.totalExpenses)}</td><td class=n>${fmt(s.totalCash)}</td>" +
            "<td class=n>${fmt(s.totalDeposits)}</td><td></td>" +
            "</tr></tbody></table></body></html>"
    }

    // ── Format helper ─────────────────────────────────────────────────────────

    private fun fmt(v: Double): String {
        if (v == 0.0) return "—"
        val abs    = Math.abs(v).toLong()
        val prefix = if (v < 0) "-₹" else "₹"
        // Indian grouping: last 3 digits, then groups of 2
        val s = abs.toString()
        val grouped = if (s.length <= 3) s else {
            val last3 = s.takeLast(3)
            val rest  = s.dropLast(3)
            val pairs = rest.reversed().chunked(2).joinToString(",").reversed()
            "$pairs,$last3"
        }
        return "$prefix$grouped"
    }
}
