package com.signalk.companion.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.signalk.companion.replay.RecordingSession
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * Drives [AttitudeEngine] through a mocked [SensorManager]: the registered listener is
 * captured and fed events directly on the test thread, standing in for the sensor thread.
 */
class AttitudeEngineTest {

    private val sensorManager = mock(SensorManager::class.java)
    private val context = mock(Context::class.java)
    private val accelerometer = sensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
    private val magnetometer = sensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)

    private lateinit var engine: AttitudeEngine

    @BeforeEach
    fun setUp() {
        `when`(context.getSystemService(Context.SENSOR_SERVICE)).thenReturn(sensorManager)
        `when`(sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)).thenReturn(accelerometer)
        `when`(sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)).thenReturn(gyroscope)
        `when`(sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED))
            .thenReturn(magnetometer)
        `when`(
            sensorManager.registerListener(
                org.mockito.ArgumentMatchers.any(SensorEventListener::class.java),
                org.mockito.ArgumentMatchers.any(Sensor::class.java),
                anyInt(),
                anyInt(),
                org.mockito.ArgumentMatchers.any()
            )
        ).thenReturn(true)

        engine = AttitudeEngine(context, RecordingSession(context))
    }

    @Test
    fun `stop clears the published pose so the UI does not show a frozen estimate`() {
        assertTrue(engine.start())
        feedUntilEmitted(captureListener())
        assertNotNull(engine.state.value, "precondition: the engine emitted a pose while running")

        engine.stop()

        assertNull(engine.state.value, "a stopped engine must not keep publishing its last pose")
    }

    @Test
    fun `restart does not show the previous session's pose before the first new estimate`() {
        assertTrue(engine.start())
        feedUntilEmitted(captureListener())
        assertNotNull(engine.state.value, "precondition: the engine emitted a pose while running")

        assertTrue(engine.start())

        assertNull(engine.state.value, "a fresh session starts with no pose until it emits one")
    }

    private fun captureListener(): SensorEventListener {
        val captor = ArgumentCaptor.forClass(SensorEventListener::class.java)
        verify(sensorManager, atLeastOnce()).registerListener(
            captor.capture(),
            eq(gyroscope),
            anyInt(),
            anyInt(),
            org.mockito.ArgumentMatchers.any()
        )
        return captor.value
    }

    /** Level, stationary device: gravity up, field north and down, no rotation. */
    private fun feedUntilEmitted(listener: SensorEventListener) {
        var t = 1_000_000_000L
        repeat(EMIT_SAMPLES) {
            listener.onSensorChanged(event(accelerometer, t, 0f, 0f, 9.81f))
            listener.onSensorChanged(event(magnetometer, t, 0f, 20f, -40f, 0f, 0f, 0f))
            listener.onSensorChanged(event(gyroscope, t, 0f, 0f, 0f, 0f, 0f, 0f))
            t += STEP_NS
        }
    }

    private fun sensor(type: Int): Sensor = mock(Sensor::class.java).also {
        `when`(it.type).thenReturn(type)
    }

    private fun event(sensor: Sensor, timestampNs: Long, vararg values: Float): SensorEvent {
        val event = mock(SensorEvent::class.java)
        setField(event, "sensor", sensor)
        setField(event, "timestamp", timestampNs)
        setField(event, "values", values)
        return event
    }

    private fun setField(target: Any, name: String, value: Any) {
        val field = SensorEvent::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    private companion object {
        const val STEP_NS = 5_000_000L // 200 Hz
        const val EMIT_SAMPLES = 100 // 0.5 s: several emit intervals
    }
}
