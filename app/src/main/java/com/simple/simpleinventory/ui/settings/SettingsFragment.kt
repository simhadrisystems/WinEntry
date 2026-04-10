package com.simple.simpleinventory.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.simple.simpleinventory.MainActivity
import com.simple.simpleinventory.R
import com.simple.simpleinventory.databinding.FragmentSettingsBinding
import com.simple.simpleinventory.ui.auth.ErrorLogger
import com.simple.simpleinventory.utils.AppDialogs
import com.simple.simpleinventory.utils.AppStrings
import com.simple.simpleinventory.utils.LangPrefs

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.cardLanguage.setOnClickListener {
            val names = AppStrings.Lang.entries.map { it.displayName }.toTypedArray()
            AppDialogs.choice(requireContext(), "Language / భాష", names) { which ->
                val selected = AppStrings.Lang.entries[which]
                LangPrefs.set(requireContext(), selected)
                applyLanguage()
            }
        }

        binding.cardBusinessInfo.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_businessInfo)
        }

        binding.cardSyncSettings.setOnClickListener {
            (activity as? MainActivity)?.showSyncSettingsPublic()
        }

        binding.cardHelpSupport.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_helpSupport)
        }

        // ── Error Log ─────────────────────────────────────────────────────
        // Show share button only when errors exist — hidden otherwise
        updateErrorLogButton()

        binding.btnShareErrorLog.setOnClickListener {
            ErrorLogger.share(requireContext())
        }

        binding.btnClearErrorLog.setOnClickListener {
            ErrorLogger.clear(requireContext())
            updateErrorLogButton()
        }
    }

    override fun onResume() {
        super.onResume()
        applyLanguage()
    }

    private fun applyLanguage() {
        val lang = LangPrefs.get(requireContext())
        binding.toolbar.title                  = AppStrings.settingsToolbarTitle.get(lang)
        binding.textSectionLanguage.text       = AppStrings.settingsSectionLanguage.get(lang)
        binding.textLanguageTitle.text         = AppStrings.settingsLanguageTitle.get(lang)
        binding.textLanguageDesc.text          = AppStrings.settingsLanguageDesc.get(lang)
        binding.textSectionBusiness.text       = AppStrings.settingsSectionBusiness.get(lang)
        binding.textBusinessInfoTitle.text     = AppStrings.settingsBusinessInfoTitle.get(lang)
        binding.textBusinessInfoDesc.text      = AppStrings.settingsBusinessInfoDesc.get(lang)
        binding.textSectionSync.text           = AppStrings.settingsSectionSync.get(lang)
        binding.textSyncSettingsTitle.text     = AppStrings.settingsSyncTitle.get(lang)
        binding.textSyncSettingsDesc.text      = AppStrings.settingsSyncDesc.get(lang)
        binding.textSectionSupport.text        = AppStrings.settingsSectionSupport.get(lang)
        binding.textHelpSupportTitle.text      = AppStrings.settingsHelpSupportTitle.get(lang)
        binding.textHelpSupportDesc.text       = AppStrings.settingsHelpSupportDesc.get(lang)
    }

    private fun updateErrorLogButton() {
        val hasErrors = ErrorLogger.hasErrors(requireContext())
        binding.btnShareErrorLog.visibility = if (hasErrors) View.VISIBLE else View.GONE
        binding.btnClearErrorLog.visibility = if (hasErrors) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
