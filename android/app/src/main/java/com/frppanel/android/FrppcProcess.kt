package com.frppanel.android

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader

data class StartResult(val success: Boolean, val error: String? = null)

/**
 * Manages the frppc (frp-panel client) subprocess.
 *
 * frppc is the Go client daemon that connects to the frp-panel master
 * via WebSocket/gRPC, registers the client, and runs the embedded frpc
 * library in-process when config is received from the master.
 *
 * The binary is bundled in APK assets/ and extracted on first run.
 * Configuration is passed via environment variables.
 */
class FrppcProcess(private val context: Context) {

    companion object {
        private const val TAG = "FrppcProcess"
        private const val BINARY_NAME = "frppc"
        private const val MAX_LOG_LINES = 500
    }

    private var process: Process? = null
    private var running = false
    private val logBuffer = mutableListOf<String>()
    private var outputReaderThread: Thread? = null

    val logLines: List<String> get() = synchronized(logBuffer) { logBuffer.toList() }

    /**
     * Extract the frppc binary from assets to an executable location.
     *
     * Android 10+ mounts /data/data/<pkg>/files/ as noexec, preventing
     * direct binary execution. We use getDir("native", MODE_PRIVATE)
     * which creates /data/data/<pkg>/app_native/ — this directory is
     * often on an exec-enabled filesystem.
     */
    fun extractBinary(): File? {
        // Use getDir to create an app-private directory that supports exec
        val binDir = context.getDir("native", Context.MODE_PRIVATE)
        val binary = File(binDir, BINARY_NAME)
        if (binary.exists() && binary.canExecute()) {
            Log.i(TAG, "Binary already extracted: ${binary.absolutePath}")
            return binary
        }

        try {
            // Clean up old copies in filesDir if present
            File(context.filesDir, BINARY_NAME).delete()

            context.assets.open(BINARY_NAME).use { input ->
                FileOutputStream(binary).use { output ->
                    input.copyTo(output)
                }
            }
            binary.setExecutable(true)
            Log.i(TAG, "Binary extracted: ${binary.absolutePath} (size=${binary.length()})")
            return binary
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract binary: ${e.message}", e)
            return null
        }
    }

    /**
     * Start frppc as a subprocess with the given credentials.
     *
     * frppc connects to the master via WebSocket/gRPC, registers the client,
     * maintains the persistent connection, and runs frpc in-process when
     * the master sends a config.
     *
     * @param binary The frppc binary file
     * @param clientId The full client ID (e.g. "admin.c.myandroid")
     * @param clientSecret The client secret from the master
     * @param rpcUrl The master RPC URL (ws://host:port, derived from master HTTP URL)
     * @return Result with success status and error message on failure
     */
    fun start(binary: File, clientId: String, clientSecret: String, rpcUrl: String): StartResult {
        if (running) {
            Log.w(TAG, "Already running")
            return StartResult(false, "Already running")
        }

        // Check binary
        if (!binary.exists()) {
            return StartResult(false, "Binary not found: ${binary.absolutePath}")
        }
        val size = binary.length()
        if (size == 0L) {
            return StartResult(false, "Binary is empty")
        }
        Log.i(TAG, "Binary size: $size bytes at ${binary.absolutePath}")

        // Ensure executable
        if (!binary.canExecute()) {
            binary.setExecutable(true)
        }

        try {
            val pb = ProcessBuilder(binary.absolutePath)
            pb.directory(context.filesDir)
            pb.redirectErrorStream(true)

            // Pass config via environment variables (frppc reads CLIENT_* env vars)
            pb.environment().putAll(mapOf(
                "CLIENT_ID" to clientId,
                "CLIENT_SECRET" to clientSecret,
                "CLIENT_RPC_URL" to rpcUrl,
                "CLIENT_TLS_RPC" to "false",
                "CLIENT_TLS_INSECURE_SKIP_VERIFY" to "true"
            ))

            process = pb.start()
            running = true
            Log.i(TAG, "frppc process started (id=$clientId)")

            // Read combined stdout+stderr in background
            outputReaderThread = Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(process!!.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val logLine = line!!
                        Log.d(TAG, "frppc: $logLine")
                        synchronized(logBuffer) {
                            logBuffer.add(logLine)
                            if (logBuffer.size > MAX_LOG_LINES) {
                                logBuffer.removeAt(0)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (running) {
                        Log.w(TAG, "Output reader ended: ${e.message}")
                    }
                }
                running = false
                Log.i(TAG, "frppc process ended")
            }.also { thread ->
                thread.isDaemon = true
                thread.name = "frppc-stdout"
                thread.start()
            }

            return StartResult(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start frppc: ${e.message}", e)
            running = false
            return StartResult(false, "Process error: ${e.message}")
        }
    }

    /**
     * Stop the frppc process gracefully (SIGTERM).
     * frppc will clean up the in-process frpc and disconnect from the master.
     */
    fun stop() {
        val proc = process ?: return
        running = false
        try {
            proc.destroy()
            Log.i(TAG, "Sent SIGTERM to frppc")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping frppc: ${e.message}")
        }
        process = null
    }

    fun isRunning(): Boolean = running && (process?.isAlive == true)

    /**
     * Clean up the binary file.
     */
    fun cleanup() {
        stop()
        File(context.filesDir, BINARY_NAME).delete()
        File(context.getDir("native", Context.MODE_PRIVATE), BINARY_NAME).delete()
        Log.i(TAG, "Cleanup complete")
    }
}
