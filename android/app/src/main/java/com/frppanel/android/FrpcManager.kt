package com.frppanel.android

import android.util.Log
import frpcengine.FrpcEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Singleton wrapper around the gomobile FrpcEngine.
 * Provides coroutine-friendly suspend functions for the UI layer.
 */
object FrpcManager {
    private const val TAG = "FrpcManager"

    private val engine: FrpcEngine = FrpcEngine()

    data class Status(
        val running: Boolean,
        val text: String
    )

    fun getStatus(): Status = Status(
        running = engine.isRunning,
        text = engine.status
    )

    suspend fun start(
        masterUrl: String,
        username: String,
        password: String,
        clientId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "starting engine masterUrl=$masterUrl clientId=$clientId")
            val err = engine.start(masterUrl, username, password, clientId)
            if (err != null) {
                Log.e(TAG, "start failed: ${err.message}")
                Result.failure(Exception(err.message))
            } else {
                Log.i(TAG, "engine started")
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Log.e(TAG, "start exception", e)
            Result.failure(e)
        }
    }

    suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            engine.stop()
            Log.i(TAG, "engine stopped")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "stop exception", e)
            Result.failure(e)
        }
    }
}
