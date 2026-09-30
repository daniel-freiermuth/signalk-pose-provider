package com.signalk.companion.util

import org.junit.jupiter.api.Test

/** ZYX ([TaitBryanZyx]) and ZXZ ([DeviceCalibration]) compose/decompose round trips. */
class EulerAnglesTest {

    // --- Identity / zero-angle tests ---

    @Test
    fun `identity matrix decomposes to zero angles`() {
        val identity = DeviceCalibration.IDENTITY_3X3.copyOf()
        val (rz, ry, rx) = TaitBryanZyx.decompose(identity)
        assertAngleEquals(0f, rz)
        assertAngleEquals(0f, ry)
        assertAngleEquals(0f, rx)
    }

    @Test
    fun `zero angles compose to identity matrix`() {
        val result = TaitBryanZyx.compose(0f, 0f, 0f)
        assertMatrixEquals(DeviceCalibration.IDENTITY_3X3, result)
    }

    // --- ZXZ identity / zero-angle tests ---

    @Test
    fun `ZXZ identity matrix decomposes to zero angles`() {
        val identity = DeviceCalibration.IDENTITY_3X3.copyOf()
        val (alpha, beta, gamma) = DeviceCalibration.decomposeZXZ(identity)
        assertAngleEquals(0f, alpha)
        assertAngleEquals(0f, beta)
        assertAngleEquals(0f, gamma)
    }

    @Test
    fun `ZXZ zero angles compose to identity matrix`() {
        val result = DeviceCalibration.composeZXZ(0f, 0f, 0f)
        assertMatrixEquals(DeviceCalibration.IDENTITY_3X3, result)
    }

    // --- Single-axis rotation tests ---

    @Test
    fun `90 degrees around Z axis`() {
        val matrix = TaitBryanZyx.compose(rzDeg = 90f, ryDeg = 0f, rxDeg = 0f)
        val (rz, ry, rx) = TaitBryanZyx.decompose(matrix)
        assertAngleEquals(90f, rz)
        assertAngleEquals(0f, ry)
        assertAngleEquals(0f, rx)
    }

    @Test
    fun `minus 90 degrees around Z axis`() {
        val matrix = TaitBryanZyx.compose(rzDeg = -90f, ryDeg = 0f, rxDeg = 0f)
        val (rz, ry, rx) = TaitBryanZyx.decompose(matrix)
        assertAngleEquals(-90f, rz)
        assertAngleEquals(0f, ry)
        assertAngleEquals(0f, rx)
    }

    @Test
    fun `45 degrees around Y axis`() {
        val matrix = TaitBryanZyx.compose(rzDeg = 0f, ryDeg = 45f, rxDeg = 0f)
        val (rz, ry, rx) = TaitBryanZyx.decompose(matrix)
        assertAngleEquals(0f, rz)
        assertAngleEquals(45f, ry)
        assertAngleEquals(0f, rx)
    }

    @Test
    fun `30 degrees around X axis`() {
        val matrix = TaitBryanZyx.compose(rzDeg = 0f, ryDeg = 0f, rxDeg = 30f)
        val (rz, ry, rx) = TaitBryanZyx.decompose(matrix)
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
        val matrix = TaitBryanZyx.compose(rzIn, ryIn, rxIn)
        val (rzOut, ryOut, rxOut) = TaitBryanZyx.decompose(matrix)
        assertAngleEquals(rzIn, rzOut)
        assertAngleEquals(ryIn, ryOut)
        assertAngleEquals(rxIn, rxOut)
    }

    @Test
    fun `roundtrip with all three angles nonzero`() {
        val rzIn = 45f
        val ryIn = -20f
        val rxIn = 10f
        val matrix = TaitBryanZyx.compose(rzIn, ryIn, rxIn)
        val (rzOut, ryOut, rxOut) = TaitBryanZyx.decompose(matrix)
        assertAngleEquals(rzIn, rzOut)
        assertAngleEquals(ryIn, ryOut)
        assertAngleEquals(rxIn, rxOut)
    }

    @Test
    fun `roundtrip with 180 degrees Z`() {
        val rzIn = 180f
        val ryIn = 0f
        val rxIn = 0f
        val matrix = TaitBryanZyx.compose(rzIn, ryIn, rxIn)
        val (rzOut, ryOut, rxOut) = TaitBryanZyx.decompose(matrix)
        assertAngleEquals(rzIn, rzOut)
        assertAngleEquals(ryIn, ryOut)
        assertAngleEquals(rxIn, rxOut)
    }

    @Test
    fun `roundtrip with negative angles`() {
        val rzIn = -45f
        val ryIn = -30f
        val rxIn = -15f
        val matrix = TaitBryanZyx.compose(rzIn, ryIn, rxIn)
        val (rzOut, ryOut, rxOut) = TaitBryanZyx.decompose(matrix)
        assertAngleEquals(rzIn, rzOut)
        assertAngleEquals(ryIn, ryOut)
        assertAngleEquals(rxIn, rxOut)
    }

    // --- ZXZ roundtrip tests ---

    @Test
    fun `ZXZ roundtrip with 90 degree tilt`() {
        val alphaIn = 45f
        val betaIn = 90f
        val gammaIn = -30f
        val matrix = DeviceCalibration.composeZXZ(alphaIn, betaIn, gammaIn)
        val (alphaOut, betaOut, gammaOut) = DeviceCalibration.decomposeZXZ(matrix)
        assertAngleEquals(alphaIn, alphaOut)
        assertAngleEquals(betaIn, betaOut)
        assertAngleEquals(gammaIn, gammaOut)
    }

    @Test
    fun `ZXZ roundtrip with all angles nonzero`() {
        val alphaIn = -60f
        val betaIn = 120f
        val gammaIn = 45f
        val matrix = DeviceCalibration.composeZXZ(alphaIn, betaIn, gammaIn)
        val (alphaOut, betaOut, gammaOut) = DeviceCalibration.decomposeZXZ(matrix)
        assertAngleEquals(alphaIn, alphaOut)
        assertAngleEquals(betaIn, betaOut)
        assertAngleEquals(gammaIn, gammaOut)
    }

    @Test
    fun `ZXZ roundtrip with small tilt`() {
        val alphaIn = 10f
        val betaIn = 5f
        val gammaIn = 20f
        val matrix = DeviceCalibration.composeZXZ(alphaIn, betaIn, gammaIn)
        val (alphaOut, betaOut, gammaOut) = DeviceCalibration.decomposeZXZ(matrix)
        assertAngleEquals(alphaIn, alphaOut)
        assertAngleEquals(betaIn, betaOut)
        assertAngleEquals(gammaIn, gammaOut)
    }

    @Test
    fun `ZXZ decompose at gimbal lock beta 0 returns valid angles`() {
        // β=0 means flat mounting (gimbal lock)
        val matrix = DeviceCalibration.composeZXZ(alphaDeg = 30f, betaDeg = 0f, gammaDeg = 45f)
        val (alpha, beta, gamma) = DeviceCalibration.decomposeZXZ(matrix)
        assertAngleEquals(0f, beta)
        // At gimbal lock, only α+γ is determined. Recompose should match.
        val recomposed = DeviceCalibration.composeZXZ(alpha, beta, gamma)
        assertMatrixEquals(matrix, recomposed, tolerance = 1e-3f)
    }

    @Test
    fun `ZXZ decompose at gimbal lock beta 180 returns valid angles`() {
        val matrix = DeviceCalibration.composeZXZ(alphaDeg = 60f, betaDeg = 180f, gammaDeg = -20f)
        val (alpha, beta, gamma) = DeviceCalibration.decomposeZXZ(matrix)
        assertAngleEquals(180f, beta)
        val recomposed = DeviceCalibration.composeZXZ(alpha, beta, gamma)
        assertMatrixEquals(matrix, recomposed, tolerance = 1e-3f)
    }

    @Test
    fun `ZXZ and ZYX produce same matrix for pure Z rotation`() {
        // A pure Z rotation should be representable in both conventions
        val zyx = TaitBryanZyx.compose(90f, 0f, 0f)
        val zxz = DeviceCalibration.composeZXZ(90f, 0f, 0f)
        // Both should be Rz(90) since middle/last angles are 0
        assertMatrixEquals(zyx, zxz, tolerance = 1e-5f)
    }

    // --- Gimbal lock (pitch = ±90°) ---

    @Test
    fun `decompose at positive gimbal lock returns valid angles`() {
        // RY = +90° is gimbal lock. The matrix loses one DOF.
        val matrix = TaitBryanZyx.compose(rzDeg = 45f, ryDeg = 90f, rxDeg = 20f)
        val (rz, ry, rx) = TaitBryanZyx.decompose(matrix)
        // At gimbal lock, ry should be 90, and rz+rx combined effect should match
        assertAngleEquals(90f, ry)
        // Recompose and check the matrix matches
        val recomposed = TaitBryanZyx.compose(rz, ry, rx)
        assertMatrixEquals(matrix, recomposed, tolerance = 1e-3f)
    }

    @Test
    fun `decompose at negative gimbal lock returns valid angles`() {
        val matrix = TaitBryanZyx.compose(rzDeg = 30f, ryDeg = -90f, rxDeg = 10f)
        val (rz, ry, rx) = TaitBryanZyx.decompose(matrix)
        assertAngleEquals(-90f, ry)
        val recomposed = TaitBryanZyx.compose(rz, ry, rx)
        assertMatrixEquals(matrix, recomposed, tolerance = 1e-3f)
    }
}
