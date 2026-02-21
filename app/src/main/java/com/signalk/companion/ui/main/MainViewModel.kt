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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class DeviceOrientation(val displayName: String, val rotationDegrees: Int, val description: String) {
    PORTRAIT("Portrait", 0, "Phone held normally (top of phone = bow)"),
    LANDSCAPE_LEFT("Landscape Left", 90, "Phone rotated 90° CCW (left side = bow)"),
    LANDSCAPE_RIGHT("Landscape Right", 270, "Phone rotated 90° CW (right side = bow)"),
    PORTRAIT_INVERTED("Portrait Inverted", 180, "Phone upside down (bottom = bow)");
    
    companion object {
        val DEFAULT = LANDSCAPE_LEFT
    }
}

data class MainUiState(
    val isConnected: Boolean = false,
    val isStreaming: Boolean = false,
    val parsedUrl: UrlParser.ParsedUrl? = null,
    val vesselId: String = "self",
    val deviceOrientation: DeviceOrientation = DeviceOrientation.DEFAULT,
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
    @ApplicationContext private val applicationContext: Context,
    private val locationService: LocationService,
    private val sensorService: SensorService,
    private val signalKTransmitter: SignalKTransmitter,
    private val authenticationService: AuthenticationService
) : ViewModel() {
    
    private var streamingService: SignalKStreamingService? = null
    private var bound = false
    private var serviceCollectorJob: Job? = null
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as SignalKStreamingService.LocalBinder
            streamingService = binder.getService()
            bound = true
            
            // Cancel any previous collectors and wait for completion before starting new ones
            viewModelScope.launch {
                serviceCollectorJob?.cancelAndJoin()
                startServiceCollectors()
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            cleanupServiceBinding(unbind = false)
        }
    }
    
    /**
     * Starts coroutines to collect service state flows.
     * Must be called from a coroutine context after ensuring previous collectors are cancelled.
     */
    private fun startServiceCollectors() {
        // Capture service reference to ensure all collectors use the same instance
        val service = streamingService ?: return
        
        serviceCollectorJob = viewModelScope.launch {
            launch {
                service.isStreaming.collect { isStreaming ->
                    _uiState.update { it.copy(isStreaming = isStreaming) }
                }
            }
            
            launch {
                service.messagesSent.collect { count ->
                    _uiState.update { it.copy(messagesSent = count) }
                }
            }
            
            launch {
                service.lastTransmissionTime.collect { time ->
                    _uiState.update { it.copy(lastTransmissionTime = time) }
                }
            }
            
            launch {
                service.error.collect { errorMsg ->
                    if (errorMsg != null) {
                        _uiState.update { it.copy(error = errorMsg) }
                    }
                }
            }
        }
    }
    
    /**
     * Cleans up service binding state. Call when disconnecting from the service.
     * @param unbind If true, unbinds from the service. Set to false when called from onServiceDisconnected
     *               since the system has already unbound us.
     */
    private fun cleanupServiceBinding(unbind: Boolean) {
        serviceCollectorJob?.cancel()
        serviceCollectorJob = null
        
        if (unbind && bound) {
            applicationContext.unbindService(serviceConnection)
        }
        bound = false
        streamingService = null
    }
    
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    
    init {
        // Configure sensor service for boat mounting (landscape left by default)
        sensorService.setDeviceOrientation(DeviceOrientation.DEFAULT)
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
        AppSettings.setServerUrl(applicationContext, parsed?.toUrlString() ?: url)
        
        // Warn if URL contains a path that will be ignored
        if (parsed?.hasPath == true) {
            _uiState.update { 
                it.copy(error = "Warning: URL path will be ignored. SignalK uses /signalk/v1/stream")
            }
        }
    }

    fun updateDeviceOrientation(orientation: DeviceOrientation) {
        _uiState.update { it.copy(deviceOrientation = orientation) }
        // Save to shared preferences
        AppSettings.setDeviceOrientation(applicationContext, orientation.name)
        // Update sensor service with new orientation
        sensorService.setDeviceOrientation(orientation)
        // If streaming service is bound, update it too
        streamingService?.updateDeviceOrientation(orientation)
    }

    fun updateCompassTiltCorrection(enabled: Boolean) {
        _uiState.update { it.copy(compassTiltCorrection = enabled) }
        // Save to shared preferences
        AppSettings.setCompassTiltCorrection(applicationContext, enabled)
        // Update sensor service with tilt correction setting
        sensorService.setTiltCorrection(enabled)
        // If streaming service is bound, update it too
        streamingService?.updateTiltCorrection(enabled)
    }

    fun updateHeadingOffset(offsetDegrees: Float) {
        _uiState.update { it.copy(headingOffset = offsetDegrees) }
        // Save to shared preferences
        AppSettings.setHeadingOffset(applicationContext, offsetDegrees)
        // Update sensor service with heading offset
        sensorService.setHeadingOffset(offsetDegrees)
        // If streaming service is bound, update it too
        streamingService?.updateHeadingOffset(offsetDegrees)
    }
    
    fun updateVesselId(vesselId: String) {
        val trimmedId = vesselId.trim()
        _uiState.update { it.copy(vesselId = trimmedId) }
        // Save to shared preferences
        AppSettings.setVesselId(applicationContext, trimmedId)
    }
    
    fun updateSendLocation(enabled: Boolean) {
        _uiState.update { it.copy(sendLocation = enabled) }
        // Save to shared preferences
        AppSettings.setSendLocation(applicationContext, enabled)
        // Update running service if active
        sendConfigUpdateToService()
    }
    
    fun updateSendHeading(enabled: Boolean) {
        _uiState.update { it.copy(sendHeading = enabled) }
        // Save to shared preferences
        AppSettings.setSendHeading(applicationContext, enabled)
        // Update running service if active
        sendConfigUpdateToService()
    }
    
    fun updateSendPressure(enabled: Boolean) {
        _uiState.update { it.copy(sendPressure = enabled) }
        // Save to shared preferences
        AppSettings.setSendPressure(applicationContext, enabled)
        // Update running service if active
        sendConfigUpdateToService()
    }
    
    private fun sendConfigUpdateToService() {
        if (_uiState.value.isStreaming) {
            val intent = Intent(applicationContext, SignalKStreamingService::class.java).apply {
                action = SignalKStreamingService.ACTION_UPDATE_CONFIG
                putExtra(SignalKStreamingService.EXTRA_LOCATION_RATE, 1000L) // Use current default
                putExtra(SignalKStreamingService.EXTRA_SENSOR_RATE, 1000)   // Use current default
                putExtra(SignalKStreamingService.EXTRA_SEND_LOCATION, _uiState.value.sendLocation)
                putExtra(SignalKStreamingService.EXTRA_SEND_HEADING, _uiState.value.sendHeading)
                putExtra(SignalKStreamingService.EXTRA_SEND_PRESSURE, _uiState.value.sendPressure)
            }
            applicationContext.startService(intent)
        }
    }
    
    private var settingsInitialized = false
    
    /**
     * Loads settings from shared preferences. Safe to call multiple times;
     * auto-login only occurs on first invocation.
     */
    fun initializeSettings() {
        // Load settings from shared preferences
        val savedServerUrl = AppSettings.getServerUrl(applicationContext)
        val savedParsedUrl = UrlParser.parseUrl(savedServerUrl)
        val savedVesselId = AppSettings.getVesselId(applicationContext)
        val savedSendLocation = AppSettings.getSendLocation(applicationContext)
        val savedSendHeading = AppSettings.getSendHeading(applicationContext)
        val savedSendPressure = AppSettings.getSendPressure(applicationContext)
        val savedUsername = AppSettings.getUsername(applicationContext)
        
        // Load device orientation and compass settings
        val savedOrientationName = AppSettings.getDeviceOrientation(applicationContext)
        val savedOrientation = DeviceOrientation.values()
            .find { it.name == savedOrientationName } 
            ?: DeviceOrientation.DEFAULT
        val savedTiltCorrection = AppSettings.getCompassTiltCorrection(applicationContext)
        val savedHeadingOffset = AppSettings.getHeadingOffset(applicationContext)
        
        // Apply orientation and compass settings to sensor service
        sensorService.setDeviceOrientation(savedOrientation)
        sensorService.setTiltCorrection(savedTiltCorrection)
        sensorService.setHeadingOffset(savedHeadingOffset)
        
        _uiState.update { 
            it.copy(
                parsedUrl = savedParsedUrl,
                vesselId = savedVesselId,
                sendLocation = savedSendLocation,
                sendHeading = savedSendHeading,
                sendPressure = savedSendPressure,
                username = savedUsername.ifBlank { null },
                deviceOrientation = savedOrientation,
                compassTiltCorrection = savedTiltCorrection,
                headingOffset = savedHeadingOffset
            )
        }
        
        // Auto-login if credentials are stored (only on first initialization)
        if (!settingsInitialized && 
            AppSettings.hasCredentials(applicationContext) && 
            savedServerUrl.isNotBlank()) {
            val savedPassword = AppSettings.getPassword(applicationContext)
            viewModelScope.launch {
                authenticationService.login(savedServerUrl, savedUsername, savedPassword)
            }
        }
        settingsInitialized = true
    }

    fun startStreaming() {
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
            val intent = Intent(applicationContext, SignalKStreamingService::class.java)
            applicationContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
        
        // Start streaming service
        val serviceIntent = Intent(applicationContext, SignalKStreamingService::class.java).apply {
            action = SignalKStreamingService.ACTION_START_STREAMING
            putExtra(SignalKStreamingService.EXTRA_PARSED_URL, currentState.parsedUrl)
            putExtra(SignalKStreamingService.EXTRA_LOCATION_RATE, 1000L) // Fixed 1-second interval
            putExtra(SignalKStreamingService.EXTRA_SENSOR_RATE, 1000) // Fixed 1-second interval
            putExtra(SignalKStreamingService.EXTRA_SEND_LOCATION, currentState.sendLocation)
            putExtra(SignalKStreamingService.EXTRA_SEND_HEADING, currentState.sendHeading)
            putExtra(SignalKStreamingService.EXTRA_SEND_PRESSURE, currentState.sendPressure)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(serviceIntent)
        } else {
            applicationContext.startService(serviceIntent)
        }
    }

    fun stopStreaming() {
        val serviceIntent = Intent(applicationContext, SignalKStreamingService::class.java).apply {
            action = SignalKStreamingService.ACTION_STOP_STREAMING
        }
        applicationContext.startService(serviceIntent)
        
        cleanupServiceBinding(unbind = true)
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
        cleanupServiceBinding(unbind = true)
    }
}
