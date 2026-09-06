package com.signalk.companion.ahrs

import com.signalk.companion.util.DeviceCalibration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Behavioural tests for the M1 attitude filter.
 *
 * These are written against synthetic sensors derived from a *known* true attitude, so a
 * sign error anywhere in the ENU re-derivation shows up as divergence rather than as a
 * plausible-looking number. The frame conventions asserted here are the same ones
 * `FrameConventionsTest` pins for the static extraction — §5 and §10 of
 * frame-conventions.md.
 */
class MahonyAhrsTest {

    private val g = MahonyAhrs.GRAVITY

    /**
     * Earth field in ENU for a northern-hemisphere location: horizontal component to the
     * North (+Y), vertical component **downward** (−Z), dip 60°, 50 µT (§4.3).
     */
    private val fieldWorld = floatArrayOf(
        0f,
        50f * cos(Math.toRadians(60.0)).toFloat(),
        -50f * sin(Math.toRadians(60.0)).toFloat()
    )

    /** Rotation about a unit axis by `angleRad`, Hamilton scalar-first. */
    private fun axisAngle(ax: Float, ay: Float, az: Float, angleRad: Float): Quaternion {
        val h = angleRad / 2f
        val s = sin(h.toDouble()).toFloat()
        return Quaternion(cos(h.toDouble()).toFloat(), ax * s, ay * s, az * s)
    }

    /**
     * Attitude of a level boat on the given heading. Heading is clockwise from North, while
     * rotation about world +Z (up) is counterclockwise, hence the negation.
     */
    private fun levelAtHeading(headingDeg: Float): Quaternion =
        axisAngle(0f, 0f, 1f, -Math.toRadians(headingDeg.toDouble()).toFloat())

    /** What the sensors read, in the body frame, for a given true attitude at rest. */
    private fun sensorsAtRest(qTrue: Quaternion): Pair<FloatArray, FloatArray> {
        // Accelerometer measures specific force: at rest it reads +g along world up (§4.1).
        val accel = qTrue.rotateInverse(floatArrayOf(0f, 0f, g))
        val mag = qTrue.rotateInverse(fieldWorld)
        return accel to mag
    }

    /**
     * Run the filter to convergence against a static true attitude.
     *
     * Default `ki = 0` on purpose: these tests are about the attitude loop, and the integral
     * term makes the system second-order with a settling time of a couple of minutes (see
     * `cold start settles within the documented time`). Isolating the proportional loop
     * keeps a genuine sign error from hiding behind a slow transient.
     */
    private fun settle(
        qTrue: Quaternion,
        filter: MahonyAhrs = MahonyAhrs(kp = 2f, ki = 0f),
        seconds: Double = 30.0,
        hz: Double = 100.0,
        gyro: FloatArray = floatArrayOf(0f, 0f, 0f)
    ): MahonyAhrs {
        val (accel, mag) = sensorsAtRest(qTrue)
        val dtNs = (1e9 / hz).toLong()
        var t = 1_000_000_000L
        repeat((seconds * hz).toInt()) {
            filter.onAccelerometer(accel[0], accel[1], accel[2])
            filter.onMagnetometer(mag[0], mag[1], mag[2])
            filter.onGyroscope(t, gyro[0], gyro[1], gyro[2])
            t += dtNs
        }
        return filter
    }

    private fun angles(filter: MahonyAhrs) =
        DeviceCalibration.extractNauticalAngles(filter.attitude.toRotationMatrix())

    private fun assertDegreesNear(expected: Float, actualRad: Float, tolDeg: Float, name: String) {
        val actual = Math.toDegrees(actualRad.toDouble()).toFloat()
        val diff = abs(((expected - actual + 540f) % 360f) - 180f)
        assertTrue(diff <= tolDeg, "$name: expected ${expected}°, got ${actual}° (tol ${tolDeg}°)")
    }

    // ------------------------------------------------------------------ convergence

    @Test
    fun `converges to level north from a cold start`() {
        val f = settle(levelAtHeading(0f))
        val a = angles(f)
        assertDegreesNear(0f, a.headingRad, 2f, "heading")
        assertDegreesNear(0f, a.pitchRad, 1f, "pitch")
        assertDegreesNear(0f, a.rollRad, 1f, "roll")
    }

    @Test
    fun `converges to the correct heading on each cardinal`() {
        // 180° is excluded here and tested separately — see the antipodal case below.
        for (heading in listOf(0f, 45f, 90f, 270f, 315f)) {
            val a = angles(settle(levelAtHeading(heading)))
            assertDegreesNear(heading, a.headingRad, 0.5f, "heading $heading")
            assertDegreesNear(0f, a.rollRad, 0.5f, "roll at heading $heading")
            assertDegreesNear(0f, a.pitchRad, 0.5f, "pitch at heading $heading")
        }
    }

    @Test
    fun `seeds the attitude from the first measurement pair`() {
        // TRIAD seeding means there is no cold-start transient to converge through: one
        // accelerometer and one magnetometer sample determine the orientation outright.
        for (heading in listOf(0f, 90f, 180f, 270f)) {
            val f = MahonyAhrs(kp = 2f, ki = 0f)
            val (accel, mag) = sensorsAtRest(levelAtHeading(heading))
            f.onAccelerometer(accel[0], accel[1], accel[2])
            f.onMagnetometer(mag[0], mag[1], mag[2])
            f.onGyroscope(1_000_000_000L, 0f, 0f, 0f) // first sample seeds
            assertDegreesNear(heading, angles(f).headingRad, 0.5f, "seeded heading $heading")
        }
    }

    @Test
    fun `seeding handles the antipodal case that iteration could not`() {
        // Without seeding this was the pathological case: starting at identity with truth at
        // 180°, the correction is a cross product of two nearly-opposed vectors, so it is
        // near zero and the filter sits on that unstable point. Measured: still reading 0°
        // after 90 s of simulated time. Seeding makes it exact immediately.
        val f = settle(levelAtHeading(180f), seconds = 1.0)
        assertDegreesNear(180f, angles(f).headingRad, 0.5f, "antipodal heading")
    }

    @Test
    fun `seeding is skipped when the field is parallel to gravity`() {
        // Degenerate: heading is genuinely undetermined, so seeding must decline rather than
        // emit a NaN attitude.
        val f = MahonyAhrs()
        f.onAccelerometer(0f, 0f, g)
        f.onMagnetometer(0f, 0f, 40f) // straight up — no horizontal component
        assertFalse(f.seedFromMeasurements(), "a degenerate pair must not produce an attitude")
        assertTrue(f.attitude.norm().isFinite(), "attitude must stay valid")
    }

    @Test
    fun `seeding recovers roll and pitch too`() {
        val qTrue = axisAngle(0f, 1f, 0f, Math.toRadians(20.0).toFloat()) // 20° starboard heel
        val f = MahonyAhrs(kp = 2f, ki = 0f)
        val (accel, mag) = sensorsAtRest(qTrue)
        f.onAccelerometer(accel[0], accel[1], accel[2])
        f.onMagnetometer(mag[0], mag[1], mag[2])
        f.onGyroscope(1_000_000_000L, 0f, 0f, 0f)
        val a = angles(f)
        assertDegreesNear(20f, a.rollRad, 0.5f, "seeded roll")
        assertDegreesNear(0f, a.pitchRad, 0.5f, "seeded pitch")
    }

    @Test
    fun `heel to starboard converges to positive roll`() {
        // Bow north, heeled 20° to starboard: rotation about the bow axis (world +Y).
        // Positive roll is starboard-down (§5); starboard is +X, so rolling it downward is
        // a positive rotation about +Y by the right-hand rule.
        val qTrue = axisAngle(0f, 1f, 0f, Math.toRadians(20.0).toFloat())
        val a = angles(settle(qTrue))
        assertTrue(a.rollRad > 0f, "heel to starboard must give POSITIVE roll, got ${a.rollRad}")
        assertDegreesNear(20f, a.rollRad, 2f, "roll")
        assertDegreesNear(0f, a.pitchRad, 2f, "pitch")
        assertDegreesNear(0f, a.headingRad, 3f, "heading")
    }

    @Test
    fun `bow up converges to positive pitch`() {
        // Pitch bow up about the starboard axis (world +X). By the right-hand rule a
        // POSITIVE rotation about +X carries +Y (bow) toward +Z (up), so bow-up is +φ.
        val qTrue = axisAngle(1f, 0f, 0f, Math.toRadians(10.0).toFloat())
        val a = angles(settle(qTrue))
        assertTrue(a.pitchRad > 0f, "bow up must give POSITIVE pitch, got ${a.pitchRad}")
        assertDegreesNear(10f, a.pitchRad, 2f, "pitch")
        assertDegreesNear(0f, a.rollRad, 2f, "roll")
    }

    // ------------------------------------------------------------------ gyro handling

    @Test
    fun `propagates on the gyro alone when corrections are disabled`() {
        // Gains off: pure dead-reckoning. A positive rate about the vehicle's up axis is
        // counterclockwise seen from above, i.e. a turn to PORT, so heading decreases (§4.2).
        val f = MahonyAhrs(kp = 0f, ki = 0f)
        val dtNs = 5_000_000L // 200 Hz
        var t = 1_000_000_000L
        f.onGyroscope(t, 0f, 0f, 0f) // establish time base
        repeat(200) { // 1 second at 0.1 rad/s
            t += dtNs
            f.onGyroscope(t, 0f, 0f, 0.1f)
        }
        val headingDeg = Math.toDegrees(angles(f).headingRad.toDouble()).toFloat()
        // 0.1 rad/s for 1 s = 5.73° to port → heading 354.27°
        assertDegreesNear(360f - 5.73f, angles(f).headingRad, 0.5f, "heading after port turn")
        assertTrue(headingDeg > 180f, "turn to port must decrease heading, got $headingDeg")
    }

    @Test
    fun `estimates and removes a constant gyro bias`() {
        // A stationary, level boat with a biased gyro. The integral term must learn the bias
        // rather than let it walk the attitude away — this is what gate G1 depends on.
        val bias = floatArrayOf(0.02f, -0.015f, 0.01f) // rad/s, ~1°/s
        val f = settle(levelAtHeading(0f), MahonyAhrs(kp = 2f, ki = 0.5f), seconds = 120.0, gyro = bias)

        for (i in 0..2) {
            assertEquals(
                bias[i], f.gyroBias[i], 0.005f,
                "bias axis $i should converge to the injected value"
            )
        }
        val a = angles(f)
        assertDegreesNear(0f, a.rollRad, 1f, "roll must stay level despite bias")
        assertDegreesNear(0f, a.pitchRad, 1f, "pitch must stay level despite bias")
    }

    @Test
    fun `recovers from a large error without seeding, given time`() {
        // The unseeded path still has to work: seeding needs both sensors, and either can be
        // missing at startup. Forced here by resetting to a deliberately wrong attitude after
        // initialisation. With the integral term active the system is second-order and
        // settles in minutes — measured from 90° out at kp=2, ki=0.1:
        //   30 s → 5.6° out    60 s → 0.8° out    120 s → 0.02° out
        // Pinned so the documented figure and the code cannot drift apart, and so a future
        // gain change has to confront the cost.
        val f = MahonyAhrs(kp = 2f, ki = 0.1f)
        val (accel, mag) = sensorsAtRest(levelAtHeading(90f))
        var t = 1_000_000_000L
        // Gyro arrives first, before either reference sensor — a real startup ordering, and
        // the one case where seeding cannot apply. The filter starts at identity, 90° out.
        f.onGyroscope(t, 0f, 0f, 0f)
        assertEquals(1f, Quaternion.IDENTITY.cosAngleTo(f.attitude), 1e-6f,
            "with no reference sensors yet, the filter must start at identity")

        repeat(15000) {
            t += 10_000_000L
            f.onAccelerometer(accel[0], accel[1], accel[2])
            f.onMagnetometer(mag[0], mag[1], mag[2])
            f.onGyroscope(t, 0f, 0f, 0f)
        }
        assertDegreesNear(90f, angles(f).headingRad, 0.5f, "heading 150 s after a 90° error")
        for (i in 0..2) {
            assertTrue(
                abs(f.gyroBias[i]) < 0.005f,
                "a stationary, unbiased gyro must settle to ~zero bias, axis $i = ${f.gyroBias[i]}"
            )
        }
    }

    @Test
    fun `bias estimator is frozen while the error is large`() {
        // Gyro first, so seeding cannot apply and the filter really does start 180° out.
        val f = MahonyAhrs(kp = 2f, ki = 0.5f)
        val (accel, mag) = sensorsAtRest(levelAtHeading(180f))
        var t = 1_000_000_000L
        f.onGyroscope(t, 0f, 0f, 0f)
        f.onAccelerometer(accel[0], accel[1], accel[2])
        f.onMagnetometer(mag[0], mag[1], mag[2])
        t += 10_000_000L
        f.onGyroscope(t, 0f, 0f, 0f)
        assertFalse(f.biasEstimatorRunning, "bias must not learn from a large transient")
    }

    @Test
    fun `does not seed from an implausible accelerometer reading`() {
        // Seeding must respect the same gate as the correction: a slam is not gravity, and a
        // confidently-wrong seed is worse than no seed because it looks settled.
        val f = MahonyAhrs()
        f.onAccelerometer(3f * g, 0f, 0f)
        f.onMagnetometer(0f, 25f, -43f)
        assertFalse(f.seedFromMeasurements(), "a 3 g reading must not seed the attitude")
    }

    @Test
    fun `does not seed from a distrusted magnetometer`() {
        // magGain 0 is a caller saying "do not believe the magnetometer" — seeding from it
        // would reintroduce it at full weight.
        val f = MahonyAhrs(kp = 2f, ki = 0f)
        f.magGain = 0f
        val (accel, mag) = sensorsAtRest(levelAtHeading(90f))
        f.onAccelerometer(accel[0], accel[1], accel[2])
        f.onMagnetometer(mag[0], mag[1], mag[2])
        f.onGyroscope(1_000_000_000L, 0f, 0f, 0f)
        assertDegreesNear(0f, angles(f).headingRad, 0.5f, "must not seed heading from a gated mag")
    }

    @Test
    fun `bias estimate is clamped to a plausible range`() {
        val absurd = floatArrayOf(5f, 5f, 5f) // rad/s, far beyond any real MEMS bias
        val f = settle(levelAtHeading(0f), MahonyAhrs(kp = 2f, ki = 2f), seconds = 60.0, gyro = absurd)
        for (i in 0..2) {
            assertTrue(
                abs(f.gyroBias[i]) <= MahonyAhrs.MAX_GYRO_BIAS + 1e-6f,
                "bias axis $i must stay clamped, got ${f.gyroBias[i]}"
            )
        }
    }

    // ------------------------------------------------------------------ gating

    @Test
    fun `rejects the accelerometer when it is not measuring gravity`() {
        val f = MahonyAhrs(kp = 2f, ki = 0f)
        val (_, mag) = sensorsAtRest(levelAtHeading(0f))
        // A slam: 3 g sideways. Trusting this as "down" would tip the attitude over.
        f.onAccelerometer(3f * g, 0f, 0f)
        f.onMagnetometer(mag[0], mag[1], mag[2])
        var t = 1_000_000_000L
        f.onGyroscope(t, 0f, 0f, 0f)
        repeat(500) { t += 10_000_000L; f.onGyroscope(t, 0f, 0f, 0f) }

        assertFalse(f.accelerometerAccepted, "an implausible |a| must be gated out")
        val a = angles(f)
        assertDegreesNear(0f, a.rollRad, 1f, "roll must not follow a slam")
        assertDegreesNear(0f, a.pitchRad, 1f, "pitch must not follow a slam")
    }

    @Test
    fun `rejects the accelerometer while the boat is swinging hard`() {
        // Regression test: this gate was declared and documented but never consulted by the
        // correction path, so a hard swing still pulled the vertical. The phone sits off the
        // centre of rotation, so swinging produces real lever-arm acceleration at the phone
        // that is not acceleration of the boat (P7) — and it can pass |a| ≈ g while pointing
        // somewhere other than down.
        val f = MahonyAhrs(kp = 2f, ki = 0f)
        val (accel, mag) = sensorsAtRest(levelAtHeading(0f))
        var t = 1_000_000_000L
        f.onAccelerometer(accel[0], accel[1], accel[2])
        f.onMagnetometer(mag[0], mag[1], mag[2])
        f.onGyroscope(t, 0f, 0f, 0f)

        // A hard tack: well above maxGyroForAccel, with a perfectly plausible |a|.
        val fast = MahonyAhrs.DEFAULT_MAX_GYRO_FOR_ACCEL * 2f
        t += 10_000_000L
        f.onGyroscope(t, 0f, 0f, fast)
        assertFalse(f.accelerometerAccepted, "a hard swing must gate out the accelerometer")
        assertTrue(f.magnetometerAccepted, "heading is unaffected by turning — mag stays live")

        // And it comes back once the swing stops.
        t += 10_000_000L
        f.onGyroscope(t, 0f, 0f, 0f)
        assertTrue(f.accelerometerAccepted, "the gate must reopen when the swing stops")
    }

    @Test
    fun `accepts the accelerometer at rest`() {
        val f = settle(levelAtHeading(0f), MahonyAhrs(kp = 2f, ki = 0f), seconds = 1.0)
        assertTrue(f.accelerometerAccepted, "a 1 g reading at rest must be accepted")
        assertTrue(f.magnetometerAccepted, "a valid field must be accepted")
    }

    @Test
    fun `zero gain freezes the affected axis`() {
        // magGain = 0 is the "freeze and coast" lever P6 gives the heading gates.
        val f = MahonyAhrs(kp = 2f, ki = 0f)
        f.magGain = 0f
        settle(levelAtHeading(90f), f, seconds = 20.0)
        assertFalse(f.magnetometerAccepted, "magGain 0 must suppress the magnetometer term")
        // Heading stays at its cold-start value because nothing corrects it.
        assertDegreesNear(0f, angles(f).headingRad, 2f, "heading must not be corrected")
        // Roll and pitch still converge — the accelerometer is untouched by the mag gate.
        assertDegreesNear(0f, angles(f).rollRad, 2f, "roll still converges")
    }

    // ------------------------------------------------------------------ time handling

    @Test
    fun `ignores non-positive dt`() {
        val f = MahonyAhrs(kp = 0f, ki = 0f)
        val t = 1_000_000_000L
        f.onGyroscope(t, 0f, 0f, 0f)
        f.onGyroscope(t + 10_000_000L, 0f, 0f, 1f)
        val afterOne = f.attitude
        // Same timestamp again, and a backwards one: neither may advance the filter.
        f.onGyroscope(t + 10_000_000L, 0f, 0f, 1f)
        f.onGyroscope(t + 5_000_000L, 0f, 0f, 1f)
        assertEquals(1f, afterOne.cosAngleTo(f.attitude), 1e-6f, "duplicate/backwards dt must not integrate")
    }

    @Test
    fun `a rejected out-of-order sample does not move the time base backward`() {
        // Regression: lastGyroNs was updated before the dt>0 guard, so a rejected,
        // out-of-order timestamp still became the new baseline. The next accepted sample then
        // integrated against that stale point instead of the last sample actually accepted,
        // silently over-integrating the rotation.
        val f = MahonyAhrs(kp = 0f, ki = 0f)
        val rateRadS = 10f
        val t = 1_000_000_000L
        f.onGyroscope(t, 0f, 0f, 0f) // establish time base
        f.onGyroscope(t + 10_000_000L, 0f, 0f, rateRadS) // accepted: dt = 10 ms
        f.onGyroscope(t + 5_000_000L, 0f, 0f, rateRadS) // rejected: out of order
        f.onGyroscope(t + 20_000_000L, 0f, 0f, rateRadS) // must see dt = 10 ms, not 15 ms
        // Two accepted 10 ms ticks at 10 rad/s = 20 ms total integrated, to port.
        val expectedDeg = 360f - Math.toDegrees((rateRadS * 0.020).toDouble()).toFloat()
        assertDegreesNear(
            expectedDeg,
            angles(f).headingRad,
            0.2f,
            "a rejected sample must not inflate the next sample's dt"
        )
    }

    @Test
    fun `clamps an implausibly long gap`() {
        // A 60 s gap (Doze, suspended sensor) must not integrate 60 s of rotation.
        val f = MahonyAhrs(kp = 0f, ki = 0f)
        var t = 1_000_000_000L
        f.onGyroscope(t, 0f, 0f, 0f)
        t += 60_000_000_000L
        f.onGyroscope(t, 0f, 0f, 1f) // 1 rad/s
        val turnedDeg = abs(Math.toDegrees(angles(f).headingRad.toDouble()).toFloat().let {
            if (it > 180f) 360f - it else it
        })
        val maxExpected = Math.toDegrees((1f * MahonyAhrs.MAX_DT_SECONDS).toDouble()).toFloat()
        assertTrue(
            turnedDeg <= maxExpected + 0.5f,
            "a 60 s gap must clamp to ${MahonyAhrs.MAX_DT_SECONDS}s, but turned $turnedDeg°"
        )
    }

    @Test
    fun `first sample only establishes the time base`() {
        val f = MahonyAhrs(kp = 0f, ki = 0f)
        assertFalse(f.isInitialised)
        f.onGyroscope(1_000_000_000L, 0f, 0f, 10f) // huge rate, but no dt exists yet
        assertTrue(f.isInitialised)
        assertEquals(1f, Quaternion.IDENTITY.cosAngleTo(f.attitude), 1e-6f,
            "the first gyro sample must not rotate anything")
    }

    // ------------------------------------------------------------------ numerics

    @Test
    fun `stays normalised over a long run`() {
        // Float state at 200 Hz for an hour of wall time is the realistic duty cycle.
        // If Float were inadequate this is where it would show.
        val f = MahonyAhrs(kp = 0.33f, ki = 0.01f)
        val (accel, mag) = sensorsAtRest(levelAtHeading(45f))
        var t = 1_000_000_000L
        repeat(200 * 60 * 60) {
            f.onAccelerometer(accel[0], accel[1], accel[2])
            f.onMagnetometer(mag[0], mag[1], mag[2])
            f.onGyroscope(t, 0.001f, -0.002f, 0.0005f)
            t += 5_000_000L
        }
        assertEquals(1f, f.attitude.norm(), 1e-3f, "quaternion norm must not drift")
        assertDegreesNear(45f, angles(f).headingRad, 3f, "heading must hold over an hour")
    }

    // ------------------------------------------------------------------ quaternion basics

    @Test
    fun `rotation matrix matches the frame-convention reference poses`() {
        // Cross-check Quaternion.toRotationMatrix against the §10 poses that
        // FrameConventionsTest pins for DeviceCalibration.
        for (heading in listOf(0f, 45f, 90f, 180f, 270f, 315f)) {
            val fromQuaternion = DeviceCalibration.extractNauticalAngles(
                levelAtHeading(heading).toRotationMatrix()
            )
            assertDegreesNear(heading, fromQuaternion.headingRad, 0.01f, "heading $heading")
        }
    }

    @Test
    fun `alignedWith keeps a sequence continuous where canonical would jump`() {
        val q = Quaternion(0.01f, 0.9999f, 0f, 0f).normalized()
        val next = Quaternion(-0.01f, -0.9999f, 0f, 0f).normalized() // same rotation, flipped sign
        // Same physical rotation either way.
        assertEquals(1f, q.cosAngleTo(next), 1e-3f)
        // Aligned: continuous with the previous sample.
        assertTrue(next.alignedWith(q).dot(q) > 0f, "aligned sample must not flip sign")
        // Canonical: would flip, which is exactly the discontinuity §3.2 warns about.
        assertTrue(q.canonical().w >= 0f && next.canonical().w >= 0f)
    }
}
