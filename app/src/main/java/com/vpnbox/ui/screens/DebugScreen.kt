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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.vpnbox.core.ConfigGenerator
import com.vpnbox.core.VpnTunnelService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class DebugViewModel @Inject constructor(
    private val configGenerator: ConfigGenerator
) : ViewModel() {

    private val _configText = MutableStateFlow("")
    val configText: StateFlow<String> = _configText.asStateFlow()

    private val _coreLogsText = MutableStateFlow("")
    val coreLogsText: StateFlow<String> = _coreLogsText.asStateFlow()

    private val _isCoreRunning = MutableStateFlow(false)
    val isCoreRunning: StateFlow<Boolean> = _isCoreRunning.asStateFlow()

    private val _connectionState = MutableStateFlow("Disconnected")
    val connectionState: StateFlow<String> = _connectionState.asStateFlow()

    fun refresh() {
        // Fetch last generated config from ConfigGenerator
        _configText.value = configGenerator.getLastConfig().ifEmpty { "No config generated yet." }

        // Fetch logs and status from VpnTunnelService companion
        _coreLogsText.value = VpnTunnelService.getCoreLogs().ifEmpty { "No logs available." }
        _isCoreRunning.value = VpnTunnelService.isCoreRunning()
        _connectionState.value = if (VpnTunnelService.isCoreRunning()) "Connected" else "Disconnected"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    onBack: () -> Unit,
    viewModel: DebugViewModel = hiltViewModel()
) {
    val configText by viewModel.configText.collectAsState()
    val coreLogsText by viewModel.coreLogsText.collectAsState()
    val isCoreRunning by viewModel.isCoreRunning.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val context = LocalContext.current

    // Refresh data on first composition
    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug - VPN Config") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
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
            // ── Status Indicators ──────────────────────────────────────
            SectionHeader(title = "Status")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StatusChip(
                    label = "Core Running",
                    value = if (isCoreRunning) "Yes" else "No",
                    color = if (isCoreRunning) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                StatusChip(
                    label = "Connection",
                    value = connectionState,
                    color = when (connectionState) {
                        "Connected" -> MaterialTheme.colorScheme.primary
                        "Disconnected" -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.tertiary
                    }
                )
            }

            HorizontalDivider()

            // ── Generated Config ───────────────────────────────────────
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

            // Copy Config button
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                    val clip = ClipData.newPlainText("VPN Config", configText)
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

            // ── Connection Logs ────────────────────────────────────────
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
                    val logLines = coreLogsText.lines().filter { it.isNotBlank() }
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
                                line.startsWith("[ERR]") ||
                                line.contains("error", ignoreCase = true) ->
                                    MaterialTheme.colorScheme.error
                                line.startsWith("[EXIT]") ->
                                    MaterialTheme.colorScheme.tertiary
                                line.startsWith("[OUT]") ->
                                    MaterialTheme.colorScheme.primary
                                line.startsWith("[INFO]") ||
                                line.contains("info", ignoreCase = true) ->
                                    MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
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

            // Refresh button (bottom)
            Button(
                onClick = { viewModel.refresh() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Refresh")
            }
        }
    }
}

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
private fun StatusChip(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Surface(
        modifier = Modifier,
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.15f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color, MaterialTheme.shapes.small)
            )
            Column {
                Text(
                    text = label,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = color
                )
            }
        }
    }
}
