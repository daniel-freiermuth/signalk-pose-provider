package com.signalk.companion.service

import android.content.Context
import android.util.Log
import com.signalk.companion.data.model.*
import com.signalk.companion.ui.main.TransmissionProtocol
import com.signalk.companion.util.AppSettings
import com.signalk.companion.util.UrlParser
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
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transmits SignalK messages over UDP or WebSocket.
 * 
 * Lifecycle: Call stopStreaming() when done to cancel all background jobs.
 */
@Singleton
class SignalKTransmitter @Inject constructor(
    private val authenticationService: AuthenticationService
) {
    
    companion object {
        private const val TAG = "SignalKTransmitter"
    }
    
    /**
     * WebSocket connection state machine.
     * State transitions are atomic via AtomicReference.compareAndSet.
     */
    private enum class WebSocketState {
        DISCONNECTED,  // No connection, ready to connect
        CONNECTING,    // Connection attempt in progress
        CONNECTED      // WebSocket is open and functional
    }
    
    private var context: Context? = null
    private var serverAddress: String = ""
    private var serverPort: Int = 55555
    private var baseUrl: String = ""  // Store the full base URL for HTTP(S) streaming
    private var transmissionProtocol: TransmissionProtocol = TransmissionProtocol.UDP
    private var socket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null
    
    // WebSocket support
    private var okHttpClient: OkHttpClient? = null
    @Volatile private var webSocket: WebSocket? = null
    private val webSocketState = AtomicReference(WebSocketState.DISCONNECTED)
    
    // Managed coroutine scope for all background jobs - cancelled in stopStreaming()
    private var transmitterScope: CoroutineScope? = null
    private var dnsRefreshJob: Job? = null
    private var reconnectionJob: Job? = null
    
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
    
    fun updateProtocol(isHttps: Boolean) {
        if (transmissionProtocol == TransmissionProtocol.WEBSOCKET) {
            val wsProtocol = if (isHttps) "wss" else "ws"
            baseUrl = "$wsProtocol://$serverAddress:$serverPort"
        }
    }
    
    fun setContext(context: Context) {
        this.context = context
    }
    
    // Configure from a full URL and auto-detect WebSocket protocol based on HTTP/HTTPS
    // Note: URL should be pre-validated before calling this method
    fun configureFromUrl(fullUrl: String, protocol: TransmissionProtocol = TransmissionProtocol.UDP) {
        val parsedUrl = UrlParser.parseUrl(fullUrl)
        if (parsedUrl == null) {
            // This should not happen if URL was pre-validated, but handle defensively
            throw IllegalArgumentException("Invalid SignalK server URL: $fullUrl")
        }
        
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
        // Create a fresh scope for this streaming session
        transmitterScope?.cancel()
        transmitterScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        
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
                            Log.d(TAG, "WebSocket disconnected and IP changed, attempting reconnection after DNS refresh")
                            transmitterScope?.launch {
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
        val scope = transmitterScope ?: run {
            Log.w(TAG, "Cannot start DNS refresh timer - transmitter scope is null")
            return
        }
        
        // Cancel any existing refresh timer
        dnsRefreshJob?.cancel()
        
        // Start new refresh timer
        dnsRefreshJob = scope.launch {
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
    
    private fun scheduleReconnection(delayMs: Long) {
        val scope = transmitterScope ?: run {
            Log.w(TAG, "Cannot schedule reconnection - transmitter scope is null (streaming stopped?)")
            return
        }
        
        // Cancel any existing reconnection attempt to avoid piling up
        reconnectionJob?.cancel()
        reconnectionJob = scope.launch {
            delay(delayMs)
            // Only attempt if still disconnected
            if (webSocketState.get() == WebSocketState.DISCONNECTED) {
                Log.d(TAG, "Executing scheduled WebSocket reconnection...")
                try {
                    println("Executing scheduled WebSocket reconnection...")
                    initializeWebSocket()
                } catch (e: Exception) {
                    println("Scheduled WebSocket reconnection failed: ${e.message}")
                    // Could implement exponential backoff here if needed
                }
            } else {
                println("Skipping scheduled reconnection - already connected")
            }
        }
    }
    
    fun stopStreaming() {
        // Cancel all background jobs by cancelling the scope
        transmitterScope?.cancel()
        transmitterScope = null
        dnsRefreshJob = null
        reconnectionJob = null
        
        // Close socket and reset state
        socket?.close()
        socket = null
        targetAddress = null
        
        // Close WebSocket connection
        webSocket?.close(1000, "Streaming stopped")
        webSocket = null
        okHttpClient = null
        webSocketState.set(WebSocketState.DISCONNECTED)
        
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
            label = "SignalK Pose Provider",
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
            label = "SignalK Pose Provider - Sensors",
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
        // Atomic state transition: DISCONNECTED -> CONNECTING
        // If already CONNECTING or CONNECTED, this returns false and we skip initialization
        if (!webSocketState.compareAndSet(WebSocketState.DISCONNECTED, WebSocketState.CONNECTING)) {
            Log.d(TAG, "WebSocket initialization skipped - current state: ${webSocketState.get()}")
            return
        }
        
        Log.d(TAG, "Starting WebSocket connection...")
        
        withContext(Dispatchers.IO) {
            try {
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
                        // Transition: CONNECTING -> CONNECTED
                        webSocketState.set(WebSocketState.CONNECTED)
                        _connectionStatus.value = true
                        Log.d(TAG, "WebSocket connected to SignalK server")
                    }
                
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        Log.d(TAG, "Received from SignalK: $text")
                    }
                    
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        Log.d(TAG, "WebSocket closing: $code $reason")
                    }
                    
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        // Transition: any state -> DISCONNECTED
                        webSocketState.set(WebSocketState.DISCONNECTED)
                        _connectionStatus.value = false
                        this@SignalKTransmitter.webSocket = null
                        Log.d(TAG, "WebSocket closed: $code $reason")
                        
                        // Auto-reconnect for unexpected closures (not user-initiated)
                        if (code != 1000) {
                            Log.w(TAG, "Unexpected WebSocket closure (code: $code), scheduling reconnection...")
                            scheduleReconnection(5000)
                        }
                    }
                    
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        // Transition: any state -> DISCONNECTED
                        webSocketState.set(WebSocketState.DISCONNECTED)
                        _connectionStatus.value = false
                        this@SignalKTransmitter.webSocket = null
                        Log.e(TAG, "WebSocket error: ${t.message}", t)
                        
                        handleWebSocketFailure(response)
                    }
                }
                
                webSocket = okHttpClient?.newWebSocket(request, webSocketListener)
                
            } catch (e: Exception) {
                // Transition back to DISCONNECTED on setup error
                webSocketState.set(WebSocketState.DISCONNECTED)
                Log.e(TAG, "WebSocket initialization error: ${e.message}", e)
                throw e
            }
        }
    }
    
    /**
     * Handle WebSocket failure by checking for auth errors and scheduling reconnection.
     */
    private fun handleWebSocketFailure(response: Response?) {
        response?.let { resp ->
            if (resp.code == 401 || resp.code == 403) {
                val errorMsg = "Authentication failed (${resp.code}): Token may be expired or invalid"
                Log.e(TAG, errorMsg)
                _authenticationError.value = errorMsg
                
                // Attempt automatic token renewal
                // Use NonCancellable to ensure token renewal completes even if streaming stops
                transmitterScope?.launch {
                    try {
                        Log.d(TAG, "Attempting automatic token renewal...")
                        val result = withContext(NonCancellable) {
                            authenticationService.tryRefreshToken()
                        }
                        if (result.isSuccess && result.getOrNull() != null) {
                            Log.d(TAG, "Token renewed successfully")
                            _authenticationError.value = null
                            // Only schedule reconnection if scope is still active
                            if (transmitterScope?.isActive == true) {
                                Log.d(TAG, "Scheduling reconnection...")
                                scheduleReconnection(1000)
                            } else {
                                Log.d(TAG, "Streaming stopped - skipping reconnection after token renewal")
                            }
                        } else {
                            val failureMsg = "Automatic token renewal failed - manual re-authentication required"
                            Log.e(TAG, failureMsg)
                            _authenticationError.value = failureMsg
                        }
                    } catch (e: Exception) {
                        val renewalError = "Error during token renewal: ${e.message}"
                        Log.e(TAG, renewalError, e)
                        _authenticationError.value = renewalError
                    }
                } ?: Log.w(TAG, "Cannot attempt token renewal - transmitter scope is null")
            } else {
                // Non-authentication HTTP error
                _authenticationError.value = null
                Log.w(TAG, "WebSocket failure (HTTP ${resp.code}), scheduling reconnection...")
                scheduleReconnection(10000)
            }
        } ?: run {
            // No response = network error (connection refused, timeout, etc.)
            _authenticationError.value = null
            Log.w(TAG, "Network-related WebSocket failure, scheduling reconnection...")
            scheduleReconnection(10000)
        }
    }
}
