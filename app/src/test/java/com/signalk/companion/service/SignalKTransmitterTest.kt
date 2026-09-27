package com.signalk.companion.service

import com.signalk.companion.data.model.LocationData
import com.signalk.companion.data.model.SensorData
import com.signalk.companion.util.UrlParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

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
    private lateinit var reconnectDelay: ControlledReconnectDelay

    /** Real threads: the transmitter blocks on loopback sockets while the test acts. */
    private val io = Executors.newCachedThreadPool().asCoroutineDispatcher()

    @BeforeEach
    fun setUp() {
        server = FakeSignalKServer()
        authenticationService = AuthenticationService(io)
        reconnectDelay = ControlledReconnectDelay()
        transmitter = newTransmitter()
    }

    private fun newTransmitter() =
        SignalKTransmitter(authenticationService, io, reconnectDelay::await).apply {
            configure(requireNotNull(UrlParser.parseUrl(server.url)))
        }

    @AfterEach
    fun tearDown() {
        transmitter.stopStreaming()
        server.close()
        io.close()
    }

    // ── Connection state machine ──────────────────────────────────────────────

    @Test
    fun `startStreaming completes the upgrade and sends no Authorization without a token`() {
        startStreaming()

        transmitter.sendLocation(locationData())

        assertNotNull(server.awaitMessage(), "server should receive the message over the open socket")
        assertEquals(1, server.streamRequests.size)
        assertEquals("/signalk/v1/stream", server.streamRequests[0].path)
        assertNull(
            server.streamRequests[0].authorization,
            "no token stored, so the upgrade must not carry an Authorization header"
        )
    }

    @Test
    fun `startStreaming while already connected does not open a second connection`() {
        startStreaming()
        transmitter.sendLocation(locationData())
        assertNotNull(server.awaitMessage(), "first connection should be open")

        // The CAS guard only admits DISCONNECTED -> CONNECTING, so a second start while
        // CONNECTED must be a no-op rather than a second socket.
        startStreaming()
        transmitter.sendLocation(locationData())

        assertNotNull(server.awaitMessage(), "the original connection should still carry traffic")
        assertEquals(1, server.streamRequests.size, "a connected transmitter must not re-upgrade")
    }

    @Test
    fun `startStreaming returns with a websocket that can carry a message straight away`() {
        // Repeated on fresh transmitters: the defect this guards against was a second,
        // concurrently launched connection attempt that won the CAS guard only some of the
        // time, leaving startStreaming() to return before the WebSocket was assigned.
        repeat(START_CYCLES) { cycle ->
            transmitter.stopStreaming()
            transmitter = newTransmitter()

            startStreaming()
            transmitter.sendLocation(locationData())

            assertNotNull(
                server.awaitMessage(),
                "cycle $cycle: a send straight after startStreaming() must not be dropped"
            )
            assertEquals(cycle + 1, server.streamRequests.size, "cycle $cycle: exactly one upgrade per start")
        }
    }

    @Test
    fun `connectionStatus reports connected only once the server accepts the upgrade`() {
        val gate = CountDownLatch(1)
        server.streamGate = gate

        startStreaming()
        awaitCondition("the upgrade request to reach the server") { server.streamRequests.isNotEmpty() }

        assertFalse(
            transmitter.connectionStatus.value,
            "the server has not answered the upgrade, so the transmitter is not connected yet"
        )

        gate.countDown()
        transmitter.connectionStatus.awaitValue { it } // fails on timeout
    }

    @Test
    fun `stopStreaming resets the session and streaming can start again`() {
        startStreaming()
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
        startStreaming()
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
        startStreaming()

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
        startStreaming()

        // Vessel at anchor pointing due south: every zero here is a real measurement.
        transmitter.sendLocation(
            locationData().copy(
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
        startStreaming()

        transmitter.sendLocation(
            locationData().copy(accuracy = 0.0f, speed = null, bearing = null, altitude = null)
        )

        val values = valuesOf(requireNotNull(server.awaitMessage()))
        assertEquals(setOf("navigation.position"), values.keys)
    }

    @Test
    fun `sendLocationData with sendLocation false transmits nothing`() {
        startStreaming()

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
        startStreaming()

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
        startStreaming()

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
        startStreaming()

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
        startStreaming()

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
    fun `401 upgrade failure renews the token and reconnects with it one second later`() {
        login()
        server.loginToken = "token-2"
        server.scriptStreamStatuses(401, FakeSignalKServer.STATUS_SWITCHING_PROTOCOLS)

        startStreaming()

        val reconnect = reconnectDelay.nextRequest()
        assertEquals(1_000L, reconnect.delayMs)
        // The renewal cleared the error before scheduling that reconnect.
        assertNull(transmitter.authenticationError.value)
        assertEquals(1, server.streamRequests.size, "nothing reconnects before the delay elapses")

        reconnect.elapse()
        awaitCondition("the scheduled reconnect") { server.streamRequests.size >= 2 }
        assertEquals("Bearer token-1", server.streamRequests[0].authorization)
        assertEquals(
            "Bearer token-2",
            server.streamRequests[1].authorization,
            "the reconnect must carry the renewed token"
        )

        transmitter.connectionStatus.awaitValue { it } // the reconnect's upgrade has completed
        transmitter.sendLocation(locationData())
        assertNotNull(server.awaitMessage(), "the reconnected socket should be usable")
    }

    @Test
    fun `403 upgrade failure with a failing renewal reports it and retries after 30 seconds`() {
        login()
        server.loginStatus = 401 // every renewal from here on fails
        server.scriptStreamStatuses(403)

        startStreaming()

        transmitter.authenticationError.awaitValue { it == RENEWAL_FAILED_MESSAGE }
        // Credentials are stored, so a retry is scheduled — 30 s out, not the 1 s used
        // after a successful renewal.
        assertEquals(30_000L, reconnectDelay.nextRequest().delayMs)
        assertEquals(2, server.loginRequests.size, "the initial login plus exactly one renewal")
        assertEquals(1, server.streamRequests.size)
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

        // Clearing the error is the step right before the reconnect decision.
        transmitter.authenticationError.awaitValue { it == null }
        reconnectDelay.assertNoRequest(QUIET_PERIOD_MS)
        assertEquals(1, server.streamRequests.size, "a stopped transmitter must not reconnect")
        assertFalse(transmitter.connectionStatus.value)
    }

    @Test
    fun `token renewal completing after a restart does not schedule a reconnect for the new session`() {
        login()
        val gate = CountDownLatch(1)
        server.loginGate = gate
        server.loginToken = "token-2"
        server.scriptStreamStatuses(401)

        startStreaming()
        awaitCondition("token renewal to reach the login endpoint") { server.loginRequests.size == 2 }

        // Restart while that renewal is in flight; the new session connects on its own.
        transmitter.stopStreaming()
        startStreaming()
        transmitter.connectionStatus.awaitValue { it }
        gate.countDown()

        // Clearing the error is the step right before the reconnect decision.
        transmitter.authenticationError.awaitValue { it == null }
        reconnectDelay.assertNoRequest(QUIET_PERIOD_MS)
        assertEquals(2, server.streamRequests.size)
        assertTrue(transmitter.connectionStatus.value)
    }

    @Test
    fun `non-authentication http failure retries after 10 seconds without renewing the token`() {
        login()
        server.scriptStreamStatuses(500)

        startStreaming()

        val reconnect = reconnectDelay.nextRequest()
        assertEquals(10_000L, reconnect.delayMs)
        assertNull(transmitter.authenticationError.value, "HTTP 500 is not an authentication problem")
        assertEquals(1, server.loginRequests.size, "only the initial login; no renewal attempt")

        reconnect.elapse()
        transmitter.connectionStatus.awaitValue { it }
        assertEquals(2, server.streamRequests.size, "the elapsed delay reconnects")
    }

    @Test
    fun `transport failure without a response retries after 10 seconds without an auth error`() {
        login()
        server.defaultStreamStatus = FakeSignalKServer.STATUS_CLOSE_WITHOUT_RESPONSE

        startStreaming()

        assertEquals(10_000L, reconnectDelay.nextRequest().delayMs)
        assertNull(
            transmitter.authenticationError.value,
            "a dropped connection is not an authentication problem"
        )
        assertEquals(1, server.loginRequests.size, "only the initial login; no renewal attempt")
    }

    // ── Server-initiated close ────────────────────────────────────────────────

    @Test
    fun `server closing the stream abnormally reconnects after 5 seconds`() {
        startStreaming()
        transmitter.connectionStatus.awaitValue { it }

        server.closeOpenStreams(1011, "server restarting")

        val reconnect = reconnectDelay.nextRequest()
        assertEquals(5_000L, reconnect.delayMs)
        assertFalse(transmitter.connectionStatus.value)
        assertEquals(1, server.streamRequests.size, "nothing reconnects before the delay elapses")

        reconnect.elapse()
        awaitCondition("the scheduled reconnect") { server.streamRequests.size == 2 }
        transmitter.connectionStatus.awaitValue { it }
        transmitter.sendLocation(locationData())
        assertNotNull(server.awaitMessage(), "the reconnected socket should be usable")
    }

    // ── Superseded connections ────────────────────────────────────────────────

    @Test
    fun `the previous session's socket failing after a restart does not disturb the new session`() {
        // The server hangs up on the old socket without answering its close, so that socket's
        // listener reports a transport failure while the new session is running.
        restartThenFinishPreviousClose(serverReply = null)

        reconnectDelay.assertNoRequest(QUIET_PERIOD_MS)
        assertNewSessionIntact()
    }

    @Test
    fun `the previous session's socket closing after a restart does not disturb the new session`() {
        restartThenFinishPreviousClose(serverReply = 1000)

        reconnectDelay.assertNoRequest(QUIET_PERIOD_MS) // also lets the old socket's onClosed land
        assertNewSessionIntact()
    }

    @Test
    fun `the previous session's socket opening after a restart does not mark the new session connected`() {
        val upgradeGate = CountDownLatch(1)
        server.streamGate = upgradeGate
        startStreaming()
        awaitCondition("the first upgrade request to reach the server") { server.streamRequests.size == 1 }

        // Stop while that upgrade is unanswered, then restart against a refusing server.
        transmitter.stopStreaming()
        server.streamGate = null
        server.scriptStreamStatuses(500)
        startStreaming()
        val retry = reconnectDelay.nextRequest()
        assertEquals(10_000L, retry.delayMs)

        // The server now accepts the stale upgrade. Holding the close that stopStreaming()
        // queued on that socket keeps it open for the rest of the test.
        val closeGate = CountDownLatch(1)
        server.clientCloseGate = closeGate
        upgradeGate.countDown()
        awaitCondition("the stale socket to open") { server.clientClosesReceived == 1 }

        assertFalse(
            transmitter.connectionStatus.value,
            "only the stopped session's socket opened; the new session is still disconnected"
        )
        retry.elapse()
        awaitCondition("the new session's scheduled reconnect") { server.streamRequests.size == 3 }
        transmitter.connectionStatus.awaitValue { it }
        transmitter.sendLocation(locationData())
        assertNotNull(server.awaitMessage(), "the new session's reconnected socket should be usable")
        closeGate.countDown()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Stops a connected session and starts the next one while the previous socket's closing
     * handshake is still pending at the server, then lets the server finish it — answering
     * with [serverReply], or hanging up if null. That is when the previous socket's listener
     * delivers its last callback: after the new session is already connected.
     */
    private fun restartThenFinishPreviousClose(serverReply: Int?) {
        startStreaming()
        transmitter.connectionStatus.awaitValue { it }

        val closeGate = CountDownLatch(1)
        server.clientCloseGate = closeGate
        server.clientCloseReply = serverReply
        transmitter.stopStreaming()
        awaitCondition("the previous socket's close to reach the server") {
            server.clientClosesReceived == 1
        }
        server.clientCloseGate = null

        startStreaming()
        transmitter.connectionStatus.awaitValue { it }

        closeGate.countDown()
    }

    private fun assertNewSessionIntact() {
        assertTrue(transmitter.connectionStatus.value, "the new session's socket is still open")
        transmitter.sendLocation(locationData())
        assertNotNull(server.awaitMessage(), "the new session's socket must still carry traffic")
        assertEquals(2, server.streamRequests.size, "the new session must not have reconnected")
    }

    private fun startStreaming() = runBlocking { transmitter.startStreaming() }

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
    ) {
        // Completes as soon as a value satisfies predicate, including the current one.
        runBlocking { withTimeout(timeoutMs) { takeWhile { !predicate(it) }.collect() } }
    }

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

    private fun locationData() = LocationData(
        latitude = 59.3293,
        longitude = 18.0686,
        accuracy = 5.0f,
        bearing = 180.0f,
        speed = 5.0f,
        altitude = 10.0,
        timestamp = 1_723_027_496_789L
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

    /**
     * Stands in for the transmitter's reconnect delay: records every delay a failure path
     * requests and holds it until the test lets it elapse. Held delays are cancelled with
     * the transmitter scope, exactly like `delay`.
     */
    private class ControlledReconnectDelay {
        class Pending(val delayMs: Long) {
            val elapsed = CompletableDeferred<Unit>()
            fun elapse() {
                check(elapsed.complete(Unit)) { "this delay has already elapsed" }
            }
        }

        private val requests = LinkedBlockingQueue<Pending>()

        suspend fun await(delayMs: Long) {
            val pending = Pending(delayMs)
            requests.put(pending)
            pending.elapsed.await()
        }

        fun nextRequest(timeoutMs: Long = FakeSignalKServer.DEFAULT_TIMEOUT_MS): Pending =
            requests.poll(timeoutMs, TimeUnit.MILLISECONDS)
                ?: fail("Timed out waiting for a reconnection to be scheduled")

        fun assertNoRequest(quietMs: Long) {
            assertNull(
                requests.poll(quietMs, TimeUnit.MILLISECONDS)?.delayMs,
                "no reconnection should have been scheduled"
            )
        }
    }

    private companion object {
        /**
         * How long to watch for a reconnection that must not be scheduled, measured from an
         * edge that immediately precedes the scheduling decision.
         */
        const val QUIET_PERIOD_MS = 500L

        /** Enough start cycles that the former start-up race would surface reliably. */
        const val START_CYCLES = 20

        const val RENEWAL_FAILED_MESSAGE = "Automatic token renewal failed - will retry"
    }
}
