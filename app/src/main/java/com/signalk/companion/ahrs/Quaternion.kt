package com.signalk.companion.ahrs

import com.signalk.companion.util.at
import kotlin.math.sqrt

/**
 * Unit quaternion, **Hamilton convention, scalar first**: `q = (w, x, y, z)`.
 *
 * Represents `q_W_V` — rotates vehicle-frame vectors into the world frame, the same
 * direction as `R_W_V`, so the subscript-cancellation rule applies unchanged.
 * See frame-conventions.md §3.2, which is normative for everything in this file.
 *
 * Sign handling is deliberately *not* baked in here. `q` and `−q` are the same rotation,
 * and the correct treatment depends on what you are doing (§3.2):
 * - successive samples → [alignedWith], never `w ≥ 0`
 * - comparing rotations → [cosAngleTo], which is sign-agnostic
 * - standalone storage → [canonical]
 */
data class Quaternion(val w: Float, val x: Float, val y: Float, val z: Float) {

    companion object {
        val IDENTITY = Quaternion(1f, 0f, 0f, 0f)

        /** Below this norm a quaternion is not a rotation; see [normalized]. */
        private const val MIN_NORM = 1e-12f

        /**
         * Shepperd's `s = 2·√(1 ± …)` equals 4× the pivot component; this factor recovers
         * the pivot from `s`.
         */
        private const val PIVOT_PER_S = 0.25f

        /**
         * Quaternion from a row-major rotation matrix (Shepperd's method).
         *
         * Branches on the largest diagonal term rather than always using the trace, because
         * the trace form divides by a quantity that vanishes near 180° rotations — exactly
         * the orientations a boat reaches by turning round.
         */
        fun fromRotationMatrix(r: FloatArray): Quaternion {
            val m00 = r.at(0, 0)
            val m01 = r.at(0, 1)
            val m02 = r.at(0, 2)
            val m10 = r.at(1, 0)
            val m11 = r.at(1, 1)
            val m12 = r.at(1, 2)
            val m20 = r.at(2, 0)
            val m21 = r.at(2, 1)
            val m22 = r.at(2, 2)
            val trace = m00 + m11 + m22
            return when {
                trace > 0f -> {
                    val s = sqrt(trace + 1f) * 2f
                    Quaternion(PIVOT_PER_S * s, (m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s)
                }
                m00 > m11 && m00 > m22 -> {
                    val s = sqrt(1f + m00 - m11 - m22) * 2f
                    Quaternion((m21 - m12) / s, PIVOT_PER_S * s, (m01 + m10) / s, (m02 + m20) / s)
                }
                m11 > m22 -> {
                    val s = sqrt(1f + m11 - m00 - m22) * 2f
                    Quaternion((m02 - m20) / s, (m01 + m10) / s, PIVOT_PER_S * s, (m12 + m21) / s)
                }
                else -> {
                    val s = sqrt(1f + m22 - m00 - m11) * 2f
                    Quaternion((m10 - m01) / s, (m02 + m20) / s, (m12 + m21) / s, PIVOT_PER_S * s)
                }
            }.normalized()
        }
    }

    /** Hamilton product `this ⊗ other`. Not commutative. */
    operator fun times(other: Quaternion): Quaternion = Quaternion(
        w * other.w - x * other.x - y * other.y - z * other.z,
        w * other.x + x * other.w + y * other.z - z * other.y,
        w * other.y - x * other.z + y * other.w + z * other.x,
        w * other.z + x * other.y - y * other.x + z * other.w
    )

    fun norm(): Float = sqrt(w * w + x * x + y * y + z * z)

    /** Renormalize. Every integration step must do this (§3.2). */
    fun normalized(): Quaternion {
        val n = norm()
        // A zero-norm quaternion is not a rotation; fall back rather than emit NaN.
        if (n < MIN_NORM || !n.isFinite()) return IDENTITY
        return Quaternion(w / n, x / n, y / n, z / n)
    }

    private fun conjugate(): Quaternion = Quaternion(w, -x, -y, -z)

    /**
     * The same rotation, sign-aligned to [previous] so a sequence stays continuous.
     *
     * This is the rule for temporal continuity, logging and replay. Canonicalising on
     * `w ≥ 0` instead would flip every component whenever a continuous rotation carries
     * `w` through zero, injecting a discontinuity into a smooth trajectory (§3.2).
     */
    fun alignedWith(previous: Quaternion): Quaternion =
        if (dot(previous) < 0f) Quaternion(-w, -x, -y, -z) else this

    /** `|cos(θ/2)|` between two rotations — sign-agnostic, for comparison only (§3.2). */
    fun cosAngleTo(other: Quaternion): Float = kotlin.math.abs(dot(other))

    /** One stable representation, for **standalone** storage only — never for sequences. */
    fun canonical(): Quaternion = if (w < 0f) Quaternion(-w, -x, -y, -z) else this

    fun dot(other: Quaternion): Float =
        w * other.w + x * other.x + y * other.y + z * other.z

    /**
     * Rotate a vehicle-frame vector into the world frame: `v_W = q ⊗ v_V ⊗ q*`.
     */
    fun rotate(v: FloatArray): FloatArray {
        // Standard expansion; equivalent to R_W_V · v but without building the matrix.
        val tx = 2f * (y * v[2] - z * v[1])
        val ty = 2f * (z * v[0] - x * v[2])
        val tz = 2f * (x * v[1] - y * v[0])
        return floatArrayOf(
            v[0] + w * tx + (y * tz - z * ty),
            v[1] + w * ty + (z * tx - x * tz),
            v[2] + w * tz + (x * ty - y * tx)
        )
    }

    /** Rotate a world-frame vector into the vehicle frame: `v_V = q* ⊗ v_W ⊗ q`. */
    fun rotateInverse(v: FloatArray): FloatArray = conjugate().rotate(v)

    /**
     * Row-major `R_W_V`, matching the storage convention of §3.1 and
     * `DeviceCalibration` — so `DeviceCalibration.extractNauticalAngles` consumes this
     * directly and the §10 reference poses apply unchanged.
     */
    fun toRotationMatrix(): FloatArray {
        val xx = x * x
        val yy = y * y
        val zz = z * z
        val xy = x * y
        val xz = x * z
        val yz = y * z
        val wx = w * x
        val wy = w * y
        val wz = w * z
        return floatArrayOf(
            1f - 2f * (yy + zz), 2f * (xy - wz), 2f * (xz + wy),
            2f * (xy + wz), 1f - 2f * (xx + zz), 2f * (yz - wx),
            2f * (xz - wy), 2f * (yz + wx), 1f - 2f * (xx + yy)
        )
    }
}
