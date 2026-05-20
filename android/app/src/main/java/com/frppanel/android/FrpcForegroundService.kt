package com.frppanel.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
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
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _status = MutableStateFlow(FrpcManager.getStatus())
    val status: StateFlow<FrpcManager.Status> = _status.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Initializing..."))
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
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        stopEngine()
        super.onDestroy()
    }

    private fun startEngine(masterUrl: String, username: String, password: String, clientId: String) {
        scope.launch {
            updateNotification("Connecting...")
            val result = FrpcManager.start(masterUrl, username, password, clientId)
            if (result.isSuccess) {
                _status.value = FrpcManager.getStatus()
                updateNotification("Tunnels active")
            } else {
                _status.value = FrpcManager.Status(false, "Error: ${result.exceptionOrNull()?.message}")
                updateNotification("Connection failed")
            }
        }
    }

    private fun stopEngine() {
        scope.launch {
            FrpcManager.stop()
            _status.value = FrpcManager.Status(false, "Stopped")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FRPC Tunnel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "FRPC tunnel service notification"
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
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
