package com.signalk.companion.util

import org.junit.jupiter.api.Assertions.assertEquals
import kotlin.math.abs

/** Default element-wise tolerance for [assertMatrixEquals]. */
private const val MATRIX_TOLERANCE = 1e-5f

internal fun assertMatrixEquals(
    expected: FloatArray,
    actual: FloatArray,
    tolerance: Float = MATRIX_TOLERANCE
) {
    assertEquals(expected.size, actual.size, "Matrix sizes differ")
    for (i in expected.indices) {
        assertEquals(expected[i], actual[i], tolerance, "Mismatch at index $i")
    }
}

internal fun assertAngleEquals(expectedDeg: Float, actualDeg: Float, tolerance: Float = 0.1f) {
    assertEquals(expectedDeg, actualDeg, tolerance, "Angle mismatch")
}

/** Compare angles modulo 360° (handles ±180° boundary). */
internal fun assertAngleWrappedEquals(
    expectedDeg: Float,
    actualDeg: Float,
    tolerance: Float = 0.5f
) {
    var diff = (expectedDeg - actualDeg) % 360f
    if (diff > 180f) diff -= 360f
    if (diff < -180f) diff += 360f
    assertEquals(
        0f,
        abs(diff),
        tolerance,
        "Wrapped angle mismatch: expected $expectedDeg° but was $actualDeg°"
    )
}

/** Normalize angle to [0, 360) for heading comparisons. */
internal fun normalizeHeading(deg: Float): Float {
    var d = deg % 360f
    if (d < 0) d += 360f
    return d
}

internal fun assertHeadingEquals(expectedDeg: Float, actualDeg: Float, tolerance: Float = 0.5f) {
    val diff = abs(normalizeHeading(expectedDeg) - normalizeHeading(actualDeg))
    val wrapped = if (diff > 180f) 360f - diff else diff
    assertEquals(
        0f,
        wrapped,
        tolerance,
        "Heading mismatch: expected $expectedDeg° but was $actualDeg°"
    )
}
