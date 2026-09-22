package com.signalk.companion.service

import com.signalk.companion.data.model.LocationData
import com.signalk.companion.data.model.SensorData
import com.signalk.companion.util.UrlParser
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch

/**
 * Drives [SignalKTransmitter] against a loopback [FakeSignalKServer] over its real OkHttp
 * stack, so the connection state machine, the authentication-failure recovery and the
 * message builders are exercised exactly as a SignalK server would see them.
 *
 * Nothing here mocks the class under test: what the server receives on the wire, plus the
 * transmitter's public StateFlows, are the only observations made.
 */
class SignalKTransmitterTest {

    private lateinit var server: FakeSignalKServer
    private lateinit var authenticationService: AuthenticationService
    private lateinit var transmitter: SignalKTransmitter

    @BeforeEach
    fun setUp() {
        server = FakeSignalKServer()
        authenticationService = AuthenticationService()
        transmitter = SignalKTransmitter(authenticationService)
        transmitter.configure(requireNotNull(UrlParser.parseUrl(server.url)))
    }

    @AfterEach
    fun tearDown() {
        transmitter.stopStreaming()
        server.close()
    }

    // ── Connection state machine ──────────────────────────────────────────────

    @Test
    fun `startStreaming completes the upgrade and sends no Authorization without a token`() {
        connect()

        transmitter.sendLocation(locationData())

        assertNotNull(server.awaitMessage(), "server should receive the message over the open socket")
        assertEquals(1, server.streamRequests.size)
        assertEquals("/signalk/v1/stream", server.streamRequests[0].path)
        assertNull(
            server.streamRequests[0].authorization,
            "no token stored, so the upgrade must not carry an Authorization header"
        )
        assertTrue(transmitter.connectionStatus.value)
    }

    @Test
    fun `startStreaming while already connected does not open a second connection`() {
        connect()
        transmitter.sendLocation(locationData())
        assertNotNull(server.awaitMessage(), "first connection should be open")

        // The CAS guard only admits DISCONNECTED -> CONNECTING, so a second start while
        // CONNECTED must be a no-op rather than a second socket.
        // The existing socket stays assigned across the no-op, so no readiness wait here.
        startStreaming()
        transmitter.sendLocation(locationData())

        assertNotNull(server.awaitMessage(), "the original connection should still carry traffic")
        assertEquals(1, server.streamRequests.size, "a connected transmitter must not re-upgrade")
    }

    @Test
    fun `stopStreaming resets the session and streaming can start again`() {
        connect()
        transmitter.sendLocation(locationData())
        assertNotNull(server.awaitMessage())
        assertEquals(1, transmitter.messagesSent.value)

        transmitter.stopStreaming()

        assertFalse(transmitter.connectionStatus.value)
        assertEquals(0, transmitter.messagesSent.value)
        assertNull(transmitter.lastSentMessage.value)
        assertNull(transmitter.lastTransmissionTime.value)
        assertNull(transmitter.currentResolvedIp.value)

        // The state machine is back at DISCONNECTED, so a fresh connection is possible.
        connect()
        transmitter.sendLocation(locationData())

        assertNotNull(server.awaitMessage(), "restarted session should deliver messages")
        assertEquals(2, server.streamRequests.size)
    }

    @Test
    fun `sending without a connection is swallowed and leaves the counters untouched`() {
        // No startStreaming, so the WebSocket is null and sendMessage throws internally.
        transmitter.sendLocation(locationData())

        assertEquals(0, transmitter.messagesSent.value, "a failed send must not count as sent")
        assertNull(transmitter.lastSentMessage.value)
        assertNull(transmitter.lastTransmissionTime.value)
        assertFalse(transmitter.connectionStatus.value)
        assertEquals(0, server.streamRequests.size)
    }

    @Test
    fun `successful sends advance the transmission counters`() {
        connect()

        transmitter.sendLocation(locationData())
        val firstMessage = server.awaitMessage()

        assertNotNull(firstMessage)
        assertEquals(1, transmitter.messagesSent.value)
        assertEquals(firstMessage, transmitter.lastSentMessage.value)
        assertNotNull(transmitter.lastTransmissionTime.value)

        transmitter.sendLocation(locationData())
        assertNotNull(server.awaitMessage())

        assertEquals(2, transmitter.messagesSent.value)
    }

    // ── Location message contract ─────────────────────────────────────────────

    @Test
    fun `location message keeps zero speed bearing and altitude and converts bearing to radians`() {
        connect()

        // Vessel at anchor pointing due south: every zero here is a real measurement.
        transmitter.sendLocation(
            locationData(
                accuracy = 5.0f,
                speed = 0.0f,
                bearing = 180.0f,
                altitude = 0.0,
                speedAccuracy = 0.5f,
                bearingAccuracy = 2.0f,
                verticalAccuracy = 3.0f
            )
        )

        val values = valuesOf(requireNotNull(server.awaitMessage()))
        assertEquals(0.0, values.getValue("navigation.speedOverGround").jsonPrimitive.double)
        assertEquals(Math.PI, values.getValue("navigation.courseOverGroundTrue").jsonPrimitive.double, 1e-9)
        assertEquals(0.0, values.getValue("navigation.gnss.altitude").jsonPrimitive.double)

        // The accuracy > 0 gate, plus the per-measurement accuracy sub-paths.
        assertEquals(5.0, values.getValue("navigation.position.accuracy").jsonPrimitive.double, 1e-6)
        assertEquals(0.5, values.getValue("navigation.speedOverGround.accuracy").jsonPrimitive.double, 1e-6)
        assertEquals(
            Math.toRadians(2.0),
            values.getValue("navigation.courseOverGroundTrue.accuracy").jsonPrimitive.double,
            1e-9
        )
        assertEquals(3.0, values.getValue("navigation.gnss.altitude.accuracy").jsonPrimitive.double, 1e-6)

        val position = values.getValue("navigation.position").jsonObject
        assertEquals(59.3293, position.getValue("latitude").jsonPrimitive.double, 1e-9)
        assertEquals(18.0686, position.getValue("longitude").jsonPrimitive.double, 1e-9)
    }

    @Test
    fun `location message omits unmeasured values and zero accuracy`() {
        connect()

        transmitter.sendLocation(
            locationData(accuracy = 0.0f, speed = null, bearing = null, altitude = null)
        )

        val values = valuesOf(requireNotNull(server.awaitMessage()))
        assertEquals(setOf("navigation.position"), values.keys)
    }

    @Test
    fun `sendLocationData with sendLocation false transmits nothing`() {
        connect()

        runBlocking { transmitter.sendLocationData(locationData(), sendLocation = false) }
        transmitter.sendSensor(SensorData(pressure = 101_325.0f)) // ordering marker

        val values = valuesOf(requireNotNull(server.awaitMessage()))
        assertEquals(
            setOf("environment.outside.pressure"),
            values.keys,
            "the suppressed location message must not have been sent ahead of the sensor one"
        )
        assertEquals(1, transmitter.messagesSent.value)
    }

    // ── Sensor message contract ───────────────────────────────────────────────

    @Test
    fun `sensor message with sendHeading false suppresses heading variation and magnetometer accuracy`() {
        connect()

        transmitter.sendSensor(fullSensorData(), sendHeading = false)

        val values = valuesOf(requireNotNull(server.awaitMessage()))
        assertFalse(values.containsKey("navigation.headingCompass"))
        assertFalse(values.containsKey("navigation.magneticVariation"))
        assertFalse(values.containsKey("sensors.magnetometer.accuracy"))
        // Everything not gated on heading still goes out.
        assertTrue(values.containsKey("navigation.attitude"))
        assertTrue(values.containsKey("navigation.rateOfTurn"))
        assertTrue(values.containsKey("environment.outside.pressure"))
    }

    @Test
    fun `sensor message with sendPressure false suppresses pressure only`() {
        connect()

        transmitter.sendSensor(fullSensorData(), sendPressure = false)

        val values = valuesOf(requireNotNull(server.awaitMessage()))
        assertFalse(values.containsKey("environment.outside.pressure"))
        // The readings are Floats, so compare against their exact widened values rather
        // than against decimal literals a Float cannot represent.
        assertEquals(1.57f.toDouble(), values.getValue("navigation.headingCompass").jsonPrimitive.double)
        assertEquals(3.0, values.getValue("sensors.magnetometer.accuracy").jsonPrimitive.double)
        assertEquals(293.15f.toDouble(), values.getValue("environment.outside.temperature").jsonPrimitive.double)
        assertEquals(0.6f.toDouble(), values.getValue("environment.outside.relativeHumidity").jsonPrimitive.double)
    }

    @Test
    fun `sensor message with no readings is sent with no updates`() {
        connect()

        transmitter.sendSensor(SensorData())

        val message = requireNotNull(server.awaitMessage())
        assertEquals(JsonArray(emptyList()), updatesOf(message))
        assertEquals(
            "vessels.self",
            Json.parseToJsonElement(message).jsonObject.getValue("context").jsonPrimitive.content
        )
        assertEquals(1, transmitter.messagesSent.value)
    }

    @Test
    fun `non-finite sensor readings are dropped rather than breaking the message`() {
        connect()

        transmitter.sendSensor(
            SensorData(
                compassHeading = Float.NaN,
                magneticVariation = Float.POSITIVE_INFINITY,
                roll = Float.NaN,
                pitch = Float.NaN,
                rateOfTurn = Float.NaN,
                pressure = 101_325.0f
            )
        )

        val values = valuesOf(requireNotNull(server.awaitMessage()))
        assertEquals(setOf("environment.outside.pressure"), values.keys)
    }

    // ── Authentication-failure recovery ───────────────────────────────────────

    @Test
    fun `401 upgrade failure renews the token and reconnects with it`() {
        login()
        server.loginToken = "token-2"
        server.scriptStreamStatuses(401, FakeSignalKServer.STATUS_SWITCHING_PROTOCOLS)

        startStreaming()

        awaitCondition("the reconnect that follows a successful token renewal") {
            server.streamRequests.size >= 2
        }
        // The renewal cleared the error before scheduling that reconnect.
        assertNull(transmitter.authenticationError.value)
        assertEquals("Bearer token-1", server.streamRequests[0].authorization)
        assertEquals(
            "Bearer token-2",
            server.streamRequests[1].authorization,
            "the reconnect must carry the renewed token"
        )

        awaitWebSocket()
        transmitter.sendLocation(locationData())
        assertNotNull(server.awaitMessage(), "the reconnected socket should be usable")
    }

    @Test
    fun `403 upgrade failure with a failing renewal reports it and defers the retry`() {
        login()
        server.loginStatus = 401 // every renewal from here on fails
        server.scriptStreamStatuses(403)

        startStreaming()

        assertEquals(
            RENEWAL_FAILED_MESSAGE,
            transmitter.authenticationError.awaitValue { it == RENEWAL_FAILED_MESSAGE }
        )

        // Credentials are stored, so a retry is scheduled — but 30 s out, not the 1 s
        // used after a successful renewal.
        Thread.sleep(RECONNECT_QUIET_MS)
        assertEquals(1, server.streamRequests.size, "the retry must not fire within a second")
        assertEquals(2, server.loginRequests.size, "the initial login plus exactly one renewal")
    }

    @Test
    fun `token renewal completing after streaming stopped does not reconnect`() {
        login()
        val gate = CountDownLatch(1)
        server.loginGate = gate
        server.loginToken = "token-2"
        server.scriptStreamStatuses(401)

        startStreaming()
        awaitCondition("token renewal to reach the login endpoint") { server.loginRequests.size == 2 }

        // Streaming stops while the renewal is in flight. The renewal is NonCancellable,
        // so it still completes — and must then decline to reconnect.
        transmitter.stopStreaming()
        gate.countDown()

        Thread.sleep(RECONNECT_QUIET_MS)
        assertEquals(1, server.streamRequests.size, "a stopped transmitter must not reconnect")
        assertNull(transmitter.authenticationError.value, "the successful renewal still cleared the error")
        assertFalse(transmitter.connectionStatus.value)
    }

    @Test
    fun `non-authentication http failure neither renews the token nor raises an auth error`() {
        login()
        server.defaultStreamStatus = 500

        startStreaming()
        awaitCondition("the upgrade attempt to be refused") { server.streamRequests.isNotEmpty() }
        Thread.sleep(RECONNECT_QUIET_MS)

        assertNull(transmitter.authenticationError.value, "HTTP 500 is not an authentication problem")
        assertEquals(1, server.loginRequests.size, "only the initial login; no renewal attempt")
        assertEquals(1, server.streamRequests.size, "reconnection is deferred by 10 s")
    }

    @Test
    fun `transport failure without a response does not raise an authentication error`() {
        login()
        server.defaultStreamStatus = FakeSignalKServer.STATUS_CLOSE_WITHOUT_RESPONSE

        startStreaming()
        awaitCondition("the upgrade attempt to be dropped") { server.streamRequests.isNotEmpty() }
        Thread.sleep(RECONNECT_QUIET_MS)

        assertNull(
            transmitter.authenticationError.value,
            "a dropped connection is not an authentication problem"
        )
        assertEquals(1, server.loginRequests.size, "only the initial login; no renewal attempt")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun startStreaming() = runBlocking { transmitter.startStreaming() }

    /** Starts streaming and waits until the transmitter owns a WebSocket it can send on. */
    private fun connect() {
        startStreaming()
        awaitWebSocket()
    }

    /**
     * Waits until the transmitter holds a live WebSocket reference.
     *
     * `startStreaming()` returning is not enough: its initial DNS resolution sees a
     * disconnected transmitter and launches its own `initializeWebSocket` on the
     * transmitter scope, racing the one `startStreaming` performs inline. Whichever wins
     * the CAS guard is the one that assigns the WebSocket, so the other can return first
     * and a send issued right then would be dropped.
     *
     * `refreshDns()` is the cheapest public probe of that reference: it no-ops while the
     * WebSocket is null, and re-resolves once it is set — which replaces the "(initial)"
     * resolution note with an "(unchanged)" one. That note change is the readiness edge,
     * so this may only be called while the initial note is still in place.
     */
    private fun awaitWebSocket() {
        val noteBeforeProbe = transmitter.lastDnsRefresh.value
        assertTrue(
            noteBeforeProbe == null || noteBeforeProbe.endsWith("(initial)"),
            "awaitWebSocket() detects a change of the DNS note, so the note must still be " +
                "the initial resolution, not: $noteBeforeProbe"
        )
        awaitCondition("the transmitter to hold an open websocket") {
            runBlocking { transmitter.refreshDns() }
            transmitter.lastDnsRefresh.value != noteBeforeProbe
        }
    }

    private fun login() = runBlocking {
        val result = authenticationService.login(server.url, "user", "password")
        assertTrue(result.isSuccess, "fake server login should succeed: $result")
        assertEquals("token-1", authenticationService.getAuthToken())
    }

    private fun SignalKTransmitter.sendLocation(locationData: LocationData) =
        runBlocking { sendLocationData(locationData) }

    private fun SignalKTransmitter.sendSensor(
        sensorData: SensorData,
        sendHeading: Boolean = true,
        sendPressure: Boolean = true
    ) = runBlocking { sendSensorData(sensorData, sendHeading, sendPressure) }

    private fun <T> StateFlow<T>.awaitValue(
        timeoutMs: Long = FakeSignalKServer.DEFAULT_TIMEOUT_MS,
        predicate: (T) -> Boolean
    ): T = runBlocking { withTimeout(timeoutMs) { first(predicate) } }

    private fun awaitCondition(
        description: String,
        timeoutMs: Long = FakeSignalKServer.DEFAULT_TIMEOUT_MS,
        condition: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        fail<Unit>("Timed out waiting for $description")
    }

    private fun locationData(
        accuracy: Float = 5.0f,
        bearing: Float? = 180.0f,
        speed: Float? = 5.0f,
        altitude: Double? = 10.0,
        speedAccuracy: Float? = null,
        bearingAccuracy: Float? = null,
        verticalAccuracy: Float? = null
    ) = LocationData(
        latitude = 59.3293,
        longitude = 18.0686,
        accuracy = accuracy,
        bearing = bearing,
        speed = speed,
        altitude = altitude,
        timestamp = 1_723_027_496_789L,
        speedAccuracy = speedAccuracy,
        bearingAccuracy = bearingAccuracy,
        verticalAccuracy = verticalAccuracy
    )

    private fun fullSensorData() = SensorData(
        compassHeading = 1.57f,
        magneticVariation = 0.1f,
        magnetometerAccuracy = 3,
        roll = 0.05f,
        pitch = -0.02f,
        rateOfTurn = 0.01f,
        pressure = 101_325.0f,
        temperature = 293.15f,
        relativeHumidity = 0.6f
    )

    private fun updatesOf(message: String): JsonArray =
        Json.parseToJsonElement(message).jsonObject.getValue("updates").jsonArray

    /** Flattens a SignalK delta into `path -> value`, which is what a server consumes. */
    private fun valuesOf(message: String): Map<String, JsonElement> =
        updatesOf(message)
            .flatMap { it.jsonObject.getValue("values").jsonArray }
            .associate { value ->
                value.jsonObject.getValue("path").jsonPrimitive.content to value.jsonObject.getValue("value")
            }

    private companion object {
        /**
         * Longer than the 1 s reconnect used after a successful token renewal, shorter than
         * the 10 s and 30 s deferred retries — so "no reconnect happened" is meaningful.
         */
        const val RECONNECT_QUIET_MS = 1_500L

        const val RENEWAL_FAILED_MESSAGE = "Automatic token renewal failed - will retry"
    }
}
