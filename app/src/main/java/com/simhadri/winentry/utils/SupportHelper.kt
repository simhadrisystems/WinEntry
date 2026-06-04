package com.simhadri.winentry.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * SupportHelper — builds contact intents for Email and WhatsApp.
 * No user data is collected or pre-filled automatically.
 * The message body only prompts the user to describe their issue
 * and optionally add details they are comfortable sharing.
 *
 * Admin contact details live here as constants — update before release.
 */
object SupportHelper {

    // ── Admin contact details ──────────────────────────────────────────────
    const val SUPPORT_EMAIL     = "simhadrisystems@gmail.com"
    const val SUPPORT_WHATSAPP  = "+911234567890"   // country code — WhatsApp support coming soon

    // ── User guide — GitHub Pages, language-specific ──────────────────────
    private const val USER_GUIDE_URL_EN = "https://simhadrisystems.github.io/guides/en/"
    private const val USER_GUIDE_URL_TE = "https://simhadrisystems.github.io/guides/te/"
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
            putExtra(Intent.EXTRA_TEXT, buildEmailBody(issueType))
        }
        try {
            context.startActivity(Intent.createChooser(intent, "Send via email…"))
        } catch (e: Exception) {
            Toast.makeText(context, "No email app found on this device.", Toast.LENGTH_SHORT).show()
        }
    }

    /** Open the user guide in the device browser, matching the app's current language. */
    fun openUserGuide(context: Context) {
        val url = when (LangPrefs.get(context)) {
            AppStrings.Lang.TE -> USER_GUIDE_URL_TE
            else               -> USER_GUIDE_URL_EN
        }
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open the user guide. Please try again.", Toast.LENGTH_SHORT).show()
        }
    }

    /** Launch WhatsApp (or browser fallback) pre-filled with a support message. */
    fun sendWhatsApp(context: Context, issueType: IssueType) {
        val phone   = SUPPORT_WHATSAPP.filter { it.isDigit() || it == '+' }
        val message = buildWhatsAppMessage(issueType)
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

    private fun buildEmailBody(issueType: IssueType): String = buildString {
        appendLine("Hello,")
        appendLine()
        appendLine(messagePlaceholder(issueType))
        appendLine()
        appendLine()
        append(optionalDetailsHint())
    }

    private fun buildWhatsAppMessage(issueType: IssueType): String = buildString {
        appendLine("*Win Entry — ${issueType.label}*")
        appendLine()
        appendLine(messagePlaceholder(issueType))
        appendLine()
        append(optionalDetailsHint())
    }

    private fun messagePlaceholder(issueType: IssueType): String = when (issueType) {
        IssueType.WORKSPACE_REQUEST ->
            "I would like to request a cloud workspace (Google Sheet) for my account."
        IssueType.BUG ->
            "[Please describe what happened and the steps that led to it]"
        IssueType.HELP ->
            "[Please describe what you need help with]"
        IssueType.FEATURE ->
            "[Please describe the feature or improvement you are requesting]"
    }

    private fun optionalDetailsHint(): String = buildString {
        appendLine("─────────────────────────────────────────")
        appendLine("To help us resolve this faster, you may")
        appendLine("optionally add any of the following:")
        appendLine()
        appendLine("  • Your business name and location")
        appendLine("  • Your registered phone number")
        appendLine("  • App version (visible in About / Settings)")
        appendLine("  • Device model and Android version")
        appendLine("─────────────────────────────────────────")
    }
}
