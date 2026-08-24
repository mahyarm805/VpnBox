package com.vpnbox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vpnbox.data.api.ApiClient
import com.vpnbox.data.api.LoginResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val isSuccess: Boolean = false
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val apiClient: ApiClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun login(serverUrl: String, username: String, password: String) {
        viewModelScope.launch {
            _uiState.value = LoginUiState(isLoading = true)

            apiClient.setBaseUrl(serverUrl)
            val result = apiClient.login(username, password)

            result.fold(
                onSuccess = { response ->
                    apiClient.setAuthToken(response.token)
                    _uiState.value = LoginUiState(isSuccess = true)
                },
                onFailure = { error ->
                    _uiState.value = LoginUiState(error = error.message)
                }
            )
        }
    }
}
