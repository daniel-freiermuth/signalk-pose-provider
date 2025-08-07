package com.signalk.companion.service

import com.signalk.companion.data.model.LocationData
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.Assert.*

class SignalKTransmitterTest {
    
    @Test
    fun testLocationDataSerialization() {
        val signalKTransmitter = SignalKTransmitter()
        
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
        
        // Verify basic instantiation
        assertNotNull(signalKTransmitter)
        assertNotNull(locationData)
        
        // Test serialization
        val json = Json.encodeToString(locationData)
        assertNotNull(json)
        assertTrue(json.contains("latitude"))
        assertTrue(json.contains("longitude"))
    }
    
    @Test 
    fun testServiceConfiguration() {
        val signalKTransmitter = SignalKTransmitter()
        
        // Test server configuration
        signalKTransmitter.configure("192.168.1.100:3000")
        
        // Basic assertion - service should be created
        assertNotNull(signalKTransmitter)
    }
    
    @Test
    fun testConnectionStatus() {
        val signalKTransmitter = SignalKTransmitter()
        
        // Initial state should be disconnected
        assertFalse(signalKTransmitter.connectionStatus.value)
    }
}
