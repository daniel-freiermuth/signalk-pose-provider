package com.signalk.companion.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import com.signalk.companion.util.DeviceCalibration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.math.PI

/**
 * Behaviour of [SensorService] between raw Android sensor events and the emitted
 * [SensorService.sensorData] flow: rate limiting, unit conversion, and how the mount
 * calibration is applied. The angle math itself is covered by FrameConventionsTest.
 */
class SensorServiceTest {

    private var nowMs = START_MS
    private val context = mock(Context::class.java)
    private val sensorManager = mock(SensorManager::class.java)
    private val sensors = mutableMapOf<Int, Sensor>()
    private lateinit var service: SensorService

    @BeforeEach
    fun setUp() {
        `when`(context.getSystemService(Context.SENSOR_SERVICE)).thenReturn(sensorManager)
        for (type in SENSOR_TYPES) {
            val sensor = mock(Sensor::class.java)
            `when`(sensor.type).thenReturn(type)
            `when`(sensorManager.getDefaultSensor(type)).thenReturn(sensor)
            sensors[type] = sensor
        }
        // LocationService starts with no fix and touches no Android API until started.
        service = SensorService(context, LocationService()) { nowMs }
    }

    // ------------------------------------------------------------------ rate limiting

    @Test
    fun `first reading after start is emitted immediately`() {
        service.startSensorUpdates(INTERVAL_MS)

        service.onSensorChanged(event(Sensor.TYPE_PRESSURE, 1013.25f))

        val emitted = service.sensorData.value
        assertEquals(101_325f, emitted.pressure!!, PRESSURE_TOL_PA)
        assertEquals(START_MS, emitted.timestamp)
    }

    @Test
    fun `readings inside the interval are held back and emitted together at the next slot`() {
        service.startSensorUpdates(INTERVAL_MS)
        service.onSensorChanged(event(Sensor.TYPE_PRESSURE, 1013.25f))

        nowMs += 10
        service.onSensorChanged(event(Sensor.TYPE_AMBIENT_TEMPERATURE, 20f))
        assertNull(service.sensorData.value.temperature, "reading inside the interval must not be emitted yet")

        nowMs = START_MS + INTERVAL_MS
        service.onSensorChanged(event(Sensor.TYPE_RELATIVE_HUMIDITY, 60f))

        // The held-back temperature must survive into this emission: a slow sensor that only
        // ever reports between slots would otherwise never be published.
        val emitted = service.sensorData.value
        assertEquals(101_325f, emitted.pressure!!, PRESSURE_TOL_PA)
        assertEquals(293.15f, emitted.temperature!!, TOL)
        assertEquals(0.6f, emitted.relativeHumidity!!, TOL)
        assertEquals(nowMs, emitted.timestamp)
    }

    @Test
    fun `emission is suppressed one millisecond before the interval and allowed exactly at it`() {
        service.startSensorUpdates(INTERVAL_MS)
        service.onSensorChanged(event(Sensor.TYPE_PRESSURE, 1013.25f))

        nowMs = START_MS + INTERVAL_MS - 1
        service.onSensorChanged(event(Sensor.TYPE_AMBIENT_TEMPERATURE, 20f))
        assertEquals(START_MS, service.sensorData.value.timestamp)

        nowMs = START_MS + INTERVAL_MS
        service.onSensorChanged(event(Sensor.TYPE_AMBIENT_TEMPERATURE, 21f))
        assertEquals(START_MS + INTERVAL_MS, service.sensorData.value.timestamp)
        assertEquals(294.15f, service.sensorData.value.temperature!!, TOL)
    }

    @Test
    fun `changing the rate emits the next reading immediately`() {
        service.startSensorUpdates(INTERVAL_MS)
        service.onSensorChanged(event(Sensor.TYPE_PRESSURE, 1013.25f))

        nowMs += 10
        service.updateSensorRate(5_000)
        service.onSensorChanged(event(Sensor.TYPE_AMBIENT_TEMPERATURE, 20f))

        assertEquals(nowMs, service.sensorData.value.timestamp)
        assertEquals(293.15f, service.sensorData.value.temperature!!, TOL)
    }

    // ---------------------------------------------------------------- unit conversion

    @Test
    fun `pressure is converted from hPa to Pa`() {
        service.startSensorUpdates(INTERVAL_MS)
        service.onSensorChanged(event(Sensor.TYPE_PRESSURE, 1013.25f))
        assertEquals(101_325f, service.sensorData.value.pressure!!, PRESSURE_TOL_PA)
    }

    @Test
    fun `temperature is converted from Celsius to Kelvin`() {
        service.startSensorUpdates(INTERVAL_MS)
        service.onSensorChanged(event(Sensor.TYPE_AMBIENT_TEMPERATURE, -5f))
        assertEquals(268.15f, service.sensorData.value.temperature!!, TOL)
    }

    @Test
    fun `relative humidity is converted from percent to ratio`() {
        service.startSensorUpdates(INTERVAL_MS)
        service.onSensorChanged(event(Sensor.TYPE_RELATIVE_HUMIDITY, 60f))
        assertEquals(0.6f, service.sensorData.value.relativeHumidity!!, TOL)
    }

    // ---------------------------------------------------------------- sampling delay

    @Test
    fun `an interval of 100 ms samples at game rate`() {
        service.startSensorUpdates(100)
        verify(sensorManager).registerListener(
            eq(service),
            eq(sensors.getValue(Sensor.TYPE_PRESSURE)),
            eq(SensorManager.SENSOR_DELAY_GAME)
        )
    }

    @Test
    fun `an interval of 101 ms samples at normal rate`() {
        service.startSensorUpdates(101)
        verify(sensorManager).registerListener(
            eq(service),
            eq(sensors.getValue(Sensor.TYPE_PRESSURE)),
            eq(SensorManager.SENSOR_DELAY_NORMAL)
        )
    }

    // ------------------------------------------------------------ calibration and attitude

    @Test
    fun `calibration angles change the emitted rate of turn`() {
        service.startSensorUpdates(INTERVAL_MS)
        // Phone on a bulkhead (β = 90°): a starboard turn shows up on the device Y axis.
        val gyroStarboardTurn = floatArrayOf(0f, 0.1f, 0f)

        service.onSensorChanged(event(Sensor.TYPE_GYROSCOPE, *gyroStarboardTurn))
        assertEquals(0f, service.sensorData.value.rateOfTurn!!, TOL)

        service.setCalibrationAngles(0f, 90f, 0f)
        nowMs += INTERVAL_MS
        service.onSensorChanged(event(Sensor.TYPE_GYROSCOPE, *gyroStarboardTurn))
        assertEquals(0.1f, service.sensorData.value.rateOfTurn!!, TOL)
    }

    @Test
    fun `heading pitch and roll are taken from the device attitude through the calibration`() {
        val deviceHeadingEast = DeviceCalibration.buildFlatHeadingMatrix(90f)
        withDeviceAttitude(deviceHeadingEast) {
            service.startSensorUpdates(INTERVAL_MS)

            service.onSensorChanged(event(Sensor.TYPE_MAGNETIC_FIELD, 0f, 0f, 0f))
            val uncalibrated = service.sensorData.value
            assertEquals(HALF_PI, uncalibrated.compassHeading!!, TOL)
            assertEquals(0f, uncalibrated.pitch!!, TOL)
            assertEquals(0f, uncalibrated.roll!!, TOL)

            // Mount yawed 90° to port of the phone: the boat points north while the phone
            // points east.
            service.setCalibrationAngles(0f, 0f, 90f)
            nowMs += INTERVAL_MS
            service.onSensorChanged(event(Sensor.TYPE_MAGNETIC_FIELD, 0f, 0f, 0f))
            assertEquals(0f, service.sensorData.value.compassHeading!!, TOL)
        }
    }

    @Test
    fun `approximate true heading is withheld without a position fix`() {
        withDeviceAttitude(DeviceCalibration.buildFlatHeadingMatrix(90f)) {
            service.startSensorUpdates(INTERVAL_MS)

            service.onSensorChanged(event(Sensor.TYPE_MAGNETIC_FIELD, 0f, 0f, 0f))

            val emitted = service.sensorData.value
            assertNotNull(emitted.compassHeading)
            assertNull(emitted.magneticVariation, "variation is unknown without a position")
            // Falling back to the magnetic heading here would label a magnetic value "true".
            assertNull(emitted.approxTrueHeading)
        }
    }

    // ------------------------------------------------------------------------ helpers

    /** Makes [SensorManager.getRotationMatrix] report [rotationW_D] for the duration of [block]. */
    private fun withDeviceAttitude(rotationW_D: FloatArray, block: () -> Unit) {
        mockStatic(SensorManager::class.java).use { mocked ->
            mocked.`when`<Boolean> {
                SensorManager.getRotationMatrix(any(), isNull(), any(), any())
            }.thenAnswer { invocation ->
                rotationW_D.copyInto(invocation.getArgument<FloatArray>(0))
                true
            }
            block()
        }
    }

    /** SensorEvent has no public constructor; mock it and fill its public fields directly. */
    private fun event(type: Int, vararg values: Float): SensorEvent {
        val event = mock(SensorEvent::class.java)
        event.sensor = sensors.getValue(type)
        SensorEvent::class.java.getField("values").apply { isAccessible = true }.set(event, values)
        return event
    }

    private companion object {
        const val START_MS = 1_000_000L
        const val INTERVAL_MS = 1_000
        const val TOL = 1e-4f
        const val PRESSURE_TOL_PA = 0.5f
        val HALF_PI = (PI / 2).toFloat()

        val SENSOR_TYPES = listOf(
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_PRESSURE,
            Sensor.TYPE_AMBIENT_TEMPERATURE,
            Sensor.TYPE_RELATIVE_HUMIDITY
        )
    }
}
