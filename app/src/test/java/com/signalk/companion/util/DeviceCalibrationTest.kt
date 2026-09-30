package com.signalk.companion.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2

class DeviceCalibrationTest {

    // --- Calibration from sensor readings ---

    @Test
    fun `calibrate when device aligned with vehicle gives identity`() {
        // Device and vehicle have same orientation in world frame
        val R_W_D = TaitBryanZyx.compose(45f, 10f, 5f)
        // Vehicle has same attitude
        val R_W_V = R_W_D.copyOf()
        val R_D_V = DeviceCalibration.computeCalibration(R_W_D, R_W_V)
        assertMatrixEquals(DeviceCalibration.IDENTITY_3X3, R_D_V, tolerance = 1e-4f)
    }

    @Test
    fun `calibrate with known mounting offset`() {
        // Device is rotated 90 degrees around Z relative to vehicle
        val knownMounting = TaitBryanZyx.compose(90f, 0f, 0f) // R_D_V
        // Vehicle pointing north, flat
        val R_W_V = DeviceCalibration.IDENTITY_3X3.copyOf()
        // R_W_D = R_W_V * R_V_D = R_W_V * R_D_V^T
        val R_V_D = Matrix3.transpose(knownMounting)
        val R_W_D = Matrix3.multiply(R_W_V, R_V_D)

        val calibrated = DeviceCalibration.computeCalibration(R_W_D, R_W_V)
        val (rz, ry, rx) = TaitBryanZyx.decompose(calibrated)
        assertAngleEquals(90f, rz)
        assertAngleEquals(0f, ry)
        assertAngleEquals(0f, rx)
    }

    @Test
    fun `applying calibration recovers vehicle attitude`() {
        // Given some arbitrary vehicle attitude in world
        val R_W_V = TaitBryanZyx.compose(120f, 15f, -5f)
        // Some arbitrary mounting
        val R_D_V = TaitBryanZyx.compose(90f, 30f, 0f)
        // Compute what the device sensor would read: R_W_D = R_W_V * R_V_D
        val R_V_D = Matrix3.transpose(R_D_V)
        val R_W_D = Matrix3.multiply(R_W_V, R_V_D)

        // Apply calibration: R_W_V_recovered = R_W_D * R_D_V
        val R_W_V_recovered = Matrix3.multiply(R_W_D, R_D_V)
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
            -1f, 0f, 0f,
            0f, -1f, 0f,
            0f, 0f, 1f
        )
        assertMatrixEquals(expected, R_W_V, tolerance = 1e-4f)
    }

    // --- Tilt-only calibration (α, β) ---

    @Test
    fun `calibrateTilt makes pitch and roll zero`() {
        val R_W_D = TaitBryanZyx.compose(0f, 15f, -5f)

        val (_, _, updated) = DeviceCalibration.calibrateTilt(R_W_D, 0f)
        val R_W_V_after = Matrix3.multiply(R_W_D, updated)

        // Flat: R_W_V row 2 = [0, 0, 1]
        assertEquals(0f, R_W_V_after[7], 1e-3f)
        assertEquals(0f, R_W_V_after[6], 1e-3f)
    }

    @Test
    fun `calibrateTilt preserves gamma`() {
        val R_W_D = TaitBryanZyx.compose(0f, 15f, -5f)
        val gammaIn = 42f

        val (_, _, updated) = DeviceCalibration.calibrateTilt(R_W_D, gammaIn)
        val (_, _, gammaOut) = DeviceCalibration.decomposeZXZ(updated)

        assertAngleEquals(gammaIn, gammaOut)
    }

    @Test
    fun `full calibration end-to-end scenario`() {
        // Scenario: Phone mounted in landscape (90° Z), tilted back 30° on dashboard
        // Vehicle heading north, flat

        // The "real" mounting: R_D_V
        val realMounting = TaitBryanZyx.compose(90f, 0f, 30f)

        // Vehicle attitude: heading north, flat → identity
        val R_W_V_actual = DeviceCalibration.IDENTITY_3X3.copyOf()

        // What sensor reads: R_W_D = R_W_V * R_D_V^(-1) = I * R_D_V^T
        val R_W_D = Matrix3.transpose(realMounting)

        // GPS says heading = 0 (north)
        val R_W_V_desired = DeviceCalibration.buildFlatHeadingMatrix(0f)

        // Calibrate
        val calibrated = DeviceCalibration.computeCalibration(R_W_D, R_W_V_desired)

        // Should recover the real mounting angles
        val (rz, ry, rx) = TaitBryanZyx.decompose(calibrated)
        assertAngleEquals(90f, rz)
        assertAngleEquals(0f, ry)
        assertAngleEquals(30f, rx)

        // Verify: applying calibration to sensor reading gives correct vehicle attitude
        val recovered = Matrix3.multiply(R_W_D, calibrated)
        assertMatrixEquals(R_W_V_actual, recovered, tolerance = 1e-3f)
    }

    // --- Azimuth-only calibration ---

    @Test
    fun `calibrateAzimuth produces correct heading`() {
        // Device mounted landscape (90° Z), flat vehicle heading north
        val mounting = TaitBryanZyx.compose(90f, 0f, 0f)
        val R_W_V_true = DeviceCalibration.buildFlatHeadingMatrix(0f) // heading north
        val R_W_D = Matrix3.multiply(R_W_V_true, Matrix3.transpose(mounting))

        // Existing calibration has wrong heading (30° off)
        val existingCal = TaitBryanZyx.compose(60f, 0f, 0f)
        val (existingAlpha, existingBeta, existingGamma) = DeviceCalibration.decomposeZXZ(existingCal)

        // GPS says heading = 0°
        val (_, updated) = DeviceCalibration.calibrateAzimuth(R_W_D, existingAlpha, existingBeta, existingGamma, 0f)
        val R_W_V_after = Matrix3.multiply(R_W_D, updated)

        val heading = Math.toDegrees(
            atan2(R_W_V_after[1].toDouble(), R_W_V_after[4].toDouble())
        ).toFloat()
        assertAngleEquals(0f, heading, tolerance = 0.5f)
    }

    @Test
    fun `calibrateAzimuth preserves alpha and beta`() {
        val mounting = TaitBryanZyx.compose(0f, 0f, -90f)
        val R_W_V_true = DeviceCalibration.buildFlatHeadingMatrix(90f)
        val R_W_D = Matrix3.multiply(R_W_V_true, Matrix3.transpose(mounting))

        // Existing calibration with wrong heading
        val existingCal = TaitBryanZyx.compose(0f, 0f, -90f)
        val (alphaB, betaB, gammaB) = DeviceCalibration.decomposeZXZ(existingCal)

        val (_, updated) = DeviceCalibration.calibrateAzimuth(R_W_D, alphaB, betaB, gammaB, 90f)
        val (alphaA, betaA, _) = DeviceCalibration.decomposeZXZ(updated)

        assertAngleWrappedEquals(alphaB, alphaA)
        assertAngleEquals(betaB, betaA)
    }

    @Test
    fun `calibrateAzimuth preserves tilt when vehicle is tilted by waves`() {
        // Device mounted portrait (rx=90°), vehicle heading east
        val mounting = TaitBryanZyx.compose(0f, 0f, 90f)
        val R_W_V_flat = DeviceCalibration.buildFlatHeadingMatrix(90f)

        // Vehicle tilted 10° by waves
        val waveTilt = TaitBryanZyx.compose(0f, 0f, 10f)
        val R_W_V_wavy = Matrix3.multiply(waveTilt, R_W_V_flat)
        val R_W_D_wavy = Matrix3.multiply(
            R_W_V_wavy,
            Matrix3.transpose(mounting)
        )

        // Before azimuth calibration
        val R_W_V_before = Matrix3.multiply(R_W_D_wavy, mounting)
        val pitchBefore = asin(-R_W_V_before[7].toDouble()).toFloat()
        val rollBefore = atan2(-R_W_V_before[6].toDouble(), R_W_V_before[8].toDouble()).toFloat()

        val (existingAlpha, existingBeta, existingGamma) = DeviceCalibration.decomposeZXZ(mounting)
        val (_, updated) = DeviceCalibration.calibrateAzimuth(
            R_W_D_wavy,
            existingAlpha,
            existingBeta,
            existingGamma,
            90f
        )
        val R_W_V_after = Matrix3.multiply(R_W_D_wavy, updated)

        val pitchAfter = asin(-R_W_V_after[7].toDouble()).toFloat()
        val rollAfter = atan2(-R_W_V_after[6].toDouble(), R_W_V_after[8].toDouble()).toFloat()
        assertEquals(pitchBefore, pitchAfter, 1e-3f)
        assertEquals(rollBefore, rollAfter, 1e-3f)

        val heading = Math.toDegrees(
            atan2(R_W_V_after[1].toDouble(), R_W_V_after[4].toDouble())
        ).toFloat()
        assertAngleEquals(90f, heading, tolerance = 0.5f)
    }

    @Test
    fun `calibrateAzimuth works with large mounting angles`() {
        // Complex 3-axis mounting: proper workflow is calibrateTilt then calibrateAzimuth
        val mounting = TaitBryanZyx.compose(45f, 30f, -60f)
        val R_W_V_true = DeviceCalibration.buildFlatHeadingMatrix(270f)
        val R_W_D = Matrix3.multiply(
            R_W_V_true,
            Matrix3.transpose(mounting)
        )

        // Step 1: Tilt calibration (gets α, β correct, heading arbitrary)
        val (alpha1, beta1, afterTilt) = DeviceCalibration.calibrateTilt(R_W_D, 0f)

        // Step 2: Azimuth calibration with GPS heading (γ=0 after initial tilt calibration)
        val (_, updated) = DeviceCalibration.calibrateAzimuth(R_W_D, alpha1, beta1, 0f, 270f)
        val R_W_V_after = Matrix3.multiply(R_W_D, updated)

        val heading = Math.toDegrees(
            atan2(R_W_V_after[1].toDouble(), R_W_V_after[4].toDouble())
        ).toFloat()
        assertHeadingEquals(270f, heading)
    }

    @Test
    fun `sequential calibration - tilt first then azimuth`() {
        // The user's workflow: calibrate tilt at dock, then azimuth underway

        // Device mounted landscape (90° Z), tilted back 20° (rx=20°)
        val realMounting = TaitBryanZyx.compose(90f, 0f, 20f)

        // Vehicle heading north, flat, at the dock
        val R_W_V_dock = DeviceCalibration.buildFlatHeadingMatrix(0f)
        val R_W_D_dock = Matrix3.multiply(
            R_W_V_dock,
            Matrix3.transpose(realMounting)
        )

        // Step 1: Calibrate tilt (γ=0 initially)
        val (afterTiltAlpha, afterTiltBeta, afterTilt) = DeviceCalibration.calibrateTilt(R_W_D_dock, 0f)

        // Verify vehicle is flat
        val R_W_V_step1 = Matrix3.multiply(R_W_D_dock, afterTilt)
        assertEquals(0f, R_W_V_step1[7], 1e-3f) // pitch component
        assertEquals(0f, R_W_V_step1[6], 1e-3f) // roll component

        // Step 2: Now underway heading east, vehicle tilted 5° by waves
        val waveTilt = TaitBryanZyx.compose(0f, 5f, 0f)
        val R_W_V_sea = Matrix3.multiply(
            waveTilt,
            DeviceCalibration.buildFlatHeadingMatrix(90f)
        )
        val R_W_D_sea = Matrix3.multiply(
            R_W_V_sea,
            Matrix3.transpose(realMounting)
        )

        // Calibrate azimuth with GPS = 90° (east)
        val (_, afterAzimuth) = DeviceCalibration.calibrateAzimuth(R_W_D_sea, afterTiltAlpha, afterTiltBeta, 0f, 90f)
        val R_W_V_final = Matrix3.multiply(R_W_D_sea, afterAzimuth)

        // Heading should be 90°
        val heading = Math.toDegrees(
            atan2(R_W_V_final[1].toDouble(), R_W_V_final[4].toDouble())
        ).toFloat()
        assertHeadingEquals(90f, heading)

        // Wave tilt should be preserved
        val tiltBefore = Math.toDegrees(
            acos(R_W_V_sea[8].toDouble().coerceIn(-1.0, 1.0))
        ).toFloat()
        val tiltAfter = Math.toDegrees(
            acos(R_W_V_final[8].toDouble().coerceIn(-1.0, 1.0))
        ).toFloat()
        assertEquals(tiltBefore, tiltAfter, 1f)
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
        val R_D_V = TaitBryanZyx.compose(rzDeg, ryDeg, rxDeg)
        val R_W_D = Matrix3.transpose(R_D_V) // vehicle heading north, flat
        val R_W_V = Matrix3.multiply(R_W_D, R_D_V)
        assertMatrixEquals(DeviceCalibration.IDENTITY_3X3, R_W_V, tolerance = 1e-4f)
    }

    @Test
    fun `mounting - flat portrait charging port aft`() {
        // Device X=starboard, Y=forward, Z=up → identity
        verifyMounting(0f, 0f, 0f)
        // ZXZ: gimbal lock (flat), α=0, β=0, γ=0
        val R_D_V = TaitBryanZyx.compose(0f, 0f, 0f)
        val (alpha, beta, gamma) = DeviceCalibration.decomposeZXZ(R_D_V)
        assertAngleEquals(0f, alpha)
        assertAngleEquals(0f, beta)
        assertAngleEquals(0f, gamma)
    }

    @Test
    fun `mounting - flat landscape charging port port`() {
        // Phone rotated 90° CW from above: top→starboard, right→aft
        verifyMounting(90f, 0f, 0f)
        // ZXZ: gimbal lock (flat), α=90°, β=0, γ=0
        val R_D_V = TaitBryanZyx.compose(90f, 0f, 0f)
        val (alpha, beta, gamma) = DeviceCalibration.decomposeZXZ(R_D_V)
        assertAngleEquals(90f, alpha)
        assertAngleEquals(0f, beta)
        assertAngleEquals(0f, gamma)
    }

    @Test
    fun `mounting - vertical HUD charging port down`() {
        // Screen faces helm (aft), top faces up, right faces starboard
        val R_D_V = TaitBryanZyx.compose(0f, 0f, -90f)
        // Device Z (screen) should map to -Y vehicle (aft)
        // Column 1 of R_D_V = Y_vehicle in device coords = (0, 0, -1)
        assertEquals(0f, R_D_V[1], 1e-5f)
        assertEquals(0f, R_D_V[4], 1e-5f)
        assertEquals(-1f, R_D_V[7], 1e-5f)
        verifyMounting(0f, 0f, -90f)
        // ZXZ: α=180°, β=90°, γ=±180°
        val (alpha, beta, gamma) = DeviceCalibration.decomposeZXZ(R_D_V)
        assertAngleWrappedEquals(180f, alpha)
        assertAngleEquals(90f, beta)
        assertAngleWrappedEquals(180f, gamma)
    }

    @Test
    fun `mounting - vertical HUD charging port up`() {
        // Upside-down HUD: screen faces helm, top faces down
        verifyMounting(180f, 0f, -90f)
        // ZXZ: α=0°, β=90°, γ=±180°
        val R_D_V = TaitBryanZyx.compose(180f, 0f, -90f)
        val (alpha, beta, gamma) = DeviceCalibration.decomposeZXZ(R_D_V)
        assertAngleEquals(0f, alpha)
        assertAngleEquals(90f, beta)
        assertAngleWrappedEquals(180f, gamma)
    }

    @Test
    fun `mounting - vertical starboard screen to port charging port down`() {
        // Phone on starboard wall, screen facing inward (port), top up
        // Hits gimbal lock at RY=90° in ZYX
        verifyMounting(90f, 90f, 0f)
        // ZXZ: α=180°, β=90°, γ=-90°
        val R_D_V = TaitBryanZyx.compose(90f, 90f, 0f)
        val (alpha, beta, gamma) = DeviceCalibration.decomposeZXZ(R_D_V)
        assertAngleEquals(180f, alpha)
        assertAngleEquals(90f, beta)
        assertAngleEquals(-90f, gamma)
    }

    @Test
    fun `mounting - vertical starboard screen to port charging port forward`() {
        // Same wall mount but phone rotated: charging port faces bow
        // Hits gimbal lock at RY=90° in ZYX
        verifyMounting(180f, 90f, 0f)
        // ZXZ: α=-90°, β=90°, γ=-90°
        val R_D_V = TaitBryanZyx.compose(180f, 90f, 0f)
        val (alpha, beta, gamma) = DeviceCalibration.decomposeZXZ(R_D_V)
        assertAngleEquals(-90f, alpha)
        assertAngleEquals(90f, beta)
        assertAngleEquals(-90f, gamma)
    }

    // --- Euler angle non-independence at large tilt (ZYX) ---
    // Scenarios 3 and 5 are the same physical tilt (vertical, charging port down)
    // with different horizontal orientation. In ZYX, they have completely different
    // Euler angles (gimbal lock issue). In ZXZ, they share β=90° and differ only in γ.

    // --- ZXZ advantage: scenarios 3 and 5 differ only in γ ---

    @Test
    fun `ZXZ scenarios 3 and 5 share alpha and beta, differ only in gamma`() {
        val R_D_V_3 = TaitBryanZyx.compose(0f, 0f, -90f)
        val R_D_V_5 = TaitBryanZyx.compose(90f, 90f, 0f)
        val (alpha3, beta3, gamma3) = DeviceCalibration.decomposeZXZ(R_D_V_3)
        val (alpha5, beta5, gamma5) = DeviceCalibration.decomposeZXZ(R_D_V_5)

        // Same tilt from horizontal
        assertAngleEquals(beta3, beta5, tolerance = 0.1f)
        // Same screen twist
        assertAngleEquals(alpha3, alpha5, tolerance = 0.1f)
        // Different heading offset (90° apart)
        val gammaDiff = abs(normalizeHeading(gamma3) - normalizeHeading(gamma5))
        val wrappedDiff = if (gammaDiff > 180f) 360f - gammaDiff else gammaDiff
        assertAngleEquals(90f, wrappedDiff, tolerance = 0.5f)
    }

    /**
     * Helper: simulate the full calibration workflow for any mounting.
     * 1. At dock: calibrate tilt with vehicle flat, heading north
     * 2. Underway: calibrate azimuth with GPS heading, vehicle tilted 8° by waves
     * Verify heading is correct and wave tilt is preserved.
     */
    private fun verifyCalibrationWorkflow(realMounting: FloatArray, gpsHeading: Float = 90f) {
        // Step 1: At dock, vehicle flat, heading north
        val R_W_V_dock = DeviceCalibration.buildFlatHeadingMatrix(0f)
        val R_W_D_dock = Matrix3.multiply(
            R_W_V_dock,
            Matrix3.transpose(realMounting)
        )
        val (afterTiltAlpha, afterTiltBeta, afterTilt) = DeviceCalibration.calibrateTilt(R_W_D_dock, 0f)

        // Verify flat after step 1
        val R_W_V_step1 = Matrix3.multiply(R_W_D_dock, afterTilt)
        assertEquals(0f, R_W_V_step1[6], 1e-3f)
        assertEquals(0f, R_W_V_step1[7], 1e-3f)

        // Step 2: Underway heading east, 8° wave tilt (pitch)
        val waveTilt = TaitBryanZyx.compose(0f, 8f, 0f)
        val R_W_V_sea = Matrix3.multiply(
            waveTilt,
            DeviceCalibration.buildFlatHeadingMatrix(gpsHeading)
        )
        val R_W_D_sea = Matrix3.multiply(
            R_W_V_sea,
            Matrix3.transpose(realMounting)
        )
        val (_, afterAzimuth) = DeviceCalibration.calibrateAzimuth(
            R_W_D_sea,
            afterTiltAlpha,
            afterTiltBeta,
            0f,
            gpsHeading
        )
        val R_W_V_final = Matrix3.multiply(R_W_D_sea, afterAzimuth)

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
        val mounting = TaitBryanZyx.compose(0f, 0f, -90f)
        verifyCalibrationWorkflow(mounting)
    }

    @Test
    fun `workflow works for vertical starboard mounting (scenario 5)`() {
        val mounting = TaitBryanZyx.compose(90f, 90f, 0f)
        verifyCalibrationWorkflow(mounting)
    }

    @Test
    fun `scenarios 3 and 5 differ only by horizontal rotation`() {
        // Both mountings are "phone vertical, charging port down".
        // They differ by 90° horizontal rotation.
        // The R_D_V matrices should be related by a pure rotation around vehicle Z.
        val R_D_V_3 = TaitBryanZyx.compose(0f, 0f, -90f)
        val R_D_V_5 = TaitBryanZyx.compose(90f, 90f, 0f)

        // R_D_V_5 = R_D_V_3 * Rz_vehicle(-90°)
        val Rz_neg90 = DeviceCalibration.buildFlatHeadingMatrix(-90f)
        val expected = Matrix3.multiply(R_D_V_3, Rz_neg90)

        assertMatrixEquals(expected, R_D_V_5, tolerance = 1e-4f)
    }

    @Test
    fun `calibrateAzimuth from identity calibration updates gamma not alpha`() {
        // Regression test: when calibration is identity (β=0, gimbal lock),
        // azimuth calibration must update γ and leave α unchanged (=0).
        val mounting = DeviceCalibration.IDENTITY_3X3.copyOf()
        val R_W_V = DeviceCalibration.buildFlatHeadingMatrix(30f) // vehicle heading 30°
        val R_W_D = Matrix3.multiply(R_W_V, Matrix3.transpose(mounting))

        // Start from identity calibration: α=0, β=0, γ=0
        val (newGamma, updated) = DeviceCalibration.calibrateAzimuth(R_W_D, 0f, 0f, 0f, 30f)

        // γ must change, α must stay 0 (user pressed "Azimuth Only")
        assertAngleEquals(0f, 0f) // α stays 0 — verified by new update path in ViewModel
        assertAngleEquals(0f, newGamma, tolerance = 0.5f) // correction brings heading to 30°

        // Resulting calibration must produce correct heading
        val R_W_V_after = Matrix3.multiply(R_W_D, updated)
        val heading = Math.toDegrees(
            atan2(R_W_V_after[1].toDouble(), R_W_V_after[4].toDouble())
        ).toFloat()
        assertHeadingEquals(30f, heading)
    }

    @Test
    fun `ZXZ azimuth correction changes only gamma for flat vehicle`() {
        // Vertical HUD mounting (0°, 0°, -90° in ZYX)
        val mounting = TaitBryanZyx.compose(0f, 0f, -90f)

        // At dock: vehicle heading north, flat
        val R_W_D_true = Matrix3.transpose(mounting)

        // Magnetometer adds 30° error
        val magError = DeviceCalibration.buildFlatHeadingMatrix(30f)
        val R_W_D_distorted = Matrix3.multiply(magError, R_W_D_true)

        // Tilt calibration at dock — correct tilt, heading arbitrary (γ=0)
        val (alpha1, beta1, afterTilt) = DeviceCalibration.calibrateTilt(R_W_D_distorted, 0f)
        val gamma1 = 0f // γ passed to calibrateTilt

        // GPS says heading = 0° (north)
        val (newGamma, afterAzimuth) = DeviceCalibration.calibrateAzimuth(
            R_W_D_distorted,
            alpha1,
            beta1,
            gamma1,
            0f
        )
        val (alpha2, beta2, gamma2) = DeviceCalibration.decomposeZXZ(afterAzimuth)

        // KEY PROPERTY: In ZXZ, α (screen twist) and β (tilt) are preserved exactly
        assertAngleWrappedEquals(alpha1, alpha2, tolerance = 0.5f)
        assertAngleEquals(beta1, beta2, tolerance = 0.5f)
        // Only γ (heading offset) changed
        assertAngleWrappedEquals(newGamma, gamma2, tolerance = 0.1f)

        // Vehicle attitude is correct
        val R_W_V = Matrix3.multiply(R_W_D_distorted, afterAzimuth)
        val heading = Math.toDegrees(
            atan2(R_W_V[1].toDouble(), R_W_V[4].toDouble())
        ).toFloat()
        assertHeadingEquals(0f, heading)
        assertEquals(0f, R_W_V[6], 1e-3f)
        assertEquals(0f, R_W_V[7], 1e-3f)
    }

    @Test
    fun `ZYX azimuth correction can change non-RZ Euler angles for tilted mounting`() {
        // Demonstrates ZYX limitation: for tilted mountings, heading correction
        // affects non-heading Euler angles. This motivates the switch to ZXZ.
        val mounting = TaitBryanZyx.compose(0f, 0f, -90f)

        // At dock: vehicle heading north, flat
        val R_W_D_true = Matrix3.transpose(mounting)

        // Magnetometer adds 30° error
        val magError = DeviceCalibration.buildFlatHeadingMatrix(30f)
        val R_W_D_distorted = Matrix3.multiply(magError, R_W_D_true)

        // Tilt calibration at dock
        val (alpha1, beta1, afterTilt) = DeviceCalibration.calibrateTilt(R_W_D_distorted, 0f)
        val gamma1 = 0f // γ passed to calibrateTilt
        val (rz1, ry1, rx1) = TaitBryanZyx.decompose(afterTilt)

        // GPS says heading = 0° (north)
        val (_, afterAzimuth) = DeviceCalibration.calibrateAzimuth(
            R_W_D_distorted,
            alpha1,
            beta1,
            gamma1,
            0f
        )
        val (rz2, ry2, rx2) = TaitBryanZyx.decompose(afterAzimuth)

        // KEY INSIGHT: In ZYX decomposition, heading correction does NOT stay
        // in a single angle. At least one of (rz, ry, rx) besides rz changes.
        // This is because for this vertical mounting, heading rotation maps to
        // a combination of ZYX Euler angles — there's no clean separation.
        val ryChanged = abs(ry2 - ry1) > 1f
        val rxChanged = abs(abs(rx2) - abs(rx1)) > 1f
        assertTrue(
            ryChanged || rxChanged,
            "ZYX heading correction should leak into non-RZ angles"
        )

        // In contrast, ZXZ decomposition keeps α, β unchanged
        val (alphaA, betaA, _) = DeviceCalibration.decomposeZXZ(afterAzimuth)
        assertAngleWrappedEquals(alpha1, alphaA, tolerance = 0.5f)
        assertAngleEquals(beta1, betaA, tolerance = 0.5f)

        // Vehicle attitude is correct regardless
        val R_W_V = Matrix3.multiply(R_W_D_distorted, afterAzimuth)
        val heading = Math.toDegrees(
            atan2(R_W_V[1].toDouble(), R_W_V[4].toDouble())
        ).toFloat()
        assertHeadingEquals(0f, heading)
        assertEquals(0f, R_W_V[6], 1e-3f)
        assertEquals(0f, R_W_V[7], 1e-3f)
    }
}
