package com.simhadri.winentry.sync

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class SheetInfo(val sheetId: String, val sheetUrl: String)

sealed class CreateSheetResult {
    data class Success(val sheetInfo: SheetInfo) : CreateSheetResult()
    object NotInvited : CreateSheetResult()
    object Error : CreateSheetResult()
}

sealed class SyncSheetResult {
    data class Written(val written: Int, val updated: Int, val inserted: Int) : SyncSheetResult()
    data class Deleted(val deleted: Int) : SyncSheetResult()
    data class AllRead(
        val purchases:      List<List<Any>>,
        val dailyStock:     List<List<Any>>,
        val daySummary:     List<List<Any>>,
        val purchaseImport: List<List<Any>>
    ) : SyncSheetResult()
    object NoSheet : SyncSheetResult()
    object Error : SyncSheetResult()
}

class CloudFunctionClient {

    companion object {
        private const val CLOUD_FUNCTION_BASE_URL =
            "https://asia-south1-winentry-a87f2.cloudfunctions.net"

        private const val CREATE_SHEET_ENDPOINT       = "$CLOUD_FUNCTION_BASE_URL/createUserSheet"
        private const val REGISTER_USER_ENDPOINT      = "$CLOUD_FUNCTION_BASE_URL/registerUserOnly"
        private const val SYNC_SHEET_ENDPOINT         = "$CLOUD_FUNCTION_BASE_URL/syncUserSheet"
        private const val DELETE_REGISTRATION_ENDPOINT = "$CLOUD_FUNCTION_BASE_URL/deleteUserRegistration"

        private const val TIMEOUT_MS = 60_000
    }

    suspend fun createUserSheet(
        uid: String,
        email: String,
        displayName: String,
        ownerName: String = "",
        businessName: String = "",
        phone: String = "",
        location: String = "",
        androidVersion: String = "",
        appVersion: String = ""
    ): CreateSheetResult {
        return try {
            android.util.Log.d("CloudFunctionClient", "createUserSheet called — uid=$uid")
            val idToken = getFirebaseIdToken()
            if (idToken == null) {
                android.util.Log.e("CloudFunctionClient", "createUserSheet aborted — Firebase user is null")
                return CreateSheetResult.Error
            }

            val requestBody = JSONObject().apply {
                put("uid", uid)
                put("email", email)
                put("displayName", displayName)
                put("ownerName", ownerName)
                put("businessName", businessName)
                put("phone", phone)
                put("location", location)
                put("androidVersion", androidVersion)
                put("appVersion", appVersion)
            }.toString()

            val (statusCode, responseBody) = withContext(Dispatchers.IO) {
                postJson(CREATE_SHEET_ENDPOINT, requestBody, idToken)
            }

            when {
                statusCode == 403 -> {
                    val error = runCatching { JSONObject(responseBody).optString("error") }.getOrNull()
                    if (error == "not_invited") {
                        android.util.Log.i("CloudFunctionClient", "createUserSheet — user not yet invited, request recorded")
                        CreateSheetResult.NotInvited
                    } else {
                        android.util.Log.e("CloudFunctionClient", "createUserSheet 403: $responseBody")
                        CreateSheetResult.Error
                    }
                }
                statusCode in 200..299 -> {
                    val responseJson = JSONObject(responseBody)
                    val sheetId = responseJson.optString("sheetId").takeIf { it.isNotBlank() }
                        ?: return CreateSheetResult.Error
                    val sheetUrl = responseJson.optString("sheetUrl")
                    CreateSheetResult.Success(SheetInfo(sheetId, sheetUrl))
                }
                else -> {
                    android.util.Log.e("CloudFunctionClient", "createUserSheet HTTP $statusCode: $responseBody")
                    CreateSheetResult.Error
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CloudFunctionClient", "createUserSheet failed", e)
            CreateSheetResult.Error
        }
    }

    suspend fun registerUserOnly(
        uid: String,
        email: String,
        displayName: String,
        ownerName: String = "",
        businessName: String = "",
        phone: String = "",
        location: String = "",
        androidVersion: String = "",
        appVersion: String = "",
        forceUpdate: Boolean = false
    ): Boolean {
        return try {
            val idToken = getFirebaseIdToken() ?: return false
            val requestBody = JSONObject().apply {
                put("uid", uid)
                put("email", email)
                put("displayName", displayName)
                put("ownerName", ownerName)
                put("businessName", businessName)
                put("phone", phone)
                put("location", location)
                put("androidVersion", androidVersion)
                put("appVersion", appVersion)
                put("forceUpdate", forceUpdate)
            }.toString()
            withContext(Dispatchers.IO) {
                postJson(REGISTER_USER_ENDPOINT, requestBody, idToken)
            }
            true
        } catch (e: Exception) {
            android.util.Log.w("CloudFunctionClient", "registerUserOnly failed (non-fatal)", e)
            false
        }
    }

    suspend fun syncUserSheet(
        operation: String,
        rows: List<List<Any>> = emptyList(),
        txnIds: List<String> = emptyList()
    ): SyncSheetResult {
        return try {
            val idToken = getFirebaseIdToken() ?: return SyncSheetResult.Error

            val body = JSONObject().apply {
                put("operation", operation)
                if (rows.isNotEmpty()) {
                    put("rows", JSONArray().also { arr ->
                        rows.forEach { row ->
                            arr.put(JSONArray().also { rowArr ->
                                row.forEach { cell -> rowArr.put(cell) }
                            })
                        }
                    })
                }
                if (txnIds.isNotEmpty()) {
                    put("txnIds", JSONArray(txnIds))
                }
            }.toString()

            val (statusCode, responseBody) = withContext(Dispatchers.IO) {
                postJson(SYNC_SHEET_ENDPOINT, body, idToken)
            }

            if (statusCode == 404) {
                val error = runCatching { JSONObject(responseBody).optString("error") }.getOrNull()
                if (error == "no_sheet") return SyncSheetResult.NoSheet
            }

            if (statusCode !in 200..299) {
                android.util.Log.e("CloudFunctionClient", "syncUserSheet [$operation] HTTP $statusCode: $responseBody")
                return SyncSheetResult.Error
            }

            val json = JSONObject(responseBody)
            when (operation) {
                "write_purchases", "write_daily_stock", "write_day_summary" ->
                    SyncSheetResult.Written(
                        json.optInt("written"),
                        json.optInt("updated"),
                        json.optInt("inserted")
                    )
                "delete_purchases" ->
                    SyncSheetResult.Deleted(json.optInt("deleted"))
                "clear_all" ->
                    SyncSheetResult.Deleted(0)
                "read_all" -> {
                    fun parseTab(key: String): List<List<Any>> {
                        val arr = json.optJSONArray(key) ?: return emptyList()
                        return (0 until arr.length()).map { i ->
                            val row = arr.optJSONArray(i) ?: return@map emptyList()
                            (0 until row.length()).map { j -> row.get(j) }
                        }
                    }
                    SyncSheetResult.AllRead(
                        purchases      = parseTab("purchases"),
                        dailyStock     = parseTab("dailyStock"),
                        daySummary     = parseTab("daySummary"),
                        purchaseImport = parseTab("purchaseImport")
                    )
                }
                else -> SyncSheetResult.Error
            }
        } catch (e: Exception) {
            android.util.Log.e("CloudFunctionClient", "syncUserSheet [$operation] failed", e)
            SyncSheetResult.Error
        }
    }

    suspend fun deleteUserRegistration(uid: String, email: String): Boolean {
        return try {
            val idToken = getFirebaseIdToken() ?: return false
            val body = JSONObject().apply {
                put("uid", uid)
                put("email", email)
            }.toString()
            val (statusCode, _) = withContext(Dispatchers.IO) {
                postJson(DELETE_REGISTRATION_ENDPOINT, body, idToken)
            }
            if (statusCode !in 200..299) {
                android.util.Log.w("CloudFunctionClient", "deleteUserRegistration HTTP $statusCode")
            }
            statusCode in 200..299
        } catch (e: Exception) {
            android.util.Log.w("CloudFunctionClient", "deleteUserRegistration failed (non-fatal)", e)
            false
        }
    }

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
     * HTTP POST returning (statusCode, responseBody) for all responses.
     * Never throws on non-2xx — caller decides how to handle each status code.
     */
    private fun postJson(urlString: String, body: String, idToken: String): Pair<Int, String> {
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
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val responseText = BufferedReader(InputStreamReader(stream)).use { it.readText() }
        connection.disconnect()

        return Pair(responseCode, responseText)
    }
}
