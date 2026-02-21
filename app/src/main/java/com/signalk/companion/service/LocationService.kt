package com.signalk.companion.service

import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.*
import com.signalk.companion.data.model.LocationData
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocationService @Inject constructor() {
    
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var lastLocationTime = 0L
    
    private val _locationUpdates = MutableStateFlow<LocationData?>(null)
    val locationUpdates: StateFlow<LocationData?> = _locationUpdates
    
    companion object {
        private const val TAG = "LocationService"
    }
    
    @Throws(SecurityException::class)
    suspend fun startLocationUpdates(context: Context, updateIntervalMs: Long = 500L) {  // Faster default
        Log.d(TAG, "Starting location updates with interval: ${updateIntervalMs}ms")
        lastLocationTime = 0L // Reset for accurate interval logging
        
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        
        // Always use highest accuracy for marine navigation
        val priority = Priority.PRIORITY_HIGH_ACCURACY
        
        val locationRequest = LocationRequest.Builder(
            priority,
            updateIntervalMs
        ).apply {
            setMinUpdateIntervalMillis(100L) // Much faster minimum (100ms)
            setMaxUpdateDelayMillis(updateIntervalMs) // Tighter delay control  
            setWaitForAccurateLocation(false) // Don't wait for high accuracy
            setMaxUpdateAgeMillis(200L) // Very fresh data only
        }.build()
        
        Log.d(TAG, "LocationRequest configured with:")
        Log.d(TAG, "  - Priority: $priority")
        Log.d(TAG, "  - Update interval: ${updateIntervalMs}ms")
        Log.d(TAG, "  - Min update interval: ${updateIntervalMs / 2}ms")
        Log.d(TAG, "  - Max update delay: ${updateIntervalMs * 2}ms")
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val currentTime = System.currentTimeMillis()
                val actualInterval = if (lastLocationTime > 0) currentTime - lastLocationTime else 0
                lastLocationTime = currentTime
                
                Log.d(TAG, "Location update received. Actual interval: ${actualInterval}ms (configured: ${updateIntervalMs}ms)")
                
                locationResult.lastLocation?.let { location ->
                    val locationData = LocationData(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracy = location.accuracy,
                        bearing = if (location.hasBearing()) location.bearing else null,
                        speed = if (location.hasSpeed()) location.speed else null,
                        altitude = if (location.hasAltitude()) location.altitude else null,
                        timestamp = location.time,
                        // Enhanced quality measures (API 26+)
                        verticalAccuracy = if (android.os.Build.VERSION.SDK_INT >= 26 && location.hasVerticalAccuracy()) {
                            location.verticalAccuracyMeters
                        } else null,
                        speedAccuracy = if (android.os.Build.VERSION.SDK_INT >= 26 && location.hasSpeedAccuracy()) {
                            location.speedAccuracyMetersPerSecond
                        } else null,
                        bearingAccuracy = if (android.os.Build.VERSION.SDK_INT >= 26 && location.hasBearingAccuracy()) {
                            location.bearingAccuracyDegrees
                        } else null,
                        provider = location.provider
                    )
                    _locationUpdates.value = locationData
                }
            }
        }
        
        fusedLocationClient?.requestLocationUpdates(
            locationRequest,
            locationCallback!!,
            Looper.getMainLooper()
        )
        
        Log.d(TAG, "Location updates started successfully")
    }
    
    fun stopLocationUpdates() {
        Log.d(TAG, "Stopping location updates")
        locationCallback?.let { callback ->
            fusedLocationClient?.removeLocationUpdates(callback)
        }
        locationCallback = null
        fusedLocationClient = null
        _locationUpdates.value = null
    }
    
    fun isLocationUpdatesActive(): Boolean {
        return fusedLocationClient != null && locationCallback != null
    }
    
    @Throws(SecurityException::class)
    suspend fun updateLocationRate(context: Context, updateIntervalMs: Long) {
        Log.d(TAG, "Updating location rate to ${updateIntervalMs}ms")
        if (fusedLocationClient != null && locationCallback != null) {
            // Stop current updates
            stopLocationUpdates()
            // Restart with new rate
            startLocationUpdates(context, updateIntervalMs)
        }
    }
    
    @Throws(SecurityException::class)
    fun updateLocationRate(updateIntervalMs: Long) {
        // Simplified version that can be called from service
        Log.d(TAG, "Updating location rate to ${updateIntervalMs}ms (simplified)")
        // For now, just log - in practice, we'd need to track the context
        // The service will handle stopping and restarting with new rate
    }
}
