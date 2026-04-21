package com.simhadri.winentry.ui.auth

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.simhadri.winentry.R
import com.simhadri.winentry.databinding.FragmentLoginBinding
import com.simhadri.winentry.utils.AppDialogs

class LoginFragment : Fragment() {

    companion object {
        /**
         * Stored in inventory_prefs (same file as auth prefs).
         * Set to true once the user has acknowledged the data-access consent
         * dialog.  Not cleared on sign-out — the consent is device-level and
         * covers the app's data policy, not a specific account.
         */
        private const val KEY_DATA_ACCESS_CONSENTED = "data_access_consented"
    }

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    private val authViewModel: AuthViewModel by viewModels()
    private lateinit var auth: FirebaseAuth

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Always try to extract the account — this gives us the actual error code
        // even when resultCode is RESULT_CANCELED (e.g. status 10 = DEVELOPER_ERROR
        // means SHA-1 or package name not registered in Firebase Console).
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            account.idToken?.let { idToken ->
                firebaseAuthWithGoogle(idToken)
            } ?: showError("Sign-in failed: no ID token")
        } catch (e: ApiException) {
            binding.btnSignIn.isEnabled = true
            binding.progressBar.visibility = View.GONE
            val reason = when (e.statusCode) {
                10   -> "OAuth config error (status 10) — SHA-1 or package not registered in Firebase"
                12500 -> "Sign-in failed (12500)"
                12501 -> "Sign-in cancelled by user (12501)"
                else -> "Google sign-in failed: status ${e.statusCode}"
            }
            showError(reason)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        auth = FirebaseAuth.getInstance()

        // If already signed in, navigate away
        if (auth.currentUser != null) {
            authViewModel.handleSignedInUser(auth.currentUser!!)
        }

        binding.btnSignIn.setOnClickListener {
            startGoogleSignIn()
        }

        // Underline the privacy policy link so it reads as a tappable link.
        binding.tvPrivacyPolicy.paintFlags =
            binding.tvPrivacyPolicy.paintFlags or Paint.UNDERLINE_TEXT_FLAG
        binding.tvPrivacyPolicy.setOnClickListener {
            val url = getString(R.string.privacy_policy_url)
            if (url.startsWith("http")) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }

        observeAuthState()
    }

    /**
     * Entry point for the sign-in button.
     *
     * On the first attempt, shows a one-time data-access consent dialog that
     * explains exactly which Google Sheets the app accesses and which it does
     * not.  This satisfies Google's OAuth verification requirement for
     * sensitive-scope disclosure before the OAuth consent screen is shown.
     *
     * On subsequent attempts the dialog is skipped and OAuth launches directly.
     */
    private fun startGoogleSignIn() {
        val prefs = requireContext()
            .getSharedPreferences(AuthViewModel.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_DATA_ACCESS_CONSENTED, false)) {
            showDataAccessConsentDialog(prefs)
        } else {
            launchGoogleSignIn()
        }
    }

    /**
     * Shows the data-access disclosure dialog.  On acceptance the consent flag
     * is saved and OAuth sign-in proceeds.  On cancellation nothing happens —
     * the user can try again.
     */
    private fun showDataAccessConsentDialog(
        prefs: android.content.SharedPreferences
    ) {
        AppDialogs.confirm(
            context   = requireContext(),
            title     = getString(R.string.data_access_consent_title),
            message   = getString(R.string.data_access_consent_message),
            actionLabel = getString(R.string.data_access_accept)
        ) {
            prefs.edit().putBoolean(KEY_DATA_ACCESS_CONSENTED, true).apply()
            launchGoogleSignIn()
        }
    }

    /** Disables the sign-in button, shows the spinner, then launches OAuth. */
    private fun launchGoogleSignIn() {
        binding.btnSignIn.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            // SheetsScopes.SPREADSHEETS is the minimum scope required:
            //   • read-only access to the admin-managed product master sheet
            //   • read-write access to the user's admin-provisioned workspace sheet
            // No Drive scope is requested; personal Drive files are never accessed.
            .requestScopes(com.google.android.gms.common.api.Scope(SheetsScopes.SPREADSHEETS))
            .build()

        val googleSignInClient = GoogleSignIn.getClient(requireActivity(), gso)
        signInLauncher.launch(googleSignInClient.signInIntent)
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        hasNavigated = false  // reset so navigation works on this fresh attempt
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(requireActivity()) { task ->
                if (task.isSuccessful) {
                    auth.currentUser?.let { user ->
                        authViewModel.handleSignedInUser(user)
                    }
                } else {
                    binding.btnSignIn.isEnabled = true
                    binding.progressBar.visibility = View.GONE
                    showError("Firebase authentication failed: ${task.exception?.message}")
                }
            }
    }

    private var hasNavigated = false

    private fun observeAuthState() {
        authViewModel.authState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is AuthState.Loading -> {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.btnSignIn.isEnabled = false
                    binding.tvStatus.text = state.message
                    binding.tvStatus.visibility = View.VISIBLE
                }
                is AuthState.Success -> {
                    binding.progressBar.visibility = View.GONE
                    if (!hasNavigated) {
                        hasNavigated = true
                        findNavController().navigate(R.id.action_loginFragment_to_homeFragment)
                    }
                }
                is AuthState.Error -> {
                    binding.progressBar.visibility = View.GONE
                    binding.btnSignIn.isEnabled = true
                    binding.tvStatus.visibility = View.GONE
                    showError(state.message)
                }
                else -> {
                    binding.progressBar.visibility = View.GONE
                    binding.btnSignIn.isEnabled = true
                }
            }
        }
    }

    private fun showError(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
