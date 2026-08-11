package com.signalk.companion.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.signalk.companion.replay.AccelRecord
import com.signalk.companion.replay.GyroRecord
import com.signalk.companion.replay.MagRecord
import com.signalk.companion.replay.RotationVectorRecord
import com.signalk.companion.replay.SensorRecord

/**
 * High-rate raw sensor ingestion for the M1 attitude pipeline.
 *
 * This is the P3 sensor layer: **uncalibrated** magnetometer and gyroscope plus the
 * accelerometer, delivered at an explicit rate on a dedicated thread, stamped with
 * `SensorEvent.timestamp`. It is separate from [SensorService], which still runs the legacy
 * tilt-compensated compass — the two coexist until the filter is wired into the published
 * output, so the old path keeps working while the new one is proven against recordings.
 *
 * Four things here matter more than they look (frame-conventions.md §4.2, §4.3, §7):
 *
 * 1. **Uncalibrated sensor types.** `TYPE_MAGNETIC_FIELD_UNCALIBRATED` and
 *    `TYPE_GYROSCOPE_UNCALIBRATED` expose the raw measurement *and* the HAL's own bias
 *    estimate separately, so we can own the correction rather than inherit an opaque one
 *    that restales itself silently. Where a device lacks them we fall back to the
 *    calibrated types and say so in [availability] — a fallback that is invisible would
 *    make a recording lie about its own provenance.
 * 2. **Explicit `samplingPeriodUs`**, not `SENSOR_DELAY_*`. The delay constants are coarse
 *    buckets; the filter needs a known rate, and 200 Hz is not reachable through them.
 * 3. **A dedicated `HandlerThread`.** At 200 Hz across three sensors this is ~600
 *    callbacks/second. Delivering those on the main looper — which is what
 *    `registerListener` does when passed no handler, as the legacy path does — puts the
 *    sensor stream in contention with UI rendering, and drops samples when Compose is busy.
 * 4. **Monotonic timestamps.** `event.timestamp` is the only clock the filter integrates
 *    on. `elapsedRealtimeNanos()` is captured once at start so a recording can be checked
 *    against the documented base on devices that report a different one.
 */
class RawSensorSource(private val context: Context) {

    /** What the device actually offers, and therefore what a recording contains. */
    data class Availability(
        val hasAccelerometer: Boolean,
        val hasGyroscope: Boolean,
        val hasMagnetometer: Boolean,
        /** False when the device only offers the calibrated gyroscope. */
        val gyroscopeUncalibrated: Boolean,
        /** False when the device only offers the calibrated magnetometer. */
        val magnetometerUncalibrated: Boolean,
        /** Android's fused attitude, recorded for comparison only. Absent on some devices. */
        val hasRotationVector: Boolean = false
    ) {
        /** The filter needs all three; without them M1 cannot produce an attitude. */
        val isUsable: Boolean get() = hasAccelerometer && hasGyroscope && hasMagnetometer

        /** True when the raw pipeline is fully raw, as P3 intends. */
        val isFullyRaw: Boolean get() = gyroscopeUncalibrated && magnetometerUncalibrated
    }

    companion object {
        private const val TAG = "RawSensorSource"

        /** 200 Hz. Above the wave and rig band with margin; see P3. */
        const val DEFAULT_SAMPLING_PERIOD_US = 5_000

        /** 25 Hz for the comparison trace — enough to resolve boat motion, see [start]. */
        const val REFERENCE_SAMPLING_PERIOD_US = 40_000

        /**
         * Deliver events as they arrive rather than in batches. Batching saves power by
         * letting the sensor hub buffer, but it delays the attitude solution by the batch
         * window, which is the one thing a live instrument cannot trade away.
         */
        private const val NO_BATCHING_US = 0
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    // Prefer uncalibrated; fall back so a device without it still records something useful.
    private val gyroscope =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val magnetometer =
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    // Deliberately TYPE_ROTATION_VECTOR and not TYPE_GAME_ROTATION_VECTOR: the comparison
    // worth having is against the magnetometer-aided solution, since heading is the number
    // this project is judged on. Recorded only; never an input (P3).
    private val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    val availability = Availability(
        hasAccelerometer = accelerometer != null,
        hasGyroscope = gyroscope != null,
        hasMagnetometer = magnetometer != null,
        gyroscopeUncalibrated = gyroscope?.type == Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
        magnetometerUncalibrated = magnetometer?.type == Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED,
        hasRotationVector = rotationVector != null
    )

    /**
     * The offset between `SensorEvent.timestamp` and `elapsedRealtimeNanos()`, sampled at
     * start. Zero on a well-behaved device; a large value means this device reports a
     * different clock base, which frame-conventions.md §7 warns about. Recorded rather than
     * corrected — a recording should carry the evidence, not a guess.
     */
    var clockOffsetNs: Long = 0L
        private set

    private var thread: HandlerThread? = null
    private var listener: SensorEventListener? = null

    val isRunning: Boolean get() = listener != null

    /**
     * Start delivering records to [onRecord], which is called on the sensor thread — not
     * the main thread. Whatever it does must be cheap and thread-safe.
     *
     * @return false when the device lacks a sensor the filter requires.
     */
    fun start(
        samplingPeriodUs: Int = DEFAULT_SAMPLING_PERIOD_US,
        recordReferenceAttitude: Boolean = true,
        onRecord: (SensorRecord) -> Unit
    ): Boolean {
        if (isRunning) stop()

        if (!availability.isUsable) {
            Log.e(TAG, "Device lacks a required sensor: $availability")
            return false
        }
        if (!availability.isFullyRaw) {
            Log.w(
                TAG,
                "Falling back to calibrated sensors (gyroUncal=${availability.gyroscopeUncalibrated}, " +
                    "magUncal=${availability.magnetometerUncalibrated}) - recordings will note this"
            )
        }

        val handlerThread = HandlerThread("raw-sensors", Process.THREAD_PRIORITY_URGENT_AUDIO)
        handlerThread.start()
        thread = handlerThread
        val handler = Handler(handlerThread.looper)

        val eventListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val record = event.toRecord() ?: return
                onRecord(record)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                // Android's own opinion of magnetometer quality. Logged rather than acted
                // upon: M3's gates measure the field against the WMM model instead, which
                // is a measurement rather than an opinion (P4, P6).
                Log.d(TAG, "Accuracy changed: ${sensor?.stringType} -> $accuracy")
            }
        }
        listener = eventListener

        listOfNotNull(accelerometer, gyroscope, magnetometer).forEach { sensor ->
            val ok = sensorManager.registerListener(
                eventListener, sensor, samplingPeriodUs, NO_BATCHING_US, handler
            )
            Log.d(TAG, "Registered ${sensor.stringType} at ${samplingPeriodUs}us: $ok")
        }

        // The reference trace is registered at a lower rate on purpose. It is never
        // integrated, only compared, so it needs to resolve boat motion rather than the
        // filter's step size — and at 200 Hz it would be a third of the recording's bulk
        // for no extra answer.
        if (recordReferenceAttitude) {
            rotationVector?.let { sensor ->
                val ok = sensorManager.registerListener(
                    eventListener, sensor, REFERENCE_SAMPLING_PERIOD_US, NO_BATCHING_US, handler
                )
                Log.d(TAG, "Registered ${sensor.stringType} (comparison only): $ok")
            } ?: Log.d(TAG, "No TYPE_ROTATION_VECTOR - recording without a comparison trace")
        }

        clockOffsetNs = 0L
        Log.i(TAG, "Raw sensor ingestion started at ${1_000_000 / samplingPeriodUs} Hz")
        return true
    }

    fun stop() {
        listener?.let { sensorManager.unregisterListener(it) }
        listener = null
        thread?.quitSafely()
        thread = null
        Log.d(TAG, "Raw sensor ingestion stopped")
    }

    /**
     * Record the observed clock offset from the first event seen. Called by the owner
     * rather than internally so it stays a diagnostic rather than a hidden correction.
     */
    fun noteClockBase(eventTimestampNs: Long) {
        clockOffsetNs = SystemClock.elapsedRealtimeNanos() - eventTimestampNs
    }

    private fun SensorEvent.toRecord(): SensorRecord? = when (sensor.type) {
        Sensor.TYPE_ACCELEROMETER ->
            AccelRecord(timestamp, values[0], values[1], values[2])

        // values[0..2] are the rate WITHOUT drift compensation; values[3..5] are the HAL's
        // drift estimate. Both are recorded (§4.2).
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED ->
            GyroRecord(timestamp, values[0], values[1], values[2], values[3], values[4], values[5])
        Sensor.TYPE_GYROSCOPE ->
            GyroRecord(timestamp, values[0], values[1], values[2])

        // values[0..2] are the field WITHOUT hard-iron correction; values[3..5] are the
        // HAL's bias estimate. Both are recorded so the M2 boundary can be crossed (§4.3).
        Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED ->
            MagRecord(timestamp, values[0], values[1], values[2], values[3], values[4], values[5])
        // Stored exactly as delivered: scalar-LAST, with values[3] absent on many devices.
        // The conversion into our scalar-first convention lives in RotationVectorRecord.
        Sensor.TYPE_ROTATION_VECTOR -> RotationVectorRecord(
            timestamp, values[0], values[1], values[2], values.getOrNull(3)
        )

        else -> null
    }
}
