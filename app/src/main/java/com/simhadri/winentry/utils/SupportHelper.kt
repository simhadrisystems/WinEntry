package com.simhadri.winentry.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import com.simhadri.winentry.ui.auth.ErrorLogger
import com.simhadri.winentry.ui.settings.BusinessInfoFragment

/**
 * SupportHelper — builds contact intents for Email and WhatsApp,
 * pre-filled with a structured message and auto-collected diagnostic info.
 *
 * Admin contact details live here as constants — update before release.
 */
object SupportHelper {

    // ── Admin contact details — update before release ──────────────────────
    const val SUPPORT_EMAIL     = "admin@simpleinventory.app"
    const val SUPPORT_WHATSAPP  = "+911234567890"   // country code, no spaces/dashes

    // ── User guide — update with actual Google Drive shareable link ────────
    // Upload the PDF to Google Drive → Share → "Anyone with the link can view"
    // → copy the link and paste it here.
    const val USER_GUIDE_URL    = "https://drive.google.com/file/d/181kyA9WU2nEr059S8DMHe0K9mznIidId/view?usp=drive_link"
    // ───────────────────────────────────────────────────────────────────────

    enum class IssueType(val label: String, val emoji: String) {
        BUG("Bug Report", "Bug"),
        HELP("Help Request", "Help"),
        FEATURE("Feature Request", "Feature"),
        WORKSPACE_REQUEST("Workspace Request", "Workspace")
    }

    /** Launch email client pre-filled with subject, body and diagnostic info. */
    fun sendEmail(context: Context, issueType: IssueType) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "[Win Entry] ${issueType.label}")
            putExtra(Intent.EXTRA_TEXT, buildEmailBody(context, issueType))
        }
        try {
            context.startActivity(Intent.createChooser(intent, "Send via email…"))
        } catch (e: Exception) {
            Toast.makeText(context, "No email app found on this device.", Toast.LENGTH_SHORT).show()
        }
    }

    /** Open the user guide PDF from Google Drive in the device browser. */
    fun openUserGuide(context: Context) {
        if (USER_GUIDE_URL.contains("YOUR_USER_GUIDE_FILE_ID")) {
            Toast.makeText(
                context,
                "User guide not available yet. Contact admin for help.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(USER_GUIDE_URL))
            )
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open the user guide. Please try again.", Toast.LENGTH_SHORT).show()
        }
    }

    /** Launch WhatsApp (or browser fallback) pre-filled with a support message. */
    fun sendWhatsApp(context: Context, issueType: IssueType) {
        val phone   = SUPPORT_WHATSAPP.filter { it.isDigit() || it == '+' }
        val message = buildWhatsAppMessage(context, issueType)
        val url     = "https://api.whatsapp.com/send?phone=$phone&text=${Uri.encode(message)}"

        // Try native WhatsApp first; fall back to browser link if not installed.
        val waIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage("com.whatsapp")
        }
        val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))

        try {
            context.startActivity(waIntent)
        } catch (e: Exception) {
            try {
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Toast.makeText(context, "Could not open WhatsApp.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Private builders ────────────────────────────────────────────────────

    private fun buildEmailBody(context: Context, issueType: IssueType): String = buildString {
        if (issueType == IssueType.WORKSPACE_REQUEST) {
            appendLine("Hello Admin,")
            appendLine()
            appendLine("I would like to request a cloud workspace (Google Sheet) to be set up " +
                "for my account so I can sync my inventory data to the admin-managed workspace.")
            appendLine()
            appendLine("────────────────────────────────────")
            appendLine("User Details (auto-filled)")
            appendLine("────────────────────────────────────")
            append(buildWorkspaceBlock(context))
        } else {
            appendLine("Hello Support Team,")
            appendLine()
            appendLine("[Describe your ${issueType.label.lowercase()} here]")
            appendLine()
            appendLine()
            appendLine("────────────────────────────────────")
            appendLine("Diagnostic Info (auto-filled)")
            appendLine("────────────────────────────────────")
            append(buildDiagnosticBlock(context))
        }
    }

    private fun buildWhatsAppMessage(context: Context, issueType: IssueType): String = buildString {
        if (issueType == IssueType.WORKSPACE_REQUEST) {
            appendLine("*Win Entry — Workspace Request*")
            appendLine()
            appendLine("Hello Admin, I am requesting a cloud workspace to sync my inventory data.")
            appendLine()
            appendLine("_User Details:_")
            append(buildWorkspaceBlock(context))
        } else {
            appendLine("*Win Entry — ${issueType.emoji}: ${issueType.label}*")
            appendLine()
            appendLine("[Describe your issue here]")
            appendLine()
            appendLine("_Diagnostic Info:_")
            append(buildDiagnosticBlock(context))
        }
    }

    /**
     * Builds a workspace-request block with the user's registered business details.
     * Falls back gracefully when fields are not yet filled.
     */
    private fun buildWorkspaceBlock(context: Context): String {
        val bizPrefs = context.getSharedPreferences(
            BusinessInfoFragment.PREFS_NAME, Context.MODE_PRIVATE
        )
        val businessName = bizPrefs.getString(BusinessInfoFragment.KEY_BUSINESS, "").orEmpty()
        val ownerName    = bizPrefs.getString(BusinessInfoFragment.KEY_OWNER_NAME, "").orEmpty()
        val phone        = bizPrefs.getString(BusinessInfoFragment.KEY_PHONE, "").orEmpty()
        val location     = bizPrefs.getString(BusinessInfoFragment.KEY_LOCATION, "").orEmpty()
        val email        = Firebase.auth.currentUser?.email ?: "not signed in"
        val uid          = Firebase.auth.currentUser?.uid?.take(8)?.let { "$it…" } ?: "—"

        return buildString {
            appendLine("Email        : $email")
            appendLine("User ID      : $uid")
            if (ownerName.isNotEmpty())    appendLine("Owner Name   : $ownerName")
            if (businessName.isNotEmpty()) appendLine("Business     : $businessName")
            if (phone.isNotEmpty())        appendLine("Phone        : $phone")
            if (location.isNotEmpty())     appendLine("Location     : $location")
        }
    }

    private fun buildDiagnosticBlock(context: Context): String {
        val uid = Firebase.auth.currentUser?.uid?.take(8)?.let { "$it…" } ?: "not signed in"
        val role = context
            .getSharedPreferences("inventory_prefs", Context.MODE_PRIVATE)
            .getString("user_role", "editor") ?: "editor"
        val businessName = context
            .getSharedPreferences(BusinessInfoFragment.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(BusinessInfoFragment.KEY_BUSINESS, "") ?: ""
        val hasErrors = ErrorLogger.hasErrors(context)

        val (versionName, versionCode) = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            (info.versionName ?: "?") to code
        } catch (e: Exception) {
            "?" to 0L
        }

        return buildString {
            appendLine("App Version : $versionName ($versionCode)")
            appendLine("Android     : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device      : ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("User ID     : $uid")
            appendLine("Role        : $role")
            if (businessName.isNotEmpty()) appendLine("Business    : $businessName")
            appendLine("Error Log   : ${if (hasErrors) "errors present" else "none"}")
        }
    }
}
