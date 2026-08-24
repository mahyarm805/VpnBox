package com.vpnbox.core

import android.content.Context
import android.util.Log
import com.vpnbox.data.model.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class SingBoxService(private val context: Context) {

    companion object {
        private const val TAG = "SingBoxService"
        private const val CONFIG_FILE = "sing-box-config.json"
        private const val LOG_FILE = "sing-box.log"
    }

    private var isRunning = false
    private var currentProcess: Process? = null

    suspend fun start(config: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val configFile = File(context.filesDir, CONFIG_FILE)
            FileOutputStream(configFile).use { fos ->
                fos.write(config.toByteArray())
            }

            val logFile = File(context.filesDir, LOG_FILE)

            val processBuilder = ProcessBuilder(
                "sing-box", "run", "-c", configFile.absolutePath
            )
            processBuilder.redirectErrorStream(true)
            processBuilder.redirectOutput(logFile)
            processBuilder.directory(context.filesDir)

            currentProcess = processBuilder.start()
            isRunning = true
            Log.d(TAG, "Sing-box started successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start sing-box", e)
            false
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        try {
            currentProcess?.destroy()
            currentProcess = null
            isRunning = false
            Log.d(TAG, "Sing-box stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop sing-box", e)
        }
    }

    fun isRunning(): Boolean = isRunning

    suspend fun getStatus(): String = withContext(Dispatchers.IO) {
        try {
            val logFile = File(context.filesDir, LOG_FILE)
            if (logFile.exists()) {
                logFile.readText().takeLast(1000)
            } else {
                "No logs available"
            }
        } catch (e: Exception) {
            "Error reading logs: ${e.message}"
        }
    }
}
