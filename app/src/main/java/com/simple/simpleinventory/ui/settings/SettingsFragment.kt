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

        binding.cardProductOrder.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_productOrder)
        }

        binding.cardOpeningStock.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_openingStock)
        }

        binding.cardProducts.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_products)
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
