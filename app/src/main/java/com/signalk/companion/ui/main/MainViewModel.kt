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
import com.signalk.companion.util.DeviceCalibration
import com.signalk.companion.util.UrlParser
import com.signalk.companion.service.SignalKTransmitter
import com.signalk.companion.service.AuthenticationService
import android.util.Log
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class MainUiState(
    val isConnected: Boolean = false,
    val isStreaming: Boolean = false,
    val serverUrl: String = "", // Raw user input for the URL field
    val parsedUrl: UrlParser.ParsedUrl? = null,
    val vesselId: String = "self",
    val calibrationAlphaDeg: Float = 0f,  // ZXZ α: screen twist (charging port direction)
    val calibrationBetaDeg: Float = 0f,   // ZXZ β: tilt from horizontal [0°, 180°]
    val calibrationGammaDeg: Float = 0f,  // ZXZ γ: heading offset
    // Data transmission options
    val sendLocation: Boolean = true,
    val sendHeading: Boolean = true,
    val sendPressure: Boolean = true,
    val locationIntervalMs: Long = 1000L,
    val sensorIntervalMs: Long = 250L,
    val locationData: LocationData? = null,
    val sensorData: SensorData? = null,
    val error: String? = null,
    val lastSentMessage: String? = null,
    val messagesSent: Int = 0,
    val lastTransmissionTime: Long? = null,
    val isAuthenticated: Boolean = false,
    val username: String? = null,
    val isLoggingIn: Boolean = false
)

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
    private var authJob: Job? = null
    private var isAppInForeground = false

    companion object {
        private const val TAG = "MainViewModel"
    }
    
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
        // Configure sensor service with default (identity) calibration
        sensorService.setCalibrationAngles(0f, 0f, 0f)
        
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

        // Observe last sent message and transmission stats for UI
        viewModelScope.launch {
            signalKTransmitter.lastSentMessage.collect { msg ->
                _uiState.update { it.copy(lastSentMessage = msg) }
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
        _uiState.update { it.copy(serverUrl = url, parsedUrl = parsed) }
        // Save to shared preferences
        AppSettings.setServerUrl(applicationContext, url)
        
        // Warn if URL contains a path that will be ignored
        if (parsed?.hasPath == true) {
            _uiState.update { 
                it.copy(error = "Warning: URL path will be ignored. SignalK uses /signalk/v1/stream")
            }
        }
    }

    fun updateCalibrationAngles(alphaDeg: Float, betaDeg: Float, gammaDeg: Float) {
        Log.d(TAG, "updateCalibrationAngles: α=$alphaDeg, β=$betaDeg, γ=$gammaDeg")
        _uiState.update { it.copy(calibrationAlphaDeg = alphaDeg, calibrationBetaDeg = betaDeg, calibrationGammaDeg = gammaDeg) }
        AppSettings.setCalibrationAngles(applicationContext, alphaDeg, betaDeg, gammaDeg)
        sensorService.setCalibrationAngles(alphaDeg, betaDeg, gammaDeg)
        streamingService?.updateCalibrationAngles(alphaDeg, betaDeg, gammaDeg)
    }

    // --- App foreground lifecycle ---

    /**
     * Called from MainScreen ON_RESUME. Starts sensors and GPS for UI display
     * and calibration access.
     */
    fun onAppForeground() {
        isAppInForeground = true
        startForegroundSensors()
    }

    /**
     * Called from MainScreen ON_PAUSE. Stops sensors and GPS to save battery,
     * unless streaming is active (the service needs them).
     */
    fun onAppBackground() {
        isAppInForeground = false
        if (!_uiState.value.isStreaming) {
            stopForegroundSensors()
        }
    }

    private fun startForegroundSensors() {
        Log.d(TAG, "Starting foreground sensors")
        val state = _uiState.value
        sensorService.startSensorUpdates(
            updateIntervalMs = state.sensorIntervalMs.toInt(),
            needsHeading = true,
            needsPressure = true
        )
        viewModelScope.launch {
            try {
                locationService.startLocationUpdates(applicationContext, state.locationIntervalMs)
            } catch (e: SecurityException) {
                Log.e(TAG, "Location permission not granted for foreground display", e)
            }
        }
    }

    private fun stopForegroundSensors() {
        Log.d(TAG, "Stopping foreground sensors")
        sensorService.stopSensorUpdates()
        locationService.stopLocationUpdates()
    }

    /**
     * Full calibration: use current sensor reading + GPS heading to compute all three angles.
     * If no GPS heading is available, only calibrates pitch and roll.
     */
    fun calibrateAll() {
        viewModelScope.launch {
            Log.d(TAG, "calibrateAll: starting, sensorsActive=${sensorService.isSensorUpdatesActive()}, hasRotation=${sensorService.hasValidRotationMatrix()}")
            if (!ensureSensorData()) {
                Log.w(TAG, "calibrateAll: sensor data not available within timeout")
                _uiState.update { it.copy(error = "Calibration failed: no sensor data available") }
                return@launch
            }
            val R_W_D = sensorService.getCurrentRotationMatrix()
            Log.d(TAG, "calibrateAll: R_W_D=[${R_W_D.joinToString()}]")
            val location = locationService.locationUpdates.value
            val bearing = location?.bearing
            val speed = location?.speed
            Log.d(TAG, "calibrateAll: bearing=$bearing, speed=$speed")

            if (bearing != null && speed != null && speed > 0.5f) {
                val R_W_V = DeviceCalibration.buildFlatHeadingMatrix(bearing)
                val R_D_V = DeviceCalibration.computeCalibration(R_W_D, R_W_V)
                val (alpha, beta, gamma) = DeviceCalibration.decomposeZXZ(R_D_V)
                Log.d(TAG, "calibrateAll: GPS path → α=$alpha, β=$beta, γ=$gamma")
                updateCalibrationAngles(alpha, beta, gamma)
            } else {
                Log.d(TAG, "calibrateAll: no GPS heading, falling back to pitch/roll only")
                calibratePitchRollInternal()
            }
        }
    }

    /**
     * Calibrate only the azimuth (horizontal heading) using GPS heading.
     * Preserves existing tilt calibration, even if the vehicle is tilted by waves/wind.
     */
    fun calibrateAzimuth() {
        viewModelScope.launch {
            if (!ensureSensorData()) {
                Log.w(TAG, "calibrateAzimuth: sensor data not available within timeout")
                _uiState.update { it.copy(error = "Calibration failed: no sensor data available") }
                return@launch
            }
            val R_W_D = sensorService.getCurrentRotationMatrix()
            val location = locationService.locationUpdates.value
            val bearing = location?.bearing
            val speed = location?.speed
            Log.d(TAG, "calibrateAzimuth: bearing=$bearing, speed=$speed")
            if (bearing == null || speed == null || speed <= 0.5f) {
                Log.w(TAG, "calibrateAzimuth: GPS heading not available")
                return@launch
            }

            val existingCalibration = DeviceCalibration.composeZXZ(
                _uiState.value.calibrationAlphaDeg,
                _uiState.value.calibrationBetaDeg,
                _uiState.value.calibrationGammaDeg
            )
            val (newGamma, R_D_V) = DeviceCalibration.calibrateAzimuth(
                R_W_D, existingCalibration, _uiState.value.calibrationGammaDeg, bearing
            )
            val (alpha, beta, gamma) = DeviceCalibration.decomposeZXZ(R_D_V)
            Log.d(TAG, "calibrateAzimuth: α=$alpha, β=$beta, γ=$gamma (was γ=${_uiState.value.calibrationGammaDeg})")
            updateCalibrationAngles(alpha, beta, gamma)
        }
    }

    /**
     * Calibrate twist and tilt only. Assumes the vehicle is currently flat.
     * Keeps existing heading offset (γ) unchanged.
     */
    fun calibrateTilt() {
        viewModelScope.launch {
            if (!ensureSensorData()) {
                Log.w(TAG, "calibrateTilt: sensor data not available within timeout")
                _uiState.update { it.copy(error = "Calibration failed: no sensor data available") }
                return@launch
            }
            calibrateTiltInternal()
        }
    }

    private fun calibrateTiltInternal() {
        val R_W_D = sensorService.getCurrentRotationMatrix()
        Log.d(TAG, "calibrateTiltInternal: R_W_D=[${R_W_D.joinToString()}]")
        val R_D_V = DeviceCalibration.calibrateTilt(R_W_D, _uiState.value.calibrationGammaDeg)
        val (alpha, beta, gamma) = DeviceCalibration.decomposeZXZ(R_D_V)
        Log.d(TAG, "calibrateTiltInternal: α=$alpha, β=$beta, γ=$gamma")
        updateCalibrationAngles(alpha, beta, gamma)
    }

    /**
     * Waits for sensors to produce a valid rotation matrix.
     * Sensors should already be running (started in onAppForeground).
     */
    private suspend fun ensureSensorData(): Boolean {
        return withTimeoutOrNull(2000L) {
            while (!sensorService.hasValidRotationMatrix()) {
                delay(50)
            }
            true
        } != null
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

    fun updateLocationIntervalMs(intervalMs: Long) {
        _uiState.update { it.copy(locationIntervalMs = intervalMs) }
        AppSettings.setLocationIntervalMs(applicationContext, intervalMs)
        sendConfigUpdateToService()
    }

    fun updateSensorIntervalMs(intervalMs: Long) {
        _uiState.update { it.copy(sensorIntervalMs = intervalMs) }
        AppSettings.setSensorIntervalMs(applicationContext, intervalMs)
        sendConfigUpdateToService()
    }
    
    private fun sendConfigUpdateToService() {
        if (_uiState.value.isStreaming) {
            val intent = Intent(applicationContext, SignalKStreamingService::class.java).apply {
                action = SignalKStreamingService.ACTION_UPDATE_CONFIG
                putExtra(SignalKStreamingService.EXTRA_LOCATION_RATE, _uiState.value.locationIntervalMs)
                putExtra(SignalKStreamingService.EXTRA_SENSOR_RATE, _uiState.value.sensorIntervalMs.toInt())
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
        val savedLocationIntervalMs = AppSettings.getLocationIntervalMs(applicationContext)
        val savedSensorIntervalMs = AppSettings.getSensorIntervalMs(applicationContext)
        val savedUsername = AppSettings.getUsername(applicationContext)
        
        // Load calibration angles
        val savedAlpha = AppSettings.getCalibrationAlphaDeg(applicationContext)
        val savedBeta = AppSettings.getCalibrationBetaDeg(applicationContext)
        val savedGamma = AppSettings.getCalibrationGammaDeg(applicationContext)
        
        // Apply calibration to sensor service
        sensorService.setCalibrationAngles(savedAlpha, savedBeta, savedGamma)
        
        _uiState.update { 
            it.copy(
                serverUrl = savedServerUrl,
                parsedUrl = savedParsedUrl,
                vesselId = savedVesselId,
                sendLocation = savedSendLocation,
                sendHeading = savedSendHeading,
                sendPressure = savedSendPressure,
                locationIntervalMs = savedLocationIntervalMs,
                sensorIntervalMs = savedSensorIntervalMs,
                username = savedUsername.ifBlank { null },
                calibrationAlphaDeg = savedAlpha,
                calibrationBetaDeg = savedBeta,
                calibrationGammaDeg = savedGamma
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
            putExtra(SignalKStreamingService.EXTRA_LOCATION_RATE, currentState.locationIntervalMs)
            putExtra(SignalKStreamingService.EXTRA_SENSOR_RATE, currentState.sensorIntervalMs.toInt())
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

        // Update state eagerly: cleanupServiceBinding cancels the service collector before
        // the service can emit isStreaming=false, which would leave the button stuck in "Stop" state.
        _uiState.update { it.copy(isStreaming = false) }

        cleanupServiceBinding(unbind = true)

        // Service will asynchronously stop sensors in its stopStreaming().
        // Restart for foreground display after the service finishes processing.
        if (isAppInForeground) {
            viewModelScope.launch {
                delay(500)
                startForegroundSensors()
            }
        }
    }
    
    fun login(username: String, password: String) {
        authJob?.cancel()
        authJob = viewModelScope.launch {
            authenticationService.login(_uiState.value.serverUrl, username, password)
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
    }
    
    fun getAvailableSensors(): Map<String, Boolean> {
        return sensorService.getAvailableSensors()
    }
    
    override fun onCleared() {
        super.onCleared()
        cleanupServiceBinding(unbind = true)
        stopForegroundSensors()
    }
}
