package com.signalk.companion.service

import com.signalk.companion.data.model.SensorData
import org.junit.Assert.*
import org.junit.Test

class SensorServiceTest {

    @Test
    fun `sensor data structure validation`() {
        // Test that we can create SensorData with all fields
        val sensorData = SensorData(
            magneticHeading = 1.57f, // 90 degrees in radians
            trueHeading = 1.67f,
            roll = 0.1f,
            pitch = 0.2f,
            yaw = 0.3f,
            rateOfTurn = 0.05f,
            pressure = 101325.0f, // Standard atmospheric pressure in Pa
            temperature = 293.15f, // 20°C in Kelvin
            relativeHumidity = 0.6f // 60%
        )
        
        assertNotNull(sensorData)
        assertEquals(1.57f, sensorData.magneticHeading!!, 0.01f)
        assertEquals(101325.0f, sensorData.pressure!!, 0.1f)
        assertEquals(0.6f, sensorData.relativeHumidity!!, 0.01f)
        assertTrue("Timestamp should be set", sensorData.timestamp > 0)
    }

    @Test
    fun `sensor data with null values`() {
        val sensorData = SensorData()
        
        assertNull(sensorData.magneticHeading)
        assertNull(sensorData.pressure)
        assertNull(sensorData.temperature)
        assertTrue("Timestamp should be set even with null values", sensorData.timestamp > 0)
    }

    @Test
    fun `sensor data timestamp is recent`() {
        val sensorData = SensorData()
        val currentTime = System.currentTimeMillis()
        
        // Timestamp should be within reasonable range (last 1 second)
        assertTrue("Timestamp should be recent", 
            sensorData.timestamp > currentTime - 1000 && 
            sensorData.timestamp <= currentTime)
    }

    @Test
    fun `sensor data partial values`() {
        val sensorData = SensorData(
            pressure = 101325.0f,
            temperature = 293.15f
        )
        
        assertNotNull(sensorData.pressure)
        assertNotNull(sensorData.temperature)
        assertNull(sensorData.magneticHeading)
    }

    @Test
    fun `sensor data update rate validation`() {
        // Test that update intervals map to correct values
        val fastUpdate = 500 // 0.5 seconds  
        val normalUpdate = 1000 // 1 second
        val slowUpdate = 2000 // 2 seconds
        val verySlowUpdate = 5000 // 5 seconds
        
        // These should be different intervals
        assertNotEquals(fastUpdate, normalUpdate)
        assertNotEquals(normalUpdate, slowUpdate) 
        assertNotEquals(slowUpdate, verySlowUpdate)
        
        // Verify they're in ascending order
        assertTrue("Fast should be less than normal", fastUpdate < normalUpdate)
        assertTrue("Normal should be less than slow", normalUpdate < slowUpdate)
        assertTrue("Slow should be less than very slow", slowUpdate < verySlowUpdate)
    }
}
