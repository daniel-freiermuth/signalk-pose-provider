package com.signalk.companion.util

import org.junit.jupiter.api.Test

/** ZXZ ([DeviceCalibration]) compose/decompose round trips and [TaitBryanZyx] fixture checks. */
class EulerAnglesTest {

    // --- Identity / zero-angle tests ---

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
}
