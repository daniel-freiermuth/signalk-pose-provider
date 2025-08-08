package com.signalk.companion.service

import com.signalk.companion.data.model.*
import com.signalk.companion.ui.main.TransmissionProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.*
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SignalKTransmitter @Inject constructor(
    private val authenticationService: AuthenticationService
) {
    
    private var serverAddress: String = ""
    private var serverPort: Int = 55555
    private var baseUrl: String = ""  // Store the full base URL for HTTP(S) streaming
    private var transmissionProtocol: TransmissionProtocol = TransmissionProtocol.UDP
    private var socket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null
    private var dnsRefreshJob: Job? = null
    
    // WebSocket support
    private var okHttpClient: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    
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
    
    fun configure(hostname: String, port: Int, protocol: TransmissionProtocol = TransmissionProtocol.UDP) {
        serverAddress = hostname.trim()
        serverPort = port
        transmissionProtocol = protocol
        
        // For WebSocket/HTTP(S), construct the base URL using the protocol from the enum
        baseUrl = when (protocol) {
            TransmissionProtocol.UDP -> ""  // Not used for UDP
            TransmissionProtocol.WEBSOCKET -> "ws://$hostname:$port"
            TransmissionProtocol.WEBSOCKET_SSL -> "wss://$hostname:$port"
        }
        
        // Don't resolve DNS immediately - do it when actually starting streaming
        // This allows hostnames like "signalk.local", "my-boat.local", etc.
        _connectionStatus.value = false
    }
    
    // Configure from a full URL and auto-detect WebSocket protocol based on HTTP/HTTPS
    fun configureFromUrl(fullUrl: String, protocol: TransmissionProtocol = TransmissionProtocol.UDP) {
        val parsedUrl = parseUrl(fullUrl)
        
        // Auto-detect WebSocket protocol based on HTTPS/HTTP
        val detectedProtocol = when {
            protocol == TransmissionProtocol.UDP -> TransmissionProtocol.UDP
            parsedUrl.isHttps -> TransmissionProtocol.WEBSOCKET_SSL
            else -> TransmissionProtocol.WEBSOCKET
        }
        
        val port = when (detectedProtocol) {
            TransmissionProtocol.UDP -> 55555  // Fixed UDP port
            TransmissionProtocol.WEBSOCKET, TransmissionProtocol.WEBSOCKET_SSL -> parsedUrl.port
        }
        
        serverAddress = parsedUrl.hostname
        serverPort = port
        transmissionProtocol = detectedProtocol
        
        // Build WebSocket URL based on detected protocol
        baseUrl = when (detectedProtocol) {
            TransmissionProtocol.UDP -> ""
            TransmissionProtocol.WEBSOCKET -> "ws://${parsedUrl.hostname}:${port}"
            TransmissionProtocol.WEBSOCKET_SSL -> "wss://${parsedUrl.hostname}:${port}"
        }
        
        _connectionStatus.value = false
    }
    
    // Convenient method to configure WebSocket from HTTP URL with auto-detection
    fun configureWebSocketFromHttpUrl(fullUrl: String) {
        configureFromUrl(fullUrl, TransmissionProtocol.WEBSOCKET) // Will auto-detect WSS if HTTPS
    }
    
    private data class ParsedUrl(val hostname: String, val port: Int, val isHttps: Boolean)
    
    private fun parseUrl(url: String): ParsedUrl {
        return try {
            val cleanUrl = url.lowercase().let { 
                if (!it.startsWith("http://") && !it.startsWith("https://") && 
                    !it.startsWith("ws://") && !it.startsWith("wss://")) {
                    "http://$it"
                } else it
            }
            
            val isHttps = cleanUrl.startsWith("https://") || cleanUrl.startsWith("wss://")
            val withoutProtocol = cleanUrl
                .removePrefix("https://")
                .removePrefix("http://")
                .removePrefix("wss://")
                .removePrefix("ws://")
            val parts = withoutProtocol.split(":")
            
            val hostname = parts[0].trim()
            val port = if (parts.size > 1) {
                parts[1].split("/")[0].toIntOrNull() ?: (if (isHttps) 443 else 80)
            } else {
                if (isHttps) 443 else 80
            }
            
            ParsedUrl(hostname, port, isHttps)
        } catch (e: Exception) {
            ParsedUrl("192.168.1.100", 3000, false)
        }
    }
    
    // Keep backwards compatibility method
    fun configure(address: String, protocol: TransmissionProtocol = TransmissionProtocol.UDP) {
        val parts = address.split(":")
        val hostname = parts[0].trim()
        val port = if (parts.size > 1) parts[1].toIntOrNull() ?: getDefaultPort(protocol) else getDefaultPort(protocol)
        configure(hostname, port, protocol)
    }
    
    private fun getDefaultPort(protocol: TransmissionProtocol): Int {
        return when (protocol) {
            TransmissionProtocol.UDP -> 55555  // SignalK UDP port
            TransmissionProtocol.WEBSOCKET -> 3000  // SignalK WebSocket port
            TransmissionProtocol.WEBSOCKET_SSL -> 3443 // SignalK WebSocket SSL port (or 3000 if using SSL termination)
        }
    }
    
    suspend fun startStreaming() {
        try {
            when (transmissionProtocol) {
                TransmissionProtocol.UDP -> {
                    // Initialize UDP socket
                    socket = DatagramSocket()
                    
                    // Initial DNS resolution on IO dispatcher
                    withContext(Dispatchers.IO) {
                        refreshDnsResolution()
                    }
                    
                    // Start periodic DNS refresh to handle changing IP addresses
                    startDnsRefreshTimer()
                }
                TransmissionProtocol.WEBSOCKET, TransmissionProtocol.WEBSOCKET_SSL -> {
                    // Initialize WebSocket connection
                    initializeWebSocket()
                    
                    // Start periodic DNS refresh for hostname resolution
                    startDnsRefreshTimer()
                }
            }
            
            _connectionStatus.value = true
        } catch (e: Exception) {
            _connectionStatus.value = false
            throw e
        }
    }
    
    private fun refreshDnsResolution() {
        try {
            when (transmissionProtocol) {
                TransmissionProtocol.UDP -> {
                    targetAddress = InetAddress.getByName(serverAddress)
                    // DNS resolution successful - connection is good
                }
                TransmissionProtocol.WEBSOCKET, TransmissionProtocol.WEBSOCKET_SSL -> {
                    // For WebSocket, just validate that we can resolve the hostname
                    // Actual connection will be made per WebSocket connection
                    InetAddress.getByName(serverAddress)
                    // DNS resolution successful
                }
            }
        } catch (e: Exception) {
            // DNS resolution failed - but don't kill the entire streaming
            // Keep using the old IP if we had one, or fail if this is the first attempt
            if (targetAddress == null && transmissionProtocol == TransmissionProtocol.UDP) {
                throw e // First time and failed - propagate the error
            }
            // Otherwise, keep using the cached address or continue for WebSocket
        }
    }
    
    private fun startDnsRefreshTimer() {
        // Cancel any existing refresh timer
        dnsRefreshJob?.cancel()
        
        // Start new refresh timer
        dnsRefreshJob = CoroutineScope(Dispatchers.IO).launch {
            while (socket != null || webSocket != null) {
                delay(DNS_REFRESH_INTERVAL_MS)
                if (socket != null || webSocket != null) { // Check again after delay
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
        
        // Close WebSocket connection
        webSocket?.close(1000, "Streaming stopped")
        webSocket = null
        okHttpClient = null
        
        _connectionStatus.value = false
        _lastSentMessage.value = null
        _messagesSent.value = 0
        _lastTransmissionTime.value = null
    }
    
    // Manual DNS refresh - can be called from UI if user reports connectivity issues
    suspend fun refreshDns() {
        if (socket != null || webSocket != null) {
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
            src = "android-companion"
        )
        
        val values = mutableListOf<SignalKValue>()
        
        // Position with quality indicators
        values.add(
            SignalKValue(
                path = "navigation.position",
                value = SignalKValues.position(
                    latitude = locationData.latitude,
                    longitude = locationData.longitude
                )
            )
        )
        
        // Add position accuracy as separate quality indicator
        if (locationData.accuracy > 0) {
            values.add(
                SignalKValue(
                    path = "navigation.position.accuracy",
                    value = SignalKValues.number(locationData.accuracy.toDouble())
                )
            )
        }
        
        // Speed over ground with accuracy
        if (locationData.speed > 0) {
            values.add(
                SignalKValue(
                    path = "navigation.speedOverGround",
                    value = SignalKValues.number(locationData.speed.toDouble())
                )
            )
            
            // Add speed accuracy if available
            locationData.speedAccuracy?.let { speedAcc ->
                values.add(
                    SignalKValue(
                        path = "navigation.speedOverGround.accuracy",
                        value = SignalKValues.number(speedAcc.toDouble())
                    )
                )
            }
        }
        
        // Course over ground with accuracy
        if (locationData.bearing > 0) {
            values.add(
                SignalKValue(
                    path = "navigation.courseOverGroundTrue",
                    value = SignalKValues.number(Math.toRadians(locationData.bearing.toDouble()))
                )
            )
            
            // Add bearing accuracy if available
            locationData.bearingAccuracy?.let { bearingAcc ->
                values.add(
                    SignalKValue(
                        path = "navigation.courseOverGroundTrue.accuracy",
                        value = SignalKValues.number(Math.toRadians(bearingAcc.toDouble()))
                    )
                )
            }
        }
        
        // Altitude with accuracy
        if (locationData.altitude != 0.0) {
            values.add(
                SignalKValue(
                    path = "navigation.gnss.altitude",
                    value = SignalKValues.number(locationData.altitude)
                )
            )
            
            // Add vertical accuracy if available
            locationData.verticalAccuracy?.let { vertAcc ->
                values.add(
                    SignalKValue(
                        path = "navigation.gnss.altitude.accuracy",
                        value = SignalKValues.number(vertAcc.toDouble())
                    )
                )
            }
        }
        
        // GPS quality indicators
        locationData.provider?.let { provider ->
            values.add(
                SignalKValue(
                    path = "navigation.gnss.type",
                    value = SignalKValues.string(provider)
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
            updates = listOf(update),
            token = if (transmissionProtocol == TransmissionProtocol.UDP) {
                authenticationService.getAuthToken()
            } else null  // For WebSocket, token goes in connection headers
        )
    }
    
    private fun createSensorMessage(sensorData: SensorData): SignalKMessage {
        val timestamp = dateFormat.format(Date(sensorData.timestamp))
        val source = SignalKSource(
            label = "Android Companion Sensors",
            src = "android-companion-sensors"
        )
        
        val values = mutableListOf<SignalKValue>()
        
        // Navigation orientation data
        sensorData.magneticHeading?.let { heading ->
            values.add(
                SignalKValue(
                    path = "navigation.headingMagnetic",
                    value = SignalKValues.number(heading.toDouble()) // Already in radians
                )
            )
        }
        
        sensorData.trueHeading?.let { heading ->
            values.add(
                SignalKValue(
                    path = "navigation.headingTrue", 
                    value = SignalKValues.number(heading.toDouble()) // Already in radians
                )
            )
        }
        
        sensorData.courseOverGround?.let { course ->
            values.add(
                SignalKValue(
                    path = "navigation.courseOverGroundTrue",
                    value = SignalKValues.number(course.toDouble()) // Already in radians
                )
            )
        }
        
        sensorData.speedOverGround?.let { speed ->
            values.add(
                SignalKValue(
                    path = "navigation.speedOverGround",
                    value = SignalKValues.number(speed.toDouble()) // Already in m/s
                )
            )
        }
        
        // Device attitude (roll, pitch, yaw)
        if (sensorData.roll != null || sensorData.pitch != null || sensorData.yaw != null) {
            val attitude = buildJsonObject {
                sensorData.roll?.let { put("roll", JsonPrimitive(it.toDouble())) }
                sensorData.pitch?.let { put("pitch", JsonPrimitive(it.toDouble())) }
                sensorData.yaw?.let { put("yaw", JsonPrimitive(it.toDouble())) }
            }
            values.add(
                SignalKValue(
                    path = "navigation.attitude",
                    value = attitude
                )
            )
        }
        
        sensorData.rateOfTurn?.let { rate ->
            values.add(
                SignalKValue(
                    path = "navigation.rateOfTurn",
                    value = SignalKValues.number(rate.toDouble()) // Already in rad/s
                )
            )
        }
        
        // Environmental sensors
        sensorData.pressure?.let { pressure ->
            values.add(
                SignalKValue(
                    path = "environment.outside.pressure",
                    value = SignalKValues.number(pressure.toDouble()) // Already in Pa
                )
            )
        }
        
        sensorData.temperature?.let { temperature ->
            values.add(
                SignalKValue(
                    path = "environment.outside.temperature",
                    value = SignalKValues.number(temperature.toDouble()) // Already in K
                )
            )
        }
        
        sensorData.relativeHumidity?.let { humidity ->
            values.add(
                SignalKValue(
                    path = "environment.outside.relativeHumidity",
                    value = SignalKValues.number(humidity.toDouble()) // Already as ratio
                )
            )
        }
        
        sensorData.illuminance?.let { illuminance ->
            values.add(
                SignalKValue(
                    path = "environment.outside.illuminance",
                    value = SignalKValues.number(illuminance.toDouble()) // Already in Lux
                )
            )
        }
        
        // Device electrical information
        sensorData.batteryLevel?.let { level ->
            values.add(
                SignalKValue(
                    path = "electrical.batteries.phone.capacity.stateOfCharge",
                    value = SignalKValues.number(level.toDouble()) // Already as ratio
                )
            )
        }
        
        sensorData.batteryVoltage?.let { voltage ->
            values.add(
                SignalKValue(
                    path = "electrical.batteries.phone.voltage",
                    value = SignalKValues.number(voltage.toDouble()) // Already in V
                )
            )
        }
        
        if (values.isEmpty()) return SignalKMessage(
            context = "vessels.self", 
            updates = emptyList(),
            token = if (transmissionProtocol == TransmissionProtocol.UDP) {
                authenticationService.getAuthToken()
            } else null  // For WebSocket, token goes in connection headers
        )
        
        val update = SignalKUpdate(
            source = source,
            timestamp = timestamp,
            values = values
        )
        
        return SignalKMessage(
            context = "vessels.self",
            updates = listOf(update),
            token = if (transmissionProtocol == TransmissionProtocol.UDP) {
                authenticationService.getAuthToken()
            } else null  // For WebSocket, token goes in connection headers
        )
    }
    
    private suspend fun sendMessage(message: SignalKMessage) {
        try {
            val json = Json.encodeToString(message)
            
            when (transmissionProtocol) {
                TransmissionProtocol.UDP -> {
                    sendViaUDP(json)
                }
                TransmissionProtocol.WEBSOCKET, TransmissionProtocol.WEBSOCKET_SSL -> {
                    sendViaWebSocket(json)
                }
            }
            
            // Update tracking state
            _lastSentMessage.value = json
            _messagesSent.value = _messagesSent.value + 1
            _lastTransmissionTime.value = System.currentTimeMillis()
        } catch (e: Exception) {
            _connectionStatus.value = false
            // Log the error details for debugging
            println("SignalK transmission error: ${e.javaClass.simpleName} - ${e.message}")
            e.printStackTrace()
        }
    }
    
    private suspend fun sendViaUDP(json: String) {
        val data = json.toByteArray()
        socket?.let { socket ->
            targetAddress?.let { address ->
                val packet = DatagramPacket(data, data.size, address, serverPort)
                withContext(Dispatchers.IO) {
                    socket.send(packet)
                }
            }
        }
    }
    
    private suspend fun sendViaWebSocket(json: String) {
        webSocket?.let { ws ->
            ws.send(json)
        } ?: run {
            throw Exception("WebSocket connection not established")
        }
    }
    
    private suspend fun initializeWebSocket() {
        withContext(Dispatchers.IO) {
            okHttpClient = OkHttpClient.Builder()
                .build()
            
            val streamUrl = "${baseUrl}/signalk/v1/stream"
            val request = Request.Builder()
                .url(streamUrl)
                .apply {
                    // Add authentication header if we have a token
                    authenticationService.getAuthToken()?.let { token ->
                        addHeader("Authorization", "Bearer $token")
                    }
                }
                .build()
            
            val webSocketListener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    _connectionStatus.value = true
                    println("WebSocket connected to SignalK server")
                }
                
                override fun onMessage(webSocket: WebSocket, text: String) {
                    // Handle incoming messages from server (optional)
                    println("Received from SignalK: $text")
                }
                
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    println("WebSocket closing: $code $reason")
                }
                
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    _connectionStatus.value = false
                    println("WebSocket closed: $code $reason")
                }
                
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _connectionStatus.value = false
                    println("WebSocket error: ${t.message}")
                    t.printStackTrace()
                }
            }
            
            webSocket = okHttpClient?.newWebSocket(request, webSocketListener)
        }
    }
}
