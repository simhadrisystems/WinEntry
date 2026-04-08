package com.simple.simpleinventory.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.simple.simpleinventory.databinding.FragmentHelpSupportBinding
import com.simple.simpleinventory.utils.AppDialogs
import com.simple.simpleinventory.utils.SupportHelper
import com.simple.simpleinventory.utils.SupportHelper.IssueType

class HelpSupportFragment : Fragment() {

    private var _binding: FragmentHelpSupportBinding? = null
    private val binding get() = _binding!!

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

        binding.cardReportIssue.setOnClickListener {
            showContactDialog(IssueType.BUG)
        }

        binding.cardAskHelp.setOnClickListener {
            showContactDialog(IssueType.HELP)
        }

        binding.cardRequestFeature.setOnClickListener {
            showContactDialog(IssueType.FEATURE)
        }
    }

    private fun showContactDialog(issueType: IssueType) {
        AppDialogs.choice(
            context    = requireContext(),
            title      = "Contact Support",
            message    = "How would you like to reach us?",
            items      = arrayOf("Email", "WhatsApp"),
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
