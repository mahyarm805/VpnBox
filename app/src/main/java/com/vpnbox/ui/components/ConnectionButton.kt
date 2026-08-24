package com.vpnbox.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Power
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vpnbox.data.model.ConnectionState
import com.vpnbox.ui.theme.ConnectedGreen
import com.vpnbox.ui.theme.DisconnectedRed

@Composable
fun ConnectionButton(
    connectionState: ConnectionState,
    onClick: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = when (connectionState) {
            ConnectionState.CONNECTED -> ConnectedGreen
            ConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
            ConnectionState.DISCONNECTED -> DisconnectedRed
            ConnectionState.DISCONNECTING -> MaterialTheme.colorScheme.tertiary
            ConnectionState.ERROR -> MaterialTheme.colorScheme.error
        },
        label = "buttonColor"
    )

    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(120.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = backgroundColor
        ),
        enabled = connectionState != ConnectionState.CONNECTING && 
                  connectionState != ConnectionState.DISCONNECTING
    ) {
        Icon(
            imageVector = Icons.Default.Power,
            contentDescription = "Toggle Connection",
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onPrimary
        )
    }
}
