package com.vpnbox.ui.viewmodel

import android.content.Intent
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

    init {
        loadCurrentServer()
    }

    private fun loadCurrentServer() {
        viewModelScope.launch {
            _currentServer.value = repository.getSelectedServer()
        }
    }

    fun onVpnPermissionGranted() {
        connectVpn()
    }

    fun connectVpn() {
        val server = _currentServer.value ?: return
        viewModelScope.launch {
            _connectionState.value = ConnectionState.CONNECTING
            try {
                val service = VpnTunnelService.getInstance()
                if (service != null) {
                    val configGenerator = ConfigGenerator()
                    val success = service.startVpn(server, configGenerator)
                    if (success) {
                        _connectionState.value = ConnectionState.CONNECTED
                        connectionStartTime = System.currentTimeMillis()
                        startTimer()
                    } else {
                        _connectionState.value = ConnectionState.ERROR
                    }
                } else {
                    _connectionState.value = ConnectionState.ERROR
                }
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.ERROR
            }
        }
    }

    fun disconnectVpn() {
        viewModelScope.launch {
            _connectionState.value = ConnectionState.DISCONNECTING
            try {
                VpnTunnelService.getInstance()?.stopVpn()
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
