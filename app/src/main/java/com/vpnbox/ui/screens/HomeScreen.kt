package com.vpnbox.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vpnbox.data.model.ConnectionState
import com.vpnbox.ui.components.ConnectionButton
import com.vpnbox.ui.viewmodel.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToServers: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val currentServer by viewModel.currentServer.collectAsState()
    val connectionTime by viewModel.connectionTime.collectAsState()
    val context = LocalContext.current

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onVpnPermissionGranted()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VpnBox") },
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
            Text(
                text = when (connectionState) {
                    ConnectionState.CONNECTED -> "Connected"
                    ConnectionState.CONNECTING -> "Connecting..."
                    ConnectionState.DISCONNECTED -> "Disconnected"
                    ConnectionState.DISCONNECTING -> "Disconnecting..."
                    ConnectionState.ERROR -> "Error"
                },
                style = MaterialTheme.typography.headlineMedium,
                color = when (connectionState) {
                    ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
                    ConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
                    ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.error
                    ConnectionState.DISCONNECTING -> MaterialTheme.colorScheme.tertiary
                    ConnectionState.ERROR -> MaterialTheme.colorScheme.error
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Current Server
            Text(
                text = currentServer?.name ?: "No server selected",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Connection Button
            ConnectionButton(
                connectionState = connectionState,
                onClick = {
                    if (connectionState == ConnectionState.DISCONNECTED || connectionState == ConnectionState.ERROR) {
                        val prepareIntent = VpnService.prepare(context)
                        if (prepareIntent != null) {
                            vpnPermissionLauncher.launch(prepareIntent)
                        } else {
                            viewModel.connectVpn()
                        }
                    } else if (connectionState == ConnectionState.CONNECTED) {
                        viewModel.disconnectVpn()
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

            Spacer(modifier = Modifier.height(16.dp))

            // Quick Actions
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedButton(
                    onClick = onNavigateToServers
                ) {
                    Icon(Icons.Default.Link, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Servers")
                }
            }
        }
    }
}
