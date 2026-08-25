package com.vpnbox.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vpnbox.core.VpnTunnelService
import com.vpnbox.data.model.ConnectionState
import com.vpnbox.data.model.ServerConfig
import com.vpnbox.data.repository.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: ServerRepository
) : ViewModel() {

    companion object {
        private const val TAG = "HomeViewModel"
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _currentServer = MutableStateFlow<ServerConfig?>(null)
    val currentServer: StateFlow<ServerConfig?> = _currentServer.asStateFlow()

    private val _connectionTime = MutableStateFlow("00:00:00")
    val connectionTime: StateFlow<String> = _connectionTime.asStateFlow()

    private var connectionStartTime: Long = 0

    init {
        // Reactively observe the selected server from database
        viewModelScope.launch {
            repository.observeSelectedServer().collectLatest { server ->
                Log.d(TAG, "Selected server changed: ${server?.name} (${server?.protocol})")
                _currentServer.value = server
            }
        }
    }

    fun connectVpn(context: Context) {
        val server = _currentServer.value
        if (server == null) {
            Log.w(TAG, "No server selected, cannot connect")
            _connectionState.value = ConnectionState.ERROR
            return
        }

        Log.d(TAG, "Starting VPN connection to: ${server.name} (${server.protocol.displayName})")
        _connectionState.value = ConnectionState.CONNECTING

        val intent = Intent(context, VpnTunnelService::class.java).apply {
            action = VpnTunnelService.ACTION_CONNECT
            putExtra("server_name", server.name)
            putExtra("server_id", server.id)
        }

        try {
            context.startService(intent)
            Log.d(TAG, "VPN service start command sent")

            // Poll for service readiness
            viewModelScope.launch {
                var attempts = 0
                while (attempts < 30) {
                    delay(500)
                    if (VpnTunnelService.isCoreRunning()) {
                        Log.d(TAG, "VPN connected successfully")
                        _connectionState.value = ConnectionState.CONNECTED
                        connectionStartTime = System.currentTimeMillis()
                        startTimer()
                        return@launch
                    }
                    attempts++
                    Log.d(TAG, "Waiting for VPN service... attempt $attempts")
                }
                Log.e(TAG, "VPN service timeout after 15s")
                _connectionState.value = ConnectionState.ERROR
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN service", e)
            _connectionState.value = ConnectionState.ERROR
        }
    }

    fun disconnectVpn(context: Context) {
        viewModelScope.launch {
            Log.d(TAG, "Disconnecting VPN")
            _connectionState.value = ConnectionState.DISCONNECTING
            try {
                val intent = Intent(context, VpnTunnelService::class.java).apply {
                    action = VpnTunnelService.ACTION_DISCONNECT
                }
                context.startService(intent)
                _connectionState.value = ConnectionState.DISCONNECTED
                _connectionTime.value = "00:00:00"
                Log.d(TAG, "VPN disconnected")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to disconnect", e)
                _connectionState.value = ConnectionState.ERROR
            }
        }
    }

    private fun startTimer() {
        viewModelScope.launch {
            while (_connectionState.value == ConnectionState.CONNECTED) {
                val elapsed = System.currentTimeMillis() - connectionStartTime
                val hours = elapsed / 3600000
                val minutes = (elapsed % 3600000) / 60000
                val seconds = (elapsed % 60000) / 1000
                _connectionTime.value = String.format("%02d:%02d:%02d", hours, minutes, seconds)
                delay(1000)
            }
        }
    }
}
