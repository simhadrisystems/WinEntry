package com.simhadri.winentry.utils

import android.content.Context
import android.content.DialogInterface
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.simhadri.winentry.R

/**
 * Centralised dialog factory for the entire app.
 *
 * WHY THIS EXISTS
 * ───────────────
 * Calling MaterialAlertDialogBuilder inline in every Fragment/Activity leads to:
 *   • Inconsistent button labels and UX patterns across screens.
 *   • Dark-mode theming bugs if the wrong context is passed.
 *   • Duplicated boilerplate that has to be fixed in N places.
 *
 * Every dialog in the app should be created via one of the functions below.
 * Future changes (custom layouts, analytics, accessibility) happen in one place.
 *
 * USAGE
 * ─────
 *   // Simple info
 *   AppDialogs.info(requireContext(), "Title", "Message")
 *
 *   // Confirmation
 *   AppDialogs.confirm(requireContext(), "Delete?", "This cannot be undone.") {
 *       viewModel.delete(item)
 *   }
 *
 *   // Destructive (same visual as confirm — reserved for deletes / sign-out)
 *   AppDialogs.destructive(requireContext(), "Sign Out", "Are you sure?") {
 *       signOut()
 *   }
 *
 *   // Toggle setting (shows current state + action label that switches it)
 *   AppDialogs.toggle(requireContext(), "Sync Settings", statusMessage, toggleLabel) {
 *       coordinator.setAutoSyncEnabled(!isEnabled)
 *   }
 */
object AppDialogs {

    /**
     * Single-button informational dialog.
     * Use for account info, success messages, read-only status.
     */
    fun info(
        context: Context,
        title: String,
        message: String,
        buttonLabel: String = "Close"
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(buttonLabel, null)
            .show()
    }

    /**
     * Two-button confirmation: Cancel + action.
     * Use for any irreversible or significant action.
     * [onCancel] is optional — called when the user taps Cancel (default: no-op).
     */
    fun confirm(
        context: Context,
        title: String,
        message: String,
        actionLabel: String,
        onCancel: () -> Unit = {},
        onConfirm: () -> Unit
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Cancel") { _, _ -> onCancel() }
            .setPositiveButton(actionLabel) { _, _ -> onConfirm() }
            .show()
    }

    /**
     * Destructive confirmation (delete / sign-out / clear data).
     * Semantically identical to confirm — kept separate so a future
     * redesign (e.g. red button) only needs to change this function.
     */
    fun destructive(
        context: Context,
        title: String,
        message: String,
        actionLabel: String = "Delete",
        onConfirm: () -> Unit
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(actionLabel) { _, _ -> onConfirm() }
            .show()
    }

    /**
     * Single-choice list dialog.
     * Use when the user must pick one option from a short list (e.g. Email vs WhatsApp).
     * [onChoice] receives the 0-based index of the tapped item.
     * Note: setMessage and setItems are mutually exclusive in AlertDialog — do not add message support.
     */
    fun choice(
        context: Context,
        title: String,
        items: Array<String>,
        onChoice: (Int) -> Unit
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setItems(items) { _, which -> onChoice(which) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Email-confirmed destructive dialog — used for account deletion.
     * The action button stays disabled until the user types their sign-in email exactly.
     * This prevents accidental or unauthorised deletion if the device is unattended.
     */
    fun withEmailInput(
        context: Context,
        title: String,
        message: String,
        actionLabel: String,
        expectedEmail: String,
        onConfirm: () -> Unit
    ) {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.dialog_email_confirm, null)
        val emailInput  = view.findViewById<TextInputEditText>(R.id.emailInput)
        val inputLayout = view.findViewById<TextInputLayout>(R.id.emailInputLayout)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setView(view)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(actionLabel, null)
            .show()

        val confirmBtn = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        confirmBtn.isEnabled = false

        emailInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                confirmBtn.isEnabled = s.toString().trim()
                    .equals(expectedEmail, ignoreCase = true)
                inputLayout.error = null
            }
        })

        confirmBtn.setOnClickListener {
            if (emailInput.text.toString().trim().equals(expectedEmail, ignoreCase = true)) {
                dialog.dismiss()
                onConfirm()
            } else {
                inputLayout.error = "Email does not match your account"
            }
        }
    }

    /**
     * Toggle/settings dialog: Close (neutral) + dynamic action label.
     * Use for on/off settings like auto-sync where the button label
     * reflects the *next* state ("Enable Auto-Sync" / "Disable Auto-Sync").
     */
    fun toggle(
        context: Context,
        title: String,
        message: String,
        actionLabel: String,
        onAction: () -> Unit
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setNeutralButton("Close", null)
            .setPositiveButton(actionLabel) { _, _ -> onAction() }
            .show()
    }
}
