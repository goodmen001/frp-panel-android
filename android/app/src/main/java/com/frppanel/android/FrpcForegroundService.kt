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

class FrpcForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "frpc_tunnel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.frppanel.android.START"
        const val ACTION_STOP = "com.frppanel.android.STOP"
        const val EXTRA_MASTER_URL = "master_url"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_CLIENT_ID = "client_id"
        private const val TAG = "FrpcForegroundService"
    }

    inner class LocalBinder : Binder() {
        fun getService(): FrpcForegroundService = this@FrpcForegroundService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var frpcProcess: FrpcProcess

    private val _status = MutableStateFlow("Stopped")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        frpcProcess = FrpcProcess(this)
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
                val clientId = intent.getStringExtra(EXTRA_CLIENT_ID) ?: return START_NOT_STICKY
                startEngine(masterUrl, username, password, clientId)
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
            _status.value = "Connecting..."
            updateNotification("Connecting to $masterUrl")

            // 1. Extract frpc binary
            val binary = frpcProcess.extractBinary()
            if (binary == null) {
                _status.value = "Error: binary extraction failed"
                _running.value = false
                updateNotification("Binary extraction failed")
                return@launch
            }

            // 2. Login to master
            val api = MasterApi(masterUrl.trimEnd('/'))
            _status.value = "Logging in..."
            val loginResult = api.login(username, password)
            if (!loginResult.success) {
                _status.value = "Error: ${loginResult.error}"
                _running.value = false
                updateNotification("Login failed")
                return@launch
            }
            Log.i(TAG, "Login successful")

            // 3. Get client config from master
            _status.value = "Looking up client..."
            updateNotification("Looking up client $clientName")
            val configResult = api.getClientConfig(clientName)
            if (!configResult.success) {
                _status.value = "Error: ${configResult.error}"
                _running.value = false
                updateNotification("Client config failed")
                return@launch
            }

            val clientInfo = configResult.clientInfo!!
            val configJson = clientInfo.configJson
            if (configJson.isNullOrBlank()) {
                _status.value = "Client '${clientInfo.id}' has no config"
                _running.value = false
                updateNotification("Client has no proxy config assigned")
                Log.w(TAG, "Client ${clientInfo.id} has no proxy config")
                return@launch
            }

            Log.i(TAG, "Got config for client: ${clientInfo.id}")

            // 4. Write config file
            val configFile = frpcProcess.writeConfig(configJson)
            if (configFile == null) {
                _status.value = "Error: cannot write config"
                _running.value = false
                updateNotification("Config write failed")
                return@launch
            }

            // 5. Start frpc
            _status.value = "Starting frpc..."
            updateNotification("Starting tunnels...")
            val started = frpcProcess.start(binary, configFile)
            if (started) {
                _status.value = "Running"
                _running.value = true
                updateNotification("Tunnels active")
                Log.i(TAG, "frpc started successfully")
            } else {
                _status.value = "Error: frpc failed to start"
                _running.value = false
                updateNotification("Start failed")
            }
            } catch (e: Exception) {
                Log.e(TAG, "Engine crashed", e)
                _status.value = "Error: ${e.message}"
                _running.value = false
                updateNotification("Error: ${e.message}")
            }
        }
    }

    private fun stopEngine() {
        frpcProcess.stop()
        _status.value = "Stopped"
        _running.value = false
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
