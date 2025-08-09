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
import com.signalk.companion.ui.main.UpdateRate
import com.signalk.companion.ui.main.DeviceOrientation
import com.signalk.companion.util.BatteryOptimizationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SignalKStreamingService : Service() {

    @Inject
    lateinit var locationService: LocationService
    
    @Inject
    lateinit var sensorService: SensorService
    
    @Inject
    lateinit var signalKTransmitter: SignalKTransmitter

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val binder = LocalBinder()
    
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()
    
    private val _messagesSent = MutableStateFlow(0)
    val messagesSent: StateFlow<Int> = _messagesSent.asStateFlow()
    
    private val _lastTransmissionTime = MutableStateFlow<Long?>(null)
    val lastTransmissionTime: StateFlow<Long?> = _lastTransmissionTime.asStateFlow()

    companion object {
        private const val TAG = "SignalKStreamingService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "SIGNALK_STREAMING"
        
        const val ACTION_START_STREAMING = "START_STREAMING"
        const val ACTION_STOP_STREAMING = "STOP_STREAMING"
        
        const val EXTRA_SERVER_URL = "SERVER_URL"
        const val EXTRA_LOCATION_RATE = "LOCATION_RATE"
        const val EXTRA_SENSOR_RATE = "SENSOR_RATE"
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
                        signalKTransmitter.sendLocationData(it)
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
                    signalKTransmitter.sendSensorData(sensorData)
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
                val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: "https://signalk.entrop.mywire.org"
                val locationRate = intent.getLongExtra(EXTRA_LOCATION_RATE, 1000L)
                val sensorRate = intent.getIntExtra(EXTRA_SENSOR_RATE, 1000)
                
                startStreaming(serverUrl, locationRate, sensorRate)
            }
            ACTION_STOP_STREAMING -> {
                stopStreaming()
            }
        }
        
        return START_STICKY // Restart if killed by system
    }

    private fun startStreaming(serverUrl: String, locationRate: Long, sensorRate: Int) {
        if (_isStreaming.value) {
            Log.d(TAG, "Already streaming, ignoring start request")
            return
        }
        
        Log.d(TAG, "Starting SignalK streaming to $serverUrl")
        
        // Check battery optimization status
        val batteryOptimized = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)
        Log.d(TAG, "Battery optimization disabled: $batteryOptimized")
        if (!batteryOptimized) {
            Log.w(TAG, "WARNING: Battery optimization is enabled - app may stop in background!")
        }
        
        serviceScope.launch {
            try {
                Log.d(TAG, "Configuring SignalK transmitter for: $serverUrl")
                
                // Configure SignalK transmitter
                signalKTransmitter.configureWebSocketFromHttpUrl(serverUrl)
                
                // Start SignalK streaming (this is crucial!)
                Log.d(TAG, "Starting SignalK transmitter...")
                signalKTransmitter.startStreaming()
                
                // Wait a moment for connection to establish
                delay(1000)
                
                Log.d(TAG, "Starting location updates with rate: ${locationRate}ms")
                // Start location updates
                try {
                    locationService.startLocationUpdates(this@SignalKStreamingService, locationRate)
                } catch (e: SecurityException) {
                    Log.e(TAG, "Location permission not granted", e)
                }
                
                Log.d(TAG, "Starting sensor updates with rate: ${sensorRate}ms")
                // Start sensor updates
                sensorService.startSensorUpdates(sensorRate)
                
                _isStreaming.value = true
                
                // Start foreground service with notification
                val notification = createNotification("Streaming to SignalK server")
                startForeground(NOTIFICATION_ID, notification)
                
                Log.d(TAG, "SignalK streaming started successfully")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start streaming", e)
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
            
            _isStreaming.value = false
            
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    fun updateLocationRate(rate: UpdateRate) {
        if (_isStreaming.value) {
            serviceScope.launch {
                try {
                    locationService.updateLocationRate(this@SignalKStreamingService, rate.intervalMs)
                } catch (e: SecurityException) {
                    Log.e(TAG, "Location permission not granted", e)
                }
            }
        }
    }

    fun updateSensorRate(rate: UpdateRate) {
        if (_isStreaming.value) {
            sensorService.updateSensorRate(rate.intervalMs.toInt())
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
            .setContentTitle("SignalK Navigation Provider")
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
