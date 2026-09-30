package com.signalk.companion.util

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * ZYX Tait-Bryan angles (legacy): R = Rz(rz) * Ry(ry) * Rx(rx), angles in degrees.
 *
 * Kept as a matrix construction helper; not used for UI or persistence, which use the ZXZ
 * parameterisation of [DeviceCalibration] because ZYX angles are not independent at large
 * tilt. Matrices are row-major, per frame-conventions.md §3.1.
 */
object TaitBryanZyx {

    /**
     * Above this `|sin(ry)|` the pitch is treated as ±90° (gimbal lock), where rz and rx
     * are no longer separable.
     */
    private const val GIMBAL_LOCK_SIN_RY = 0.99999f

    /**
     * Compose a rotation matrix from ZYX Euler angles (in degrees).
     * R = Rz(rz) * Ry(ry) * Rx(rx)
     */
    fun compose(rzDeg: Float, ryDeg: Float, rxDeg: Float): FloatArray {
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
            cz * cy, cz * sy * sx - sz * cx, cz * sy * cx + sz * sx,
            sz * cy, sz * sy * sx + cz * cx, sz * sy * cx - cz * sx,
            -sy, cy * sx, cy * cx
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
    fun decompose(R: FloatArray): Triple<Float, Float, Float> {
        val r20 = R.at(2, 0) // -sin(ry)

        val ry: Float
        val rz: Float
        val rx: Float

        if (abs(r20) < GIMBAL_LOCK_SIN_RY) {
            ry = asin(-r20)
            rz = atan2(R.at(1, 0), R.at(0, 0)) // atan2(sz*cy, cz*cy)
            rx = atan2(R.at(2, 1), R.at(2, 2)) // atan2(cy*sx, cy*cx)
        } else {
            // Gimbal lock: ry ≈ ±90°
            ry = if (r20 < 0) (PI / 2).toFloat() else (-PI / 2).toFloat()
            // At +90°: R(0,1) = cz*sx - sz*cx = -sin(rz-rx), R(1,1) = sz*sx + cz*cx = cos(rz-rx)
            // At -90°: R(0,1) = -(cz*sx + sz*cx) = -sin(rz+rx), R(1,1) = cz*cx - sz*sx = cos(rz+rx)
            rx = 0f
            rz = atan2(-R.at(0, 1), R.at(1, 1))
        }

        return Triple(
            Math.toDegrees(rz.toDouble()).toFloat(),
            Math.toDegrees(ry.toDouble()).toFloat(),
            Math.toDegrees(rx.toDouble()).toFloat()
        )
    }
}
