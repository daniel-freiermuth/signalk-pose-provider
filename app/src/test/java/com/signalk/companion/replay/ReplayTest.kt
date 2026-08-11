package com.signalk.companion.replay

import com.signalk.companion.ahrs.MahonyAhrs
import com.signalk.companion.util.DeviceCalibration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class ReplayTest {

    private val g = MahonyAhrs.GRAVITY

    // ------------------------------------------------------------------ format

    @Test
    fun `records round-trip through the text format`() {
        val records = listOf(
            AccelRecord(1_000_000_000L, 0.1f, -0.2f, 9.81f),
            GyroRecord(1_005_000_000L, 0.01f, -0.02f, 0.03f, 1e-4f, 2e-4f, -3e-4f),
            MagRecord(1_010_000_000L, 12.5f, -30.25f, -40f, 1.5f, -2.5f, 3.5f),
            FixRecord(1_020_000_000L, 59.3293, 18.0686, 12.5, 3.2f, 187.4f, 4.1f, 0.2f, 2.5f)
        )
        val text = records.joinToString("\n") { RecordingFormat.format(it) }
        val parsed = RecordingFormat.parseAll(text.lineSequence()).toList()
        assertEquals(records, parsed)
    }

    @Test
    fun `absent optional fix fields survive the round trip`() {
        val fix = FixRecord(5_000L, 1.0, 2.0) // everything optional missing
        val parsed = RecordingFormat.parse(RecordingFormat.format(fix))
        assertEquals(fix, parsed)
    }

    @Test
    fun `comments blanks and malformed lines are skipped, not fatal`() {
        // A recording is a field artefact: a battery pull mid-write truncates the last line,
        // and losing that line must not cost the whole sail.
        val text = """
            ${RecordingFormat.header("test", 1L, 2L)}

            A 1000 0.0 0.0 9.81
            not-a-record
            G 2000 oops bad numbers
            A 3000 0.0
            A 40
        """.trimIndent()
        val parsed = RecordingFormat.parseAll(text.lineSequence()).toList()
        assertEquals(1, parsed.size, "only the one well-formed record should survive")
        assertEquals(1000L, parsed[0].timestampNs)
    }

    @Test
    fun `header does not parse as a record`() {
        val header = RecordingFormat.header("Pixel", 1234L, 5678L)
        assertTrue(RecordingFormat.parseAll(header.lineSequence()).toList().isEmpty())
    }

    // ------------------------------------------------------------------ replay

    /** A synthetic sail: level, heading 90°, at rest, sampled at 100 Hz. */
    private fun syntheticRecording(headingDeg: Float, seconds: Double): List<SensorRecord> {
        val theta = Math.toRadians(-headingDeg.toDouble()).toFloat() // world→body about up
        val c = cos(theta.toDouble()).toFloat(); val s = sin(theta.toDouble()).toFloat()
        // Rotate world vectors into the body frame for a level boat on this heading.
        fun toBody(v: FloatArray) = floatArrayOf(
            c * v[0] + s * v[1], -s * v[0] + c * v[1], v[2]
        )
        val accel = toBody(floatArrayOf(0f, 0f, g))
        val field = toBody(floatArrayOf(0f, 25f, -43.3f))

        val out = mutableListOf<SensorRecord>()
        var t = 1_000_000_000L
        repeat((seconds * 100).toInt()) {
            out.add(AccelRecord(t, accel[0], accel[1], accel[2]))
            out.add(MagRecord(t, field[0], field[1], field[2]))
            out.add(GyroRecord(t, 0f, 0f, 0f))
            t += 10_000_000L
        }
        return out
    }

    @Test
    fun `replay recovers the recorded attitude`() {
        val samples = ReplayRunner(MahonyAhrs(kp = 2f, ki = 0f))
            .run(syntheticRecording(90f, 5.0).asSequence())
        assertTrue(samples.isNotEmpty(), "replay must emit attitude samples")
        val last = samples.last()
        assertEquals(90.0, Math.toDegrees(last.headingRad.toDouble()), 0.5, "heading")
        assertEquals(0.0, Math.toDegrees(last.rollRad.toDouble()), 0.5, "roll")
    }

    @Test
    fun `replay is deterministic`() {
        // The property that makes a recorded sail a regression test rather than an anecdote.
        val recording = syntheticRecording(137f, 3.0)
        val a = ReplayRunner(MahonyAhrs(kp = 2f, ki = 0.1f)).run(recording.asSequence())
        val b = ReplayRunner(MahonyAhrs(kp = 2f, ki = 0.1f)).run(recording.asSequence())
        assertEquals(a, b, "same recording and configuration must give the same trace")
    }

    @Test
    fun `replay through a text round trip gives the same answer`() {
        // Guards the format against silently losing precision: replaying the parsed text
        // must match replaying the in-memory records.
        val recording = syntheticRecording(212f, 3.0)
        val direct = ReplayRunner(MahonyAhrs(kp = 2f, ki = 0f)).run(recording.asSequence())
        val text = recording.joinToString("\n") { RecordingFormat.format(it) }
        val viaText = ReplayRunner(MahonyAhrs(kp = 2f, ki = 0f))
            .run(RecordingFormat.parseAll(text.lineSequence()))
        assertEquals(direct, viaText)
    }

    @Test
    fun `hard-iron strategies are comparable on one recording`() {
        // The point of storing raw field plus HAL estimate: replay the same sail both ways.
        // Here a known 10 µT offset is injected on the body X axis and reported as the HAL
        // estimate, so subtracting it should recover the true heading and ignoring it
        // should not.
        val base = syntheticRecording(0f, 5.0)
        val offset = 10f
        val withBias = base.map {
            if (it is MagRecord) it.copy(x = it.x + offset, biasX = offset) else it
        }

        val uncorrected = ReplayRunner(MahonyAhrs(kp = 2f, ki = 0f), HardIronStrategy.None)
            .run(withBias.asSequence()).last()
        val corrected = ReplayRunner(MahonyAhrs(kp = 2f, ki = 0f), HardIronStrategy.HalEstimate)
            .run(withBias.asSequence()).last()

        assertEquals(0.0, Math.toDegrees(corrected.headingRad.toDouble()), 0.5,
            "subtracting the known bias must recover true heading")
        val uncorrectedErr = abs(Math.toDegrees(uncorrected.headingRad.toDouble()).let {
            if (it > 180) 360 - it else it
        })
        assertTrue(uncorrectedErr > 5.0,
            "ignoring a 10 µT hard iron must visibly skew heading, got ${uncorrectedErr}°")
    }

    @Test
    fun `mount rotation is applied to the replay output`() {
        // Phone mounted 90° off the centreline. Applying the mount outside the filter is
        // what lets a recording be replayed against a corrected calibration without
        // re-recording.
        //
        // Note the sign: a mount yaw of +γ shifts the reported heading by **−γ**, so +90°
        // here reads as 270°. That is not a quirk of this test — `R_D_V` maps vehicle into
        // device and is applied on the right of `R_W_V = R_W_D · R_D_V`, so rotating the
        // vehicle frame one way rotates the reported bow the other. `calibrateAzimuth`
        // already relies on exactly this relationship: it adds `currentHeading − gpsHeading`
        // to γ in order to *reduce* the heading by that amount.
        val recording = syntheticRecording(0f, 5.0)
        val mount = DeviceCalibration.composeZXZ(0f, 0f, 90f)
        val rotated = ReplayRunner(MahonyAhrs(kp = 2f, ki = 0f), mountRotation = mount)
            .run(recording.asSequence()).last()
        val plain = ReplayRunner(MahonyAhrs(kp = 2f, ki = 0f))
            .run(recording.asSequence()).last()

        val delta = Math.toDegrees((rotated.headingRad - plain.headingRad).toDouble())
        val wrapped = ((delta % 360) + 360) % 360
        assertEquals(270.0, wrapped, 1.0, "mount yaw +γ must shift reported heading by −γ")
    }

    @Test
    fun `fixes are collected but do not emit attitude samples`() {
        val recording = syntheticRecording(0f, 1.0) +
            FixRecord(2_000_000_000L, 59.0, 18.0, speedMps = 2.5f)
        val runner = ReplayRunner(MahonyAhrs(kp = 2f, ki = 0f))
        val samples = runner.run(recording.asSequence())
        assertEquals(1, runner.fixes.size, "the fix must be captured for M4")
        assertEquals(2.5f, runner.fixes[0].speedMps)
        assertTrue(samples.all { it.timestampNs < 2_000_000_000L },
            "only gyro ticks advance the filter, so no sample comes from the fix")
    }

    @Test
    fun `out-of-order records are left to the filter's own guards`() {
        // The runner must not silently sort: delivery pathology is worth seeing, and §7's
        // guards already handle it. This asserts the replay survives it without exploding.
        val recording = syntheticRecording(45f, 2.0).toMutableList()
        val moved = recording.removeAt(30)
        recording.add(moved) // one record now arrives far too late
        val samples = ReplayRunner(MahonyAhrs(kp = 2f, ki = 0f)).run(recording.asSequence())
        assertTrue(samples.isNotEmpty())
        assertTrue(samples.last().headingRad.isFinite(), "a late record must not produce NaN")
    }
}
