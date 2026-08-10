package com.signalk.companion.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class SignalKMessage(
    val context: String,
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

    /**
     * Number, or null when the value is NaN or infinite.
     *
     * JSON has no NaN or Infinity literal, so a non-finite value either throws at encode
     * time or produces a document no consumer can parse — in both cases taking the whole
     * update down, not just the one bad path. Dropping the path is the honest failure
     * (P5): a missing value reads as "unknown", which is what it is.
     */
    fun finiteNumber(value: Double): JsonElement? =
        if (value.isFinite()) JsonPrimitive(value) else null
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
    val bearing: Float?,              // Course over ground in degrees (null if not available)
    val speed: Float?,                // Speed over ground in m/s (null if not available)
    val altitude: Double?,            // Altitude in meters (null if not available)
    val timestamp: Long,              // UTC wall clock (ms) — for message timestamps only
    // Monotonic time base, shared with SensorEvent.timestamp. This is what M4 will use to
    // place fixes on the IMU timeline and to measure true fix age; wall clock cannot be
    // used for that because it jumps when NTP or the receiver corrects it.
    // See frame-conventions.md §7.
    val elapsedRealtimeNanos: Long? = null,
    // Additional quality measures
    val verticalAccuracy: Float? = null,  // Vertical accuracy in meters (API 26+)
    val speedAccuracy: Float? = null,     // Speed accuracy in m/s (API 26+)  
    val bearingAccuracy: Float? = null,   // Bearing accuracy in degrees (API 26+)
    val satellites: Int? = null,          // Number of satellites used
    val provider: String? = null          // GPS, Network, Fused, etc.
)

@Serializable
data class SensorData(
    // Navigation orientation data.
    //
    // `compassHeading` is named for what SignalK calls it: `navigation.headingCompass`,
    // "magnetic heading received from the compass, NOT adjusted for magneticDeviation".
    // That is exactly our situation until M2 calibrates the boat's magnetics out, so this
    // must NOT be published as `navigation.headingMagnetic`, which the spec defines as
    // "headingCompass adjusted for magneticDeviation". See frame-conventions.md §11.
    val compassHeading: Float? = null,        // radians, [0, 2π), deviation-uncorrected
    // compassHeading + magneticVariation, radians. Deliberately NOT called trueHeading:
    // SignalK derives headingTrue from headingMagnetic, i.e. after deviation is corrected
    // out, and we skip that step entirely until M2. This value is therefore wrong by the
    // boat's deviation — small on a GRP hull, tens of degrees near the engine or a speaker.
    // Traditional navigation has no name for it because you would never apply variation
    // before deviation; that it needs an invented name is the point. Display only, never
    // published (frame-conventions.md §11.2).
    val approxTrueHeading: Float? = null,
    val magneticVariation: Float? = null,     // radians, positive east (WMM model at our position)
    val magnetometerAccuracy: Int? = null,    // SensorManager.SENSOR_STATUS_* (0=unreliable … 3=high)

    // Vehicle attitude, radians. Signs per frame-conventions.md §5:
    //   roll  — positive = starboard down. This is instantaneous inclination: steady heel
    //           with wave-driven roll oscillation superimposed (see §5).
    //   pitch — positive = bow up
    // There is deliberately no `yaw` field. The previous one carried a gyro *rate* published
    // as an *angle* (audit A2); beyond that, SignalK does not define a datum for
    // `attitude.yaw`, so no value we could put there has an unambiguous meaning (§11).
    val roll: Float? = null,
    val pitch: Float? = null,
    val rateOfTurn: Float? = null,            // rad/s, vehicle frame, positive to starboard
    
    // Environmental sensors
    val pressure: Float? = null,              // Pa, barometric pressure
    val temperature: Float? = null,           // K, ambient temperature
    val relativeHumidity: Float? = null,      // ratio (0-1), humidity
    
    val timestamp: Long = System.currentTimeMillis()
)
