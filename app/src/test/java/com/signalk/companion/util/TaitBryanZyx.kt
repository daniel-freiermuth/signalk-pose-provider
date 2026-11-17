package com.signalk.companion.util

import kotlin.math.cos
import kotlin.math.sin

/**
 * Test fixture: builds rotation matrices from ZYX Tait-Bryan angles,
 * R = Rz(rz) * Ry(ry) * Rx(rx), angles in degrees.
 *
 * Production uses the ZXZ parameterisation of [DeviceCalibration]; this exists only to
 * construct known rotations in tests. Matrices are row-major, per frame-conventions.md §3.1.
 */
object TaitBryanZyx {

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
}
