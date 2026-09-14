package com.simhadri.winentry.ui.reports

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.simhadri.winentry.R
import com.simhadri.winentry.databinding.FragmentReportsBinding
import com.simhadri.winentry.sync.SyncCoordinator
import com.simhadri.winentry.utils.AppDialogs
import com.simhadri.winentry.utils.AppStrings
import com.simhadri.winentry.utils.LangPrefs
import java.text.SimpleDateFormat
import java.util.*

class ReportsFragment : Fragment() {

    private var _binding: FragmentReportsBinding? = null
    private val binding get() = _binding!!

    private val sdf        = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val displayFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    private var dailySheetDate: String    = sdf.format(Date())
    private var closingDate: String       = sdf.format(Date())
    private var purchaseFromDate: String  = sdf.format(Date())
    private var purchaseToDate: String    = sdf.format(Date())
    private var brandWiseDate: String     = sdf.format(Date())
    private var salesMarginFrom: String   = sdf.format(Date())
    private var salesMarginTo: String     = sdf.format(Date())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReportsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.hide()

        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.scrollView) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        applyLanguage()

        // Pre-fill date buttons with today
        updateDateButton(binding.btnDailySheetDate, dailySheetDate)
        updateDateButton(binding.btnClosingDate, closingDate)
        updateDateButton(binding.btnPurchaseFrom, purchaseFromDate)
        updateDateButton(binding.btnPurchaseTo, purchaseToDate)
        updateDateButton(binding.btnBrandWiseDate, brandWiseDate)
        updateDateButton(binding.btnSalesMarginFrom, salesMarginFrom)
        updateDateButton(binding.btnSalesMarginTo, salesMarginTo)

        // ── Daily Stock Sheet ────────────────────────────────────
        binding.btnDailySheetDate.setOnClickListener {
            showDatePicker(dailySheetDate) { picked ->
                dailySheetDate = picked
                updateDateButton(binding.btnDailySheetDate, picked)
            }
        }
        binding.btnDailySheetView.setOnClickListener {
            navigateToViewer(
                reportType = ReportViewerFragment.TYPE_DAILY_SHEET,
                date       = dailySheetDate,
                title      = "Daily Stock Sheet  ·  ${displayFmt.format(sdf.parse(dailySheetDate)!!)}"
            )
        }

        // ── Closing Balances ─────────────────────────────────────
        binding.btnClosingDate.setOnClickListener {
            showDatePicker(closingDate) { picked ->
                closingDate = picked
                updateDateButton(binding.btnClosingDate, picked)
            }
        }
        binding.btnClosingView.setOnClickListener {
            requireInvitation {
                navigateToViewer(
                    reportType = ReportViewerFragment.TYPE_CLOSING_BALANCES,
                    date       = closingDate,
                    title      = "Closing Balances  ·  ${displayFmt.format(sdf.parse(closingDate)!!)}"
                )
            }
        }

        // ── Purchase Report ──────────────────────────────────────
        binding.btnPurchaseFrom.setOnClickListener {
            showDatePicker(purchaseFromDate) { picked ->
                purchaseFromDate = picked
                updateDateButton(binding.btnPurchaseFrom, picked)
                // Auto-correct: if From > To, snap To forward to match
                if (picked > purchaseToDate) {
                    purchaseToDate = picked
                    updateDateButton(binding.btnPurchaseTo, picked)
                }
            }
        }
        binding.btnPurchaseTo.setOnClickListener {
            showDatePicker(purchaseToDate) { picked ->
                purchaseToDate = picked
                updateDateButton(binding.btnPurchaseTo, picked)
                // Auto-correct: if To < From, snap From back to match
                if (picked < purchaseFromDate) {
                    purchaseFromDate = picked
                    updateDateButton(binding.btnPurchaseFrom, picked)
                }
            }
        }
        binding.btnPurchaseView.setOnClickListener {
            val fromDisp = displayFmt.format(sdf.parse(purchaseFromDate)!!)
            val toDisp   = displayFmt.format(sdf.parse(purchaseToDate)!!)
            val title = if (purchaseFromDate == purchaseToDate)
                "Purchases  ·  $fromDisp"
            else
                "Purchases  ·  $fromDisp → $toDisp"
            navigateToPurchaseViewer(
                fromDate = purchaseFromDate,
                toDate   = purchaseToDate,
                title    = title
            )
        }

        // ── Brand Wise Account Report ──────────────────────────────
        binding.btnBrandWiseDate.setOnClickListener {
            showDatePicker(brandWiseDate) { picked ->
                brandWiseDate = picked
                updateDateButton(binding.btnBrandWiseDate, picked)
            }
        }
        binding.btnBrandWiseView.setOnClickListener {
            requireInvitation {
                val includeZero = binding.checkIncludeZero.isChecked
                navigateToViewer(
                    reportType  = ReportViewerFragment.TYPE_BRAND_WISE_REPORT,
                    date        = brandWiseDate,
                    title       = "Brand Wise A/c - ${displayFmt.format(sdf.parse(brandWiseDate)!!)}",
                    includeZero = includeZero
                )
            }
        }

        // ── Sales & Profit Margin Report ─────────────────────────
        binding.btnSalesMarginFrom.setOnClickListener {
            showDatePicker(salesMarginFrom) { picked ->
                salesMarginFrom = picked
                updateDateButton(binding.btnSalesMarginFrom, picked)
                if (picked > salesMarginTo) {
                    salesMarginTo = picked
                    updateDateButton(binding.btnSalesMarginTo, picked)
                }
            }
        }
        binding.btnSalesMarginTo.setOnClickListener {
            showDatePicker(salesMarginTo) { picked ->
                salesMarginTo = picked
                updateDateButton(binding.btnSalesMarginTo, picked)
                if (picked < salesMarginFrom) {
                    salesMarginFrom = picked
                    updateDateButton(binding.btnSalesMarginFrom, picked)
                }
            }
        }
        binding.btnSalesMarginView.setOnClickListener {
            requireInvitation {
                val fromDisp = displayFmt.format(sdf.parse(salesMarginFrom)!!)
                val toDisp   = displayFmt.format(sdf.parse(salesMarginTo)!!)
                val title = if (salesMarginFrom == salesMarginTo)
                    "Sales & Profit Margin  ·  $fromDisp"
                else
                    "Sales & Profit Margin  ·  $fromDisp → $toDisp"
                navigateToPurchaseViewer(
                    fromDate   = salesMarginFrom,
                    toDate     = salesMarginTo,
                    title      = title,
                    reportType = ReportViewerFragment.TYPE_SALES_MARGIN_REPORT
                )
            }
        }

        // ── Monthly Sale Data ─────────────────────────────────────
        binding.btnMonthlySaleView.setOnClickListener {
            requireInvitation {
                findNavController().navigate(R.id.action_reports_to_monthlySummary)
            }
        }
        binding.cardMonthlySaleData.setOnClickListener {
            requireInvitation {
                findNavController().navigate(R.id.action_reports_to_monthlySummary)
            }
        }

        // ── Quick Sale Check ───────────────────────────────────────
        binding.btnQuickSaleOpen.setOnClickListener {
            findNavController().navigate(R.id.action_reports_to_quickSaleCheck)
        }
        binding.cardQuickSaleCheck.setOnClickListener {
            findNavController().navigate(R.id.action_reports_to_quickSaleCheck)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
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

    // ── Helpers ──────────────────────────────────────────────────

    private fun updateDateButton(btn: android.widget.Button, date: String) {
        try { btn.text = displayFmt.format(sdf.parse(date)!!) } catch (e: Exception) { btn.text = date }
    }

    private fun showDatePicker(current: String, onPicked: (String) -> Unit) {
        val cal = Calendar.getInstance()
        try { cal.time = sdf.parse(current)!! } catch (e: Exception) { }
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                onPicked(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day))
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).apply {
            setButton(DatePickerDialog.BUTTON_NEUTRAL, "Today") { _, which ->
                if (which == DatePickerDialog.BUTTON_NEUTRAL) onPicked(sdf.format(Date()))
            }
            show()
        }
    }

    private fun applyLanguage() {
        val lang = LangPrefs.get(requireContext())
        val te = lang == AppStrings.Lang.TE
        val titleSp = if (te) 18f else 16f
        val descSp  = if (te) 11f else 13f

        binding.toolbar.title                       = AppStrings.reportsToolbarTitle.get(lang)
        binding.textSectionDailyReports.text        = AppStrings.reportsSectionDaily.get(lang)
        binding.textDailySheetTitle.text            = AppStrings.reportsDailySheetTitle.get(lang)
        binding.textDailySheetDesc.text             = AppStrings.reportsDailySheetDesc.get(lang)
        binding.textMonthlySaleTitle.text           = AppStrings.reportsMonthlySaleTitle.get(lang)
        binding.textMonthlySaleDesc.text            = AppStrings.reportsMonthlySaleDesc.get(lang)
        binding.textQuickSaleTitle.text              = AppStrings.reportsQuickSaleTitle.get(lang)
        binding.textQuickSaleDesc.text               = AppStrings.reportsQuickSaleDesc.get(lang)
        binding.textClosingBalancesTitle.text       = AppStrings.reportsClosingBalancesTitle.get(lang)
        binding.textClosingBalancesDesc.text        = AppStrings.reportsClosingBalancesDesc.get(lang)
        binding.textBrandWiseTitle.text             = AppStrings.reportsBrandWiseTitle.get(lang)
        binding.textBrandWiseDesc.text              = AppStrings.reportsBrandWiseDesc.get(lang)
        binding.textSectionPeriodReports.text       = AppStrings.reportsSectionPeriod.get(lang)
        binding.textPurchaseReportTitle.text        = AppStrings.reportsPurchaseReportTitle.get(lang)
        binding.textPurchaseReportDesc.text         = AppStrings.reportsPurchaseReportDesc.get(lang)
        binding.textSalesMarginTitle.text           = AppStrings.reportsSalesMarginTitle.get(lang)
        binding.textSalesMarginDesc.text            = AppStrings.reportsSalesMarginDesc.get(lang)
        val viewLabel = AppStrings.reportsViewButton.get(lang)
        binding.btnDailySheetView.text              = viewLabel
        binding.btnClosingView.text                 = viewLabel
        binding.btnPurchaseView.text                = viewLabel
        binding.btnBrandWiseView.text               = viewLabel
        binding.btnSalesMarginView.text             = viewLabel
        binding.btnMonthlySaleView.text             = AppStrings.reportsBrowseMonthsButton.get(lang)
        binding.checkIncludeZero.text               = AppStrings.reportsIncludeZero.get(lang)
        binding.tvReportsBusinessInfoNote.text      = AppStrings.reportsBusinessInfoNote.get(lang)

        for (v in listOf(binding.textSectionDailyReports, binding.textSectionPeriodReports))
            v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
        for (v in listOf(binding.textDailySheetTitle, binding.textMonthlySaleTitle,
                         binding.textClosingBalancesTitle, binding.textBrandWiseTitle,
                         binding.textPurchaseReportTitle, binding.textSalesMarginTitle,
                         binding.textQuickSaleTitle))
            v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, titleSp)
        for (v in listOf(binding.textDailySheetDesc, binding.textMonthlySaleDesc,
                         binding.textClosingBalancesDesc, binding.textBrandWiseDesc,
                         binding.textPurchaseReportDesc, binding.textSalesMarginDesc,
                         binding.textQuickSaleDesc))
            v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, descSp)
    }

    private fun navigateToViewer(reportType: String, date: String, title: String, includeZero: Boolean = false) {
        val bundle = Bundle().apply {
            putString(ReportViewerFragment.ARG_REPORT_TYPE, reportType)
            putString(ReportViewerFragment.ARG_DATE, date)
            putString(ReportViewerFragment.ARG_TITLE, title)
            putBoolean(ReportViewerFragment.ARG_INCLUDE_ZERO, includeZero)
        }
        findNavController().navigate(R.id.reportViewerFragment, bundle)
    }

    private fun navigateToPurchaseViewer(
        fromDate:   String,
        toDate:     String,
        title:      String,
        reportType: String = ReportViewerFragment.TYPE_PURCHASE_REPORT
    ) {
        val bundle = Bundle().apply {
            putString(ReportViewerFragment.ARG_REPORT_TYPE, reportType)
            putString(ReportViewerFragment.ARG_DATE,    fromDate)
            putString(ReportViewerFragment.ARG_DATE_TO, toDate)
            putString(ReportViewerFragment.ARG_TITLE,   title)
        }
        findNavController().navigate(R.id.reportViewerFragment, bundle)
    }
}
