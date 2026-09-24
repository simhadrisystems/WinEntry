package com.simhadri.winentry.utils

import android.content.Context
import com.simhadri.winentry.data.AppDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Copies the local database to filesDir/backups before a bulk operation; keeps the newest 3. */
object DbSnapshot {

    private const val KEEP = 3

    fun take(context: Context, reason: String): File? = try {
        val db = AppDatabase.getInstance(context)
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        val src = context.getDatabasePath(AppDatabase.DB_NAME)
        val dir = File(context.filesDir, "backups").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dest = File(dir, "inventory_${stamp}_$reason.db")
        src.copyTo(dest, overwrite = true)
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
        dest
    } catch (e: Exception) {
        com.simhadri.winentry.ui.auth.ErrorLogger.log(context, "DbSnapshot", "Snapshot failed ($reason)", e)
        null
    }
}
