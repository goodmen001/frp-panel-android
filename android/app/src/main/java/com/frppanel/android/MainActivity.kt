package com.frppanel.android

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {

    private val serviceIntent: Intent by lazy {
        Intent(this, FrpcForegroundService::class.java)
    }

    private var serviceBound = false
    private var boundService: FrpcForegroundService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as FrpcForegroundService.LocalBinder
            boundService = binder.getService()
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme()
            ) {
                FrpcPanelUI(
                    onStart = { url, user, pass, cid ->
                        startEngine(url, user, pass, cid)
                    },
                    onStop = { stopEngine() },
                    service = boundService
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(serviceIntent, connection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(connection)
            serviceBound = false
        }
    }

    private fun startEngine(masterUrl: String, username: String, password: String, clientId: String) {
        serviceIntent.apply {
            action = FrpcForegroundService.ACTION_START
            putExtra(FrpcForegroundService.EXTRA_MASTER_URL, masterUrl)
            putExtra(FrpcForegroundService.EXTRA_USERNAME, username)
            putExtra(FrpcForegroundService.EXTRA_PASSWORD, password)
            putExtra(FrpcForegroundService.EXTRA_CLIENT_ID, clientId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopEngine() {
        serviceIntent.action = FrpcForegroundService.ACTION_STOP
        startService(serviceIntent)
    }
}

@Composable
fun FrpcPanelUI(
    onStart: (String, String, String, String) -> Unit,
    onStop: () -> Unit,
    service: FrpcForegroundService?
) {
    var masterUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var clientName by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("Stopped") }
    var isRunning by remember { mutableStateOf(false) }

    // Observe service status when bound
    LaunchedEffect(service) {
        if (service != null) {
            service.status.collectLatest { s ->
                statusText = s
            }
        }
    }
    LaunchedEffect(service) {
        if (service != null) {
            service.running.collectLatest { r ->
                isRunning = r
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "FRP Panel Client",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        OutlinedTextField(
            value = masterUrl,
            onValueChange = { masterUrl = it },
            label = { Text("Master URL") },
            placeholder = { Text("https://your-server.com:3003") },
            singleLine = true,
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            singleLine = true,
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            enabled = !isRunning,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = clientName,
            onValueChange = { clientName = it },
            label = { Text("Client Name") },
            placeholder = { Text("e.g. my-android-phone") },
            singleLine = true,
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Status card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Status",
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isRunning) "● Running - $statusText" else statusText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isRunning) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = {
                    if (masterUrl.isNotBlank() && username.isNotBlank() &&
                        password.isNotBlank() && clientName.isNotBlank()) {
                        onStart(masterUrl.trim(), username.trim(), password.trim(), clientName.trim())
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !isRunning
            ) {
                Text("Start")
            }

            OutlinedButton(
                onClick = { onStop() },
                modifier = Modifier.weight(1f),
                enabled = isRunning
            ) {
                Text("Stop")
            }
        }
    }
}
