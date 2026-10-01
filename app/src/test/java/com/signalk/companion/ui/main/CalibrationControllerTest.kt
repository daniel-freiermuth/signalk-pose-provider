package com.signalk.companion.ui.main

import android.content.Context
import android.content.SharedPreferences
import com.signalk.companion.data.model.LocationData
import com.signalk.companion.service.LocationService
import com.signalk.companion.service.SensorService
import com.signalk.companion.util.DeviceCalibration
import com.signalk.companion.util.Matrix3
import com.signalk.companion.util.assertAngleEquals
import com.signalk.companion.util.verifyCalled
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.anyFloat
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

/**
 * Calibration routing: which angles each action derives, which it preserves, and how it
 * fails. The geometry is a real mount on a real heading, so each branch is checked by
 * whether it recovers that mount rather than by echoing [DeviceCalibration]'s output.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalibrationControllerTest {

    private companion object {
        // A plausible bulkhead mount: near-vertical, slightly twisted, yawed off the keel.
        const val MOUNT_ALPHA_DEG = 10f
        const val MOUNT_BETA_DEG = 80f
        const val MOUNT_GAMMA_DEG = 25f

        const val BOAT_HEADING_DEG = 40f

        const val SENSOR_DATA_TIMEOUT_MS = 2000L
    }

    private lateinit var context: Context
    private lateinit var sensorService: SensorService
    private lateinit var locationService: LocationService
    private lateinit var locationUpdates: MutableStateFlow<LocationData?>
    private lateinit var uiState: MutableStateFlow<MainUiState>
    private val appliedAngles = mutableListOf<Triple<Float, Float, Float>>()

    /** Device attitude of a phone in the mount above, on a flat boat at [BOAT_HEADING_DEG]. */
    private val mountedDeviceAttitude: FloatArray = Matrix3.multiply(
        DeviceCalibration.buildFlatHeadingMatrix(BOAT_HEADING_DEG),
        Matrix3.transpose(
            DeviceCalibration.composeZXZ(MOUNT_ALPHA_DEG, MOUNT_BETA_DEG, MOUNT_GAMMA_DEG)
        )
    )

    @BeforeEach
    fun setup() {
        context = mock(Context::class.java)
        val preferences = mock(SharedPreferences::class.java)
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences)
        `when`(preferences.edit()).thenReturn(mock(SharedPreferences.Editor::class.java))

        sensorService = mock(SensorService::class.java)
        `when`(sensorService.hasValidRotationMatrix()).thenReturn(true)
        `when`(sensorService.getCurrentRotationMatrix()).thenReturn(mountedDeviceAttitude)

        locationService = mock(LocationService::class.java)
        locationUpdates = MutableStateFlow(null)
        `when`(locationService.locationUpdates).thenReturn(locationUpdates)

        uiState = MutableStateFlow(MainUiState())
        appliedAngles.clear()
    }

    private fun TestScope.controller() = CalibrationController(
        context,
        this,
        uiState,
        sensorService,
        locationService
    ) { alpha, beta, gamma -> appliedAngles += Triple(alpha, beta, gamma) }

    private fun fix(bearing: Float?, speedMps: Float?) = LocationData(
        latitude = 60.0,
        longitude = 25.0,
        accuracy = 3f,
        bearing = bearing,
        speed = speedMps,
        altitude = null,
        timestamp = 0L
    )

    private fun setExistingCalibration(alpha: Float, beta: Float, gamma: Float) {
        uiState.value = uiState.value.copy(
            calibrationAlphaDeg = alpha,
            calibrationBetaDeg = beta,
            calibrationGammaDeg = gamma
        )
    }

    private fun assertCalibration(alpha: Float, beta: Float, gamma: Float) {
        val state = uiState.value
        assertAngleEquals(alpha, state.calibrationAlphaDeg)
        assertAngleEquals(beta, state.calibrationBetaDeg)
        assertAngleEquals(gamma, state.calibrationGammaDeg)
        // The same angles must reach the local sensors and a running service.
        verify(sensorService).setCalibrationAngles(
            state.calibrationAlphaDeg,
            state.calibrationBetaDeg,
            state.calibrationGammaDeg
        )
        assertEquals(
            listOf(
                Triple(
                    state.calibrationAlphaDeg,
                    state.calibrationBetaDeg,
                    state.calibrationGammaDeg
                )
            ),
            appliedAngles
        )
    }

    @Test
    fun `calibrateAll with a GPS course at calibration speed recovers all three mount angles`() =
        runTest {
            setExistingCalibration(0f, 0f, -5f)
            locationUpdates.value = fix(
                bearing = BOAT_HEADING_DEG,
                speedMps = DeviceCalibration.MIN_CALIBRATION_SPEED_MPS
            )

            controller().calibrateAll()
            advanceUntilIdle()

            assertCalibration(MOUNT_ALPHA_DEG, MOUNT_BETA_DEG, MOUNT_GAMMA_DEG)
            assertNull(uiState.value.error)
        }

    @Test
    fun `calibrateAll just below calibration speed falls back to tilt only and keeps gamma`() =
        runTest {
            setExistingCalibration(0f, 0f, -5f)
            locationUpdates.value = fix(
                bearing = BOAT_HEADING_DEG,
                speedMps = DeviceCalibration.MIN_CALIBRATION_SPEED_MPS - 0.01f
            )

            controller().calibrateAll()
            advanceUntilIdle()

            assertCalibration(MOUNT_ALPHA_DEG, MOUNT_BETA_DEG, -5f)
            // Unlike calibrateAzimuth, the fallback is silent: tilt is a valid calibration.
            assertNull(uiState.value.error)
        }

    @Test
    fun `calibrateAll without a GPS course falls back to tilt only`() = runTest {
        setExistingCalibration(0f, 0f, -5f)
        locationUpdates.value = fix(bearing = null, speedMps = 5f)

        controller().calibrateAll()
        advanceUntilIdle()

        assertCalibration(MOUNT_ALPHA_DEG, MOUNT_BETA_DEG, -5f)
    }

    @Test
    fun `calibrateAll without any fix falls back to tilt only`() = runTest {
        setExistingCalibration(0f, 0f, -5f)

        controller().calibrateAll()
        advanceUntilIdle()

        assertCalibration(MOUNT_ALPHA_DEG, MOUNT_BETA_DEG, -5f)
    }

    @Test
    fun `calibrateAzimuth corrects only gamma and preserves the existing alpha and beta`() =
        runTest {
            setExistingCalibration(MOUNT_ALPHA_DEG, MOUNT_BETA_DEG, -5f)
            locationUpdates.value = fix(bearing = BOAT_HEADING_DEG, speedMps = 3f)

            controller().calibrateAzimuth()
            advanceUntilIdle()

            assertCalibration(MOUNT_ALPHA_DEG, MOUNT_BETA_DEG, MOUNT_GAMMA_DEG)
            // α and β are passed through untouched, not recomputed from the sensors.
            assertEquals(MOUNT_ALPHA_DEG, uiState.value.calibrationAlphaDeg)
            assertEquals(MOUNT_BETA_DEG, uiState.value.calibrationBetaDeg)
        }

    @Test
    fun `calibrateAzimuth below calibration speed reports an error and changes nothing`() =
        runTest {
            setExistingCalibration(1f, 2f, 3f)
            locationUpdates.value = fix(
                bearing = BOAT_HEADING_DEG,
                speedMps = DeviceCalibration.MIN_CALIBRATION_SPEED_MPS - 0.01f
            )

            controller().calibrateAzimuth()
            advanceUntilIdle()

            assertNotNull(uiState.value.error)
            assertEquals(1f, uiState.value.calibrationAlphaDeg)
            assertEquals(2f, uiState.value.calibrationBetaDeg)
            assertEquals(3f, uiState.value.calibrationGammaDeg)
            verify(sensorService, never()).setCalibrationAngles(anyFloat(), anyFloat(), anyFloat())
            assertEquals(emptyList<Triple<Float, Float, Float>>(), appliedAngles)
        }

    @Test
    fun `calibrateTilt recovers alpha and beta and keeps gamma even with a GPS course`() =
        runTest {
            setExistingCalibration(0f, 0f, -5f)
            locationUpdates.value = fix(bearing = BOAT_HEADING_DEG, speedMps = 5f)

            controller().calibrateTilt()
            advanceUntilIdle()

            assertCalibration(MOUNT_ALPHA_DEG, MOUNT_BETA_DEG, -5f)
        }

    @Test
    fun `calibration waits for the first rotation matrix instead of using a stale one`() =
        runTest {
            var sensorsReady = false
            `when`(sensorService.hasValidRotationMatrix()).thenAnswer { sensorsReady }
            setExistingCalibration(0f, 0f, -5f)

            controller().calibrateTilt()
            advanceTimeBy(SENSOR_DATA_TIMEOUT_MS / 2)
            runCurrent()
            verifyCalled(sensorService, never()) { getCurrentRotationMatrix() }

            sensorsReady = true
            advanceUntilIdle()

            assertCalibration(MOUNT_ALPHA_DEG, MOUNT_BETA_DEG, -5f)
            assertNull(uiState.value.error)
        }

    @Test
    fun `calibration reports an error after two seconds without sensor data`() = runTest {
        `when`(sensorService.hasValidRotationMatrix()).thenReturn(false)
        setExistingCalibration(1f, 2f, 3f)
        locationUpdates.value = fix(bearing = BOAT_HEADING_DEG, speedMps = 5f)

        controller().calibrateAll()
        advanceTimeBy(SENSOR_DATA_TIMEOUT_MS - 1)
        runCurrent()
        assertNull(uiState.value.error, "Gave up before the timeout")

        advanceTimeBy(1)
        runCurrent()

        assertEquals("Calibration failed: no sensor data available", uiState.value.error)
        assertEquals(1f, uiState.value.calibrationAlphaDeg)
        assertEquals(2f, uiState.value.calibrationBetaDeg)
        assertEquals(3f, uiState.value.calibrationGammaDeg)
        verify(sensorService, never()).setCalibrationAngles(anyFloat(), anyFloat(), anyFloat())
        assertEquals(emptyList<Triple<Float, Float, Float>>(), appliedAngles)
    }
}
