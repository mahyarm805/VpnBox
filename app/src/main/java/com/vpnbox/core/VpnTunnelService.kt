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

        // ── Debug / state exposed for viewer ────────────────────────────
        private var lastConfig: String? = null
        private val coreLogs = StringBuilder()
        private var coreRunning = false
        private var lastError: String = ""

        fun getLastConfig(): String? = lastConfig
        fun getCoreLogs(): String = synchronized(coreLogs) { coreLogs.toString() }
        fun isCoreRunning(): Boolean = coreRunning
        fun getLastError(): String = lastError

        fun getDiagnostics(): SingBoxManager.CoreDiagnostics = _cachedDiagnostics

        fun clearLogs() {
            synchronized(coreLogs) { coreLogs.clear() }
            lastError = ""
        }

        /**
         * Cached diagnostics snapshot, refreshed on each connect.
         * Defaults to "not installed" so callers always get a valid object.
         */
        private var _cachedDiagnostics = SingBoxManager.CoreDiagnostics()
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var singBoxProcess: Process? = null
    private var singBoxBinaryPath: String? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Logging helpers ──────────────────────────────────────────────────

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

    // ── Lifecycle ─────────────────────────────────────────────────────────

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

    // ── Main connect flow ─────────────────────────────────────────────────

    /**
     * Main connect flow with full pre-launch diagnostics:
     *
     *  1. Load server from DB
     *  2. Generate config JSON
     *  3. Write config to filesDir/sing-box-config.json
     *  4. Find sing-box binary (download if missing)
     *  5. PRE-LAUNCH CHECKS (file exists, size > 0, executable)
     *  6. LAUNCH sing-box (ProcessBuilder.start())
     *  7. Verify process is alive IMMEDIATELY after start
     *  8. Capture stdout/stderr, monitor exit
     */
    private suspend fun connectWithServer(serverId: Long) {
        try {
            clearLogs()
            appendLog("[INFO] Starting VPN connection...")

            // ── Step 1: Load server from Room database ──────────────────
            val serverDao = AppDatabase.getDatabase(applicationContext).serverDao()
            val server = serverDao.getServerById(serverId)
            if (server == null) {
                setError("Server not found (id=$serverId)")
                return
            }
            appendLog("[INFO] Server: ${server.name} (${server.protocol.displayName})")
            appendLog("[INFO] Address: ${server.address}:${server.port}")

            // ── Step 2: Generate sing-box config JSON ───────────────────
            val configGenerator = ConfigGenerator()
            val configJson = configGenerator.generateConfig(server)
            lastConfig = configJson
            appendLog("[INFO] Config generated (${configJson.length} bytes)")

            // ── Step 3: Write config to filesDir ────────────────────────
            val configFile = File(filesDir, "sing-box-config.json")
            withContext(Dispatchers.IO) {
                FileOutputStream(configFile).use { fos ->
                    fos.write(configJson.toByteArray(Charsets.UTF_8))
                    fos.flush()
                }
            }
            if (!configFile.exists() || configFile.length() == 0L) {
                setError("Config file write failed: ${configFile.absolutePath} (exists=${configFile.exists()}, size=${configFile.length()})")
                return
            }
            appendLog("[INFO] Config written to: ${configFile.absolutePath} (${configFile.length()} bytes)")

            // ── Step 4: Find or download sing-box binary ────────────────
            appendLog("[INFO] Searching for sing-box binary...")
            val binaryPath = findSingBoxBinary()
            if (binaryPath == null) {
                setError("sing-box binary not available. Go to Debug → Download sing-box")
                return
            }
            singBoxBinaryPath = binaryPath
            appendLog("[INFO] sing-box binary found at: $binaryPath")

            // ── Step 5: PRE-LAUNCH CHECKS ──────────────────────────────
            val preLaunchResult = runPreLaunchChecks(binaryPath, configFile)
            if (preLaunchResult != null) {
                setError(preLaunchResult)
                return
            }
            appendLog("[INFO] Pre-launch checks PASSED")

            // ── Step 5.5: Refresh diagnostics snapshot ──────────────────
            try {
                _cachedDiagnostics = SingBoxManager.getDiagnostics(applicationContext)
            } catch (_: Exception) { }

            // ── Step 6: LAUNCH sing-box process ─────────────────────────
            val cmd = listOf(binaryPath, "run", "-c", configFile.absolutePath)
            Log.d(TAG, "Launching: ${cmd.joinToString(" ")}")
            appendLog("[INFO] Launching: ${cmd.joinToString(" ")}")

            val processBuilder = ProcessBuilder(cmd)
            processBuilder.directory(filesDir)
            processBuilder.environment()["TMPDIR"] = filesDir.absolutePath

            val process = try {
                processBuilder.start()
            } catch (e: Exception) {
                setError("Failed to start sing-box process: ${e::class.simpleName}: ${e.message}")
                return
            }

            // ── Step 7: Verify process is alive IMMEDIATELY ─────────────
            if (!process.isAlive) {
                val exitCode = try { process.exitValue() } catch (_: Exception) { -1 }
                val stderr = try {
                    BufferedReader(InputStreamReader(process.errorStream)).use { it.readText().trim() }
                } catch (_: Exception) { "" }
                val stdout = try {
                    BufferedReader(InputStreamReader(process.inputStream)).use { it.readText().trim() }
                } catch (_: Exception) { "" }
                val detail = buildString {
                    append("sing-box died immediately after start (exit=$exitCode)")
                    if (stderr.isNotEmpty()) append("\nstderr: $stderr")
                    if (stdout.isNotEmpty()) append("\nstdout: $stdout")
                }
                Log.e(TAG, detail)
                setError(detail)
                return
            }
            singBoxProcess = process
            coreRunning = true
            appendLog("[INFO] sing-box process started (pid=${getProcessId(process)})")

            // ── Step 8: CAPTURE OUTPUT + MONITOR EXIT ──────────────────

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

            // Capture stderr asynchronously — this is where errors appear
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
            setError("Connection error: ${e::class.simpleName}: ${e.message}")
            disconnectVpn()
        }
    }

    // ── Pre-launch checks ─────────────────────────────────────────────────

    /**
     * Run comprehensive checks on the sing-box binary and config before attempting launch.
     *
     * Returns null on success (all checks passed), or an error message string on failure.
     */
    private suspend fun runPreLaunchChecks(
        binaryPath: String,
        configFile: File
    ): String? = withContext(Dispatchers.IO) {
        val binaryFile = File(binaryPath)
        val issues = mutableListOf<String>()

        // Check 1: Binary file exists
        if (!binaryFile.exists()) {
            issues.add("Binary file does not exist: $binaryPath")
        }

        // Check 2: Binary file size > 0
        if (binaryFile.exists() && binaryFile.length() == 0L) {
            issues.add("Binary file is empty (0 bytes): $binaryPath")
        }

        // Check 3: Binary is readable
        if (binaryFile.exists() && !binaryFile.canRead()) {
            issues.add("Binary file is not readable: $binaryPath")
        }

        // Check 4: Binary is executable (or make it so)
        if (binaryFile.exists() && !binaryFile.canExecute()) {
            appendLog("[WARN] Binary not executable, attempting chmod 755...")
            Log.w(TAG, "Binary not executable, attempting chmod 755 on: $binaryPath")
            try {
                val chmodProcess = ProcessBuilder("chmod", "755", binaryPath)
                    .redirectErrorStream(true)
                    .start()
                val exitCode = chmodProcess.waitFor()
                if (exitCode != 0) {
                    val err = BufferedReader(InputStreamReader(chmodProcess.errorStream))
                        .use { it.readText().trim() }
                    issues.add("chmod 755 failed (exit=$exitCode): $err")
                }
            } catch (e: Exception) {
                issues.add("chmod 755 threw exception: ${e.message}")
            }

            // Re-check after chmod
            if (!binaryFile.canExecute()) {
                issues.add("Binary still not executable after chmod 755: $binaryPath " +
                    "(Android may restrict execution from this directory — try /data/local/tmp)")
            }
        }

        // Check 5: Config file exists and is non-empty
        if (!configFile.exists()) {
            issues.add("Config file does not exist: ${configFile.absolutePath}")
        } else if (configFile.length() == 0L) {
            issues.add("Config file is empty (0 bytes): ${configFile.absolutePath}")
        }

        // Check 6: Config file is readable
        if (configFile.exists() && !configFile.canRead()) {
            issues.add("Config file is not readable: ${configFile.absolutePath}")
        }

        // Check 7: filesDir is writable (sing-box may need to create temp files)
        if (!filesDir.canWrite()) {
            issues.add("filesDir is not writable: ${filesDir.absolutePath}")
        }

        // Check 8: Basic disk space check — warn if < 1 MB free
        try {
            val stat = android.os.StatFs(filesDir.absolutePath)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            appendLog("[INFO] Available disk: ${availableBytes / 1024} KB")
            if (availableBytes < 1024 * 1024) {
                issues.add("Low disk space: only ${availableBytes / 1024} KB available in ${filesDir.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not check disk space: ${e.message}")
            // Non-fatal — don't add to issues
        }

        if (issues.isNotEmpty()) {
            val report = "Pre-launch checks FAILED:\n${issues.joinToString("\n") { "  • $it" }}"
            Log.e(TAG, report)
            appendLog("[ERR] Pre-launch check failures:")
            issues.forEach { appendLog("[ERR]   • $it") }
            return@withContext report
        }

        // Log diagnostics on success
        appendLog("[INFO] Binary: $binaryPath (${binaryFile.length()} bytes, executable=${binaryFile.canExecute()})")
        appendLog("[INFO] Config: ${configFile.absolutePath} (${configFile.length()} bytes)")
        null // all checks passed
    }

    // ── Find sing-box binary ──────────────────────────────────────────────

    /**
     * Search for sing-box binary in multiple locations.
     * First checks SingBoxManager managed install, then falls back to
     * native libs, filesDir, /data/local/tmp, PATH, and finally download.
     *
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

        // Priority 2: getDir("bin") — downloaded at runtime
        val binDir = getDir("bin", MODE_PRIVATE)
        val binDirPath = "${binDir.absolutePath}/sing-box"
        if (File(binDirPath).let { it.exists() && it.canExecute() }) {
            Log.d(TAG, "Found sing-box at bin dir: $binDirPath")
            return binDirPath
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

        // sing-box not found — try downloading via SingBoxManager
        appendLog("[INFO] sing-box not found in any known location, attempting download...")
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
                setError("sing-box download failed — check network connection")
                return null
            }
        } catch (e: Exception) {
            setError("sing-box download error: ${e::class.simpleName}: ${e.message}")
            return null
        }
    }

    // ── Disconnect ────────────────────────────────────────────────────────

    /**
     * Disconnect: kill sing-box, close TUN, clear state.
     */
    private fun disconnectVpn() {
        try {
            // Kill sing-box process
            singBoxProcess?.let { proc ->
                Log.d(TAG, "Destroying sing-box process")
                appendLog("[INFO] Stopping sing-box process...")
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
            appendLog("[INFO] VPN fully disconnected")
            Log.d(TAG, "VPN fully disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect", e)
        }
    }

    // ── Process utilities ─────────────────────────────────────────────────

    /**
     * Extract PID from a Process object.
     * On Android, Process is a UNIXProcess with a 'pid' field accessible via reflection.
     * Returns -1 if unavailable.
     */
    private fun getProcessId(process: Process): Int {
        return try {
            val pidField = process.javaClass.getDeclaredField("pid")
            pidField.isAccessible = true
            pidField.getInt(process)
        } catch (_: Exception) {
            -1
        }
    }

    // ── Notification helpers ──────────────────────────────────────────────

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

    // ── Lifecycle ─────────────────────────────────────────────────────────

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
