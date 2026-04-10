package com.simple.simpleinventory.ui.auth

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.simple.simpleinventory.data.UserProfile
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

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
        const val KEY_USER_SHEET_ID = "user_sheet_id"
        const val KEY_USER_EMAIL    = "user_email"
        const val KEY_USER_UID      = "user_uid"
        const val KEY_USER_ROLE     = "user_role"   // "editor" | "viewer"
    }

    /**
     * Entry point after Firebase credential sign-in succeeds.
     *
     * Flow:
     *   1. Check local SharedPreferences cache → fast path if sheet ID exists
     *   2. Check Firestore /users/{uid} → returning user on fresh install
     *   3. No document found → admin has not set up this user yet
     *
     * The Firestore document must have:
     *   - userSheetId  : Google Sheet ID for this user
     *   - role         : "editor" (default) or "viewer"
     *
     * Two users can share the same userSheetId — one editor, one viewer.
     * The role field controls what they can do in the app.
     */
    fun handleSignedInUser(user: FirebaseUser) {
        viewModelScope.launch {
            try {
                _authState.value = AuthState.Loading("Checking your account…")

                val prefs = getApplication<Application>()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

                // ── Fast path: sheet ID already cached locally ─────────────────
                val cachedSheetId = prefs.getString(KEY_USER_SHEET_ID, null)
                if (!cachedSheetId.isNullOrBlank()) {
                    saveToSyncPrefs(cachedSheetId)
                    _authState.value = AuthState.Success
                    return@launch
                }

                // ── Check Firestore for returning user on fresh install ─────────
                _authState.value = AuthState.Loading("Looking up your profile…")
                val snapshot = try {
                    firestore.collection("users").document(user.uid).get().await()
                } catch (e: Exception) {
                    null
                }

                if (snapshot != null && snapshot.exists()) {
                    val sheetId = snapshot.getString("userSheetId")
                    if (!sheetId.isNullOrBlank()) {
                        // Read role — default to "editor" if field absent
                        val role = snapshot.getString("role")
                            ?.takeIf { it == "viewer" || it == "editor" }
                            ?: "editor"

                        prefs.edit()
                            .putString(KEY_USER_SHEET_ID, sheetId)
                            .putString(KEY_USER_EMAIL, user.email)
                            .putString(KEY_USER_UID, user.uid)
                            .putString(KEY_USER_ROLE, role)
                            .apply()
                        saveToSyncPrefs(sheetId)
                        _authState.value = AuthState.Success
                        return@launch
                    }
                }

                // ── No Firestore document found → admin setup required ──────────
                _authState.value = AuthState.Error(
                    "Account not set up yet. Please contact the admin."
                )

            } catch (e: Exception) {
                _authState.value = AuthState.Error("Sign-in error: ${e.message}")
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
     * Signs out from Firebase Auth and clears all local caches including role.
     */
    fun signOut() {
        com.google.firebase.auth.FirebaseAuth.getInstance().signOut()

        getApplication<Application>()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_USER_SHEET_ID)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_USER_UID)
            .remove(KEY_USER_ROLE)
            .apply()

        // Clear SyncPrefs so stale sheet ID doesn't persist after sign-out
        getApplication<Application>()
            .getSharedPreferences("SyncPrefs", Context.MODE_PRIVATE)
            .edit()
            .remove("spreadsheet_id")
            .apply()

        _authState.value = AuthState.Idle
    }
}
