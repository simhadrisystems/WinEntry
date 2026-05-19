package com.simhadri.winentry.data

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

/**
 * Firestore document model for /users/{uid}.
 * Fields written by the Cloud Function: uid, email, displayName, userSheetId,
 * userSheetUrl, ownerName, businessName, phone, location, role, createdAt.
 * The app reads userSheetId and role on login (via snapshot.getString —
 * toObject() is not currently used, but this class is kept for ProGuard safety).
 */
data class UserProfile(
    @DocumentId
    val uid: String = "",

    val email: String = "",

    val displayName: String = "",

    /**
     * Google Sheets spreadsheet ID used for cloud backup.
     * Created by the Cloud Function in the admin-managed Google Drive.
     */
    val userSheetId: String = "",

    /** URL to open the sheet directly in a browser — convenience for admins. */
    val userSheetUrl: String = "",

    // ── Business profile — filled by user on first login ─────────────────────

    val businessName: String = "",
    val ownerName: String = "",
    val phone: String = "",
    val address: String = "",

    // ── Timestamps ───────────────────────────────────────────────────────────

    /** Set by the Cloud Function when the document is first created. */
    @ServerTimestamp
    val createdAt: Date? = null,

    /** Set by the app once the user finishes their first-login profile. */
    @ServerTimestamp
    val profileCompletedAt: Date? = null,

    /** Updated whenever the app syncs. Set by the app, not the Cloud Function. */
    @ServerTimestamp
    val lastSyncAt: Date? = null
) {
    // Firestore requires a no-arg constructor (data class default values provide this)
}
