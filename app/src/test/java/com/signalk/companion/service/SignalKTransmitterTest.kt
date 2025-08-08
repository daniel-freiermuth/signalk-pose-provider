package com.signalk.companion.service

import com.signalk.companion.data.model.LocationData
import com.signalk.companion.data.model.*
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
    fun testSignalKJsonStructure() {
        // Test the new simplified JSON structure
        val source = SignalKSource(
            label = "SignalK Navigation Provider",
            src = "signalk-nav-provider"
        )
        
        val values = listOf(
            SignalKValue(
                path = "navigation.position",
                value = SignalKValues.position(59.3293, 18.0686)
            ),
            SignalKValue(
                path = "navigation.speedOverGround", 
                value = SignalKValues.number(5.0)
            ),
            SignalKValue(
                path = "navigation.gnss.type",
                value = SignalKValues.string("GPS")
            )
        )
        
        val update = SignalKUpdate(
            source = source,
            timestamp = "2025-08-07T12:34:56.789Z",
            values = values
        )
        
        val message = SignalKMessage(
            context = "vessels.self",
            updates = listOf(update)
        )
        
        val json = Json { prettyPrint = true }.encodeToString(message)
        
        // Basic verification
        assertNotNull(json)
        assertTrue("Should contain navigation.position", json.contains("navigation.position"))
        assertFalse("Should not contain 'type' field in source", json.contains("\"type\":"))
        
        // For debugging - this will appear in test failure messages if needed
        if (json.length > 100) {
            val shortJson = json.take(200) + "..."
            assertTrue("JSON should be well-formed: $shortJson", json.startsWith("{"))
        }
    }
}
