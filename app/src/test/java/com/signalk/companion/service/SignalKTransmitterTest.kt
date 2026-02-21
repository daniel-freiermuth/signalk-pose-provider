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
        assertEquals(180.0f, locationData.bearing!!)
        assertEquals(5.0f, locationData.speed!!)
        assertEquals(10.0, locationData.altitude!!, 0.0001)
    }
    
    @Test
    fun testSignalKJsonStructure() {
        // Test the new simplified JSON structure
        val source = SignalKSource(
            label = "SignalK Pose Provider",
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
    
    @Test
    fun testLocationDataWithZeroBearing() {
        // Zero bearing (True North) is a valid measurement and should be preserved
        val locationData = LocationData(
            latitude = 59.3293,
            longitude = 18.0686,
            accuracy = 5.0f,
            bearing = 0.0f,  // True North - valid measurement
            speed = 5.0f,
            altitude = 10.0,
            timestamp = System.currentTimeMillis()
        )
        
        assertNotNull(locationData.bearing)
        assertEquals(0.0f, locationData.bearing!!)
        // A SignalK message created from this should include COG=0.0
    }
    
    @Test
    fun testLocationDataWithZeroSpeed() {
        // Zero speed (stationary) is a valid measurement and should be preserved
        val locationData = LocationData(
            latitude = 59.3293,
            longitude = 18.0686,
            accuracy = 5.0f,
            bearing = 180.0f,
            speed = 0.0f,  // Stationary - valid measurement
            altitude = 10.0,
            timestamp = System.currentTimeMillis()
        )
        
        assertNotNull(locationData.speed)
        assertEquals(0.0f, locationData.speed!!)
        // A SignalK message created from this should include SOG=0.0
    }
    
    @Test
    fun testLocationDataWithZeroAltitude() {
        // Zero altitude (sea level) is a valid measurement and should be preserved
        val locationData = LocationData(
            latitude = 59.3293,
            longitude = 18.0686,
            accuracy = 5.0f,
            bearing = 180.0f,
            speed = 5.0f,
            altitude = 0.0,  // Sea level - valid measurement
            timestamp = System.currentTimeMillis()
        )
        
        assertNotNull(locationData.altitude)
        assertEquals(0.0, locationData.altitude!!, 0.0001)
        // A SignalK message created from this should include altitude=0.0
    }
    
    @Test
    fun testLocationDataWithNullValues() {
        // Null values should represent "not measured" and be omitted from SignalK message
        val locationData = LocationData(
            latitude = 59.3293,
            longitude = 18.0686,
            accuracy = 5.0f,
            bearing = null,  // Not measured
            speed = null,    // Not measured
            altitude = null, // Not measured
            timestamp = System.currentTimeMillis()
        )
        
        assertNull(locationData.bearing)
        assertNull(locationData.speed)
        assertNull(locationData.altitude)
        // A SignalK message created from this should NOT include COG, SOG, or altitude paths
    }
    
    @Test
    fun testLocationDataAllZeroValues() {
        // Edge case: vessel at anchor, heading North, at sea level - all zeros are valid
        val locationData = LocationData(
            latitude = 59.3293,
            longitude = 18.0686,
            accuracy = 5.0f,
            bearing = 0.0f,   // True North
            speed = 0.0f,     // Stationary
            altitude = 0.0,   // Sea level
            timestamp = System.currentTimeMillis()
        )
        
        assertNotNull(locationData.bearing)
        assertNotNull(locationData.speed)
        assertNotNull(locationData.altitude)
        assertEquals(0.0f, locationData.bearing!!)
        assertEquals(0.0f, locationData.speed!!)
        assertEquals(0.0, locationData.altitude!!, 0.0001)
        // A SignalK message should include all these zero values
    }
}
