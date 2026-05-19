package com.simhadri.winentry

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.simhadri.winentry.utils.AppDialogs
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.simhadri.winentry.databinding.ActivityMainBinding
import com.simhadri.winentry.sync.SyncCoordinator
import com.simhadri.winentry.ui.auth.AuthViewModel
import com.simhadri.winentry.ui.auth.ErrorLogger
import com.simhadri.winentry.ui.auth.UserRole
import com.simhadri.winentry.ui.home.HomeFragment
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle     = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        // ── Auth Gate ──────────────────────────────────────────────────────────
        if (auth.currentUser == null) {
            val navGraph = navController.navInflater.inflate(R.navigation.nav_graph)
            navGraph.setStartDestination(R.id.loginFragment)
            navController.setGraph(navGraph, intent.extras)
        }
    }

    // ── Called by HomeFragment → btnSync ──────────────────────────────────────

    /**
     * Triggers a full sync.
     *
     * Viewer role: down-sync only (sheet → local DB, refreshes their view).
     * Editor role: full up+down sync.
     */
    fun performSyncPublic() {
        val coordinator = SyncCoordinator(this)

        if (!coordinator.isUserSheetReady()) {
            // Cloud backup not yet configured — guide the user to Sync Settings.
            // The app works fully offline; sync is optional and set up separately.
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Cloud Sync Not Configured")
                .setMessage(
                    "Cloud backup is not set up yet.\n\n" +
                    "Your data is safely stored on this device.\n\n" +
                    "To enable cloud backup, go to Settings \u2192 Drive Backup " +
                    "and request a workspace from the administrator."
                )
                .setPositiveButton("Open Settings") { _, _ ->
                    // navController is a lateinit field set in onCreate()
                    navController.navigate(R.id.action_home_to_settings)
                }
                .setNegativeButton("Later", null)
                .show()
            return
        }

        if (UserRole.isViewer(this)) {
            // Viewer: down-sync only — refresh local data from sheet, never write up
            lifecycleScope.launch {
                Toast.makeText(this@MainActivity, "Refreshing data…", Toast.LENGTH_SHORT).show()
                when (val result = coordinator.downloadDailyStockFromCloud()) {
                    is SyncCoordinator.SyncResult.DailyStockDownSync ->
                        Toast.makeText(
                            this@MainActivity,
                            "Data refreshed — ${result.count} rows",
                            Toast.LENGTH_LONG
                        ).show()
                    is SyncCoordinator.SyncResult.Error -> {
                        ErrorLogger.log(this@MainActivity, "Sync/Viewer",
                            "Viewer refresh failed: ${result.message}")
                        Toast.makeText(
                            this@MainActivity,
                            "Refresh failed: ${result.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    else -> { }
                }
                refreshHomeSyncStatus()
            }
            return
        }

        // Editor: full up+down sync
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "Sync started…", Toast.LENGTH_SHORT).show()
            when (val result = coordinator.performFullSync()) {
                is SyncCoordinator.SyncResult.Success ->
                    Toast.makeText(
                        this@MainActivity,
                        "Sync complete — ${result.purchasesCount} purchases, ${result.stockCount} stock rows",
                        Toast.LENGTH_LONG
                    ).show()
                is SyncCoordinator.SyncResult.Error ->
                    Toast.makeText(
                        this@MainActivity,
                        "Sync failed: ${result.message}",
                        Toast.LENGTH_LONG
                    ).show()
                else -> { }
            }
            refreshHomeSyncStatus()
        }
    }

    // ── Called by HomeFragment → action_sign_in (only when not signed in) ────

    fun signInToGooglePublic() {
        if (auth.currentUser == null) {
            navigateToLogin()
        }
        // If somehow called while signed in, do nothing — menu should hide this item
    }

    // ── Called by HomeFragment → action_account (signed in, shows info) ───────

    fun showAccountInfoPublic() {
        val user = auth.currentUser ?: return
        val roleLabel = if (UserRole.getRole(this) == UserRole.VIEWER) "View-only" else "Editor"
        AppDialogs.info(this, "Account", "${user.email ?: "Unknown"}\n\nAccount type: $roleLabel")
    }

    // ── Called by HomeFragment → action_sign_out ──────────────────────────────

    fun showSignOutConfirmationPublic() {
        val user = auth.currentUser ?: return
        AppDialogs.destructive(
            context     = this,
            title       = "Sign Out",
            message     = "Signed in as ${user.email ?: "unknown"}.\n\nAre you sure you want to sign out?",
            actionLabel = "Sign Out"
        ) { signOut() }
    }

    // ── Called by HomeFragment → action_sync_settings menu item ──────────────

    fun showSyncSettingsPublic() {
        // Viewer has no sync settings to configure
        if (UserRole.isViewer(this)) {
            Toast.makeText(this, "Sync settings not available in view-only mode.",
                Toast.LENGTH_SHORT).show()
            return
        }

        val coordinator = SyncCoordinator(this)
        val isEnabled   = coordinator.isAutoSyncEnabled()
        val lastSync    = coordinator.getLastSyncTime()

        val lastSyncText = if (lastSync == 0L) "Never synced"
        else "Last sync: ${java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a",
            java.util.Locale.getDefault()).format(java.util.Date(lastSync))}"

        val toggleLabel = if (isEnabled) "Disable Auto-Sync" else "Enable Auto-Sync"

        AppDialogs.toggle(
            context     = this,
            title       = "Sync Settings",
            message     = lastSyncText,
            actionLabel = toggleLabel
        ) {
            coordinator.setAutoSyncEnabled(!isEnabled)
            val msg = if (!isEnabled) "Auto-sync enabled" else "Auto-sync disabled"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    // ── Sign-Out ───────────────────────────────────────────────────────────────

    fun signOut() {
        // Sign out from Google Sign-In so the next login gets a fresh token.
        // Without this, GoogleSignInClient retains a stale cached account after
        // Firebase sign-out, causing DEVELOPER_ERROR on the GMS broker during re-login.
        GoogleSignIn.getClient(this, GoogleSignInOptions.DEFAULT_SIGN_IN).signOut()

        auth.signOut()

        getSharedPreferences(AuthViewModel.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(AuthViewModel.KEY_USER_SHEET_ID)
            .remove(AuthViewModel.KEY_USER_EMAIL)
            .remove(AuthViewModel.KEY_USER_UID)
            .remove(AuthViewModel.KEY_USER_ROLE)
            .apply()

        getSharedPreferences("SyncPrefs", Context.MODE_PRIVATE)
            .edit()
            .remove("spreadsheet_id")
            .apply()

        navigateToLogin()
    }

    /**
     * Navigate to LoginFragment and clear the entire back stack.
     * Uses navigate() with popUpTo instead of setGraph() — more reliable
     * when called from within an active navigation session.
     */
    private fun navigateToLogin() {
        try {
            navController.navigate(
                R.id.loginFragment,
                null,
                androidx.navigation.NavOptions.Builder()
                    .setPopUpTo(R.id.homeFragment, true)
                    .setLaunchSingleTop(true)
                    .build()
            )
        } catch (e: Exception) {
            // If homeFragment is not in back stack (already on login), ignore
            android.util.Log.w("MainActivity", "navigateToLogin: ${e.message}")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * After any sync completes, tell the currently visible HomeFragment to
     * re-check the SYNC_ERROR count so the sync button tint updates immediately
     * without waiting for the next onResume().
     */
    private fun refreshHomeSyncStatus() {
        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
        val current = navHost?.childFragmentManager?.fragments?.firstOrNull()
        (current as? HomeFragment)?.refreshSyncStatus()
    }

    fun getUserSheetId(): String? {
        val prefs = getSharedPreferences(AuthViewModel.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(AuthViewModel.KEY_USER_SHEET_ID, null)
    }

    companion object {
        fun getSpreadsheetId(context: Context): String? {
            val prefs = context.getSharedPreferences(
                AuthViewModel.PREFS_NAME, Context.MODE_PRIVATE
            )
            return prefs.getString(AuthViewModel.KEY_USER_SHEET_ID, null)
        }

    }
}
