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
    private var calibrationMatrix = DeviceCalibration.IDENTITY_3X3.copyOf()
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

    companion object {
        private const val TAG = "SensorService"
    }

    fun startSensorUpdates(updateIntervalMs: Int = 1000) {
        startSensorUpdates(updateIntervalMs, needsHeading = true, needsPressure = true)
    }
    
    fun startSensorUpdates(updateIntervalMs: Int = 1000, needsHeading: Boolean = true, needsPressure: Boolean = true) {
        this.updateIntervalMs = updateIntervalMs
        currentSensorDelay = getSensorDelayFromInterval(updateIntervalMs)
        lastUpdateTime = 0L // Reset to force immediate first update
        hasRotationMatrix = false // Force waiting for fresh sensor data
        pendingData = SensorData(magnetometerAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE)
        pendingSensorUpdate = false
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
            
            // Extract orientation from vehicle attitude matrix.
            //
            // vehicleMatrix columns (row-major, ENU world frame):
            //   col 0 = vehicle X = starboard direction in world → [R[0], R[3], R[6]]
            //   col 1 = vehicle Y = bow direction in world      → [R[1], R[4], R[7]]
            //   col 2 = vehicle Z = up direction in world       → [R[2], R[5], R[8]]
            //
            // We extract angles directly from the matrix instead of using
            // SensorManager.getOrientation(), which assumes device portrait-frame
            // semantics and gives wrong results when the vehicle axes don't align
            // with the phone's natural portrait orientation.

            // Heading: bearing of bow projected onto the horizontal plane.
            // atan2(East_component_of_bow, North_component_of_bow)
            var magneticHeading = atan2(vehicleMatrix[1].toDouble(), vehicleMatrix[4].toDouble()).toFloat()

            // Pitch: elevation of bow above horizontal (bow-up positive, nautical convention).
            // Up_component_of_bow = vehicleMatrix[7] (row 2, col 1); asin gives elevation angle.
            val pitch = asin(vehicleMatrix[7].coerceIn(-1f, 1f).toDouble()).toFloat()

            // Roll: starboard-down positive (nautical convention).
            // atan2(−Up_component_of_stbd, Up_component_of_up) where Up_of_stbd = vehicleMatrix[6].
            val roll = atan2(-vehicleMatrix[6].toDouble(), vehicleMatrix[8].toDouble()).toFloat()
            
            // Normalize heading to 0-2π range
            magneticHeading = normalizeHeading(magneticHeading)
            
            // Calculate true heading by adding magnetic declination
            val trueHeading = calculateTrueHeading(magneticHeading)
            
            Log.d(TAG, "Magnetic heading: ${Math.toDegrees(magneticHeading.toDouble()).toFloat()}°, " +
                      "True heading: ${Math.toDegrees(trueHeading.toDouble()).toFloat()}°, " +
                      "pitch: ${Math.toDegrees(pitch.toDouble()).toFloat()}°, " +
                      "roll: ${Math.toDegrees(roll.toDouble()).toFloat()}°")
            
            updateSensorData { 
                copy(
                    magneticHeading = magneticHeading,
                    trueHeading = trueHeading,
                    pitch = pitch,
                    roll = roll
                ) 
            }
        }
    }

    private fun normalizeHeading(heading: Float): Float {
        var normalized = heading
        while (normalized < 0) normalized += (2 * PI).toFloat()
        while (normalized >= (2 * PI).toFloat()) normalized -= (2 * PI).toFloat()
        return normalized
    }

    private fun calculateTrueHeading(magneticHeading: Float): Float {
        // Get current location from LocationService
        val locationData = locationService.locationUpdates.value
        
        if (locationData == null) {
            Log.w(TAG, "No location data available for magnetic declination calculation, using magnetic heading as true heading")
            return magneticHeading
        }
        
        try {
            // Calculate magnetic declination using Android's GeomagneticField
            // Use 0.0 for altitude if not available (has minimal impact on declination)
            val geomagneticField = GeomagneticField(
                locationData.latitude.toFloat(),
                locationData.longitude.toFloat(),
                (locationData.altitude ?: 0.0).toFloat(),
                System.currentTimeMillis()
            )
            
            // Get declination in degrees and convert to radians
            val declinationDegrees = geomagneticField.declination
            val declinationRadians = Math.toRadians(declinationDegrees.toDouble()).toFloat()
            
            Log.d(TAG, "Magnetic declination: ${declinationDegrees}° at ${locationData.latitude}, ${locationData.longitude}")
            
            // True heading = Magnetic heading + Declination
            val trueHeading = normalizeHeading(magneticHeading + declinationRadians)
            
            return trueHeading
            
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating magnetic declination: ${e.message}")
            return magneticHeading
        }
    }

    private fun updateGyroscopeData() {
        // Rate of turn is typically the z-axis rotation (yaw rate)
        val rateOfTurn = gyroscope_data[2]  // rad/s
        val yaw = gyroscope_data[2]  // Could be integrated over time for absolute yaw
        
        updateSensorData { 
            copy(
                rateOfTurn = rateOfTurn,
                yaw = yaw
            ) 
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
        
        Log.d(TAG, "Sensor data emitted. Actual interval: ${actualInterval}ms (configured: ${updateIntervalMs}ms)")
    }

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
