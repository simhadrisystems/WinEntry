package com.simhadri.winentry.ui.reports

import android.content.Intent
import android.os.Bundle
import android.print.PrintManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.simhadri.winentry.databinding.FragmentReportViewerBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ReportViewerFragment : Fragment() {

    private var _binding: FragmentReportViewerBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: ReportViewerViewModel

    companion object {
        const val ARG_REPORT_TYPE         = "reportType"
        const val ARG_DATE                = "date"
        const val ARG_TITLE               = "title"
        const val ARG_DATE_TO             = "dateTo"
        const val ARG_INCLUDE_ZERO        = "includeZero"
        const val TYPE_DAILY_SHEET          = "DAILY_SHEET"
        const val TYPE_CLOSING_BALANCES     = "CLOSING_BALANCES"
        const val TYPE_PURCHASE_REPORT      = "PURCHASE_REPORT"
        const val TYPE_BRAND_WISE_REPORT    = "BRAND_WISE"
        const val TYPE_SALES_MARGIN_REPORT  = "SALES_MARGIN"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        viewModel = ViewModelProvider(this)[ReportViewerViewModel::class.java]
        _binding = FragmentReportViewerBinding.inflate(inflater, container, false)
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
        ViewCompat.setOnApplyWindowInsetsListener(binding.webView) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }

        val reportType = arguments?.getString(ARG_REPORT_TYPE) ?: return
        val date       = arguments?.getString(ARG_DATE)        ?: return
        val dateTo     = arguments?.getString(ARG_DATE_TO)     ?: date
        val title      = arguments?.getString(ARG_TITLE)       ?: "Report"

        binding.toolbarTitle.text = title
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.webView.settings.apply {
            javaScriptEnabled    = false
            builtInZoomControls  = true
            displayZoomControls  = false
            useWideViewPort      = true
            loadWithOverviewMode = true
        }
        binding.webView.webViewClient = WebViewClient()

        // Print button
        binding.btnPrint.setOnClickListener {
            if (viewModel.cachedHtml.isNotEmpty()) printWebView(title)
        }

        // Share button
        binding.btnShare.setOnClickListener {
            if (viewModel.cachedHtml.isNotEmpty()) shareHtmlAsPdf(title, date, dateTo, reportType)
        }

        // Observe report state — handles both first load and rotation restore
        viewModel.state.observe(viewLifecycleOwner) { state ->
            when (state) {
                is ReportViewerViewModel.ReportState.Loading -> {
                    // Optionally show a progress indicator here
                }
                is ReportViewerViewModel.ReportState.Ready -> {
                    binding.webView.loadDataWithBaseURL(
                        null, state.html, "text/html", "UTF-8", null)
                }
                is ReportViewerViewModel.ReportState.Empty -> {
                    binding.webView.loadData(
                        viewModel.buildNoDataHtml(state.date), "text/html", "UTF-8")
                    Toast.makeText(requireContext(),
                        "No data found for ${state.date}", Toast.LENGTH_SHORT).show()
                }
                is ReportViewerViewModel.ReportState.Error -> {
                    Toast.makeText(requireContext(),
                        "Error: ${state.msg}", Toast.LENGTH_LONG).show()
                }
            }
        }

        // Trigger load — ViewModel ignores this if HTML already cached (rotation safe)
        viewModel.loadReport(arguments)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun printWebView(title: String) {
        val printManager = requireContext()
            .getSystemService(android.content.Context.PRINT_SERVICE) as PrintManager
        val jobName = title.replace("·", "-").replace("  ", " ").trim()
        val printAdapter = binding.webView.createPrintDocumentAdapter(jobName)
        printManager.print(
            jobName,
            printAdapter,
            null   // null = use printer defaults; user controls orientation in print dialog
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // SHARE — saves HTML to cache and shares via Intent
    // ═══════════════════════════════════════════════════════════════

    private fun shareHtmlAsPdf(title: String, date: String, dateTo: String, reportType: String) {
        val html = viewModel.cachedHtml
        if (html.isEmpty()) return
        try {
            val safeName = date.replace("-", "") + "_" + when (reportType) {
                TYPE_DAILY_SHEET      -> "DailySheet"
                TYPE_CLOSING_BALANCES -> "ClosingBalances"
                TYPE_PURCHASE_REPORT  -> "Purchases_to_${dateTo.replace("-", "")}"
                TYPE_BRAND_WISE_REPORT -> "BrandWise"
                else -> "Report"
            } + ".html"

            val dir = requireContext().externalCacheDir ?: requireContext().cacheDir
            dir.mkdirs()
            val file = File(dir, safeName)
            file.writeText(html, Charsets.UTF_8)

            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )

            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/html"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share Report"))

        } catch (e: Exception) {
            try {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, html)
                    putExtra(Intent.EXTRA_SUBJECT, title)
                }, "Share Report"))
            } catch (e2: Exception) {
                Toast.makeText(requireContext(), "Share failed: ${e2.message}", Toast.LENGTH_LONG).show()
            }
        }
    }



    /** Format integer with Indian digit grouping: ##,##,### (lakhs/crores) */
    private fun fmtUnits(n: Int): String {
        if (n == 0) return "0"
        val inLocale = java.util.Locale("en", "IN")
        return java.text.NumberFormat.getIntegerInstance(inLocale).format(n.toLong())
    }

    /** Format currency with Indian digit grouping: ₹##,##,###.## */
    private fun fmtCurrency(amount: Double): String {
        val inLocale = java.util.Locale("en", "IN")
        val nf = java.text.NumberFormat.getNumberInstance(inLocale).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        return "₹${nf.format(amount)}"
    }

    // ── loadEntriesAll — same as loadEntries but includes zero-activity products ──
}
