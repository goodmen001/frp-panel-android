package com.frppanel.android

import android.content.Intent
import android.os.Build
import android.os.Bundle
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
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val serviceIntent: Intent by lazy {
        Intent(this, FrpcForegroundService::class.java)
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
                    onStop = { stopEngine() }
                )
            }
        }
    }

    private fun startEngine(masterUrl: String, username: String, password: String, clientId: String) {
        serviceIntent.action = FrpcForegroundService.ACTION_START
        serviceIntent.putExtra(FrpcForegroundService.EXTRA_MASTER_URL, masterUrl)
        serviceIntent.putExtra(FrpcForegroundService.EXTRA_USERNAME, username)
        serviceIntent.putExtra(FrpcForegroundService.EXTRA_PASSWORD, password)
        serviceIntent.putExtra(FrpcForegroundService.EXTRA_CLIENT_ID, clientId)
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
    onStop: () -> Unit
) {
    var masterUrl by remember { mutableStateOf("https://api.ekxuexi.cn:3003") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var clientId by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("Stopped") }
    var isRunning by remember { mutableStateOf(false) }

    // Poll status
    LaunchedEffect(Unit) {
        while (true) {
            val s = FrpcManager.getStatus()
            isRunning = s.running
            statusText = s.text
            delay(2000)
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
            placeholder = { Text("https://api.ekxuexi.cn:3003") },
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
            value = clientId,
            onValueChange = { clientId = it },
            label = { Text("Client ID") },
            placeholder = { Text("admin.c.myandroid@1") },
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
                        password.isNotBlank() && clientId.isNotBlank()) {
                        onStart(masterUrl.trim(), username.trim(), password.trim(), clientId.trim())
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
