package com.vpnbox.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vpnbox.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val isDarkMode by viewModel.isDarkMode.collectAsState()
    val autoConnect by viewModel.autoConnect.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // Dark Mode
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Dark Mode")
                Switch(
                    checked = isDarkMode,
                    onCheckedChange = { viewModel.toggleDarkMode() }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Auto Connect
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Auto Connect")
                Switch(
                    checked = autoConnect,
                    onCheckedChange = { viewModel.toggleAutoConnect() }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Import from URI
            OutlinedButton(
                onClick = { viewModel.importFromUri() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Import from URI")
            }
        }
    }
}
