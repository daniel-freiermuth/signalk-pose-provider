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
 * ZXZ proper Euler decomposition (primary): R = Rz(α) * Rx(β) * Rz(γ)
 * - α: first Z rotation  — screen twist (charging port direction)
 * - β: X rotation [0°,180°] — tilt from horizontal (0°=flat, 90°=vertical)
 * - γ: second Z rotation — heading offset (only thing azimuth calibration touches)
 * Gimbal lock at β=0° (flat mounting, uncommon for marine use).
 *
 * ZYX Tait-Bryan decomposition (legacy): R = Rz(rz) * Ry(ry) * Rx(rx)
 * Kept for matrix construction helpers; not used for UI/persistence.
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
     * Compose a rotation matrix from ZXZ proper Euler angles (in degrees).
     * R = Rz(α) * Rx(β) * Rz(γ)
     *
     * - α (alpha): screen twist / charging port direction
     * - β (beta):  tilt from horizontal [0°, 180°]
     * - γ (gamma): heading offset
     */
    fun composeZXZ(alphaDeg: Float, betaDeg: Float, gammaDeg: Float): FloatArray {
        val a = Math.toRadians(alphaDeg.toDouble())
        val b = Math.toRadians(betaDeg.toDouble())
        val g = Math.toRadians(gammaDeg.toDouble())

        val ca = cos(a).toFloat()
        val sa = sin(a).toFloat()
        val cb = cos(b).toFloat()
        val sb = sin(b).toFloat()
        val cg = cos(g).toFloat()
        val sg = sin(g).toFloat()

        // R = Rz(α) · Rx(β) · Rz(γ), expanded (row-major)
        return floatArrayOf(
            ca * cg - sa * cb * sg,   -ca * sg - sa * cb * cg,   sa * sb,
            sa * cg + ca * cb * sg,   -sa * sg + ca * cb * cg,  -ca * sb,
            sb * sg,                   sb * cg,                   cb
        )
    }

    /**
     * Decompose a 3x3 rotation matrix into ZXZ proper Euler angles (in degrees).
     * Returns Triple(α, β, γ) where β ∈ [0°, 180°].
     *
     * At gimbal lock (β ≈ 0° or β ≈ 180°), only α±γ is determined.
     * We set γ = 0 and absorb the combined rotation into α.
     */
    fun decomposeZXZ(R: FloatArray): Triple<Float, Float, Float> {
        val cosB = R[8].coerceIn(-1f, 1f)  // R[8] = cos(β)
        val beta: Float
        val alpha: Float
        val gamma: Float

        if (1f - abs(cosB) > 1e-5f) {
            // Non-degenerate: sin(β) ≠ 0
            beta = acos(cosB)
            alpha = atan2(R[2], -R[5])  // atan2(sα·sβ, cα·sβ) → atan2(sα, cα) since sβ > 0
            gamma = atan2(R[6], R[7])   // atan2(sβ·sγ, sβ·cγ) → atan2(sγ, cγ) since sβ > 0
        } else {
            // Gimbal lock: β ≈ 0° or β ≈ 180°
            // β=0: R = Rz(α+γ), β=180: R = Rz(α−γ) · diag(1,−1,−1)
            // In both cases: set γ=0, α = atan2(R[3], R[0])
            beta = if (cosB > 0) 0f else PI.toFloat()
            gamma = 0f
            alpha = atan2(R[3], R[0])
        }

        return Triple(
            Math.toDegrees(alpha.toDouble()).toFloat(),
            Math.toDegrees(beta.toDouble()).toFloat(),
            Math.toDegrees(gamma.toDouble()).toFloat()
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
     * Calibrate only the tilt (α and β in ZXZ), preserving the heading offset γ.
     * Used when standing still at dock (no GPS heading available).
     *
     * Assumes the vehicle is currently flat (no pitch, no roll).
     *
     * For R_W_V = R_W_D · Rz(α)·Rx(β)·Rz(γ) to be flat, its Z-column must be [0,0,1].
     * Since Rz(γ)·e_z = e_z, flatness depends only on α and β — γ cancels out.
     *
     * The Z-column of R_D_V is [sinα·sinβ, −cosα·sinβ, cosβ] (from ZXZ expansion).
     * We need R_W_D · R_D_V to have Z-column = [0,0,1], i.e. R_D_V's Z-column = R_W_D^T · [0,0,1].
     * This gives: cosβ = R_W_D[8], α = atan2(R_W_D[6], −R_W_D[7]).
     *
     * @param R_W_D Current device rotation matrix from sensors
     * @param gammaDeg Existing heading offset γ to preserve
     * @return New calibration matrix R_D_V = Rz(α_new)·Rx(β_new)·Rz(γ_old)
     */
    fun calibrateTilt(R_W_D: FloatArray, gammaDeg: Float): FloatArray {
        val dz2 = R_W_D[8].coerceIn(-1f, 1f)  // cos(β)
        val betaRad = acos(dz2)
        val alphaRad = if (abs(sin(betaRad)) > 1e-5f) {
            atan2(R_W_D[6].toDouble(), -R_W_D[7].toDouble()).toFloat()
        } else {
            // β ≈ 0 (device already flat) — α is indeterminate, keep 0
            0f
        }

        val alphaDeg = Math.toDegrees(alphaRad.toDouble()).toFloat()
        val betaDeg = Math.toDegrees(betaRad.toDouble()).toFloat()

        return composeZXZ(alphaDeg, betaDeg, gammaDeg)
    }

    /**
     * Calibrate only the azimuth (heading offset γ in ZXZ), preserving α and β.
     * Used when GPS heading becomes available after a prior tilt calibration.
     *
     * Approach: compute the heading error δ from R_W_D * R_D_V_current, then
     * adjust γ by δ. Since heading rotation is a post-multiplication by Rz(δ)
     * in vehicle frame, and γ is the trailing Rz in ZXZ, this is exact:
     * R_D_V_new = Rz(α)·Rx(β)·Rz(γ + δ)
     *
     * @param R_W_D Current device rotation matrix from sensors
     * @param existingCalibration Current R_D_V calibration matrix
     * @param existingGammaDeg Current γ value (heading offset)
     * @param gpsHeadingDeg GPS bearing in degrees, 0 = North, clockwise
     * @return Pair(newGammaDeg, newCalibrationMatrix)
     */
    fun calibrateAzimuth(
        R_W_D: FloatArray,
        existingCalibration: FloatArray,
        existingGammaDeg: Float,
        gpsHeadingDeg: Float
    ): Pair<Float, FloatArray> {
        val R_W_V_current = multiply3x3(R_W_D, existingCalibration)

        // Current heading from vehicle forward direction projected horizontally.
        // In ENU frame: forward = column 1 of R_W_V, heading = atan2(east, north)
        val currentHeadingRad = atan2(
            R_W_V_current[1].toDouble(),
            R_W_V_current[4].toDouble()
        )
        val currentHeadingDeg = Math.toDegrees(currentHeadingRad).toFloat()

        // Heading correction δ: post-multiplying by Rz(δ) changes heading by −δ,
        // so δ = currentHeading − gpsHeading makes heading_new = gpsHeading.
        var delta = currentHeadingDeg - gpsHeadingDeg
        // Normalize to [-180, 180]
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f

        var newGamma = existingGammaDeg + delta
        // Normalize to [-180, 180]
        while (newGamma > 180f) newGamma -= 360f
        while (newGamma < -180f) newGamma += 360f

        // Reconstruct calibration: the caller knows α, β, so they can also
        // recompose, but we return the matrix for convenience.
        val (alpha, beta, _) = decomposeZXZ(existingCalibration)
        val newCalibration = composeZXZ(alpha, beta, newGamma)

        return Pair(newGamma, newCalibration)
    }
}
