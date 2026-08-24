package com.vpnbox.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vpnbox.data.model.Protocol

@Composable
fun ProtocolChip(
    protocol: Protocol,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = { Text(protocol.displayName) },
        modifier = Modifier.padding(end = 8.dp)
    )
}
