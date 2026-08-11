package com.signalk.companion.replay

import com.signalk.companion.ahrs.MahonyAhrs
import com.signalk.companion.ahrs.Quaternion
import com.signalk.companion.util.DeviceCalibration

/**
 * How a replay should treat the magnetometer's hard-iron bias.
 *
 * This is deliberately a choice made at replay time rather than at record time. The
 * recording stores the raw field and the HAL's estimate side by side (see [MagRecord]), so
 * the same sail can be replayed under every strategy and the results compared — which is
 * the whole argument for the harness.
 *
 * It is also where the open M1/M2 question gets answered empirically rather than by
 * argument: M1 reads the uncalibrated magnetometer while our own ellipsoid fit does not
 * arrive until M2, so the interim options are [None] and [HalEstimate]. Replay both against
 * a recorded sail and the heading traces will say which is less bad.
 */
sealed interface HardIronStrategy {
    fun correct(record: MagRecord): FloatArray

    /** Raw field, no correction. What M1 has if it simply drops Android's C1. */
    object None : HardIronStrategy {
        override fun correct(record: MagRecord) =
            floatArrayOf(record.x, record.y, record.z)
    }

    /** Subtract the HAL's own hard-iron estimate — borrowing C1 through the raw sensor. */
    object HalEstimate : HardIronStrategy {
        override fun correct(record: MagRecord) = floatArrayOf(
            record.x - record.biasX,
            record.y - record.biasY,
            record.z - record.biasZ
        )
    }

    /** Subtract a fixed offset, e.g. the output of M2's ellipsoid fit. */
    data class Fixed(val bx: Float, val by: Float, val bz: Float) : HardIronStrategy {
        override fun correct(record: MagRecord) =
            floatArrayOf(record.x - bx, record.y - by, record.z - bz)
    }
}

/** One attitude output, emitted per propagation step. */
data class AttitudeSample(
    val timestampNs: Long,
    val headingRad: Float,
    val pitchRad: Float,
    val rollRad: Float,
    val attitude: Quaternion,
    val gyroBias: FloatArray,
    val accelerometerAccepted: Boolean,
    val magnetometerAccepted: Boolean,
    val biasEstimatorRunning: Boolean
) {
    // Generated equals/hashCode would compare the FloatArray by identity. This class exists
    // to be compared in tests, so define them on content instead.
    override fun equals(other: Any?): Boolean =
        other is AttitudeSample &&
            timestampNs == other.timestampNs &&
            headingRad == other.headingRad &&
            pitchRad == other.pitchRad &&
            rollRad == other.rollRad &&
            attitude == other.attitude &&
            gyroBias.contentEquals(other.gyroBias)

    override fun hashCode(): Int =
        timestampNs.hashCode() * 31 + attitude.hashCode()
}

/**
 * Feeds a recording through the attitude filter and returns the resulting trace.
 *
 * Deterministic by construction: same recording plus same configuration gives the same
 * output, every time. That is what makes a recorded sail a regression test rather than an
 * anecdote, and it is why the filter takes its time base from record timestamps rather than
 * from a clock.
 *
 * The mount rotation is applied on the way out, not inside the filter. The filter estimates
 * *device* attitude in the world; `R_W_V = R_W_D · R_D_V` turns that into vehicle attitude
 * (frame-conventions.md §3.1), and keeping the two separate means a recording can be
 * replayed against a corrected mount calibration without re-recording.
 */
class ReplayRunner(
    private val filter: MahonyAhrs = MahonyAhrs(),
    private val hardIron: HardIronStrategy = HardIronStrategy.None,
    /** `R_D_V`, row-major. Identity means "report device attitude unchanged". */
    private val mountRotation: FloatArray = DeviceCalibration.IDENTITY_3X3
) {

    /** Fixes seen during the replay, in order. M4 consumes these; M1 only carries them. */
    val fixes: MutableList<FixRecord> = mutableListOf()

    /**
     * Android's fused attitude, in order, for comparison only — never fed to the filter.
     * Carried so a replay can answer "is ours better, and where?" against the same sail.
     */
    val referenceAttitudes: MutableList<RotationVectorRecord> = mutableListOf()

    /**
     * Run the records through the filter.
     *
     * Records are consumed in the order given — a recording is written in arrival order, and
     * the filter's own §7 guards handle any out-of-order or duplicated timestamps rather
     * than this runner silently sorting them away. Sorting here would hide exactly the
     * delivery pathology worth knowing about.
     */
    fun run(records: Sequence<SensorRecord>): List<AttitudeSample> {
        val out = mutableListOf<AttitudeSample>()
        for (record in records) {
            when (record) {
                is AccelRecord -> filter.onAccelerometer(record.x, record.y, record.z)
                is MagRecord -> {
                    val m = hardIron.correct(record)
                    filter.onMagnetometer(m[0], m[1], m[2])
                }
                is FixRecord -> fixes.add(record)
                // Collected, never consumed: feeding Android's fused output back into our
                // filter would make the comparison circular and re-import the black box P3
                // exists to remove.
                is RotationVectorRecord -> referenceAttitudes.add(record)
                is GyroRecord -> {
                    filter.onGyroscope(record.timestampNs, record.x, record.y, record.z)
                    if (filter.isInitialised) out.add(sample(record.timestampNs))
                }
            }
        }
        return out
    }

    private fun sample(timestampNs: Long): AttitudeSample {
        val deviceAttitude = filter.attitude
        val vehicle = DeviceCalibration.multiply3x3(deviceAttitude.toRotationMatrix(), mountRotation)
        val angles = DeviceCalibration.extractNauticalAngles(vehicle)
        return AttitudeSample(
            timestampNs = timestampNs,
            headingRad = angles.headingRad,
            pitchRad = angles.pitchRad,
            rollRad = angles.rollRad,
            attitude = deviceAttitude,
            gyroBias = filter.gyroBias.copyOf(),
            accelerometerAccepted = filter.accelerometerAccepted,
            magnetometerAccepted = filter.magnetometerAccepted,
            biasEstimatorRunning = filter.biasEstimatorRunning
        )
    }
}
