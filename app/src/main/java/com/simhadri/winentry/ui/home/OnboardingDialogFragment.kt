package com.simhadri.winentry.ui.home

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.simhadri.winentry.R
import com.simhadri.winentry.databinding.DialogOnboardingBinding
import com.simhadri.winentry.databinding.ItemOnboardingStepBinding
import com.simhadri.winentry.utils.AppStrings
import com.simhadri.winentry.utils.LangPrefs
import com.simhadri.winentry.utils.SupportHelper
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Calendar

class OnboardingDialogFragment : DialogFragment() {

    private var _binding: DialogOnboardingBinding? = null
    private val binding get() = _binding!!

    private val vm: OnboardingViewModel by viewModels(
        ownerProducer = { requireParentFragment() }
    )

    private var lang = AppStrings.Lang.EN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.FullScreenDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogOnboardingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Push content below the status bar — required on Android 15 edge-to-edge dialogs
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        lang = LangPrefs.get(requireContext())

        // Language toggle — initialize to current language
        binding.langToggleGroup.check(
            if (lang == AppStrings.Lang.EN) R.id.btnLangEn else R.id.btnLangTe
        )
        binding.langToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            lang = if (checkedId == R.id.btnLangEn) AppStrings.Lang.EN else AppStrings.Lang.TE
            LangPrefs.set(requireContext(), lang)
            applyStaticText()
            vm.state.value.let { render(it) }
        }

        applyStaticText()

        binding.btnClose.setOnClickListener { dismiss() }

        // Navigate without dismissing — dialog re-appears when user returns to Home
        binding.stepBusinessInfo.btnStepAction.setOnClickListener {
            requireParentFragment().findNavController()
                .navigate(R.id.action_home_to_businessInfo)
        }
        binding.stepImportProducts.btnStepAction.setOnClickListener {
            vm.startProductDownload()
        }
        binding.stepTestData.btnStepAction.setOnClickListener {
            showDatePicker()
        }
        binding.stepOpeningStock.btnStepAction.setOnClickListener {
            requireParentFragment().findNavController()
                .navigate(R.id.action_home_to_openingStock)
        }
        binding.btnUserGuide.setOnClickListener {
            SupportHelper.openUserGuide(requireContext())
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { state -> render(state) }
            }
        }
    }

    private fun applyStaticText() {
        if (_binding == null) return
        binding.tvOnboardingTitle.text   = AppStrings.onboardingTitle.get(lang)
        binding.tvOnboardingWelcome.text = AppStrings.onboardingWelcome.get(lang)
        binding.tvOnboardingDesc.text    = AppStrings.onboardingDesc.get(lang)
        binding.tvChecklistHeader.text   = AppStrings.onboardingChecklist.get(lang)
        binding.tvUserGuideNote.text     = AppStrings.onboardingUserGuideNote.get(lang)
        binding.btnUserGuide.text        = AppStrings.onboardingUserGuideBtn.get(lang)
    }

    private fun render(state: OnboardingState) {
        if (_binding == null) return

        binding.tvOnboardingProgress.text =
            "${state.completedCount} / ${state.totalRequired}"

        renderStep(
            step         = binding.stepBusinessInfo,
            label        = AppStrings.onboardingStep1.get(lang),
            done         = state.step1Done,
            actionLabel  = if (lang == AppStrings.Lang.TE) "సెటప్" else "Set Up",
            showProgress = false
        )
        renderStep(
            step         = binding.stepImportProducts,
            label        = AppStrings.onboardingStep2.get(lang),
            done         = state.step2Done,
            actionLabel  = if (lang == AppStrings.Lang.TE) "దిగుమతి" else "Download",
            showProgress = state.downloadInProgress,
            locked       = !state.step1Done
        )
        renderStep(
            step         = binding.stepTestData,
            label        = AppStrings.onboardingStep3.get(lang),
            done         = state.step3Done,
            actionLabel  = if (lang == AppStrings.Lang.TE) "లోడ్" else "Load",
            showProgress = state.testImportInProgress,
            locked       = !state.step1Done
        )
        renderStep(
            step         = binding.stepOpeningStock,
            label        = AppStrings.onboardingStep4.get(lang),
            done         = state.step4Done && !state.step3Done,
            actionLabel  = if (lang == AppStrings.Lang.TE) "ఎంటర్" else "Enter",
            showProgress = false
        )

        state.downloadError?.let { error ->
            Snackbar.make(binding.root, "Download failed: $error", Snackbar.LENGTH_LONG).show()
            vm.clearDownloadError()
        }
        state.testImportError?.let { error ->
            Snackbar.make(binding.root, error, Snackbar.LENGTH_LONG).show()
            vm.clearTestImportError()
        }

        if (state.requiredDone) dismiss()
    }

    private fun renderStep(
        step: ItemOnboardingStepBinding,
        label: String,
        done: Boolean,
        actionLabel: String,
        showProgress: Boolean,
        locked: Boolean = false
    ) {
        step.tvStepLabel.text = label
        step.ivStepStatus.setImageResource(
            if (done) R.drawable.ic_onboarding_done else R.drawable.ic_onboarding_pending
        )
        when {
            done         -> {
                step.btnStepAction.visibility  = View.GONE
                step.pbStepProgress.visibility = View.GONE
            }
            showProgress -> {
                step.btnStepAction.visibility  = View.GONE
                step.pbStepProgress.visibility = View.VISIBLE
            }
            locked       -> {
                step.btnStepAction.text        = actionLabel
                step.btnStepAction.isEnabled   = false
                step.btnStepAction.visibility  = View.VISIBLE
                step.pbStepProgress.visibility = View.GONE
            }
            else         -> {
                step.btnStepAction.text        = actionLabel
                step.btnStepAction.isEnabled   = true
                step.btnStepAction.visibility  = View.VISIBLE
                step.pbStepProgress.visibility = View.GONE
            }
        }
    }

    private fun showDatePicker() {
        val default = LocalDate.now().minusDays(7)
        val cal = Calendar.getInstance().apply {
            set(default.year, default.monthValue - 1, default.dayOfMonth)
        }
        val title = if (lang == AppStrings.Lang.TE)
            "నమూనా డేటాకు ప్రారంభ నిల్వ తేదీ"
        else
            "Opening Balance Date for Sample Data"
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                vm.startTestDataImport(LocalDate.of(year, month + 1, day))
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).apply {
            setTitle(title)
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
