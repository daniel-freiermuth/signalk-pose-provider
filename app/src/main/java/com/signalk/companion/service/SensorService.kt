package com.signalk.companion.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.GeomagneticField
import android.util.Log
import com.signalk.companion.data.model.SensorData
import com.signalk.companion.util.DeviceCalibration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

@Singleton
class SensorService @Inject constructor(
    private val context: Context,
    private val locationService: LocationService
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val _sensorData = MutableStateFlow(SensorData())
    val sensorData: StateFlow<SensorData> = _sensorData.asStateFlow()

    // Available sensors
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val pressure = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val temperature = sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
    private val humidity = sensorManager.getDefaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY)

    // Sensor data storage
    private var magneticField = FloatArray(3)
    private var gravity = FloatArray(3)
    private var gyroscope_data = FloatArray(3)
    private var rotationMatrix = FloatArray(9)

    // Filtering for smooth data
    private val alpha = 0.8f  // Low-pass filter constant
    
    // Marine navigation configuration: device-to-vehicle calibration matrix (R_D_V)
    // R_W_V = R_W_D * calibrationMatrix gives vehicle attitude in world frame.
    // @Volatile because this is genuinely cross-thread: SignalKStreamingService writes it
    // from a Dispatchers.Default coroutine while sensor callbacks read it on the main
    // looper. The volatile write/read pair also publishes the array's contents safely,
    // since the matrix is fully built before assignment.
    @Volatile private var calibrationMatrix = DeviceCalibration.IDENTITY_3X3.copyOf()
    @Volatile private var hasRotationMatrix = false
    
    // Current sensor delay setting and rate limiting
    private var currentSensorDelay = SensorManager.SENSOR_DELAY_UI
    private var updateIntervalMs = 1000 // Default 1 second
    private var lastUpdateTime = 0L
    
    // Cached sensor data to prevent data loss during rate limiting
    // Initialize magnetometerAccuracy to UNRELIABLE so the UI shows something
    // immediately; onAccuracyChanged will update it once Android reports the real value.
    private var pendingData = SensorData(magnetometerAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE)
    
    // Accumulated sensor readings for rate limiting
    private var pendingSensorUpdate = false
    
    // Track active state
    private var isActive = false

    // Magnetic variation cache — see currentMagneticVariation()
    private var cachedVariationRad: Float? = null
    private var cachedVariationLat = 0.0
    private var cachedVariationLon = 0.0
    private var cachedVariationAtMs = 0L

    companion object {
        private const val TAG = "SensorService"

        /** Recompute variation after this long, even if the boat has barely moved. */
        private const val VARIATION_CACHE_MAX_AGE_MS = 10 * 60 * 1000L

        /**
         * Recompute variation once position moves this far, in degrees of lat/lon.
         * 0.01° is about 1.1 km; declination gradients are well under 1° per 100 km, so
         * the cached value stays accurate to far better than the compass itself.
         */
        private const val VARIATION_CACHE_MAX_MOVE_DEG = 0.01
    }

    fun startSensorUpdates(updateIntervalMs: Int = 1000) {
        startSensorUpdates(updateIntervalMs, needsHeading = true, needsPressure = true)
    }
    
    fun startSensorUpdates(updateIntervalMs: Int = 1000, needsHeading: Boolean = true, needsPressure: Boolean = true) {
        this.updateIntervalMs = updateIntervalMs
        currentSensorDelay = getSensorDelayFromInterval(updateIntervalMs)
        lastUpdateTime = 0L // Reset to force immediate first update
        hasRotationMatrix = false // Force waiting for fresh sensor data
        Log.d(TAG, "Starting sensor updates with interval ${updateIntervalMs}ms (delay: $currentSensorDelay, heading=$needsHeading, pressure=$needsPressure)")
        
        // Register sensors based on what's needed
        if (needsHeading) {
            magnetometer?.let { 
                sensorManager.registerListener(this, it, currentSensorDelay)
                Log.d(TAG, "Magnetometer registered (needed for heading)")
            }
            accelerometer?.let { 
                sensorManager.registerListener(this, it, currentSensorDelay)
                Log.d(TAG, "Accelerometer registered (needed for heading)")
            }
            gyroscope?.let { 
                sensorManager.registerListener(this, it, currentSensorDelay)
                Log.d(TAG, "Gyroscope registered (needed for heading)")
            }
        } else {
            Log.d(TAG, "Heading disabled - skipping magnetometer, accelerometer, gyroscope")
        }
        
        if (needsPressure) {
            pressure?.let { 
                sensorManager.registerListener(this, it, currentSensorDelay)
                Log.d(TAG, "Pressure sensor registered")
            }
        } else {
            Log.d(TAG, "Pressure disabled - skipping pressure sensor")
        }
        
        // Always register temperature and humidity as they're not configurable yet
        temperature?.let { 
            sensorManager.registerListener(this, it, currentSensorDelay)
            Log.d(TAG, "Temperature sensor registered")
        }
        humidity?.let { 
            sensorManager.registerListener(this, it, currentSensorDelay)
            Log.d(TAG, "Humidity sensor registered")
        }

        logAvailableSensors()
        isActive = true
    }

    fun updateSensorRate(updateIntervalMs: Int) {
        Log.d(TAG, "Updating sensor rate to ${updateIntervalMs}ms")
        this.updateIntervalMs = updateIntervalMs
        lastUpdateTime = 0L // Reset to force immediate first update
        // Stop current sensors
        sensorManager.unregisterListener(this)
        // Restart with new rate
        startSensorUpdates(updateIntervalMs)
    }

    fun setCalibrationAngles(alphaDeg: Float, betaDeg: Float, gammaDeg: Float) {
        Log.d(TAG, "Setting calibration angles: α=${alphaDeg}°, β=${betaDeg}°, γ=${gammaDeg}°")
        calibrationMatrix = DeviceCalibration.composeZXZ(alphaDeg, betaDeg, gammaDeg)
    }

    fun getCurrentRotationMatrix(): FloatArray = rotationMatrix.copyOf()

    fun hasValidRotationMatrix(): Boolean = hasRotationMatrix

    fun stopSensorUpdates() {
        Log.d(TAG, "Stopping sensor updates")
        sensorManager.unregisterListener(this)
        isActive = false
    }
    
    fun isSensorUpdatesActive(): Boolean {
        return isActive
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        when (event.sensor.type) {
            Sensor.TYPE_MAGNETIC_FIELD -> {
                // Apply low-pass filter to reduce noise
                magneticField[0] = alpha * magneticField[0] + (1 - alpha) * event.values[0]
                magneticField[1] = alpha * magneticField[1] + (1 - alpha) * event.values[1]
                magneticField[2] = alpha * magneticField[2] + (1 - alpha) * event.values[2]
                updateOrientation()
            }
            
            Sensor.TYPE_ACCELEROMETER -> {
                // Apply low-pass filter for gravity
                gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
                gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
                gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]
                updateOrientation()
            }
            
            Sensor.TYPE_GYROSCOPE -> {
                gyroscope_data[0] = event.values[0]  // rad/s around x-axis
                gyroscope_data[1] = event.values[1]  // rad/s around y-axis
                gyroscope_data[2] = event.values[2]  // rad/s around z-axis
                updateGyroscopeData()
            }
            
            Sensor.TYPE_PRESSURE -> {
                val pressureHpa = event.values[0]
                val pressurePa = pressureHpa * 100  // Convert hPa to Pa
                updateSensorData { copy(pressure = pressurePa) }
            }
            
            Sensor.TYPE_AMBIENT_TEMPERATURE -> {
                val temperatureCelsius = event.values[0]
                val temperatureKelvin = temperatureCelsius + 273.15f  // Convert °C to K
                updateSensorData { copy(temperature = temperatureKelvin) }
            }
            
            Sensor.TYPE_RELATIVE_HUMIDITY -> {
                val humidityPercent = event.values[0]
                val humidityRatio = humidityPercent / 100f  // Convert % to ratio
                updateSensorData { copy(relativeHumidity = humidityRatio) }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d(TAG, "Sensor accuracy changed: ${sensor?.name} -> $accuracy")
        if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            updateSensorData { copy(magnetometerAccuracy = accuracy) }
        }
    }

    private fun updateOrientation() {
        if (SensorManager.getRotationMatrix(rotationMatrix, null, gravity, magneticField)) {
            hasRotationMatrix = true
            
            // Apply calibration: R_W_V = R_W_D * R_D_V
            val vehicleMatrix = DeviceCalibration.multiply3x3(rotationMatrix, calibrationMatrix)

            // Nautical angles from the vehicle attitude matrix. The extraction is a pure
            // function so the reference poses of frame-conventions.md §10 can test it
            // directly; see FrameConventionsTest.
            val angles = DeviceCalibration.extractNauticalAngles(vehicleMatrix)
            val compassHeading = angles.headingRad
            val pitch = angles.pitchRad
            val roll = angles.rollRad

            // Magnetic variation (declination) at our position, positive east. SignalK
            // defines it as the quantity *added* to a magnetic heading to get true, which
            // matches GeomagneticField's sign — see frame-conventions.md §4.4.
            val variation = currentMagneticVariation()

            // Compass heading referred to true north. NOT a true heading: the deviation
            // step is missing entirely until M2, so this is wrong by the boat's deviation
            // (frame-conventions.md §11.2). Display only, never published.
            // Null rather than falling back to the compass heading — labelling a magnetic
            // heading "true" is exactly the dishonesty P5 exists to prevent.
            val approxTrueHeading = variation?.let { DeviceCalibration.wrapTo2Pi(compassHeading + it) }

            // No log here on purpose: updateOrientation() runs on every accelerometer AND
            // magnetometer event, so a formatted log line would build several strings a
            // hundred times a second — the same hot-path waste as the uncached variation
            // lookup. The orientation values are logged instead at the rate-limited
            // emission point in updateSensorData().
            updateSensorData {
                copy(
                    compassHeading = compassHeading,
                    approxTrueHeading = approxTrueHeading,
                    magneticVariation = variation,
                    pitch = pitch,
                    roll = roll
                )
            }
        }
    }

    /**
     * Magnetic variation at the current position, in radians, positive east.
     *
     * Returns null when there is no position fix — variation is a function of position, and
     * without one we simply do not know it.
     *
     * Cached. [updateOrientation] runs on every accelerometer *and* magnetometer event, so
     * an uncached `GeomagneticField` would evaluate the WMM spherical-harmonic model up to a
     * few hundred times a second for a value that changes by well under a degree over tens
     * of kilometres. That is real battery and thermal load on an always-on mounted phone
     * (plan §6). Recomputed when the position moves materially or the entry ages out.
     *
     * Cache state is touched only from the sensor callback thread, so it needs no
     * synchronisation of its own.
     */
    private fun currentMagneticVariation(): Float? {
        val locationData = locationService.locationUpdates.value ?: run {
            Log.w(TAG, "No position fix - magnetic variation unknown, true heading unavailable")
            return null
        }

        val now = System.currentTimeMillis()
        val cached = cachedVariationRad
        if (cached != null &&
            now - cachedVariationAtMs < VARIATION_CACHE_MAX_AGE_MS &&
            abs(locationData.latitude - cachedVariationLat) < VARIATION_CACHE_MAX_MOVE_DEG &&
            abs(locationData.longitude - cachedVariationLon) < VARIATION_CACHE_MAX_MOVE_DEG
        ) {
            return cached
        }

        return try {
            // Altitude has negligible effect on declination; 0 is fine when unavailable.
            val geomagneticField = GeomagneticField(
                locationData.latitude.toFloat(),
                locationData.longitude.toFloat(),
                (locationData.altitude ?: 0.0).toFloat(),
                now
            )

            val declinationDegrees = geomagneticField.declination
            Log.d(TAG, "Magnetic variation: ${declinationDegrees}° at ${locationData.latitude}, ${locationData.longitude}")

            val variationRad = Math.toRadians(declinationDegrees.toDouble()).toFloat()
            cachedVariationRad = variationRad
            cachedVariationLat = locationData.latitude
            cachedVariationLon = locationData.longitude
            cachedVariationAtMs = now
            variationRad
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating magnetic variation: ${e.message}")
            null
        }
    }

    /**
     * Publish rate of turn in the vehicle frame, with the nautical sign convention.
     *
     * Two corrections over the previous version (frame-conventions.md audit A1):
     *
     * 1. **Frame.** The gyroscope reports rates about the *device* axes. Taking the raw
     *    device Z rate is correct only for a phone mounted perfectly flat; otherwise it
     *    is the boat's turn rate projected onto the wrong axis. Rotate into the vehicle
     *    frame first: ω_V = R_D_V^T · ω_D  (frame-conventions.md §3.3).
     *
     * 2. **Sign.** Android's gyroscope is positive by the right-hand rule, so a positive
     *    rate about the vehicle's up axis is counterclockwise seen from above — a turn to
     *    *port*. SignalK's `navigation.rateOfTurn` is positive to *starboard*, so the sign
     *    is inverted exactly once, here (frame-conventions.md §4.2).
     *
     * Previously both were wrong, which published an inverted rate of turn.
     */
    private fun updateGyroscopeData() {
        val rateOfTurn = DeviceCalibration.rateOfTurnFromGyro(calibrationMatrix, gyroscope_data)
        updateSensorData {
            copy(rateOfTurn = rateOfTurn)
        }
    }

    private fun updateSensorData(update: SensorData.() -> SensorData) {
        val currentTime = System.currentTimeMillis()
        
        // Always cache the latest sensor values to prevent data loss
        pendingData = pendingData.update()
        
        // Rate limiting: only emit to StateFlow at configured intervals
        if (lastUpdateTime != 0L && currentTime - lastUpdateTime < updateIntervalMs) {
            pendingSensorUpdate = true
            return // Skip emission but data is cached
        }
        
        // Emit cached data with current timestamp
        _sensorData.value = pendingData.copy(timestamp = currentTime)
        
        // Update the last update time to track rate limiting
        val actualInterval = if (lastUpdateTime > 0) currentTime - lastUpdateTime else 0
        lastUpdateTime = currentTime
        pendingSensorUpdate = false
        
        // Orientation values ride along here rather than being logged per sensor event,
        // so diagnostics cost one formatted line per emission instead of one per callback.
        val d = pendingData
        Log.d(TAG, "Sensor data emitted. Actual interval: ${actualInterval}ms " +
                  "(configured: ${updateIntervalMs}ms)" +
                  d.compassHeading.degOrNull("compass")+
                  d.approxTrueHeading.degOrNull("approxTrue") +
                  d.pitch.degOrNull("pitch") +
                  d.roll.degOrNull("roll"))
    }

    /** Formats a radian value as `, name=12.3°`, or "" when absent. Log-only helper. */
    private fun Float?.degOrNull(name: String): String =
        this?.let { ", $name=${"%.1f".format(Math.toDegrees(it.toDouble()))}°" } ?: ""

    private fun logAvailableSensors() {
        val availableSensors = mutableListOf<String>()
        if (magnetometer != null) availableSensors.add("Magnetometer")
        if (accelerometer != null) availableSensors.add("Accelerometer") 
        if (gyroscope != null) availableSensors.add("Gyroscope")
        if (pressure != null) availableSensors.add("Pressure")
        if (temperature != null) availableSensors.add("Temperature")
        if (humidity != null) availableSensors.add("Humidity")
        
        Log.i(TAG, "Available sensors: ${availableSensors.joinToString(", ")}")
    }

    fun getAvailableSensors(): Map<String, Boolean> {
        return mapOf(
            "magnetometer" to (magnetometer != null),
            "accelerometer" to (accelerometer != null),
            "gyroscope" to (gyroscope != null),
            "pressure" to (pressure != null),
            "temperature" to (temperature != null),
            "humidity" to (humidity != null)
        )
    }
    
    private fun getSensorDelayFromInterval(updateIntervalMs: Int): Int {
        // Match sensor sampling rate to emission rate for battery efficiency
        return when {
            updateIntervalMs <= 100 -> SensorManager.SENSOR_DELAY_GAME     // ~50Hz for very fast updates
            updateIntervalMs <= 500 -> SensorManager.SENSOR_DELAY_NORMAL   // ~5Hz provides good smoothing
            else -> SensorManager.SENSOR_DELAY_NORMAL                      // ~5Hz for 1+ second intervals
        }
    }
}
