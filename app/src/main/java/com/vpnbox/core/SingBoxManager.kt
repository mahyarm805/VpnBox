package com.vpnbox.core

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages the sing-box binary lifecycle: locate, download, verify, test.
 *
 * Binary search priority:
 *   1. nativeLibraryDir/libsing-box.so  (bundled in APK via CI)
 *   2. getDir("bin")/sing-box           (downloaded at runtime)
 *   3. /data/local/tmp/sing-box         (adb pushed)
 *
 * Never looks in filesDir — Android 11+ mounts it noexec.
 */
object SingBoxManager {

    private const val TAG = "SingBoxManager"
    private const val SING_BOX_VERSION = "1.11.4"

    // ── Data classes ──────────────────────────────────────────────

    data class CoreDiagnostics(
        val androidVersion: Int,
        val sdkVersion: Int,
        val deviceAbi: String,
        val nativeLibPath: String,
        val nativeLibExists: Boolean,
        val nativeLibCanExecute: Boolean,
        val nativeLibSize: Long,
        val binDirPath: String,
        val binDirExists: Boolean,
        val binPath: String,
        val binExists: Boolean,
        val binCanRead: Boolean,
        val binCanExecute: Boolean,
        val binSize: Long,
        val activePath: String?,
        val singBoxVersion: String?,
        val isInstalled: Boolean
    )

    /** Backward-compatible install info (used by DebugScreen). */
    data class InstallInfo(
        val installed: Boolean,
        val version: String = "",
        val path: String = "",
        val architecture: String = "",
        val sizeBytes: Long = 0
    )

    // ── ABI / triplet mapping ────────────────────────────────────

    private val deviceAbi: String
        get() = if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0]
                else Build.CPU_ABI

    private fun abiToTriplet(abi: String): String = when (abi) {
        "arm64-v8a" -> "android-arm64"
        "armeabi-v7a", "armeabi" -> "android-arm"
        "x86_64" -> "android-amd64"
        "x86" -> "android-x86"
        else -> "android-arm64"
    }

    // ── Public API ────────────────────────────────────────────────

    /**
     * Returns true when any copy of sing-box is found and executable.
     */
    fun isInstalled(context: Context): Boolean = findBinary(context) != null

    /**
     * Absolute path to the best sing-box binary, or null.
     */
    fun getBinaryPath(context: Context): String? = findBinary(context)

    /**
     * Comprehensive diagnostic snapshot — useful for troubleshooting
     * VPN core issues on diverse Android devices.
     */
    fun getDiagnostics(context: Context): CoreDiagnostics {
        // Native lib check
        val nativeLibPath = "${context.applicationInfo.nativeLibraryDir}/libsing-box.so"
        val nativeLibFile = File(nativeLibPath)

        // getDir("bin") check
        val binDir = context.getDir("bin", Context.MODE_PRIVATE)
        val binPath = "${binDir.absolutePath}/sing-box"
        val binFile = File(binPath)

        val activePath = findBinary(context)
        val version = activePath?.let { queryVersion(it) }

        return CoreDiagnostics(
            androidVersion = Build.VERSION.SDK_INT,
            sdkVersion = Build.VERSION.SDK_INT,
            deviceAbi = deviceAbi,
            nativeLibPath = nativeLibPath,
            nativeLibExists = nativeLibFile.exists(),
            nativeLibCanExecute = nativeLibFile.canExecute(),
            nativeLibSize = if (nativeLibFile.exists()) nativeLibFile.length() else 0L,
            binDirPath = binDir.absolutePath,
            binDirExists = binDir.exists(),
            binPath = binPath,
            binExists = binFile.exists(),
            binCanRead = binFile.canRead(),
            binCanExecute = binFile.canExecute(),
            binSize = if (binFile.exists()) binFile.length() else 0L,
            activePath = activePath,
            singBoxVersion = version,
            isInstalled = activePath != null
        )
    }

    /**
     * Backward-compatible: returns [InstallInfo] derived from [CoreDiagnostics].
     */
    suspend fun getInstallInfo(context: Context): InstallInfo = withContext(Dispatchers.IO) {
        val diag = getDiagnostics(context)
        val path = diag.activePath ?: return@withContext InstallInfo(installed = false)
        val file = File(path)
        InstallInfo(
            installed = true,
            version = diag.singBoxVersion ?: "unknown",
            path = path,
            architecture = diag.deviceAbi,
            sizeBytes = if (file.exists()) file.length() else 0L
        )
    }

    // ── Download ──────────────────────────────────────────────────

    @Volatile
    private var isDownloading = false

    /**
     * Download sing-box from GitHub releases into `getDir("bin")`.
     *
     * @param onProgress  0–100 progress callback
     * @return absolute path on success, null on failure
     */
    suspend fun download(
        context: Context,
        onProgress: (Float) -> Unit = {}
    ): String? = withContext(Dispatchers.IO) {
        // Already installed — skip
        if (isInstalled(context)) {
            Log.d(TAG, "sing-box already installed, skipping download")
            onProgress(100f)
            return@withContext findBinary(context)
        }

        // Another coroutine is downloading — wait
        if (isDownloading) {
            Log.d(TAG, "Download already in progress, waiting…")
            while (isDownloading) delay(500)
            onProgress(100f)
            return@withContext findBinary(context)
        }

        isDownloading = true
        try {
            val abi = deviceAbi
            val triplet = abiToTriplet(abi)
            Log.d(TAG, "Device ABI=$abi → triplet=$triplet")

            val fileName = "sing-box-${SING_BOX_VERSION}-${triplet}.tar.gz"
            val url = "https://github.com/SagerNet/sing-box/releases/download/v${SING_BOX_VERSION}/$fileName"
            Log.d(TAG, "Download URL: $url")

            onProgress(2f)

            // ── Download ──────────────────────────────────────────
            val tmpFile = File(context.cacheDir, "sing-box-download.tar.gz")
            val bytesDownloaded = downloadFile(url, tmpFile) { pct ->
                onProgress(2f + pct * 0.78f) // 2% → 80%
            }
            if (bytesDownloaded <= 0) {
                Log.e(TAG, "Download produced empty file")
                return@withContext null
            }
            onProgress(80f)

            // ── Extract ───────────────────────────────────────────
            val extractDir = File(context.cacheDir, "sing-box-extract").apply {
                deleteRecursively()
                mkdirs()
            }
            val tarProcess = ProcessBuilder(
                "tar", "xzf", tmpFile.absolutePath,
                "-C", extractDir.absolutePath, "--strip-components=1"
            ).redirectErrorStream(true).start()
            val tarExit = tarProcess.waitFor()

            if (tarExit != 0) {
                val err = BufferedReader(InputStreamReader(tarProcess.inputStream)).readText()
                Log.e(TAG, "tar extraction failed (exit=$tarExit): $err")
                return@withContext null
            }
            onProgress(85f)

            // ── Locate extracted binary ───────────────────────────
            val binary = findFileRecursive(extractDir, "sing-box")
            if (binary == null) {
                Log.e(TAG, "sing-box binary not found after extraction")
                Log.d(TAG, "Extracted contents: ${extractDir.listFiles()?.map { it.name }}")
                return@withContext null
            }

            // ── Copy to getDir("bin") ─────────────────────────────
            val binDir = context.getDir("bin", Context.MODE_PRIVATE)
            binDir.mkdirs()
            val targetFile = File(binDir, "sing-box")
            binary.copyTo(targetFile, overwrite = true)
            onProgress(90f)

            // ── chmod 755 ─────────────────────────────────────────
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "755", targetFile.absolutePath)).waitFor()
                Log.d(TAG, "chmod 755 applied to: ${targetFile.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "chmod failed: ${e.message}")
            }

            // ── Verify executable ─────────────────────────────────
            if (!targetFile.canExecute()) {
                Log.w(TAG, "canExecute()=false after chmod in getDir(bin); trying /data/local/tmp")
                val tmpBin = File("/data/local/tmp/sing-box")
                try {
                    binary.copyTo(tmpBin, overwrite = true)
                    Runtime.getRuntime().exec(arrayOf("chmod", "755", tmpBin.absolutePath)).waitFor()
                    if (tmpBin.canExecute()) {
                        Log.d(TAG, "Binary executable at /data/local/tmp/sing-box")
                        cleanup(tmpFile, extractDir)
                        onProgress(100f)
                        return@withContext tmpBin.absolutePath
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Fallback to /data/local/tmp failed: ${e.message}")
                }
                // Not executable anywhere — still return getDir path (caller decides)
                Log.e(TAG, "Binary not executable at any location; returning getDir path anyway")
            }

            cleanup(tmpFile, extractDir)
            onProgress(100f)
            Log.d(TAG, "sing-box installed at: ${targetFile.absolutePath}")
            targetFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            null
        } finally {
            isDownloading = false
        }
    }

    // ── Test ──────────────────────────────────────────────────────

    /**
     * Run `sing-box version` and report the result.
     * @return Pair(success, output-or-error)
     */
    suspend fun testCore(context: Context): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val path = findBinary(context)
            ?: return@withContext Pair(false, "sing-box binary not found — run download() first")

        try {
            val process = ProcessBuilder(path, "version")
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).readText().trim()
            val exitCode = process.waitFor()
            Pair(
                exitCode == 0,
                output.ifEmpty { "Exit code: $exitCode" }
            )
        } catch (e: Exception) {
            Pair(false, "Execution error: ${e.message}")
        }
    }

    // ── Private helpers ───────────────────────────────────────────

    /**
     * Locate the best sing-box binary.
     *
     * Priority:
     *   1. nativeLibraryDir/libsing-box.so  — bundled in APK, always executable
     *   2. getDir("bin")/sing-box           — downloaded at runtime
     *   3. /data/local/tmp/sing-box         — adb pushed (debug builds)
     *
     * Deliberately never checks filesDir — Android 11+ enforces noexec there.
     */
    private fun findBinary(context: Context): String? {
        // Priority 1: bundled native library
        val nativeLibPath = "${context.applicationInfo.nativeLibraryDir}/libsing-box.so"
        val nativeLib = File(nativeLibPath)
        if (nativeLib.exists() && nativeLib.canExecute()) {
            Log.d(TAG, "Found sing-box at native lib: $nativeLibPath (${nativeLib.length()} bytes)")
            return nativeLibPath
        }

        // Priority 2: getDir("bin") — downloaded
        val binDir = context.getDir("bin", Context.MODE_PRIVATE)
        val binPath = "${binDir.absolutePath}/sing-box"
        val binFile = File(binPath)
        if (binFile.exists() && binFile.canExecute()) {
            Log.d(TAG, "Found sing-box at bin dir: $binPath (${binFile.length()} bytes)")
            return binPath
        }

        // Priority 3: /data/local/tmp — adb pushed
        val tmpPath = "/data/local/tmp/sing-box"
        val tmpFile = File(tmpPath)
        if (tmpFile.exists() && tmpFile.canExecute()) {
            Log.d(TAG, "Found sing-box at tmp: $tmpPath (${tmpFile.length()} bytes)")
            return tmpPath
        }

        // Log diagnostic detail when nothing is found
        Log.w(TAG, "sing-box not found in any location:")
        Log.w(TAG, "  nativeLib=$nativeLibPath exists=${nativeLib.exists()} exec=${nativeLib.canExecute()}")
        Log.w(TAG, "  binDir=$binPath exists=${binFile.exists()} exec=${binFile.canExecute()}")
        Log.w(TAG, "  tmp=$tmpPath exists=${tmpFile.exists()} exec=${tmpFile.canExecute()}")

        return null
    }

    /**
     * Query `sing-box version` and extract version string.
     * Returns null on any failure.
     */
    private fun queryVersion(binaryPath: String): String? = try {
        val process = ProcessBuilder(binaryPath, "version")
            .redirectErrorStream(true)
            .start()
        val output = BufferedReader(InputStreamReader(process.inputStream)).readText().trim()
        process.waitFor()
        output.lines().firstOrNull()?.let { line ->
            // Typical output: "sing-box version 1.11.4"
            val parts = line.split("\\s+".toRegex())
            if (parts.size >= 3) parts.last() else line
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to query version from $binaryPath: ${e.message}")
        null
    }

    /**
     * Download a file with progress callback. Progress is 0.0–1.0.
     * Returns total bytes written.
     */
    private fun downloadFile(
        urlString: String,
        target: File,
        onProgress: (Float) -> Unit = {}
    ): Long {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.connect()

        val contentLength = connection.contentLength.toLong()
        val estimatedSize = if (contentLength > 0) contentLength else 15_000_000L
        Log.d(TAG, "Content-Length: $contentLength bytes (estimated total: $estimatedSize)")

        val inputStream = connection.inputStream
        val outputStream = target.outputStream()

        val buffer = ByteArray(8192)
        var bytesRead: Int
        var totalRead = 0L

        try {
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                onProgress(totalRead.toFloat() / estimatedSize)
            }
        } finally {
            outputStream.close()
            inputStream.close()
            connection.disconnect()
        }

        Log.d(TAG, "Downloaded $totalRead bytes to ${target.absolutePath}")
        return totalRead
    }

    /**
     * Recursively search [dir] for a file named [name].
     */
    private fun findFileRecursive(dir: File, name: String): File? {
        if (dir.isFile && dir.name == name) return dir
        dir.listFiles()?.forEach { child ->
            findFileRecursive(child, name)?.let { return it }
        }
        return null
    }

    /**
     * Remove temporary download / extraction files.
     */
    private fun cleanup(vararg files: File) {
        files.forEach { f ->
            try {
                if (f.isDirectory) f.deleteRecursively() else f.delete()
            } catch (e: Exception) {
                Log.w(TAG, "Cleanup failed for ${f.absolutePath}: ${e.message}")
            }
        }
    }
}
