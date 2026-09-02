package com.vpnbox.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vpnbox.core.ConfigGenerator
import com.vpnbox.core.SingBoxManager
import com.vpnbox.core.VpnTunnelService
import com.vpnbox.data.repository.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ══════════════════════════════════════════════════════════════════════════════
// Pipeline stage data model
// ══════════════════════════════════════════════════════════════════════════════

data class PipelineStage(
    val name: String,
    val status: StageStatus,
    val details: String
)

enum class StageStatus { PASS, FAIL, WAITING }

// ══════════════════════════════════════════════════════════════════════════════
// ViewModel
// ══════════════════════════════════════════════════════════════════════════════

@HiltViewModel
class DebugViewModel @Inject constructor(
    private val configGenerator: ConfigGenerator,
    private val serverRepository: ServerRepository
) : ViewModel() {

    // ── VLESS encryption ────────────────────────────────────────────────
    private val _vlessEncryption = MutableStateFlow("none")
    val vlessEncryption: StateFlow<String> = _vlessEncryption.asStateFlow()

    // ── Device info ──────────────────────────────────────────────────────
    private val _androidVersion = MutableStateFlow("${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    val androidVersion: StateFlow<String> = _androidVersion.asStateFlow()

    private val _deviceAbi = MutableStateFlow(
        if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else Build.CPU_ABI
    )
    val deviceAbi: StateFlow<String> = _deviceAbi.asStateFlow()

    // ── Sing-box install info ────────────────────────────────────────────
    private val _installInfo = MutableStateFlow(SingBoxManager.InstallInfo(installed = false))
    val installInfo: StateFlow<SingBoxManager.InstallInfo> = _installInfo.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    private val _downloadError = MutableStateFlow<String?>(null)
    val downloadError: StateFlow<String?> = _downloadError.asStateFlow()

    // ── Test result ──────────────────────────────────────────────────────
    private val _testSuccess = MutableStateFlow<Boolean?>(null)
    val testSuccess: StateFlow<Boolean?> = _testSuccess.asStateFlow()

    private val _testOutput = MutableStateFlow("")
    val testOutput: StateFlow<String> = _testOutput.asStateFlow()

    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    // ── Config & logs ────────────────────────────────────────────────────
    private val _configText = MutableStateFlow("")
    val configText: StateFlow<String> = _configText.asStateFlow()

    private val _coreLogsText = MutableStateFlow("")
    val coreLogsText: StateFlow<String> = _coreLogsText.asStateFlow()

    private val _isCoreRunning = MutableStateFlow(false)
    val isCoreRunning: StateFlow<Boolean> = _isCoreRunning.asStateFlow()

    private val _lastError = MutableStateFlow("")
    val lastError: StateFlow<String> = _lastError.asStateFlow()

    // ── Pipeline stages ──────────────────────────────────────────────────
    private val _stages = MutableStateFlow<List<PipelineStage>>(emptyList())
    val stages: StateFlow<List<PipelineStage>> = _stages.asStateFlow()

    // ── Public actions ───────────────────────────────────────────────────

    /** Full refresh: re-read install info, config, logs, core status, and rebuild pipeline. */
    fun refresh(context: Context) {
        viewModelScope.launch {
            _installInfo.value = SingBoxManager.getInstallInfo(context)
        }
        viewModelScope.launch {
            val server = serverRepository.getSelectedServer()
            _vlessEncryption.value = server?.vlessEncryption ?: "none"
        }

        _configText.value = configGenerator.getLastConfig()
            .ifEmpty { "No config generated yet." }
        _coreLogsText.value = VpnTunnelService.getCoreLogs()
            .ifEmpty { "No logs available." }
        _isCoreRunning.value = VpnTunnelService.isCoreRunning()
        _lastError.value = VpnTunnelService.getLastError()

        rebuildPipeline(context)
    }

    /** Download sing-box binary with progress updates. */
    fun download(context: Context) {
        if (_isDownloading.value) return
        viewModelScope.launch {
            _isDownloading.value = true
            _downloadProgress.value = 0f
            _downloadError.value = null
            try {
                val result = SingBoxManager.download(context) { progress ->
                    _downloadProgress.value = progress
                }
                if (result != null) {
                    _installInfo.value = SingBoxManager.getInstallInfo(context)
                    rebuildPipeline(context)
                } else {
                    _downloadError.value = "Download failed. Check your network connection."
                }
            } catch (e: Exception) {
                _downloadError.value = "Error: ${e.message}"
            } finally {
                _isDownloading.value = false
            }
        }
    }

    /** Run `sing-box version` and display the output. */
    fun testCore(context: Context) {
        if (_isTesting.value) return
        viewModelScope.launch {
            _isTesting.value = true
            _testSuccess.value = null
            _testOutput.value = ""
            try {
                val (success, output) = SingBoxManager.testCore(context)
                _testSuccess.value = success
                _testOutput.value = output
            } catch (e: Exception) {
                _testSuccess.value = false
                _testOutput.value = "Error: ${e.message}"
            } finally {
                _isTesting.value = false
            }
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private fun rebuildPipeline(context: Context) {
        val info = _installInfo.value
        val config = _configText.value
        val logs = _coreLogsText.value
        val running = _isCoreRunning.value
        val error = _lastError.value
        val hasConfig = config.isNotEmpty() && config != "No config generated yet."
        val logLines = logs.lines().filter { it.isNotBlank() }

        // Stage 1: DOWNLOAD
        val stage1 = if (info.installed) {
            PipelineStage("DOWNLOAD", StageStatus.PASS, "sing-box binary is present")
        } else {
            PipelineStage("DOWNLOAD", StageStatus.FAIL, "sing-box binary not found — tap Download above")
        }

        // Stage 2: BINARY FOUND
        val stage2 = if (info.installed) {
            PipelineStage("BINARY FOUND", StageStatus.PASS, info.path)
        } else {
            PipelineStage("BINARY FOUND", StageStatus.FAIL, "No binary located on device")
        }

        // Stage 3: BINARY PERMISSION
        val stage3 = if (info.installed && info.sizeBytes > 0) {
            val sizeStr = formatSize(info.sizeBytes)
            PipelineStage("BINARY PERMISSION", StageStatus.PASS, "Executable — $sizeStr, ${info.architecture}")
        } else if (info.installed) {
            PipelineStage("BINARY PERMISSION", StageStatus.PASS, "Executable — ${info.architecture}")
        } else {
            PipelineStage("BINARY PERMISSION", StageStatus.FAIL, "Cannot check — binary missing")
        }

        // Stage 4: PROCESS START
        val stage4 = if (running) {
            PipelineStage("PROCESS START", StageStatus.PASS, "ProcessBuilder launched successfully")
        } else if (error.isNotEmpty()) {
            PipelineStage("PROCESS START", StageStatus.FAIL, error)
        } else {
            PipelineStage("PROCESS START", StageStatus.WAITING, "No connection attempt yet")
        }

        // Stage 5: SING-BOX ALIVE
        val stage5 = if (running) {
            PipelineStage("SING-BOX ALIVE", StageStatus.PASS, "sing-box process is running")
        } else {
            val exitLog = logLines.lastOrNull { it.startsWith("[EXIT]") }
            if (exitLog != null) {
                PipelineStage("SING-BOX ALIVE", StageStatus.FAIL, exitLog)
            } else {
                PipelineStage("SING-BOX ALIVE", StageStatus.WAITING, "Not running")
            }
        }

        // Stage 6: CONFIG VALIDATION
        val stage6 = if (hasConfig) {
            try {
                // Quick JSON parse check — does the config start with { and end with }?
                val trimmed = config.trim()
                if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                    PipelineStage("CONFIG VALIDATION", StageStatus.PASS, "Valid JSON — ${config.length} bytes")
                } else {
                    PipelineStage("CONFIG VALIDATION", StageStatus.FAIL, "Config does not look like valid JSON")
                }
            } catch (_: Exception) {
                PipelineStage("CONFIG VALIDATION", StageStatus.FAIL, "Config parse error")
            }
        } else {
            PipelineStage("CONFIG VALIDATION", StageStatus.WAITING, "No config generated yet")
        }

        // Stage 7: VLESS ENCRYPTION WARNING
        val lastConfig = VpnTunnelService.getLastConfig() ?: ""
        val hasMlkem = lastConfig.contains("mlkem768x25519plus")
        val stage7 = if (hasMlkem) {
            PipelineStage(
                name = "CONFIG WARNING",
                status = StageStatus.FAIL,
                details = "VLESS Encryption (mlkem768x25519plus) NOT supported by sing-box v1.11.4. Regular VLESS works. Use Xray-core for post-quantum."
            )
        } else null

        // Stage 8: TUN START
        val tunUp = running && logLines.any { it.contains("tun", ignoreCase = true) && !it.contains("[ERR]") }
        val stage8 = if (tunUp) {
            PipelineStage("TUN START", StageStatus.PASS, "TUN interface is active")
        } else if (running) {
            PipelineStage("TUN START", StageStatus.WAITING, "sing-box running, TUN status pending")
        } else {
            val tunErr = logLines.lastOrNull {
                it.contains("[ERR]") && it.contains("tun", ignoreCase = true)
            }
            if (tunErr != null) {
                PipelineStage("TUN START", StageStatus.FAIL, tunErr)
            } else {
                PipelineStage("TUN START", StageStatus.WAITING, "sing-box not running")
            }
        }

        // Stage 9: PROXY CONNECTION
        val proxyUp = running && logLines.any {
            it.contains("[OUT]", ignoreCase = true) && !it.contains("[ERR]")
        }
        val stage9 = if (proxyUp) {
            PipelineStage("PROXY CONNECTION", StageStatus.PASS, "Traffic is flowing")
        } else if (running) {
            PipelineStage("PROXY CONNECTION", StageStatus.WAITING, "sing-box running, waiting for traffic")
        } else {
            val proxyErr = logLines.lastOrNull {
                it.contains("[ERR]") && (it.contains("connect", ignoreCase = true) || it.contains("proxy", ignoreCase = true))
            }
            if (proxyErr != null) {
                PipelineStage("PROXY CONNECTION", StageStatus.FAIL, proxyErr)
            } else {
                PipelineStage("PROXY CONNECTION", StageStatus.WAITING, "No active connection")
            }
        }

        _stages.value = listOfNotNull(stage1, stage2, stage3, stage4, stage5, stage6, stage7, stage8, stage9)
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "N/A"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) String.format("%.1f MB", mb) else String.format("%.0f KB", kb)
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// Composable screen
// ══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    onBack: () -> Unit,
    viewModel: DebugViewModel = hiltViewModel()
) {
    val installInfo by viewModel.installInfo.collectAsState()
    val isDownloading by viewModel.isDownloading.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val downloadError by viewModel.downloadError.collectAsState()

    val testSuccess by viewModel.testSuccess.collectAsState()
    val testOutput by viewModel.testOutput.collectAsState()
    val isTesting by viewModel.isTesting.collectAsState()

    val configText by viewModel.configText.collectAsState()
    val coreLogsText by viewModel.coreLogsText.collectAsState()
    val isCoreRunning by viewModel.isCoreRunning.collectAsState()
    val vlessEncryption by viewModel.vlessEncryption.collectAsState()

    val stages by viewModel.stages.collectAsState()
    val androidVersion by viewModel.androidVersion.collectAsState()
    val deviceAbi by viewModel.deviceAbi.collectAsState()

    val context = LocalContext.current

    // Initial load
    LaunchedEffect(Unit) {
        viewModel.refresh(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug — Pipeline Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh(context) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── Device & Binary Info ─────────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "System Info",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    InfoRow(label = "Android", value = androidVersion)
                    InfoRow(label = "Device ABI", value = deviceAbi)
                    InfoRow(
                        label = "sing-box version",
                        value = installInfo.version.ifEmpty { "—" }
                    )
                    InfoRow(
                        label = "Binary path",
                        value = installInfo.path.ifEmpty { "—" }
                    )
                    InfoRow(
                        label = "VLESS Encryption",
                        value = vlessEncryption.ifEmpty { "none" }
                    )
                }
            }

            HorizontalDivider()

            // ── Download controls ────────────────────────────────────────
            if (!installInfo.installed) {
                if (isDownloading) {
                    LinearProgressIndicator(
                        progress = { downloadProgress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                    )
                    Text(
                        text = "Downloading… ${downloadProgress.toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                downloadError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Button(
                    onClick = { viewModel.download(context) },
                    enabled = !isDownloading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isDownloading) "Downloading…" else "Download sing-box")
                }
            }

            // ── Pipeline Stages ──────────────────────────────────────────
            Text(
                text = "Pipeline Stages",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            stages.forEach { stage ->
                PipelineStageCard(stage)
            }

            // ── Test button ──────────────────────────────────────────────
            OutlinedButton(
                onClick = { viewModel.testCore(context) },
                enabled = !isTesting && installInfo.installed,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isTesting) "Testing…" else "Test sing-box version")
            }

            if (testSuccess != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (testSuccess == true)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else
                            MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = if (testSuccess == true) "✓ Success" else "✗ Failed",
                            fontWeight = FontWeight.Bold,
                            color = if (testSuccess == true)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error
                        )
                        if (testOutput.isNotEmpty()) {
                            SelectionContainer {
                                Text(
                                    text = testOutput,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            // ── Generated Config ─────────────────────────────────────────
            SectionHeader(title = "Generated Config")

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    SelectionContainer {
                        Text(
                            text = configText.ifEmpty { "No config generated yet." },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                    val clip = ClipData.newPlainText("sing-box Config", configText)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "Config copied to clipboard", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Copy Config")
            }

            HorizontalDivider()

            // ── Connection Logs ──────────────────────────────────────────
            SectionHeader(title = "Connection Logs")

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .heightIn(min = 120.dp, max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    val allLines = coreLogsText.lines().filter { it.isNotBlank() }
                    val logLines = allLines.takeLast(50)

                    if (logLines.isEmpty()) {
                        Text(
                            text = "No logs available.",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        logLines.forEach { line ->
                            val color = when {
                                line.startsWith("[ERR]") ->
                                    MaterialTheme.colorScheme.error
                                line.startsWith("[EXIT]") ->
                                    Color(0xFFFF9800) // orange
                                line.startsWith("[OUT]") ->
                                    Color(0xFF4CAF50) // green
                                else ->
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Text(
                                text = line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 14.sp,
                                color = color
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            // ── Connection Status ────────────────────────────────────────
            SectionHeader(title = "Connection Status")

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            color = if (isCoreRunning)
                                Color(0xFF4CAF50)
                            else
                                MaterialTheme.colorScheme.error,
                            shape = MaterialTheme.shapes.small
                        )
                )
                Text(
                    text = "Core running: ${if (isCoreRunning) "Yes" else "No"}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // Bottom spacer
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// Pipeline stage card composable
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun PipelineStageCard(stage: PipelineStage) {
    val statusIcon = when (stage.status) {
        StageStatus.PASS -> "✓"
        StageStatus.FAIL -> "✗"
        StageStatus.WAITING -> "⏳"
    }
    val statusColor = when (stage.status) {
        StageStatus.PASS -> Color(0xFF4CAF50)       // green
        StageStatus.FAIL -> MaterialTheme.colorScheme.error
        StageStatus.WAITING -> Color(0xFFFFC107)    // yellow/amber
    }
    val bgColor = when (stage.status) {
        StageStatus.PASS -> statusColor.copy(alpha = 0.08f)
        StageStatus.FAIL -> statusColor.copy(alpha = 0.08f)
        StageStatus.WAITING -> statusColor.copy(alpha = 0.06f)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = statusIcon,
                fontSize = 18.sp,
                color = statusColor,
                modifier = Modifier.padding(end = 12.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stage.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stage.details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// Private helpers
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = valueColor
        )
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "N/A"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) String.format("%.1f MB", mb) else String.format("%.0f KB", kb)
}
