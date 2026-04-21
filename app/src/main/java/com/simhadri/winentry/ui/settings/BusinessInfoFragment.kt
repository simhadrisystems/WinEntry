package com.simhadri.winentry.ui.settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.simhadri.winentry.databinding.FragmentBusinessInfoBinding

class BusinessInfoFragment : Fragment() {

    private var _binding: FragmentBusinessInfoBinding? = null
    private val binding get() = _binding!!

    companion object {
        const val PREFS_NAME      = "business_info"
        const val KEY_OWNER_NAME  = "owner_name"
        const val KEY_BUSINESS    = "business_name"
        const val KEY_LICENCE     = "licence_no"
        const val KEY_ADDRESS1    = "address1"
        const val KEY_ADDRESS2    = "address2"
        const val KEY_LOCATION    = "location"
        const val KEY_PHONE       = "phone"
        const val KEY_EMAIL       = "email"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBusinessInfoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        populateFields()

        binding.buttonSave.setOnClickListener {
            saveFields()
        }
    }

    private fun populateFields() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        binding.editOwnerName.setText(prefs.getString(KEY_OWNER_NAME, ""))
        binding.editBusinessName.setText(prefs.getString(KEY_BUSINESS, ""))
        binding.editLicenceNo.setText(prefs.getString(KEY_LICENCE, ""))
        binding.editAddress1.setText(prefs.getString(KEY_ADDRESS1, ""))
        binding.editAddress2.setText(prefs.getString(KEY_ADDRESS2, ""))
        binding.editLocation.setText(prefs.getString(KEY_LOCATION, ""))
        binding.editPhone.setText(prefs.getString(KEY_PHONE, ""))
        binding.editEmail.setText(prefs.getString(KEY_EMAIL, ""))
    }

    private fun saveFields() {
        val businessName = binding.editBusinessName.text.toString().trim()
        if (businessName.isEmpty()) {
            binding.editBusinessName.error = "Business name is required"
            binding.editBusinessName.requestFocus()
            return
        }

        requireContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_OWNER_NAME, binding.editOwnerName.text.toString().trim())
            .putString(KEY_BUSINESS,   businessName)
            .putString(KEY_LICENCE,    binding.editLicenceNo.text.toString().trim())
            .putString(KEY_ADDRESS1,   binding.editAddress1.text.toString().trim())
            .putString(KEY_ADDRESS2,   binding.editAddress2.text.toString().trim())
            .putString(KEY_LOCATION,   binding.editLocation.text.toString().trim())
            .putString(KEY_PHONE,      binding.editPhone.text.toString().trim())
            .putString(KEY_EMAIL,      binding.editEmail.text.toString().trim())
            .apply()

        Toast.makeText(requireContext(), "Business info saved", Toast.LENGTH_SHORT).show()
        findNavController().navigateUp()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
