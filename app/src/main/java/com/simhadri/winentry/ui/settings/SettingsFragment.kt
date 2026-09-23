package com.simhadri.winentry.ui.settings

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Build
import androidx.core.content.ContextCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.simhadri.winentry.ui.update.AppUpdateManager
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.simhadri.winentry.BuildConfig
import com.simhadri.winentry.MainActivity
import com.simhadri.winentry.R
import com.simhadri.winentry.databinding.FragmentSettingsBinding
import com.simhadri.winentry.sync.CloudFunctionClient
import com.simhadri.winentry.sync.CreateSheetResult
import com.simhadri.winentry.sync.CloudSyncManager
import com.simhadri.winentry.sync.SyncCoordinator
import com.simhadri.winentry.ui.auth.AuthViewModel
import com.simhadri.winentry.ui.auth.ErrorLogger
import com.simhadri.winentry.utils.AppDialogs
import com.simhadri.winentry.utils.AppStrings
import com.simhadri.winentry.utils.LangPrefs
import com.simhadri.winentry.utils.SupportHelper
import com.simhadri.winentry.utils.UserRegistrationManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val updateLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == com.google.android.play.core.install.model.ActivityResult.RESULT_IN_APP_UPDATE_FAILED) {
            Toast.makeText(requireContext(), "Update failed — opening Play Store.", Toast.LENGTH_SHORT).show()
            AppUpdateManager(requireActivity()).openPlayStore()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.scrollView) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.cardLanguage.setOnClickListener {
            val names = AppStrings.Lang.entries.map { it.displayName }.toTypedArray()
            AppDialogs.choice(requireContext(), "Language / భాష", names) { which ->
                val selected = AppStrings.Lang.entries[which]
                LangPrefs.set(requireContext(), selected)
                applyLanguage()
            }
        }

        binding.cardBusinessInfo.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_businessInfo)
        }

        // Sync Settings — only meaningful once Drive Backup is active
        binding.cardSyncSettings.setOnClickListener {
            if (SyncCoordinator(requireContext()).isUserSheetReady()) {
                (activity as? MainActivity)?.showSyncSettingsPublic()
            } else {
                AppDialogs.info(
                    requireContext(),
                    "Drive Backup Required",
                    "Enable Cloud Drive Backup first (CLOUD BACKUP section above) before configuring sync settings."
                )
            }
        }

        binding.cardHelpSupport.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_helpSupport)
        }

        // Drive Backup card — all workspace request/status/deactivation logic lives here
        binding.cardMyDrive.setOnClickListener {
            showDriveBackupOptions()
        }

        binding.tvAppVersion.text = "v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"

        binding.btnCheckUpdate.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val msg = when (AppUpdateManager(requireActivity()).checkForUpdatesManually(updateLauncher)) {
                    AppUpdateManager.ManualResult.UP_TO_DATE    -> "You're up to date."
                    AppUpdateManager.ManualResult.SKIPPED_DEBUG -> "Update check is disabled in debug builds."
                    else -> null
                }
                msg?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show() }
            }
        }

        // ── Error Log ─────────────────────────────────────────────────────
        // Show share button only when errors exist — hidden otherwise
        updateErrorLogButton()

        binding.btnShareErrorLog.setOnClickListener {
            ErrorLogger.share(requireContext())
        }

        binding.btnClearErrorLog.setOnClickListener {
            ErrorLogger.clear(requireContext())
            updateErrorLogButton()
        }
    }

    override fun onResume() {
        super.onResume()
        applyLanguage()
        refreshDriveBackupStatus()
        refreshSyncCardStatus()
    }

    // ── CLOUD BACKUP card ─────────────────────────────────────────────────────

    /**
     * Updates the Drive Backup card title and description to reflect the current state:
     *   Active   → "Drive Backup Active"   / sync target info
     *   Pending  → "Drive Backup Pending"  / reminder prompt
     *   Inactive → "Request Drive Backup"  / invite to request
     */
    private fun refreshDriveBackupStatus() {
        val sync = SyncCoordinator(requireContext())
        val lang = LangPrefs.get(requireContext())
        when {
            sync.isUserSheetReady() -> {
                binding.textMyDriveTitle.text = AppStrings.settingsDriveBackupActiveTitle.get(lang)
                binding.textMyDriveDesc.text  = AppStrings.settingsDriveBackupActiveDesc.get(lang)
                updateDriveCardVisuals(DriveState.ACTIVE)
                // One-time registry refresh: ensures processedAt/role/sheetUrl are updated
                // for users whose sheet was activated before the full registry write was in place.
                val prefs = requireContext()
                    .getSharedPreferences(AuthViewModel.PREFS_NAME, Context.MODE_PRIVATE)
                if (!prefs.getBoolean("registry_refreshed_v2", false)) {
                    val uid = prefs.getString(AuthViewModel.KEY_USER_UID, null)
                    if (!uid.isNullOrBlank()) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            val refreshed = activateSheetFromFirestoreIfReady(uid, sync)
                            if (refreshed) {
                                prefs.edit().putBoolean("registry_refreshed_v2", true).apply()
                            }
                        }
                    }
                }
            }
            sync.isWorkspaceRequested() -> {
                val prefs = requireContext()
                    .getSharedPreferences(AuthViewModel.PREFS_NAME, Context.MODE_PRIVATE)
                val sheetIdMissing = prefs.getBoolean(AuthViewModel.KEY_SHEET_ID_MISSING, false)
                if (sheetIdMissing) {
                    binding.textMyDriveTitle.text = "Workspace Setup Incomplete"
                    binding.textMyDriveDesc.text  =
                        "Sheet not linked yet. Tap here to retry automatic setup."
                } else {
                    binding.textMyDriveTitle.text = AppStrings.settingsDriveBackupPendingTitle.get(lang)
                    binding.textMyDriveDesc.text  = AppStrings.settingsDriveBackupPendingDesc.get(lang)
                }
                updateDriveCardVisuals(DriveState.PENDING)
                // Also check Firestore in case the sheet was created after the request
                val uid = prefs.getString(AuthViewModel.KEY_USER_UID, null)
                if (!uid.isNullOrBlank()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        activateSheetFromFirestoreIfReady(uid, sync)
                    }
                }
            }
            else -> {
                binding.textMyDriveTitle.text = AppStrings.settingsDriveBackupRequestTitle.get(lang)
                binding.textMyDriveDesc.text  = AppStrings.settingsDriveBackupRequestDesc.get(lang)
                updateDriveCardVisuals(DriveState.INACTIVE)
            }
        }
    }

    private enum class DriveState { INACTIVE, PENDING, ACTIVE }

    private fun updateDriveCardVisuals(state: DriveState) {
        val bgRes = when (state) {
            DriveState.ACTIVE   -> R.color.settings_download_light
            DriveState.PENDING  -> R.color.settings_test_data_light
            DriveState.INACTIVE -> R.color.module_settings_light
        }
        val accentRes = when (state) {
            DriveState.ACTIVE   -> R.color.settings_download
            DriveState.PENDING  -> R.color.settings_test_data
            DriveState.INACTIVE -> R.color.module_settings
        }
        val bg     = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), bgRes))
        val accent = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), accentRes))
        binding.cardMyDrive.setCardBackgroundColor(bg)
        binding.driveBackupIconContainer.backgroundTintList = bg
        binding.iconDriveBackup.imageTintList               = accent
        binding.driveBackupChevron.imageTintList            = accent
    }

    /**
     * Handles Drive Backup card tap — three different dialogs based on state.
     */
    private fun showDriveBackupOptions() {
        val sync = SyncCoordinator(requireContext())
        when {
            sync.isUserSheetReady() -> showBackupActiveOptions()
            sync.isWorkspaceRequested() -> {
                val sheetIdMissing = requireContext()
                    .getSharedPreferences(AuthViewModel.PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(AuthViewModel.KEY_SHEET_ID_MISSING, false)
                if (sheetIdMissing) showSheetIdMissingDialog() else showReminderDialog()
            }
            else -> UserRegistrationManager.ensureRegistered(
                context = requireContext(),
                scope   = viewLifecycleOwner.lifecycleScope,
                onNotRegistered = {
                    AppDialogs.confirm(
                        context     = requireContext(),
                        title       = "Registration Required",
                        message     = "App registration required to access Admin's drive space.\n\n" +
                            "Go to Business Info to register.",
                        actionLabel = "Go to Business Info"
                    ) { findNavController().navigate(R.id.action_settings_to_businessInfo) }
                },
                onReady = { showConsentThenRequest() }
            )
        }
    }

    /** When backup is active — show status and offer to deactivate. */
    private fun showBackupActiveOptions() {
        AppDialogs.toggle(
            context     = requireContext(),
            title       = "Drive Backup Active",
            message     = "Your inventory data is syncing to the admin-managed Google workspace.\n\n" +
                "Tap 'Deactivate' to stop cloud sync. " +
                "Your local data and Excel exports will continue to work.",
            actionLabel = "Deactivate"
        ) {
            val sync = SyncCoordinator(requireContext())
            sync.clearSpreadsheetId()
            sync.setWorkspaceRequested(false)
            // Also clear cached sheetId from inventory_prefs so login fast-path doesn't restore it
            requireContext()
                .getSharedPreferences(AuthViewModel.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(AuthViewModel.KEY_USER_SHEET_ID).apply()
            refreshDriveBackupStatus()
            refreshSyncCardStatus()
            Toast.makeText(requireContext(),
                "Cloud backup deactivated. Local data is unaffected.",
                Toast.LENGTH_LONG).show()
        }
    }

    /** When request is pending — offer to email the admin directly with pre-filled details. */
    private fun showReminderDialog() {
        val bizPrefs = requireContext().getSharedPreferences(
            BusinessInfoFragment.PREFS_NAME, Context.MODE_PRIVATE
        )
        val ownerName    = bizPrefs.getString(BusinessInfoFragment.KEY_OWNER_NAME, "").orEmpty()
        val businessName = bizPrefs.getString(BusinessInfoFragment.KEY_BUSINESS, "").orEmpty()
        val phone        = bizPrefs.getString(BusinessInfoFragment.KEY_PHONE, "").orEmpty()
        val location     = bizPrefs.getString(BusinessInfoFragment.KEY_LOCATION, "").orEmpty()
        val userEmail    = requireContext()
            .getSharedPreferences(AuthViewModel.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(AuthViewModel.KEY_USER_EMAIL, "").orEmpty()

        AppDialogs.toggle(
            context     = requireContext(),
            title       = "Drive Backup Pending",
            message     = "Your workspace request has been received by the system.\n\n" +
                "Cloud sync activates only after the admin links your account to a Google Sheet. " +
                "The admin needs to add your email to the approved list.\n\n" +
                "Tap 'Email Admin' to send your registration details directly and request activation.",
            actionLabel = "Email Admin"
        ) {
            SupportHelper.sendWorkspaceReminderEmail(
                context      = requireContext(),
                ownerName    = ownerName,
                businessName = businessName,
                phone        = phone,
                location     = location,
                userEmail    = userEmail
            )
        }
    }

    private fun showSheetIdMissingDialog() {
        val bizPrefs = requireContext().getSharedPreferences(
            BusinessInfoFragment.PREFS_NAME, Context.MODE_PRIVATE
        )
        val ownerName    = bizPrefs.getString(BusinessInfoFragment.KEY_OWNER_NAME, "").orEmpty()
        val businessName = bizPrefs.getString(BusinessInfoFragment.KEY_BUSINESS, "").orEmpty()
        val phone        = bizPrefs.getString(BusinessInfoFragment.KEY_PHONE, "").orEmpty()
        val location     = bizPrefs.getString(BusinessInfoFragment.KEY_LOCATION, "").orEmpty()

        AppDialogs.confirm(
            context     = requireContext(),
            title       = "Workspace Setup Incomplete",
            message     = "Your account was found but the cloud workspace sheet has not been " +
                "set up yet.\n\n" +
                "Tap 'Retry Setup' to attempt automatic setup now. " +
                "If the problem persists, contact the administrator at simhadrisystems@gmail.com.\n\n" +
                "Your local data and Excel exports continue to work normally.",
            actionLabel = "Retry Setup"
        ) {
            submitWorkspaceRequest(ownerName, businessName, phone, location)
        }
    }

    /**
     * First-time request path — shows consent + indemnity dialog before proceeding.
     * Consent is shown every time (not a one-time flag) because it contains the
     * indemnity terms the user must explicitly accept before each new activation.
     * Registration check is done before calling this method.
     */
    private fun showConsentThenRequest() {
        val bizPrefs = requireContext().getSharedPreferences(
            BusinessInfoFragment.PREFS_NAME, Context.MODE_PRIVATE
        )
        val ownerName    = bizPrefs.getString(BusinessInfoFragment.KEY_OWNER_NAME, "").orEmpty()
        val businessName = bizPrefs.getString(BusinessInfoFragment.KEY_BUSINESS, "").orEmpty()
        val phone        = bizPrefs.getString(BusinessInfoFragment.KEY_PHONE, "").orEmpty()
        val location     = bizPrefs.getString(BusinessInfoFragment.KEY_LOCATION, "").orEmpty()

        AppDialogs.confirm(
            context     = requireContext(),
            title       = "Enable Cloud Drive Backup",
            message     = "What this does\n" +
                "A private Google Sheet will be created in the admin-managed Google Drive " +
                "exclusively for your inventory data. This sheet is not visible in your " +
                "personal Google Drive. No other user\u2019s data is stored in your sheet.\n\n" +
                "What data is stored in the cloud\n" +
                "Your daily stock entries, purchases, and day-end summaries will sync to this sheet. " +
                "Your business registration details (name, owner, phone, location) " +
                "are already held by the administrator from your Business Info registration.\n\n" +
                "Administrator access\n" +
                "The administrator has read access to all records synced to this sheet. " +
                "This is the basis on which cloud backup is offered as a facility.\n\n" +
                "Automatic background sync\n" +
                "Once active, the app syncs your data automatically every 6 hours. " +
                "You can also trigger a manual sync at any time using the Sync button on the Home screen.\n\n" +
                "This is optional\n" +
                "Cloud backup is an additional facility alongside local storage and Excel exports. " +
                "You can deactivate it at any time by tapping the Drive Backup card on this screen. " +
                "Your local data and Excel exports are never affected.\n\n" +
                "Limitation of liability\n" +
                "Cloud sync depends on internet connectivity and third-party services. " +
                "Data loss due to service outages or sync errors is possible. " +
                "Always maintain local backups via Excel export.\n\n" +
                "By tapping \u2018I Agree \u2014 Enable Backup\u2019 you confirm you have read " +
                "and understood the above.",
            actionLabel = "I Agree — Enable Backup"
        ) {
            submitWorkspaceRequest(ownerName, businessName, phone, location)
        }
    }

    // ── Sync card status + workspace request ──────────────────────────────────

    /**
     * Updates the Sync Settings card description:
     *   - Backup active  → normal "Auto-sync / last-sync" label set by applyLanguage()
     *   - Backup inactive → dim label prompting to enable Drive Backup first
     */
    private fun refreshSyncCardStatus() {
        val sync = SyncCoordinator(requireContext())
        if (!sync.isUserSheetReady()) {
            binding.textSyncSettingsDesc.text = "Enable Drive Backup above to configure"
        }
        // If sheet is ready, applyLanguage() already set the correct description
    }

    /**
     * Checks Firestore /users/{uid} for a userSheetId.
     * If found, activates sync locally and refreshes the card to the normal state.
     * Returns true if the sheet was activated, false otherwise.
     */
    private suspend fun activateSheetFromFirestoreIfReady(
        uid: String,
        sync: SyncCoordinator
    ): Boolean {
        return try {
            val doc = FirebaseFirestore.getInstance()
                .collection("users").document(uid).get().await()
            val sheetId = doc.getString("userSheetId")
            if (!sheetId.isNullOrBlank()) {
                // Sheet exists in Firestore — activate locally and clear any error flags
                requireContext()
                    .getSharedPreferences(AuthViewModel.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(AuthViewModel.KEY_USER_SHEET_ID, sheetId)
                    .remove(AuthViewModel.KEY_SHEET_ID_MISSING)
                    .apply()
                sync.setSpreadsheetId(sheetId)
                if (_binding != null) refreshDriveBackupStatus()

                // Registry is written by the Cloud Function (service account) — not here.
                true
            } else {
                if (_binding != null) {
                    binding.textSyncSettingsDesc.text =
                        "Workspace request submitted — awaiting admin"
                }
                false
            }
        } catch (e: Exception) {
            if (_binding != null) {
                binding.textSyncSettingsDesc.text =
                    "Workspace request submitted — awaiting admin"
            }
            false
        }
    }

    /**
     * Submits the workspace request:
     *  1. Writes /admin_requests/{uid} to Firestore with status "awaiting_sheet"
     *  2. If ADMIN_USER_REGISTRY_SPREADSHEET_ID is configured, appends a row there too
     *  3. Marks workspace_requested=true so the card and dialog reflect sent state
     *  4. Shows Email / WhatsApp choice so the user can also notify the admin directly
     */
    private fun submitWorkspaceRequest(
        ownerName: String, businessName: String, phone: String, location: String
    ) {
        val prefs = requireContext()
            .getSharedPreferences(AuthViewModel.PREFS_NAME, Context.MODE_PRIVATE)

        // Prefer cached prefs; fall back to live Firebase Auth if prefs not yet written
        val firebaseUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        val uid   = prefs.getString(AuthViewModel.KEY_USER_UID,   null)
            ?: firebaseUser?.uid
        val email = prefs.getString(AuthViewModel.KEY_USER_EMAIL, null)
            ?: firebaseUser?.email

        val sync = SyncCoordinator(requireContext())

        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        fun com.google.firebase.Timestamp.fmt(): String = sdf.format(this.toDate())

        viewLifecycleOwner.lifecycleScope.launch {
            if (uid.isNullOrBlank() || email.isNullOrBlank()) {
                Toast.makeText(requireContext(),
                    "Sign in required. Please sign in and try again.",
                    Toast.LENGTH_LONG).show()
                return@launch
            }

            // ── 0. Check Firestore first — sheet may already exist ───────────
            // Handles: reinstall, app data cleared, or trigger already ran after
            // a previous request.  Avoids creating a duplicate sheet.
            val existingDoc = try {
                FirebaseFirestore.getInstance()
                    .collection("users").document(uid).get().await()
            } catch (_: Exception) { null }

            val existingSheetId = existingDoc?.getString("userSheetId")

            // View may have been destroyed while the Firestore call was in flight
            // (e.g. user backed out of Settings) — requireContext()/binding below
            // would throw IllegalStateException on a detached fragment.
            if (_binding == null) return@launch

            if (!existingSheetId.isNullOrBlank()) {
                requireContext()
                    .getSharedPreferences(AuthViewModel.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putString(AuthViewModel.KEY_USER_SHEET_ID, existingSheetId).apply()
                sync.setSpreadsheetId(existingSheetId)
                sync.setWorkspaceRequested(true)
                refreshDriveBackupStatus()
                refreshSyncCardStatus()

                // Registry is written by the Cloud Function (service account) — not here.
                Toast.makeText(requireContext(),
                    "Workspace already active — sync enabled!",
                    Toast.LENGTH_LONG).show()
                return@launch
            }

            // ── 1. Call Cloud Function — creates sheet in admin's workspace ──
            // The Cloud Function uses a Service Account to create the sheet in the
            // admin's Google Drive, then writes /users/{uid} to Firestore with the
            // new sheetId.  Sheet is never created in the user's own Drive here.
            Toast.makeText(requireContext(), "Requesting workspace…", Toast.LENGTH_SHORT).show()

            val displayName = firebaseUser?.displayName ?: ownerName
            val cfResult = CloudFunctionClient().createUserSheet(
                uid            = uid,
                email          = email,
                displayName    = displayName,
                ownerName      = ownerName,
                businessName   = businessName,
                phone          = phone,
                location       = location,
                androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                appVersion     = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            )

            // View may have been destroyed while the Cloud Function call was in
            // flight — guard before touching requireContext()/binding below.
            if (_binding == null) return@launch

            when (cfResult) {
                is CreateSheetResult.Success -> {
                    val sheetInfo = cfResult.sheetInfo
                    requireContext()
                        .getSharedPreferences(AuthViewModel.PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(AuthViewModel.KEY_USER_SHEET_ID, sheetInfo.sheetId)
                        .apply()
                    sync.setSpreadsheetId(sheetInfo.sheetId)
                    sync.setWorkspaceRequested(true)
                    refreshDriveBackupStatus()
                    refreshSyncCardStatus()
                    Toast.makeText(requireContext(),
                        "Workspace ready — cloud sync is now active!",
                        Toast.LENGTH_LONG).show()
                    return@launch
                }
                is CreateSheetResult.NotInvited -> {
                    // Admin has been notified — user waits for approval
                    sync.setWorkspaceRequested(true)
                    refreshDriveBackupStatus()
                    refreshSyncCardStatus()
                    Toast.makeText(requireContext(),
                        "Request submitted — admin will set up your workspace shortly.",
                        Toast.LENGTH_LONG).show()
                    return@launch
                }
                is CreateSheetResult.Error -> {
                    // Fall through to admin_requests Firestore write below
                    android.util.Log.w("SettingsFragment",
                        "Cloud Function unavailable — falling back to admin_requests")
                }
            }

            // ── 2. Cloud Function error — send a pending request ─────────────
            // Admin will create the sheet manually and write /users/{uid} to
            // Firestore.  The app picks it up on next login via fetchSheetIdInBackground.
            android.util.Log.w("SettingsFragment",
                "Cloud Function unavailable — falling back to admin_requests")

            var firestoreOk = false
            try {
                FirebaseFirestore.getInstance()
                    .collection("admin_requests").document(uid)
                    .set(mapOf(
                        "uid"          to uid,
                        "email"        to email,
                        "ownerName"    to ownerName,
                        "businessName" to businessName,
                        "phone"        to phone,
                        "location"     to location,
                        "registeredAt"  to Timestamp.now(),
                        "status"        to "awaiting_sheet",
                        "androidVersion" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                        "appVersion"    to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                    ))
                    .await()
                firestoreOk = true
            } catch (e: Exception) {
                android.util.Log.w("SettingsFragment",
                    "Firestore admin_requests write failed: ${e.message}")
            }

            // View may have been destroyed while the Firestore write was in
            // flight — guard before touching requireContext()/binding below.
            if (_binding == null) return@launch

            if (firestoreOk) {
                // Registry update skipped here — the onAdminRequestCreated Cloud Function
                // writes to both AppRequests and UserRegistry tabs via service account,
                // which works for all users regardless of sheet permissions.
                sync.setWorkspaceRequested(true)
                refreshDriveBackupStatus()
                refreshSyncCardStatus()
                Toast.makeText(requireContext(),
                    "Request sent — your workspace will be set up by admin shortly.",
                    Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(requireContext(),
                    "Could not send request. Check your connection and try again.",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun applyLanguage() {
        val lang = LangPrefs.get(requireContext())
        val te = lang == AppStrings.Lang.TE
        val titleSp = if (te) 19f else 17f
        val descSp  = if (te) 11f else 13f

        binding.toolbar.title                  = AppStrings.settingsToolbarTitle.get(lang)
        binding.textLanguageTitle.text         = AppStrings.settingsLanguageTitle.get(lang)
        binding.textLanguageDesc.text          = AppStrings.settingsLanguageDesc.get(lang)
        binding.textBusinessInfoTitle.text     = AppStrings.settingsBusinessInfoTitle.get(lang)
        binding.textBusinessInfoDesc.text      = AppStrings.settingsBusinessInfoDesc.get(lang)
        binding.textSyncSettingsTitle.text     = AppStrings.settingsSyncTitle.get(lang)
        binding.textSyncSettingsDesc.text      = AppStrings.settingsSyncDesc.get(lang)
        binding.textHelpSupportTitle.text      = AppStrings.settingsHelpSupportTitle.get(lang)
        binding.textHelpSupportDesc.text       = AppStrings.settingsHelpSupportDesc.get(lang)

        // Drive Backup card text depends on both language AND current backup state
        val sync = SyncCoordinator(requireContext())
        when {
            sync.isUserSheetReady() -> {
                binding.textMyDriveTitle.text = AppStrings.settingsDriveBackupActiveTitle.get(lang)
                binding.textMyDriveDesc.text  = AppStrings.settingsDriveBackupActiveDesc.get(lang)
            }
            sync.isWorkspaceRequested() -> {
                binding.textMyDriveTitle.text = AppStrings.settingsDriveBackupPendingTitle.get(lang)
                binding.textMyDriveDesc.text  = AppStrings.settingsDriveBackupPendingDesc.get(lang)
            }
            else -> {
                binding.textMyDriveTitle.text = AppStrings.settingsDriveBackupRequestTitle.get(lang)
                binding.textMyDriveDesc.text  = AppStrings.settingsDriveBackupRequestDesc.get(lang)
            }
        }

        for (v in listOf(binding.textLanguageTitle, binding.textBusinessInfoTitle,
                         binding.textSyncSettingsTitle, binding.textHelpSupportTitle, binding.textMyDriveTitle))
            v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, titleSp)
        for (v in listOf(binding.textLanguageDesc, binding.textBusinessInfoDesc,
                         binding.textSyncSettingsDesc, binding.textHelpSupportDesc, binding.textMyDriveDesc))
            v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, descSp)
    }

    private fun updateErrorLogButton() {
        val hasErrors = ErrorLogger.hasErrors(requireContext())
        binding.btnShareErrorLog.visibility = if (hasErrors) View.VISIBLE else View.GONE
        binding.btnClearErrorLog.visibility = if (hasErrors) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
