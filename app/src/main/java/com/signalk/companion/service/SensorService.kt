package com.signalk.companion.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.signalk.companion.data.model.SensorData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

@Singleton
class SensorService @Inject constructor(
    private val context: Context
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    private val _sensorData = MutableStateFlow(SensorData())
    val sensorData: StateFlow<SensorData> = _sensorData.asStateFlow()

    // Available sensors
    private val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val pressure = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val temperature = sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
    private val humidity = sensorManager.getDefaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY)
    private val light = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

    // Sensor data storage
    private var magneticField = FloatArray(3)
    private var gravity = FloatArray(3)
    private var gyroscope_data = FloatArray(3)
    private var rotationMatrix = FloatArray(9)
    private var orientation = FloatArray(3)

    // Filtering for smooth data
    private val alpha = 0.8f  // Low-pass filter constant
    
    // Current sensor delay setting and rate limiting
    private var currentSensorDelay = SensorManager.SENSOR_DELAY_UI
    private var updateIntervalMs = 1000 // Default 1 second
    private var lastUpdateTime = 0L
    
    // Accumulated sensor readings for rate limiting
    private var pendingSensorUpdate = false

    companion object {
        private const val TAG = "SensorService"
    }

    fun startSensorUpdates(updateIntervalMs: Int = 1000) {
        this.updateIntervalMs = updateIntervalMs
        currentSensorDelay = getSensorDelayFromInterval(updateIntervalMs)
        lastUpdateTime = 0L // Reset to force immediate first update
        Log.d(TAG, "Starting sensor updates with interval ${updateIntervalMs}ms (delay: $currentSensorDelay)")
        
        // Register available sensors
        magnetometer?.let { 
            sensorManager.registerListener(this, it, currentSensorDelay)
            Log.d(TAG, "Magnetometer registered")
        }
        accelerometer?.let { 
            sensorManager.registerListener(this, it, currentSensorDelay)
            Log.d(TAG, "Accelerometer registered")
        }
        gyroscope?.let { 
            sensorManager.registerListener(this, it, currentSensorDelay)
            Log.d(TAG, "Gyroscope registered")
        }
        pressure?.let { 
            sensorManager.registerListener(this, it, currentSensorDelay)
            Log.d(TAG, "Pressure sensor registered")
        }
        temperature?.let { 
            sensorManager.registerListener(this, it, currentSensorDelay)
            Log.d(TAG, "Temperature sensor registered")
        }
        humidity?.let { 
            sensorManager.registerListener(this, it, currentSensorDelay)
            Log.d(TAG, "Humidity sensor registered")
        }
        light?.let { 
            sensorManager.registerListener(this, it, currentSensorDelay)
            Log.d(TAG, "Light sensor registered")
        }

        logAvailableSensors()
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

    fun stopSensorUpdates() {
        Log.d(TAG, "Stopping sensor updates")
        sensorManager.unregisterListener(this)
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
            
            Sensor.TYPE_LIGHT -> {
                val illuminanceLux = event.values[0]
                updateSensorData { copy(illuminance = illuminanceLux) }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d(TAG, "Sensor accuracy changed: ${sensor?.name} -> $accuracy")
    }

    private fun updateOrientation() {
        if (SensorManager.getRotationMatrix(rotationMatrix, null, gravity, magneticField)) {
            SensorManager.getOrientation(rotationMatrix, orientation)
            
            // orientation[0] = azimuth (rotation around z-axis) - magnetic heading
            // orientation[1] = pitch (rotation around x-axis)  
            // orientation[2] = roll (rotation around y-axis)
            
            val magneticHeading = orientation[0]  // Already in radians
            val pitch = orientation[1]           // Already in radians
            val roll = orientation[2]            // Already in radians
            
            updateSensorData { 
                copy(
                    magneticHeading = magneticHeading,
                    pitch = pitch,
                    roll = roll
                ) 
            }
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
        // Rate limiting: only emit sensor data at configured intervals
        val currentTime = System.currentTimeMillis()
        if (lastUpdateTime != 0L && currentTime - lastUpdateTime < updateIntervalMs) {
            pendingSensorUpdate = true
            return // Skip this update to maintain configured rate
        }
        
        val currentData = _sensorData.value
        val newData = currentData.update().copy(timestamp = currentTime)
        
        // Add battery information
        val batteryLevel = getBatteryLevel()
        val batteryVoltage = getBatteryVoltage()
        
        _sensorData.value = newData.copy(
            batteryLevel = batteryLevel,
            batteryVoltage = batteryVoltage
        )
        
        // Update the last update time to track rate limiting
        val actualInterval = if (lastUpdateTime > 0) currentTime - lastUpdateTime else 0
        lastUpdateTime = currentTime
        pendingSensorUpdate = false
        
        Log.d(TAG, "Sensor data updated. Actual interval: ${actualInterval}ms (configured: ${updateIntervalMs}ms)")
    }

    private fun getBatteryLevel(): Float? {
        return try {
            val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (level >= 0) level / 100f else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get battery level", e)
            null
        }
    }

    private fun getBatteryVoltage(): Float? {
        return try {
            val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, intentFilter)
            val voltage = batteryStatus?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
            if (voltage > 0) voltage / 1000f else null  // Convert mV to V
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get battery voltage", e)
            null
        }
    }

    private fun logAvailableSensors() {
        val availableSensors = mutableListOf<String>()
        if (magnetometer != null) availableSensors.add("Magnetometer")
        if (accelerometer != null) availableSensors.add("Accelerometer") 
        if (gyroscope != null) availableSensors.add("Gyroscope")
        if (pressure != null) availableSensors.add("Pressure")
        if (temperature != null) availableSensors.add("Temperature")
        if (humidity != null) availableSensors.add("Humidity")
        if (light != null) availableSensors.add("Light")
        
        Log.i(TAG, "Available sensors: ${availableSensors.joinToString(", ")}")
    }

    fun getAvailableSensors(): Map<String, Boolean> {
        return mapOf(
            "magnetometer" to (magnetometer != null),
            "accelerometer" to (accelerometer != null),
            "gyroscope" to (gyroscope != null),
            "pressure" to (pressure != null),
            "temperature" to (temperature != null),
            "humidity" to (humidity != null),
            "light" to (light != null)
        )
    }
    
    private fun getSensorDelayFromInterval(updateIntervalMs: Int): Int {
        return when {
            updateIntervalMs <= 100 -> SensorManager.SENSOR_DELAY_FASTEST  // ~100Hz
            updateIntervalMs <= 500 -> SensorManager.SENSOR_DELAY_GAME     // ~50Hz  
            updateIntervalMs <= 1000 -> SensorManager.SENSOR_DELAY_UI      // ~60Hz (but limited by interval)
            else -> SensorManager.SENSOR_DELAY_NORMAL                      // ~5Hz
        }
    }
}
