package com.vpnbox.core

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Manages the sing-box binary lifecycle: check installation, download, info, test.
 */
object SingBoxManager {

    private const val TAG = "SingBoxManager"

    data class InstallInfo(
        val installed: Boolean,
        val version: String = "",
        val path: String = "",
        val architecture: String = "",
        val sizeBytes: Long = 0
    )

    /**
     * Check whether a usable sing-box binary is available.
     */
    fun isInstalled(context: Context): Boolean {
        return findBinary(context) != null
    }

    /**
     * Gather install metadata (version, path, arch, size).
     * If not installed, returns InstallInfo(installed=false).
     */
    suspend fun getInstallInfo(context: Context): InstallInfo = withContext(Dispatchers.IO) {
        val path = findBinary(context)
            ?: return@withContext InstallInfo(installed = false)

        val file = File(path)
        val sizeBytes = if (file.exists()) file.length() else 0L

        // Determine architecture from ABI
        val arch = when {
            Build.SUPPORTED_ABIS.isNotEmpty() -> Build.SUPPORTED_ABIS[0]
            else -> Build.CPU_ABI
        }

        // Try to get version via `sing-box version`
        val version = try {
            val process = ProcessBuilder(path, "version")
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).readText().trim()
            process.waitFor()
            // Extract version string — first line typically: "sing-box version 1.x.x"
            output.lines().firstOrNull()?.let { line ->
                val parts = line.split("\\s+".toRegex())
                if (parts.size >= 3) parts.last() else line
            } ?: "unknown"
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get sing-box version: ${e.message}")
            "unknown"
        }

        InstallInfo(
            installed = true,
            version = version,
            path = path,
            architecture = arch,
            sizeBytes = sizeBytes
        )
    }

    /**
     * Download the sing-box binary from the official GitHub release for the current device.
     * Reports progress (0..100) via [onProgress].
     * Returns the path to the downloaded binary on success, null on failure.
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

            // Get latest release tag from GitHub API
            onProgress(5f)
            val tagProcess = ProcessBuilder(
                "curl", "-sL", "-H", "Accept: application/vnd.github.v3+json",
                "https://api.github.com/repos/SagerNet/sing-box/releases/latest"
            ).start()
            val tagOutput = BufferedReader(InputStreamReader(tagProcess.inputStream)).readText()
            tagProcess.waitFor()

            val tag = Regex(""""tag_name"\s*:\s*"([^"]+)"""")
                .find(tagOutput)?.groupValues?.get(1)
            if (tag == null) {
                Log.e(TAG, "Failed to parse latest release tag")
                return@withContext null
            }
            onProgress(15f)

            val fileName = "sing-box-${tag}-${triplet}.tar.gz"
            val url = "https://github.com/SagerNet/sing-box/releases/download/$tag/$fileName"
            val outputFile = File(context.filesDir, fileName)

            Log.d(TAG, "Downloading sing-box from: $url")

            // Download with progress via curl
            val curlProcess = ProcessBuilder(
                "curl", "-L", "-o", outputFile.absolutePath, url
            ).start()

            // Poll file size for progress (rough approximation)
            val sizeJob = kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                while (curlProcess.isAlive) {
                    kotlinx.coroutines.delay(500)
                    if (outputFile.exists()) {
                        val downloaded = outputFile.length()
                        // Approximate: assume ~15MB binary
                        val progress = (downloaded.toFloat() / 15_000_000f * 70f).coerceIn(20f, 90f)
                        onProgress(progress)
                    }
                }
            }

            val exitCode = curlProcess.waitFor()
            sizeJob.cancel()

            if (exitCode != 0 || !outputFile.exists()) {
                Log.e(TAG, "Download failed with exit code $exitCode")
                return@withContext null
            }
            onProgress(90f)

            // Extract the tar.gz
            val extractDir = File(context.filesDir, "sing-box-extract")
            extractDir.mkdirs()
            val tarProcess = ProcessBuilder(
                "tar", "xzf", outputFile.absolutePath, "-C", extractDir.absolutePath, "--strip-components=1"
            ).start()
            tarProcess.waitFor()

            // Find the sing-box binary in extracted files
            val binary = File(extractDir, "sing-box")
            if (!binary.exists()) {
                Log.e(TAG, "sing-box binary not found after extraction")
                return@withContext null
            }

            // Move to filesDir and make executable
            val targetPath = File(context.filesDir, "sing-box")
            binary.copyTo(targetPath, overwrite = true)
            targetPath.setExecutable(true)

            // Clean up
            outputFile.delete()
            extractDir.deleteRecursively()

            onProgress(100f)
            Log.d(TAG, "sing-box installed at: ${targetPath.absolutePath}")
            targetPath.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            null
        }
    }

    /**
     * Run `sing-box version` and return the output.
     * @return Pair(success, output)
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

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun findBinary(context: Context): String? {
        // Priority 1: Native libs dir (shipped with APK)
        val nativeLibPath = "${context.applicationInfo.nativeLibraryDir}/libsing-box.so"
        if (File(nativeLibPath).let { it.exists() && it.canExecute() }) {
            return nativeLibPath
        }

        // Priority 2: App filesDir (downloaded or manually placed)
        val filesDirPath = "${context.filesDir.absolutePath}/sing-box"
        if (File(filesDirPath).let { it.exists() && it.canExecute() }) {
            return filesDirPath
        }

        // Priority 3: /data/local/tmp (adb pushed)
        val tmpPath = "/data/local/tmp/sing-box"
        if (File(tmpPath).let { it.exists() && it.canExecute() }) {
            return tmpPath
        }

        return null
    }
}
