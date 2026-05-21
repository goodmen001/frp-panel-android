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
 *
 * The frp-panel API wraps ALL responses in a Result envelope:
 *   {"code":200, "msg":"success", "body": { <protobuf-json> }}
 * Status codes inside body are integers (1=success, 4=invalid, etc.).
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
            val responseBody = response.body?.string()
                ?: return@withContext LoginResult(false, null, "Empty response")

            // Check if response is HTML (wrong URL/port)
            if (responseBody.trimStart().startsWith("<!") || responseBody.trimStart().startsWith("<html")) {
                return@withContext LoginResult(false, null,
                    "Server returned HTML (wrong URL or port?). Check Master URL")
            }

            val respJson = JSONObject(responseBody)

            // Check the Result wrapper
            if (respJson.optInt("code", 0) != 200) {
                return@withContext LoginResult(false, null, respJson.optString("msg", "Request failed"))
            }

            // The actual response is inside "body"
            val bodyObj = respJson.optJSONObject("body")
                ?: return@withContext LoginResult(false, null, "No body in response")

            val status = bodyObj.optJSONObject("status")
                ?: return@withContext LoginResult(false, null, "No status in response")

            // Status code is an integer: 1 = RESP_CODE_SUCCESS
            if (status.optInt("code", 0) == 1) {
                val t = bodyObj.optString("token", "")
                if (t.isEmpty()) {
                    LoginResult(false, null, "Empty token in response")
                } else {
                    token = t
                    LoginResult(true, t, null)
                }
            } else {
                LoginResult(false, null, "Login failed: ${status.optString("message", "unknown error")}")
            }
        } catch (e: Exception) {
            LoginResult(false, null, "Connection error: ${e.message}")
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

            // protojson expects camelCase field names
            val json = JSONObject().apply {
                put("clientId", clientName)
                put("ephemeral", false)
            }
            val reqBody = json.toString().toRequestBody(JSON)
            val request = Request.Builder()
                .url("$baseUrl/api/v1/client/init")
                .post(reqBody)
                .header("Authorization", t)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
                ?: return@withContext InitResult(false, null, "Empty response")

            if (responseBody.trimStart().startsWith("<!") || responseBody.trimStart().startsWith("<html")) {
                return@withContext InitResult(false, null, "Server returned HTML (wrong URL or port?)")
            }

            val respJson = JSONObject(responseBody)

            if (respJson.optInt("code", 0) != 200) {
                return@withContext InitResult(false, null, respJson.optString("msg", "Request failed"))
            }

            val bodyObj = respJson.optJSONObject("body")
                ?: return@withContext InitResult(false, null, "No body in response")

            val status = bodyObj.optJSONObject("status")
                ?: return@withContext InitResult(false, null, "No status in response")

            if (status.optInt("code", 0) == 1) {
                // Response uses proto field name "client_id" (via Go json tag)
                val cid = bodyObj.optString("client_id", "")
                if (cid.isEmpty()) {
                    InitResult(false, null, "Empty client_id in response")
                } else {
                    InitResult(true, cid, null)
                }
            } else {
                InitResult(false, null, "Init client failed: ${status.optString("message", "unknown error")}")
            }
        } catch (e: Exception) {
            InitResult(false, null, "Connection error: ${e.message}")
        }
    }

    /**
     * Get client info (config + secret) from the master.
     * POST /api/v1/client/get
     */
    suspend fun getClientConfig(clientId: String): ConfigResult = withContext(Dispatchers.IO) {
        try {
            val t = token ?: return@withContext ConfigResult(false, null, "Not logged in")

            // protojson expects camelCase field names
            val json = JSONObject().apply {
                put("clientId", clientId)
            }
            val reqBody = json.toString().toRequestBody(JSON)
            val request = Request.Builder()
                .url("$baseUrl/api/v1/client/get")
                .post(reqBody)
                .header("Authorization", t)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
                ?: return@withContext ConfigResult(false, null, "Empty response")

            if (responseBody.trimStart().startsWith("<!") || responseBody.trimStart().startsWith("<html")) {
                return@withContext ConfigResult(false, null, "Server returned HTML (wrong URL or port?)")
            }

            val respJson = JSONObject(responseBody)

            if (respJson.optInt("code", 0) != 200) {
                return@withContext ConfigResult(false, null, respJson.optString("msg", "Request failed"))
            }

            val bodyObj = respJson.optJSONObject("body")
                ?: return@withContext ConfigResult(false, null, "No body in response")

            val status = bodyObj.optJSONObject("status")
                ?: return@withContext ConfigResult(false, null, "No status in response")

            if (status.optInt("code", 0) != 1) {
                return@withContext ConfigResult(false, null,
                    "API error: ${status.optString("message", "unknown error")}")
            }

            val clientObj = bodyObj.optJSONObject("client")
                ?: return@withContext ConfigResult(false, null, "No client in response")

            if (clientObj.optBoolean("stopped", false)) {
                return@withContext ConfigResult(false, null, "Client is stopped on server")
            }

            val cid = clientObj.optString("id", "")
            val secret = clientObj.optString("secret", null)
            val configStr = clientObj.optString("config", "")

            // Return the client info even if config is empty — the caller
            // decides how to handle an unconfigured client.
            val normalized = if (configStr.isEmpty()) "" else normalizeConfigKeys(configStr)
            ConfigResult(true, ClientInfo(cid, secret, normalized), null)
        } catch (e: Exception) {
            ConfigResult(false, null, "Connection error: ${e.message}")
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
