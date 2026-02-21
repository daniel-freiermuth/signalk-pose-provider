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
import com.signalk.companion.util.AppSettings
import com.signalk.companion.util.UrlParser
import com.signalk.companion.service.SignalKTransmitter
import com.signalk.companion.service.AuthenticationService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class TransmissionProtocol(val displayName: String, val description: String) {
    UDP("UDP", "Direct UDP transmission - fastest, requires network access"),
    WEBSOCKET("WebSocket (Auto)", "Auto-detects WS/WSS from HTTP/HTTPS URL - real-time, firewall-friendly")
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
    val parsedUrl: UrlParser.ParsedUrl? = null,
    val transmissionProtocol: TransmissionProtocol = TransmissionProtocol.WEBSOCKET,
    val vesselId: String = "self",
    val deviceOrientation: DeviceOrientation = DeviceOrientation.LANDSCAPE_LEFT,
    val compassTiltCorrection: Boolean = true,
    val headingOffset: Float = 0.0f, // Heading correction in degrees (+/- for device mounting angle)
    // Data transmission options
    val sendLocation: Boolean = true,
    val sendHeading: Boolean = true,
    val sendPressure: Boolean = true,
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
    val serverUrl: String get() = parsedUrl?.toUrlString() ?: ""
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
            
            viewModelScope.launch {
                streamingService?.error?.collect { errorMsg ->
                    if (errorMsg != null) {
                        _uiState.update { it.copy(error = errorMsg) }
                    }
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
        sensorService.setHeadingOffset(0.0f) // No offset by default
        
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
        
        // Observe authentication errors from SignalK transmitter
        viewModelScope.launch {
            signalKTransmitter.authenticationError.collect { authError ->
                if (authError != null) {
                    _uiState.update { it.copy(error = authError) }
                }
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
        val parsed = UrlParser.parseUrl(url)
        _uiState.update { it.copy(parsedUrl = parsed) }
        // Save to shared preferences
        currentContext?.let { AppSettings.setServerUrl(it, parsed?.toUrlString() ?: url) }
        
        // Warn if URL contains a path that will be ignored
        if (parsed?.hasPath == true) {
            _uiState.update { 
                it.copy(error = "Warning: URL path will be ignored. SignalK uses /signalk/v1/stream")
            }
        }
    }

    fun updateTransmissionProtocol(protocol: TransmissionProtocol) {
        _uiState.update { it.copy(transmissionProtocol = protocol) }
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

    fun updateHeadingOffset(offsetDegrees: Float) {
        _uiState.update { it.copy(headingOffset = offsetDegrees) }
        // Update sensor service with heading offset
        sensorService.setHeadingOffset(offsetDegrees)
        // If streaming service is bound, update it too
        streamingService?.updateHeadingOffset(offsetDegrees)
    }
    
    fun updateVesselId(vesselId: String) {
        val trimmedId = vesselId.trim()
        _uiState.update { it.copy(vesselId = trimmedId) }
        // Save to shared preferences
        currentContext?.let { AppSettings.setVesselId(it, trimmedId) }
    }
    
    fun updateSendLocation(enabled: Boolean) {
        _uiState.update { it.copy(sendLocation = enabled) }
        // Save to shared preferences
        currentContext?.let { AppSettings.setSendLocation(it, enabled) }
        // Update running service if active
        sendConfigUpdateToService()
    }
    
    fun updateSendHeading(enabled: Boolean) {
        _uiState.update { it.copy(sendHeading = enabled) }
        // Save to shared preferences
        currentContext?.let { AppSettings.setSendHeading(it, enabled) }
        // Update running service if active
        sendConfigUpdateToService()
    }
    
    fun updateSendPressure(enabled: Boolean) {
        _uiState.update { it.copy(sendPressure = enabled) }
        // Save to shared preferences
        currentContext?.let { AppSettings.setSendPressure(it, enabled) }
        // Update running service if active
        sendConfigUpdateToService()
    }
    
    private fun sendConfigUpdateToService() {
        currentContext?.let { context ->
            if (_uiState.value.isStreaming) {
                val intent = Intent(context, SignalKStreamingService::class.java).apply {
                    action = SignalKStreamingService.ACTION_UPDATE_CONFIG
                    putExtra(SignalKStreamingService.EXTRA_LOCATION_RATE, 1000L) // Use current default
                    putExtra(SignalKStreamingService.EXTRA_SENSOR_RATE, 1000)   // Use current default
                    putExtra(SignalKStreamingService.EXTRA_SEND_LOCATION, _uiState.value.sendLocation)
                    putExtra(SignalKStreamingService.EXTRA_SEND_HEADING, _uiState.value.sendHeading)
                    putExtra(SignalKStreamingService.EXTRA_SEND_PRESSURE, _uiState.value.sendPressure)
                }
                context.startService(intent)
            }
        }
    }
    
    fun initializeSettings(context: Context) {
        if (currentContext == null) {
            currentContext = context
            // Load settings from shared preferences
            val savedServerUrl = AppSettings.getServerUrl(context)
            val savedParsedUrl = UrlParser.parseUrl(savedServerUrl)
            val savedVesselId = AppSettings.getVesselId(context)
            val savedSendLocation = AppSettings.getSendLocation(context)
            val savedSendHeading = AppSettings.getSendHeading(context)
            val savedSendPressure = AppSettings.getSendPressure(context)
            val savedUsername = AppSettings.getUsername(context)
            
            _uiState.update { 
                it.copy(
                    parsedUrl = savedParsedUrl,
                    vesselId = savedVesselId,
                    sendLocation = savedSendLocation,
                    sendHeading = savedSendHeading,
                    sendPressure = savedSendPressure,
                    username = savedUsername.ifBlank { null }
                )
            }
            
            // Auto-login if credentials are stored
            if (AppSettings.hasCredentials(context) && savedServerUrl.isNotBlank()) {
                val savedPassword = AppSettings.getPassword(context)
                viewModelScope.launch {
                    authenticationService.login(savedServerUrl, savedUsername, savedPassword)
                }
            }
        } else {
            // Reload settings when returning from settings screen
            val savedServerUrl = AppSettings.getServerUrl(context)
            val savedParsedUrl = UrlParser.parseUrl(savedServerUrl)
            val savedVesselId = AppSettings.getVesselId(context)
            val savedSendLocation = AppSettings.getSendLocation(context)
            val savedSendHeading = AppSettings.getSendHeading(context)
            val savedSendPressure = AppSettings.getSendPressure(context)
            val savedUsername = AppSettings.getUsername(context)
            
            _uiState.update { 
                it.copy(
                    parsedUrl = savedParsedUrl,
                    vesselId = savedVesselId,
                    sendLocation = savedSendLocation,
                    sendHeading = savedSendHeading,
                    sendPressure = savedSendPressure,
                    username = savedUsername.ifBlank { null }
                )
            }
        }
    }

    fun startStreaming(context: android.content.Context) {
        currentContext = context
        
        // Validate URL before starting service
        val currentState = _uiState.value
        if (currentState.parsedUrl == null) {
            _uiState.update { 
                it.copy(error = "Invalid server URL: ${currentState.serverUrl}. Please use http://, https://, ws://, or wss:// protocol.") 
            }
            return
        }
        
        // Clear any previous errors
        _uiState.update { it.copy(error = null) }
        
        // Bind to service if not already bound
        if (!bound) {
            val intent = Intent(context, SignalKStreamingService::class.java)
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
        
        // Start streaming service
        val serviceIntent = Intent(context, SignalKStreamingService::class.java).apply {
            action = SignalKStreamingService.ACTION_START_STREAMING
            putExtra(SignalKStreamingService.EXTRA_PARSED_URL, currentState.parsedUrl)
            putExtra(SignalKStreamingService.EXTRA_LOCATION_RATE, 1000L) // Fixed 1-second interval
            putExtra(SignalKStreamingService.EXTRA_SENSOR_RATE, 1000) // Fixed 1-second interval
            putExtra(SignalKStreamingService.EXTRA_SEND_LOCATION, currentState.sendLocation)
            putExtra(SignalKStreamingService.EXTRA_SEND_HEADING, currentState.sendHeading)
            putExtra(SignalKStreamingService.EXTRA_SEND_PRESSURE, currentState.sendPressure)
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
    
    fun clearError() {
        _uiState.update { it.copy(error = null) }
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
