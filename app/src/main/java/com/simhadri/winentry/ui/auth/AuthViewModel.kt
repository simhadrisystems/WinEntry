package com.simhadri.winentry.ui.auth

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.simhadri.winentry.data.UserProfile
import com.simhadri.winentry.utils.UserRegistrationManager
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

sealed class AuthState {
    object Idle : AuthState()
    data class Loading(val message: String) : AuthState()
    object Success : AuthState()
    data class Error(val message: String) : AuthState()
}

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val _authState = MutableLiveData<AuthState>(AuthState.Idle)
    val authState: LiveData<AuthState> = _authState

    private val firestore = FirebaseFirestore.getInstance()

    companion object {
        const val PREFS_NAME        = "inventory_prefs"
        const val KEY_USER_SHEET_ID      = "user_sheet_id"
        const val KEY_USER_EMAIL         = "user_email"
        const val KEY_USER_UID           = "user_uid"
        const val KEY_USER_ROLE          = "user_role"          // "editor" | "viewer"
        const val KEY_SHEET_ID_MISSING   = "sheet_id_missing"   // true = doc exists but sheetId not set by admin
    }

    /**
     * Entry point after Firebase credential sign-in succeeds.
     *
     * Design principle: login must be instant — the app is fully functional
     * with local Room DB regardless of cloud state.  Sheet lookup and admin
     * notification happen silently in the background AFTER the user is
     * already on the Home screen.  Nothing here should block or time-out.
     *
     * Fast path  — sheet ID cached locally → Home in <100ms.
     * Background — no local cache → emit Success immediately, then fetch
     *              Firestore silently.  If a sheetId is found it is saved to
     *              prefs for the next sync; if not found the admin is notified.
     *
     * The user configures cloud backup at their own pace via
     * Settings → Sync Settings.  No Cloud Function is called at login.
     */
    fun handleSignedInUser(user: FirebaseUser) {
        viewModelScope.launch {
            try {
                _authState.value = AuthState.Loading("Signing in…")

                val prefs = getApplication<Application>()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

                // Persist basic identity from the Firebase token immediately
                prefs.edit()
                    .putString(KEY_USER_EMAIL, user.email)
                    .putString(KEY_USER_UID, user.uid)
                    .apply()

                // ── Fast path: sheet ID already cached ─────────────────────────
                val cachedSheetId = prefs.getString(KEY_USER_SHEET_ID, null)
                if (!cachedSheetId.isNullOrBlank()) {
                    saveToSyncPrefs(cachedSheetId)
                    _authState.value = AuthState.Success
                    return@launch
                }

                // ── No local cache — go to Home now, check Firestore in background
                // The app is fully usable without a cloud sheet ID.  The user can
                // set up cloud backup later via Settings → Sync Settings.
                _authState.value = AuthState.Success
                fetchSheetIdInBackground(user)

            } catch (e: Exception) {
                _authState.value = AuthState.Error("Sign-in error: ${e.message}")
            }
        }
    }

    /**
     * Runs after the user is already on the Home screen — never touches UI state.
     *
     * Firestore /users/{uid} found → save sheetId + role to local prefs so the
     *   next sync works automatically without another Firestore read.
     *
     * Not found (brand-new user) → the user can request a workspace sheet
     *   via Settings → Drive Backup.
     */
    private fun fetchSheetIdInBackground(user: FirebaseUser) {
        viewModelScope.launch {
            try {
                val prefs = getApplication<Application>()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

                val snapshot = withContext(NonCancellable) {
                    firestore.collection("users").document(user.uid).get().await()
                }

                if (snapshot.exists()) {
                    val sheetId = snapshot.getString("userSheetId")
                    val role    = snapshot.getString("role")
                        ?.takeIf { it == "viewer" || it == "editor" }
                        ?: "editor"

                    if (!sheetId.isNullOrBlank()) {
                        prefs.edit()
                            .putString(KEY_USER_SHEET_ID, sheetId)
                            .putString(KEY_USER_ROLE, role)
                            .remove(KEY_SHEET_ID_MISSING)
                            .apply()
                        saveToSyncPrefs(sheetId)
                        android.util.Log.i("AuthViewModel",
                            "Sheet ID recovered from Firestore in background")
                    } else {
                        // Document exists but admin hasn't set userSheetId yet — flag for UI
                        prefs.edit().putBoolean(KEY_SHEET_ID_MISSING, true).apply()
                        android.util.Log.w("AuthViewModel",
                            "users/${user.uid} exists but userSheetId is blank. Fields: ${snapshot.data?.keys}")
                        ErrorLogger.log(getApplication(), "AuthViewModel",
                            "users/${user.uid} doc exists but userSheetId blank. fields=${snapshot.data?.keys}")
                    }
                } else {
                    // Brand-new user — no sheet yet. Do NOT write admin_requests here:
                    // the user hasn't filled in business details yet. The Settings →
                    // Sync Settings registration form writes admin_requests with all
                    // fields, which triggers onAdminRequestCreated with complete data.
                    android.util.Log.i("AuthViewModel",
                        "New user — no Firestore doc yet. User should register via Settings.")
                }
            } catch (e: Exception) {
                android.util.Log.w("AuthViewModel",
                    "Background Firestore check failed (non-fatal): ${e.message}")
                com.simhadri.winentry.ui.auth.ErrorLogger.log(
                    getApplication(), "AuthViewModel",
                    "fetchSheetIdInBackground failed for uid=${user.uid}", e)
            }
        }
    }

    /**
     * Writes the user sheet ID into SyncPrefs so SyncCoordinator
     * can read it via getSpreadsheetId() without any changes to its API.
     */
    private fun saveToSyncPrefs(sheetId: String) {
        getApplication<Application>()
            .getSharedPreferences("SyncPrefs", Context.MODE_PRIVATE)
            .edit()
            .putString("spreadsheet_id", sheetId)
            .apply()
    }

    /**
     * Signs out from Firebase Auth and clears all per-user local state.
     * Clears: auth identity, sync sheet ID, workspace request flag,
     * registration state, and business info — so a different user
     * logging in on the same device starts with a clean slate.
     */
    fun signOut() {
        val app = getApplication<Application>()

        com.google.firebase.auth.FirebaseAuth.getInstance().signOut()

        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_USER_SHEET_ID)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_USER_UID)
            .remove(KEY_USER_ROLE)
            .apply()

        // Clear sync state — sheet ID and workspace request are user-specific
        app.getSharedPreferences("SyncPrefs", Context.MODE_PRIVATE)
            .edit()
            .remove("spreadsheet_id")
            .remove("workspace_requested")
            .apply()

        // Clear registration flag — it is UID-scoped so a new user must register
        UserRegistrationManager.clearOnSignOut(app)

        // Clear business info — a different user should not see the previous user's details
        app.getSharedPreferences("business_info", Context.MODE_PRIVATE)
            .edit().clear().apply()

        _authState.value = AuthState.Idle
    }
}
