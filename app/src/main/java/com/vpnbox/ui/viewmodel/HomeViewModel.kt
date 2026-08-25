package com.vpnbox.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vpnbox.core.ConfigGenerator
import com.vpnbox.core.VpnTunnelService
import com.vpnbox.data.model.ConnectionState
import com.vpnbox.data.model.ServerConfig
import com.vpnbox.data.repository.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: ServerRepository
) : ViewModel() {

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _currentServer = MutableStateFlow<ServerConfig?>(null)
    val currentServer: StateFlow<ServerConfig?> = _currentServer.asStateFlow()

    private val _connectionTime = MutableStateFlow("00:00:00")
    val connectionTime: StateFlow<String> = _connectionTime.asStateFlow()

    private var connectionStartTime: Long = 0
    private var pendingConnect: Boolean = false

    init {
        loadCurrentServer()
    }

    private fun loadCurrentServer() {
        viewModelScope.launch {
            _currentServer.value = repository.getSelectedServer()
        }
    }

    fun checkVpnPermission(context: Context): Intent? {
        return VpnService.prepare(context)
    }

    fun onVpnPermissionGranted(context: Context) {
        pendingConnect = true
        startVpnService(context)
    }

    private fun startVpnService(context: Context) {
        val server = _currentServer.value
        if (server == null) {
            _connectionState.value = ConnectionState.ERROR
            return
        }

        _connectionState.value = ConnectionState.CONNECTING

        val intent = Intent(context, VpnTunnelService::class.java).apply {
            action = VpnTunnelService.ACTION_CONNECT
            putExtra("server_name", server.name)
            putExtra("server_id", server.id)
        }

        try {
            context.startForegroundService(intent)
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.ERROR
            return
        }

        // Poll for service readiness
        viewModelScope.launch {
            var attempts = 0
            while (attempts < 20) {
                delay(500)
                val service = VpnTunnelService.getInstance()
                if (service != null && service.isRunning()) {
                    _connectionState.value = ConnectionState.CONNECTED
                    connectionStartTime = System.currentTimeMillis()
                    startTimer()
                    pendingConnect = false
                    return@launch
                }
                attempts++
            }
            // Timeout
            _connectionState.value = ConnectionState.ERROR
            pendingConnect = false
        }
    }

    fun connectVpn(context: Context) {
        val intent = checkVpnPermission(context)
        if (intent != null) {
            // Permission needed - caller should launch intent
            _connectionState.value = ConnectionState.CONNECTING
        } else {
            // Permission already granted
            startVpnService(context)
        }
    }

    fun disconnectVpn(context: Context) {
        viewModelScope.launch {
            _connectionState.value = ConnectionState.DISCONNECTING
            try {
                val intent = Intent(context, VpnTunnelService::class.java).apply {
                    action = VpnTunnelService.ACTION_DISCONNECT
                }
                context.startService(intent)
                _connectionState.value = ConnectionState.DISCONNECTED
                _connectionTime.value = "00:00:00"
            } catch (e: Exception) {
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
