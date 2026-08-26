package com.vpnbox.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ══════════════════════════════════════════════════════════════════════════════
// ViewModel
// ══════════════════════════════════════════════════════════════════════════════

@HiltViewModel
class DebugViewModel @Inject constructor(
    private val configGenerator: ConfigGenerator
) : ViewModel() {

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

    // ── Public actions ───────────────────────────────────────────────────

    /** Full refresh: re-read install info, config, logs, and core status. */
    fun refresh(context: Context) {
        viewModelScope.launch {
            _installInfo.value = SingBoxManager.getInstallInfo(context)
        }
        _configText.value = configGenerator.getLastConfig()
            .ifEmpty { "No config generated yet." }
        _coreLogsText.value = VpnTunnelService.getCoreLogs()
            .ifEmpty { "No logs available." }
        _isCoreRunning.value = VpnTunnelService.isCoreRunning()
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
                    // Refresh install info after successful download
                    _installInfo.value = SingBoxManager.getInstallInfo(context)
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

    val context = LocalContext.current

    // Initial load
    LaunchedEffect(Unit) {
        viewModel.refresh(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug – sing-box") },
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── 1. Sing-box Status ────────────────────────────────────────
            SectionHeader(title = "sing-box Status")

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Installed row
                    InfoRow(
                        label = "Installed",
                        value = if (installInfo.installed) "Yes" else "No",
                        valueColor = if (installInfo.installed)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error
                    )

                    if (installInfo.installed) {
                        InfoRow(label = "Version", value = installInfo.version)
                        InfoRow(label = "Path", value = installInfo.path)
                        InfoRow(label = "Architecture", value = installInfo.architecture)
                        InfoRow(
                            label = "Size",
                            value = formatSize(installInfo.sizeBytes)
                        )
                    } else {
                        // Not installed → show download button + progress
                        Spacer(modifier = Modifier.height(4.dp))

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
                }
            }

            HorizontalDivider()

            // ── 2. Test sing-box ──────────────────────────────────────────
            SectionHeader(title = "Test sing-box")

            OutlinedButton(
                onClick = { viewModel.testCore(context) },
                enabled = !isTesting && installInfo.installed,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isTesting) "Testing…" else "Test sing-box")
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

            // ── 3. Generated Config ───────────────────────────────────────
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

            // ── 4. Connection Logs ────────────────────────────────────────
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
                    // Show last 50 lines
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

            // ── 5. Connection Status ──────────────────────────────────────
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
