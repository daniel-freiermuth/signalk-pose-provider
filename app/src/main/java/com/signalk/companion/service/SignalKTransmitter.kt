package com.signalk.companion.service

import com.signalk.companion.data.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    
    private val _connectionStatus = MutableStateFlow(false)
    val connectionStatus: StateFlow<Boolean> = _connectionStatus
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    
    fun configure(address: String) {
        val parts = address.split(":")
        serverAddress = parts[0]
        serverPort = if (parts.size > 1) parts[1].toIntOrNull() ?: 55555 else 55555
        
        try {
            targetAddress = InetAddress.getByName(serverAddress)
            _connectionStatus.value = true
        } catch (e: Exception) {
            _connectionStatus.value = false
            throw e
        }
    }
    
    fun startStreaming() {
        try {
            socket = DatagramSocket()
            _connectionStatus.value = true
        } catch (e: Exception) {
            _connectionStatus.value = false
            throw e
        }
    }
    
    fun stopStreaming() {
        socket?.close()
        socket = null
        _connectionStatus.value = false
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
                }
            }
        } catch (e: Exception) {
            _connectionStatus.value = false
            // Log error or handle as needed
        }
    }
}
