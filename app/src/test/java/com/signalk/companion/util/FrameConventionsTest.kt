package com.signalk.companion.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Executable form of the normative reference poses in `frame-conventions.md` §10.
 *
 * These are the acceptance tests for any attitude implementation in this project. A change
 * that breaks a row here is a frame regression, not a failing test to be adjusted — fix the
 * code, or change the conventions document first, deliberately.
 *
 * World frame is ENU (X = East, Y = North, Z = Up); vehicle frame is starboard-bow-up.
 * R_W_V is row-major, so column j is the world-frame image of vehicle axis j.
 */
class FrameConventionsTest {

    private val tol = 1e-4f

    /**
     * Build R_W_V from the world-frame images of the vehicle's starboard, bow and up axes.
     * They are the *columns* of the matrix; row-major storage puts column j at [j, j+3, j+6].
     */
    private fun matrixFromColumns(
        starboard: FloatArray,
        bow: FloatArray,
        up: FloatArray
    ): FloatArray = floatArrayOf(
        starboard[0], bow[0], up[0],
        starboard[1], bow[1], up[1],
        starboard[2], bow[2], up[2]
    )

    private fun deg(radians: Float): Float = Math.toDegrees(radians.toDouble()).toFloat()

    private fun assertAngleEquals(expectedDeg: Float, actualRad: Float, name: String) {
        val actualDeg = deg(actualRad)
        // Compare on the circle so 0° and 360° are the same answer.
        val diff = abs(((expectedDeg - actualDeg + 540f) % 360f) - 180f)
        assertTrue(
            diff < 0.01f,
            "$name: expected ${expectedDeg}°, got ${actualDeg}°"
        )
    }

    private fun cosd(d: Double) = cos(Math.toRadians(d)).toFloat()
    private fun sind(d: Double) = sin(Math.toRadians(d)).toFloat()

    // ---------------------------------------------------------------- reference poses

    @Test
    fun `level bow north is heading 0 pitch 0 roll 0`() {
        val R = matrixFromColumns(
            starboard = floatArrayOf(1f, 0f, 0f),   // East
            bow = floatArrayOf(0f, 1f, 0f),         // North
            up = floatArrayOf(0f, 0f, 1f)           // Up
        )
        val a = DeviceCalibration.extractNauticalAngles(R)
        assertAngleEquals(0f, a.headingRad, "heading")
        assertAngleEquals(0f, a.pitchRad, "pitch")
        assertAngleEquals(0f, a.rollRad, "roll")
    }

    @Test
    fun `level bow east is heading 90`() {
        val R = matrixFromColumns(
            starboard = floatArrayOf(0f, -1f, 0f),  // South
            bow = floatArrayOf(1f, 0f, 0f),         // East
            up = floatArrayOf(0f, 0f, 1f)
        )
        val a = DeviceCalibration.extractNauticalAngles(R)
        assertAngleEquals(90f, a.headingRad, "heading")
        assertAngleEquals(0f, a.pitchRad, "pitch")
        assertAngleEquals(0f, a.rollRad, "roll")
    }

    @Test
    fun `level bow south is heading 180`() {
        val R = matrixFromColumns(
            starboard = floatArrayOf(-1f, 0f, 0f),  // West
            bow = floatArrayOf(0f, -1f, 0f),        // South
            up = floatArrayOf(0f, 0f, 1f)
        )
        val a = DeviceCalibration.extractNauticalAngles(R)
        assertAngleEquals(180f, a.headingRad, "heading")
    }

    @Test
    fun `level bow west is heading 270`() {
        val R = matrixFromColumns(
            starboard = floatArrayOf(0f, 1f, 0f),   // North
            bow = floatArrayOf(-1f, 0f, 0f),        // West
            up = floatArrayOf(0f, 0f, 1f)
        )
        val a = DeviceCalibration.extractNauticalAngles(R)
        assertAngleEquals(270f, a.headingRad, "heading")
    }

    @Test
    fun `heel to starboard is positive roll`() {
        // Bow North, heeled 20° to starboard: the starboard axis dips below horizontal.
        val R = matrixFromColumns(
            starboard = floatArrayOf(cosd(20.0), 0f, -sind(20.0)),
            bow = floatArrayOf(0f, 1f, 0f),
            up = floatArrayOf(sind(20.0), 0f, cosd(20.0))
        )
        val a = DeviceCalibration.extractNauticalAngles(R)
        assertAngleEquals(0f, a.headingRad, "heading")
        assertAngleEquals(0f, a.pitchRad, "pitch")
        assertAngleEquals(20f, a.rollRad, "roll")
        assertTrue(a.rollRad > 0f, "heel to starboard must give POSITIVE roll")
    }

    @Test
    fun `bow up is positive pitch`() {
        val R = matrixFromColumns(
            starboard = floatArrayOf(1f, 0f, 0f),
            bow = floatArrayOf(0f, cosd(10.0), sind(10.0)),
            up = floatArrayOf(0f, -sind(10.0), cosd(10.0))
        )
        val a = DeviceCalibration.extractNauticalAngles(R)
        assertAngleEquals(0f, a.headingRad, "heading")
        assertAngleEquals(10f, a.pitchRad, "pitch")
        assertAngleEquals(0f, a.rollRad, "roll")
        assertTrue(a.pitchRad > 0f, "bow up must give POSITIVE pitch")
    }

    @Test
    fun `heading increases north east south west`() {
        val headings = listOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f).map { h ->
            deg(DeviceCalibration.extractNauticalAngles(
                DeviceCalibration.buildFlatHeadingMatrix(h)
            ).headingRad)
        }
        for (i in 1 until headings.size) {
            assertTrue(
                headings[i] > headings[i - 1],
                "heading must increase clockwise from North: $headings"
            )
        }
    }

    // ------------------------------------------------------------------- invariants

    @Test
    fun `reference poses are right handed`() {
        val poses = listOf(
            DeviceCalibration.IDENTITY_3X3,
            DeviceCalibration.buildFlatHeadingMatrix(37f),
            DeviceCalibration.composeZXZ(15f, 80f, 200f),
            matrixFromColumns(
                starboard = floatArrayOf(cosd(20.0), 0f, -sind(20.0)),
                bow = floatArrayOf(0f, 1f, 0f),
                up = floatArrayOf(sind(20.0), 0f, cosd(20.0))
            )
        )
        for (R in poses) {
            // col0 × col1 == col2
            val c0 = floatArrayOf(R[0], R[3], R[6])
            val c1 = floatArrayOf(R[1], R[4], R[7])
            val c2 = floatArrayOf(R[2], R[5], R[8])
            val cross = floatArrayOf(
                c0[1] * c1[2] - c0[2] * c1[1],
                c0[2] * c1[0] - c0[0] * c1[2],
                c0[0] * c1[1] - c0[1] * c1[0]
            )
            for (i in 0..2) {
                assertEquals(c2[i], cross[i], tol, "col0 × col1 must equal col2 (component $i)")
            }
        }
    }

    // ------------------------------------------------------------ angle normalization

    @Test
    fun `wrapTo2Pi maps into range`() {
        val twoPi = (2 * PI).toFloat()
        assertEquals(0f, DeviceCalibration.wrapTo2Pi(0f), tol)
        assertEquals(1f, DeviceCalibration.wrapTo2Pi(1f), tol)
        assertEquals(twoPi - 1f, DeviceCalibration.wrapTo2Pi(-1f), tol)
        assertEquals(0f, DeviceCalibration.wrapTo2Pi(twoPi), tol)
        assertEquals(1f, DeviceCalibration.wrapTo2Pi(1f + 10f * twoPi), tol)

        for (x in listOf(-100f, -6.5f, -0.0001f, 0f, 3f, 6.28f, 1000f)) {
            val w = DeviceCalibration.wrapTo2Pi(x)
            assertTrue(w >= 0f && w < twoPi, "wrapTo2Pi($x) = $w out of [0, 2π)")
        }
    }

    @Test
    fun `wrapTo2Pi terminates on non-finite input`() {
        // The previous while-loop implementation hung forever here (audit A5).
        assertTrue(DeviceCalibration.wrapTo2Pi(Float.NaN).isNaN())
        assertTrue(DeviceCalibration.wrapTo2Pi(Float.POSITIVE_INFINITY).isNaN())
        assertTrue(DeviceCalibration.wrapTo2Pi(Float.NEGATIVE_INFINITY).isNaN())
    }

    // -------------------------------------------------------------------- rate of turn

    @Test
    fun `turn to starboard is positive rate of turn`() {
        // Phone mounted aligned with the boat (identity). A turn to starboard is clockwise
        // seen from above, which by the right-hand rule is a NEGATIVE gyro rate about up.
        val gyroTurningStarboard = floatArrayOf(0f, 0f, -0.1f)
        val rot = DeviceCalibration.rateOfTurnFromGyro(
            DeviceCalibration.IDENTITY_3X3, gyroTurningStarboard
        )
        assertTrue(rot > 0f, "turn to starboard must give POSITIVE rate of turn, got $rot")
        assertEquals(0.1f, rot, tol)
    }

    @Test
    fun `turn to port is negative rate of turn`() {
        val gyroTurningPort = floatArrayOf(0f, 0f, 0.1f)
        val rot = DeviceCalibration.rateOfTurnFromGyro(
            DeviceCalibration.IDENTITY_3X3, gyroTurningPort
        )
        assertTrue(rot < 0f, "turn to port must give NEGATIVE rate of turn, got $rot")
    }

    @Test
    fun `rate of turn accounts for mount orientation`() {
        // Phone mounted vertically against a bulkhead: R_D_V = Rx(90°), so the device's Y
        // axis lies along the vehicle's up axis and the device's Z axis lies horizontally.
        //
        // This is the case that exposes the frame half of audit A1. A turn to starboard now
        // registers entirely on the device's *Y* gyro axis, and device Z reads exactly zero
        // — so the old "rate of turn = raw device Z" was not merely mis-signed here, it was
        // blind to the turn altogether.
        val bulkheadMount = DeviceCalibration.composeZXZ(0f, 90f, 0f)

        // Physical turn to starboard for this mount, in device axes.
        val gyro_D = floatArrayOf(0f, 0.1f, 0f)
        val rot = DeviceCalibration.rateOfTurnFromGyro(bulkheadMount, gyro_D)

        assertTrue(rot > 0f, "turn to starboard must give POSITIVE rate of turn, got $rot")
        assertEquals(0.1f, rot, tol)
        assertEquals(
            0f, gyro_D[2], tol,
            "precondition: device Z sees nothing of this turn"
        )
    }

    @Test
    fun `rate of turn ignores pitch and roll rates`() {
        // Pure roll and pure pitch rates must not leak into rate of turn.
        val identity = DeviceCalibration.IDENTITY_3X3
        assertEquals(0f, DeviceCalibration.rateOfTurnFromGyro(identity, floatArrayOf(0.5f, 0f, 0f)), tol)
        assertEquals(0f, DeviceCalibration.rateOfTurnFromGyro(identity, floatArrayOf(0f, 0.5f, 0f)), tol)
    }
}
