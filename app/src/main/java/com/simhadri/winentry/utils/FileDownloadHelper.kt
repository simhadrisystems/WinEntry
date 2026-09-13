package com.simhadri.winentry.utils

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.fragment.app.Fragment
import java.io.File

private const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
private const val TAG = "FileDownload"

/**
 * Save [fileName] from the app's external-files staging dir to the public Downloads
 * folder, show a confirmation toast, then open the system share chooser so the user
 * can also send the file via Drive, email, WhatsApp, etc.
 *
 * Every Excel helper in this app writes its output to
 *   context.getExternalFilesDir(null)/fileName
 * before returning a FileProvider URI, so [fileName] alone is enough to locate the file.
 *
 * Usage — replace a bare startActivity(Intent.createChooser(...)) with:
 *   exportToDownloadsAndShare(uri, fileName, "Share Daily Stock")
 */
fun Fragment.exportToDownloadsAndShare(
    uri:      android.net.Uri,
    fileName: String,
    title:    String = "Share file"
) {
    val ctx = requireContext()
    val tempFile = File(ctx.getExternalFilesDir(null), fileName)
    if (saveToDownloads(ctx, tempFile, fileName)) {
        Toast.makeText(ctx, "Saved to Downloads: $fileName", Toast.LENGTH_LONG).show()
    }
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = XLSX_MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }, title))
}

/**
 * Copy [source] to the public Downloads folder.
 * Android 10+ uses MediaStore (no permission required).
 * Android 8-9 writes directly to Environment.DIRECTORY_DOWNLOADS
 * (WRITE_EXTERNAL_STORAGE permission is declared in the manifest with maxSdkVersion=29).
 */
fun saveToDownloads(context: Context, source: File, fileName: String): Boolean {
    if (!source.exists()) {
        Log.w(TAG, "saveToDownloads: source file not found — $fileName")
        return false
    }
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, XLSX_MIME)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads
                .getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val itemUri = context.contentResolver.insert(collection, cv)
                ?: return false
            context.contentResolver.openOutputStream(itemUri)
                ?.use { out -> source.inputStream().use { it.copyTo(out) } }
            cv.clear()
            cv.put(MediaStore.Downloads.IS_PENDING, 0)
            context.contentResolver.update(itemUri, cv, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dest = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                fileName
            )
            source.copyTo(dest, overwrite = true)
        }
        true
    } catch (e: Exception) {
        Log.e(TAG, "saveToDownloads failed for $fileName: ${e.message}", e)
        false
    }
}
