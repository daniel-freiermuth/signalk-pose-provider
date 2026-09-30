package com.signalk.companion.util

import org.junit.jupiter.api.Test

class Matrix3Test {

    // --- Matrix multiply tests ---

    @Test
    fun `multiply by identity gives same matrix`() {
        val m = TaitBryanZyx.compose(30f, 20f, 10f)
        val result = Matrix3.multiply(m, DeviceCalibration.IDENTITY_3X3)
        assertMatrixEquals(m, result)
    }

    @Test
    fun `multiply identity by matrix gives same matrix`() {
        val m = TaitBryanZyx.compose(30f, 20f, 10f)
        val result = Matrix3.multiply(DeviceCalibration.IDENTITY_3X3, m)
        assertMatrixEquals(m, result)
    }

    @Test
    fun `transpose of rotation matrix is its inverse`() {
        val m = TaitBryanZyx.compose(30f, 20f, 10f)
        val mt = Matrix3.transpose(m)
        val product = Matrix3.multiply(m, mt)
        assertMatrixEquals(DeviceCalibration.IDENTITY_3X3, product, tolerance = 1e-4f)
    }
}
