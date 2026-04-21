package com.simhadri.winentry.ui.auth

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ErrorLogger — captures app errors to a local text file on the device.
 *
 * Testers can share the log file via WhatsApp, email or any share sheet
 * directly from the app without needing Android Studio or USB cable.
 *
 * Usage:
 *   // Log an error anywhere in the app:
 *   ErrorLogger.log(context, "DayReconciliation", "Save failed", exception)
 *
 *   // Share the log file (call from any Fragment/Activity):
 *   ErrorLogger.share(requireContext())
 *
 * Log file location: app's private files dir — not accessible to other apps,
 * but shareable via FileProvider.
 */
object ErrorLogger {

    private const val LOG_FILE_NAME = "app_errors.txt"
    private const val MAX_FILE_SIZE = 100_000L  // 100 KB — auto-clears when exceeded

    /**
     * Log an error with context, message and optional exception.
     * Thread-safe — can be called from any coroutine or thread.
     */
    fun log(
        context: Context,
        screen: String,
        message: String,
        exception: Exception? = null
    ) {
        try {
            val file = getLogFile(context)

            // Auto-clear if file gets too large
            if (file.exists() && file.length() > MAX_FILE_SIZE) {
                file.delete()
            }

            val timestamp = SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.getDefault()
            ).format(Date())

            val entry = buildString {
                appendLine("─────────────────────────────────────")
                appendLine("Time    : $timestamp")
                appendLine("Screen  : $screen")
                appendLine("Error   : $message")
                if (exception != null) {
                    appendLine("Type    : ${exception.javaClass.simpleName}")
                    appendLine("Detail  : ${exception.message}")
                    // Include first 5 stack frames for diagnosis
                    val frames = exception.stackTrace.take(5)
                        .joinToString("\n          ") { it.toString() }
                    appendLine("Stack   : $frames")
                }
                appendLine()
            }

            FileWriter(file, true).use { it.write(entry) }

            // Also log to Logcat for developer use
            android.util.Log.e("APP_ERROR", "[$screen] $message", exception)

        } catch (e: Exception) {
            // Never crash the app due to logging failure
            android.util.Log.e("ErrorLogger", "Failed to write log", e)
        }
    }

    /**
     * Share the error log file via the system share sheet.
     * Testers can send via WhatsApp, Gmail, Drive, etc.
     */
    fun share(context: Context) {
        val file = getLogFile(context)
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(context, "No errors logged yet.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Win Entry Error Log")
                putExtra(Intent.EXTRA_TEXT,
                    "Error log from Win Entry app. Please forward to admin.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(
                Intent.createChooser(shareIntent, "Share error log via…")
            )
        } catch (e: Exception) {
            Toast.makeText(context,
                "Could not share log: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Clear the log file — useful after admin has reviewed the errors.
     */
    fun clear(context: Context) {
        getLogFile(context).delete()
        Toast.makeText(context, "Error log cleared.", Toast.LENGTH_SHORT).show()
    }

    /**
     * Returns true if there are any logged errors.
     * Use this to show/hide the "Share Errors" button in Settings.
     */
    fun hasErrors(context: Context): Boolean {
        val file = getLogFile(context)
        return file.exists() && file.length() > 0
    }

    private fun getLogFile(context: Context): File {
        return File(context.filesDir, LOG_FILE_NAME)
    }
}
