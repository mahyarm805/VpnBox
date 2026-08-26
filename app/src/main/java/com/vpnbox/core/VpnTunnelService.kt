package com.vpnbox.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.vpnbox.MainActivity
import com.vpnbox.R
import com.vpnbox.data.db.AppDatabase
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader

class VpnTunnelService : VpnService() {

    companion object {
        private const val TAG = "VpnTunnelService"
        private const val CHANNEL_ID = "vpn_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_CONNECT = "com.vpnbox.CONNECT"
        const val ACTION_DISCONNECT = "com.vpnbox.DISCONNECT"

        private var instance: VpnTunnelService? = null
        fun getInstance(): VpnTunnelService? = instance

        // Debug/expose state for viewer
        private var lastConfig: String? = null
        private val coreLogs = StringBuilder()
        private var coreRunning = false
        private var lastError: String = ""

        fun getLastConfig(): String? = lastConfig
        fun getCoreLogs(): String = synchronized(coreLogs) { coreLogs.toString() }
        fun isCoreRunning(): Boolean = coreRunning
        fun getLastError(): String = lastError

        fun clearLogs() {
            synchronized(coreLogs) { coreLogs.clear() }
            lastError = ""
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var singBoxProcess: Process? = null
    private var singBoxBinaryPath: String? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun appendLog(line: String) {
        synchronized(coreLogs) {
            coreLogs.appendLine(line)
            if (coreLogs.length > 65536) {
                val trimmed = coreLogs.toString().substring(coreLogs.length - 32768)
                coreLogs.clear()
                coreLogs.append(trimmed)
            }
        }
    }

    private fun setError(error: String) {
        lastError = error
        Log.e(TAG, "ERROR: $error")
        appendLog("[ERR] $error")
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val serverId = intent.getLongExtra("server_id", 0)
                Log.d(TAG, "ACTION_CONNECT: server_id=$serverId")
                serviceScope.launch { connectWithServer(serverId) }
            }
            ACTION_DISCONNECT -> {
                Log.d(TAG, "ACTION_DISCONNECT")
                disconnectVpn()
            }
            else -> {
                Log.d(TAG, "Unknown action: ${intent?.action}")
            }
        }
        return START_STICKY
    }

    /**
     * Main connect flow: load server → generate config → find/download sing-box → launch.
     */
    private suspend fun connectWithServer(serverId: Long) {
        try {
            clearLogs()
            appendLog("[INFO] Starting VPN connection...")

            // 1. Load server config from Room database
            val serverDao = AppDatabase.getDatabase(applicationContext).serverDao()
            val server = serverDao.getServerById(serverId)
            if (server == null) {
                setError("Server not found (id=$serverId)")
                return
            }
            appendLog("[INFO] Server: ${server.name} (${server.protocol.displayName})")
            appendLog("[INFO] Address: ${server.address}:${server.port}")

            // 2. Generate sing-box config JSON
            val configGenerator = ConfigGenerator()
            val configJson = configGenerator.generateConfig(server)
            lastConfig = configJson
            appendLog("[INFO] Config generated (${configJson.length} bytes)")

            // 3. Write config to filesDir
            val configFile = File(filesDir, "sing-box-config.json")
            withContext(Dispatchers.IO) {
                FileOutputStream(configFile).use { fos ->
                    fos.write(configJson.toByteArray(Charsets.UTF_8))
                    fos.flush()
                }
            }
            appendLog("[INFO] Config written to: ${configFile.absolutePath}")

            // 4. Find or download sing-box binary
            appendLog("[INFO] Searching for sing-box binary...")
            val binaryPath = findSingBoxBinary()
            if (binaryPath == null) {
                setError("sing-box binary not found. Go to Debug → Download sing-box")
                return
            }
            singBoxBinaryPath = binaryPath
            appendLog("[INFO] sing-box found at: $binaryPath")

            // 5. Build TUN interface (don't create it yet — let sing-box manage it)
            appendLog("[INFO] Starting sing-box process...")

            // 6. Launch sing-box process
            val cmd = listOf(binaryPath, "run", "-c", configFile.absolutePath)
            Log.d(TAG, "Launching: ${cmd.joinToString(" ")}")

            val processBuilder = ProcessBuilder(cmd)
            processBuilder.directory(filesDir)
            processBuilder.environment()["TMPDIR"] = filesDir.absolutePath

            val process = try {
                processBuilder.start()
            } catch (e: Exception) {
                setError("Failed to start sing-box: ${e.message}")
                return
            }
            singBoxProcess = process
            coreRunning = true

            // Capture stdout asynchronously
            serviceScope.launch {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        reader.lines().forEach { line ->
                            Log.d(TAG, "[stdout] $line")
                            appendLog("[OUT] $line")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "stdout reader error", e)
                }
            }

            // Capture stderr asynchronously — this is where errors appear!
            serviceScope.launch {
                try {
                    BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                        reader.lines().forEach { line ->
                            Log.e(TAG, "[stderr] $line")
                            appendLog("[ERR] $line")
                            // Set the first error as the primary error message
                            if (lastError.isEmpty()) {
                                lastError = line
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "stderr reader error", e)
                }
            }

            // Monitor process exit in background
            serviceScope.launch {
                val exitCode = process.waitFor()
                Log.w(TAG, "sing-box exited with code: $exitCode")
                coreRunning = false
                appendLog("[EXIT] sing-box exited with code $exitCode")

                if (exitCode != 0 && lastError.isEmpty()) {
                    lastError = "sing-box exited with code $exitCode"
                }

                // If process dies unexpectedly, clean up
                withContext(Dispatchers.Main) {
                    disconnectVpn()
                }
            }

            showNotification("Connected to ${server.name}")
            Log.i(TAG, "VPN connected to ${server.name} via sing-box")

        } catch (e: CancellationException) {
            Log.e(TAG, "Connect cancelled", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect VPN", e)
            setError("Connection error: ${e.message}")
            disconnectVpn()
        }
    }

    /**
     * Search for sing-box binary in multiple locations.
     * Returns absolute path if found, null otherwise.
     */
    private suspend fun findSingBoxBinary(): String? {
        // Priority 0: SingBoxManager managed install
        if (SingBoxManager.isInstalled(applicationContext)) {
            val path = SingBoxManager.getBinaryPath(applicationContext)
            Log.d(TAG, "Found sing-box via SingBoxManager: $path")
            return path
        }

        // Priority 1: Native libs dir (shipped with APK)
        val nativeLibPath = "${applicationInfo.nativeLibraryDir}/libsing-box.so"
        if (File(nativeLibPath).let { it.exists() && it.canExecute() }) {
            Log.d(TAG, "Found sing-box at native lib: $nativeLibPath")
            return nativeLibPath
        }

        // Priority 2: App filesDir (manually placed)
        val filesDirPath = "${filesDir.absolutePath}/sing-box"
        if (File(filesDirPath).let { it.exists() && it.canExecute() }) {
            Log.d(TAG, "Found sing-box at filesDir: $filesDirPath")
            return filesDirPath
        }

        // Priority 3: /data/local/tmp (adb pushed for testing)
        val tmpPath = "/data/local/tmp/sing-box"
        if (File(tmpPath).let { it.exists() && it.canExecute() }) {
            Log.d(TAG, "Found sing-box at tmp: $tmpPath")
            return tmpPath
        }

        // Priority 4: Try 'sing-box' on system PATH
        try {
            val whichProcess = ProcessBuilder("which", "sing-box")
                .redirectErrorStream(true)
                .start()
            val exitCode = whichProcess.waitFor()
            if (exitCode == 0) {
                val path = BufferedReader(
                    InputStreamReader(whichProcess.inputStream)
                ).readText().trim()
                if (path.isNotEmpty()) {
                    Log.d(TAG, "Found sing-box on PATH: $path")
                    return path
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "which sing-box failed: ${e.message}")
        }

        // sing-box not found — try downloading
        appendLog("[INFO] sing-box not found, downloading...")
        try {
            val downloadPath = SingBoxManager.download(applicationContext) { progress ->
                val pct = (progress * 100).toInt()
                Log.d(TAG, "Download progress: $pct%")
                appendLog("[INFO] Download progress: $pct%")
            }
            if (downloadPath != null) {
                Log.i(TAG, "sing-box downloaded to: $downloadPath")
                appendLog("[INFO] sing-box downloaded to: $downloadPath")
                return downloadPath
            } else {
                setError("sing-box download failed. Check network connection.")
                return null
            }
        } catch (e: Exception) {
            setError("sing-box download error: ${e.message}")
            return null
        }
    }

    /**
     * Disconnect: kill sing-box, close TUN, clear state.
     */
    private fun disconnectVpn() {
        try {
            // Kill sing-box process
            singBoxProcess?.let { proc ->
                Log.d(TAG, "Destroying sing-box process")
                proc.destroy()
                try {
                    if (!proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                        Log.w(TAG, "sing-box did not exit in 2s, destroyingForcibly")
                        proc.destroyForcibly()
                    }
                } catch (_: Exception) {}
            }
            singBoxProcess = null
            coreRunning = false

            // Close TUN interface
            vpnInterface?.let { fd ->
                Log.d(TAG, "Closing TUN interface (fd=${fd.fd})")
                fd.close()
            }
            vpnInterface = null

            hideNotification()
            Log.d(TAG, "VPN fully disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect", e)
        }
    }

    // ---- Notification helpers ----

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows VPN connection status"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(text: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WhiteHole")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun hideNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.cancel(NOTIFICATION_ID)
    }

    // ---- Lifecycle ----

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        super.onDestroy()
        disconnectVpn()
        instance = null
        serviceScope.cancel()
    }

    override fun onRevoke() {
        Log.d(TAG, "onRevoke")
        disconnectVpn()
        super.onRevoke()
    }
}
