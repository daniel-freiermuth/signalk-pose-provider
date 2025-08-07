package com.signalk.companion.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class SignalKBackgroundService : Service() {
    
    @Inject
    lateinit var signalKTransmitter: SignalKTransmitter
    
    @Inject 
    lateinit var locationService: LocationService
    
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        
        // Start collecting location data and transmitting to SignalK
        serviceScope.launch {
            locationService.locationUpdates.collect { locationData ->
                locationData?.let { 
                    signalKTransmitter.sendLocationData(it)
                }
            }
        }
        
        return START_STICKY
    }
    
    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SignalK Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "SignalK data streaming service"
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SignalK Companion")
            .setContentText("Streaming sensor data to SignalK server")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
    
    companion object {
        private const val CHANNEL_ID = "signalk_service_channel"
        private const val NOTIFICATION_ID = 1
    }
}
