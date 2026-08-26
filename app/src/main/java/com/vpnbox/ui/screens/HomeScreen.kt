package com.vpnbox.ui.screens

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vpnbox.data.model.ConnectionState
import com.vpnbox.ui.components.ConnectionButton
import com.vpnbox.ui.viewmodel.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToServers: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToDebug: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val currentServer by viewModel.currentServer.collectAsState()
    val connectionTime by viewModel.connectionTime.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val coreLogs by viewModel.coreLogs.collectAsState()
    val context = LocalContext.current

    var showLogs by remember { mutableStateOf(false) }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.connectVpn(context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WhiteHole") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Connection Status
            val statusText = when (connectionState) {
                ConnectionState.CONNECTED -> "Connected"
                ConnectionState.CONNECTING -> "Connecting..."
                ConnectionState.DISCONNECTED -> "Disconnected"
                ConnectionState.DISCONNECTING -> "Disconnecting..."
                ConnectionState.ERROR -> "Connection Failed"
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.headlineMedium,
                color = when (connectionState) {
                    ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
                    ConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
                    ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
                    ConnectionState.DISCONNECTING -> MaterialTheme.colorScheme.tertiary
                    ConnectionState.ERROR -> MaterialTheme.colorScheme.error
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Current Server
            Text(
                text = currentServer?.let { "${it.protocol.displayName} - ${it.name}" } ?: "No server selected",
                style = MaterialTheme.typography.bodyLarge,
                color = if (currentServer != null) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Connection Button
            ConnectionButton(
                connectionState = connectionState,
                onClick = {
                    when (connectionState) {
                        ConnectionState.DISCONNECTED, ConnectionState.ERROR -> {
                            if (currentServer == null) {
                                viewModel.setErrorMessage("Please select a server first")
                                return@ConnectionButton
                            }
                            val prepareIntent = VpnService.prepare(context)
                            if (prepareIntent != null) {
                                vpnPermissionLauncher.launch(prepareIntent)
                            } else {
                                viewModel.connectVpn(context)
                            }
                        }
                        ConnectionState.CONNECTED -> {
                            viewModel.disconnectVpn(context)
                        }
                        else -> { }
                    }
                }
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Connection Time
            if (connectionState == ConnectionState.CONNECTED) {
                Text(
                    text = "Connected for: $connectionTime",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Error Message
            if (connectionState == ConnectionState.ERROR && errorMessage.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )

                        // Expandable logs
                        if (coreLogs.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showLogs = !showLogs },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (showLogs) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Show logs (${coreLogs.lines().size} lines)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                                )
                            }

                            AnimatedVisibility(visible = showLogs) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp)
                                        .heightIn(max = 200.dp)
                                        .verticalScroll(rememberScrollState())
                                        .background(
                                            MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                                            MaterialTheme.shapes.small
                                        )
                                        .padding(8.dp)
                                ) {
                                    coreLogs.lines().takeLast(30).forEach { line ->
                                        val color = when {
                                            line.contains("[ERR]") -> MaterialTheme.colorScheme.error
                                            line.contains("[EXIT]") -> Color(0xFFFF9800)
                                            line.contains("[OUT]") -> Color(0xFF4CAF50)
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                        Text(
                                            text = line,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            lineHeight = 13.sp,
                                            color = color
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Quick Actions
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(onClick = onNavigateToServers) {
                    Icon(Icons.Default.Link, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Servers")
                }
                OutlinedButton(onClick = onNavigateToDebug) {
                    Text("Debug")
                }
            }
        }
    }
}
