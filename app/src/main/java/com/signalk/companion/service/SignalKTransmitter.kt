package com.signalk.companion.service

import android.content.Context
import android.util.Log
import com.signalk.companion.data.model.LocationData
import com.signalk.companion.data.model.SensorData
import com.signalk.companion.data.model.SignalKMessage
import com.signalk.companion.data.model.SignalKSource
import com.signalk.companion.data.model.SignalKUpdate
import com.signalk.companion.data.model.SignalKValue
import com.signalk.companion.data.model.SignalKValues
import com.signalk.companion.di.IoDispatcher
import com.signalk.companion.util.UrlParser
import com.signalk.companion.util.VesselSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.UnknownHostException
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
class SignalKTransmitter internal constructor(
    private val authenticationService: AuthenticationService,
    private val ioDispatcher: CoroutineDispatcher,
    /**
     * Waits out a scheduled reconnection's delay, in milliseconds. Injectable so tests can
     * observe which delay each failure path requests and control when it elapses.
     */
    private val reconnectDelay: suspend (Long) -> Unit
) {

    @Inject
    constructor(
        authenticationService: AuthenticationService,
        @IoDispatcher ioDispatcher: CoroutineDispatcher
    ) : this(authenticationService, ioDispatcher, { delayMs -> delay(delayMs) })

    companion object {
        private const val TAG = "SignalKTransmitter"

        // DNS refresh interval (5 minutes) - good balance between responsiveness and network load
        private const val DNS_REFRESH_INTERVAL_MS = 5 * 60 * 1000L

        private const val READ_TIMEOUT_S = 60L
        private const val WRITE_TIMEOUT_S = 60L
        private const val CONNECT_TIMEOUT_S = 30L

        /** RFC 6455 close code for a deliberate, user-initiated closure. */
        private const val NORMAL_CLOSURE = 1000

        private const val UNEXPECTED_CLOSURE_RECONNECT_DELAY_MS = 5_000L
        private const val FAILURE_RECONNECT_DELAY_MS = 10_000L
        private const val TOKEN_RENEWED_RECONNECT_DELAY_MS = 1_000L
        private const val TOKEN_RENEWAL_RETRY_DELAY_MS = 30_000L

        private const val DEFAULT_VESSEL_CONTEXT = "vessels.self"

        private val LOCATION_SOURCE = SignalKSource(
            label = "SignalK Pose Provider",
            src = "signalk-nav-provider"
        )
        private val SENSOR_SOURCE = SignalKSource(
            label = "SignalK Pose Provider - Sensors",
            src = "signalk-nav-provider-sensors"
        )
    }

    /**
     * WebSocket connection state machine.
     * State transitions are atomic via AtomicReference.compareAndSet.
     */
    private enum class WebSocketState {
        DISCONNECTED, // No connection, ready to connect
        CONNECTING, // Connection attempt in progress
        CONNECTED // WebSocket is open and functional
    }

    private var context: Context? = null
    private var serverAddress: String = ""
    private var serverPort: Int = 3000
    private var baseUrl: String = "" // Store the full base URL for HTTP(S) streaming

    // WebSocket support
    private var okHttpClient: OkHttpClient? = null

    @Volatile private var webSocket: WebSocket? = null
    private val webSocketState = AtomicReference(WebSocketState.DISCONNECTED)

    /**
     * Identifies the connection attempt that owns [webSocket], [webSocketState] and
     * [connectionStatus]. OkHttp keeps delivering a socket's callbacks after streaming is
     * stopped and restarted, so each listener checks this, under [connectionLock], before
     * touching shared state; bumping it disowns every earlier socket at once.
     */
    private val connectionLock = Any()
    private var connectionGeneration = 0L // guarded by connectionLock

    // Managed coroutine scope for all background jobs - cancelled in stopStreaming()
    private var transmitterScope: CoroutineScope? = null
    private var dnsRefreshJob: Job? = null
    private var reconnectionJob: Job? = null

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
        transmitterScope = CoroutineScope(SupervisorJob() + ioDispatcher)

        // Initial DNS resolution for WebSocket. Resolve only: the connection is opened
        // inline below, and a reconnect launched from here would race it for the CAS guard.
        val resolved = withContext(ioDispatcher) { resolveServerAddress() }
        if (!resolved) Log.w(TAG, "Initial DNS resolution failed; connecting anyway")

        // Initialize WebSocket connection. connectionStatus is not touched here: only the
        // listener knows whether the upgrade succeeded, so onOpen alone reports "connected".
        initializeWebSocket()

        // Start periodic DNS refresh for hostname resolution
        startDnsRefreshTimer()
    }

    /**
     * Resolves [serverAddress] and records the result for diagnostics.
     *
     * @return whether resolution succeeded.
     */
    private fun resolveServerAddress(): Boolean {
        return try {
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
            true
        } catch (e: UnknownHostException) {
            // DNS resolution failed - but don't kill the entire streaming
            // Keep the WebSocket going, it will handle its own reconnection
            Log.e(TAG, "DNS resolution failed for $serverAddress: ${e.message}", e)
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "DNS resolution not permitted for $serverAddress: ${e.message}", e)
            false
        }
    }

    /**
     * Periodic and manual DNS refresh: re-resolves the server and, if the WebSocket is
     * down, attempts to reconnect.
     */
    private fun refreshDnsResolution() {
        if (!resolveServerAddress()) return

        if (!_connectionStatus.value && webSocket == null) {
            Log.d(TAG, "WebSocket disconnected, attempting reconnection after DNS refresh")
            transmitterScope?.launch {
                reconnectWebSocket("WebSocket reconnection failed")
            } ?: Log.w(TAG, "Cannot attempt reconnection - transmitter scope is null")
        }
    }

    /** Opens a new WebSocket, logging rather than propagating a malformed server URL. */
    private suspend fun reconnectWebSocket(failureMessage: String) {
        try {
            initializeWebSocket()
        } catch (e: IllegalArgumentException) {
            // State is already DISCONNECTED, so future reconnection attempts are possible
            Log.e(TAG, "$failureMessage: ${e.message}", e)
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
                    Log.d(TAG, "Performing periodic DNS refresh for $serverAddress...")
                    refreshDnsResolution()
                }
            }
        }
    }

    private fun scheduleReconnection(delayMs: Long) {
        val scope = transmitterScope ?: run {
            Log.w(
                TAG,
                "Cannot schedule reconnection - transmitter scope is null (streaming stopped?)"
            )
            return
        }

        // Cancel any existing reconnection attempt to avoid piling up
        reconnectionJob?.cancel()
        reconnectionJob = scope.launch {
            reconnectDelay(delayMs)
            // Only attempt if still disconnected
            if (webSocketState.get() == WebSocketState.DISCONNECTED) {
                Log.d(TAG, "Executing scheduled WebSocket reconnection...")
                reconnectWebSocket("Scheduled WebSocket reconnection failed")
            } else {
                Log.d(TAG, "Skipping scheduled reconnection - state: ${webSocketState.get()}")
            }
        }
    }

    fun stopStreaming() {
        // Disown the current socket first: its listener can still deliver callbacks after
        // streaming restarts, and they must not act on the next session's state.
        synchronized(connectionLock) { connectionGeneration++ }

        // Cancel all background jobs by cancelling the scope
        transmitterScope?.cancel()
        transmitterScope = null
        dnsRefreshJob = null
        reconnectionJob = null

        // Close WebSocket connection
        webSocket?.close(NORMAL_CLOSURE, "Streaming stopped")
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
            withContext(ioDispatcher) {
                refreshDnsResolution()
            }
        }
    }

    // Clear authentication error - can be called from UI after user acknowledges the error
    fun clearAuthenticationError() {
        _authenticationError.value = null
    }

    fun sendLocationData(locationData: LocationData, sendLocation: Boolean = true) {
        if (sendLocation) {
            val signalKMessage = createLocationMessage(locationData)
            sendMessage(signalKMessage)
        }
    }

    fun sendSensorData(
        sensorData: SensorData,
        sendHeading: Boolean = true,
        sendPressure: Boolean = true
    ) {
        val signalKMessage = createSensorMessage(sensorData, sendHeading, sendPressure)
        sendMessage(signalKMessage)
    }

    private fun createLocationMessage(locationData: LocationData): SignalKMessage {
        val values = buildList {
            // Position with quality indicators
            add(
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
                add(numberValue("navigation.position.accuracy", locationData.accuracy.toDouble()))
            }

            // Speed over ground with accuracy
            // Use null check instead of > 0 to allow valid zero speed (stationary)
            locationData.speed?.let { speed ->
                add(numberValue("navigation.speedOverGround", speed.toDouble()))
                locationData.speedAccuracy?.let { speedAcc ->
                    add(numberValue("navigation.speedOverGround.accuracy", speedAcc.toDouble()))
                }
            }

            // Course over ground with accuracy
            // Use null check instead of > 0 to allow valid zero bearing (True North)
            locationData.bearing?.let { bearing ->
                add(
                    numberValue(
                        "navigation.courseOverGroundTrue",
                        Math.toRadians(bearing.toDouble())
                    )
                )
                locationData.bearingAccuracy?.let { bearingAcc ->
                    add(
                        numberValue(
                            "navigation.courseOverGroundTrue.accuracy",
                            Math.toRadians(bearingAcc.toDouble())
                        )
                    )
                }
            }

            // Altitude with accuracy
            // Use null check instead of != 0.0 to allow valid zero altitude (sea level)
            locationData.altitude?.let { altitude ->
                add(numberValue("navigation.gnss.altitude", altitude))
                locationData.verticalAccuracy?.let { vertAcc ->
                    add(numberValue("navigation.gnss.altitude.accuracy", vertAcc.toDouble()))
                }
            }
        }

        return buildMessage(LOCATION_SOURCE, locationData.timestamp, values)
    }

    private fun createSensorMessage(
        sensorData: SensorData,
        sendHeading: Boolean,
        sendPressure: Boolean
    ): SignalKMessage {
        val values = buildList {
            if (sendHeading) addAll(headingValues(sensorData))
            attitudeValue(sensorData)?.let { add(it) }

            // Vehicle-frame rate of turn, positive to starboard (frame-conventions.md §4.2).
            sensorData.rateOfTurn?.let { rate ->
                finiteNumberValue("navigation.rateOfTurn", rate.toDouble()) // already rad/s
            }?.let { add(it) }

            addAll(environmentValues(sensorData, sendPressure))
        }

        return buildMessage(SENSOR_SOURCE, sensorData.timestamp, values)
    }

    /**
     * Heading, variation and magnetometer accuracy — sent only when heading is enabled.
     *
     * Heading rides on `navigation.headingCompass`, whose spec description — "magnetic
     * heading received from the compass, this is not adjusted for magneticDeviation" —
     * is a precise statement of what we have until M2. `headingMagnetic` is defined as
     * "headingCompass adjusted for magneticDeviation" and `headingTrue` as
     * "headingMagnetic adjusted for magneticVariation", so publishing on either would
     * assert a correction we have not made. The spec's own vocabulary is the honesty
     * marker; no custom quality path is needed (frame-conventions.md §11).
     *
     * At M2 this promotes to `headingMagnetic`, alongside `navigation.magneticDeviation`
     * carrying the correction actually applied.
     */
    private fun headingValues(sensorData: SensorData): List<SignalKValue> = buildList {
        sensorData.compassHeading?.let { heading ->
            finiteNumberValue("navigation.headingCompass", heading.toDouble()) // already radians
        }?.let { add(it) }

        // Variation is a property of position, not of our compass, so it is honest to
        // publish today. It also lets a consumer derive true heading itself, with the
        // deviation caveat visible in the path name it came from.
        sensorData.magneticVariation?.let { variation ->
            finiteNumberValue("navigation.magneticVariation", variation.toDouble()) // radians
        }?.let { add(it) }

        // Magnetometer accuracy (always sent when heading is enabled, independent of GPS)
        sensorData.magnetometerAccuracy?.let { acc ->
            // 0=unreliable, 1=low, 2=medium, 3=high
            add(numberValue("sensors.magnetometer.accuracy", acc.toDouble()))
        }
    }

    /**
     * Vehicle attitude, or null when neither roll nor pitch is finite.
     *
     * Roll is positive to starboard ("list to starboard" in the spec's wording), pitch
     * positive bow-up — both verified verbatim against the schema. Roll here is
     * instantaneous inclination: steady heel plus wave-driven roll oscillation
     * (frame-conventions.md §5).
     *
     * No `yaw` member, permanently rather than pending M1. The field that used to be here
     * carried a gyro *rate* in a slot consumers read as an *angle* (audit A2), but the
     * deeper reason is that SignalK defines no datum for `attitude.yaw`: with north as
     * datum it merely duplicates the heading paths, and with mean heading as datum it is
     * a yawing oscillation — two incompatible readings the spec never resolves
     * (frame-conventions.md §11). Heading rides on the heading paths, which are
     * unambiguous.
     */
    private fun attitudeValue(sensorData: SensorData): SignalKValue? {
        val rollValue = sensorData.roll?.toDouble()?.takeIf { it.isFinite() }
        val pitchValue = sensorData.pitch?.toDouble()?.takeIf { it.isFinite() }
        if (rollValue == null && pitchValue == null) return null

        val attitude = buildJsonObject {
            rollValue?.let { put("roll", JsonPrimitive(it)) }
            pitchValue?.let { put("pitch", JsonPrimitive(it)) }
        }
        return SignalKValue(path = "navigation.attitude", value = attitude)
    }

    /** Environmental sensors; pressure is conditional on settings. */
    private fun environmentValues(
        sensorData: SensorData,
        sendPressure: Boolean
    ): List<SignalKValue> = buildList {
        if (sendPressure) {
            sensorData.pressure?.let { pressure ->
                add(numberValue("environment.outside.pressure", pressure.toDouble())) // Pa
            }
        }
        sensorData.temperature?.let { temperature ->
            add(numberValue("environment.outside.temperature", temperature.toDouble())) // K
        }
        sensorData.relativeHumidity?.let { humidity ->
            add(numberValue("environment.outside.relativeHumidity", humidity.toDouble())) // ratio
        }
    }

    private fun numberValue(path: String, value: Double): SignalKValue =
        SignalKValue(path = path, value = SignalKValues.number(value))

    private fun finiteNumberValue(path: String, value: Double): SignalKValue? =
        SignalKValues.finiteNumber(value)?.let { SignalKValue(path = path, value = it) }

    /** Wraps [values] in a single update, or no update at all when there is nothing to send. */
    private fun buildMessage(
        source: SignalKSource,
        timestampMs: Long,
        values: List<SignalKValue>
    ): SignalKMessage {
        val vesselContext =
            context?.let { VesselSettings.getSignalKContext(it) } ?: DEFAULT_VESSEL_CONTEXT
        val updates = if (values.isEmpty()) {
            emptyList()
        } else {
            val timestamp = dateFormat.format(java.time.Instant.ofEpochMilli(timestampMs))
            listOf(SignalKUpdate(source = source, timestamp = timestamp, values = values))
        }
        return SignalKMessage(context = vesselContext, updates = updates)
    }

    private fun sendMessage(message: SignalKMessage) {
        val ws = webSocket
        if (ws == null) {
            _connectionStatus.value = false
            Log.e(TAG, "SignalK transmission error: WebSocket connection not established")
            return
        }

        try {
            val json = Json.encodeToString(message)
            if (!ws.send(json)) {
                // OkHttp refuses once the socket is closing or its outgoing queue is full;
                // either way this message is lost and the link is not usable.
                _connectionStatus.value = false
                Log.w(TAG, "SignalK transmission dropped: WebSocket closing or send queue full")
                return
            }

            // Update tracking state
            _lastSentMessage.value = json
            _messagesSent.value = _messagesSent.value + 1
            _lastTransmissionTime.value = System.currentTimeMillis()
        } catch (e: SerializationException) {
            _connectionStatus.value = false
            Log.e(TAG, "SignalK transmission error: message could not be encoded", e)
        }
    }

    private suspend fun initializeWebSocket() {
        // Atomic state transition: DISCONNECTED -> CONNECTING
        // If already CONNECTING or CONNECTED, this returns false and we skip initialization
        val claimed = webSocketState.compareAndSet(
            WebSocketState.DISCONNECTED,
            WebSocketState.CONNECTING
        )
        if (!claimed) {
            Log.d(TAG, "WebSocket initialization skipped - current state: ${webSocketState.get()}")
            return
        }
        val generation = synchronized(connectionLock) { ++connectionGeneration }

        Log.d(TAG, "Starting WebSocket connection...")

        // If we have no token but stored credentials exist, try to login now.
        // This covers the case where the startup auto-login raced with a server restart
        // and the server wasn't ready yet.  We attempt this before opening the WebSocket
        // so the WS upgrade request can carry a valid Authorization header immediately.
        if (authenticationService.getAuthToken() == null &&
            authenticationService.hasStoredCredentials()
        ) {
            Log.d(TAG, "No token available — attempting login before WebSocket connection")
            val token = authenticationService.tryRefreshToken().getOrNull()
            if (token == null) {
                Log.w(TAG, "Login before WebSocket connection failed; connecting without a token")
            }
        }

        withContext(ioDispatcher) {
            try {
                val client = OkHttpClient.Builder()
                    .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
                    .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
                    .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build()
                okHttpClient = client
                webSocket = client.newWebSocket(buildStreamRequest(), ConnectionListener(generation))
            } catch (e: IllegalArgumentException) {
                // Request.Builder.url() rejects a malformed server URL.
                // Transition back to DISCONNECTED on setup error
                webSocketState.set(WebSocketState.DISCONNECTED)
                Log.e(TAG, "WebSocket initialization error: ${e.message}", e)
                throw e
            }
        }
    }

    private fun buildStreamRequest(): Request = Request.Builder()
        .url("$baseUrl/signalk/v1/stream")
        .apply {
            // Add authentication header if we have a token
            authenticationService.getAuthToken()?.let { token ->
                addHeader("Authorization", "Bearer $token")
            }
        }
        .build()

    /**
     * Runs [block] under [connectionLock] if the connection attempt [generation] still owns
     * the shared connection state; otherwise drops [event], which a superseded socket
     * delivered after streaming stopped or reconnected.
     */
    private inline fun ifCurrentConnection(generation: Long, event: String, block: () -> Unit) {
        synchronized(connectionLock) {
            if (generation != connectionGeneration) {
                Log.d(TAG, "Ignoring $event from a superseded WebSocket")
                return
            }
            block()
        }
    }

    /** Listener for connection attempt [generation]; ignored once that attempt is superseded. */
    private inner class ConnectionListener(private val generation: Long) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            ifCurrentConnection(generation, "open") {
                // Transition: CONNECTING -> CONNECTED
                webSocketState.set(WebSocketState.CONNECTED)
                _connectionStatus.value = true
                Log.d(TAG, "WebSocket connected to SignalK server")
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "Received from SignalK: $text")
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closing: $code $reason")
            // Complete the closing handshake. OkHttp does not answer a peer's close
            // frame itself, and onClosed (which reports the peer's code and drives
            // the reconnect below) only fires once both sides have sent theirs.
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            ifCurrentConnection(generation, "close ($code)") {
                // Transition: any state -> DISCONNECTED
                webSocketState.set(WebSocketState.DISCONNECTED)
                _connectionStatus.value = false
                this@SignalKTransmitter.webSocket = null
                Log.d(TAG, "WebSocket closed: $code $reason")

                // Auto-reconnect for unexpected closures (not user-initiated)
                if (code != NORMAL_CLOSURE) {
                    Log.w(
                        TAG,
                        "Unexpected WebSocket closure (code: $code), scheduling reconnection..."
                    )
                    scheduleReconnection(UNEXPECTED_CLOSURE_RECONNECT_DELAY_MS)
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            ifCurrentConnection(generation, "failure (${t.message})") {
                // Transition: any state -> DISCONNECTED
                webSocketState.set(WebSocketState.DISCONNECTED)
                _connectionStatus.value = false
                this@SignalKTransmitter.webSocket = null
                Log.e(TAG, "WebSocket error: ${t.message}", t)

                handleWebSocketFailure(response, generation)
            }
        }
    }

    /**
     * Handle WebSocket failure by checking for auth errors and scheduling reconnection.
     *
     * @param generation the failed connection attempt; its token renewal only reconnects
     *   while that attempt still owns the connection state.
     */
    private fun handleWebSocketFailure(response: Response?, generation: Long) {
        val code = response?.code
        if (code == HttpURLConnection.HTTP_UNAUTHORIZED ||
            code == HttpURLConnection.HTTP_FORBIDDEN
        ) {
            val errorMsg = "Authentication failed ($code): Token may be expired or invalid"
            Log.e(TAG, errorMsg)
            _authenticationError.value = errorMsg

            // Attempt automatic token renewal
            transmitterScope?.launch { renewTokenAndReconnect(generation) }
                ?: Log.w(TAG, "Cannot attempt token renewal - transmitter scope is null")
        } else {
            _authenticationError.value = null
            if (code != null) {
                // Non-authentication HTTP error
                Log.w(TAG, "WebSocket failure (HTTP $code), scheduling reconnection...")
            } else {
                // No response = network error (connection refused, timeout, etc.)
                Log.w(TAG, "Network-related WebSocket failure, scheduling reconnection...")
            }
            scheduleReconnection(FAILURE_RECONNECT_DELAY_MS)
        }
    }

    private suspend fun renewTokenAndReconnect(generation: Long) {
        Log.d(TAG, "Attempting automatic token renewal...")
        // Use NonCancellable to ensure token renewal completes even if streaming stops
        val result = withContext(NonCancellable) {
            authenticationService.tryRefreshToken()
        }
        if (result.isSuccess && result.getOrNull() != null) {
            Log.d(TAG, "Token renewed successfully")
            _authenticationError.value = null
            // Reconnect only if this failure still belongs to the running session:
            // streaming may have stopped, or stopped and restarted, meanwhile.
            ifCurrentConnection(generation, "token renewal") {
                Log.d(TAG, "Scheduling reconnection...")
                scheduleReconnection(TOKEN_RENEWED_RECONNECT_DELAY_MS)
            }
        } else {
            val failureMsg = "Automatic token renewal failed - will retry"
            Log.e(TAG, failureMsg)
            _authenticationError.value = failureMsg
            // Keep retrying as long as we have credentials: the server may
            // still be coming up (auth endpoint and WebSocket together).
            if (authenticationService.hasStoredCredentials()) {
                ifCurrentConnection(generation, "failed token renewal") {
                    Log.d(TAG, "Credentials exist — scheduling reconnect retry in 30 s")
                    scheduleReconnection(TOKEN_RENEWAL_RETRY_DELAY_MS)
                }
            }
        }
    }
}
