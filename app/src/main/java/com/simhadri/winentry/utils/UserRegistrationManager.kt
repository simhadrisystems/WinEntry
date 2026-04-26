package com.simhadri.winentry.utils

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.simhadri.winentry.ui.auth.AuthViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single owner of app-registration state.
 *
 * Registration = first-time write of /users/{uid} Firestore doc + UserRegistry row.
 * Done from BusinessInfoFragment via "Save & Register".
 *
 * Registration is UID-scoped: the stored UID is validated against the current
 * Firebase user on every [isRegistered] call. Switching accounts forces a fresh
 * registration check, so User B never inherits User A's registration state.
 *
 * Gated features call [ensureRegistered]:
 *   - Fast path: local flag set AND UID matches → calls onReady immediately.
 *   - Self-heal: flag missing but doc exists in Firestore (reinstall / new login) → marks locally → onReady.
 *   - Not registered: calls onNotRegistered (caller decides dialog/navigation).
 */
object UserRegistrationManager {

    private const val PREFS_NAME          = "user_registration"
    private const val KEY_REGISTERED      = "is_registered"
    private const val KEY_REG_DATE        = "registered_date_ms"
    private const val KEY_REGISTERED_UID  = "registered_uid"

    /**
     * Returns true only if the device has a registration flag AND it belongs to
     * the currently signed-in Firebase user.
     */
    fun isRegistered(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_REGISTERED, false)) return false
        val storedUid  = prefs.getString(KEY_REGISTERED_UID, null) ?: return false
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid ?: return false
        return storedUid == currentUid
    }

    fun markRegistered(context: Context, uid: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_REGISTERED, true)
            .putLong(KEY_REG_DATE, System.currentTimeMillis())
            .putString(KEY_REGISTERED_UID, uid)
            .apply()
    }

    fun registeredDateDisplay(context: Context): String {
        val ms = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_REG_DATE, 0L)
        if (ms == 0L) return ""
        return SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(ms))
    }

    /**
     * Called from BusinessInfoFragment on open: checks Firestore for the current uid,
     * restores business info + marks registered if the doc exists, then calls [onComplete].
     * If already registered locally, calls [onComplete] immediately (no network).
     * Always calls [onComplete] — callers use it to re-populate the UI after restore.
     */
    fun tryRestoreFromFirestore(context: Context, scope: CoroutineScope, onComplete: () -> Unit) {
        val prefs = context.getSharedPreferences(AuthViewModel.PREFS_NAME, Context.MODE_PRIVATE)
        val uid   = prefs.getString(AuthViewModel.KEY_USER_UID, null)
            ?: FirebaseAuth.getInstance().currentUser?.uid

        if (isRegistered(context) || uid.isNullOrBlank()) { onComplete(); return }

        scope.launch {
            val doc = try {
                FirebaseFirestore.getInstance()
                    .collection("users").document(uid).get().await()
            } catch (_: Exception) { null }

            if (doc != null && doc.exists()) {
                restoreBusinessInfoIfMissing(context, doc)
                markRegistered(context, uid)
            }
            onComplete()
        }
    }

    /**
     * Restores owner/business/phone/location from a Firestore doc into local business_info prefs.
     * Only writes fields that are currently blank — never overwrites locally-entered data.
     * Called on the self-heal path (reinstall / new login with a previously-registered uid).
     */
    private fun restoreBusinessInfoIfMissing(
        context: Context,
        doc: com.google.firebase.firestore.DocumentSnapshot
    ) {
        val bizPrefs = context.getSharedPreferences("business_info", Context.MODE_PRIVATE)
        val editor   = bizPrefs.edit()
        var changed  = false

        fun restoreIfBlank(localKey: String, firestoreKey: String) {
            if (bizPrefs.getString(localKey, "").isNullOrBlank()) {
                val value = doc.getString(firestoreKey) ?: return
                if (value.isNotBlank()) { editor.putString(localKey, value); changed = true }
            }
        }

        restoreIfBlank("owner_name",    "ownerName")
        restoreIfBlank("business_name", "businessName")
        restoreIfBlank("phone",         "phone")
        restoreIfBlank("location",      "location")

        if (changed) editor.apply()
    }

    /** Called from AuthViewModel.signOut() — wipes registration state for the device. */
    fun clearOnSignOut(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    /**
     * Full-control gate. [onNotRegistered] is called if registration is needed;
     * caller decides whether to show a dialog, navigate, or do nothing.
     */
    fun ensureRegistered(
        context: Context,
        scope: CoroutineScope,
        onNotRegistered: () -> Unit,
        onReady: () -> Unit
    ) {
        if (isRegistered(context)) { onReady(); return }

        val prefs = context.getSharedPreferences(AuthViewModel.PREFS_NAME, Context.MODE_PRIVATE)
        val uid = prefs.getString(AuthViewModel.KEY_USER_UID, null)
            ?: FirebaseAuth.getInstance().currentUser?.uid

        if (uid.isNullOrBlank()) { onNotRegistered(); return }

        scope.launch {
            val doc = try {
                FirebaseFirestore.getInstance()
                    .collection("users").document(uid).get().await()
            } catch (_: Exception) { null }

            if (doc != null && doc.exists()) {
                restoreBusinessInfoIfMissing(context, doc)
                markRegistered(context, uid)
                onReady()
            } else {
                onNotRegistered()
            }
        }
    }

    /**
     * Convenience overload — shows a standard info dialog pointing to Business Info.
     */
    fun ensureRegistered(context: Context, scope: CoroutineScope, onReady: () -> Unit) =
        ensureRegistered(
            context = context,
            scope = scope,
            onNotRegistered = {
                AppDialogs.info(
                    context,
                    "Registration Required",
                    "This feature requires app registration.\n\n" +
                        "Go to Settings \u2192 Business Info and tap \u2018Save & Register\u2019."
                )
            },
            onReady = onReady
        )
}
