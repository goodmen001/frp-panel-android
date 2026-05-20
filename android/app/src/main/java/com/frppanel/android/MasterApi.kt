package com.frppanel.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * REST API client for frp-panel master.
 * Supports login and client config retrieval via HTTP JSON API.
 */
class MasterApi(private val baseUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private var token: String? = null

    data class LoginResult(
        val success: Boolean,
        val token: String?,
        val error: String?
    )

    data class ConfigResult(
        val success: Boolean,
        val configJson: String?,
        val error: String?
    )

    /**
     * Login to the frp-panel master with admin credentials.
     * POST /api/v1/auth/login
     */
    suspend fun login(username: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("username", username)
                put("password", password)
            }
            val body = json.toString().toRequestBody(JSON)
            val request = Request.Builder()
                .url("$baseUrl/api/v1/auth/login")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext LoginResult(false, null, "Empty response")

            val respJson = JSONObject(responseBody)
            val status = respJson.optJSONObject("status")
            val code = status?.optString("code") ?: ""
            val message = status?.optString("message") ?: ""

            if (code == "RESP_CODE_SUCCESS") {
                val t = respJson.optString("token", "")
                if (t.isEmpty()) {
                    LoginResult(false, null, "Empty token in response")
                } else {
                    token = t
                    LoginResult(true, t, null)
                }
            } else {
                LoginResult(false, null, "Login failed: $message")
            }
        } catch (e: Exception) {
            LoginResult(false, null, "Network error: ${e.message}")
        }
    }

    /**
     * Get frpc config for a client.
     * POST /api/v1/client/get
     * Returns raw JSON of the client config.
     */
    suspend fun getClientConfig(clientId: String): ConfigResult = withContext(Dispatchers.IO) {
        try {
            val t = token ?: return@withContext ConfigResult(false, null, "Not logged in")

            val json = JSONObject().apply {
                put("clientId", clientId)
            }
            val body = json.toString().toRequestBody(JSON)
            val request = Request.Builder()
                .url("$baseUrl/api/v1/client/get")
                .post(body)
                .header("Authorization", t)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext ConfigResult(false, null, "Empty response")

            val respJson = JSONObject(responseBody)
            val status = respJson.optJSONObject("status")
            val code = status?.optString("code") ?: ""
            val message = status?.optString("message") ?: ""

            if (code != "RESP_CODE_SUCCESS") {
                return@withContext ConfigResult(false, null, "API error: $message")
            }

            val clientObj = respJson.optJSONObject("client") ?: return@withContext ConfigResult(false, null, "No client in response")

            if (clientObj.optBoolean("stopped", false)) {
                return@withContext ConfigResult(false, null, "Client is stopped on server")
            }

            val configStr = clientObj.optString("config", "")
            if (configStr.isEmpty()) {
                return@withContext ConfigResult(false, null, "Client has no config assigned")
            }

            // configStr is a JSON string containing frpc config.
            // Normalize Proxies -> proxies, Visitors -> visitors
            val normalized = normalizeConfigKeys(configStr)
            ConfigResult(true, normalized, null)
        } catch (e: Exception) {
            ConfigResult(false, null, "Network error: ${e.message}")
        }
    }

    /**
     * Normalize (Proxies -> proxies, Visitors -> visitors).
     */
    private fun normalizeConfigKeys(configJson: String): String {
        return try {
            val obj = JSONObject(configJson)
            if (obj.has("Proxies")) {
                obj.put("proxies", obj.remove("Proxies"))
            }
            if (obj.has("Visitors")) {
                obj.put("visitors", obj.remove("Visitors"))
            }
            obj.toString(2)
        } catch (e: Exception) {
            configJson // return as-is on failure
        }
    }
}
