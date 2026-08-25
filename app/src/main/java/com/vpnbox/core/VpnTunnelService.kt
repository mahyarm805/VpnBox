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

        fun getLastConfig(): String? = lastConfig
        fun getCoreLogs(): String = synchronized(coreLogs) { coreLogs.toString() }
        fun isCoreRunning(): Boolean = coreRunning
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var singBoxProcess: Process? = null
    private var singBoxBinaryPath: String? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
     * Main connect flow: load server from DB → generate config → write config →
     * establish TUN → find & launch sing-box binary.
     */
    private suspend fun connectWithServer(serverId: Long) {
        try {
            // 1. Load server config from Room database
            val serverDao = AppDatabase.getDatabase(applicationContext).serverDao()
            val server = serverDao.getServerById(serverId)
            if (server == null) {
                Log.e(TAG, "Server not found for id=$serverId, aborting connect")
                return
            }
            Log.d(TAG, "Loaded server: ${server.name} (${server.protocol})")

            // 2. Generate sing-box config JSON
            val configGenerator = ConfigGenerator()
            val configJson = configGenerator.generateConfig(server)
            lastConfig = configJson
            Log.d(TAG, "Generated sing-box config (${configJson.length} bytes)")

            // 3. Write config to filesDir/sing-box-config.json
            val configFile = File(filesDir, "sing-box-config.json")
            withContext(Dispatchers.IO) {
                FileOutputStream(configFile).use { fos ->
                    fos.write(configJson.toByteArray(Charsets.UTF_8))
                    fos.flush()
                }
            }
            Log.d(TAG, "Config written to: ${configFile.absolutePath}")

            // 4. Build and establish TUN interface
            val tunFd = buildTunInterface(server.name)
            if (tunFd == null) {
                Log.e(TAG, "Failed to establish TUN interface, aborting")
                return
            }
            vpnInterface = tunFd
            Log.d(TAG, "TUN interface established (fd=${tunFd.fd})")

            // 5. Find sing-box binary
            val binaryPath = findSingBoxBinary()
            if (binaryPath == null) {
                Log.e(TAG, "sing-box binary NOT FOUND anywhere. Checked:")
                Log.e(TAG, "  - ${applicationInfo.nativeLibraryDir}/libsing-box.so")
                Log.e(TAG, "  - ${filesDir.absolutePath}/sing-box")
                Log.e(TAG, "  - /data/local/tmp/sing-box")
                Log.e(TAG, "  - 'sing-box' on PATH")
                Log.e(TAG, "UI will show 'connected' but traffic will NOT route through sing-box.")
                // Still show connected for UI testing
                coreRunning = false
                showNotification("Connected to ${server.name} (core missing)")
                return
            }
            singBoxBinaryPath = binaryPath
            Log.i(TAG, "sing-box binary found at: $binaryPath")

            // 6. Launch sing-box process with captured output
            val configFileCanonical = File(filesDir, "sing-box-config.json")
            val cmd = listOf(binaryPath, "run", "-c", configFileCanonical.absolutePath)
            Log.d(TAG, "Launching: ${cmd.joinToString(" ")}")

            val processBuilder = ProcessBuilder(cmd)
            processBuilder.directory(filesDir)
            processBuilder.environment()["TMPDIR"] = filesDir.absolutePath

            val process = processBuilder.start()
            singBoxProcess = process
            coreRunning = true

            // Capture stdout asynchronously
            serviceScope.launch {
                try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        reader.lines().forEach { line ->
                            Log.d(TAG, "[sing-box stdout] $line")
                            synchronized(coreLogs) {
                                coreLogs.appendLine("[OUT] $line")
                                // Keep log buffer bounded (~500 lines)
                                if (coreLogs.length > 65536) {
                                    val trimmed = coreLogs.toString().substring(
                                        coreLogs.length - 32768
                                    )
                                    coreLogs.clear()
                                    coreLogs.append(trimmed)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "stdout reader error", e)
                }
            }

            // Capture stderr asynchronously
            serviceScope.launch {
                try {
                    BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                        reader.lines().forEach { line ->
                            Log.e(TAG, "[sing-box stderr] $line")
                            synchronized(coreLogs) {
                                coreLogs.appendLine("[ERR] $line")
                                if (coreLogs.length > 65536) {
                                    val trimmed = coreLogs.toString().substring(
                                        coreLogs.length - 32768
                                    )
                                    coreLogs.clear()
                                    coreLogs.append(trimmed)
                                }
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
                Log.w(TAG, "sing-box process exited with code: $exitCode")
                coreRunning = false
                synchronized(coreLogs) {
                    coreLogs.appendLine("[EXIT] sing-box exited with code $exitCode")
                }
                // If process dies unexpectedly, clean up
                withContext(Dispatchers.Main) {
                    disconnectVpn()
                }
            }

            showNotification("Connected to ${server.name}")
            Log.i(TAG, "VPN fully connected to ${server.name} via sing-box")

        } catch (e: CancellationException) {
            Log.e(TAG, "Connect cancelled", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect VPN", e)
            disconnectVpn()
        }
    }

    /**
     * Build the TUN interface using Android's VpnService.Builder.
     * Returns the file descriptor on success, null on failure.
     */
    private fun buildTunInterface(serverName: String): ParcelFileDescriptor? {
        val builder = Builder()
        builder.setSession("VpnBox - $serverName")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("8.8.4.4")
            .setMtu(1500)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        return builder.establish()
    }

    /**
     * Search for the sing-box binary in multiple locations.
     * Returns the absolute path if found, null otherwise.
     */
    private fun findSingBoxBinary(): String? {
        // Priority 1: Native libs dir (shipped with APK)
        val nativeLibPath = "${applicationInfo.nativeLibraryDir}/libsing-box.so"
        val nativeLibFile = File(nativeLibPath)
        if (nativeLibFile.exists() && nativeLibFile.canExecute()) {
            Log.d(TAG, "Found sing-box at native lib: $nativeLibPath")
            return nativeLibPath
        }

        // Priority 2: App filesDir (manually placed)
        val filesDirPath = "${filesDir.absolutePath}/sing-box"
        val filesDirFile = File(filesDirPath)
        if (filesDirFile.exists() && filesDirFile.canExecute()) {
            Log.d(TAG, "Found sing-box at filesDir: $filesDirPath")
            return filesDirPath
        }

        // Priority 3: /data/local/tmp (adb pushed for testing)
        val tmpPath = "/data/local/tmp/sing-box"
        val tmpFile = File(tmpPath)
        if (tmpFile.exists() && tmpFile.canExecute()) {
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

        return null
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
                // Give it a moment to die, then force-kill if needed
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
