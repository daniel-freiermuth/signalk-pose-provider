package com.signalk.companion.service

import android.content.Context
import android.util.Log
import com.signalk.companion.ahrs.MahonyAhrs
import com.signalk.companion.ahrs.Quaternion
import com.signalk.companion.replay.AccelRecord
import com.signalk.companion.replay.FixRecord
import com.signalk.companion.replay.GyroRecord
import com.signalk.companion.replay.HardIronStrategy
import com.signalk.companion.replay.MagRecord
import com.signalk.companion.replay.RecordingSession
import com.signalk.companion.replay.RotationVectorRecord
import com.signalk.companion.replay.SensorRecord
import com.signalk.companion.util.DeviceCalibration
import com.signalk.companion.util.Matrix3
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The M1 attitude pipeline, live: [RawSensorSource] → [MahonyAhrs] → mount rotation → pose.
 *
 * This is the on-device counterpart of [com.signalk.companion.replay.ReplayRunner], and the
 * two are deliberately the same shape — same filter, same hard-iron strategy, same mount
 * rotation applied on the way out. A recording made here replays there and gives the same
 * answer, which is the property that makes a recorded sail a regression test.
 *
 * **It does not publish to SignalK.** [SensorService] still owns the published output. The
 * two run side by side on purpose: this estimate is unvalidated and untuned — no gain here
 * has ever seen a boat — and the milestone that says "correct roll/pitch under heel" is only
 * true once the gains have been tuned against a recorded sail. Until then this is a
 * comparison trace you can watch next to the legacy value, and the recording is the
 * deliverable. Switching the published source is a decision for whoever has the recordings.
 *
 * **Threading.** Records arrive on [RawSensorSource]'s dedicated sensor thread and are
 * handled there: the filter is single-threaded by contract, and this is the one thread that
 * feeds it. Only [state] crosses back to the main thread, rate-limited — publishing 200
 * updates a second into Compose is exactly the main-thread contention the sensor thread
 * exists to avoid.
 */
@Singleton
class AttitudeEngine @Inject constructor(
    private val context: Context,
    private val recordingSession: RecordingSession
) {

    /**
     * One pose estimate, in the vehicle frame, plus enough state to explain it.
     *
     * The `*Accepted` flags and [biasEstimatorRunning] are the beginnings of M5's quality
     * publishing: an instrument that says "coasting on the gyro because the magnetometer is
     * gated" is worth more than one that quietly reports a stale number.
     */
    data class State(
        val timestampNs: Long,
        /** Vehicle heading, radians, 0..2π clockwise from magnetic north (§5). */
        val headingRad: Float,
        /** Bow-up positive, radians (§5). */
        val pitchRad: Float,
        /** Starboard-down positive, radians — "heel" in SignalK terms (§5.1). */
        val rollRad: Float,
        /** Nautical rate of turn, rad/s, positive to starboard (§11.4). */
        val rateOfTurnRadS: Float,
        val accelerometerAccepted: Boolean,
        val magnetometerAccepted: Boolean,
        val biasEstimatorRunning: Boolean,
        /** Estimated gyro bias magnitude, rad/s — a drift readout for the diagnostics screen. */
        val gyroBiasMagnitude: Float,
        /**
         * Heading from Android's own fused attitude, radians, or null where the device has no
         * `TYPE_ROTATION_VECTOR`. Comparison only; never an input.
         */
        val referenceHeadingRad: Float?
    )

    companion object {
        private const val TAG = "AttitudeEngine"

        /**
         * Emit state at ~10 Hz. Fast enough to look live, slow enough that the UI is not
         * doing the filter's work; the filter itself still runs at the full sensor rate.
         */
        const val EMIT_INTERVAL_NS = 100_000_000L
    }

    private val filter = MahonyAhrs()
    private var source: RawSensorSource? = null

    /**
     * `R_D_V`, the mount rotation. Volatile because the calibration screen writes it from the
     * main thread while the sensor thread reads it; swapped whole rather than mutated so a
     * reader never sees half of one calibration and half of another.
     */
    @Volatile private var mountRotation: FloatArray = DeviceCalibration.IDENTITY_3X3

    /**
     * How the magnetometer's hard iron is handled. Default [HardIronStrategy.HalEstimate]:
     * M1 reads the uncalibrated magnetometer but our own ellipsoid fit does not arrive until
     * M2, so the alternative is *no* correction at all — worse heading than the app ships
     * today, having dropped Android's correction without having our own. Borrowing the HAL's
     * estimate is the least-bad interim, and it is a `var` because the recordings store the
     * raw field alongside the estimate precisely so the choice can be re-decided offline.
     */
    @Volatile var hardIron: HardIronStrategy = HardIronStrategy.HalEstimate

    private val _state = MutableStateFlow<State?>(null)

    /**
     * The latest estimate of the running session, or null while stopped and before a new
     * session's first emit. The UI treats non-null as live, so a stopped engine must never
     * leave its last pose behind.
     */
    val state: StateFlow<State?> = _state.asStateFlow()

    /**
     * The session currently allowed to publish [state], or null while stopped. Guarded by
     * [stateLock], which also covers every write to [_state], so clearing and a sensor-thread
     * publish can never interleave: an emit computed for a session that has since stopped is
     * dropped rather than republished. [RawSensorSource.stop] joining its thread already
     * gives this on a device; the engine keeps the guarantee itself instead of depending on
     * how the source tears down.
     */
    private val stateLock = Any()
    private var publishingSession: Any? = null

    /** What the device offers, or null before the first [start]. */
    var availability: RawSensorSource.Availability? = null
        private set

    val isRunning: Boolean get() = source?.isRunning == true

    // Sensor-thread state. Only touched from the RawSensorSource handler thread.
    private var lastEmitNs = 0L
    private var lastGyro = floatArrayOf(0f, 0f, 0f)
    private var referenceAttitude: Quaternion? = null
    private var clockNoted = false

    /**
     * Start the pipeline.
     *
     * @param record when true, every raw record is also appended to [recordingSession] —
     *   the same records the filter sees, before any correction, so the recording stays
     *   replayable under a strategy that has not been chosen yet.
     * @return false if the device lacks a sensor the filter needs.
     */
    fun start(
        samplingPeriodUs: Int = RawSensorSource.DEFAULT_SAMPLING_PERIOD_US,
        record: Boolean = false
    ): Boolean {
        stop()

        val raw = RawSensorSource(context)
        availability = raw.availability
        val started = if (raw.availability.isUsable) {
            startSource(raw, samplingPeriodUs, record)
        } else {
            Log.e(TAG, "Cannot start attitude engine: ${raw.availability}")
            false
        }

        if (started) {
            source = raw
            Log.i(TAG, "Attitude engine started (recording=$record, hardIron=$hardIron)")
        }
        return started
    }

    /** Reset the filter and sensor-thread state, then start [raw] feeding [consume]. */
    private fun startSource(raw: RawSensorSource, samplingPeriodUs: Int, record: Boolean): Boolean {
        filter.reset(clearBias = true)
        lastEmitNs = 0L
        referenceAttitude = null
        clockNoted = false

        // Allowed to publish before raw.start returns: the first records can arrive as soon
        // as the sensors are registered.
        val session = Any()
        synchronized(stateLock) { publishingSession = session }

        val started = raw.start(samplingPeriodUs) { sensorRecord ->
            if (!clockNoted) {
                raw.noteClockBase(sensorRecord.timestampNs)
                clockNoted = true
            }
            if (record) recordingSession.write(sensorRecord)
            consume(sensorRecord, session)
        }
        if (!started) clearState()
        return started
    }

    fun stop() {
        source?.stop()
        source = null
        clearState()
    }

    private fun clearState() {
        synchronized(stateLock) {
            publishingSession = null
            _state.value = null
        }
    }

    /**
     * A GNSS fix, forwarded so a recording carries position on the same timeline as the IMU.
     * M4 is what consumes it; M1 only needs it to be in the file.
     *
     * Called from whichever thread delivers fixes — the main looper, in [LocationService] —
     * rather than the sensor thread, so it only touches the recording, never the filter.
     */
    fun onFix(fix: FixRecord) {
        recordingSession.write(fix)
    }

    /** Set the mount calibration `R_D_V` from ZXZ angles, as the calibration screen does. */
    fun setCalibrationAngles(alphaDeg: Float, betaDeg: Float, gammaDeg: Float) {
        mountRotation = DeviceCalibration.composeZXZ(alphaDeg, betaDeg, gammaDeg)
    }

    // ------------------------------------------------------------------ sensor thread

    private fun consume(record: SensorRecord, session: Any) {
        when (record) {
            is AccelRecord -> filter.onAccelerometer(record.x, record.y, record.z)

            is MagRecord -> {
                val m = hardIron.correct(record)
                filter.onMagnetometer(m[0], m[1], m[2])
            }

            // Comparison only. Feeding Android's fused output back into our filter would make
            // the comparison circular and re-import the black box P3 exists to remove.
            is RotationVectorRecord -> referenceAttitude = record.toQuaternion()

            is FixRecord -> Unit // arrives through onFix(), not the sensor stream

            is GyroRecord -> {
                lastGyro = floatArrayOf(record.x, record.y, record.z)
                filter.onGyroscope(record.timestampNs, record.x, record.y, record.z)
                maybeEmit(record.timestampNs, session)
            }
        }
    }

    private fun maybeEmit(timestampNs: Long, session: Any) {
        if (!filter.isInitialised) return
        // Guard on the absolute difference, not `timestampNs - lastEmitNs >= interval`: a
        // clock that jumps backwards (§7) would otherwise stop emission until it caught up.
        if (lastEmitNs != 0L && Math.abs(timestampNs - lastEmitNs) < EMIT_INTERVAL_NS) return
        lastEmitNs = timestampNs

        val mount = mountRotation
        val vehicle = Matrix3.multiply(filter.attitude.toRotationMatrix(), mount)
        val angles = DeviceCalibration.extractNauticalAngles(vehicle)

        // Rate of turn uses the bias-corrected rate: the estimator's whole job is that the
        // published rate is the boat turning rather than the sensor drifting.
        val bias = filter.gyroBias
        val corrected = floatArrayOf(
            lastGyro[0] - bias[0],
            lastGyro[1] - bias[1],
            lastGyro[2] - bias[2]
        )

        val reference = referenceAttitude?.let {
            DeviceCalibration.extractNauticalAngles(
                Matrix3.multiply(it.toRotationMatrix(), mount)
            ).headingRad
        }

        val next = State(
            timestampNs = timestampNs,
            headingRad = angles.headingRad,
            pitchRad = angles.pitchRad,
            rollRad = angles.rollRad,
            rateOfTurnRadS = DeviceCalibration.rateOfTurnFromGyro(mount, corrected),
            accelerometerAccepted = filter.accelerometerAccepted,
            magnetometerAccepted = filter.magnetometerAccepted,
            biasEstimatorRunning = filter.biasEstimatorRunning,
            gyroBiasMagnitude = kotlin.math.sqrt(
                bias[0] * bias[0] + bias[1] * bias[1] + bias[2] * bias[2]
            ),
            referenceHeadingRad = reference
        )
        synchronized(stateLock) {
            if (publishingSession === session) _state.value = next
        }
    }
}
