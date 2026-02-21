package com.signalk.companion.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.signalk.companion.data.model.AuthState
import com.signalk.companion.service.AuthenticationService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authenticationService: AuthenticationService
) : ViewModel() {
    
    val authState: StateFlow<AuthState> = authenticationService.authState
    private var authJob: Job? = null
    
    fun login(serverUrl: String, username: String, password: String) {
        authJob?.cancel()
        authJob = viewModelScope.launch {
            authenticationService.login(serverUrl, username, password)
        }
    }
    
    fun logout() {
        authJob?.cancel()
        authJob = viewModelScope.launch {
            authenticationService.logout()
        }
    }
    
    fun clearError() {
        authenticationService.clearError()
    }
}
