package com.simple.simpleinventory.utils

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder

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
     */
    fun confirm(
        context: Context,
        title: String,
        message: String,
        actionLabel: String,
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
     */
    fun choice(
        context: Context,
        title: String,
        message: String? = null,
        items: Array<String>,
        onChoice: (Int) -> Unit
    ) {
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setItems(items) { _, which -> onChoice(which) }
            .setNegativeButton("Cancel", null)
        if (!message.isNullOrBlank()) builder.setMessage(message)
        builder.show()
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
