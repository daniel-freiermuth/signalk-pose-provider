package com.signalk.companion.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.signalk.companion.service.AuthenticationService
import com.signalk.companion.util.ConnectionSettings
import com.signalk.companion.util.VesselSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val vesselId: String = "self",
    val isAuthenticated: Boolean = false,
    val isLoggingIn: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    val saveSuccess: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authenticationService: AuthenticationService
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var isInitialized = false
    private var lastAuthError: String? = null
    private var authJob: Job? = null

    init {
        // Observe authentication state
        viewModelScope.launch {
            authenticationService.authState.collect { authState ->
                _uiState.update {
                    it.copy(
                        isAuthenticated = authState.isAuthenticated,
                        isLoggingIn = authState.isLoading
                    )
                }

                // Update error state only if the auth error changed
                // This prevents:
                // 1. Overwriting local errors with null
                // 2. Repeated emissions of the same error overwriting newer local errors
                if (authState.error != null && authState.error != lastAuthError) {
                    // New auth error arrived - update both tracking and UI
                    lastAuthError = authState.error
                    _uiState.update { it.copy(error = authState.error) }
                } else if (authState.error == null && lastAuthError != null) {
                    // Auth error cleared - reset tracking but preserve any local error in UI
                    // This allows the same auth error to update UI again if it reappears
                    lastAuthError = null
                }
            }
        }
    }

    fun initializeSettings(context: Context) {
        if (isInitialized) return
        isInitialized = true

        val savedServerUrl = ConnectionSettings.getServerUrl(context)
        val savedUsername = ConnectionSettings.getUsername(context)
        val savedPassword = ConnectionSettings.getPassword(context)
        val savedVesselId = VesselSettings.getVesselId(context)

        _uiState.update {
            it.copy(
                serverUrl = savedServerUrl,
                username = savedUsername,
                password = savedPassword,
                vesselId = savedVesselId
            )
        }
    }

    fun updateServerUrl(url: String) {
        _uiState.update { it.copy(serverUrl = url, saveSuccess = false) }
    }

    fun updateUsername(username: String) {
        _uiState.update { it.copy(username = username, saveSuccess = false) }
    }

    fun updatePassword(password: String) {
        _uiState.update { it.copy(password = password, saveSuccess = false) }
    }

    fun updateVesselId(vesselId: String) {
        _uiState.update { it.copy(vesselId = vesselId, saveSuccess = false) }
    }

    fun saveSettings(context: Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }

            try {
                val currentState = _uiState.value

                // Save all settings
                ConnectionSettings.setServerUrl(context, currentState.serverUrl)
                ConnectionSettings.setUsername(context, currentState.username)
                ConnectionSettings.setPassword(context, currentState.password)
                VesselSettings.setVesselId(context, currentState.vesselId)

                _uiState.update { it.copy(isSaving = false, saveSuccess = true) }
            } catch (e: IllegalStateException) {
                // getSharedPreferences() throws this while credential-encrypted storage is
                // still locked (direct boot, before the first unlock).
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        error = "Failed to save settings: ${e.message}"
                    )
                }
            }
        }
    }

    fun testConnection(context: Context) {
        authJob?.cancel()
        authJob = viewModelScope.launch {
            val currentState = _uiState.value

            if (currentState.serverUrl.isBlank()) {
                _uiState.update { it.copy(error = "Server URL is required") }
                return@launch
            }

            // Save settings first
            saveSettings(context)

            // If credentials are provided, attempt login
            if (currentState.username.isNotBlank() && currentState.password.isNotBlank()) {
                authenticationService.login(
                    currentState.serverUrl,
                    currentState.username,
                    currentState.password
                )
            } else {
                _uiState.update {
                    it.copy(error = "Enter username and password to test authentication")
                }
            }
        }
    }

    fun logout() {
        authJob?.cancel()
        authJob = viewModelScope.launch {
            authenticationService.logout()
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
        lastAuthError = null
        authenticationService.clearError()
    }

    fun clearSaveSuccess() {
        _uiState.update { it.copy(saveSuccess = false) }
    }
}
