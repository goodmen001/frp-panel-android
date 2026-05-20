package com.frppanel.android

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Manages the frpc binary lifecycle.
 *
 * The frpc binary is bundled in APK assets/ and extracted on first run.
 * Config is written to internal storage and the binary is executed as a subprocess.
 */
class FrpcProcess(private val context: Context) {

    companion object {
        private const val TAG = "FrpcProcess"
        private const val BINARY_NAME = "frpc"
        private const val CONFIG_NAME = "frpc.json"
    }

    private var process: Process? = null
    private var binaryFile: File? = null
    private var configFile: File? = null
    private var running = false

    data class ProcessStatus(
        val running: Boolean,
        val pid: Int?
    )

    /**
     * Extract the frpc binary from assets to internal storage.
     * Returns the binary file, or null on failure.
     */
    fun extractBinary(): File? {
        val binary = File(context.filesDir, BINARY_NAME)
        if (binary.exists() && binary.canExecute()) {
            Log.i(TAG, "Binary already extracted: ${binary.absolutePath}")
            binaryFile = binary
            return binary
        }

        try {
            context.assets.open(BINARY_NAME).use { input ->
                FileOutputStream(binary).use { output ->
                    input.copyTo(output)
                }
            }
            binary.setExecutable(true)
            Log.i(TAG, "Binary extracted: ${binary.absolutePath}")
            binaryFile = binary
            return binary
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract binary: ${e.message}", e)
            return null
        }
    }

    /**
     * Write frpc config to internal storage.
     */
    fun writeConfig(configJson: String): File? {
        val config = File(context.filesDir, CONFIG_NAME)
        return try {
            config.writeText(configJson)
            Log.i(TAG, "Config written: ${config.absolutePath}")
            configFile = config
            config
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write config: ${e.message}", e)
            null
        }
    }

    /**
     * Start the frpc binary as a subprocess.
     * The binary reads config from the given file.
     */
    fun start(binary: File, config: File): Boolean {
        if (running) {
            Log.w(TAG, "Already running")
            return false
        }

        try {
            val pb = ProcessBuilder(
                binary.absolutePath,
                "-c", config.absolutePath
            )
            pb.directory(context.filesDir)
            pb.redirectErrorStream(true)

            process = pb.start()
            running = true
            Log.i(TAG, "frpc process started (pid: ${process?.pid()})")

            // Read stdout in background
            Thread {
                try {
                    process?.inputStream?.bufferedReader()?.use { reader ->
                        reader.lines().forEach { line ->
                            Log.d(TAG, "frpc: $line")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "frpc output reader ended: ${e.message}")
                }
                running = false
                Log.i(TAG, "frpc process ended")
            }.apply {
                isDaemon = true
                name = "frpc-stdout"
            }.start()

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start frpc: ${e.message}", e)
            running = false
            return false
        }
    }

    /**
     * Stop the frpc process gracefully (SIGTERM), force after timeout.
     */
    fun stop() {
        val proc = process ?: return
        running = false
        try {
            proc.destroy() // sends SIGTERM
            Log.i(TAG, "Sent SIGTERM to frpc")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping frpc: ${e.message}")
        }
        process = null
    }

    fun isRunning(): Boolean = running

    fun getStatus(): ProcessStatus {
        val proc = process
        return ProcessStatus(
            running = running && proc?.isAlive == true,
            pid = if (running && proc?.isAlive == true) proc.pid() else null
        )
    }

    /**
     * Clean up all frpc files (binary, config).
     */
    fun cleanup() {
        stop()
        File(context.filesDir, BINARY_NAME).delete()
        File(context.filesDir, CONFIG_NAME).delete()
        Log.i(TAG, "Cleanup complete")
    }
}
