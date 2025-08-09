package com.signalk.companion.service

import android.content.Context
import com.signalk.companion.data.model.*
import com.signalk.companion.ui.main.TransmissionProtocol
import com.signalk.companion.util.AppSettings
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
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SignalKTransmitter @Inject constructor(
    private val authenticationService: AuthenticationService
) {
    
    private var context: Context? = null
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
    
    private val _authenticationError = MutableStateFlow<String?>(null)
    val authenticationError: StateFlow<String?> = _authenticationError
    
    private val _lastSentMessage = MutableStateFlow<String?>(null)
    val lastSentMessage: StateFlow<String?> = _lastSentMessage
    
    private val _messagesSent = MutableStateFlow(0)
    val messagesSent: StateFlow<Int> = _messagesSent
    
    private val _lastTransmissionTime = MutableStateFlow<Long?>(null)
    val lastTransmissionTime: StateFlow<Long?> = _lastTransmissionTime
    
    private val _lastDnsRefresh = MutableStateFlow<String?>(null)
    val lastDnsRefresh: StateFlow<String?> = _lastDnsRefresh
    
    private val _currentResolvedIp = MutableStateFlow<String?>(null)
    val currentResolvedIp: StateFlow<String?> = _currentResolvedIp
    
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
        }
        
        // Don't resolve DNS immediately - do it when actually starting streaming
        // This allows hostnames like "signalk.local", "my-boat.local", etc.
        _connectionStatus.value = false
    }
    
    fun setContext(context: Context) {
        this.context = context
    }
    
    // Configure from a full URL and auto-detect WebSocket protocol based on HTTP/HTTPS
    fun configureFromUrl(fullUrl: String, protocol: TransmissionProtocol = TransmissionProtocol.UDP) {
        val parsedUrl = parseUrl(fullUrl)
        
        // Auto-detect WebSocket protocol (WEBSOCKET handles both HTTP and HTTPS)
        val detectedProtocol = when {
            protocol == TransmissionProtocol.UDP -> TransmissionProtocol.UDP
            else -> TransmissionProtocol.WEBSOCKET
        }
        
        val port = when (detectedProtocol) {
            TransmissionProtocol.UDP -> 55555  // Fixed UDP port
            TransmissionProtocol.WEBSOCKET -> parsedUrl.port
        }
        
        serverAddress = parsedUrl.hostname
        serverPort = port
        transmissionProtocol = detectedProtocol
        
        // Build WebSocket URL based on detected protocol
        baseUrl = when (detectedProtocol) {
            TransmissionProtocol.UDP -> ""
            TransmissionProtocol.WEBSOCKET -> {
                // Auto-detect ws:// or wss:// based on original URL protocol
                val wsProtocol = if (parsedUrl.isHttps) "wss" else "ws"
                "$wsProtocol://${parsedUrl.hostname}:${port}"
            }
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
            TransmissionProtocol.WEBSOCKET -> 3000  // SignalK WebSocket port (auto-detects SSL)
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
                TransmissionProtocol.WEBSOCKET -> {
                    // Initial DNS resolution for WebSocket
                    withContext(Dispatchers.IO) {
                        refreshDnsResolution()
                    }
                    
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
            val oldAddress = targetAddress
            val newAddress = InetAddress.getByName(serverAddress)
                    
            // Check if IP address actually changed
            if (oldAddress == null || !oldAddress.equals(newAddress)) {
                targetAddress = newAddress
                val message = "DNS resolved: $serverAddress -> ${newAddress.hostAddress} (was: ${oldAddress?.hostAddress ?: "null"})"
                println(message)
                _lastDnsRefresh.value = message
            }

            when (transmissionProtocol) {
                TransmissionProtocol.UDP -> {
                }
                TransmissionProtocol.WEBSOCKET -> {
                    // For WebSocket, test DNS resolution and reconnect if needed
                    
                    // Check if IP address actually changed
                    if (oldAddress == null || !oldAddress.equals(newAddress)) {
                        // IP changed - if WebSocket is disconnected, try to reconnect
                        if (_connectionStatus.value == false && webSocket == null) {
                            println("WebSocket disconnected and IP changed, attempting reconnection after DNS refresh")
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    initializeWebSocket()
                                } catch (e: Exception) {
                                    println("WebSocket reconnection failed: ${e.message}")
                                }
                            }
                        }
                    } else {
                        // IP didn't change, just update the tracking without logging
                        println("DNS refresh for WebSocket: $serverAddress -> ${newAddress.hostAddress} (no change)")
                    }
                }
            }
            _currentResolvedIp.value = newAddress.hostAddress
        } catch (e: Exception) {
            println("DNS resolution failed for $serverAddress: ${e.message}")
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
                        println("Performing periodic DNS refresh for $serverAddress...")
                        refreshDnsResolution()
                    } catch (e: Exception) {
                        println("DNS refresh failed: ${e.message}")
                        // For UDP, if we lose DNS resolution completely, mark as disconnected
                        if (transmissionProtocol == TransmissionProtocol.UDP && targetAddress == null) {
                            _connectionStatus.value = false
                        }
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
        _lastDnsRefresh.value = null
        _currentResolvedIp.value = null
    }
    
    // Manual DNS refresh - can be called from UI if user reports connectivity issues
    suspend fun refreshDns() {
        if (socket != null || webSocket != null) {
            withContext(Dispatchers.IO) {
                refreshDnsResolution()
            }
        }
    }
    
    // Clear authentication error - can be called from UI after user acknowledges the error
    fun clearAuthenticationError() {
        _authenticationError.value = null
    }
    
    suspend fun sendLocationData(locationData: LocationData, sendLocation: Boolean = true) {
        if (sendLocation) {
            val signalKMessage = createLocationMessage(locationData)
            sendMessage(signalKMessage)
        }
    }
    
    suspend fun sendSensorData(sensorData: SensorData, sendHeading: Boolean = true, sendPressure: Boolean = true) {
        val signalKMessage = createSensorMessage(sensorData, sendHeading, sendPressure)
        sendMessage(signalKMessage)
    }
    
    private fun createLocationMessage(locationData: LocationData): SignalKMessage {
        val timestamp = dateFormat.format(Date(locationData.timestamp))
        val source = SignalKSource(
            label = "SignalK Navigation Provider",
            src = "signalk-nav-provider"
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
        
        val vesselContext = context?.let { AppSettings.getSignalKContext(it) } ?: "vessels.self"
        
        return SignalKMessage(
            context = vesselContext,
            updates = listOf(update)
        )
    }    private fun createSensorMessage(sensorData: SensorData, sendHeading: Boolean = true, sendPressure: Boolean = true): SignalKMessage {
        val timestamp = dateFormat.format(Date(sensorData.timestamp))
        val source = SignalKSource(
            label = "SignalK Navigation Provider - Sensors",
            src = "signalk-nav-provider-sensors"
        )
        
        val values = mutableListOf<SignalKValue>()
        
        // Navigation orientation data (only if heading is enabled)
        if (sendHeading) {
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
        } // End of sendHeading condition
        
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
        
        // Environmental sensors (conditional based on settings)        } // End of sendHeading condition
        
        // Environmental sensors (conditional based on settings)
        if (sendPressure) {
            sensorData.pressure?.let { pressure ->
                values.add(
                    SignalKValue(
                        path = "environment.outside.pressure",
                        value = SignalKValues.number(pressure.toDouble()) // Already in Pa
                    )
                )
            }
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

        val vesselContext = context?.let { AppSettings.getSignalKContext(it) } ?: "vessels.self"
        
        if (values.isEmpty()) return SignalKMessage(
            context = vesselContext, 
            updates = emptyList()
        )

        val update = SignalKUpdate(
            source = source,
            timestamp = timestamp,
            values = values
        )
        
        return SignalKMessage(
            context = vesselContext,
            updates = listOf(update)
        )
    }
    
    private suspend fun sendMessage(message: SignalKMessage) {
        try {
            val json = Json.encodeToString(message)
            
            when (transmissionProtocol) {
                TransmissionProtocol.UDP -> {
                    sendViaUDP(json)
                }
                TransmissionProtocol.WEBSOCKET -> {
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
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .connectTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
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
                    
                    // Check if this is an authentication failure
                    response?.let { resp ->
                        if (resp.code == 401 || resp.code == 403) {
                            val errorMsg = "Authentication failed (${resp.code}): Token may be expired or invalid"
                            println(errorMsg)
                            _authenticationError.value = errorMsg
                            
                            // Launch coroutine to handle token renewal and reconnection
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    println("Attempting automatic token renewal...")
                                    val result = authenticationService.tryRefreshToken()
                                    if (result.isSuccess && result.getOrNull() != null) {
                                        println("Token renewed successfully, attempting to reconnect...")
                                        _authenticationError.value = null // Clear error
                                        // Token was refreshed, attempt to reconnect
                                        delay(1000) // Brief delay before reconnection
                                        initializeWebSocket()
                                    } else {
                                        val failureMsg = "Automatic token renewal failed - manual re-authentication required"
                                        println(failureMsg)
                                        _authenticationError.value = failureMsg
                                    }
                                } catch (e: Exception) {
                                    val renewalError = "Error during token renewal: ${e.message}"
                                    println(renewalError)
                                    _authenticationError.value = renewalError
                                    e.printStackTrace()
                                }
                            }
                        } else {
                            // Non-authentication error, could be network issue
                            _authenticationError.value = null
                        }
                    } ?: run {
                        // No response means likely network error
                        _authenticationError.value = null
                    }
                }
            }
            
            webSocket = okHttpClient?.newWebSocket(request, webSocketListener)
        }
    }
}
