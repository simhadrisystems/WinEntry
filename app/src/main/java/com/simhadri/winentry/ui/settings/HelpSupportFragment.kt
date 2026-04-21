package com.simhadri.winentry.ui.settings

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.simhadri.winentry.databinding.FragmentHelpSupportBinding
import com.simhadri.winentry.utils.AppDialogs
import com.simhadri.winentry.utils.SupportHelper
import com.simhadri.winentry.utils.SupportHelper.IssueType
import java.time.LocalDate
import java.util.Calendar

class HelpSupportFragment : Fragment() {

    private var _binding: FragmentHelpSupportBinding? = null
    private val binding get() = _binding!!

    private val testDataViewModel: TestDataViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHelpSupportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.cardUserGuide.setOnClickListener {
            SupportHelper.openUserGuide(requireContext())
        }
        binding.cardReportIssue.setOnClickListener { showContactDialog(IssueType.BUG) }
        binding.cardAskHelp.setOnClickListener { showContactDialog(IssueType.HELP) }
        binding.cardRequestFeature.setOnClickListener { showContactDialog(IssueType.FEATURE) }

        binding.cardImportTestData.setOnClickListener { onImportTestDataTapped() }

        observeTestDataState()
        testDataViewModel.checkDataState()
    }

    // ── Test data import card ─────────────────────────────────────────────────

    private fun observeTestDataState() {
        testDataViewModel.state.observe(viewLifecycleOwner) { state ->
            when (state) {
                is TestDataViewModel.State.Checking -> {
                    binding.tvTestDataStatus.text = "Checking database…"
                    setCardEnabled(false)
                }
                is TestDataViewModel.State.NoData -> {
                    binding.tvTestDataStatus.text = "No stock data found — tap to load sample data and explore the app."
                    setCardEnabled(true)
                }
                is TestDataViewModel.State.HasData -> {
                    // Disabled — only for fresh installs with no data
                    binding.tvTestDataStatus.text = "Daily stock data already exists in this device."
                    setCardEnabled(false)
                }
                is TestDataViewModel.State.Loading -> {
                    binding.tvTestDataStatus.text = "Downloading and importing test data…"
                    setCardEnabled(false)
                }
                is TestDataViewModel.State.Done -> {
                    if (state.success) {
                        AppDialogs.info(requireContext(), "Test Data Loaded", state.message)
                    } else {
                        AppDialogs.info(requireContext(), "Import Failed", state.message)
                    }
                    testDataViewModel.checkDataState()
                }
            }
        }
    }

    private fun setCardEnabled(enabled: Boolean) {
        binding.cardImportTestData.isEnabled   = enabled
        binding.cardImportTestData.isClickable = enabled
        binding.cardImportTestData.alpha       = if (enabled) 1f else 0.42f
    }

    private fun onImportTestDataTapped() {
        // Card is disabled when HasData/Checking/Loading — this only fires for NoData
        showDatePicker { date ->
            AppDialogs.confirm(
                context     = requireContext(),
                title       = "Load Test Data",
                message     = "This will load sample Opening Balances on $date " +
                              "and 7 days of Closing Balances.\n\nProceed?",
                actionLabel = "Import"
            ) {
                testDataViewModel.importTestData(date)
            }
        }
    }

    private fun showDatePicker(onDateSelected: (LocalDate) -> Unit) {
        val default = LocalDate.now().minusDays(7)
        val cal = Calendar.getInstance().apply {
            set(default.year, default.monthValue - 1, default.dayOfMonth)
        }
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                onDateSelected(LocalDate.of(year, month + 1, day))
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).apply {
            setTitle("Opening Balance Date")
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    // ── Contact support ───────────────────────────────────────────────────────

    private fun showContactDialog(issueType: IssueType) {
        AppDialogs.choice(
            context = requireContext(),
            title   = "Contact Support",
            items   = arrayOf("Email", "WhatsApp"),
        ) { which ->
            when (which) {
                0 -> SupportHelper.sendEmail(requireContext(), issueType)
                1 -> SupportHelper.sendWhatsApp(requireContext(), issueType)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
