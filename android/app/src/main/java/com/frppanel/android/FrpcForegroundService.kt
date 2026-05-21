package com.frppanel.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that manages the frppc (frp-panel client) daemon.
 *
 * frppc is the Go client daemon that:
 * 1. Connects to the frp-panel master via WebSocket/gRPC
 * 2. Registers this device as a client
 * 3. Maintains a persistent bidirectional connection
 * 4. Runs the embedded frpc library in-process when the master assigns config
 *
 * This service handles authentication against the master's REST API,
 * retrieves the client credentials, and manages the frppc subprocess lifecycle.
 */
class FrpcForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "frpc_tunnel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.frppanel.android.START"
        const val ACTION_STOP = "com.frppanel.android.STOP"
        const val EXTRA_MASTER_URL = "master_url"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_CLIENT_NAME = "client_name"
        private const val TAG = "FrpcForegroundService"
    }

    inner class LocalBinder : Binder() {
        fun getService(): FrpcForegroundService = this@FrpcForegroundService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var frppcProcess: FrppcProcess

    private val _status = MutableStateFlow("Stopped")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        frppcProcess = FrppcProcess(this)
        createNotificationChannel()
        try {
            startForeground(NOTIFICATION_ID, buildNotification("Initializing..."))
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val masterUrl = intent.getStringExtra(EXTRA_MASTER_URL) ?: return START_NOT_STICKY
                val username = intent.getStringExtra(EXTRA_USERNAME) ?: return START_NOT_STICKY
                val password = intent.getStringExtra(EXTRA_PASSWORD) ?: return START_NOT_STICKY
                val clientName = intent.getStringExtra(EXTRA_CLIENT_NAME) ?: return START_NOT_STICKY
                startEngine(masterUrl, username, password, clientName)
            }
            ACTION_STOP -> {
                stopEngine()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        scope.cancel()
        stopEngine()
        super.onDestroy()
    }

    private fun startEngine(masterUrl: String, username: String, password: String, clientName: String) {
        scope.launch {
            try {
                _status.value = "Extracting binary..."
                updateNotification("Preparing frppc...")

                // 1. Extract frppc binary
                val binary = frppcProcess.extractBinary()
                if (binary == null) {
                    error("Binary extraction failed")
                    return@launch
                }

                // 2. Login to master REST API
                val api = MasterApi(masterUrl.trimEnd('/'))
                _status.value = "Logging in..."
                updateNotification("Authenticating...")
                val loginResult = api.login(username, password)
                if (!loginResult.success) {
                    error("Login failed: ${loginResult.error}")
                    return@launch
                }
                Log.i(TAG, "Login successful")

                // 3. Get or create client. Try GetClient first — if the client
                //    already exists (e.g. from a previous session), we get its
                //    secret directly. If not, InitClient creates a new one.
                _status.value = "Looking up client..."
                updateNotification("Looking up $clientName...")

                var clientId: String
                var secret: String

                val existing = api.getClientConfig(clientName)
                if (existing.success && existing.clientInfo?.secret != null) {
                    clientId = existing.clientInfo.id
                    secret = existing.clientInfo.secret!!
                    Log.i(TAG, "Found existing client: $clientId")
                } else {
                    // Client doesn't exist yet — create it
                    _status.value = "Registering client..."
                    updateNotification("Registering $clientName...")
                    val initResult = api.initClient(clientName)
                    if (!initResult.success) {
                        error("Init failed: ${initResult.error}")
                        return@launch
                    }
                    clientId = initResult.clientId!!
                    Log.i(TAG, "Client created: $clientId")

                    // Get the secret for the newly created client
                    _status.value = "Getting credentials..."
                    updateNotification("Retrieving client secret...")
                    val newInfo = api.getClientConfig(clientId)
                    if (!newInfo.success || newInfo.clientInfo?.secret.isNullOrEmpty()) {
                        error("Cannot get client secret")
                        return@launch
                    }
                    secret = newInfo.clientInfo!!.secret!!
                }
                Log.i(TAG, "Using client: $clientId")

                // 4. Build RPC URL (convert http:// to ws://, https:// to wss://)
                val rpcUrl = masterUrl.trimEnd('/')
                    .replace("http://", "ws://")
                    .replace("https://", "wss://")

                // 5. Start frppc subprocess
                _status.value = "Starting frppc..."
                updateNotification("Connecting to master...")
                val started = frppcProcess.start(binary, clientId, secret, rpcUrl)
                if (started) {
                    _status.value = "Connected"
                    _running.value = true
                    updateNotification("Connected to master")
                    Log.i(TAG, "frppc started successfully")

                    // Start polling log output
                    launchLogPoller()
                } else {
                    error("frppc failed to start")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Engine crashed", e)
                _status.value = "Error: ${e.message}"
                _running.value = false
                updateNotification("Error: ${e.message}")
            }
        }
    }

    private fun launchLogPoller() {
        scope.launch {
            while (_running.value) {
                _logLines.value = frppcProcess.logLines
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private fun error(msg: String) {
        _status.value = "Error: $msg"
        _running.value = false
        updateNotification("Error: $msg")
        Log.e(TAG, msg)
    }

    private fun stopEngine() {
        frppcProcess.stop()
        _status.value = "Stopped"
        _running.value = false
        _logLines.value = emptyList()
        updateNotification("Stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FRPC Tunnel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "FRPC tunnel service"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FRP Panel Client")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
