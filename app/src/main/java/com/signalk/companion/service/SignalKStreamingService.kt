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
import com.signalk.companion.ui.main.DeviceOrientation
import com.signalk.companion.util.BatteryOptimizationHelper
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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val binder = LocalBinder()
    
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
        
        // Observe data flows and forward to SignalK
        serviceScope.launch {
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
        
        serviceScope.launch {
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
        
        // Observe SignalK connection status
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_STREAMING -> {
                val parsedUrl = intent.getParcelableExtra(EXTRA_PARSED_URL, UrlParser.ParsedUrl::class.java)
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
        }
        
        return START_STICKY // Restart if killed by system
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
        
        serviceScope.launch {
            locationService.stopLocationUpdates()
            sensorService.stopSensorUpdates() 
            signalKTransmitter.stopStreaming()
            
            _streamingState.value = StreamingState.IDLE
            
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
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

    fun updateDeviceOrientation(orientation: DeviceOrientation) {
        sensorService.setDeviceOrientation(orientation)
        Log.d(TAG, "Updated device orientation to: ${orientation.displayName}")
    }

    fun updateTiltCorrection(enabled: Boolean) {
        sensorService.setTiltCorrection(enabled)
        Log.d(TAG, "Updated tilt correction to: $enabled")
    }

    fun updateHeadingOffset(offsetDegrees: Float) {
        sensorService.setHeadingOffset(offsetDegrees)
        Log.d(TAG, "Updated heading offset to: ${offsetDegrees}°")
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

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, SignalKStreamingService::class.java).apply {
            action = ACTION_STOP_STREAMING
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
                "Stop",
                stopPendingIntent
            )
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "SignalK Streaming Service destroyed")
        serviceScope.cancel()
    }
}
