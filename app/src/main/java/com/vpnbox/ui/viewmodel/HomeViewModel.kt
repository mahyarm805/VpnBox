package com.vpnbox.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vpnbox.core.VpnTunnelService
import com.vpnbox.data.model.ConnectionState
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

    private val _currentServer = MutableStateFlow<com.vpnbox.data.model.ServerConfig?>(null)
    val currentServer: StateFlow<com.vpnbox.data.model.ServerConfig?> = _currentServer.asStateFlow()

    private val _connectionTime = MutableStateFlow("00:00:00")
    val connectionTime: StateFlow<String> = _connectionTime.asStateFlow()

    private val _errorMessage = MutableStateFlow("")
    val errorMessage: StateFlow<String> = _errorMessage.asStateFlow()

    private val _coreLogs = MutableStateFlow("")
    val coreLogs: StateFlow<String> = _coreLogs.asStateFlow()

    private var connectionStartTime: Long = 0

    init {
        viewModelScope.launch {
            repository.observeSelectedServer().collectLatest { server ->
                Log.d(TAG, "Selected server: ${server?.name} (${server?.protocol?.displayName})")
                _currentServer.value = server
            }
        }
    }

    fun setErrorMessage(msg: String) {
        _errorMessage.value = msg
        _connectionState.value = ConnectionState.ERROR
    }

    fun connectVpn(context: Context) {
        val server = _currentServer.value
        if (server == null) {
            Log.w(TAG, "No server selected")
            _errorMessage.value = "Please select a server from the list"
            _connectionState.value = ConnectionState.ERROR
            return
        }

        Log.d(TAG, "Connecting to: ${server.name} (${server.protocol.displayName})")
        _errorMessage.value = ""
        _coreLogs.value = ""
        _connectionState.value = ConnectionState.CONNECTING

        val intent = Intent(context, VpnTunnelService::class.java).apply {
            action = VpnTunnelService.ACTION_CONNECT
            putExtra("server_name", server.name)
            putExtra("server_id", server.id)
        }

        try {
            context.startService(intent)
            Log.d(TAG, "Service start command sent")

            viewModelScope.launch {
                var attempts = 0
                while (attempts < 30) {
                    delay(500)

                    // Refresh logs on every poll
                    _coreLogs.value = VpnTunnelService.getCoreLogs()

                    if (VpnTunnelService.isCoreRunning()) {
                        Log.d(TAG, "VPN core running - connected!")
                        _connectionState.value = ConnectionState.CONNECTED
                        _errorMessage.value = ""
                        connectionStartTime = System.currentTimeMillis()
                        startTimer()
                        return@launch
                    }

                    // Check if service already set an error
                    val serviceError = VpnTunnelService.getLastError()
                    if (serviceError.isNotEmpty() && attempts > 2) {
                        Log.e(TAG, "Connection failed: $serviceError")
                        _errorMessage.value = serviceError
                        _connectionState.value = ConnectionState.ERROR
                        return@launch
                    }

                    attempts++
                }

                // Timeout — show whatever error we have
                val finalError = VpnTunnelService.getLastError()
                    .ifEmpty { "Connection timeout" }
                Log.e(TAG, "Connection failed: $finalError")
                _errorMessage.value = finalError
                _coreLogs.value = VpnTunnelService.getCoreLogs()
                _connectionState.value = ConnectionState.ERROR
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service", e)
            _errorMessage.value = "Service error: ${e.message}"
            _connectionState.value = ConnectionState.ERROR
        }
    }

    fun disconnectVpn(context: Context) {
        viewModelScope.launch {
            Log.d(TAG, "Disconnecting")
            _connectionState.value = ConnectionState.DISCONNECTING
            try {
                val intent = Intent(context, VpnTunnelService::class.java).apply {
                    action = VpnTunnelService.ACTION_DISCONNECT
                }
                context.startService(intent)
                _connectionState.value = ConnectionState.DISCONNECTED
                _connectionTime.value = "00:00:00"
                _errorMessage.value = ""
                _coreLogs.value = ""
            } catch (e: Exception) {
                Log.e(TAG, "Disconnect failed", e)
                _errorMessage.value = "Disconnect error: ${e.message}"
                _connectionState.value = ConnectionState.ERROR
            }
        }
    }

    private fun startTimer() {
        viewModelScope.launch {
            while (_connectionState.value == ConnectionState.CONNECTED) {
                val elapsed = System.currentTimeMillis() - connectionStartTime
                val h = elapsed / 3600000
                val m = (elapsed % 3600000) / 60000
                val s = (elapsed % 60000) / 1000
                _connectionTime.value = String.format("%02d:%02d:%02d", h, m, s)
                delay(1000)
            }
        }
    }
}
