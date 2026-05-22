package com.frppanel.android

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
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
 * On Android 10+, /data/data/<pkg>/ is mounted noexec, so direct
 * execution fails. We fall back to running via /system/bin/linker64
 * which loads the binary through the dynamic linker and bypasses the
 * noexec restriction.
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
     * Extract the frppc binary from assets to the app's private directory.
     */
    fun extractBinary(): File? {
        val binary = File(context.filesDir, BINARY_NAME)
        if (binary.exists() && binary.length() > 0L) {
            Log.i(TAG, "Binary already exists: ${binary.absolutePath} (size=${binary.length()})")
            return binary
        }

        try {
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
     * Direct execution is tried first. On Android 10+ the app data
     * directory is often mounted noexec, causing error=13. When that
     * happens we retry via /system/bin/linker64 which loads the ELF
     * through the dynamic linker, bypassing the noexec restriction.
     */
    fun start(binary: File, clientId: String, clientSecret: String, rpcUrl: String): StartResult {
        if (running) {
            Log.w(TAG, "Already running")
            return StartResult(false, "Already running")
        }

        if (!binary.exists()) {
            return StartResult(false, "Binary not found: ${binary.absolutePath}")
        }
        val size = binary.length()
        if (size == 0L) {
            return StartResult(false, "Binary is empty")
        }
        Log.i(TAG, "Binary size: $size bytes at ${binary.absolutePath}")

        if (!binary.canExecute()) {
            binary.setExecutable(true)
        }

        // Try direct execution first
        try {
            val pb = ProcessBuilder(binary.absolutePath)
            configureProcess(pb, clientId, clientSecret, rpcUrl)
            process = pb.start()
            running = true
            Log.i(TAG, "frppc process started (direct, id=$clientId)")
            startOutputReader()
            return StartResult(true)
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("error=13") || msg.contains("EACCES") || msg.contains("permission denied")) {
                Log.w(TAG, "Direct exec failed (noexec), retrying via linker64: $msg")
                return startViaLinker(binary, clientId, clientSecret, rpcUrl)
            }
            Log.e(TAG, "Failed to start frppc: $msg", e)
            running = false
            return StartResult(false, "Process error: $msg")
        }
    }

    /**
     * Retry execution via the Android dynamic linker.
     *
     * execve("/system/bin/linker64", ["linker64", "/path/to/binary"])
     * executes the linker (on exec-enabled /system/) and the linker
     * reads the binary via open+mmap rather than execve.
     */
    private fun startViaLinker(
        binary: File, clientId: String, clientSecret: String, rpcUrl: String
    ): StartResult {
        val linker = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) {
            "/system/bin/linker64"
        } else {
            "/system/bin/linker"
        }

        val linkerFile = File(linker)
        if (!linkerFile.exists()) {
            return StartResult(false, "Linker not found: $linker")
        }

        try {
            val pb = ProcessBuilder(linker, binary.absolutePath)
            configureProcess(pb, clientId, clientSecret, rpcUrl)
            process = pb.start()
            running = true
            Log.i(TAG, "frppc process started (linker64, id=$clientId)")
            startOutputReader()
            return StartResult(true)
        } catch (e: Exception) {
            Log.e(TAG, "Linker exec failed: ${e.message}", e)
            running = false
            return StartResult(false, "Linker exec error: ${e.message}")
        }
    }

    private fun configureProcess(pb: ProcessBuilder, clientId: String, clientSecret: String, rpcUrl: String) {
        pb.directory(context.filesDir)
        pb.redirectErrorStream(true)
        pb.environment().putAll(mapOf(
            "CLIENT_ID" to clientId,
            "CLIENT_SECRET" to clientSecret,
            "CLIENT_RPC_URL" to rpcUrl,
            "CLIENT_TLS_RPC" to "false",
            "CLIENT_TLS_INSECURE_SKIP_VERIFY" to "true"
        ))
    }

    private fun startOutputReader() {
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
    }

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

    fun cleanup() {
        stop()
        File(context.filesDir, BINARY_NAME).delete()
    }
}
