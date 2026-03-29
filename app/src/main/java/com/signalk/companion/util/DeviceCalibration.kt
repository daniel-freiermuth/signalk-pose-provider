package com.signalk.companion.util

import kotlin.math.*

/**
 * Pure math for device-to-vehicle calibration using 3x3 rotation matrices.
 *
 * All matrices are 9-element FloatArrays in row-major order, matching
 * Android's [android.hardware.SensorManager.getRotationMatrix] format.
 *
 * Convention:
 * - R_W_D: device attitude in world frame (from Android sensors)
 * - R_D_V: device-to-vehicle calibration matrix (stored, user-editable)
 * - R_W_V = R_W_D * R_D_V: vehicle attitude in world frame (output)
 *
 * ZYX Euler decomposition: R = Rz(rz) * Ry(ry) * Rx(rx)
 * - rz: rotation around Z (vertical axis) — device horizontal orientation
 * - ry: rotation around Y (forward axis)  — mounting twist
 * - rx: rotation around X (right axis)    — mounting tilt fore/aft
 */
object DeviceCalibration {

    val IDENTITY_3X3 = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    )

    /**
     * Compose a rotation matrix from ZYX Euler angles (in degrees).
     * R = Rz(rz) * Ry(ry) * Rx(rx)
     */
    fun composeZYX(rzDeg: Float, ryDeg: Float, rxDeg: Float): FloatArray {
        val rz = Math.toRadians(rzDeg.toDouble())
        val ry = Math.toRadians(ryDeg.toDouble())
        val rx = Math.toRadians(rxDeg.toDouble())

        val cz = cos(rz).toFloat()
        val sz = sin(rz).toFloat()
        val cy = cos(ry).toFloat()
        val sy = sin(ry).toFloat()
        val cx = cos(rx).toFloat()
        val sx = sin(rx).toFloat()

        // R = Rz * Ry * Rx, expanded (row-major)
        return floatArrayOf(
            cz * cy,                   cz * sy * sx - sz * cx,    cz * sy * cx + sz * sx,
            sz * cy,                   sz * sy * sx + cz * cx,    sz * sy * cx - cz * sx,
            -sy,                       cy * sx,                   cy * cx
        )
    }

    /**
     * Decompose a 3x3 rotation matrix into ZYX Euler angles (in degrees).
     * Returns Triple(rz, ry, rx).
     *
     * At gimbal lock (ry = ±90°), the decomposition is degenerate:
     * only (rz - rx) or (rz + rx) is determined. We set rx = 0 and
     * absorb the combined rotation into rz, which always produces a
     * matrix that recomposes correctly.
     */
    fun decomposeZYX(R: FloatArray): Triple<Float, Float, Float> {
        // Row-major: R[row*3 + col]
        val r20 = R[6]  // -sin(ry)

        val ry: Float
        val rz: Float
        val rx: Float

        if (abs(r20) < 0.99999f) {
            ry = asin(-r20)
            rz = atan2(R[3], R[0])   // atan2(sz*cy, cz*cy)
            rx = atan2(R[7], R[8])   // atan2(cy*sx, cy*cx)
        } else {
            // Gimbal lock: ry ≈ ±90°
            ry = if (r20 < 0) (PI / 2).toFloat() else (-PI / 2).toFloat()
            // At +90°: R[1] = cz*sx - sz*cx = -sin(rz-rx), R[4] = sz*sx + cz*cx = cos(rz-rx)
            // At -90°: R[1] = -(cz*sx + sz*cx) = -sin(rz+rx), R[4] = cz*cx - sz*sx = cos(rz+rx)
            rx = 0f
            rz = atan2(-R[1], R[4])
        }

        return Triple(
            Math.toDegrees(rz.toDouble()).toFloat(),
            Math.toDegrees(ry.toDouble()).toFloat(),
            Math.toDegrees(rx.toDouble()).toFloat()
        )
    }

    /**
     * Multiply two 3x3 matrices (row-major). Returns A * B.
     */
    fun multiply3x3(A: FloatArray, B: FloatArray): FloatArray {
        val result = FloatArray(9)
        for (row in 0..2) {
            for (col in 0..2) {
                var sum = 0f
                for (k in 0..2) {
                    sum += A[row * 3 + k] * B[k * 3 + col]
                }
                result[row * 3 + col] = sum
            }
        }
        return result
    }

    /**
     * Transpose a 3x3 matrix (row-major). For rotation matrices, transpose == inverse.
     */
    fun transpose3x3(R: FloatArray): FloatArray {
        return floatArrayOf(
            R[0], R[3], R[6],
            R[1], R[4], R[7],
            R[2], R[5], R[8]
        )
    }

    /**
     * Compute the calibration matrix R_D_V from sensor reading and desired vehicle attitude.
     * R_D_V = R_W_D^T * R_W_V
     */
    fun computeCalibration(R_W_D: FloatArray, R_W_V: FloatArray): FloatArray {
        val R_D_W = transpose3x3(R_W_D)
        return multiply3x3(R_D_W, R_W_V)
    }

    /**
     * Build R_W_V for a flat vehicle at a given heading.
     *
     * @param headingDeg heading in degrees, 0 = North, 90 = East (clockwise from North)
     * @return 3x3 rotation matrix (row-major) where vehicle Y points at heading, X points
     *         90° clockwise (starboard), Z points up.
     *
     * In Android's ENU world frame: East = +X, North = +Y, Up = +Z.
     *
     * For heading θ (from North, CW positive):
     * - Vehicle Y (forward) in world: (sin θ, cos θ, 0)
     * - Vehicle X (right/starboard) in world: (cos θ, -sin θ, 0)
     * - Vehicle Z (up) in world: (0, 0, 1)
     *
     * R_W_V has these as columns (column j = image of basis vector e_j).
     * In row-major storage, column j is at indices [j, j+3, j+6].
     */
    fun buildFlatHeadingMatrix(headingDeg: Float): FloatArray {
        val theta = Math.toRadians(headingDeg.toDouble())
        val ct = cos(theta).toFloat()
        val st = sin(theta).toFloat()

        // Column 0 (vehicle X/starboard in world): (cos θ, -sin θ, 0)
        // Column 1 (vehicle Y/forward in world):   (sin θ,  cos θ, 0)
        // Column 2 (vehicle Z/up in world):         (0,      0,     1)
        //
        // Row-major: R[row*3 + col]
        return floatArrayOf(
             ct,  st, 0f,   // row 0: world-X components of vehicle axes
            -st,  ct, 0f,   // row 1: world-Y components of vehicle axes
             0f,  0f, 1f    // row 2: world-Z components of vehicle axes
        )
    }

    /**
     * Calibrate only pitch (RY) and roll (RX), preserving the vehicle's current heading.
     * Used when standing still (no GPS heading available).
     *
     * Assumes the vehicle is currently flat (no pitch, no roll).
     *
     * Approach: compute the current vehicle heading from R_W_V = R_W_D * existingCalibration,
     * then build a flat target at that heading. The new calibration is guaranteed to produce
     * R_W_V_new = R_W_V_desired (flat), because R_W_D * R_W_D^T = I.
     */
    fun calibratePitchRoll(R_W_D: FloatArray, existingCalibration: FloatArray): FloatArray {
        // Current vehicle attitude with existing calibration
        val R_W_V_current = multiply3x3(R_W_D, existingCalibration)

        // Extract heading using SensorManager.getOrientation convention: azimuth = atan2(R[1], R[4])
        // This is consistent with buildFlatHeadingMatrix's heading convention.
        val headingRad = atan2(R_W_V_current[1].toDouble(), R_W_V_current[4].toDouble())
        val headingDeg = Math.toDegrees(headingRad).toFloat()

        // Build desired vehicle attitude: flat at the current heading
        val R_W_V_desired = buildFlatHeadingMatrix(headingDeg)

        // R_D_V_new = R_W_D^T * R_W_V_desired
        // By construction: R_W_D * R_D_V_new = R_W_V_desired (pitch=0, roll=0)
        return computeCalibration(R_W_D, R_W_V_desired)
    }

    /**
     * Calibrate only the azimuth (horizontal heading), preserving the existing tilt calibration.
     * Used when GPS heading becomes available after a prior pitch/roll calibration.
     *
     * Works correctly regardless of:
     * - Device mounting angle (flat, portrait, landscape, arbitrary)
     * - Current vehicle tilt (waves, wind pressure)
     *
     * Approach: compute the heading correction δ = GPS heading − current heading,
     * then rotate the current vehicle attitude around the world Z axis by δ.
     * This preserves all tilt (both mounting and wave/wind) while correcting only heading.
     *
     * @param R_W_D Current device rotation matrix from sensors
     * @param existingCalibration Current R_D_V calibration matrix (correct tilt, wrong heading)
     * @param gpsHeadingDeg GPS bearing in degrees, 0 = North, clockwise
     */
    fun calibrateAzimuth(
        R_W_D: FloatArray,
        existingCalibration: FloatArray,
        gpsHeadingDeg: Float
    ): FloatArray {
        val R_W_V_current = multiply3x3(R_W_D, existingCalibration)

        // Current heading from vehicle forward direction projected horizontally.
        // In ENU frame: forward = column 1 of R_W_V, heading = atan2(east, north)
        val currentHeadingRad = atan2(
            R_W_V_current[1].toDouble(),
            R_W_V_current[4].toDouble()
        )
        val currentHeadingDeg = Math.toDegrees(currentHeadingRad).toFloat()

        // Rotate vehicle attitude horizontally by the heading correction.
        // buildFlatHeadingMatrix(θ) is a rotation around world Z by θ degrees (GPS convention).
        val correction = buildFlatHeadingMatrix(gpsHeadingDeg - currentHeadingDeg)
        val R_W_V_desired = multiply3x3(correction, R_W_V_current)

        return computeCalibration(R_W_D, R_W_V_desired)
    }
}
