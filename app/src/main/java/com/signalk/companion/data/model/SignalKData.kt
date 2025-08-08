package com.signalk.companion.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

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
    val src: String = "android-companion"
)

@Serializable
data class SignalKValue(
    val path: String,
    val value: JsonElement  // This allows any JSON value: number, string, object
)

// Helper functions to create SignalK values
object SignalKValues {
    fun number(value: Double): JsonElement = JsonPrimitive(value)
    fun string(value: String): JsonElement = JsonPrimitive(value)
    fun position(latitude: Double, longitude: Double): JsonElement = buildJsonObject {
        put("latitude", JsonPrimitive(latitude))
        put("longitude", JsonPrimitive(longitude))
    }
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
    // Navigation orientation data
    val magneticHeading: Float? = null,        // radians, from magnetometer
    val trueHeading: Float? = null,           // radians, magnetic + declination
    val courseOverGround: Float? = null,      // radians, from GPS
    val speedOverGround: Float? = null,       // m/s, from GPS
    
    // Device attitude (roll, pitch, yaw in radians)
    val roll: Float? = null,                  // radians, device roll
    val pitch: Float? = null,                 // radians, device pitch  
    val yaw: Float? = null,                   // radians, device yaw
    val rateOfTurn: Float? = null,            // rad/s, from gyroscope
    
    // Environmental sensors
    val pressure: Float? = null,              // Pa, barometric pressure
    val temperature: Float? = null,           // K, ambient temperature
    val relativeHumidity: Float? = null,      // ratio (0-1), humidity
    val illuminance: Float? = null,           // Lux, ambient light
    
    // Device info
    val batteryLevel: Float? = null,          // ratio (0-1), battery state of charge
    val batteryVoltage: Float? = null,        // V, estimated battery voltage
    
    val timestamp: Long = System.currentTimeMillis()
)
