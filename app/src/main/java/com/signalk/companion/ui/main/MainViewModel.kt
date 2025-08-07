package com.signalk.companion.ui.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.signalk.companion.data.model.LocationData
import com.signalk.companion.data.model.SensorData
import com.signalk.companion.service.LocationService
import com.signalk.companion.service.SignalKTransmitter
import com.signalk.companion.service.AuthenticationService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainUiState(
    val isConnected: Boolean = false,
    val isStreaming: Boolean = false,
    val serverAddress: String = "192.168.1.100:3000",
    val locationData: LocationData? = null,
    val sensorData: SensorData? = null,
    val error: String? = null,
    val lastSentMessage: String? = null,
    val messagesSent: Int = 0,
    val lastTransmissionTime: Long? = null,
    val isAuthenticated: Boolean = false,
    val username: String? = null
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val locationService: LocationService,
    private val signalKTransmitter: SignalKTransmitter,
    private val authenticationService: AuthenticationService
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    
    init {
        // Observe location updates
        viewModelScope.launch {
            locationService.locationUpdates.collect { locationData ->
                _uiState.update { it.copy(locationData = locationData) }
                
                // Send location data to SignalK when streaming and we have data
                if (locationData != null && _uiState.value.isStreaming && _uiState.value.isConnected) {
                    try {
                        signalKTransmitter.sendLocationData(locationData)
                    } catch (e: Exception) {
                        _uiState.update { it.copy(error = "Failed to send location data: ${e.message}") }
                    }
                }
            }
        }
        
        // Observe service connection status
        viewModelScope.launch {
            signalKTransmitter.connectionStatus.collect { isConnected ->
                _uiState.update { it.copy(isConnected = isConnected) }
            }
        }
        
        // Observe last sent message
        viewModelScope.launch {
            signalKTransmitter.lastSentMessage.collect { message ->
                _uiState.update { it.copy(lastSentMessage = message) }
            }
        }
        
        // Observe messages sent count
        viewModelScope.launch {
            signalKTransmitter.messagesSent.collect { count ->
                _uiState.update { it.copy(messagesSent = count) }
            }
        }
        
        // Observe last transmission time
        viewModelScope.launch {
            signalKTransmitter.lastTransmissionTime.collect { time ->
                _uiState.update { it.copy(lastTransmissionTime = time) }
            }
        }
        
        // Observe authentication state
        viewModelScope.launch {
            authenticationService.authState.collect { authState ->
                _uiState.update { 
                    it.copy(
                        isAuthenticated = authState.isAuthenticated,
                        username = authState.username
                    )
                }
                
                // Update error state if there's an auth error
                if (authState.error != null) {
                    _uiState.update { it.copy(error = authState.error) }
                }
            }
        }
    }
    
    fun updateServerAddress(address: String) {
        _uiState.update { it.copy(serverAddress = address) }
    }
    
    fun startStreaming(context: Context) {
        viewModelScope.launch {
            try {
                // Configure SignalK service
                signalKTransmitter.configure(_uiState.value.serverAddress)
                
                // Start location service
                locationService.startLocationUpdates(context)
                
                // Start SignalK service (this is where DNS resolution happens)
                signalKTransmitter.startStreaming()
                
                _uiState.update { it.copy(isStreaming = true, error = null) }
            } catch (e: java.net.UnknownHostException) {
                _uiState.update { it.copy(error = "Cannot resolve hostname: ${_uiState.value.serverAddress}. Check server address.") }
            } catch (e: java.net.SocketException) {
                _uiState.update { it.copy(error = "Network error: ${e.message}") }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to start streaming: ${e.message}") }
            }
        }
    }
    
    fun stopStreaming() {
        viewModelScope.launch {
            locationService.stopLocationUpdates()
            signalKTransmitter.stopStreaming()
            _uiState.update { it.copy(isStreaming = false) }
        }
    }
    
    fun login(username: String, password: String) {
        viewModelScope.launch {
            authenticationService.login(_uiState.value.serverAddress, username, password)
        }
    }
    
    fun logout() {
        viewModelScope.launch {
            authenticationService.logout()
        }
    }
}
