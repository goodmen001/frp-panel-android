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
 * 1. Connects to the frp-panel master via WebSocket/gRPC using the RPC URL
 * 2. Registers this device as a client using the pre-assigned client ID and secret
 * 3. Maintains a persistent bidirectional connection
 * 4. Periodically pulls and applies tunnel configurations
 * 5. Runs the embedded frpc library in-process when the master assigns config
 *
 * Unlike the web UI admin flow, this connects as a client directly using
 * pre-allocated credentials (similar to `frppc client -i <id> -s <secret>`).
 */
class FrpcForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "frpc_tunnel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.frppanel.android.START"
        const val ACTION_STOP = "com.frppanel.android.STOP"
        const val EXTRA_CLIENT_ID = "client_id"
        const val EXTRA_CLIENT_SECRET = "client_secret"
        const val EXTRA_RPC_URL = "rpc_url"
        const val EXTRA_API_URL = "api_url"
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
                val clientId = intent.getStringExtra(EXTRA_CLIENT_ID) ?: return START_NOT_STICKY
                val clientSecret = intent.getStringExtra(EXTRA_CLIENT_SECRET) ?: return START_NOT_STICKY
                val rpcUrl = intent.getStringExtra(EXTRA_RPC_URL) ?: return START_NOT_STICKY
                val apiUrl = intent.getStringExtra(EXTRA_API_URL)
                startEngine(clientId, clientSecret, rpcUrl, apiUrl)
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

    private fun startEngine(clientId: String, clientSecret: String, rpcUrl: String, apiUrl: String?) {
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

                // 2. Start frppc with client credentials directly
                //    (like: frppc client -i <id> -s <secret> --rpc-url <url>)
                _status.value = "Connecting..."
                updateNotification("Connecting to master...")

                val result = frppcProcess.start(
                    binary = binary,
                    clientId = clientId,
                    clientSecret = clientSecret,
                    rpcUrl = rpcUrl,
                    apiUrl = apiUrl
                )

                if (result.success) {
                    _status.value = "Connected"
                    _running.value = true
                    updateNotification("Connected to master")
                    Log.i(TAG, "frppc started successfully (id=$clientId)")

                    // Start polling log output
                    launchLogPoller()
                } else {
                    error("frppc failed to start: ${result.error}")
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
