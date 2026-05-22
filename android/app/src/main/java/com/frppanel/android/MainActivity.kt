package com.frppanel.android

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {

    private var serviceBound = false
    private var boundService by mutableStateOf<FrpcForegroundService?>(null)

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

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not, we proceed anyway */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme()
            ) {
                FrpcPanelUI(
                    onStart = { url, user, pass, cid, rpc ->
                        startEngine(url, user, pass, cid, rpc)
                    },
                    onStop = { stopEngine() },
                    service = boundService
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, FrpcForegroundService::class.java),
            connection, BIND_AUTO_CREATE
        )
    }

    override fun onStop() {
        super.onStop()
        if (serviceBound) {
            unbindService(connection)
            serviceBound = false
            boundService = null
        }
    }

    private fun startEngine(masterUrl: String, username: String, password: String, clientName: String, rpcUrl: String = "") {
        Toast.makeText(this, "Starting...", Toast.LENGTH_SHORT).show()
        val intent = Intent(this, FrpcForegroundService::class.java).apply {
            action = FrpcForegroundService.ACTION_START
            putExtra(FrpcForegroundService.EXTRA_MASTER_URL, masterUrl)
            putExtra(FrpcForegroundService.EXTRA_USERNAME, username)
            putExtra(FrpcForegroundService.EXTRA_PASSWORD, password)
            putExtra(FrpcForegroundService.EXTRA_CLIENT_NAME, clientName)
            if (rpcUrl.isNotBlank()) {
                putExtra(FrpcForegroundService.EXTRA_RPC_URL, rpcUrl)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopEngine() {
        val intent = Intent(this, FrpcForegroundService::class.java).apply {
            action = FrpcForegroundService.ACTION_STOP
        }
        startService(intent)
    }
}

@Composable
fun FrpcPanelUI(
    onStart: (String, String, String, String, String) -> Unit,
    onStop: () -> Unit,
    service: FrpcForegroundService?
) {
    var masterUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var clientName by remember { mutableStateOf("") }
    var rpcUrl by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("Stopped") }
    var isRunning by remember { mutableStateOf(false) }
    var logLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var showLogs by remember { mutableStateOf(false) }

    val logListState = rememberLazyListState()

    LaunchedEffect(service) {
        if (service != null) {
            service.status.collectLatest { s -> statusText = s }
        }
    }
    LaunchedEffect(service) {
        if (service != null) {
            service.running.collectLatest { r -> isRunning = r }
        }
    }
    LaunchedEffect(service) {
        if (service != null) {
            service.logLines.collectLatest { lines ->
                logLines = lines
                if (lines.isNotEmpty() && showLogs) {
                    logListState.animateScrollToItem(lines.size - 1)
                }
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
            placeholder = { Text("https://your-server.com:9001") },
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

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = rpcUrl,
            onValueChange = { rpcUrl = it },
            label = { Text("RPC URL (optional)") },
            placeholder = { Text("wss://grpc.ekxuexi.cn:3005") },
            singleLine = true,
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )

        Spacer(modifier = Modifier.height(16.dp))

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

        Spacer(modifier = Modifier.height(8.dp))

        // Log toggle + content
        if (logLines.isNotEmpty() || isRunning) {
            TextButton(
                onClick = { showLogs = !showLogs }
            ) {
                Text(if (showLogs) "Hide Logs" else "Show Logs (${logLines.size})")
            }

            if (showLogs) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                    )
                ) {
                    LazyColumn(
                        state = logListState,
                        modifier = Modifier.padding(8.dp)
                    ) {
                        items(logLines) { line ->
                            Text(
                                text = line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
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
                        password.isNotBlank() && clientName.isNotBlank()
                    ) {
                        onStart(
                            masterUrl.trim(), username.trim(),
                            password.trim(), clientName.trim(),
                            rpcUrl.trim()
                        )
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
