package com.signalk.companion.service

import android.content.Context
import android.util.Log
import com.signalk.companion.data.model.*
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
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transmits SignalK messages over WebSocket.
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
    private var serverPort: Int = 3000
    private var baseUrl: String = ""  // Store the full base URL for HTTP(S) streaming
    
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
    
    private val dateFormat = java.time.format.DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(java.time.ZoneOffset.UTC)
    
    fun configure(parsedUrl: UrlParser.ParsedUrl) {
        serverAddress = parsedUrl.hostname
        serverPort = parsedUrl.port
        
        // Build WebSocket URL - auto-detect ws:// or wss:// based on original URL protocol
        val wsProtocol = if (parsedUrl.isHttps) "wss" else "ws"
        baseUrl = "$wsProtocol://${parsedUrl.hostname}:${parsedUrl.port}"
        
        _connectionStatus.value = false
    }
    
    fun setContext(context: Context) {
        this.context = context
    }
    
    suspend fun startStreaming() {
        // Create a fresh scope for this streaming session
        transmitterScope?.cancel()
        transmitterScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        
        try {
            // Initial DNS resolution for WebSocket
            withContext(Dispatchers.IO) {
                refreshDnsResolution()
            }
            
            // Initialize WebSocket connection
            initializeWebSocket()
            
            // Start periodic DNS refresh for hostname resolution
            startDnsRefreshTimer()
            
            _connectionStatus.value = true
        } catch (e: Exception) {
            _connectionStatus.value = false
            throw e
        }
    }
    
    private fun refreshDnsResolution() {
        try {
            val newAddress = InetAddress.getByName(serverAddress)
            val oldIp = _currentResolvedIp.value
            val newIp = newAddress.hostAddress
            
            // Track whether IP changed for diagnostic purposes
            val ipChanged = oldIp != null && oldIp != newIp
            val message = if (ipChanged) {
                "DNS resolved: $serverAddress -> $newIp (changed from $oldIp)"
            } else if (oldIp == null) {
                "DNS resolved: $serverAddress -> $newIp (initial)"
            } else {
                "DNS resolved: $serverAddress -> $newIp (unchanged)"
            }
            Log.d(TAG, message)
            _lastDnsRefresh.value = message
            _currentResolvedIp.value = newIp
            
            // If WebSocket is disconnected, try to reconnect
            if (!_connectionStatus.value && webSocket == null) {
                Log.d(TAG, "WebSocket disconnected, attempting reconnection after DNS refresh")
                transmitterScope?.launch {
                    try {
                        initializeWebSocket()
                    } catch (e: Exception) {
                        Log.e(TAG, "WebSocket reconnection failed: ${e.message}", e)
                    }
                } ?: Log.w(TAG, "Cannot attempt reconnection - transmitter scope is null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "DNS resolution failed for $serverAddress: ${e.message}", e)
            // DNS resolution failed - but don't kill the entire streaming
            // Keep the WebSocket going, it will handle its own reconnection
        }
    }
    
    private fun startDnsRefreshTimer() {
        val scope = transmitterScope ?: run {
            Log.w(TAG, "Cannot start DNS refresh timer - transmitter scope is null")
            return
        }
        
        // Cancel any existing refresh timer
        dnsRefreshJob?.cancel()
        
        // Start new refresh timer.
        // Use isActive (not `webSocket != null`) so the loop keeps running while
        // streaming, including during temporary disconnects while reconnecting.
        dnsRefreshJob = scope.launch {
            while (isActive) {
                delay(DNS_REFRESH_INTERVAL_MS)
                if (isActive) {
                    try {
                        Log.d(TAG, "Performing periodic DNS refresh for $serverAddress...")
                        refreshDnsResolution()
                    } catch (e: Exception) {
                        Log.e(TAG, "DNS refresh failed: ${e.message}", e)
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
                    initializeWebSocket()
                } catch (e: Exception) {
                    Log.e(TAG, "Scheduled WebSocket reconnection failed: ${e.message}", e)
                    // State is already DISCONNECTED, so future reconnection attempts are possible
                }
            } else {
                Log.d(TAG, "Skipping scheduled reconnection - state: ${webSocketState.get()}")
            }
        }
    }
    
    fun stopStreaming() {
        // Cancel all background jobs by cancelling the scope
        transmitterScope?.cancel()
        transmitterScope = null
        dnsRefreshJob = null
        reconnectionJob = null

        // Close WebSocket connection
        webSocket?.close(1000, "Streaming stopped")
        webSocket = null

        // Properly shut down OkHttpClient to release its thread pool and connection pool.
        // Simply dropping the reference (okHttpClient = null) leaves the Dispatcher's
        // CachedThreadPool alive for up to 60 s and leaks an OkIO Segment pool (~8 MB).
        // After several reconnects this accumulates significantly.
        okHttpClient?.let { client ->
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
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
        if (webSocket != null) {
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
        val timestamp = dateFormat.format(java.time.Instant.ofEpochMilli(locationData.timestamp))
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
        // Use null check instead of > 0 to allow valid zero speed (stationary)
        if (locationData.speed != null) {
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
        // Use null check instead of > 0 to allow valid zero bearing (True North)
        if (locationData.bearing != null) {
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
        // Use null check instead of != 0.0 to allow valid zero altitude (sea level)
        if (locationData.altitude != null) {
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
    }
    
    private fun createSensorMessage(sensorData: SensorData, sendHeading: Boolean = true, sendPressure: Boolean = true): SignalKMessage {
        val timestamp = dateFormat.format(java.time.Instant.ofEpochMilli(sensorData.timestamp))
        val source = SignalKSource(
            label = "SignalK Pose Provider - Sensors",
            src = "signalk-nav-provider-sensors"
        )
        
        val values = mutableListOf<SignalKValue>()
        
        // Navigation orientation data (only if heading is enabled).
        //
        // Heading rides on `navigation.headingCompass`, whose spec description — "magnetic
        // heading received from the compass, this is not adjusted for magneticDeviation" —
        // is a precise statement of what we have until M2. `headingMagnetic` is defined as
        // "headingCompass adjusted for magneticDeviation" and `headingTrue` as
        // "headingMagnetic adjusted for magneticVariation", so publishing on either would
        // assert a correction we have not made. The spec's own vocabulary is the honesty
        // marker; no custom quality path is needed (frame-conventions.md §11).
        //
        // At M2 this promotes to `headingMagnetic`, alongside `navigation.magneticDeviation`
        // carrying the correction actually applied.
        if (sendHeading) {
            sensorData.compassHeading?.let { heading ->
                SignalKValues.finiteNumber(heading.toDouble())?.let { v ->  // already radians
                    values.add(SignalKValue(path = "navigation.headingCompass", value = v))
                }
            }

            // Variation is a property of position, not of our compass, so it is honest to
            // publish today. It also lets a consumer derive true heading itself, with the
            // deviation caveat visible in the path name it came from.
            sensorData.magneticVariation?.let { variation ->
                SignalKValues.finiteNumber(variation.toDouble())?.let { v ->  // already radians
                    values.add(SignalKValue(path = "navigation.magneticVariation", value = v))
                }
            }
        } // End of sendHeading condition

        // Magnetometer accuracy (always sent when heading is enabled, independent of GPS)
        if (sendHeading) {
            sensorData.magnetometerAccuracy?.let { acc ->
                values.add(
                    SignalKValue(
                        path = "sensors.magnetometer.accuracy",
                        value = SignalKValues.number(acc.toDouble()) // 0=unreliable, 1=low, 2=medium, 3=high
                    )
                )
            }
        }

        // Vehicle attitude. Roll is positive to starboard ("list to starboard" in the spec's
        // wording), pitch positive bow-up — both verified verbatim against the schema. Roll
        // here is instantaneous inclination: steady heel plus wave-driven roll oscillation
        // (frame-conventions.md §5).
        //
        // No `yaw` member, permanently rather than pending M1. The field that used to be here
        // carried a gyro *rate* in a slot consumers read as an *angle* (audit A2), but the
        // deeper reason is that SignalK defines no datum for `attitude.yaw`: with north as
        // datum it merely duplicates the heading paths, and with mean heading as datum it is
        // a yawing oscillation — two incompatible readings the spec never resolves
        // (frame-conventions.md §11). Heading rides on the heading paths, which are
        // unambiguous.
        val rollValue = sensorData.roll?.toDouble()?.takeIf { it.isFinite() }
        val pitchValue = sensorData.pitch?.toDouble()?.takeIf { it.isFinite() }
        if (rollValue != null || pitchValue != null) {
            val attitude = buildJsonObject {
                rollValue?.let { put("roll", JsonPrimitive(it)) }
                pitchValue?.let { put("pitch", JsonPrimitive(it)) }
            }
            values.add(
                SignalKValue(
                    path = "navigation.attitude",
                    value = attitude
                )
            )
        }

        // Vehicle-frame rate of turn, positive to starboard (frame-conventions.md §4.2).
        sensorData.rateOfTurn?.let { rate ->
            SignalKValues.finiteNumber(rate.toDouble())?.let { v ->  // already rad/s
                values.add(SignalKValue(path = "navigation.rateOfTurn", value = v))
            }
        }
        
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
            
            webSocket?.let { ws ->
                ws.send(json)
            } ?: run {
                throw Exception("WebSocket connection not established")
            }
            
            // Update tracking state
            _lastSentMessage.value = json
            _messagesSent.value = _messagesSent.value + 1
            _lastTransmissionTime.value = System.currentTimeMillis()
        } catch (e: Exception) {
            _connectionStatus.value = false
            // Log the error details for debugging
            Log.e(TAG, "SignalK transmission error: ${e.javaClass.simpleName} - ${e.message}", e)
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

        // If we have no token but stored credentials exist, try to login now.
        // This covers the case where the startup auto-login raced with a server restart
        // and the server wasn't ready yet.  We attempt this before opening the WebSocket
        // so the WS upgrade request can carry a valid Authorization header immediately.
        if (authenticationService.getAuthToken() == null && authenticationService.hasStoredCredentials()) {
            Log.d(TAG, "No token available — attempting login before WebSocket connection")
            authenticationService.tryRefreshToken()
        }
        
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
                            val failureMsg = "Automatic token renewal failed - will retry"
                            Log.e(TAG, failureMsg)
                            _authenticationError.value = failureMsg
                            // Keep retrying as long as we have credentials: the server may
                            // still be coming up (auth endpoint and WebSocket together).
                            if (authenticationService.hasStoredCredentials() &&
                                transmitterScope?.isActive == true) {
                                Log.d(TAG, "Credentials exist — scheduling reconnect retry in 30 s")
                                scheduleReconnection(30_000)
                            }
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
