package com.simhadri.winentry.ui.settings

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.firestore.FirebaseFirestore
import com.simhadri.winentry.BuildConfig
import com.simhadri.winentry.MainActivity
import com.simhadri.winentry.R
import com.simhadri.winentry.data.AppDatabase
import com.simhadri.winentry.databinding.FragmentBusinessInfoBinding
import com.simhadri.winentry.sync.CloudFunctionClient
import com.simhadri.winentry.sync.SyncCoordinator
import com.simhadri.winentry.ui.auth.AuthViewModel
import com.simhadri.winentry.ui.auth.ErrorLogger
import com.simhadri.winentry.utils.AppDialogs
import com.simhadri.winentry.utils.AppStrings
import com.simhadri.winentry.utils.LangPrefs
import com.simhadri.winentry.utils.UserRegistrationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class BusinessInfoFragment : Fragment() {

    private var _binding: FragmentBusinessInfoBinding? = null
    private val binding get() = _binding!!

    companion object {
        const val PREFS_NAME     = "business_info"
        const val KEY_OWNER_NAME = "owner_name"
        const val KEY_BUSINESS   = "business_name"
        const val KEY_LICENCE    = "licence_no"
        const val KEY_ADDRESS1   = "address1"
        const val KEY_ADDRESS2   = "address2"
        const val KEY_LOCATION   = "location"
        const val KEY_PHONE      = "phone"

        private const val PREFS_DELETION_STATE          = "account_deletion_state"
        private const val KEY_PENDING_AUTH_DELETION      = "pending_auth_deletion"
        private const val KEY_PENDING_AUTH_DELETION_UID  = "pending_auth_deletion_uid"
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
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_delete_account    -> { showDeleteAccountWarning(); true }
                R.id.action_complete_deletion -> { showCompleteAuthDeletion(); true }
                R.id.action_clear_local_data  -> { showClearLocalDataOnly(); true }
                else -> false
            }
        }
        populateFields()
        updateRegistrationUI()
        checkPendingAuthDeletion()
        binding.buttonSave.setOnClickListener { onSaveTapped() }
        setupKeyboardNavigation()

        // Proactive self-heal: if not locally registered, check Firestore.
        // If the doc exists (reinstall / new login), data is restored to prefs and
        // the form is re-populated so the user sees their details without extra steps.
        UserRegistrationManager.tryRestoreFromFirestore(
            context = requireContext(),
            scope   = viewLifecycleOwner.lifecycleScope
        ) {
            if (_binding != null) {
                populateFields()
                updateRegistrationUI()
            }
        }
    }

    private fun setupKeyboardNavigation() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            insets
        }
        // When keyboard appears, expand scrollContent paddingBottom to keyboard height so
        // the Save button stays accessible above the keyboard, then scroll it into view.
        ViewCompat.setOnApplyWindowInsetsListener(binding.scrollContent) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            v.updatePadding(bottom = maxOf(imeBottom, bars.bottom), left = bars.left, right = bars.right)
            if (imeBottom > 0) {
                binding.nestedScrollView.post {
                    binding.nestedScrollView.fullScroll(View.FOCUS_DOWN)
                }
            }
            insets
        }

        // Address field: first Enter moves to line 2; second Enter moves focus to City/Location.
        binding.editAddress1.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                val newlineCount = binding.editAddress1.text?.count { it == '\n' } ?: 0
                if (newlineCount >= 1) {
                    binding.editLocation.requestFocus()
                    return@setOnKeyListener true
                }
            }
            false
        }

        // City/Location: Done action or Enter moves focus to the Save button.
        binding.editLocation.setOnEditorActionListener { _, actionId, event ->
            val isDone = actionId == EditorInfo.IME_ACTION_DONE
            val isEnter = event?.action == KeyEvent.ACTION_DOWN &&
                event.keyCode == KeyEvent.KEYCODE_ENTER
            if (isDone || isEnter) {
                binding.buttonSave.requestFocus()
                true
            } else false
        }
    }

    override fun onResume() {
        super.onResume()
        updateRegistrationUI()
    }

    // ── Registration status chip ───────────────────────────────────────────

    private fun updateRegistrationUI() {
        if (UserRegistrationManager.isRegistered(requireContext())) {
            val date = UserRegistrationManager.registeredDateDisplay(requireContext())
            binding.tvRegistrationStatus.text = "\u2713 Registered  \u00b7  $date"
            binding.buttonSave.text = "Save & Update Registration"
        } else {
            binding.tvRegistrationStatus.text = "\u25cb Not Registered"
            binding.buttonSave.text = "Save & Register"
        }
        updateMenuState()
    }

    private fun updateMenuState() {
        val prefs      = requireContext().getSharedPreferences(PREFS_DELETION_STATE, Context.MODE_PRIVATE)
        val isPending  = prefs.getBoolean(KEY_PENDING_AUTH_DELETION, false)
        val pendingUid = prefs.getString(KEY_PENDING_AUTH_DELETION_UID, null)
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid
        // Pending flag is only valid if it belongs to the currently signed-in account
        val validPending = isPending && (pendingUid == null || pendingUid == currentUid)
        val isRegistered = UserRegistrationManager.isRegistered(requireContext())

        binding.toolbar.menu.findItem(R.id.action_delete_account)?.isVisible    = isRegistered && !validPending
        binding.toolbar.menu.findItem(R.id.action_complete_deletion)?.isVisible = validPending
        binding.toolbar.menu.findItem(R.id.action_clear_local_data)?.isVisible  = !isRegistered && !validPending
    }

    private fun checkPendingAuthDeletion() {
        val prefs      = requireContext().getSharedPreferences(PREFS_DELETION_STATE, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_PENDING_AUTH_DELETION, false)) return
        val user       = FirebaseAuth.getInstance().currentUser ?: return
        val pendingUid = prefs.getString(KEY_PENDING_AUTH_DELETION_UID, null)
        if (pendingUid != null && pendingUid != user.uid) {
            // Stale flag from a different account — clear it
            prefs.edit().remove(KEY_PENDING_AUTH_DELETION).remove(KEY_PENDING_AUTH_DELETION_UID).apply()
            updateMenuState()
            return
        }
        AppDialogs.confirm(
            context     = requireContext(),
            title       = "Account Deletion In Progress",
            message     = "Your cloud data and registration were already removed.\n\n" +
                "One step remains: deleting your sign-in account. " +
                "Tap below to complete, or use the \u22ee menu at any time.",
            actionLabel = "Complete Deletion"
        ) {
            deleteFirebaseAuthOnly()
        }
    }

    private fun showCompleteAuthDeletion() {
        AppDialogs.destructive(
            context     = requireContext(),
            title       = "Complete Account Deletion",
            message     = "Your cloud data and registration were already removed.\n\n" +
                "This step deletes your sign-in account to complete the process.",
            actionLabel = "Delete Sign-In Account"
        ) {
            deleteFirebaseAuthOnly()
        }
    }

    private fun deleteFirebaseAuthOnly() {
        val ctx  = context ?: return
        val user = FirebaseAuth.getInstance().currentUser ?: return
        binding.buttonSave.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                try { user.delete().await(); true }
                catch (e: Exception) {
                    ErrorLogger.log(ctx, "AccountDeletion", "Auth delete (completion) failed", e)
                    false
                }
            }
            if (_binding == null) return@launch
            if (deleted) {
                ctx.getSharedPreferences(PREFS_DELETION_STATE, Context.MODE_PRIVATE)
                    .edit().remove(KEY_PENDING_AUTH_DELETION).remove(KEY_PENDING_AUTH_DELETION_UID).apply()
                askLocalDataDeletion(ctx)
            } else {
                Toast.makeText(ctx,
                    "Sign-in account removal failed \u2014 sign out and sign in again, then retry.",
                    Toast.LENGTH_LONG).show()
                binding.buttonSave.isEnabled = true
            }
        }
    }

    private fun showClearLocalDataOnly() {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val pending = SyncCoordinator(ctx).pendingChangeCount()
            if (_binding == null) return@launch
            if (pending == 0) confirmClearLocalData(ctx)
            else AppDialogs.confirm(
                context     = requireContext(),
                title       = "Unsynced Changes",
                message     = "$pending change(s) on this device have not reached the cloud yet. " +
                    "Clearing local data now loses them.\n\nSync first?",
                actionLabel = "Sync Now",
                onCancel    = { confirmClearLocalDataTyped(ctx, pending) }
            ) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = SyncCoordinator(ctx).performFullSync()
                    val left = SyncCoordinator(ctx).pendingChangeCount()
                    if (_binding == null) return@launch
                    if (result is SyncCoordinator.SyncResult.Success && left == 0) confirmClearLocalData(ctx)
                    else AppDialogs.info(requireContext(), "Sync Incomplete",
                        "$left change(s) are still not synced" +
                            ((result as? SyncCoordinator.SyncResult.Error)?.let { " (${it.message})" } ?: "") +
                            ". Local data was not cleared.")
                }
            }
        }
    }

    private fun confirmClearLocalDataTyped(ctx: Context, pending: Int) {
        if (_binding == null) return
        AppDialogs.withTextInput(
            context      = requireContext(),
            title        = "Discard $pending Unsynced Change(s)?",
            message      = "Type DISCARD to clear local data without syncing. These changes will be lost.",
            actionLabel  = "Clear Local Data",
            requiredText = "DISCARD"
        ) { clearLocalData(ctx) }
    }

    private fun clearLocalData(ctx: Context) {
        viewLifecycleOwner.lifecycleScope.launch {
            SyncCoordinator(ctx).wipeLocalInventory("clear-local")
            if (_binding != null) Toast.makeText(ctx, "Local data cleared.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmClearLocalData(ctx: Context) {
        AppDialogs.destructive(
            context     = requireContext(),
            title       = "Clear Local Data",
            message     = "This will delete all inventory data stored on this device:\n\n" +
                "  \u2022 Daily stock records\n" +
                "  \u2022 Purchase records\n" +
                "  \u2022 Day-end reconciliation records\n\n" +
                "Your exported Excel files are not affected.\n\n" +
                "This cannot be undone.",
            actionLabel = "Clear Local Data"
        ) { clearLocalData(ctx) }
    }

    // ── Field population ───────────────────────────────────────────────────

    private fun populateFields() {
        val lang = LangPrefs.get(requireContext())
        binding.tvBusinessInfoNote.text = AppStrings.businessInfoGuidanceNote.get(lang)

        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        binding.editOwnerName.setText(prefs.getString(KEY_OWNER_NAME, ""))
        binding.editBusinessName.setText(prefs.getString(KEY_BUSINESS, ""))
        binding.editLicenceNo.setText(prefs.getString(KEY_LICENCE, ""))
        binding.editAddress1.setText(prefs.getString(KEY_ADDRESS1, ""))
        binding.editLocation.setText(prefs.getString(KEY_LOCATION, ""))
        binding.editPhone.setText(prefs.getString(KEY_PHONE, ""))
        // Show the Firebase Auth email as read-only — this is the account the app is registered to
        val authEmail = FirebaseAuth.getInstance().currentUser?.email ?: ""
        binding.tvAccountEmail.text = if (authEmail.isNotEmpty()) "Account: $authEmail" else ""
    }

    // ── Save + Registration flow ───────────────────────────────────────────

    private fun onSaveTapped() {
        val businessName = binding.editBusinessName.text.toString().trim()
        if (businessName.isEmpty()) {
            binding.editBusinessName.error = "Business name is required"
            binding.editBusinessName.requestFocus()
            return
        }

        val ownerName = binding.editOwnerName.text.toString().trim()
        val phone     = binding.editPhone.text.toString().trim()
        val location  = binding.editLocation.text.toString().trim()

        if (phone.isNotEmpty() && !phone.matches(Regex("\\d{10}"))) {
            binding.editPhone.error = "Enter a 10-digit phone number"
            binding.editPhone.requestFocus()
            return
        }

        // Always save locally first — local data is used for reports regardless of registration
        requireContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_OWNER_NAME, ownerName)
            .putString(KEY_BUSINESS,   businessName)
            .putString(KEY_LICENCE,    binding.editLicenceNo.text.toString().trim())
            .putString(KEY_ADDRESS1,   binding.editAddress1.text.toString().trim())
            .putString(KEY_ADDRESS2,   "")
            .putString(KEY_LOCATION,   location)
            .putString(KEY_PHONE,      phone)
            .apply()

        if (UserRegistrationManager.isRegistered(requireContext())) {
            AppDialogs.confirm(
                context     = requireContext(),
                title       = "Update Registration",
                message     = "Update your registration details with the admin?\n\n" +
                    "Fields updated: Owner Name, Business Name, Phone, Location, Android version, App version.",
                actionLabel = "Update Registration",
                onCancel    = {
                    Toast.makeText(requireContext(), "Business info saved.", Toast.LENGTH_SHORT).show()
                    if (_binding != null) findNavController().navigateUp()
                }
            ) {
                doRegister(ownerName, businessName, phone, location, forceUpdate = true)
            }
        } else {
            AppDialogs.confirm(
                context     = requireContext(),
                title       = "Register App",
                message     = "Registration allows you to access Admin's drive space for:\n" +
                    "  \u2022 Downloading admin's standard product list\n" +
                    "  \u2022 Loading test data\n" +
                    "  \u2022 Enabling cloud backup\n\n" +
                    "Fields shared: Owner Name, Business Name, Phone, Location, Android version, App version.\n\n" +
                    "No other data will be captured.\n\n" +
                    "You can update these details anytime from this page.\n\n" +
                    "To remove your registration details and cloud data at any time, " +
                    "use the \u22ee menu at the top-right of this page. Your email is retained " +
                    "in an admin audit log \u2014 see Privacy Policy.",
                actionLabel = "Save & Register",
                onCancel    = {
                    Toast.makeText(requireContext(),
                        "Saved. Tap \u2018Save & Register\u2019 anytime to register.",
                        Toast.LENGTH_LONG).show()
                    if (_binding != null) findNavController().navigateUp()
                }
            ) {
                doRegister(ownerName, businessName, phone, location, forceUpdate = false)
            }
        }
    }

    private fun doRegister(
        ownerName: String, businessName: String, phone: String, location: String,
        forceUpdate: Boolean
    ) {
        binding.buttonSave.isEnabled = false

        val prefs        = requireContext().getSharedPreferences(AuthViewModel.PREFS_NAME, Context.MODE_PRIVATE)
        val firebaseUser = FirebaseAuth.getInstance().currentUser
        val uid   = prefs.getString(AuthViewModel.KEY_USER_UID, null) ?: firebaseUser?.uid
        val email = prefs.getString(AuthViewModel.KEY_USER_EMAIL, null) ?: firebaseUser?.email ?: ""

        if (uid.isNullOrBlank()) {
            Toast.makeText(requireContext(),
                "Sign in required. Please sign in and try again.", Toast.LENGTH_LONG).show()
            if (_binding != null) binding.buttonSave.isEnabled = true
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val success = CloudFunctionClient().registerUserOnly(
                uid            = uid,
                email          = email,
                displayName    = firebaseUser?.displayName ?: ownerName,
                ownerName      = ownerName,
                businessName   = businessName,
                phone          = phone,
                location       = location,
                androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                appVersion     = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                forceUpdate    = forceUpdate
            )

            if (_binding == null) return@launch

            if (success) {
                UserRegistrationManager.markRegistered(requireContext(), uid)
                val msg = if (forceUpdate) "Registration updated \u2713" else "Registered \u2713"
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            } else {
                AppDialogs.info(
                    requireContext(),
                    "Registration Failed",
                    "Could not complete registration. Check your internet connection and try again.\n\n" +
                    "Your business info has been saved on this device."
                )
            }
            binding.buttonSave.isEnabled = true
            findNavController().navigateUp()
        }
    }

    // ── Delete Account & Data ──────────────────────────────────────────────────

    private fun showDeleteAccountWarning() {
        val user = FirebaseAuth.getInstance().currentUser ?: run {
            Toast.makeText(requireContext(), "Not signed in.", Toast.LENGTH_SHORT).show()
            return
        }
        AppDialogs.destructive(
            context     = requireContext(),
            title       = "Delete Account & All Data",
            message     = "The following will be permanently removed:\n\n" +
                "  \u2022 Registration details — your name, phone number, business name,\n" +
                "    location and app version are removed from administrator records\n" +
                "  \u2022 All daily stock records in your cloud workspace\n" +
                "  \u2022 All purchase records in your cloud workspace\n" +
                "  \u2022 All day-end reconciliation records in your cloud workspace\n" +
                "  \u2022 Your Firebase sign-in account\n\n" +
                "Your email address is retained in an admin audit log solely to\n" +
                "prevent misuse of the free workspace system. No other personal\n" +
                "data is kept. See the Privacy Policy for full details.\n\n" +
                "Inventory data stored locally on this device will NOT be deleted here —\n" +
                "you will be asked about that separately after this step.\n\n" +
                "This cannot be undone.",
            actionLabel = "Continue \u2192"
        ) {
            showEmailConfirmation(user.email ?: "")
        }
    }

    private fun showEmailConfirmation(expectedEmail: String) {
        AppDialogs.withEmailInput(
            context       = requireContext(),
            title         = "Confirm Your Identity",
            message       = "Enter your sign-in email address to confirm permanent deletion " +
                "of your account and all cloud data.\n\n" +
                "The Delete button activates only when the email matches your account:",
            actionLabel   = "Delete Everything",
            expectedEmail = expectedEmail,
            onConfirm     = { executeAccountDeletion() }
        )
    }

    private fun executeAccountDeletion() {
        val ctx  = context ?: return
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val uid  = user.uid

        binding.buttonSave.isEnabled = false
        Toast.makeText(ctx, "Deleting account and cloud data\u2026", Toast.LENGTH_LONG).show()

        viewLifecycleOwner.lifecycleScope.launch {
            // 1. Clear cloud sheet rows (DailyStock, Purchases, DaySummary)
            val cloudCleared = SyncCoordinator(ctx).deleteAllCloudData()
            if (!cloudCleared) {
                if (_binding != null) {
                    binding.buttonSave.isEnabled = true
                    AppDialogs.info(requireContext(), "Account Not Deleted",
                        "Your cloud data could not be cleared (check your internet connection). " +
                            "Nothing was deleted. Please try again.")
                }
                return@launch
            }

            // 1.5. Soft-delete PII from admin registry sheet rows + log deletion event.
            // Must run before step 4 (Firebase Auth deletion) while the ID token is still valid.
            // Non-fatal: registry cleanup failure does not block the rest of account deletion.
            withContext(Dispatchers.IO) {
                SyncCoordinator(ctx).deleteRegistrationRecord(user.email ?: "")
            }

            // 2. Delete Firestore documents: /users/{uid} and /admin_requests/{uid}
            val firestoreOk = withContext(Dispatchers.IO) {
                val fsDb = FirebaseFirestore.getInstance()
                var allOk = true
                listOf(
                    fsDb.collection("users").document(uid),
                    fsDb.collection("admin_requests").document(uid)
                ).forEach { ref ->
                    try {
                        ref.delete().await()
                    } catch (e: Exception) {
                        ErrorLogger.log(ctx, "AccountDeletion", "Firestore delete failed: ${ref.path}", e)
                        allOk = false
                    }
                }
                allOk
            }

            if (_binding == null) return@launch

            if (!firestoreOk) {
                AppDialogs.confirm(
                    context     = requireContext(),
                    title       = "Account Deletion Incomplete",
                    message     = "Cloud inventory data was cleared, but your registration " +
                        "record could not be removed from the server (permission error).\n\n" +
                        "Please contact the administrator at simhadrisystems@gmail.com " +
                        "to complete removal of your profile record.\n\n" +
                        "You can sign out now or stay signed in.",
                    actionLabel = "Sign Out Now",
                    onCancel    = { binding.buttonSave.isEnabled = true }
                ) {
                    (activity as? MainActivity)?.signOut()
                }
                return@launch
            }

            // 3. Clear all locally cached registration and sync state
            UserRegistrationManager.clearOnSignOut(ctx)
            ctx.getSharedPreferences("business_info", Context.MODE_PRIVATE)
                .edit().clear().apply()
            ctx.getSharedPreferences(AuthViewModel.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(AuthViewModel.KEY_USER_SHEET_ID)
                .remove(AuthViewModel.KEY_USER_EMAIL)
                .remove(AuthViewModel.KEY_USER_UID)
                .remove(AuthViewModel.KEY_USER_ROLE)
                .apply()
            ctx.getSharedPreferences("SyncPrefs", Context.MODE_PRIVATE)
                .edit().clear().apply()

            // 4. Delete the Firebase Auth account (requires recent sign-in)
            val authDeleted = withContext(Dispatchers.IO) {
                try {
                    user.delete().await()
                    true
                } catch (e: FirebaseAuthRecentLoginRequiredException) {
                    false
                } catch (e: Exception) {
                    ErrorLogger.log(ctx, "AccountDeletion", "Auth delete failed", e)
                    false
                }
            }

            if (_binding == null) return@launch

            if (!authDeleted) {
                // Flag that Auth deletion is still pending — survives sign-out so the
                // completion path is offered automatically when the user signs back in.
                ctx.getSharedPreferences(PREFS_DELETION_STATE, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_PENDING_AUTH_DELETION, true)
                    .putString(KEY_PENDING_AUTH_DELETION_UID, uid)
                    .apply()
                AppDialogs.confirm(
                    context     = requireContext(),
                    title       = "Sign In Again to Complete",
                    message     = "Your registration details and all cloud data have been removed.\n\n" +
                        "To finish removing your Firebase sign-in account, sign out now " +
                        "and sign in again — then tap \u2018Complete Account Deletion\u2019 " +
                        "from the \u22ee menu on this page.",
                    actionLabel = "Sign Out Now",
                    onCancel    = { binding.buttonSave.isEnabled = true }
                ) {
                    (activity as? MainActivity)?.signOut()
                }
                return@launch
            }

            // 5. Offer to clear local inventory data, then sign out either way
            askLocalDataDeletion(ctx)
        }
    }

    private fun askLocalDataDeletion(ctx: Context) {
        if (_binding == null) return
        AppDialogs.confirm(
            context     = requireContext(),
            title       = "Clear Local Inventory Data?",
            message     = "Your account has been deleted from the cloud.\n\n" +
                "Do you also want to delete all inventory data stored on this device?\n\n" +
                "This includes all daily stock records, purchases and day-end " +
                "reconciliation data in the app's local database.\n\n" +
                "Your exported Excel files are not affected — they remain in your " +
                "device storage.",
            actionLabel = "Clear Local Data",
            onCancel    = {
                // User keeps local data — still need to sign out
                (activity as? MainActivity)?.signOut()
            }
        ) {
            viewLifecycleOwner.lifecycleScope.launch {
                SyncCoordinator(ctx).wipeLocalInventory("account-deleted")
                (activity as? MainActivity)?.signOut()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
