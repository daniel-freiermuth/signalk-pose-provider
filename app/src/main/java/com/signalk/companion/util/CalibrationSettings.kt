package com.signalk.companion.util

import android.content.Context
import androidx.core.content.edit

/** Device-to-vehicle calibration angles (ZXZ proper Euler decomposition). */
object CalibrationSettings {
    private const val KEY_CALIBRATION_ALPHA = "calibration_alpha_deg"
    private const val KEY_CALIBRATION_BETA = "calibration_beta_deg"
    private const val KEY_CALIBRATION_GAMMA = "calibration_gamma_deg"
    private const val DEFAULT_CALIBRATION_ANGLE = 0.0f
    private val SIGNED_HALF_TURN_DEG = -180f..180f
    private val TILT_RANGE_DEG = 0f..180f

    fun getCalibrationAlphaDeg(context: Context): Float =
        settingsPreferences(context).getFloat(KEY_CALIBRATION_ALPHA, DEFAULT_CALIBRATION_ANGLE)

    fun getCalibrationBetaDeg(context: Context): Float =
        settingsPreferences(context).getFloat(KEY_CALIBRATION_BETA, DEFAULT_CALIBRATION_ANGLE)

    fun getCalibrationGammaDeg(context: Context): Float =
        settingsPreferences(context).getFloat(KEY_CALIBRATION_GAMMA, DEFAULT_CALIBRATION_ANGLE)

    fun setCalibrationAngles(context: Context, alphaDeg: Float, betaDeg: Float, gammaDeg: Float) {
        require(alphaDeg in SIGNED_HALF_TURN_DEG) { "Alpha must be between -180 and 180 degrees" }
        require(betaDeg in TILT_RANGE_DEG) { "Beta must be between 0 and 180 degrees" }
        require(gammaDeg in SIGNED_HALF_TURN_DEG) { "Gamma must be between -180 and 180 degrees" }
        settingsPreferences(context).edit {
            putFloat(KEY_CALIBRATION_ALPHA, alphaDeg)
            putFloat(KEY_CALIBRATION_BETA, betaDeg)
            putFloat(KEY_CALIBRATION_GAMMA, gammaDeg)
        }
    }
}
