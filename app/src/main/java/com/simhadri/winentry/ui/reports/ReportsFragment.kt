package com.simhadri.winentry.ui.reports

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.simhadri.winentry.R
import com.simhadri.winentry.databinding.FragmentReportsBinding
import com.simhadri.winentry.utils.AppStrings
import com.simhadri.winentry.utils.LangPrefs
import java.text.SimpleDateFormat
import java.util.*

class ReportsFragment : Fragment() {

    private var _binding: FragmentReportsBinding? = null
    private val binding get() = _binding!!

    private val sdf        = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val displayFmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

    private var dailySheetDate: String   = sdf.format(Date())
    private var closingDate: String      = sdf.format(Date())
    private var purchaseFromDate: String = sdf.format(Date())
    private var purchaseToDate: String   = sdf.format(Date())
    private var brandWiseDate: String    = sdf.format(Date())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReportsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.hide()

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        applyLanguage()

        // Pre-fill date buttons with today
        updateDateButton(binding.btnDailySheetDate, dailySheetDate)
        updateDateButton(binding.btnClosingDate, closingDate)
        updateDateButton(binding.btnPurchaseFrom, purchaseFromDate)
        updateDateButton(binding.btnPurchaseTo, purchaseToDate)
        updateDateButton(binding.btnBrandWiseDate, brandWiseDate)

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
            navigateToViewer(
                reportType = ReportViewerFragment.TYPE_CLOSING_BALANCES,
                date       = closingDate,
                title      = "Closing Balances  ·  ${displayFmt.format(sdf.parse(closingDate)!!)}"
            )
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
            val includeZero = binding.checkIncludeZero.isChecked
            navigateToViewer(
                reportType  = ReportViewerFragment.TYPE_BRAND_WISE_REPORT,
                date        = brandWiseDate,
                title       = "Brand Wise A/c - ${displayFmt.format(sdf.parse(brandWiseDate)!!)}",
                includeZero = includeZero
            )
        }

        // ── Monthly Sale Data ─────────────────────────────────────
        binding.cardMonthlySaleData.setOnClickListener {
            findNavController().navigate(R.id.action_reports_to_monthlySummary)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
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
        binding.toolbar.title                       = AppStrings.reportsToolbarTitle.get(lang)
        binding.textSectionDailyReports.text        = AppStrings.reportsSectionDaily.get(lang)
        binding.textDailySheetTitle.text            = AppStrings.reportsDailySheetTitle.get(lang)
        binding.textDailySheetDesc.text             = AppStrings.reportsDailySheetDesc.get(lang)
        binding.textMonthlySaleTitle.text           = AppStrings.reportsMonthlySaleTitle.get(lang)
        binding.textMonthlySaleDesc.text            = AppStrings.reportsMonthlySaleDesc.get(lang)
        binding.textClosingBalancesTitle.text       = AppStrings.reportsClosingBalancesTitle.get(lang)
        binding.textClosingBalancesDesc.text        = AppStrings.reportsClosingBalancesDesc.get(lang)
        binding.textBrandWiseTitle.text             = AppStrings.reportsBrandWiseTitle.get(lang)
        binding.textBrandWiseDesc.text              = AppStrings.reportsBrandWiseDesc.get(lang)
        binding.textSectionPeriodReports.text       = AppStrings.reportsSectionPeriod.get(lang)
        binding.textPurchaseReportTitle.text        = AppStrings.reportsPurchaseReportTitle.get(lang)
        binding.textPurchaseReportDesc.text         = AppStrings.reportsPurchaseReportDesc.get(lang)
        binding.textSectionMonthlyReports.text      = AppStrings.reportsSectionMonthly.get(lang)
        binding.textMonthlyPurchasesTitle.text      = AppStrings.reportsMonthlyPurchasesTitle.get(lang)
        binding.textMonthlyPurchasesDesc.text       = AppStrings.reportsMonthlyPurchasesDesc.get(lang)
        val viewLabel = AppStrings.reportsViewButton.get(lang)
        binding.btnDailySheetView.text              = viewLabel
        binding.btnClosingView.text                 = viewLabel
        binding.btnPurchaseView.text                = viewLabel
        binding.btnBrandWiseView.text               = viewLabel
        binding.checkIncludeZero.text               = AppStrings.reportsIncludeZero.get(lang)
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

    private fun navigateToPurchaseViewer(fromDate: String, toDate: String, title: String) {
        val bundle = Bundle().apply {
            putString(ReportViewerFragment.ARG_REPORT_TYPE, ReportViewerFragment.TYPE_PURCHASE_REPORT)
            putString(ReportViewerFragment.ARG_DATE,    fromDate)
            putString(ReportViewerFragment.ARG_DATE_TO, toDate)
            putString(ReportViewerFragment.ARG_TITLE,   title)
        }
        findNavController().navigate(R.id.reportViewerFragment, bundle)
    }
}
