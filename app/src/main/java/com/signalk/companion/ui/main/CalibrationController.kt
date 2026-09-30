package com.signalk.companion.ui.main

import android.content.Context
import android.util.Log
import com.signalk.companion.service.LocationService
import com.signalk.companion.service.SensorService
import com.signalk.companion.util.CalibrationSettings
import com.signalk.companion.util.DeviceCalibration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The main screen's mount-calibration actions: sample the device attitude, derive the ZXZ
 * mount angles `R_D_V` (frame-conventions.md), then persist them and apply them everywhere
 * they are consumed.
 *
 * Owned by [MainViewModel], which supplies its scope and UI state; the new angles are
 * reported through [MainUiState] and forwarded to a running streaming service through
 * [onAnglesApplied].
 */
class CalibrationController(
    private val applicationContext: Context,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<MainUiState>,
    private val sensorService: SensorService,
    private val locationService: LocationService,
    private val onAnglesApplied: (alphaDeg: Float, betaDeg: Float, gammaDeg: Float) -> Unit
) {

    private companion object {
        const val TAG = "CalibrationController"

        /** How long a calibration waits for the sensors to produce a rotation matrix. */
        const val SENSOR_DATA_TIMEOUT_MS = 2000L
        const val SENSOR_DATA_POLL_MS = 50L
    }

    fun updateCalibrationAngles(alphaDeg: Float, betaDeg: Float, gammaDeg: Float) {
        Log.d(TAG, "updateCalibrationAngles: α=$alphaDeg, β=$betaDeg, γ=$gammaDeg")
        uiState.update {
            it.copy(
                calibrationAlphaDeg = alphaDeg,
                calibrationBetaDeg = betaDeg,
                calibrationGammaDeg = gammaDeg
            )
        }
        CalibrationSettings.setCalibrationAngles(applicationContext, alphaDeg, betaDeg, gammaDeg)
        sensorService.setCalibrationAngles(alphaDeg, betaDeg, gammaDeg)
        onAnglesApplied(alphaDeg, betaDeg, gammaDeg)
    }

    /**
     * Full calibration: use current sensor reading + GPS heading to compute all three angles.
     * If no GPS heading is available, only calibrates pitch and roll.
     */
    fun calibrateAll() {
        Log.d(
            TAG,
            "calibrateAll: starting, sensorsActive=${sensorService.isSensorUpdatesActive()}, " +
                "hasRotation=${sensorService.hasValidRotationMatrix()}"
        )
        launchWithSensorData("calibrateAll") {
            val R_W_D = sensorService.getCurrentRotationMatrix()
            Log.d(TAG, "calibrateAll: R_W_D=[${R_W_D.joinToString()}]")
            val location = locationService.locationUpdates.value
            val bearing = location?.bearing
            val speed = location?.speed
            Log.d(TAG, "calibrateAll: bearing=$bearing, speed=$speed")

            if (bearing != null && isCalibrationSpeed(speed)) {
                val R_W_V = DeviceCalibration.buildFlatHeadingMatrix(bearing)
                val R_D_V = DeviceCalibration.computeCalibration(R_W_D, R_W_V)
                val (alpha, beta, gamma) = DeviceCalibration.decomposeZXZ(R_D_V)
                Log.d(TAG, "calibrateAll: GPS path → α=$alpha, β=$beta, γ=$gamma")
                updateCalibrationAngles(alpha, beta, gamma)
            } else {
                Log.d(TAG, "calibrateAll: no GPS heading, falling back to twist/tilt only")
                calibrateTiltNow()
            }
        }
    }

    /**
     * Calibrate only the azimuth (horizontal heading) using GPS heading.
     * Preserves existing tilt calibration, even if the vehicle is tilted by waves/wind.
     */
    fun calibrateAzimuth() {
        launchWithSensorData("calibrateAzimuth") {
            val R_W_D = sensorService.getCurrentRotationMatrix()
            val location = locationService.locationUpdates.value
            val bearing = location?.bearing
            val speed = location?.speed
            Log.d(TAG, "calibrateAzimuth: bearing=$bearing, speed=$speed")
            if (bearing != null && isCalibrationSpeed(speed)) {
                val current = uiState.value
                val (newGamma, _) = DeviceCalibration.calibrateAzimuth(
                    R_W_D,
                    current.calibrationAlphaDeg,
                    current.calibrationBetaDeg,
                    current.calibrationGammaDeg,
                    bearing
                )
                Log.d(TAG, "calibrateAzimuth: γ=$newGamma (was γ=${current.calibrationGammaDeg})")
                updateCalibrationAngles(
                    current.calibrationAlphaDeg,
                    current.calibrationBetaDeg,
                    newGamma
                )
            } else {
                reportAzimuthNeedsSpeed()
            }
        }
    }

    /**
     * Calibrate twist and tilt only. Assumes the vehicle is currently flat.
     * Keeps existing heading offset (γ) unchanged.
     */
    fun calibrateTilt() {
        launchWithSensorData("calibrateTilt") { calibrateTiltNow() }
    }

    private fun calibrateTiltNow() {
        val R_W_D = sensorService.getCurrentRotationMatrix()
        val existingGamma = uiState.value.calibrationGammaDeg
        Log.d(TAG, "calibrateTiltNow: R_W_D=[${R_W_D.joinToString()}]")
        val (newAlpha, newBeta, _) = DeviceCalibration.calibrateTilt(R_W_D, existingGamma)
        Log.d(TAG, "calibrateTiltNow: α=$newAlpha, β=$newBeta, γ=$existingGamma")
        updateCalibrationAngles(newAlpha, newBeta, existingGamma)
    }

    private fun isCalibrationSpeed(speedMps: Float?): Boolean =
        speedMps != null && speedMps >= DeviceCalibration.MIN_CALIBRATION_SPEED_MPS

    private fun reportAzimuthNeedsSpeed() {
        Log.w(
            TAG,
            "calibrateAzimuth: below ${DeviceCalibration.MIN_CALIBRATION_SPEED_KN} kn " +
                "or no GPS course"
        )
        // Fail loudly. The heading reference is GPS *course*, which only equals
        // heading at speed; silently doing nothing leaves the user believing a
        // calibration happened. See DeviceCalibration.MIN_CALIBRATION_SPEED_KN.
        uiState.update {
            it.copy(
                error = "Needs at least ${DeviceCalibration.MIN_CALIBRATION_SPEED_KN} kn " +
                    "steady speed — heading is taken from GPS course, which only " +
                    "matches heading when moving well."
            )
        }
    }

    /**
     * Run [calibration] once the sensors have produced a rotation matrix, or report that
     * they did not within [SENSOR_DATA_TIMEOUT_MS].
     */
    private fun launchWithSensorData(caller: String, calibration: () -> Unit) {
        scope.launch {
            if (ensureSensorData()) {
                calibration()
            } else {
                Log.w(TAG, "$caller: sensor data not available within timeout")
                uiState.update { it.copy(error = "Calibration failed: no sensor data available") }
            }
        }
    }

    /**
     * Waits for sensors to produce a valid rotation matrix.
     * Sensors should already be running (started in [MainViewModel.onAppForeground]).
     */
    private suspend fun ensureSensorData(): Boolean {
        return withTimeoutOrNull(SENSOR_DATA_TIMEOUT_MS) {
            while (!sensorService.hasValidRotationMatrix()) {
                delay(SENSOR_DATA_POLL_MS)
            }
            true
        } != null
    }
}
