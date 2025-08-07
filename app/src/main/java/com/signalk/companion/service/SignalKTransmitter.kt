package com.signalk.companion.service

import com.signalk.companion.data.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SignalKTransmitter @Inject constructor() {
    
    private var serverAddress: String = ""
    private var serverPort: Int = 55555
    private var socket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null
    private var dnsRefreshJob: Job? = null
    
    // DNS refresh interval (5 minutes) - good balance between responsiveness and network load
    private val DNS_REFRESH_INTERVAL_MS = 5 * 60 * 1000L
    
    private val _connectionStatus = MutableStateFlow(false)
    val connectionStatus: StateFlow<Boolean> = _connectionStatus
    
    private val _lastSentMessage = MutableStateFlow<String?>(null)
    val lastSentMessage: StateFlow<String?> = _lastSentMessage
    
    private val _messagesSent = MutableStateFlow(0)
    val messagesSent: StateFlow<Int> = _messagesSent
    
    private val _lastTransmissionTime = MutableStateFlow<Long?>(null)
    val lastTransmissionTime: StateFlow<Long?> = _lastTransmissionTime
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    
    fun configure(address: String) {
        val parts = address.split(":")
        serverAddress = parts[0].trim()
        serverPort = if (parts.size > 1) parts[1].toIntOrNull() ?: 55555 else 55555
        
        // Don't resolve DNS immediately - do it when actually starting streaming
        // This allows hostnames like "signalk.local", "my-boat.local", etc.
        _connectionStatus.value = false
    }
    
    suspend fun startStreaming() {
        try {
            socket = DatagramSocket()
            
            // Initial DNS resolution on IO dispatcher
            withContext(Dispatchers.IO) {
                refreshDnsResolution()
            }
            
            // Start periodic DNS refresh to handle changing IP addresses
            startDnsRefreshTimer()
            
            _connectionStatus.value = true
        } catch (e: Exception) {
            _connectionStatus.value = false
            throw e
        }
    }
    
    private fun refreshDnsResolution() {
        try {
            targetAddress = InetAddress.getByName(serverAddress)
            // DNS resolution successful - connection is good
        } catch (e: Exception) {
            // DNS resolution failed - but don't kill the entire streaming
            // Keep using the old IP if we had one, or fail if this is the first attempt
            if (targetAddress == null) {
                throw e // First time and failed - propagate the error
            }
            // Otherwise, keep using the cached address
        }
    }
    
    private fun startDnsRefreshTimer() {
        // Cancel any existing refresh timer
        dnsRefreshJob?.cancel()
        
        // Start new refresh timer
        dnsRefreshJob = CoroutineScope(Dispatchers.IO).launch {
            while (socket != null) {
                delay(DNS_REFRESH_INTERVAL_MS)
                if (socket != null) { // Check again after delay
                    try {
                        refreshDnsResolution()
                    } catch (e: Exception) {
                        // Log DNS refresh failure, but don't stop streaming
                        // In a real app, you might want to use a proper logger here
                    }
                }
            }
        }
    }
    
    fun stopStreaming() {
        // Cancel DNS refresh timer
        dnsRefreshJob?.cancel()
        dnsRefreshJob = null
        
        // Close socket and reset state
        socket?.close()
        socket = null
        targetAddress = null
        _connectionStatus.value = false
        _lastSentMessage.value = null
        _messagesSent.value = 0
        _lastTransmissionTime.value = null
    }
    
    // Manual DNS refresh - can be called from UI if user reports connectivity issues
    suspend fun refreshDns() {
        if (socket != null) {
            withContext(Dispatchers.IO) {
                refreshDnsResolution()
            }
        }
    }
    
    suspend fun sendLocationData(locationData: LocationData) {
        val signalKMessage = createLocationMessage(locationData)
        sendMessage(signalKMessage)
    }
    
    suspend fun sendSensorData(sensorData: SensorData) {
        val signalKMessage = createSensorMessage(sensorData)
        sendMessage(signalKMessage)
    }
    
    private fun createLocationMessage(locationData: LocationData): SignalKMessage {
        val timestamp = dateFormat.format(Date(locationData.timestamp))
        val source = SignalKSource(
            label = "Android Companion",
            type = "NMEA2000",
            src = "android-companion"
        )
        
        val values = mutableListOf<SignalKValue>()
        
        // Position
        values.add(
            SignalKValue(
                path = "navigation.position",
                value = SignalKValueData.Position(
                    latitude = locationData.latitude,
                    longitude = locationData.longitude
                )
            )
        )
        
        // Speed over ground
        if (locationData.speed > 0) {
            values.add(
                SignalKValue(
                    path = "navigation.speedOverGround",
                    value = SignalKValueData.NumberValue(locationData.speed.toDouble())
                )
            )
        }
        
        // Course over ground
        if (locationData.bearing > 0) {
            values.add(
                SignalKValue(
                    path = "navigation.courseOverGroundTrue",
                    value = SignalKValueData.NumberValue(Math.toRadians(locationData.bearing.toDouble()))
                )
            )
        }
        
        // Altitude
        if (locationData.altitude != 0.0) {
            values.add(
                SignalKValue(
                    path = "navigation.gnss.altitude",
                    value = SignalKValueData.NumberValue(locationData.altitude)
                )
            )
        }
        
        val update = SignalKUpdate(
            source = source,
            timestamp = timestamp,
            values = values
        )
        
        return SignalKMessage(
            context = "vessels.self",
            updates = listOf(update)
        )
    }
    
    private fun createSensorMessage(sensorData: SensorData): SignalKMessage {
        val timestamp = dateFormat.format(Date(sensorData.timestamp))
        val source = SignalKSource(
            label = "Android Companion Sensors",
            type = "NMEA2000",
            src = "android-companion-sensors"
        )
        
        val values = mutableListOf<SignalKValue>()
        
        // Atmospheric pressure
        sensorData.pressure?.let { pressure ->
            values.add(
                SignalKValue(
                    path = "environment.outside.pressure",
                    value = SignalKValueData.NumberValue((pressure * 100).toDouble()) // Convert hPa to Pa
                )
            )
        }
        
        // Magnetic heading
        sensorData.magneticHeading?.let { heading ->
            values.add(
                SignalKValue(
                    path = "navigation.headingMagnetic",
                    value = SignalKValueData.NumberValue(Math.toRadians(heading.toDouble()))
                )
            )
        }
        
        // True heading
        sensorData.trueHeading?.let { heading ->
            values.add(
                SignalKValue(
                    path = "navigation.headingTrue",
                    value = SignalKValueData.NumberValue(Math.toRadians(heading.toDouble()))
                )
            )
        }
        
        if (values.isEmpty()) return SignalKMessage(context = "vessels.self", updates = emptyList())
        
        val update = SignalKUpdate(
            source = source,
            timestamp = timestamp,
            values = values
        )
        
        return SignalKMessage(
            context = "vessels.self",
            updates = listOf(update)
        )
    }
    
    private suspend fun sendMessage(message: SignalKMessage) {
        try {
            val json = Json.encodeToString(message)
            val data = json.toByteArray()
            
            socket?.let { socket ->
                targetAddress?.let { address ->
                    val packet = DatagramPacket(data, data.size, address, serverPort)
                    socket.send(packet)
                    
                    // Update tracking state
                    _lastSentMessage.value = json
                    _messagesSent.value = _messagesSent.value + 1
                    _lastTransmissionTime.value = System.currentTimeMillis()
                }
            }
        } catch (e: Exception) {
            _connectionStatus.value = false
            // Log error or handle as needed
        }
    }
}
