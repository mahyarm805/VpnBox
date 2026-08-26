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
import java.security.MessageDigest

/**
 * Manages the sing-box binary lifecycle: check, download, verify, test.
 */
object SingBoxManager {

    private const val TAG = "SingBoxManager"
    private const val SING_BOX_VERSION = "1.11.4"

    data class InstallInfo(
        val installed: Boolean,
        val version: String = "",
        val path: String = "",
        val architecture: String = "",
        val sizeBytes: Long = 0
    )

    /**
     * Check if sing-box binary exists and is executable.
     */
    fun isInstalled(context: Context): Boolean {
        return findBinary(context) != null
    }

    /**
     * Get the absolute path to the sing-box binary, or null.
     */
    fun getBinaryPath(context: Context): String? = findBinary(context)

    /**
     * Get detailed install info.
     */
    suspend fun getInstallInfo(context: Context): InstallInfo = withContext(Dispatchers.IO) {
        val path = findBinary(context)
            ?: return@withContext InstallInfo(installed = false)

        val file = File(path)
        val sizeBytes = if (file.exists()) file.length() else 0L

        val arch = when {
            Build.SUPPORTED_ABIS.isNotEmpty() -> Build.SUPPORTED_ABIS[0]
            else -> Build.CPU_ABI
        }

        val version = try {
            val process = ProcessBuilder(path, "version")
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).readText().trim()
            process.waitFor()
            output.lines().firstOrNull()?.let { line ->
                val parts = line.split("\\s+".toRegex())
                if (parts.size >= 3) parts.last() else line
            } ?: "unknown"
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get version: ${e.message}")
            "unknown"
        }

        InstallInfo(installed = true, version = version, path = path, architecture = arch, sizeBytes = sizeBytes)
    }

    /**
     * Download sing-box from GitHub releases. Returns path on success, null on failure.
     */
    suspend fun download(
        context: Context,
        onProgress: (Float) -> Unit = {}
    ): String? = withContext(Dispatchers.IO) {
        try {
            val arch = when {
                Build.SUPPORTED_ABIS.isNotEmpty() -> Build.SUPPORTED_ABIS[0]
                else -> Build.CPU_ABI
            }
            val triplet = when (arch) {
                "arm64-v8a" -> "android-arm64"
                "armeabi-v7a", "armeabi" -> "android-arm"
                "x86_64" -> "android-amd64"
                "x86" -> "android-x86"
                else -> "android-arm64"
            }

            onProgress(5f)

            val fileName = "sing-box-${SING_BOX_VERSION}-${triplet}.tar.gz"
            val url = "https://github.com/SagerNet/sing-box/releases/download/v${SING_BOX_VERSION}/$fileName"
            val outputFile = File(context.cacheDir, "sing-box-download.tar.gz")

            Log.d(TAG, "Downloading sing-box from: $url")

            // Download with progress tracking
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.connect()

            val totalSize = connection.contentLength.toLong()
            val inputStream = connection.inputStream
            val outputStream = outputFile.outputStream()

            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalRead = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                if (totalSize > 0) {
                    val progress = 10f + (totalRead.toFloat() / totalSize * 70f)
                    onProgress(progress.coerceIn(10f, 80f))
                }
            }
            outputStream.close()
            inputStream.close()
            connection.disconnect()

            if (outputFile.length() == 0L) {
                Log.e(TAG, "Downloaded file is empty")
                return@withContext null
            }
            onProgress(85f)

            // Extract from tar.gz
            Log.d(TAG, "Extracting tar.gz...")
            val extractDir = File(context.cacheDir, "sing-box-extract")
            extractDir.mkdirs()

            val tarProcess = ProcessBuilder(
                "tar", "xzf", outputFile.absolutePath,
                "-C", extractDir.absolutePath, "--strip-components=1"
            ).redirectErrorStream(true).start()
            val tarExit = tarProcess.waitFor()

            if (tarExit != 0) {
                val err = BufferedReader(InputStreamReader(tarProcess.errorStream)).readText()
                Log.e(TAG, "tar extraction failed (exit=$tarExit): $err")
                return@withContext null
            }

            // Find binary in extracted directory
            val binary = findFileRecursive(extractDir, "sing-box")
            if (binary == null) {
                Log.e(TAG, "sing-box binary not found after extraction")
                return@withContext null
            }
            onProgress(90f)

            // Copy to target location
            val targetDir = File(context.filesDir, "sing-box")
            targetDir.mkdirs()
            val targetFile = File(targetDir, "sing-box")
            binary.copyTo(targetFile, overwrite = true)
            targetFile.setExecutable(true, false)

            // Clean up
            outputFile.delete()
            extractDir.deleteRecursively()

            onProgress(100f)
            Log.d(TAG, "sing-box installed at: ${targetFile.absolutePath}")
            targetFile.absolutePath

        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            null
        }
    }

    /**
     * Run `sing-box version` to verify the binary works.
     * Returns Pair(success, output).
     */
    suspend fun testCore(context: Context): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val path = findBinary(context)
            ?: return@withContext Pair(false, "sing-box binary not found")

        try {
            val process = ProcessBuilder(path, "version")
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).readText().trim()
            val exitCode = process.waitFor()
            Pair(exitCode == 0, if (output.isNotEmpty()) output else "Exit code: $exitCode")
        } catch (e: Exception) {
            Pair(false, "Error: ${e.message}")
        }
    }

    // ── Private ──────────────────────────────────────────────────────

    private fun findBinary(context: Context): String? {
        // Priority 1: Native libs dir
        val nativeLibPath = "${context.applicationInfo.nativeLibraryDir}/libsing-box.so"
        if (File(nativeLibPath).let { it.exists() && it.canExecute() }) return nativeLibPath

        // Priority 2: filesDir/sing-box/sing-box
        val filesDirPath = "${context.filesDir.absolutePath}/sing-box/sing-box"
        if (File(filesDirPath).let { it.exists() && it.canExecute() }) return filesDirPath

        // Priority 3: filesDir/sing-box (old path)
        val oldPath = "${context.filesDir.absolutePath}/sing-box"
        if (File(oldPath).let { it.exists() && it.canExecute() }) return oldPath

        // Priority 4: /data/local/tmp
        val tmpPath = "/data/local/tmp/sing-box"
        if (File(tmpPath).let { it.exists() && it.canExecute() }) return tmpPath

        return null
    }

    private fun findFileRecursive(dir: File, name: String): File? {
        if (dir.isFile && dir.name == name) return dir
        dir.listFiles()?.forEach { child ->
            val found = findFileRecursive(child, name)
            if (found != null) return found
        }
        return null
    }
}
