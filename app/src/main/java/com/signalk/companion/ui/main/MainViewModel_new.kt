package com.signalk.companion.ui.main

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.signalk.companion.data.model.LocationData
import com.signalk.companion.data.model.SensorData
import com.signalk.companion.service.LocationService
import com.signalk.companion.service.SensorService
import com.signalk.companion.service.SignalKStreamingService
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

enum class UpdateRate(val displayName: String, val intervalMs: Long, val description: String) {
    FAST("Fast (0.5s)", 500L, "2 Hz - High precision, more battery usage"),
    NORMAL("Normal (1s)", 1000L, "1 Hz - Balanced performance and battery"),
    SLOW("Slow (2s)", 2000L, "0.5 Hz - Battery efficient, lower precision"),
    VERY_SLOW("Very Slow (5s)", 5000L, "0.2 Hz - Maximum battery life")
}

enum class DeviceOrientation(val displayName: String, val rotationDegrees: Int, val description: String) {
    PORTRAIT("Portrait", 0, "Phone held normally (top of phone = bow)"),
    LANDSCAPE_LEFT("Landscape Left", 90, "Phone rotated 90° CCW (left side = bow)"),
    LANDSCAPE_RIGHT("Landscape Right", 270, "Phone rotated 90° CW (right side = bow)"),
    PORTRAIT_INVERTED("Portrait Inverted", 180, "Phone upside down (bottom = bow)")
}

data class MainUiState(
    val isConnected: Boolean = false,
    val isStreaming: Boolean = false,
    val serverUrl: String = "https://signalk.entrop.mywire.org",
    val transmissionProtocol: TransmissionProtocol = TransmissionProtocol.UDP,
    val locationUpdateRate: UpdateRate = UpdateRate.NORMAL,
    val sensorUpdateRate: UpdateRate = UpdateRate.NORMAL,
    val deviceOrientation: DeviceOrientation = DeviceOrientation.LANDSCAPE_LEFT,
    val compassTiltCorrection: Boolean = true,
    val locationData: LocationData? = null,
    val sensorData: SensorData? = null,
    val error: String? = null,
    val lastSentMessage: String? = null,
    val messagesSent: Int = 0,
    val lastTransmissionTime: Long? = null,
    val isAuthenticated: Boolean = false,
    val username: String? = "test",
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
    private val sensorService: SensorService,
    private val signalKTransmitter: SignalKTransmitter,
    private val authenticationService: AuthenticationService
) : ViewModel() {
    
    private var currentContext: Context? = null
    private var streamingService: SignalKStreamingService? = null
    private var bound = false
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as SignalKStreamingService.LocalBinder
            streamingService = binder.getService()
            bound = true
            
            // Observe service state
            viewModelScope.launch {
                streamingService?.isStreaming?.collect { isStreaming ->
                    _uiState.update { it.copy(isStreaming = isStreaming) }
                }
            }
            
            viewModelScope.launch {
                streamingService?.messagesSent?.collect { count ->
                    _uiState.update { it.copy(messagesSent = count) }
                }
            }
            
            viewModelScope.launch {
                streamingService?.lastTransmissionTime?.collect { time ->
                    _uiState.update { it.copy(lastTransmissionTime = time) }
                }
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            streamingService = null
            bound = false
        }
    }
    
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    
    init {
        // Configure sensor service for boat mounting (landscape left by default)
        sensorService.setDeviceOrientation(DeviceOrientation.LANDSCAPE_LEFT)
        sensorService.setTiltCorrection(true)
        
        // Still observe location and sensor data for UI display (but not for transmission)
        viewModelScope.launch {
            locationService.locationUpdates.collect { locationData ->
                _uiState.update { it.copy(locationData = locationData) }
            }
        }
        
        viewModelScope.launch {
            sensorService.sensorData.collect { sensorData ->
                _uiState.update { it.copy(sensorData = sensorData) }
            }
        }
        
        // Observe SignalK connection status for UI
        viewModelScope.launch {
            signalKTransmitter.connectionStatus.collect { isConnected ->
                _uiState.update { it.copy(isConnected = isConnected) }
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
    
    fun updateLocationUpdateRate(rate: UpdateRate) {
        _uiState.update { it.copy(locationUpdateRate = rate) }
        // If currently streaming, update service rate
        streamingService?.updateLocationRate(rate)
    }

    fun updateSensorRate(rate: UpdateRate) {
        _uiState.update { it.copy(sensorUpdateRate = rate) }
        // If currently streaming, update service rate
        streamingService?.updateSensorRate(rate)
    }

    fun updateDeviceOrientation(orientation: DeviceOrientation) {
        _uiState.update { it.copy(deviceOrientation = orientation) }
        // Update sensor service with new orientation
        sensorService.setDeviceOrientation(orientation)
        // If streaming service is bound, update it too
        streamingService?.updateDeviceOrientation(orientation)
    }

    fun updateCompassTiltCorrection(enabled: Boolean) {
        _uiState.update { it.copy(compassTiltCorrection = enabled) }
        // Update sensor service with tilt correction setting
        sensorService.setTiltCorrection(enabled)
        // If streaming service is bound, update it too
        streamingService?.updateTiltCorrection(enabled)
    }

    fun startStreaming(context: android.content.Context) {
        currentContext = context
        
        // Bind to service if not already bound
        if (!bound) {
            val intent = Intent(context, SignalKStreamingService::class.java)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
        
        // Start streaming service
        val currentState = _uiState.value
        val serviceIntent = Intent(context, SignalKStreamingService::class.java).apply {
            action = SignalKStreamingService.ACTION_START_STREAMING
            putExtra(SignalKStreamingService.EXTRA_SERVER_URL, currentState.serverUrl)
            putExtra(SignalKStreamingService.EXTRA_LOCATION_RATE, currentState.locationUpdateRate.intervalMs)
            putExtra(SignalKStreamingService.EXTRA_SENSOR_RATE, currentState.sensorUpdateRate.intervalMs.toInt())
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    fun stopStreaming() {
        currentContext?.let { context ->
            val serviceIntent = Intent(context, SignalKStreamingService::class.java).apply {
                action = SignalKStreamingService.ACTION_STOP_STREAMING
            }
            context.startService(serviceIntent)
            
            // Unbind from service
            if (bound) {
                context.unbindService(serviceConnection)
                bound = false
            }
        }
        currentContext = null
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
    
    fun getAvailableSensors(): Map<String, Boolean> {
        return sensorService.getAvailableSensors()
    }
    
    override fun onCleared() {
        super.onCleared()
        // Unbind service when ViewModel is cleared
        if (bound && currentContext != null) {
            currentContext!!.unbindService(serviceConnection)
            bound = false
        }
    }
}
