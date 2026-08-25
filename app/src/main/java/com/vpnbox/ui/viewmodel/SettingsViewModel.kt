package com.vpnbox.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vpnbox.data.model.ServerConfig
import com.vpnbox.data.repository.ServerRepository
import com.vpnbox.util.UriParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val repository: ServerRepository
) : AndroidViewModel(application) {

    private val _isDarkMode = MutableStateFlow(true)
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    private val _autoConnect = MutableStateFlow(false)
    val autoConnect: StateFlow<Boolean> = _autoConnect.asStateFlow()

    fun toggleDarkMode() {
        _isDarkMode.value = !_isDarkMode.value
    }

    fun toggleAutoConnect() {
        _autoConnect.value = !_autoConnect.value
    }

    fun importFromUri(uri: String) {
        viewModelScope.launch {
            val server = UriParser.parse(uri)
            if (server != null) {
                repository.insertServer(server)
            }
        }
    }
}
