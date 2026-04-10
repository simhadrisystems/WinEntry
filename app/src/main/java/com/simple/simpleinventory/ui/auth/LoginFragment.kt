package com.simple.simpleinventory.ui.auth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.simple.simpleinventory.R
import com.simple.simpleinventory.databinding.FragmentLoginBinding
import androidx.activity.result.contract.ActivityResultContracts
import com.google.api.services.sheets.v4.SheetsScopes
import android.app.Activity

class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    private val authViewModel: AuthViewModel by viewModels()
    private lateinit var auth: FirebaseAuth

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                account.idToken?.let { idToken ->
                    firebaseAuthWithGoogle(idToken)
                } ?: showError("Sign-in failed: no ID token")
            } catch (e: ApiException) {
                showError("Google sign-in failed: ${e.statusCode}")
            }
        } else {
            binding.btnSignIn.isEnabled = true
            binding.progressBar.visibility = View.GONE
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

        observeAuthState()
    }

    private fun startGoogleSignIn() {
        binding.btnSignIn.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            // Request Sheets scope so CloudSyncManager.initialize() gets a valid
            // GoogleSignIn account with spreadsheet access on ALL devices/users.
            // Without this, GoogleSignIn.getLastSignedInAccount() returns an account
            // without Sheets permission and sync fails with "Not signed in to Google".
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
