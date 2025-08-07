package com.signalk.companion.data.model

import kotlinx.serialization.Serializable

@Serializable
data class SignalKMessage(
    val context: String = "vessels.self",
    val updates: List<SignalKUpdate>
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
    data class QualityValue(val value: Double, val quality: String) : SignalKValueData()
}

// Data classes for sensor readings
data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val bearing: Float,
    val speed: Float,
    val altitude: Double,
    val timestamp: Long
)

data class SensorData(
    val magneticHeading: Float? = null,
    val trueHeading: Float? = null,
    val courseOverGround: Float? = null,
    val speedOverGround: Float? = null,
    val pressure: Float? = null,
    val timestamp: Long = System.currentTimeMillis()
)
