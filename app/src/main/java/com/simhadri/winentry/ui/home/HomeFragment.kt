package com.simhadri.winentry.ui.home

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import android.content.Context
import android.content.res.ColorStateList
import androidx.activity.addCallback
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.simhadri.winentry.MainActivity
import com.simhadri.winentry.R
import com.simhadri.winentry.data.AppDatabase
import com.simhadri.winentry.sync.SyncCoordinator
import com.simhadri.winentry.databinding.FragmentHomeBinding
import com.simhadri.winentry.ui.auth.ErrorLogger
import com.simhadri.winentry.utils.AppStrings
import com.simhadri.winentry.utils.LangPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Home screen - Main landing page with module selection
 * Now includes Cloud Sync buttons in header
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    companion object {
        // In-process cache — survives fragment recreation, cleared only when the
        // URL changes (different account) or the process is killed.
        private var cachedPhotoUrl: String? = null
        private var cachedPhotoBitmap: android.graphics.Bitmap? = null
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupSyncButtons()
        setupModuleCards()
        loadBusinessInfo()
        setupBackPressGuard()
        showTestDataHintIfNeeded()
    }

    // ═══════════════════════════════════════════════════════════════
    // TEST DATA HINT — shown once when no daily stock data exists
    // ═══════════════════════════════════════════════════════════════

    private fun showTestDataHintIfNeeded() {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences("inventory_prefs", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("test_data_hint_shown", false)) return
        val db = AppDatabase.getInstance(ctx)
        viewLifecycleOwner.lifecycleScope.launch {
            val hasData = withContext(Dispatchers.IO) {
                db.dailyStockDao().getEarliestCommittedDate() != null
            }
            if (!hasData) {
                if (_binding == null) return@launch
                prefs.edit().putBoolean("test_data_hint_shown", true).apply()
                Snackbar.make(
                    binding.root,
                    "No stock data found. Load sample data: Settings → Help & Support → Import Test Data",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // SYNC BUTTONS - In Home screen header
    // ═══════════════════════════════════════════════════════════════
    
    private fun setupSyncButtons() {
        // Sync button - immediate sync
        binding.btnSync.setOnClickListener {
            (activity as? MainActivity)?.performSyncPublic()
        }
        
        // Menu button - show popup menu
        binding.btnMenu.setOnClickListener { view ->
            showSyncMenu(view)
        }
    }
    
    private fun showSyncMenu(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menuInflater.inflate(R.menu.main_menu, popup.menu)

        // Remove the toolbar sync action — we have a dedicated sync button
        popup.menu.removeItem(R.id.action_sync)

        // Show/hide items based on sign-in state
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null) {
            popup.menu.findItem(R.id.action_sign_in)?.isVisible = false
            popup.menu.findItem(R.id.action_account)?.apply {
                isVisible = true
                // Show the signed-in email as the item label
                title = user.email ?: "My Account"
            }
            popup.menu.findItem(R.id.action_sign_out)?.isVisible = true
        } else {
            popup.menu.findItem(R.id.action_sign_in)?.isVisible = true
            popup.menu.findItem(R.id.action_account)?.isVisible = false
            popup.menu.findItem(R.id.action_sign_out)?.isVisible = false
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_sign_in -> {
                    (activity as? MainActivity)?.signInToGooglePublic()
                    true
                }
                R.id.action_account -> {
                    (activity as? MainActivity)?.showAccountInfoPublic()
                    true
                }
                R.id.action_sign_out -> {
                    (activity as? MainActivity)?.showSignOutConfirmationPublic()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun setupModuleCards() {
        binding.cardDailyStock.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_dailyStock)
        }

        binding.cardPurchases.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_purchases)
        }

        binding.cardReports.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_reports)
        }

        binding.cardProducts.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_products)
        }

        binding.cardSettings.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_settings)
        }
    }

    private fun setupBackPressGuard() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Exit App")
                .setMessage("Are you sure you want to exit?")
                .setPositiveButton("Exit") { _, _ ->
                    requireActivity().finishAffinity()
                }
                .setNegativeButton("Cancel", null)
                .setCancelable(true)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        applyLanguage()
        loadBusinessInfo()
        refreshProfileButton()
        refreshSyncStatus()
    }

    private fun applyLanguage() {
        val lang = LangPrefs.get(requireContext())
        val te = lang == AppStrings.Lang.TE
        val titleSp = if (te) 19f else 17f
        val descSp  = if (te) 11f else 13f

        binding.textWelcome.text         = AppStrings.homeWelcome.get(lang)
        binding.textDailyStockTitle.text = AppStrings.homeDailyStockTitle.get(lang)
        binding.textDailyStockDesc.text  = AppStrings.homeDailyStockDesc.get(lang)
        binding.textPurchasesTitle.text  = AppStrings.homePurchasesTitle.get(lang)
        binding.textPurchasesDesc.text   = AppStrings.homePurchasesDesc.get(lang)
        binding.textReportsTitle.text    = AppStrings.homeReportsTitle.get(lang)
        binding.textReportsDesc.text     = AppStrings.homeReportsDesc.get(lang)
        binding.textProductsTitle.text   = AppStrings.homeProductsTitle.get(lang)
        binding.textProductsDesc.text    = AppStrings.homeProductsDesc.get(lang)
        binding.textSettingsTitle.text   = AppStrings.homeSettingsTitle.get(lang)
        binding.textSettingsDesc.text    = AppStrings.homeSettingsDesc.get(lang)

        for (v in listOf(binding.textDailyStockTitle, binding.textPurchasesTitle,
                         binding.textReportsTitle, binding.textProductsTitle, binding.textSettingsTitle))
            v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, titleSp)
        for (v in listOf(binding.textDailyStockDesc, binding.textPurchasesDesc,
                         binding.textReportsDesc, binding.textProductsDesc, binding.textSettingsDesc))
            v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, descSp)
    }

    /**
     * Check for any SYNC_ERROR rows or logged errors and update the sync
     * button appearance accordingly.
     * Called on resume AND by MainActivity after each manual sync completes.
     */
    fun refreshSyncStatus() {
        if (_binding == null) return
        lifecycleScope.launch {
            // ── Check 1: cloud backup not configured ────────────────────────
            val syncReady = SyncCoordinator(requireContext())
                .isUserSheetReady()
            if (!syncReady) {
                if (_binding == null) return@launch
                // Amber tint to signal "action needed" without being alarming
                binding.btnSync.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.status_warning)
                )
                binding.btnSync.contentDescription =
                    "Cloud backup not configured — tap for setup options"
                return@launch
            }

            // ── Check 2: any rows failed to sync ────────────────────────────
            val db = AppDatabase.getInstance(requireContext())
            val errorCount = withContext(Dispatchers.IO) {
                db.purchaseDao().getSyncErrorCount() +
                db.dailyStockDao().getSyncErrorCount() +
                db.dayReconciliationDao().getSyncErrorCount()
            }
            val hasLoggedErrors = ErrorLogger.hasErrors(requireContext())

            if (_binding == null) return@launch   // fragment may have been destroyed
            if (errorCount > 0 || hasLoggedErrors) {
                // Tint sync button red — makes it obvious something needs attention
                binding.btnSync.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), R.color.status_error)
                )
                binding.btnSync.contentDescription =
                    "$errorCount record(s) failed to sync — tap to retry"
            } else {
                // Restore normal white tint — all good
                binding.btnSync.imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(requireContext(), android.R.color.white)
                )
                binding.btnSync.contentDescription = "Sync with cloud"
            }
        }
    }

    /**
     * Updates the toolbar profile button:
     * - Signed in with Google photo → load and display the circular profile picture
     * - Signed in without photo / not signed in → show default person icon
     */
    private fun refreshProfileButton() {
        val photoUrl = FirebaseAuth.getInstance().currentUser?.photoUrl?.toString()
        if (photoUrl == null) {
            resetToPersonIcon()
            return
        }
        // Serve from in-process cache if URL hasn't changed — no network call needed.
        if (photoUrl == cachedPhotoUrl && cachedPhotoBitmap != null) {
            binding.btnMenu.setPadding(0, 0, 0, 0)
            binding.btnMenu.setImageBitmap(cachedPhotoBitmap)
            return
        }
        // URL is new or cache is empty — fetch once and cache.
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val conn = URL(photoUrl).openConnection() as HttpURLConnection
                    conn.doInput = true
                    conn.connect()
                    BitmapFactory.decodeStream(conn.inputStream)
                } catch (_: Exception) {
                    null
                }
            }
            if (isAdded) {
                if (bitmap != null) {
                    cachedPhotoUrl = photoUrl
                    cachedPhotoBitmap = bitmap
                    binding.btnMenu.setPadding(0, 0, 0, 0)
                    binding.btnMenu.setImageBitmap(bitmap)
                } else {
                    resetToPersonIcon()
                }
            }
        }
    }

    private fun resetToPersonIcon() {
        val p = (6 * resources.displayMetrics.density + 0.5f).toInt()
        binding.btnMenu.setPadding(p, p, p, p)
        binding.btnMenu.setImageResource(R.drawable.ic_person)
    }

    private fun loadBusinessInfo() {
        val prefs = requireContext().getSharedPreferences(
            "business_info", Context.MODE_PRIVATE
        )
        val bizName = prefs.getString("business_name", "").orEmpty()
        val location = prefs.getString("location", "").orEmpty()

        if (bizName.isNotEmpty()) {
            binding.textBusinessName.text = bizName
            binding.textBusinessName.visibility = android.view.View.VISIBLE
        } else {
            binding.textBusinessName.visibility = android.view.View.GONE
        }

        if (location.isNotEmpty()) {
            binding.textBusinessLocation.text = location
            binding.textBusinessLocation.visibility = android.view.View.VISIBLE
        } else {
            binding.textBusinessLocation.visibility = android.view.View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
