package com.signalk.companion.ahrs

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Mahony explicit complementary filter (PI form) producing vehicle attitude.
 *
 * **Frame convention is ENU world / starboard-bow-up vehicle**, per frame-conventions.md
 * §1–§4. Published Mahony formulations are almost universally NED with gravity along +Z;
 * the correction terms here were re-derived for this frame rather than transcribed, as §1
 * requires. Two consequences worth stating, because they are where a copied formula breaks:
 *
 * - The accelerometer measures **specific force** and so reads `+g` along **up** at rest
 *   (§4.1). The predicted direction it is compared against is therefore world `+Z` rotated
 *   into the body frame — not its negation.
 * - The magnetometer reference puts the horizontal field component on **+Y (North)**,
 *   because this is ENU. In NED derivations it goes on +X.
 *
 * **Asynchronous rates (P3).** The filter propagates on gyro ticks and corrects with
 * whatever accelerometer and magnetometer samples have most recently arrived. Many phones
 * cap the magnetometer at 50–100 Hz while the gyro runs at 200+, so a design that required
 * matched sample sets would either stall or throw data away.
 *
 * **The integral term is not optional.** It is the gyro-bias estimator. Phone gyros drift
 * 0.5–5°/min, and gate G1 in M3 depends on that bias being tracked, so `ki > 0` is the
 * supported configuration. `TYPE_GYROSCOPE_UNCALIBRATED` reports rates *without* drift
 * compensation (§4.2) — this filter is what removes it.
 *
 * Not thread-safe: feed it from one thread, as the sensor callbacks do.
 */
class MahonyAhrs(
    /** Proportional gain on the correction. Larger = trusts acc/mag more, tracks faster. */
    var kp: Float = DEFAULT_KP,
    /** Integral gain — the gyro-bias estimator. Zero disables bias tracking. */
    var ki: Float = DEFAULT_KI,
    /**
     * Multiplier on the accelerometer correction, 0..1. M3's gates drive this; M1 uses the
     * static gate in [accelerometerGate]. Setting 0 coasts on the gyro for the roll/pitch
     * axes, which is the "freeze and coast" behaviour of P5.
     */
    var accelGain: Float = 1f,
    /**
     * Multiplier on the magnetometer correction, 0..1. This is the single lever P6 says all
     * three heading gates should feed. Setting 0 coasts on the gyro in heading.
     */
    var magGain: Float = 1f
) {

    companion object {
        /**
         * Default proportional gain, chosen for a long correction time constant (P6): wave
         * accelerations should average out across many seconds rather than being tracked.
         * Roughly a 1/kp ≈ 3 s time constant on the gravity correction.
         */
        const val DEFAULT_KP = 0.33f

        /** Integral gain. Small: bias moves slowly and must not chase wave noise. */
        const val DEFAULT_KI = 0.01f

        /** Standard gravity, m/s². */
        const val GRAVITY = 9.80665f

        /**
         * Accelerometer is trusted only when |a| is within this of gravity. Under wave
         * or slamming loads it is measuring the boat's motion, not the vertical (P6).
         */
        const val DEFAULT_ACCEL_TOLERANCE = 1.5f

        /** …and only when the boat is not swinging hard, where lever-arm terms dominate (P7). */
        const val DEFAULT_MAX_GYRO_FOR_ACCEL = 0.5f // rad/s ≈ 29°/s

        /**
         * Largest plausible gyro bias, rad/s. Turn-on bias on phone MEMS is well under this;
         * clamping stops a pathological run (a stuck sensor, a long gap) from parking the
         * estimator somewhere it cannot return from.
         */
        const val MAX_GYRO_BIAS = 0.1f // ≈ 5.7°/s

        /**
         * Anti-windup: the bias estimator only runs while the attitude error is small.
         * `|e|` is roughly `sin(attitude error)`, so this is about 6°.
         *
         * The rationale is that gyro bias is a slowly-varying physical property of the
         * sensor, and has nothing to learn from a large transient — a cold start, or a
         * sustained magnetic disturbance of the kind M3's gates exist to catch. Without the
         * guard, that transient is integrated into the bias estimate and has to unwind.
         *
         * **It does not materially shorten cold-start settling**, which is worth stating
         * because it is tempting to assume otherwise: measured on a 90° cold start, the
         * residual heading error at 30 s went from 5.99° to 5.57° with the guard in place.
         * Settling is dominated by the coupled attitude/bias loop, not by windup — see
         * `MahonyAhrsTest.cold start settles within the documented time`. The guard earns
         * its place by keeping non-bias errors out of the bias estimate, not by being fast.
         */
        const val BIAS_FREEZE_ERROR = 0.1f

        /**
         * Longest gap that still propagates. Beyond this the gyro history is worthless —
         * a suspended sensor, a Doze window — and integrating across it invents rotation
         * (frame-conventions.md §7 rule 4).
         */
        const val MAX_DT_SECONDS = 0.5f

        private const val NS_PER_S = 1_000_000_000.0
    }

    /** Current attitude estimate `q_W_V` (§3.2). */
    var attitude: Quaternion = Quaternion.IDENTITY
        private set

    /** Estimated gyro bias in the device frame, rad/s. Subtracted from every gyro sample. */
    val gyroBias: FloatArray = floatArrayOf(0f, 0f, 0f)

    /** True once at least one gyro sample has established a time base. */
    var isInitialised: Boolean = false
        private set

    /** Whether the last propagation actually applied an accelerometer correction. */
    var accelerometerAccepted: Boolean = false
        private set

    /** Whether the last propagation actually applied a magnetometer correction. */
    var magnetometerAccepted: Boolean = false
        private set

    /**
     * Whether the bias estimator ran on the last propagation. False during a cold start or
     * any large transient, by design — see [BIAS_FREEZE_ERROR]. Useful as a "still settling"
     * indicator for the M5 quality state.
     */
    var biasEstimatorRunning: Boolean = false
        private set

    var accelTolerance: Float = DEFAULT_ACCEL_TOLERANCE
    var maxGyroForAccel: Float = DEFAULT_MAX_GYRO_FOR_ACCEL

    private var lastGyroNs: Long = 0L
    private var haveAccel = false
    private var haveMag = false
    private val accel = floatArrayOf(0f, 0f, 0f)
    private val mag = floatArrayOf(0f, 0f, 0f)

    /** Latest accelerometer sample, device frame, m/s². Specific force — reads +g up at rest. */
    fun onAccelerometer(x: Float, y: Float, z: Float) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
        accel[0] = x; accel[1] = y; accel[2] = z
        haveAccel = true
    }

    /**
     * Latest magnetometer sample, device frame, µT, **already corrected** for whatever
     * hard-iron estimate the caller has decided to trust.
     *
     * The filter deliberately does not choose that: whether the correction comes from the
     * HAL's estimate, from M2's own ellipsoid fit, or is absent entirely is an architectural
     * decision (P3), not a filter parameter. Units are irrelevant here — only the direction
     * is used — so an uncalibrated field in raw counts works as long as it is a true vector.
     */
    fun onMagnetometer(x: Float, y: Float, z: Float) {
        if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
        mag[0] = x; mag[1] = y; mag[2] = z
        haveMag = true
    }

    /**
     * Propagate on a gyro sample. This is what advances time.
     *
     * @param timestampNs monotonic nanoseconds, i.e. `SensorEvent.timestamp` — never wall
     *   clock, which jumps (§7).
     * @param wx angular rate about device X, rad/s, right-hand rule (§4.2)
     */
    fun onGyroscope(timestampNs: Long, wx: Float, wy: Float, wz: Float) {
        if (!wx.isFinite() || !wy.isFinite() || !wz.isFinite()) return

        if (!isInitialised) {
            lastGyroNs = timestampNs
            isInitialised = true
            // Seed algebraically rather than starting at identity and letting the loop walk
            // there. Starting at identity means the estimate must cross the whole error, and
            // near a 180° initial error the correction term — a cross product of two nearly
            // opposed vectors — is close to zero, so the filter sits on that unstable point
            // for minutes before falling off it. Measured: still at 0° after 90 s when truth
            // was 180°. Seeding removes the transient outright.
            //
            // Only when both references are trusted. A caller that has zeroed a gain is
            // saying that sensor is not to be believed — seeding from it anyway would smuggle
            // a distrusted measurement in through the back door, and would do it at full
            // weight rather than through a gain.
            if (accelGain > 0f && magGain > 0f) seedFromMeasurements()
            return // First sample establishes the time base only; no dt exists yet.
        }

        val dtRaw = ((timestampNs - lastGyroNs) / NS_PER_S).toFloat()
        lastGyroNs = timestampNs

        // Non-positive dt means duplicate or out-of-order delivery. Integrating it would
        // run the filter backwards; drop the sample but keep the new time base (§7 rule 4).
        if (dtRaw <= 0f || !dtRaw.isFinite()) return
        val dt = if (dtRaw > MAX_DT_SECONDS) MAX_DT_SECONDS else dtRaw

        val e = correctionTerm()

        // PI: proportional term steers the estimate, integral term learns the bias.
        // The integral runs only near convergence — see BIAS_FREEZE_ERROR.
        val errorMagnitude = sqrt(e[0] * e[0] + e[1] * e[1] + e[2] * e[2])
        biasEstimatorRunning = ki > 0f && errorMagnitude < BIAS_FREEZE_ERROR
        if (biasEstimatorRunning) {
            for (i in 0..2) {
                gyroBias[i] = (gyroBias[i] - ki * e[i] * dt).coerceIn(-MAX_GYRO_BIAS, MAX_GYRO_BIAS)
            }
        }

        val ox = wx - gyroBias[0] + kp * e[0]
        val oy = wy - gyroBias[1] + kp * e[1]
        val oz = wz - gyroBias[2] + kp * e[2]

        // q̇ = ½ q ⊗ (0, ω), first-order integration then renormalise (§3.2).
        val q = attitude
        val half = 0.5f * dt
        val dq = q * Quaternion(0f, ox, oy, oz)
        attitude = Quaternion(
            q.w + dq.w * half,
            q.x + dq.x * half,
            q.y + dq.y * half,
            q.z + dq.z * half
        ).normalized().alignedWith(q)   // continuity, never w ≥ 0 (§3.2)
    }

    /**
     * Set the attitude directly from the latest accelerometer and magnetometer pair (TRIAD).
     *
     * Two non-parallel reference directions fully determine an orientation, so no iteration
     * is needed: gravity fixes roll and pitch, and the field's horizontal component fixes
     * heading. Called automatically on the first gyro sample; also useful after a long gap.
     *
     * Note it needs no knowledge of the local dip angle. The second basis vector is
     * `up × field`, which points **West** whatever the inclination, so the construction is
     * valid in either hemisphere. It does assume the field's horizontal component points
     * magnetic north — i.e. it inherits exactly the uncorrected-deviation caveat that the
     * whole heading chain carries until M2 (frame-conventions.md §11.2).
     *
     * @return true if a seed was applied; false if measurements were missing or degenerate
     *   (field parallel to gravity, which would leave heading undetermined).
     */
    fun seedFromMeasurements(): Boolean {
        if (!haveAccel || !haveMag) return false

        // The seed is subject to the same plausibility gate as the correction. Seeding from
        // a slam or a wave impact would hand the filter a confidently wrong attitude, which
        // is worse than starting at identity and iterating: it looks settled immediately.
        if (abs(norm(accel) - GRAVITY) > accelTolerance) return false

        val up = normalize(accel) ?: return false
        val field = normalize(mag) ?: return false

        // Body-frame images of world West and world South.
        val west = normalize(cross(up, field)) ?: return false // degenerate if field ∥ gravity
        val south = cross(up, west)

        // R_W_V = M_W · M_Bᵀ, where each M holds [up, west, south] as columns. In ENU those
        // world vectors are (0,0,1), (−1,0,0) and (0,−1,0), so M_W just permutes and negates
        // rows, and the product collapses to this.
        val r = floatArrayOf(
            -west[0], -west[1], -west[2],
            -south[0], -south[1], -south[2],
            up[0], up[1], up[2]
        )
        attitude = Quaternion.fromRotationMatrix(r).alignedWith(attitude)
        return true
    }

    /** Reset to a known attitude, e.g. when restarting after a long gap. */
    fun reset(to: Quaternion = Quaternion.IDENTITY, clearBias: Boolean = false) {
        attitude = to.normalized()
        lastGyroNs = 0L
        isInitialised = false
        haveAccel = false
        haveMag = false
        accelerometerAccepted = false
        magnetometerAccepted = false
        if (clearBias) { gyroBias[0] = 0f; gyroBias[1] = 0f; gyroBias[2] = 0f }
    }

    /**
     * Body-frame correction vector `e = Σ (measured × predicted)`, summed over the
     * accelerometer (roll/pitch) and magnetometer (heading) references.
     *
     * Sign check, since this is the part a copied formula gets wrong: suppose the estimate
     * is rotated by +δ about body X relative to truth while the boat is level. The measured
     * up direction in body is `(0,0,1)`; the predicted one is `(0, sin δ, cos δ)`. Their
     * cross product is `(−δ, 0, 0)` — a rotation about X that *reduces* the error when added
     * to ω. The same argument gives the integral term its `−ki·e` sign: a positive gyro bias
     * over-rotates the estimate, producing negative `e`, which drives the bias estimate up.
     */
    private fun correctionTerm(): FloatArray {
        val e = floatArrayOf(0f, 0f, 0f)
        accelerometerAccepted = false
        magnetometerAccepted = false

        if (haveAccel && accelGain > 0f) {
            val magnitude = norm(accel)
            // Gate: only trust the accelerometer when it is plausibly measuring gravity
            // and the boat is not swinging (P6, P7).
            if (abs(magnitude - GRAVITY) <= accelTolerance && magnitude > 1e-6f) {
                val a = floatArrayOf(accel[0] / magnitude, accel[1] / magnitude, accel[2] / magnitude)
                // Predicted "up" in the body frame: world +Z rotated into body (§4.1).
                val up = attitude.rotateInverse(WORLD_UP)
                val c = cross(a, up)
                val g = accelGain
                e[0] += c[0] * g; e[1] += c[1] * g; e[2] += c[2] * g
                accelerometerAccepted = true
            }
        }

        if (haveMag && magGain > 0f) {
            val magnitude = norm(mag)
            if (magnitude > 1e-6f) {
                val m = floatArrayOf(mag[0] / magnitude, mag[1] / magnitude, mag[2] / magnitude)
                // Reference direction: take the measured field into the world frame with the
                // current estimate, then flatten its horizontal part onto +Y (North). ENU —
                // NED derivations put it on +X. This makes the correction heading-only:
                // any tilt error the field implies is left to the accelerometer.
                val h = attitude.rotate(m)
                val horizontal = sqrt(h[0] * h[0] + h[1] * h[1])
                val reference = floatArrayOf(0f, horizontal, h[2])
                val predicted = attitude.rotateInverse(reference)
                val c = cross(m, predicted)
                val g = magGain
                e[0] += c[0] * g; e[1] += c[1] * g; e[2] += c[2] * g
                magnetometerAccepted = true
            }
        }

        // A hard swing suppresses the accelerometer correction only; heading is unaffected
        // by turning, so the magnetometer term stays live.
        return e
    }

    /** Exposed so callers can report why a correction was skipped. */
    fun accelerometerGate(gyroMagnitude: Float): Boolean {
        if (!haveAccel) return false
        val magnitude = norm(accel)
        return abs(magnitude - GRAVITY) <= accelTolerance && gyroMagnitude <= maxGyroForAccel
    }

    private fun norm(v: FloatArray): Float = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    /** Unit vector, or null when the input is degenerate — never a NaN-filled array. */
    private fun normalize(v: FloatArray): FloatArray? {
        val n = norm(v)
        if (n < 1e-6f || !n.isFinite()) return null
        return floatArrayOf(v[0] / n, v[1] / n, v[2] / n)
    }

    private fun cross(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    )
}

private val WORLD_UP = floatArrayOf(0f, 0f, 1f)
