package com.signalk.companion.ui.main

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.signalk.companion.data.model.LocationData
import com.signalk.companion.data.model.SensorData
import com.signalk.companion.replay.RecordingSession
import com.signalk.companion.service.AttitudeEngine
import com.signalk.companion.service.AuthenticationService
import com.signalk.companion.service.LocationService
import com.signalk.companion.service.SensorService
import com.signalk.companion.service.SignalKStreamingService
import com.signalk.companion.service.SignalKTransmitter
import com.signalk.companion.util.CalibrationSettings
import com.signalk.companion.util.ConnectionSettings
import com.signalk.companion.util.StreamingSettings
import com.signalk.companion.util.TransmissionSettings
import com.signalk.companion.util.UrlParser
import com.signalk.companion.util.VesselSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainUiState(
    val isConnected: Boolean = false,
    val isStreaming: Boolean = false,
    val serverUrl: String = "", // Raw user input for the URL field
    val parsedUrl: UrlParser.ParsedUrl? = null,
    val vesselId: String = "self",
    val calibrationAlphaDeg: Float = 0f, // ZXZ α: screen twist (charging port direction)
    val calibrationBetaDeg: Float = 0f, // ZXZ β: tilt from horizontal [0°, 180°]
    val calibrationGammaDeg: Float = 0f, // ZXZ γ: heading offset
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
    val isLoggingIn: Boolean = false,
    /** M1 raw recording: file name, record count, byte count, truncation. */
    val recording: RecordingSession.Status = RecordingSession.Status(),
    /**
     * The M1 filter's pose, shown next to the legacy value as a comparison trace. Null until
     * a recording is running — the engine is what produces it, and it is not published.
     */
    val attitude: AttitudeEngine.State? = null
)

// LongParameterList: the constructor is the Hilt injection point for the main screen. Each
// parameter is a distinct app-scoped singleton the screen observes or drives; bundling some of
// them into a holder only to lower the count would add an indirection that means nothing.
// Placed on the class because ktlint requires constructor annotations with arguments to sit
// on separate lines; no function in the class comes close to the limit.
@Suppress("LongParameterList")
@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val locationService: LocationService,
    private val sensorService: SensorService,
    private val signalKTransmitter: SignalKTransmitter,
    private val authenticationService: AuthenticationService,
    private val recordingSession: RecordingSession,
    private val attitudeEngine: AttitudeEngine
) : ViewModel() {

    // A reference to the bound local service, not a leaked Context: it is set only between
    // onServiceConnected and onServiceDisconnected/unbindService (cleanupServiceBinding, also
    // run from onCleared), and a bound service cannot be destroyed while this binding holds it.
    @SuppressLint("StaticFieldLeak")
    private var streamingService: SignalKStreamingService? = null
    private var bound = false
    private var serviceCollectorJob: Job? = null
    private var isAppInForeground = false

    companion object {
        private const val TAG = "MainViewModel"

        /** Grace period for the service to release the sensors before the UI restarts them. */
        private const val SENSOR_RESTART_DELAY_MS = 500L
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
     * @param unbind If true, unbinds from the service. Set to false when called from
     *               onServiceDisconnected since the system has already unbound us.
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

    /** Mount calibration, applied to the local sensors and to a running streaming service. */
    val calibration = CalibrationController(
        applicationContext,
        viewModelScope,
        _uiState,
        sensorService,
        locationService
    ) { alphaDeg, betaDeg, gammaDeg ->
        streamingService?.updateCalibrationAngles(alphaDeg, betaDeg, gammaDeg)
    }

    /** What is transmitted to the SignalK server, and how often. */
    val transmissionOptions = TransmissionOptionsController(applicationContext, _uiState)

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

        // M1 recording status and the filter's own pose. Both are observed unconditionally —
        // they are cheap StateFlows that sit idle when nothing is recording, and the
        // recording outlives this ViewModel, so the UI must be able to rejoin one in progress.
        viewModelScope.launch {
            recordingSession.status.collect { status ->
                _uiState.update { it.copy(recording = status) }
            }
        }

        viewModelScope.launch {
            attitudeEngine.state.collect { attitude ->
                _uiState.update { it.copy(attitude = attitude) }
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
        // A recording needs GNSS as much as streaming does, and backgrounding the app is the
        // normal state of a phone on a boat — shutting location down here would silently
        // produce a recording with no fixes in it.
        //
        // Reads recordingSession.isRecording directly rather than the UI state: the service
        // sets it the moment a recording starts, but the StateFlow collector that copies it
        // into _uiState runs asynchronously, so backgrounding the app in that window would
        // otherwise see stale (not-yet-updated) UI state and stop GNSS out from under a
        // recording that has already begun.
        if (!_uiState.value.isStreaming && !recordingSession.isRecording) {
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
        // GNSS is shared: the foreground UI reads it, but so does an active recording, which
        // collects its FixRecords from this same LocationService. Stopping it here because
        // the UI no longer needs it would silently end the fix stream in a recording that is
        // still running, and nothing on this path ever restarts it.
        if (!recordingSession.isRecording) {
            locationService.stopLocationUpdates()
        }
    }

    private var settingsInitialized = false

    /**
     * Loads settings from shared preferences. Safe to call multiple times;
     * auto-login only occurs on first invocation.
     */
    fun initializeSettings() {
        // Load settings from shared preferences
        val savedServerUrl = ConnectionSettings.getServerUrl(applicationContext)
        val savedParsedUrl = UrlParser.parseUrl(savedServerUrl)
        val savedVesselId = VesselSettings.getVesselId(applicationContext)
        val savedSendLocation = TransmissionSettings.getSendLocation(applicationContext)
        val savedSendHeading = TransmissionSettings.getSendHeading(applicationContext)
        val savedSendPressure = TransmissionSettings.getSendPressure(applicationContext)
        val savedLocationIntervalMs = StreamingSettings.getLocationIntervalMs(applicationContext)
        val savedSensorIntervalMs = StreamingSettings.getSensorIntervalMs(applicationContext)
        val savedUsername = ConnectionSettings.getUsername(applicationContext)

        // Load calibration angles
        val savedAlpha = CalibrationSettings.getCalibrationAlphaDeg(applicationContext)
        val savedBeta = CalibrationSettings.getCalibrationBetaDeg(applicationContext)
        val savedGamma = CalibrationSettings.getCalibrationGammaDeg(applicationContext)

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
            ConnectionSettings.hasCredentials(applicationContext) &&
            savedServerUrl.isNotBlank()
        ) {
            val savedPassword = ConnectionSettings.getPassword(applicationContext)
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
                it.copy(
                    error = "Invalid server URL: ${currentState.serverUrl}. " +
                        "Please use http://, https://, ws://, or wss:// protocol."
                )
            }
            return
        }

        // Clear any previous errors
        _uiState.update { it.copy(error = null) }

        // Bind to service if not already bound
        if (!bound) bindStreamingService()

        // Start streaming service
        val serviceIntent = Intent(applicationContext, SignalKStreamingService::class.java).apply {
            action = SignalKStreamingService.ACTION_START_STREAMING
            putExtra(SignalKStreamingService.EXTRA_PARSED_URL, currentState.parsedUrl)
            putExtra(
                SignalKStreamingService.EXTRA_LOCATION_RATE,
                currentState.locationIntervalMs
            )
            putExtra(
                SignalKStreamingService.EXTRA_SENSOR_RATE,
                currentState.sensorIntervalMs.toInt()
            )
            putExtra(SignalKStreamingService.EXTRA_SEND_LOCATION, currentState.sendLocation)
            putExtra(SignalKStreamingService.EXTRA_SEND_HEADING, currentState.sendHeading)
            putExtra(SignalKStreamingService.EXTRA_SEND_PRESSURE, currentState.sendPressure)
        }

        applicationContext.sendToStreamingService(serviceIntent, foreground = true)
    }

    fun stopStreaming() {
        val serviceIntent = Intent(applicationContext, SignalKStreamingService::class.java).apply {
            action = SignalKStreamingService.ACTION_STOP_STREAMING
        }
        applicationContext.sendToStreamingService(serviceIntent)

        // Update state eagerly: cleanupServiceBinding cancels the service collector before
        // the service can emit isStreaming=false, which would leave the button stuck in
        // "Stop" state.
        _uiState.update { it.copy(isStreaming = false) }

        // A recording may still be running after streaming stops, and the service stays alive
        // for it (see SignalKStreamingService.stopStreaming()). Unbinding here would clear
        // streamingService and silently drop every calibration change until the recording
        // also stops - updateCalibrationAngles() has no other way to reach it.
        if (!recordingSession.isRecording) {
            cleanupServiceBinding(unbind = true)
        }

        // Service will asynchronously stop sensors in its stopStreaming().
        // Restart for foreground display after the service finishes processing.
        if (isAppInForeground) {
            viewModelScope.launch {
                delay(SENSOR_RESTART_DELAY_MS)
                startForegroundSensors()
            }
        }
    }

    /**
     * Start or stop a raw sensor recording (M1).
     *
     * Routed through the foreground service rather than driven from here: a recording must
     * survive the screen turning off and the app being backgrounded, which is the normal
     * state of a phone on a boat, and a ViewModel guarantees neither.
     */
    fun toggleRecording() {
        val action = if (_uiState.value.recording.isRecording) {
            SignalKStreamingService.ACTION_STOP_RECORDING
        } else {
            SignalKStreamingService.ACTION_START_RECORDING
        }

        if (!bound) bindStreamingService()

        val serviceIntent = Intent(applicationContext, SignalKStreamingService::class.java).apply {
            this.action = action
            putExtra(
                SignalKStreamingService.EXTRA_LOCATION_RATE,
                _uiState.value.locationIntervalMs
            )
        }

        // Starting needs foreground promotion; stopping does not. The SDK_INT >= O half of
        // this condition went with the rest of the checks minSdk=30 already guarantees.
        if (action == SignalKStreamingService.ACTION_START_RECORDING) {
            applicationContext.sendToStreamingService(serviceIntent, foreground = true)
        } else {
            applicationContext.sendToStreamingService(serviceIntent)
        }
    }

    private fun bindStreamingService() {
        val intent = Intent(applicationContext, SignalKStreamingService::class.java)
        if (!applicationContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
            // The service still runs from the start command; only its live state is missing.
            Log.e(TAG, "Could not bind to SignalKStreamingService; live status will not update")
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
