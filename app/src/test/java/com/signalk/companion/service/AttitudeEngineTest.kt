package com.signalk.companion.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import com.signalk.companion.replay.RecordingSession
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What [AttitudeEngine.state] holds across start and stop.
 *
 * The UI shows the filter panel whenever `state` is non-null, so a value left behind by a
 * stopped session reads as a live but wrong estimate. These tests drive the real
 * [RawSensorSource] through a mocked [SensorManager] and feed records straight into the
 * listener it registers — the same entry point the sensor thread uses on a device.
 */
class AttitudeEngineTest {

    private lateinit var sensorManager: SensorManager
    private lateinit var engine: AttitudeEngine

    private val accelerometer = sensorOfType(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorOfType(Sensor.TYPE_GYROSCOPE)
    private val magnetometer = sensorOfType(Sensor.TYPE_MAGNETIC_FIELD)

    /** Every listener registered with the sensor manager, newest last — one per session. */
    private val listeners = mutableListOf<SensorEventListener>()

    @BeforeEach
    fun setUp() {
        sensorManager = mock(SensorManager::class.java)
        `when`(sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)).thenReturn(accelerometer)
        `when`(sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)).thenReturn(gyroscope)
        `when`(sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)).thenReturn(magnetometer)
        `when`(
            sensorManager.registerListener(
                any<SensorEventListener>(),
                any<Sensor>(),
                anyInt(),
                anyInt(),
                any<Handler>()
            )
        ).thenAnswer { invocation ->
            val listener = invocation.getArgument<SensorEventListener>(0)
            if (listeners.lastOrNull() !== listener) listeners.add(listener)
            true
        }

        val context = mock(Context::class.java)
        `when`(context.getSystemService(Context.SENSOR_SERVICE)).thenReturn(sensorManager)

        engine = AttitudeEngine(context, RecordingSession(context))
    }

    @Test
    fun `stop clears the published state`() {
        assertTrue(engine.start())
        deliverGyro(listeners.last(), timestampNs = 1_000_000_000L)
        assertNotNull(engine.state.value, "precondition: the running session published a pose")

        engine.stop()

        assertNull(engine.state.value, "a stopped engine must not keep showing a pose as live")
    }

    @Test
    fun `start on a device without the required sensors leaves no previous pose published`() {
        assertTrue(engine.start())
        deliverGyro(listeners.last(), timestampNs = 1_000_000_000L)
        assertNotNull(engine.state.value, "precondition: the running session published a pose")

        `when`(sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)).thenReturn(null)

        assertFalse(engine.start())
        assertFalse(engine.availability!!.isUsable)
        assertNull(engine.state.value, "a start that could not run must not show the old pose")
    }

    @Test
    fun `restart hides the previous session until the new session emits`() {
        assertTrue(engine.start())
        val oldListener = listeners.last()
        deliverGyro(oldListener, timestampNs = 1_000_000_000L)
        assertNotNull(engine.state.value, "precondition: the first session published a pose")

        assertTrue(engine.start())
        val newListener = listeners.last()

        assertNull(engine.state.value, "nothing from the old session before the new one emits")

        // A late callback on the previous session's listener is not the new session's data.
        deliverGyro(oldListener, timestampNs = 2_000_000_000L)
        assertNull(engine.state.value, "the old session must not publish into the new one")

        deliverGyro(newListener, timestampNs = 3_000_000_000L)
        assertEquals(3_000_000_000L, engine.state.value?.timestampNs)
    }

    @Test
    fun `an emit already in flight when stop runs does not republish afterwards`() {
        assertTrue(engine.start())
        val listener = listeners.last()

        // The event's sensor type is the first thing the callback reads, so blocking there
        // parks a sensor-thread callback mid-flight while stop() runs on this thread.
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockingGyroscope = mock(Sensor::class.java)
        `when`(blockingGyroscope.type).thenAnswer {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "test never released the callback" }
            Sensor.TYPE_GYROSCOPE
        }

        val sensorThread = Thread {
            listener.onSensorChanged(sensorEvent(blockingGyroscope, 1_000_000_000L, 0f, 0f, 0f))
        }
        sensorThread.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS), "callback never started")

        engine.stop()
        release.countDown()
        sensorThread.join(TimeUnit.SECONDS.toMillis(5))
        assertFalse(sensorThread.isAlive, "callback never finished")

        assertNull(engine.state.value, "an emit racing stop() must not repopulate state")
    }

    // ------------------------------------------------------------------ helpers

    private fun deliverGyro(listener: SensorEventListener, timestampNs: Long) {
        listener.onSensorChanged(sensorEvent(gyroscope, timestampNs, 0f, 0f, 0f))
    }

    private fun sensorOfType(type: Int): Sensor =
        mock(Sensor::class.java).also { `when`(it.type).thenReturn(type) }

    /**
     * A [SensorEvent] as the framework would deliver it. Its only constructor is
     * package-private and `values` is final, so both go through reflection.
     */
    private fun sensorEvent(sensor: Sensor, timestampNs: Long, vararg values: Float): SensorEvent {
        val constructor = SensorEvent::class.java.getDeclaredConstructor()
        constructor.isAccessible = true
        val event = constructor.newInstance()
        SensorEvent::class.java.getField("values").apply { isAccessible = true }.set(event, values)
        event.sensor = sensor
        event.timestamp = timestampNs
        return event
    }
}
