package com.simple.simpleinventory.ui.auth

import android.content.Context
import com.simple.simpleinventory.ui.auth.AuthViewModel

/**
 * UserRole — central helper for role-based access control.
 *
 * Roles (stored in Firestore /users/{uid} → "role" field,
 * cached locally in SharedPreferences after login):
 *
 *   "editor"  — full access: data entry, sync up/down, all buttons active
 *   "viewer"  — read-only:   sync down only, no data entry, destructive
 *               buttons hidden
 *
 * Default is "editor" so existing users without a role field
 * are unaffected.
 *
 * Usage:
 *   if (UserRole.isViewer(requireContext())) { hide buttons }
 *   if (UserRole.isEditor(requireContext())) { allow save }
 */
object UserRole {

    const val EDITOR = "editor"
    const val VIEWER = "viewer"

    /**
     * Returns the current user's role from local SharedPreferences.
     * Defaults to "editor" if not set — preserves existing behavior
     * for all users created before role support was added.
     */
    fun getRole(context: Context): String {
        return context
            .getSharedPreferences(AuthViewModel.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(AuthViewModel.KEY_USER_ROLE, EDITOR)
            ?: EDITOR
    }

    fun isViewer(context: Context): Boolean = getRole(context) == VIEWER

    fun isEditor(context: Context): Boolean = getRole(context) == EDITOR
}
