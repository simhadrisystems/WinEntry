package com.simple.simpleinventory.data

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

/**
 * Firestore document model for /users/{uid}
 *
 * Fields written by the Cloud Function on first login:
 *   uid, email, displayName, userSheetId, createdAt
 *
 * The app reads userSheetId from this document on subsequent logins
 * (if the local SharedPreferences cache has been cleared).
 */
data class UserProfile(
    @DocumentId
    val uid: String = "",

    val email: String = "",

    val displayName: String = "",

    /**
     * Google Sheets spreadsheet ID created by the Cloud Function.
     * Stored here so the app can recover it on a fresh install.
     */
    val userSheetId: String = "",

    /**
     * URL to open the sheet directly in a browser — convenience for admins.
     */
    val userSheetUrl: String = "",

    /**
     * Server timestamp set when the Firestore document was first created.
     */
    @ServerTimestamp
    val createdAt: Date? = null,

    /**
     * Server timestamp updated whenever the app syncs.
     * Optional — set by the app, not the Cloud Function.
     */
    @ServerTimestamp
    val lastSyncAt: Date? = null
) {
    // Firestore requires a no-arg constructor (data class default values provide this)
}
