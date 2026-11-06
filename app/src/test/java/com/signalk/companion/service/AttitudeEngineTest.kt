package com.signalk.companion.service

import com.signalk.companion.ahrs.MahonyAhrs
import com.signalk.companion.replay.AccelRecord
import com.signalk.companion.replay.GyroRecord
import com.signalk.companion.replay.HardIronStrategy
import com.signalk.companion.replay.MagRecord
import com.signalk.companion.replay.RecordingSession
import com.signalk.companion.replay.ReplayRunner
import com.signalk.companion.replay.RotationVectorRecord
import com.signalk.companion.replay.SensorRecord
import com.signalk.companion.util.DeviceCalibration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Drives the live pipeline with synthetic records through the source seam. Every record goes
 * through the real [AttitudeEngine.start] callback — the same path SensorManager events take.
 */
class AttitudeEngineTest {

    private val g = MahonyAhrs.GRAVITY

    /** A fake device: every sensor present, records delivered by [deliver] on this thread. */
    private class Harness {
        val recordingSession: RecordingSession = mock(RecordingSession::class.java)
        val engine = AttitudeEngine(recordingSession) { newSource() }
        private var onRecord: ((SensorRecord) -> Unit)? = null

        private fun newSource(): RawSensorSource {
            val source = mock(RawSensorSource::class.java)
            `when`(source.availability).thenReturn(
                RawSensorSource.Availability(
                    hasAccelerometer = true,
                    hasGyroscope = true,
                    hasMagnetometer = true,
                    gyroscopeUncalibrated = true,
                    magnetometerUncalibrated = true,
                    hasRotationVector = true
                )
            )
            doAnswer { invocation ->
                onRecord = invocation.getArgument(2)
                true
            }.`when`(source).start(anyInt(), anyBoolean(), anyNonNull())
            return source
        }

        fun start(record: Boolean = false) {
            assertTrue(engine.start(record = record), "a fully equipped device must start")
        }

        fun deliver(record: SensorRecord) {
            val callback = checkNotNull(onRecord) { "start() was not called" }
            callback(record)
        }

        /** Feed [records] in order and return every state the engine emitted. */
        fun feed(records: List<SensorRecord>): List<AttitudeEngine.State> {
            val emitted = mutableListOf<AttitudeEngine.State>()
            for (record in records) {
                val before = engine.state.value
                deliver(record)
                val after = engine.state.value
                if (after != null && after !== before) emitted.add(after)
            }
            return emitted
        }
    }

    private companion object {
        /**
         * Mockito's `any()` returns null, which Kotlin rejects for a non-null parameter
         * before Mockito ever sees the call. Register the matcher, then hand Kotlin its
         * null through an unchecked cast it does not inspect.
         */
        fun <T> anyNonNull(): T {
            val matcher: T? = any<T>()
            @Suppress("UNCHECKED_CAST")
            return matcher as T
        }
    }

    /**
     * Level device on [headingDeg], sampled at 100 Hz from [startNs]. The magnetometer carries
     * a HAL hard-iron estimate that the raw field already includes, so only a pipeline that
     * subtracts it sees the true heading.
     */
    private fun syntheticRecording(
        headingDeg: Float,
        seconds: Double,
        startNs: Long = 1_000_000_000L,
        gyro: FloatArray = floatArrayOf(0f, 0f, 0f)
    ): List<SensorRecord> {
        val theta = Math.toRadians(-headingDeg.toDouble()).toFloat()
        val c = cos(theta.toDouble()).toFloat()
        val s = sin(theta.toDouble()).toFloat()
        fun toBody(v: FloatArray) = floatArrayOf(c * v[0] + s * v[1], -s * v[0] + c * v[1], v[2])
        val accel = toBody(floatArrayOf(0f, 0f, g))
        val field = toBody(floatArrayOf(0f, 25f, -43.3f))
        val halBias = floatArrayOf(8f, -5f, 3f)

        val out = mutableListOf<SensorRecord>()
        var t = startNs
        repeat((seconds * 100).toInt()) {
            out.add(AccelRecord(t, accel[0], accel[1], accel[2]))
            out.add(
                MagRecord(
                    t,
                    field[0] + halBias[0],
                    field[1] + halBias[1],
                    field[2] + halBias[2],
                    halBias[0],
                    halBias[1],
                    halBias[2]
                )
            )
            out.add(GyroRecord(t, gyro[0], gyro[1], gyro[2]))
            t += 10_000_000L
        }
        return out
    }

    /** Android-style rotation vector (scalar-last) for a level device on [headingDeg]. */
    private fun levelRotationVector(timestampNs: Long, headingDeg: Double): RotationVectorRecord {
        // Heading is clockwise from north; a rotation about world up is counter-clockwise.
        val half = Math.toRadians(-headingDeg) / 2
        return RotationVectorRecord(timestampNs, 0f, 0f, sin(half).toFloat(), cos(half).toFloat())
    }

    private fun degrees(rad: Float) = Math.toDegrees(rad.toDouble())

    private fun wrap(deg: Double) = ((deg % 360) + 360) % 360

    // ------------------------------------------------------------------ emission

    @Test
    fun `nothing is emitted before the first gyro sample, which then emits immediately`() {
        val harness = Harness()
        harness.start()
        harness.deliver(AccelRecord(1_000_000_000L, 0f, 0f, g))
        harness.deliver(MagRecord(1_000_000_000L, 0f, 25f, -43.3f))
        assertNull(harness.engine.state.value, "accel and mag alone must not initialise the filter")

        harness.deliver(GyroRecord(1_000_000_000L, 0f, 0f, 0f))
        assertEquals(
            1_000_000_000L,
            harness.engine.state.value?.timestampNs,
            "the first initialised gyro tick must emit without waiting an interval"
        )
    }

    @Test
    fun `emission is rate limited to one per interval, inclusive at the boundary`() {
        val harness = Harness()
        harness.start()
        val t0 = 1_000_000_000L
        harness.deliver(GyroRecord(t0, 0f, 0f, 0f))

        harness.deliver(GyroRecord(t0 + AttitudeEngine.EMIT_INTERVAL_NS - 1, 0f, 0f, 0f))
        assertEquals(t0, harness.engine.state.value?.timestampNs, "1 ns short of the interval")

        harness.deliver(GyroRecord(t0 + AttitudeEngine.EMIT_INTERVAL_NS, 0f, 0f, 0f))
        assertEquals(
            t0 + AttitudeEngine.EMIT_INTERVAL_NS,
            harness.engine.state.value?.timestampNs,
            "exactly one interval later must emit"
        )
    }

    @Test
    fun `a 100 Hz stream emits at 10 Hz`() {
        val harness = Harness()
        harness.start()
        val emitted = harness.feed(syntheticRecording(90f, 2.0))
        assertEquals(20, emitted.size)
        assertTrue(
            emitted.zipWithNext().all { (a, b) -> b.timestampNs - a.timestampNs == 100_000_000L },
            "emissions must be spaced by exactly one interval"
        )
    }

    @Test
    fun `a clock that jumps backwards keeps emitting instead of stalling`() {
        val harness = Harness()
        harness.start()
        val t0 = 10_000_000_000L
        harness.deliver(GyroRecord(t0, 0f, 0f, 0f))

        val jumped = t0 - 5_000_000_000L
        harness.deliver(GyroRecord(jumped, 0f, 0f, 0f))
        assertEquals(jumped, harness.engine.state.value?.timestampNs, "the jump itself must emit")

        // And emission carries on from the new base rather than waiting to pass t0 again.
        val next = jumped + AttitudeEngine.EMIT_INTERVAL_NS
        harness.deliver(GyroRecord(next, 0f, 0f, 0f))
        assertEquals(next, harness.engine.state.value?.timestampNs)
    }

    // ------------------------------------------------------------------ parity with replay

    @Test
    fun `live output matches ReplayRunner under the same strategy and mount`() {
        // The class doc's promise: a recording made here replays there and gives the same
        // answer. The HAL hard-iron estimate in the recording only cancels under HalEstimate,
        // so this also pins that as the engine's default.
        val alpha = 4f
        val beta = -7f
        val gamma = 25f
        val recording = syntheticRecording(137f, 5.0)

        val harness = Harness()
        harness.engine.setCalibrationAngles(alpha, beta, gamma)
        harness.start()
        val live = harness.feed(recording)

        val replay = ReplayRunner(
            MahonyAhrs(),
            HardIronStrategy.HalEstimate,
            DeviceCalibration.composeZXZ(alpha, beta, gamma)
        ).run(recording.asSequence()).associateBy { it.timestampNs }

        assertEquals(50, live.size)
        for (state in live) {
            val expected = checkNotNull(replay[state.timestampNs]) {
                "replay has no sample at ${state.timestampNs}"
            }
            assertEquals(expected.headingRad, state.headingRad, "heading at ${state.timestampNs}")
            assertEquals(expected.pitchRad, state.pitchRad, "pitch at ${state.timestampNs}")
            assertEquals(expected.rollRad, state.rollRad, "roll at ${state.timestampNs}")
        }
    }

    @Test
    fun `mount yaw shifts the live heading the same way it shifts the replay`() {
        val recording = syntheticRecording(0f, 5.0)

        val plain = Harness().also { it.start() }.feed(recording).last()
        val mounted = Harness().also {
            it.engine.setCalibrationAngles(0f, 0f, 90f)
            it.start()
        }.feed(recording).last()

        assertEquals(0.0, wrap(degrees(plain.headingRad)).let { if (it > 180) it - 360 else it }, 1.0)
        assertEquals(
            270.0,
            wrap(degrees(mounted.headingRad - plain.headingRad)),
            1.0,
            "mount yaw +γ must shift reported heading by −γ, as in ReplayTest"
        )
    }

    // ------------------------------------------------------------------ rate of turn

    @Test
    fun `rate of turn is the bias-corrected gyro taken through the mount`() {
        // A stationary boat whose gyro drifts on every axis. After 60 s the estimator has
        // learned a substantial part of the drift, so a dropped bias subtraction or a mount
        // applied the wrong way round both move the answer far outside the tolerance.
        val drift = floatArrayOf(0.012f, -0.008f, 0.02f)
        val alpha = 10f
        val beta = 30f
        val gamma = 50f
        val mount = DeviceCalibration.composeZXZ(alpha, beta, gamma)
        val recording = syntheticRecording(45f, 60.0, gyro = drift)

        val harness = Harness()
        harness.engine.setCalibrationAngles(alpha, beta, gamma)
        harness.start()
        val last = harness.feed(recording).last()

        val replayed = ReplayRunner(MahonyAhrs(), HardIronStrategy.HalEstimate, mount)
            .run(recording.asSequence())
            .single { it.timestampNs == last.timestampNs }
        val bias = replayed.gyroBias
        val corrected = floatArrayOf(drift[0] - bias[0], drift[1] - bias[1], drift[2] - bias[2])

        val raw = DeviceCalibration.rateOfTurnFromGyro(mount, drift)
        val expected = DeviceCalibration.rateOfTurnFromGyro(mount, corrected)
        assertTrue(
            abs(raw - expected) > 0.005f,
            "test is only meaningful once the bias is learned: raw=$raw corrected=$expected"
        )
        assertEquals(expected, last.rateOfTurnRadS, 1e-6f)
        assertEquals(
            kotlin.math.sqrt(bias[0] * bias[0] + bias[1] * bias[1] + bias[2] * bias[2]),
            last.gyroBiasMagnitude,
            1e-6f
        )
    }

    @Test
    fun `a starboard turn reads positive through an identity mount`() {
        // Device z is up for a level phone; turning to starboard is clockwise from above,
        // i.e. a negative rate about up (§11.4).
        val harness = Harness()
        harness.start()
        harness.deliver(GyroRecord(1_000_000_000L, 0f, 0f, -0.2f))
        val rot = checkNotNull(harness.engine.state.value).rateOfTurnRadS
        assertEquals(0.2f, rot, 1e-6f)
    }

    // ------------------------------------------------------------------ reference heading

    @Test
    fun `reference heading is null until a rotation vector arrives`() {
        val harness = Harness()
        harness.start()
        val emitted = harness.feed(syntheticRecording(90f, 1.0))
        assertTrue(emitted.isNotEmpty())
        assertTrue(emitted.all { it.referenceHeadingRad == null })
    }

    @Test
    fun `reference heading follows the rotation vector and never feeds the filter`() {
        // Android's answer is deliberately 30° away from the truth (90°). It must be
        // reported as 30° and leave our own solution exactly where it would have been
        // without it.
        val clean = syntheticRecording(90f, 3.0)
        val withReference = clean.flatMap { record ->
            if (record is GyroRecord) {
                listOf(levelRotationVector(record.timestampNs, 30.0), record)
            } else {
                listOf(record)
            }
        }

        val plain = Harness().also { it.start() }.feed(clean)
        val referenced = Harness().also { it.start() }.feed(withReference)
        assertEquals(plain.map { it.headingRad }, referenced.map { it.headingRad })
        assertEquals(plain.map { it.pitchRad }, referenced.map { it.pitchRad })
        assertEquals(plain.map { it.rollRad }, referenced.map { it.rollRad })
        assertEquals(30.0, degrees(checkNotNull(referenced.last().referenceHeadingRad)), 0.01)
    }

    @Test
    fun `reference heading goes through the same mount as our own heading`() {
        // A tilted mount does not commute with the device attitude, so the reference only
        // agrees with our heading if both are taken as R_W_D · R_D_V. Hand our own converged
        // device attitude back as Android's: the two headings must then coincide.
        val alpha = 0f
        val beta = 20f
        val gamma = 90f
        val recording = syntheticRecording(137f, 5.0)
        val converged = ReplayRunner(MahonyAhrs(), HardIronStrategy.HalEstimate)
            .run(recording.asSequence()).last().attitude
        val end = recording.last().timestampNs

        val harness = Harness()
        harness.engine.setCalibrationAngles(alpha, beta, gamma)
        harness.start()
        recording.forEach(harness::deliver)
        harness.deliver(RotationVectorRecord(end + 1, converged.x, converged.y, converged.z, converged.w))
        harness.deliver(GyroRecord(end + AttitudeEngine.EMIT_INTERVAL_NS, 0f, 0f, 0f))

        val state = checkNotNull(harness.engine.state.value)
        assertEquals(end + AttitudeEngine.EMIT_INTERVAL_NS, state.timestampNs)
        val delta = wrap(degrees(checkNotNull(state.referenceHeadingRad) - state.headingRad))
        assertEquals(0.0, if (delta > 180) delta - 360 else delta, 0.05)
    }

    // ------------------------------------------------------------------ sessions

    @Test
    fun `a restart does not inherit the previous session`() {
        // The monotonic clock carries on across a restart, so the second session begins
        // 50 ms after the first one's last emission. Carrying over the emit time would
        // swallow its first emission; carrying over the bias or reference would change its
        // answer.
        // 20.05 s ends 40 ms after an emission, so the restart lands inside one interval.
        val first = syntheticRecording(137f, 20.05, gyro = floatArrayOf(0.01f, 0f, 0.02f))
            .flatMap { if (it is GyroRecord) listOf(levelRotationVector(it.timestampNs, 10.0), it) else listOf(it) }

        val restarted = Harness()
        restarted.start()
        val lastEmitted = restarted.feed(first).last().timestampNs
        val secondStart = lastEmitted + 50_000_000L
        assertTrue(secondStart > first.last().timestampNs, "the clock must not run backwards here")
        val second = syntheticRecording(250f, 2.0, startNs = secondStart)

        restarted.start()
        val afterRestart = restarted.feed(second)

        val fresh = Harness().also { it.start() }.feed(second)

        assertEquals(secondStart, afterRestart.first().timestampNs, "first tick must emit at once")
        assertEquals(fresh, afterRestart, "a restarted engine must match a fresh one")
        assertTrue(afterRestart.all { it.referenceHeadingRad == null })
    }

    @Test
    fun `recording on writes every raw record in arrival order`() {
        val harness = Harness()
        harness.start(record = true)
        val records = syntheticRecording(90f, 0.05) + levelRotationVector(2_000_000_000L, 10.0)
        records.forEach(harness::deliver)

        val order = inOrder(harness.recordingSession)
        for (record in records) order.verify(harness.recordingSession).write(record)
        order.verifyNoMoreInteractions()
    }

    @Test
    fun `recording off writes nothing`() {
        val harness = Harness()
        harness.start(record = false)
        syntheticRecording(90f, 0.05).forEach(harness::deliver)
        verify(harness.recordingSession, never()).write(anyNonNull())
    }
}
