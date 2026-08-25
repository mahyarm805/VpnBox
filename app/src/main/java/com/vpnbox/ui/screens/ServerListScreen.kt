package com.vpnbox.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vpnbox.data.model.Protocol
import com.vpnbox.ui.components.ServerCard
import com.vpnbox.ui.viewmodel.ServerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    onBack: () -> Unit,
    viewModel: ServerViewModel = hiltViewModel()
) {
    val servers by viewModel.servers.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<com.vpnbox.data.model.ServerConfig?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Servers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Server")
                    }
                }
            )
        }
    ) { paddingValues ->
        if (servers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                Text(
                    text = "No servers added yet.\nTap + to add a server.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(servers) { server ->
                    ServerCard(
                        server = server,
                        onSelect = { viewModel.selectServer(server) },
                        onEdit = { editingServer = server },
                        onDelete = { viewModel.deleteServer(server) }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddServerDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { server ->
                viewModel.addServer(server)
                showAddDialog = false
            }
        )
    }

    editingServer?.let { server ->
        AddServerDialog(
            onDismiss = { editingServer = null },
            onAdd = { updated ->
                viewModel.updateServer(updated.copy(id = server.id))
                editingServer = null
            },
            existingServer = server
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddServerDialog(
    onDismiss: () -> Unit,
    onAdd: (com.vpnbox.data.model.ServerConfig) -> Unit,
    existingServer: com.vpnbox.data.model.ServerConfig? = null
) {
    var name by remember { mutableStateOf(existingServer?.name ?: "") }
    var address by remember { mutableStateOf(existingServer?.address ?: "") }
    var port by remember { mutableStateOf(existingServer?.port?.toString() ?: "443") }
    var protocol by remember { mutableStateOf(existingServer?.protocol ?: Protocol.VLESS) }
    var showProtocolMenu by remember { mutableStateOf(false) }

    // Common fields
    var password by remember { mutableStateOf(existingServer?.password ?: "") }
    var sni by remember { mutableStateOf(existingServer?.sni ?: "") }
    var fingerprint by remember { mutableStateOf(existingServer?.fingerprint ?: "") }

    // VMess
    var uuid by remember { mutableStateOf(existingServer?.uuid ?: "") }
    var alterId by remember { mutableStateOf(existingServer?.alterId?.toString() ?: "0") }
    var security by remember { mutableStateOf(existingServer?.security ?: "auto") }
    var network by remember { mutableStateOf(existingServer?.network ?: "tcp") }
    var vmessTls by remember { mutableStateOf(existingServer?.vmessTls ?: false) }

    // VLESS
    var flow by remember { mutableStateOf(existingServer?.flow ?: "") }
    var vlessEncryption by remember { mutableStateOf(existingServer?.vlessEncryption ?: "none") }
    var vlessTls by remember { mutableStateOf(existingServer?.vlessTls ?: true) }
    var realityEnabled by remember { mutableStateOf(existingServer?.realityEnabled ?: false) }
    var realityPublicKey by remember { mutableStateOf(existingServer?.realityPublicKey ?: "") }
    var realityShortId by remember { mutableStateOf(existingServer?.realityShortId ?: "") }
    var realitySpiderX by remember { mutableStateOf(existingServer?.realitySpiderX ?: "") }

    // Trojan
    var trojanTls by remember { mutableStateOf(existingServer?.trojanTls ?: true) }

    // Shadowsocks
    var ssMethod by remember { mutableStateOf(existingServer?.ssMethod ?: "aes-256-gcm") }

    // SOCKS/HTTP
    var username by remember { mutableStateOf(existingServer?.username ?: "") }

    // Hysteria2
    var obfs by remember { mutableStateOf(existingServer?.obfs ?: "") }
    var obfsPassword by remember { mutableStateOf(existingServer?.obfsPassword ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existingServer != null) "Edit Server" else "Add Server") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                // Server Name
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Server Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Protocol Selector
                ExposedDropdownMenuBox(
                    expanded = showProtocolMenu,
                    onExpandedChange = { showProtocolMenu = it }
                ) {
                    OutlinedTextField(
                        value = protocol.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Protocol") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showProtocolMenu) }
                    )
                    ExposedDropdownMenu(
                        expanded = showProtocolMenu,
                        onDismissRequest = { showProtocolMenu = false }
                    ) {
                        Protocol.entries.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(p.displayName) },
                                onClick = {
                                    protocol = p
                                    port = p.defaultPort.toString()
                                    showProtocolMenu = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Server Address
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Server Address") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Port
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("Port") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                // Protocol-specific fields
                when (protocol) {
                    Protocol.VMESS -> {
                        Text("VMess Settings", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = uuid, onValueChange = { uuid = it }, label = { Text("UUID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = alterId, onValueChange = { alterId = it }, label = { Text("Alter ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = security, onValueChange = { security = it }, label = { Text("Security (auto/aes-128-gcm/chacha20-poly1305)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = network, onValueChange = { network = it }, label = { Text("Network (tcp/kcp/ws/grpc)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("TLS")
                            Switch(checked = vmessTls, onCheckedChange = { vmessTls = it })
                        }
                        if (vmessTls) {
                            OutlinedTextField(value = sni, onValueChange = { sni = it }, label = { Text("SNI") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(value = fingerprint, onValueChange = { fingerprint = it }, label = { Text("Fingerprint") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        }
                    }
                    Protocol.VLESS -> {
                        Text("VLESS Settings", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = uuid, onValueChange = { uuid = it }, label = { Text("UUID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = flow, onValueChange = { flow = it }, label = { Text("Flow (xtls-rprx-vision)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = vlessEncryption, onValueChange = { vlessEncryption = it }, label = { Text("Encryption (none)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("TLS")
                            Switch(checked = vlessTls, onCheckedChange = { vlessTls = it })
                        }
                        if (vlessTls) {
                            OutlinedTextField(value = sni, onValueChange = { sni = it }, label = { Text("SNI") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(value = fingerprint, onValueChange = { fingerprint = it }, label = { Text("Fingerprint") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Reality")
                                Switch(checked = realityEnabled, onCheckedChange = { realityEnabled = it })
                            }
                            if (realityEnabled) {
                                OutlinedTextField(value = realityPublicKey, onValueChange = { realityPublicKey = it }, label = { Text("Public Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(value = realityShortId, onValueChange = { realityShortId = it }, label = { Text("Short ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(value = realitySpiderX, onValueChange = { realitySpiderX = it }, label = { Text("SpiderX") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            }
                        }
                    }
                    Protocol.TROJAN -> {
                        Text("Trojan Settings", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("TLS")
                            Switch(checked = trojanTls, onCheckedChange = { trojanTls = it })
                        }
                        OutlinedTextField(value = sni, onValueChange = { sni = it }, label = { Text("SNI") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = fingerprint, onValueChange = { fingerprint = it }, label = { Text("Fingerprint") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                    Protocol.SHADOWSOCKS -> {
                        Text("Shadowsocks Settings", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = ssMethod, onValueChange = { ssMethod = it }, label = { Text("Method (aes-256-gcm)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
                    }
                    Protocol.SOCKS4, Protocol.SOCKS5 -> {
                        Text("${protocol.displayName} Settings", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
                    }
                    Protocol.HTTP -> {
                        Text("HTTP Proxy Settings", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
                    }
                    Protocol.HYSTERIA2 -> {
                        Text("Hysteria2 Settings", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = obfs, onValueChange = { obfs = it }, label = { Text("Obfs Type (salamander)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = obfsPassword, onValueChange = { obfsPassword = it }, label = { Text("Obfs Password") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(value = sni, onValueChange = { sni = it }, label = { Text("SNI") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val portInt = port.toIntOrNull() ?: protocol.defaultPort
                    val server = com.vpnbox.data.model.ServerConfig(
                        name = name.ifEmpty { "${protocol.displayName} Server" },
                        protocol = protocol,
                        address = address,
                        port = portInt,
                        password = password.ifEmpty { null },
                        sni = sni.ifEmpty { null },
                        fingerprint = fingerprint.ifEmpty { null },
                        uuid = uuid.ifEmpty { null },
                        alterId = alterId.toIntOrNull() ?: 0,
                        security = security,
                        network = network,
                        vmessTls = vmessTls,
                        flow = flow.ifEmpty { null },
                        vlessEncryption = vlessEncryption,
                        vlessTls = vlessTls,
                        realityEnabled = realityEnabled,
                        realityPublicKey = realityPublicKey.ifEmpty { null },
                        realityShortId = realityShortId.ifEmpty { null },
                        realitySpiderX = realitySpiderX.ifEmpty { null },
                        trojanTls = trojanTls,
                        ssMethod = ssMethod,
                        username = username.ifEmpty { null },
                        obfs = obfs.ifEmpty { null },
                        obfsPassword = obfsPassword.ifEmpty { null }
                    )
                    onAdd(server)
                },
                enabled = name.isNotEmpty() && address.isNotEmpty()
            ) {
                Text(if (existingServer != null) "Save" else "Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
