package com.signalk.companion.data.model

import kotlinx.serialization.Serializable

@Serializable
data class SignalKMessage(
    val context: String,
    val updates: List<SignalKUpdate>,
    val token: String? = null  // JWT token for authentication
)

@Serializable
data class SignalKUpdate(
    val source: SignalKSource,
    val timestamp: String,
    val values: List<SignalKValue>
)

@Serializable
data class SignalKSource(
    val label: String,
    val type: String = "NMEA2000",
    val src: String = "android-companion"
)

@Serializable
data class SignalKValue(
    val path: String,
    val value: SignalKValueData
)

@Serializable
sealed class SignalKValueData {
    @Serializable
    data class Position(val latitude: Double, val longitude: Double) : SignalKValueData()
    
    @Serializable
    data class NumberValue(val value: Double) : SignalKValueData()
    
    @Serializable
    data class StringValue(val value: String) : SignalKValueData()
    
    @Serializable
    data class QualityValue(val value: Double, val quality: String) : SignalKValueData()
}

// Data classes for sensor readings
@Serializable
data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,              // Horizontal accuracy in meters
    val bearing: Float,
    val speed: Float,
    val altitude: Double,
    val timestamp: Long,
    // Additional quality measures
    val verticalAccuracy: Float? = null,  // Vertical accuracy in meters (API 26+)
    val speedAccuracy: Float? = null,     // Speed accuracy in m/s (API 26+)  
    val bearingAccuracy: Float? = null,   // Bearing accuracy in degrees (API 26+)
    val satellites: Int? = null,          // Number of satellites used
    val provider: String? = null          // GPS, Network, Fused, etc.
)

@Serializable
data class SensorData(
    val magneticHeading: Float? = null,
    val trueHeading: Float? = null,
    val courseOverGround: Float? = null,
    val speedOverGround: Float? = null,
    val pressure: Float? = null,
    val timestamp: Long = System.currentTimeMillis()
)
