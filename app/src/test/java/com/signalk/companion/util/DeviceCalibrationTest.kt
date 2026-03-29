package com.signalk.companion.util

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import kotlin.math.*

class DeviceCalibrationTest {

    private val EPSILON = 1e-5f

    private fun assertMatrixEquals(expected: FloatArray, actual: FloatArray, tolerance: Float = EPSILON) {
        assertEquals(expected.size, actual.size, "Matrix sizes differ")
        for (i in expected.indices) {
            assertEquals(expected[i], actual[i], tolerance, "Mismatch at index $i")
        }
    }

    private fun assertAngleEquals(expectedDeg: Float, actualDeg: Float, tolerance: Float = 0.1f) {
        assertEquals(expectedDeg, actualDeg, tolerance, "Angle mismatch")
    }

    /** Normalize angle to [0, 360) for heading comparisons. */
    private fun normalizeHeading(deg: Float): Float {
        var d = deg % 360f
        if (d < 0) d += 360f
        return d
    }

    private fun assertHeadingEquals(expectedDeg: Float, actualDeg: Float, tolerance: Float = 0.5f) {
        val diff = abs(normalizeHeading(expectedDeg) - normalizeHeading(actualDeg))
        val wrapped = if (diff > 180f) 360f - diff else diff
        assertEquals(0f, wrapped, tolerance, "Heading mismatch: expected $expectedDeg° but was $actualDeg°")
    }

    // --- Identity / zero-angle tests ---

    @Test
    fun `identity matrix decomposes to zero angles`() {
        val identity = DeviceCalibration.IDENTITY_3X3.copyOf()
        val (rz, ry, rx) = DeviceCalibration.decomposeZYX(identity)
        assertAngleEquals(0f, rz)
        assertAngleEquals(0f, ry)
        assertAngleEquals(0f, rx)
    }

    @Test
    fun `zero angles compose to identity matrix`() {
        val result = DeviceCalibration.composeZYX(0f, 0f, 0f)
        assertMatrixEquals(DeviceCalibration.IDENTITY_3X3, result)
    }

    // --- Single-axis rotation tests ---

    @Test
    fun `90 degrees around Z axis`() {
        val matrix = DeviceCalibration.composeZYX(rzDeg = 90f, ryDeg = 0f, rxDeg = 0f)
        val (rz, ry, rx) = DeviceCalibration.decomposeZYX(matrix)
        assertAngleEquals(90f, rz)
        assertAngleEquals(0f, ry)
        assertAngleEquals(0f, rx)
    }

    @Test
    fun `minus 90 degrees around Z axis`() {
        val matrix = DeviceCalibration.composeZYX(rzDeg = -90f, ryDeg = 0f, rxDeg = 0f)
        val (rz, ry, rx) = DeviceCalibration.decomposeZYX(matrix)
        assertAngleEquals(-90f, rz)
        assertAngleEquals(0f, ry)
        assertAngleEquals(0f, rx)
    }

    @Test
    fun `45 degrees around Y axis`() {
        val matrix = DeviceCalibration.composeZYX(rzDeg = 0f, ryDeg = 45f, rxDeg = 0f)
        val (rz, ry, rx) = DeviceCalibration.decomposeZYX(matrix)
        assertAngleEquals(0f, rz)
        assertAngleEquals(45f, ry)
        assertAngleEquals(0f, rx)
    }

    @Test
    fun `30 degrees around X axis`() {
        val matrix = DeviceCalibration.composeZYX(rzDeg = 0f, ryDeg = 0f, rxDeg = 30f)
        val (rz, ry, rx) = DeviceCalibration.decomposeZYX(matrix)
        assertAngleEquals(0f, rz)
        assertAngleEquals(0f, ry)
        assertAngleEquals(30f, rx)
    }

    // --- Roundtrip: compose then decompose ---

    @Test
    fun `roundtrip with typical mounting angles`() {
        // Phone in landscape tilted back 35 degrees
        val rzIn = 90f
        val ryIn = 0f
        val rxIn = 35f
        val matrix = DeviceCalibration.composeZYX(rzIn, ryIn, rxIn)
        val (rzOut, ryOut, rxOut) = DeviceCalibration.decomposeZYX(matrix)
        assertAngleEquals(rzIn, rzOut)
        assertAngleEquals(ryIn, ryOut)
        assertAngleEquals(rxIn, rxOut)
    }

    @Test
    fun `roundtrip with all three angles nonzero`() {
        val rzIn = 45f
        val ryIn = -20f
        val rxIn = 10f
        val matrix = DeviceCalibration.composeZYX(rzIn, ryIn, rxIn)
        val (rzOut, ryOut, rxOut) = DeviceCalibration.decomposeZYX(matrix)
        assertAngleEquals(rzIn, rzOut)
        assertAngleEquals(ryIn, ryOut)
        assertAngleEquals(rxIn, rxOut)
    }

    @Test
    fun `roundtrip with 180 degrees Z`() {
        val rzIn = 180f
        val ryIn = 0f
        val rxIn = 0f
        val matrix = DeviceCalibration.composeZYX(rzIn, ryIn, rxIn)
        val (rzOut, ryOut, rxOut) = DeviceCalibration.decomposeZYX(matrix)
        assertAngleEquals(rzIn, rzOut)
        assertAngleEquals(ryIn, ryOut)
        assertAngleEquals(rxIn, rxOut)
    }

    @Test
    fun `roundtrip with negative angles`() {
        val rzIn = -45f
        val ryIn = -30f
        val rxIn = -15f
        val matrix = DeviceCalibration.composeZYX(rzIn, ryIn, rxIn)
        val (rzOut, ryOut, rxOut) = DeviceCalibration.decomposeZYX(matrix)
        assertAngleEquals(rzIn, rzOut)
        assertAngleEquals(ryIn, ryOut)
        assertAngleEquals(rxIn, rxOut)
    }

    // --- Matrix multiply tests ---

    @Test
    fun `multiply by identity gives same matrix`() {
        val m = DeviceCalibration.composeZYX(30f, 20f, 10f)
        val result = DeviceCalibration.multiply3x3(m, DeviceCalibration.IDENTITY_3X3)
        assertMatrixEquals(m, result)
    }

    @Test
    fun `multiply identity by matrix gives same matrix`() {
        val m = DeviceCalibration.composeZYX(30f, 20f, 10f)
        val result = DeviceCalibration.multiply3x3(DeviceCalibration.IDENTITY_3X3, m)
        assertMatrixEquals(m, result)
    }

    @Test
    fun `transpose of rotation matrix is its inverse`() {
        val m = DeviceCalibration.composeZYX(30f, 20f, 10f)
        val mt = DeviceCalibration.transpose3x3(m)
        val product = DeviceCalibration.multiply3x3(m, mt)
        assertMatrixEquals(DeviceCalibration.IDENTITY_3X3, product, tolerance = 1e-4f)
    }

    // --- Calibration from sensor readings ---

    @Test
    fun `calibrate when device aligned with vehicle gives identity`() {
        // Device and vehicle have same orientation in world frame
        val R_W_D = DeviceCalibration.composeZYX(45f, 10f, 5f)
        // Vehicle has same attitude
        val R_W_V = R_W_D.copyOf()
        val R_D_V = DeviceCalibration.computeCalibration(R_W_D, R_W_V)
        assertMatrixEquals(DeviceCalibration.IDENTITY_3X3, R_D_V, tolerance = 1e-4f)
    }

    @Test
    fun `calibrate with known mounting offset`() {
        // Device is rotated 90 degrees around Z relative to vehicle
        val knownMounting = DeviceCalibration.composeZYX(90f, 0f, 0f) // R_D_V
        // Vehicle pointing north, flat
        val R_W_V = DeviceCalibration.IDENTITY_3X3.copyOf()
        // R_W_D = R_W_V * R_V_D = R_W_V * R_D_V^T
        val R_V_D = DeviceCalibration.transpose3x3(knownMounting)
        val R_W_D = DeviceCalibration.multiply3x3(R_W_V, R_V_D)

        val calibrated = DeviceCalibration.computeCalibration(R_W_D, R_W_V)
        val (rz, ry, rx) = DeviceCalibration.decomposeZYX(calibrated)
        assertAngleEquals(90f, rz)
        assertAngleEquals(0f, ry)
        assertAngleEquals(0f, rx)
    }

    @Test
    fun `applying calibration recovers vehicle attitude`() {
        // Given some arbitrary vehicle attitude in world
        val R_W_V = DeviceCalibration.composeZYX(120f, 15f, -5f)
        // Some arbitrary mounting
        val R_D_V = DeviceCalibration.composeZYX(90f, 30f, 0f)
        // Compute what the device sensor would read: R_W_D = R_W_V * R_V_D
        val R_V_D = DeviceCalibration.transpose3x3(R_D_V)
        val R_W_D = DeviceCalibration.multiply3x3(R_W_V, R_V_D)

        // Apply calibration: R_W_V_recovered = R_W_D * R_D_V
        val R_W_V_recovered = DeviceCalibration.multiply3x3(R_W_D, R_D_V)
        assertMatrixEquals(R_W_V, R_W_V_recovered, tolerance = 1e-4f)
    }

    // --- buildFlatHeadingMatrix tests ---

    @Test
    fun `flat heading north gives identity in ENU`() {
        // heading = 0 means north, which is +Y in ENU
        // Vehicle Y-axis points North (+Y in world), X-axis points East (+X in world), Z up
        // That IS the identity rotation in ENU
        val R_W_V = DeviceCalibration.buildFlatHeadingMatrix(0f)
        assertMatrixEquals(DeviceCalibration.IDENTITY_3X3, R_W_V, tolerance = 1e-4f)
    }

    @Test
    fun `flat heading east rotates vehicle 90 CW from above`() {
        // heading = 90 degrees (east)
        // Vehicle Y-forward should point East: (1,0,0) in ENU
        // Vehicle X-right should point South: (0,-1,0) in ENU
        val R_W_V = DeviceCalibration.buildFlatHeadingMatrix(90f)
        // In row-major 3x3:
        // Row 0 = X_vehicle in world = (0, -1, 0) → South
        // Row 1 = Y_vehicle in world = (1, 0, 0) → East
        // Row 2 = Z_vehicle in world = (0, 0, 1) → Up
        //
        // Wait — R_W_V transforms vehicle coords to world coords.
        // Column 0 = where vehicle X-axis points in world
        // Column 1 = where vehicle Y-axis points in world
        // Column 2 = where vehicle Z-axis points in world
        //
        // But Android's row-major convention: R[row][col] stored as R[row*3+col]
        // R_W_V * v_vehicle = v_world. 
        // Column j of R_W_V = R_W_V * e_j = where the j-th vehicle axis points in world.
        // In row-major storage, column j values are at indices [j, j+3, j+6].
        //
        // Column 0 (vehicle X/right in world): should point South = (0, -1, 0)
        //   R[0]=0, R[3]=-1, R[6]=0
        // Column 1 (vehicle Y/forward in world): should point East = (1, 0, 0)
        //   R[1]=1, R[4]=0, R[7]=0
        // Column 2 (vehicle Z/up in world): should point Up = (0, 0, 1)
        //   R[2]=0, R[5]=0, R[8]=1
        val expected = floatArrayOf(
             0f, 1f, 0f,
            -1f, 0f, 0f,
             0f, 0f, 1f
        )
        assertMatrixEquals(expected, R_W_V, tolerance = 1e-4f)
    }

    @Test
    fun `flat heading south`() {
        val R_W_V = DeviceCalibration.buildFlatHeadingMatrix(180f)
        // Vehicle Y/forward points South: (0,-1,0) in ENU
        // Vehicle X/right points West:    (-1,0,0) in ENU
        // Column 0 (X in world): (-1, 0, 0) → R[0]=-1, R[3]=0, R[6]=0
        // Column 1 (Y in world): (0, -1, 0) → R[1]=0, R[4]=-1, R[7]=0
        // Column 2 (Z in world): (0, 0, 1)  → R[2]=0, R[5]=0, R[8]=1
        val expected = floatArrayOf(
            -1f,  0f, 0f,
             0f, -1f, 0f,
             0f,  0f, 1f
        )
        assertMatrixEquals(expected, R_W_V, tolerance = 1e-4f)
    }

    // --- Gimbal lock (pitch = ±90°) ---

    @Test
    fun `decompose at positive gimbal lock returns valid angles`() {
        // RY = +90° is gimbal lock. The matrix loses one DOF.
        val matrix = DeviceCalibration.composeZYX(rzDeg = 45f, ryDeg = 90f, rxDeg = 20f)
        val (rz, ry, rx) = DeviceCalibration.decomposeZYX(matrix)
        // At gimbal lock, ry should be 90, and rz+rx combined effect should match
        assertAngleEquals(90f, ry)
        // Recompose and check the matrix matches
        val recomposed = DeviceCalibration.composeZYX(rz, ry, rx)
        assertMatrixEquals(matrix, recomposed, tolerance = 1e-3f)
    }

    @Test
    fun `decompose at negative gimbal lock returns valid angles`() {
        val matrix = DeviceCalibration.composeZYX(rzDeg = 30f, ryDeg = -90f, rxDeg = 10f)
        val (rz, ry, rx) = DeviceCalibration.decomposeZYX(matrix)
        assertAngleEquals(-90f, ry)
        val recomposed = DeviceCalibration.composeZYX(rz, ry, rx)
        assertMatrixEquals(matrix, recomposed, tolerance = 1e-3f)
    }

    // --- Calibration with only pitch+roll (no heading) ---

    @Test
    fun `calibratePitchRoll makes pitch and roll zero`() {
        val existingCalibration = DeviceCalibration.composeZYX(90f, 0f, 0f)
        val R_W_D = DeviceCalibration.composeZYX(0f, 15f, -5f)

        val updated = DeviceCalibration.calibratePitchRoll(R_W_D, existingCalibration)
        val R_W_V_after = DeviceCalibration.multiply3x3(R_W_D, updated)

        // SensorManager.getOrientation: pitch = asin(-R[7]), roll = atan2(-R[6], R[8])
        // Both are zero when R[6] = 0 and R[7] = 0
        assertEquals(0f, R_W_V_after[7], 1e-3f)
        assertEquals(0f, R_W_V_after[6], 1e-3f)
    }

    @Test
    fun `calibratePitchRoll preserves vehicle heading`() {
        val existingCalibration = DeviceCalibration.composeZYX(90f, 0f, 0f)
        val R_W_D = DeviceCalibration.composeZYX(0f, 15f, -5f)

        val R_W_V_before = DeviceCalibration.multiply3x3(R_W_D, existingCalibration)
        val headingBefore = atan2(R_W_V_before[1].toDouble(), R_W_V_before[4].toDouble()).toFloat()

        val updated = DeviceCalibration.calibratePitchRoll(R_W_D, existingCalibration)
        val R_W_V_after = DeviceCalibration.multiply3x3(R_W_D, updated)
        val headingAfter = atan2(R_W_V_after[1].toDouble(), R_W_V_after[4].toDouble()).toFloat()

        assertEquals(headingBefore, headingAfter, 1e-3f)
    }

    @Test
    fun `full calibration end-to-end scenario`() {
        // Scenario: Phone mounted in landscape (90° Z), tilted back 30° on dashboard
        // Vehicle heading north, flat

        // The "real" mounting: R_D_V
        val realMounting = DeviceCalibration.composeZYX(90f, 0f, 30f)

        // Vehicle attitude: heading north, flat → identity
        val R_W_V_actual = DeviceCalibration.IDENTITY_3X3.copyOf()

        // What sensor reads: R_W_D = R_W_V * R_D_V^(-1) = I * R_D_V^T
        val R_W_D = DeviceCalibration.transpose3x3(realMounting)

        // GPS says heading = 0 (north)
        val R_W_V_desired = DeviceCalibration.buildFlatHeadingMatrix(0f)

        // Calibrate
        val calibrated = DeviceCalibration.computeCalibration(R_W_D, R_W_V_desired)

        // Should recover the real mounting angles
        val (rz, ry, rx) = DeviceCalibration.decomposeZYX(calibrated)
        assertAngleEquals(90f, rz)
        assertAngleEquals(0f, ry)
        assertAngleEquals(30f, rx)

        // Verify: applying calibration to sensor reading gives correct vehicle attitude
        val recovered = DeviceCalibration.multiply3x3(R_W_D, calibrated)
        assertMatrixEquals(R_W_V_actual, recovered, tolerance = 1e-3f)
    }

    // --- Azimuth-only calibration ---

    @Test
    fun `calibrateAzimuth produces correct heading`() {
        // Device mounted landscape (90° Z), flat vehicle heading north
        val mounting = DeviceCalibration.composeZYX(90f, 0f, 0f)
        val R_W_V_true = DeviceCalibration.buildFlatHeadingMatrix(0f) // heading north
        // R_W_D = R_W_V * R_D_V^(-1) = R_W_V * R_D_V^T
        val R_W_D = DeviceCalibration.multiply3x3(R_W_V_true, DeviceCalibration.transpose3x3(mounting))

        // Existing calibration has wrong heading (30° off)
        val existingCal = DeviceCalibration.composeZYX(60f, 0f, 0f)

        // GPS says heading = 0°
        val updated = DeviceCalibration.calibrateAzimuth(R_W_D, existingCal, 0f)
        val R_W_V_after = DeviceCalibration.multiply3x3(R_W_D, updated)

        // Heading should be 0° (north)
        val heading = Math.toDegrees(
            atan2(R_W_V_after[1].toDouble(), R_W_V_after[4].toDouble())
        ).toFloat()
        assertAngleEquals(0f, heading, tolerance = 0.5f)
    }

    @Test
    fun `calibrateAzimuth preserves tilt when vehicle is tilted by waves`() {
        // Device mounted portrait (rx=90°), vehicle heading east
        val mounting = DeviceCalibration.composeZYX(0f, 0f, 90f)
        val R_W_V_flat = DeviceCalibration.buildFlatHeadingMatrix(90f)
        val R_W_D_calm = DeviceCalibration.multiply3x3(
            R_W_V_flat, DeviceCalibration.transpose3x3(mounting)
        )

        // Now vehicle is tilted 10° by waves (pitch). Sensor reading changes.
        // Wave tilt: rotate vehicle around its X axis (roll in world terms)
        val waveTilt = DeviceCalibration.composeZYX(0f, 0f, 10f)
        val R_W_V_wavy = DeviceCalibration.multiply3x3(waveTilt, R_W_V_flat)
        val R_W_D_wavy = DeviceCalibration.multiply3x3(
            R_W_V_wavy, DeviceCalibration.transpose3x3(mounting)
        )

        // Before azimuth calibration: vehicle attitude with existing mounting
        val R_W_V_before = DeviceCalibration.multiply3x3(R_W_D_wavy, mounting)
        val pitchBefore = asin(-R_W_V_before[7].toDouble()).toFloat()
        val rollBefore = atan2(-R_W_V_before[6].toDouble(), R_W_V_before[8].toDouble()).toFloat()

        // Calibrate azimuth with GPS heading = 90° (east)
        val updated = DeviceCalibration.calibrateAzimuth(R_W_D_wavy, mounting, 90f)
        val R_W_V_after = DeviceCalibration.multiply3x3(R_W_D_wavy, updated)

        // Pitch and roll should be preserved
        val pitchAfter = asin(-R_W_V_after[7].toDouble()).toFloat()
        val rollAfter = atan2(-R_W_V_after[6].toDouble(), R_W_V_after[8].toDouble()).toFloat()
        assertEquals(pitchBefore, pitchAfter, 1e-3f)
        assertEquals(rollBefore, rollAfter, 1e-3f)

        // Heading should now be 90°
        val heading = Math.toDegrees(
            atan2(R_W_V_after[1].toDouble(), R_W_V_after[4].toDouble())
        ).toFloat()
        assertAngleEquals(90f, heading, tolerance = 0.5f)
    }

    @Test
    fun `calibrateAzimuth works with large mounting angles`() {
        // Device mounted nearly upside-down: 45° tilt + 90° twist
        val mounting = DeviceCalibration.composeZYX(45f, 30f, -60f)
        val R_W_V_true = DeviceCalibration.buildFlatHeadingMatrix(270f) // heading west
        val R_W_D = DeviceCalibration.multiply3x3(
            R_W_V_true, DeviceCalibration.transpose3x3(mounting)
        )

        // Existing calibration has correct tilt but wrong heading
        val existingCal = DeviceCalibration.composeZYX(0f, 30f, -60f)

        // GPS says heading = 270°
        val updated = DeviceCalibration.calibrateAzimuth(R_W_D, existingCal, 270f)
        val R_W_V_after = DeviceCalibration.multiply3x3(R_W_D, updated)

        val heading = Math.toDegrees(
            atan2(R_W_V_after[1].toDouble(), R_W_V_after[4].toDouble())
        ).toFloat()
        assertHeadingEquals(270f, heading)
    }

    @Test
    fun `sequential calibration - pitch roll first then azimuth`() {
        // The user's workflow: calibrate tilt at dock, then azimuth underway

        // Device mounted landscape (90° Z), tilted back 20° (rx=20°)
        val realMounting = DeviceCalibration.composeZYX(90f, 0f, 20f)

        // Vehicle heading north, flat, at the dock
        val R_W_V_dock = DeviceCalibration.buildFlatHeadingMatrix(0f)
        val R_W_D_dock = DeviceCalibration.multiply3x3(
            R_W_V_dock, DeviceCalibration.transpose3x3(realMounting)
        )

        // Step 1: Calibrate pitch/roll (heading from magnetometer may be wrong)
        val identityCal = DeviceCalibration.IDENTITY_3X3.copyOf()
        val afterPitchRoll = DeviceCalibration.calibratePitchRoll(R_W_D_dock, identityCal)

        // Verify pitch/roll are zero
        val R_W_V_step1 = DeviceCalibration.multiply3x3(R_W_D_dock, afterPitchRoll)
        assertEquals(0f, R_W_V_step1[7], 1e-3f) // pitch component
        assertEquals(0f, R_W_V_step1[6], 1e-3f) // roll component

        // Step 2: Now underway heading east, vehicle tilted 5° by waves
        val waveTilt = DeviceCalibration.composeZYX(0f, 5f, 0f) // 5° pitch from waves
        val R_W_V_sea = DeviceCalibration.multiply3x3(
            waveTilt,
            DeviceCalibration.buildFlatHeadingMatrix(90f)
        )
        val R_W_D_sea = DeviceCalibration.multiply3x3(
            R_W_V_sea, DeviceCalibration.transpose3x3(realMounting)
        )

        // Calibrate azimuth with GPS = 90° (east)
        val afterAzimuth = DeviceCalibration.calibrateAzimuth(R_W_D_sea, afterPitchRoll, 90f)
        val R_W_V_final = DeviceCalibration.multiply3x3(R_W_D_sea, afterAzimuth)

        // Heading should be 90°
        val heading = Math.toDegrees(
            atan2(R_W_V_final[1].toDouble(), R_W_V_final[4].toDouble())
        ).toFloat()
        assertHeadingEquals(90f, heading)

        // Vehicle tilt should be preserved. The tilt angle is the angle between
        // vehicle Z axis and world Z axis: acos(R_W_V[8]) (Z-Z component).
        // Row 2 of R_W_V is unchanged by horizontal rotation, so tilt is preserved.
        val tiltBefore = Math.toDegrees(
            acos(R_W_V_sea[8].toDouble().coerceIn(-1.0, 1.0))
        ).toFloat()
        val tiltAfter = Math.toDegrees(
            acos(R_W_V_final[8].toDouble().coerceIn(-1.0, 1.0))
        ).toFloat()
        assertEquals(tiltBefore, tiltAfter, 1f)
        // Tilt should be approximately 5° (the wave tilt)
        assertAngleEquals(5f, tiltAfter, tolerance = 1f)
    }

    // --- Common mounting positions ---
    // Device frame: X=right, Y=top, Z=screen
    // Vehicle frame: X=starboard, Y=forward, Z=up
    // For each mounting, we verify the calibration matrix maps device axes to
    // the correct vehicle axes by checking R_W_V = R_W_D * R_D_V = identity
    // when vehicle heading = north and vehicle is flat.

    /**
     * Verify a mounting by simulating a vehicle heading north + flat.
     * R_W_V = identity (heading north, flat).
     * R_W_D = R_W_V * R_D_V^T = R_D_V^T (since R_W_V = I).
     * Then: R_W_D * R_D_V should recover identity.
     */
    private fun verifyMounting(rzDeg: Float, ryDeg: Float, rxDeg: Float) {
        val R_D_V = DeviceCalibration.composeZYX(rzDeg, ryDeg, rxDeg)
        val R_W_D = DeviceCalibration.transpose3x3(R_D_V) // vehicle heading north, flat
        val R_W_V = DeviceCalibration.multiply3x3(R_W_D, R_D_V)
        assertMatrixEquals(DeviceCalibration.IDENTITY_3X3, R_W_V, tolerance = 1e-4f)
    }

    @Test
    fun `mounting - flat portrait charging port aft`() {
        // Device X=starboard, Y=forward, Z=up → identity
        verifyMounting(0f, 0f, 0f)
    }

    @Test
    fun `mounting - flat landscape charging port port`() {
        // Phone rotated 90° CW from above: top→starboard, right→aft
        verifyMounting(90f, 0f, 0f)
    }

    @Test
    fun `mounting - vertical HUD charging port down`() {
        // Screen faces helm (aft), top faces up, right faces starboard
        val R_D_V = DeviceCalibration.composeZYX(0f, 0f, -90f)
        // Device Z (screen) should map to -Y vehicle (aft)
        // Column 1 of R_D_V = Y_vehicle in device coords = (0, 0, -1)
        assertEquals(0f, R_D_V[1], 1e-5f)
        assertEquals(0f, R_D_V[4], 1e-5f)
        assertEquals(-1f, R_D_V[7], 1e-5f)
        verifyMounting(0f, 0f, -90f)
    }

    @Test
    fun `mounting - vertical HUD charging port up`() {
        // Upside-down HUD: screen faces helm, top faces down
        verifyMounting(180f, 0f, -90f)
    }

    @Test
    fun `mounting - vertical starboard screen to port charging port down`() {
        // Phone on starboard wall, screen facing inward (port), top up
        // Hits gimbal lock at RY=90°
        verifyMounting(90f, 90f, 0f)
    }

    @Test
    fun `mounting - vertical starboard screen to port charging port forward`() {
        // Same wall mount but phone rotated: charging port faces bow
        // Hits gimbal lock at RY=90°
        verifyMounting(180f, 90f, 0f)
    }

    // --- Euler angle non-independence at large tilt ---
    // Scenarios 3 and 5 are the same physical tilt (vertical, charging port down)
    // with different horizontal orientation. Despite having completely different
    // Euler angles, the calibration workflow (pitch/roll then azimuth) works
    // identically for both because it operates on full matrices, not Euler components.

    /**
     * Helper: simulate the full calibration workflow for any mounting.
     * 1. At dock: calibrate pitch/roll with vehicle flat, heading north
     * 2. Underway: calibrate azimuth with GPS heading east, vehicle tilted 8° by waves
     * Verify heading is correct and wave tilt is preserved.
     */
    private fun verifyCalibrationWorkflow(realMounting: FloatArray, gpsHeading: Float = 90f) {
        // Step 1: At dock, vehicle flat, heading north
        val R_W_V_dock = DeviceCalibration.buildFlatHeadingMatrix(0f)
        val R_W_D_dock = DeviceCalibration.multiply3x3(
            R_W_V_dock, DeviceCalibration.transpose3x3(realMounting)
        )
        val afterPitchRoll = DeviceCalibration.calibratePitchRoll(
            R_W_D_dock, DeviceCalibration.IDENTITY_3X3.copyOf()
        )

        // Verify pitch/roll are zero after step 1
        val R_W_V_step1 = DeviceCalibration.multiply3x3(R_W_D_dock, afterPitchRoll)
        assertEquals(0f, R_W_V_step1[6], 1e-3f) // roll component
        assertEquals(0f, R_W_V_step1[7], 1e-3f) // pitch component

        // Step 2: Underway heading east, 8° wave tilt (pitch)
        val waveTilt = DeviceCalibration.composeZYX(0f, 8f, 0f)
        val R_W_V_sea = DeviceCalibration.multiply3x3(
            waveTilt, DeviceCalibration.buildFlatHeadingMatrix(gpsHeading)
        )
        val R_W_D_sea = DeviceCalibration.multiply3x3(
            R_W_V_sea, DeviceCalibration.transpose3x3(realMounting)
        )
        val afterAzimuth = DeviceCalibration.calibrateAzimuth(
            R_W_D_sea, afterPitchRoll, gpsHeading
        )
        val R_W_V_final = DeviceCalibration.multiply3x3(R_W_D_sea, afterAzimuth)

        // Heading should match GPS
        val heading = Math.toDegrees(
            atan2(R_W_V_final[1].toDouble(), R_W_V_final[4].toDouble())
        ).toFloat()
        assertHeadingEquals(gpsHeading, heading)

        // Wave tilt should be preserved
        val tilt = Math.toDegrees(
            acos(R_W_V_final[8].toDouble().coerceIn(-1.0, 1.0))
        ).toFloat()
        assertAngleEquals(8f, tilt, tolerance = 1f)
    }

    @Test
    fun `workflow works for vertical HUD mounting (scenario 3)`() {
        val mounting = DeviceCalibration.composeZYX(0f, 0f, -90f)
        verifyCalibrationWorkflow(mounting)
    }

    @Test
    fun `workflow works for vertical starboard mounting (scenario 5)`() {
        val mounting = DeviceCalibration.composeZYX(90f, 90f, 0f)
        verifyCalibrationWorkflow(mounting)
    }

    @Test
    fun `scenarios 3 and 5 differ only by horizontal rotation`() {
        // Both mountings are "phone vertical, charging port down".
        // They differ by 90° horizontal rotation.
        // The R_D_V matrices should be related by a pure rotation around vehicle Z.
        val R_D_V_3 = DeviceCalibration.composeZYX(0f, 0f, -90f)
        val R_D_V_5 = DeviceCalibration.composeZYX(90f, 90f, 0f)

        // Rz(90°) * R_D_V_3 should map the same physical tilt, rotated horizontally.
        // In vehicle-to-device convention: rotating device horizontally means
        // the device sees vehicle axes rotated by -90° around Z.
        // So R_D_V_5 = R_D_V_3 * Rz_vehicle(-90°)^T = R_D_V_3 * Rz_vehicle(90°)
        //
        // Wait: if we rotate the device 90° CW (from above) relative to vehicle,
        // vehicle axes in the new device frame = old_device_rotation * Rz(-90°) in vehicle frame
        // R_D_V_new = R_D_V_old * Rz_vehicle(-90°)
        val Rz_neg90 = DeviceCalibration.buildFlatHeadingMatrix(-90f)
        val expected = DeviceCalibration.multiply3x3(R_D_V_3, Rz_neg90)

        assertMatrixEquals(expected, R_D_V_5, tolerance = 1e-4f)
    }

    @Test
    fun `azimuth correction can change non-RZ Euler angles for tilted mounting`() {
        // Vertical HUD mounting (0°, 0°, -90°) with distorted magnetometer at dock
        val mounting = DeviceCalibration.composeZYX(0f, 0f, -90f)

        // At dock: vehicle heading north, flat
        val R_W_D_true = DeviceCalibration.transpose3x3(mounting)

        // Magnetometer adds 30° error
        val magError = DeviceCalibration.buildFlatHeadingMatrix(30f)
        val R_W_D_distorted = DeviceCalibration.multiply3x3(magError, R_W_D_true)

        // Pitch/roll calibration at dock — correct tilt, wrong heading (30° off)
        val afterPitchRoll = DeviceCalibration.calibratePitchRoll(
            R_W_D_distorted, DeviceCalibration.IDENTITY_3X3.copyOf()
        )
        val (rz1, ry1, rx1) = DeviceCalibration.decomposeZYX(afterPitchRoll)

        // GPS says heading = 0° (north)
        val afterAzimuth = DeviceCalibration.calibrateAzimuth(
            R_W_D_distorted, afterPitchRoll, 0f
        )
        val (rz2, ry2, rx2) = DeviceCalibration.decomposeZYX(afterAzimuth)

        // For this vertical mounting (RX=-90°), the heading correction appears
        // in RY, NOT in RZ — demonstrating that Euler angles don't decompose
        // into independent "heading" and "tilt" components for tilted mountings.
        assertAngleEquals(0f, rz1, tolerance = 0.1f)
        assertAngleEquals(0f, rz2, tolerance = 0.1f) // RZ unchanged!
        assertAngleEquals(0f, ry1, tolerance = 0.1f)
        assertAngleEquals(30f, ry2, tolerance = 0.5f) // Heading correction appears in RY
        assertAngleEquals(-90f, rx1, tolerance = 0.1f)
        assertAngleEquals(-90f, rx2, tolerance = 0.1f) // RX unchanged

        // Vehicle attitude is correct regardless
        val R_W_V = DeviceCalibration.multiply3x3(R_W_D_distorted, afterAzimuth)
        val heading = Math.toDegrees(
            atan2(R_W_V[1].toDouble(), R_W_V[4].toDouble())
        ).toFloat()
        assertHeadingEquals(0f, heading)
        assertEquals(0f, R_W_V[6], 1e-3f)
        assertEquals(0f, R_W_V[7], 1e-3f)
    }
}
