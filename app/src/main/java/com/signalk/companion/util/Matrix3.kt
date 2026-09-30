package com.signalk.companion.util

/**
 * Pure 3x3 matrix algebra over the storage convention of frame-conventions.md §3.1: a
 * 9-element `FloatArray` in **row-major** order, element (row, col) at `row * 3 + col`,
 * matching Android's [android.hardware.SensorManager.getRotationMatrix].
 *
 * Index through [at] rather than raw offsets, so every access reads as (row, col) — the
 * form the column identities of §3.1 are stated in.
 */
object Matrix3 {

    /** Rows and columns of the matrix; also the length of the vectors it acts on. */
    const val DIM = 3

    /** Elements in a row-major 3x3 matrix. */
    const val SIZE = DIM * DIM

    /** Returns `A · B`. */
    fun multiply(A: FloatArray, B: FloatArray): FloatArray {
        val result = FloatArray(SIZE)
        for (row in 0 until DIM) {
            for (col in 0 until DIM) {
                var sum = 0f
                for (k in 0 until DIM) {
                    sum += A.at(row, k) * B.at(k, col)
                }
                result[row * DIM + col] = sum
            }
        }
        return result
    }

    /** Returns `Rᵀ`. For a rotation matrix, transpose == inverse. */
    fun transpose(R: FloatArray): FloatArray = floatArrayOf(
        R.at(0, 0), R.at(1, 0), R.at(2, 0),
        R.at(0, 1), R.at(1, 1), R.at(2, 1),
        R.at(0, 2), R.at(1, 2), R.at(2, 2)
    )

    /** Returns `R · v` for a 3-vector [v]. */
    fun multiplyVector(R: FloatArray, v: FloatArray): FloatArray = floatArrayOf(
        R.at(0, 0) * v[0] + R.at(0, 1) * v[1] + R.at(0, 2) * v[2],
        R.at(1, 0) * v[0] + R.at(1, 1) * v[1] + R.at(1, 2) * v[2],
        R.at(2, 0) * v[0] + R.at(2, 1) * v[1] + R.at(2, 2) * v[2]
    )
}

/** Element ([row], [col]) of a row-major 3x3 matrix (frame-conventions.md §3.1). */
internal fun FloatArray.at(row: Int, col: Int): Float = this[row * Matrix3.DIM + col]
