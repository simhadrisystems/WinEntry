package com.simple.simpleinventory.ui.purchases

import android.app.DatePickerDialog
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.simple.simpleinventory.databinding.DialogDeleteDateRangeBinding
import java.text.SimpleDateFormat
import java.util.*

/**
 * Dialog for deleting purchases within a date range
 */
class DeleteDateRangeDialog : DialogFragment() {

    private var _binding: DialogDeleteDateRangeBinding? = null
    private val binding get() = _binding!!
    
    private var startDate: String = ""
    private var endDate: String = ""
    
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    private val dbDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    
    private var onDeleteListener: ((String, String) -> Unit)? = null
    
    fun setOnDeleteListener(listener: (startDate: String, endDate: String) -> Unit) {
        onDeleteListener = listener
    }
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogDeleteDateRangeBinding.inflate(LayoutInflater.from(requireContext()))
        
        // Initialize with current month
        val calendar = Calendar.getInstance()
        endDate = dbDateFormat.format(calendar.time)
        binding.etEndDate.setText(dateFormat.format(calendar.time))
        
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        startDate = dbDateFormat.format(calendar.time)
        binding.etStartDate.setText(dateFormat.format(calendar.time))
        
        setupDatePickers()
        
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Purchases by Date Range")
            .setView(binding.root)
            .setPositiveButton("Delete") { _, _ ->
                if (startDate.isNotEmpty() && endDate.isNotEmpty()) {
                    onDeleteListener?.invoke(startDate, endDate)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
    }
    
    private fun setupDatePickers() {
        binding.etStartDate.setOnClickListener {
            showDatePicker(startDate) { date ->
                startDate = date
                binding.etStartDate.setText(dateFormat.format(dbDateFormat.parse(date)!!))
            }
        }
        
        binding.etEndDate.setOnClickListener {
            showDatePicker(endDate) { date ->
                endDate = date
                binding.etEndDate.setText(dateFormat.format(dbDateFormat.parse(date)!!))
            }
        }
    }
    
    private fun showDatePicker(currentDate: String, onDateSelected: (String) -> Unit) {
        val calendar = Calendar.getInstance()
        if (currentDate.isNotEmpty()) {
            calendar.time = dbDateFormat.parse(currentDate)!!
        }
        
        DatePickerDialog(
            requireContext(),
            { _, year, month, day ->
                calendar.set(year, month, day)
                onDateSelected(dbDateFormat.format(calendar.time))
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
    
    companion object {
        fun newInstance() = DeleteDateRangeDialog()
    }
}
