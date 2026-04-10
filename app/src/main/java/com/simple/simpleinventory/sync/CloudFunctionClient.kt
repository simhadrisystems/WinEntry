package com.simple.simpleinventory.sync

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP client for calling Firebase Cloud Functions.
 *
 * All functions are called with a Firebase ID token in the Authorization header
 * so the Cloud Function can verify the caller's identity server-side.
 *
 * SETUP: Replace CLOUD_FUNCTION_BASE_URL with your actual Firebase project's
 * Cloud Functions URL. You can find this in the Firebase Console under
 * Functions > Dashboard after deploying.
 *
 * Example:
 *   https://us-central1-YOUR_PROJECT_ID.cloudfunctions.net
 */
class CloudFunctionClient {

    companion object {
        // TODO: Replace with your actual Firebase project Cloud Functions base URL
        private const val CLOUD_FUNCTION_BASE_URL =
            "https://us-central1-YOUR_PROJECT_ID.cloudfunctions.net"

        private const val CREATE_SHEET_ENDPOINT = "$CLOUD_FUNCTION_BASE_URL/createUserSheet"

        private const val TIMEOUT_MS = 30_000  // 30 seconds — sheet creation can be slow
    }

    /**
     * Calls the createUserSheet Cloud Function.
     *
     * @return The new spreadsheet ID on success, null on failure.
     *
     * The Cloud Function will:
     *   1. Verify the Firebase ID token
     *   2. Create a Google Sheet via Service Account
     *   3. Write /users/{uid} to Firestore with the new sheetId
     *   4. Return { "sheetId": "...", "sheetUrl": "..." }
     */
    suspend fun createUserSheet(
        uid: String,
        email: String,
        displayName: String
    ): String? {
        return try {
            val idToken = getFirebaseIdToken() ?: return null

            val requestBody = JSONObject().apply {
                put("uid", uid)
                put("email", email)
                put("displayName", displayName)
            }.toString()

            val response = postJson(
                urlString = CREATE_SHEET_ENDPOINT,
                body = requestBody,
                idToken = idToken
            )

            val responseJson = JSONObject(response)
            responseJson.optString("sheetId").takeIf { it.isNotBlank() }

        } catch (e: Exception) {
            android.util.Log.e("CloudFunctionClient", "createUserSheet failed", e)
            null
        }
    }

    /**
     * Fetches a fresh Firebase ID token for the currently signed-in user.
     * Returns null if no user is signed in.
     */
    private suspend fun getFirebaseIdToken(): String? {
        val user = FirebaseAuth.getInstance().currentUser ?: return null
        return try {
            user.getIdToken(false).await().token
        } catch (e: Exception) {
            android.util.Log.e("CloudFunctionClient", "getIdToken failed", e)
            null
        }
    }

    /**
     * Simple HTTP POST with JSON body and Bearer token auth.
     * Uses HttpURLConnection to avoid requiring an extra HTTP library.
     */
    private fun postJson(urlString: String, body: String, idToken: String): String {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $idToken")
        }

        OutputStreamWriter(connection.outputStream).use { writer ->
            writer.write(body)
            writer.flush()
        }

        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }

        val responseText = BufferedReader(InputStreamReader(stream)).use { reader ->
            reader.readText()
        }
        connection.disconnect()

        if (responseCode !in 200..299) {
            throw RuntimeException(
                "Cloud Function returned HTTP $responseCode: $responseText"
            )
        }

        return responseText
    }
}
