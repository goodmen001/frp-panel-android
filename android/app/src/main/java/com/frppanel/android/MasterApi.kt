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
 * Supports login, client registration, and config retrieval.
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

    data class ClientInfo(
        val id: String,
        val secret: String?,
        val configJson: String?
    )

    data class ConfigResult(
        val success: Boolean,
        val clientInfo: ClientInfo?,
        val error: String?
    )

    data class InitResult(
        val success: Boolean,
        val clientId: String?,
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
     * Initialize (create) a new client on the master.
     * POST /api/v1/client/init
     * The server transforms the client name into format: username.c.clientName
     * Returns the full client ID (e.g. "admin.c.myandroid").
     */
    suspend fun initClient(clientName: String): InitResult = withContext(Dispatchers.IO) {
        try {
            val t = token ?: return@withContext InitResult(false, null, "Not logged in")

            val json = JSONObject().apply {
                put("client_id", clientName)
                put("ephemeral", false)
            }
            val body = json.toString().toRequestBody(JSON)
            val request = Request.Builder()
                .url("$baseUrl/api/v1/client/init")
                .post(body)
                .header("Authorization", t)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
                ?: return@withContext InitResult(false, null, "Empty response")

            val respJson = JSONObject(responseBody)
            val status = respJson.optJSONObject("status")
            val code = status?.optString("code") ?: ""
            val message = status?.optString("message") ?: ""

            if (code == "RESP_CODE_SUCCESS") {
                val cid = respJson.optString("client_id", "")
                if (cid.isEmpty()) {
                    InitResult(false, null, "Empty client_id in response")
                } else {
                    InitResult(true, cid, null)
                }
            } else {
                InitResult(false, null, "Init client failed: $message")
            }
        } catch (e: Exception) {
            InitResult(false, null, "Network error: ${e.message}")
        }
    }

    /**
     * Get client info (config + secret) from the master.
     * POST /api/v1/client/get
     * Client ID can be either the short name (auto-transformed) or the full ID.
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
            val responseBody = response.body?.string()
                ?: return@withContext ConfigResult(false, null, "Empty response")

            val respJson = JSONObject(responseBody)
            val status = respJson.optJSONObject("status")
            val code = status?.optString("code") ?: ""
            val message = status?.optString("message") ?: ""

            if (code != "RESP_CODE_SUCCESS") {
                return@withContext ConfigResult(false, null, "API error: $message")
            }

            val clientObj = respJson.optJSONObject("client")
                ?: return@withContext ConfigResult(false, null, "No client in response")

            if (clientObj.optBoolean("stopped", false)) {
                return@withContext ConfigResult(false, null, "Client is stopped on server")
            }

            val cid = clientObj.optString("id", "")
            val secret = clientObj.optString("secret", null)
            val configStr = clientObj.optString("config", "")

            if (configStr.isEmpty()) {
                return@withContext ConfigResult(false, null, "Client has no config assigned")
            }

            // configStr is a JSON string containing frpc config.
            // Normalize Proxies -> proxies, Visitors -> visitors
            val normalized = normalizeConfigKeys(configStr)
            ConfigResult(true, ClientInfo(cid, secret, normalized), null)
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
