package com.signalk.companion.service

import com.signalk.companion.data.model.LocationData
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Assert.*

class SignalKTransmitterTest {
    
    @Test
    fun testLocationDataSerialization() {
        // Mock location data
        val locationData = LocationData(
            latitude = 59.3293,
            longitude = 18.0686,
            accuracy = 5.0f,
            bearing = 180.0f,
            speed = 5.0f,
            altitude = 10.0,
            timestamp = System.currentTimeMillis()
        )
        
        // Verify basic data structure
        assertNotNull(locationData)
        
        // Test serialization
        val json = Json.encodeToString(locationData)
        assertNotNull(json)
        assertTrue(json.contains("latitude"))
        assertTrue(json.contains("longitude"))
    }
    
    @Test 
    fun testLocationDataValues() {
        val locationData = LocationData(
            latitude = 59.3293,
            longitude = 18.0686,
            accuracy = 5.0f,
            bearing = 180.0f,
            speed = 5.0f,
            altitude = 10.0,
            timestamp = System.currentTimeMillis()
        )
        
        // Test basic properties
        assertEquals(59.3293, locationData.latitude, 0.0001)
        assertEquals(18.0686, locationData.longitude, 0.0001)
        assertEquals(5.0f, locationData.accuracy)
        assertEquals(180.0f, locationData.bearing)
        assertEquals(5.0f, locationData.speed)
        assertEquals(10.0, locationData.altitude, 0.0001)
    }
    
    @Test
    fun testValidCoordinates() {
        val locationData = LocationData(
            latitude = 59.3293,
            longitude = 18.0686,
            accuracy = 5.0f,
            bearing = 180.0f,
            speed = 5.0f,
            altitude = 10.0,
            timestamp = System.currentTimeMillis()
        )
        
        // Test that coordinates are in valid ranges
        assertTrue("Latitude should be valid", locationData.latitude >= -90 && locationData.latitude <= 90)
        assertTrue("Longitude should be valid", locationData.longitude >= -180 && locationData.longitude <= 180)
        assertTrue("Accuracy should be positive", locationData.accuracy > 0)
    }
}
