package com.signalk.companion.service

import android.content.Context
import android.location.Location
import android.os.Looper
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
    
    private val _locationUpdates = MutableStateFlow<LocationData?>(null)
    val locationUpdates: StateFlow<LocationData?> = _locationUpdates
    
    @Throws(SecurityException::class)
    suspend fun startLocationUpdates(context: Context) {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000L // 1 second intervals
        ).apply {
            setMinUpdateIntervalMillis(500L) // Minimum 0.5 seconds
            setMaxUpdateDelayMillis(2000L) // Maximum 2 seconds delay
        }.build()
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    val locationData = LocationData(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracy = location.accuracy,
                        bearing = if (location.hasBearing()) location.bearing else 0f,
                        speed = if (location.hasSpeed()) location.speed else 0f,
                        altitude = if (location.hasAltitude()) location.altitude else 0.0,
                        timestamp = location.time
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
    }
    
    fun stopLocationUpdates() {
        locationCallback?.let { callback ->
            fusedLocationClient?.removeLocationUpdates(callback)
        }
        locationCallback = null
        fusedLocationClient = null
        _locationUpdates.value = null
    }
}
