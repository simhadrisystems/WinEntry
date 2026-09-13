package com.simhadri.winentry.ui.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.simhadri.winentry.R
import com.simhadri.winentry.databinding.FragmentLoginBinding
import com.simhadri.winentry.utils.AppDialogs

class LoginFragment : Fragment() {

    companion object {
        /**
         * Both flags live in inventory_prefs and are device-level — not cleared
         * on sign-out, since the agreements cover the app's policies, not a
         * specific account.
         *
         * KEY_TOS_ACCEPTED: set when the user accepts the data-access consent dialog
         * that now includes the ToS paragraph.  Users who accepted the earlier version
         * (without ToS) have this flag absent and will see the dialog once more.
         */
        private const val KEY_TOS_ACCEPTED          = "tos_accepted"
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

        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        auth = FirebaseAuth.getInstance()

        // If already signed in, navigate away
        if (auth.currentUser != null) {
            authViewModel.handleSignedInUser(auth.currentUser!!)
        }

        binding.btnSignIn.setOnClickListener {
            startGoogleSignIn()
        }

        setupLegalLinks()
        observeAuthState()
    }

    /**
     * Combines Privacy Policy and Terms of Service into one tappable line.
     * Each segment opens its URL independently via ClickableSpan.
     */
    private fun setupLegalLinks() {
        val privacyUrl = getString(R.string.privacy_policy_url)
        val tosUrl     = getString(R.string.tos_url)

        val privacy   = "Privacy Policy"
        val separator = "  ·  "
        val tos       = "Terms of Service"
        val full      = "$privacy$separator$tos"

        val spannable = SpannableStringBuilder(full)

        spannable.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                if (privacyUrl.startsWith("http"))
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(privacyUrl)))
            }
        }, 0, privacy.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        spannable.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                if (tosUrl.startsWith("http"))
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(tosUrl)))
            }
        }, privacy.length + separator.length, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        binding.tvPrivacyPolicy.text            = spannable
        binding.tvPrivacyPolicy.movementMethod  = LinkMovementMethod.getInstance()
        // Force link text to a readable colour on the dark login background.
        // ClickableSpan uses textColorLink from the theme, which is dark in light mode.
        binding.tvPrivacyPolicy.setLinkTextColor(android.graphics.Color.parseColor("#94A3B8"))
    }

    /**
     * Entry point for the sign-in button.
     *
     * Shows a single data-access + ToS consent dialog before the OAuth screen.
     * The dialog is shown when either consent flag is absent, so users who accepted
     * the earlier version (data-access only, without the ToS paragraph) see it once
     * more after the app update.  Satisfies Google OAuth verification requirement
     * for sensitive-scope disclosure before the OAuth consent screen is shown.
     */
    private fun startGoogleSignIn() {
        val prefs = requireContext()
            .getSharedPreferences(AuthViewModel.PREFS_NAME, Context.MODE_PRIVATE)
        val consented = prefs.getBoolean(KEY_DATA_ACCESS_CONSENTED, false)
                     && prefs.getBoolean(KEY_TOS_ACCEPTED, false)
        if (!consented) {
            showDataAccessConsentDialog(prefs)
        } else {
            launchGoogleSignIn()
        }
    }

    /**
     * Shows the data-access + ToS disclosure dialog.  On acceptance both consent
     * flags are saved and OAuth sign-in proceeds.  On cancellation nothing happens —
     * the user can try again.
     */
    private fun showDataAccessConsentDialog(
        prefs: android.content.SharedPreferences
    ) {
        AppDialogs.confirm(
            context     = requireContext(),
            title       = getString(R.string.data_access_consent_title),
            message     = getString(R.string.data_access_consent_message),
            actionLabel = getString(R.string.data_access_accept)
        ) {
            prefs.edit()
                .putBoolean(KEY_DATA_ACCESS_CONSENTED, true)
                .putBoolean(KEY_TOS_ACCEPTED, true)
                .apply()
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
            // No Sheets scope — all sheet operations go through syncUserSheet Cloud Function
            // using the service account. Master product reads use the API key directly.
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
