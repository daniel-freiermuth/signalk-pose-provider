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

enum class TransmissionProtocol(val displayName: String, val description: String) {
    UDP("UDP", "Direct UDP transmission - fastest, requires network access"),
    WEBSOCKET("WebSocket (Auto)", "Auto-detects WS/WSS from HTTP/HTTPS URL - real-time, firewall-friendly"),
    WEBSOCKET_SSL("WebSocket SSL", "Forces secure WSS streaming - encrypted, same as HTTPS login")
}

data class MainUiState(
    val isConnected: Boolean = false,
    val isStreaming: Boolean = false,
    val serverUrl: String = "http://192.168.1.100:3000",
    val transmissionProtocol: TransmissionProtocol = TransmissionProtocol.UDP,
    val locationData: LocationData? = null,
    val sensorData: SensorData? = null,
    val error: String? = null,
    val lastSentMessage: String? = null,
    val messagesSent: Int = 0,
    val lastTransmissionTime: Long? = null,
    val isAuthenticated: Boolean = false,
    val username: String? = null,
    val isLoggingIn: Boolean = false
) {
    // Parse the URL to extract components
    private val parsedUrl: ParsedUrl get() = parseUrl(serverUrl)
    
    val hostname: String get() = parsedUrl.hostname
    val port: Int get() = parsedUrl.port
    val isHttps: Boolean get() = parsedUrl.isHttps
    
    // For UDP streaming, use hostname with port 55555
    val udpAddress: String get() = "$hostname:55555"
}

private data class ParsedUrl(
    val hostname: String,
    val port: Int,
    val isHttps: Boolean
)

private fun parseUrl(url: String): ParsedUrl {
    return try {
        val cleanUrl = url.lowercase().let { 
            if (!it.startsWith("http://") && !it.startsWith("https://")) {
                "http://$it" // Default to HTTP if no protocol specified
            } else it
        }
        
        val isHttps = cleanUrl.startsWith("https://")
        val withoutProtocol = cleanUrl.removePrefix("https://").removePrefix("http://")
        val parts = withoutProtocol.split(":")
        
        val hostname = parts[0].trim()
        val port = if (parts.size > 1) {
            parts[1].split("/")[0].toIntOrNull() ?: (if (isHttps) 443 else 80)
        } else {
            if (isHttps) 443 else 80
        }
        
        ParsedUrl(hostname, port, isHttps)
    } catch (e: Exception) {
        // Fallback for invalid URLs
        ParsedUrl("192.168.1.100", 3000, false)
    }
}

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
                        username = authState.username,
                        isLoggingIn = authState.isLoading
                    )
                }
                
                // Update error state if there's an auth error
                if (authState.error != null) {
                    _uiState.update { it.copy(error = authState.error) }
                }
            }
        }
    }
    
    fun updateServerUrl(url: String) {
        _uiState.update { it.copy(serverUrl = url) }
    }

    fun updateTransmissionProtocol(protocol: TransmissionProtocol) {
        _uiState.update { it.copy(transmissionProtocol = protocol) }
    }
    
    fun startStreaming(context: android.content.Context) {
        viewModelScope.launch {
            try {
                val currentState = _uiState.value
                
                // Configure SignalK service - auto-detect WebSocket protocol based on HTTP/HTTPS
                when (currentState.transmissionProtocol) {
                    TransmissionProtocol.UDP -> {
                        signalKTransmitter.configureFromUrl(currentState.serverUrl, TransmissionProtocol.UDP)
                    }
                    TransmissionProtocol.WEBSOCKET, TransmissionProtocol.WEBSOCKET_SSL -> {
                        // Auto-detect WS vs WSS based on HTTP vs HTTPS in the URL
                        signalKTransmitter.configureWebSocketFromHttpUrl(currentState.serverUrl)
                    }
                }
                
                // Start location service
                locationService.startLocationUpdates(context)
                
                // Start SignalK service (this is where DNS resolution happens)
                signalKTransmitter.startStreaming()
                
                _uiState.update { it.copy(isStreaming = true, error = null) }
            } catch (e: java.net.UnknownHostException) {
                _uiState.update { it.copy(error = "Cannot resolve hostname: ${_uiState.value.hostname}. Check server address.") }
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
            authenticationService.login(_uiState.value.serverUrl, username, password)
        }
    }
    
    fun logout() {
        viewModelScope.launch {
            authenticationService.logout()
        }
    }
}
