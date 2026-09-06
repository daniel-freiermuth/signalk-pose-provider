package com.signalk.companion.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.signalk.companion.MainActivity
import com.signalk.companion.R
import com.signalk.companion.data.model.LocationData
import com.signalk.companion.replay.FixRecord
import com.signalk.companion.replay.RecordingSession
import com.signalk.companion.util.BatteryOptimizationHelper
import com.signalk.companion.util.AppSettings
import com.signalk.companion.util.UrlParser
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SignalKStreamingService : Service() {

    enum class StreamingState {
        IDLE,      // Not streaming
        STARTING,  // Initialization in progress
        STREAMING  // Successfully streaming
    }

    @Inject
    lateinit var locationService: LocationService
    
    @Inject
    lateinit var sensorService: SensorService
    
    @Inject
    lateinit var signalKTransmitter: SignalKTransmitter

    /** The M1 attitude pipeline. Runs independently of streaming; publishes nothing yet. */
    @Inject
    lateinit var attitudeEngine: AttitudeEngine

    @Inject
    lateinit var recordingSession: RecordingSession

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val binder = LocalBinder()

    // Coroutine job for forwarding sensor/location data to the transmitter.
    // Null when not streaming so no data is forwarded and no CPU is burned.
    private var transmissionJob: kotlinx.coroutines.Job? = null

    // Forwards GNSS fixes into the recording. Null when not recording.
    private var recordingJob: kotlinx.coroutines.Job? = null

    // Configuration options
    private var sendLocation: Boolean = true
    private var sendHeading: Boolean = true
    private var sendPressure: Boolean = true
    
    private val _streamingState = MutableStateFlow(StreamingState.IDLE)
    val streamingState: StateFlow<StreamingState> = _streamingState.asStateFlow()
    
    // Derived property for consumers expecting Boolean - always consistent with streamingState
    val isStreaming: StateFlow<Boolean> = _streamingState
        .map { it == StreamingState.STREAMING }
        .stateIn(
            scope = serviceScope,
            started = SharingStarted.Eagerly,
            initialValue = false
        )
    
    private val _messagesSent = MutableStateFlow(0)
    val messagesSent: StateFlow<Int> = _messagesSent.asStateFlow()
    
    private val _lastTransmissionTime = MutableStateFlow<Long?>(null)
    val lastTransmissionTime: StateFlow<Long?> = _lastTransmissionTime.asStateFlow()
    
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    companion object {
        private const val TAG = "SignalKStreamingService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "SIGNALK_STREAMING"
        
        const val ACTION_START_STREAMING = "START_STREAMING"
        const val ACTION_STOP_STREAMING = "STOP_STREAMING"
        const val ACTION_UPDATE_CONFIG = "UPDATE_CONFIG"
        const val ACTION_START_RECORDING = "START_RECORDING"
        const val ACTION_STOP_RECORDING = "STOP_RECORDING"
        
        const val EXTRA_PARSED_URL = "PARSED_URL"
        const val EXTRA_LOCATION_RATE = "LOCATION_RATE"
        const val EXTRA_SENSOR_RATE = "SENSOR_RATE"
        const val EXTRA_SEND_LOCATION = "SEND_LOCATION"
        const val EXTRA_SEND_HEADING = "SEND_HEADING"
        const val EXTRA_SEND_PRESSURE = "SEND_PRESSURE"
    }

    inner class LocalBinder : Binder() {
        fun getService(): SignalKStreamingService = this@SignalKStreamingService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SignalK Streaming Service created")
        createNotificationChannel()

        // Only observe connection status for notification updates — lightweight, always needed.
        // Data forwarding coroutines are started in startStreaming() and cancelled in stopStreaming()
        // so they run ONLY while actually streaming.
        serviceScope.launch {
            signalKTransmitter.connectionStatus.collect { isConnected ->
                Log.d(TAG, "SignalK connection status: $isConnected")
                if (isConnected) {
                    updateNotification("Connected to SignalK - Messages sent: ${_messagesSent.value}")
                } else {
                    updateNotification("Connecting to SignalK...")
                }
            }
        }
    }

    /** Starts coroutines that forward sensor/location data to the transmitter. */
    private fun startTransmissionJob() {
        transmissionJob?.cancel()
        transmissionJob = serviceScope.launch {
            launch {
                locationService.locationUpdates.collect { locationData ->
                    locationData?.let {
                        try {
                            signalKTransmitter.sendLocationData(it, sendLocation)
                            updateTransmissionStats()
                            Log.d(TAG, "Sent location data: lat=${it.latitude}, lon=${it.longitude}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to send location data", e)
                        }
                    }
                }
            }
            launch {
                sensorService.sensorData.collect { sensorData ->
                    try {
                        signalKTransmitter.sendSensorData(sensorData, sendHeading, sendPressure)
                        updateTransmissionStats()
                        Log.d(TAG, "Sent sensor data: timestamp=${sensorData.timestamp}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send sensor data", e)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // OS killed and restarted the service (START_STICKY). Resume streaming only if
            // we were actively streaming when killed; otherwise just stop cleanly.
            if (AppSettings.getWasStreaming(this)) {
                Log.w(TAG, "Service restarted by OS after kill — resuming streaming from saved config")
                val serverUrl = AppSettings.getServerUrl(this)
                val parsedUrl = UrlParser.parseUrl(serverUrl)
                if (parsedUrl != null) {
                    startStreaming(
                        parsedUrl = parsedUrl,
                        locationRate = AppSettings.getLocationIntervalMs(this),
                        sensorRate = AppSettings.getSensorIntervalMs(this).toInt(),
                        sendLocation = AppSettings.getSendLocation(this),
                        sendHeading = AppSettings.getSendHeading(this),
                        sendPressure = AppSettings.getSendPressure(this)
                    )
                } else {
                    Log.e(TAG, "Cannot resume: saved server URL '$serverUrl' is invalid — stopping")
                    AppSettings.setWasStreaming(this, false)
                    stopSelf()
                }
            } else {
                Log.d(TAG, "Service restarted by OS but was not streaming — stopping")
                stopSelf()
            }
            return START_STICKY
        }

        when (intent.action) {
            ACTION_START_STREAMING -> {
                @Suppress("DEPRECATION")
                val parsedUrl = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_PARSED_URL, UrlParser.ParsedUrl::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_PARSED_URL)
                }
                val locationRate = intent.getLongExtra(EXTRA_LOCATION_RATE, 1000L)
                val sensorRate = intent.getIntExtra(EXTRA_SENSOR_RATE, 1000)
                val sendLocation = intent.getBooleanExtra(EXTRA_SEND_LOCATION, true)
                val sendHeading = intent.getBooleanExtra(EXTRA_SEND_HEADING, true)
                val sendPressure = intent.getBooleanExtra(EXTRA_SEND_PRESSURE, true)
                
                if (parsedUrl != null) {
                    startStreaming(parsedUrl, locationRate, sensorRate, sendLocation, sendHeading, sendPressure)
                } else {
                    Log.w(TAG, "ACTION_START_STREAMING received but parsedUrl is null - ignoring request")
                }
            }
            ACTION_UPDATE_CONFIG -> {
                val locationRate = intent.getLongExtra(EXTRA_LOCATION_RATE, 1000L)
                val sensorRate = intent.getIntExtra(EXTRA_SENSOR_RATE, 1000)
                val sendLocation = intent.getBooleanExtra(EXTRA_SEND_LOCATION, true)
                val sendHeading = intent.getBooleanExtra(EXTRA_SEND_HEADING, true)
                val sendPressure = intent.getBooleanExtra(EXTRA_SEND_PRESSURE, true)
                
                updateStreamingConfig(locationRate, sensorRate, sendLocation, sendHeading, sendPressure)
            }
            ACTION_STOP_STREAMING -> {
                stopStreaming()
            }
            ACTION_START_RECORDING -> {
                startRecording(intent.getLongExtra(EXTRA_LOCATION_RATE, 1000L))
            }
            ACTION_STOP_RECORDING -> {
                stopRecording()
            }
        }
        
        // START_STICKY: the OS will restart this service after an unexpected kill.
        // If it was actively streaming (tracked via AppSettings.wasStreaming), the null-intent
        // branch above will resume it. If not streaming, it will call stopSelf() immediately.
        return START_STICKY
    }

    private fun startStreaming(parsedUrl: UrlParser.ParsedUrl,
                               locationRate: Long, sensorRate: Int,
                               sendLocation: Boolean = true, sendHeading: Boolean = true, sendPressure: Boolean = true) {
        if (_streamingState.value != StreamingState.IDLE) {
            Log.d(TAG, "Already streaming or starting (state=${_streamingState.value}), ignoring start request")
            return
        }
        
        // Set to STARTING immediately to prevent race condition
        _streamingState.value = StreamingState.STARTING
        
        // Store configuration
        this.sendLocation = sendLocation
        this.sendHeading = sendHeading
        this.sendPressure = sendPressure
        
        Log.d(TAG, "Starting SignalK streaming to ${parsedUrl.toUrlString()} (location=$sendLocation, heading=$sendHeading, pressure=$sendPressure)")
        
        // Check battery optimization status
        val batteryOptimized = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)
        Log.d(TAG, "Battery optimization disabled: $batteryOptimized")
        if (!batteryOptimized) {
            Log.w(TAG, "WARNING: Battery optimization is enabled - app may stop in background!")
        }
        
        serviceScope.launch {
            try {
                _error.value = null // Clear any previous errors
                Log.d(TAG, "Configuring SignalK transmitter")
                
                // Configure SignalK transmitter with parsed URL
                signalKTransmitter.configure(parsedUrl)
                
                // Start SignalK streaming (this is crucial!)
                Log.d(TAG, "Starting SignalK transmitter...")
                signalKTransmitter.startStreaming()
                
                // Wait a moment for connection to establish
                delay(1000)
                
                // Conditionally start location updates only if location data is needed
                if (sendLocation) {
                    Log.d(TAG, "Starting location updates with rate: ${locationRate}ms")
                    try {
                        locationService.startLocationUpdates(this@SignalKStreamingService, locationRate)
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Location permission not granted", e)
                    }
                } else {
                    Log.d(TAG, "Location transmission disabled - skipping GPS activation")
                }
                
                // Conditionally start sensor updates only if heading or pressure data is needed
                if (sendHeading || sendPressure) {
                    Log.d(TAG, "Starting sensor updates with rate: ${sensorRate}ms (heading=$sendHeading, pressure=$sendPressure)")
                    sensorService.startSensorUpdates(sensorRate, needsHeading = sendHeading, needsPressure = sendPressure)
                } else {
                    Log.d(TAG, "All sensor transmission disabled - skipping sensor activation")
                }
                
                // Mark as successfully streaming
                _streamingState.value = StreamingState.STREAMING

                // Start forwarding data to the transmitter — only now that we are streaming
                startTransmissionJob()

                // Persist streaming state so the service can resume after an OS kill
                AppSettings.setWasStreaming(this@SignalKStreamingService, true)

                // Start foreground service with notification
                val notification = createNotification("Streaming to SignalK server")
                startForeground(NOTIFICATION_ID, notification)
                
                Log.d(TAG, "SignalK streaming started successfully")
                
            } catch (e: Exception) {
                val errorMessage = when (e) {
                    is IllegalArgumentException -> "Invalid server URL: ${e.message?.substringAfter(":")?.trim() ?: "unknown error"}"
                    is java.net.UnknownHostException -> "Cannot resolve hostname: ${e.message}"
                    is SecurityException -> "Permission denied: ${e.message}"
                    else -> "Failed to start streaming: ${e.message}"
                }
                Log.e(TAG, errorMessage, e)
                _error.value = errorMessage
                _streamingState.value = StreamingState.IDLE
                stopSelf()
            }
        }
    }

    private fun stopStreaming() {
        Log.d(TAG, "Stopping SignalK streaming")

        // Clear the persistence flag before anything else so an unexpected death
        // during the shutdown sequence doesn't cause a spurious resume.
        AppSettings.setWasStreaming(this, false)

        // Cancel data forwarding immediately — no more transmissions once stopping
        transmissionJob?.cancel()
        transmissionJob = null

        serviceScope.launch {
            // A recording may still be running and still needs GNSS and the service alive.
            // Tearing the service down here would end the sail mid-file — and a recording is
            // exactly the artefact you cannot re-take.
            val recording = recordingSession.isRecording
            if (!recording) locationService.stopLocationUpdates()
            sensorService.stopSensorUpdates()
            signalKTransmitter.stopStreaming()

            _streamingState.value = StreamingState.IDLE

            if (recording) {
                updateNotification("Recording raw sensor data")
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    // ------------------------------------------------------------------ recording (M1)

    /**
     * Start a raw sensor recording, and the attitude engine that consumes the same records.
     *
     * Deliberately independent of streaming. A recording is worth making with no server in
     * sight — the harness exists so filter gains are tuned against recorded sails rather than
     * on the water — so this promotes the service to the foreground on its own, and stopping
     * a recording only tears the service down if nothing else is using it.
     *
     * GNSS fixes are forwarded separately from the sensor stream because they arrive on a
     * different thread and a different clock source; [FixRecord]'s timestamp is the fix's
     * `elapsedRealtimeNanos`, which shares the sensor time base (frame-conventions.md §7). A
     * fix without that timestamp is dropped rather than stamped with an invented one: an
     * unplaceable fix is worse than a missing one for M4.
     */
    fun startRecording(locationRate: Long = 1000L): Boolean {
        if (recordingSession.isRecording) {
            Log.d(TAG, "Recording already in progress, ignoring start request")
            return true
        }

        if (recordingSession.start() == null) {
            Log.e(TAG, "Could not open a recording file - not starting the engine")
            return false
        }

        // AttitudeEngine keeps its own mount-rotation state (Volatile, see AttitudeEngine),
        // separate from SensorService's — without this it would start at identity and every
        // M1 boat-frame reading would be reported in raw device coordinates instead.
        attitudeEngine.setCalibrationAngles(
            AppSettings.getCalibrationAlphaDeg(this),
            AppSettings.getCalibrationBetaDeg(this),
            AppSettings.getCalibrationGammaDeg(this)
        )
        if (!attitudeEngine.start(record = true)) {
            Log.e(TAG, "Attitude engine refused to start - closing the empty recording")
            recordingSession.stop()
            return false
        }

        recordingJob = serviceScope.launch {
            launch {
                // The service may already have GNSS running for streaming; starting it twice
                // would double-register. LocationService re-registers cleanly, but there is
                // no reason to disturb a working stream.
                if (!locationService.isLocationUpdatesActive()) {
                    try {
                        locationService.startLocationUpdates(this@SignalKStreamingService, locationRate)
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Location permission not granted - recording without fixes", e)
                    }
                }
            }
            launch {
                var lastFixNs = 0L
                locationService.locationUpdates.collect { locationData ->
                    val fix = locationData?.toFixRecord() ?: return@collect
                    // locationUpdates is a StateFlow and replays its last value, so the fix
                    // that was current when recording started would otherwise be written
                    // again — and any repeat delivery with it.
                    if (fix.timestampNs == lastFixNs) return@collect
                    lastFixNs = fix.timestampNs
                    attitudeEngine.onFix(fix)
                }
            }
        }

        startForeground(
            NOTIFICATION_ID,
            createNotification(
                "Recording raw sensor data",
                stopAction = ACTION_STOP_RECORDING,
                stopLabel = "Stop recording"
            )
        )
        Log.i(TAG, "Recording started")
        return true
    }

    /**
     * Stop the recording and return the file, or null if none was running.
     *
     * Order matters: the engine is stopped first so the sensor thread is gone before the
     * writer closes. [RecordingSession] is explicitly single-writer, and closing underneath a
     * live 200 Hz callback is the one way to lose the tail of a sail.
     */
    fun stopRecording(): java.io.File? {
        if (!recordingSession.isRecording) return null

        recordingJob?.cancel()
        recordingJob = null

        attitudeEngine.stop()
        val file = recordingSession.stop()
        Log.i(TAG, "Recording stopped: ${file?.absolutePath}")

        if (_streamingState.value == StreamingState.IDLE || !sendLocation) {
            // Leave GNSS running only if streaming is both active and actually wants it -
            // streaming with sendLocation disabled means the recording was GNSS's only
            // reason to be active, and leaving it running now would just drain the battery.
            locationService.stopLocationUpdates()
        }
        if (_streamingState.value == StreamingState.IDLE) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            updateNotification("Messages sent: ${_messagesSent.value}")
        }
        return file
    }

    private fun updateStreamingConfig(locationRate: Long, sensorRate: Int, sendLocation: Boolean, sendHeading: Boolean, sendPressure: Boolean) {
        if (_streamingState.value != StreamingState.STREAMING) {
            Log.d(TAG, "Not currently streaming (state=${_streamingState.value}), ignoring config update")
            return
        }
        
        Log.d(TAG, "Updating streaming configuration (location=$sendLocation, heading=$sendHeading, pressure=$sendPressure)")
        
        serviceScope.launch {
            // Update stored configuration
            this@SignalKStreamingService.sendLocation = sendLocation
            this@SignalKStreamingService.sendHeading = sendHeading
            this@SignalKStreamingService.sendPressure = sendPressure
            
            // Handle location service changes
            val wasLocationActive = locationService.isLocationUpdatesActive()
            val shouldLocationBeActive = sendLocation
            
            if (wasLocationActive && !shouldLocationBeActive) {
                Log.d(TAG, "Stopping location updates (disabled in config)")
                locationService.stopLocationUpdates()
            } else if (!wasLocationActive && shouldLocationBeActive) {
                Log.d(TAG, "Starting location updates (enabled in config)")
                try {
                    locationService.startLocationUpdates(this@SignalKStreamingService, locationRate)
                } catch (e: SecurityException) {
                    Log.e(TAG, "Location permission not granted", e)
                }
            } else if (wasLocationActive && shouldLocationBeActive) {
                Log.d(TAG, "Updating location rate to ${locationRate}ms")
                locationService.updateLocationRate(locationRate)
            }
            
            // Handle sensor service changes
            val wasSensorActive = sensorService.isSensorUpdatesActive()
            val shouldSensorBeActive = sendHeading || sendPressure
            
            if (wasSensorActive && !shouldSensorBeActive) {
                Log.d(TAG, "Stopping sensor updates (all sensors disabled in config)")
                sensorService.stopSensorUpdates()
            } else if (!wasSensorActive && shouldSensorBeActive) {
                Log.d(TAG, "Starting sensor updates (sensors enabled in config)")
                sensorService.startSensorUpdates(sensorRate, needsHeading = sendHeading, needsPressure = sendPressure)
            } else if (wasSensorActive && shouldSensorBeActive) {
                Log.d(TAG, "Updating sensor configuration (heading=$sendHeading, pressure=$sendPressure)")
                // Restart sensors with new configuration
                sensorService.stopSensorUpdates()
                sensorService.startSensorUpdates(sensorRate, needsHeading = sendHeading, needsPressure = sendPressure)
            }
            
            Log.d(TAG, "Streaming configuration updated successfully")
        }
    }

    fun updateCalibrationAngles(alphaDeg: Float, betaDeg: Float, gammaDeg: Float) {
        sensorService.setCalibrationAngles(alphaDeg, betaDeg, gammaDeg)
        attitudeEngine.setCalibrationAngles(alphaDeg, betaDeg, gammaDeg)
        Log.d(TAG, "Updated calibration angles: α=${alphaDeg}°, β=${betaDeg}°, γ=${gammaDeg}°")
    }

    private fun updateTransmissionStats() {
        _messagesSent.value += 1
        _lastTransmissionTime.value = System.currentTimeMillis()
        
        // Update notification with current stats
        updateNotification("Messages sent: ${_messagesSent.value}")
    }
    
    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SignalK Streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when SignalK data streaming is active"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(
        contentText: String,
        stopAction: String = ACTION_STOP_STREAMING,
        stopLabel: String = "Stop"
    ): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, SignalKStreamingService::class.java).apply {
            action = stopAction
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SignalK Pose Provider")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Use Android default icon
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                stopLabel,
                stopPendingIntent
            )
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "SignalK Streaming Service destroyed")
        // Safety-net: stop sensors if the service is destroyed without ViewModel cleanup
        // (e.g., OS-restart path with no Activity). ViewModel will restart them for
        // foreground display if the app is still visible.
        // Close any open recording before the process goes away, so the last buffered records
        // reach disk rather than dying with the service.
        recordingJob?.cancel()
        recordingJob = null
        attitudeEngine.stop()
        recordingSession.stop()
        sensorService.stopSensorUpdates()
        locationService.stopLocationUpdates()
        serviceScope.cancel()
    }
}

/**
 * A fix on the sensor time base, or null when the platform did not supply one.
 *
 * Dropping the fix is deliberate. `elapsedRealtimeNanos` is what lets M4 place a fix on the
 * IMU timeline (frame-conventions.md §7); substituting wall clock would put a plausible but
 * wrong timestamp in a recording that is meant to be ground truth, and wall clock jumps
 * whenever NTP or the receiver corrects it.
 */
private fun LocationData.toFixRecord(): FixRecord? {
    val monotonicNs = elapsedRealtimeNanos ?: return null
    return FixRecord(
        timestampNs = monotonicNs,
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        speedMps = speed,
        courseDeg = bearing,
        horizontalAccuracyM = accuracy,
        speedAccuracyMps = speedAccuracy,
        courseAccuracyDeg = bearingAccuracy
    )
}
