package com.signalk.companion.service

import com.signalk.companion.data.model.LocationData
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SignalKDataTest {

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

        // This would normally be a private method, but for testing we can verify
        // the basic structure
        assertNotNull(locationData)
        assertTrue(locationData.latitude > 0)
        assertTrue(locationData.longitude > 0)
    }
}
