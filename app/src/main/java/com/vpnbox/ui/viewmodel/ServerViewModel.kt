package com.vpnbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vpnbox.data.model.Protocol
import com.vpnbox.data.model.ServerConfig
import com.vpnbox.data.repository.ServerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ServerViewModel @Inject constructor(
    private val repository: ServerRepository
) : ViewModel() {

    private val _servers = MutableStateFlow<List<ServerConfig>>(emptyList())
    val servers: StateFlow<List<ServerConfig>> = _servers.asStateFlow()

    init {
        loadServers()
    }

    private fun loadServers() {
        viewModelScope.launch {
            repository.getAllServers().collect { servers ->
                _servers.value = servers
            }
        }
    }

    fun selectServer(server: ServerConfig) {
        viewModelScope.launch {
            repository.selectServer(server)
        }
    }

    fun deleteServer(server: ServerConfig) {
        viewModelScope.launch {
            repository.deleteServer(server)
        }
    }

    fun addServer(name: String, address: String, port: Int, protocol: String) {
        viewModelScope.launch {
            val server = ServerConfig(
                name = name,
                protocol = Protocol.valueOf(protocol),
                address = address,
                port = port
            )
            repository.insertServer(server)
        }
    }
}
