package com.signalk.companion.replay

/**
 * Recording format for raw sensor and GNSS data, and its parser.
 *
 * This is the M1 deliverable that turns every sail into a regression dataset. Filter gains
 * and gate thresholds are tuned against recordings, not against the water: the boat is a
 * slow, expensive and unrepeatable test fixture, and a recording is none of those things.
 *
 * **Everything is raw.** Uncalibrated magnetometer and gyroscope samples are stored with
 * the HAL's own bias estimates alongside them, rather than pre-subtracted, so a recording
 * stays valid when the correction strategy changes — which it will at M2. A recording that
 * baked in today's correction could not be replayed against tomorrow's.
 *
 * **Timestamps are the monotonic sensor clock** (`SensorEvent.timestamp`), never wall clock
 * (frame-conventions.md §7). GNSS fixes carry `Location.getElapsedRealtimeNanos()`, which
 * shares that base — that is what lets a fix be placed on the IMU timeline in M4.
 *
 * Line-oriented text on purpose: appendable at 200 Hz without a serialisation library,
 * greppable, diffable, and readable in a bug report. One record per line:
 * ```text
 * # comment or header
 * A <ns> <x> <y> <z>                                  accelerometer, m/s²
 * G <ns> <x> <y> <z> <bx> <by> <bz>                   gyroscope, rad/s + HAL drift estimate
 * M <ns> <x> <y> <z> <bx> <by> <bz>                   magnetometer, µT + HAL hard-iron estimate
 * F <ns> <lat> <lon> <alt> <sog> <cog> <hAcc> <sAcc> <cAcc>   GNSS fix; '-' where absent
 * ```
 */
sealed interface SensorRecord {
    /** Monotonic nanoseconds, shared time base across every record type (§7). */
    val timestampNs: Long
}

/** Specific force in device coordinates, m/s². Reads +g along up at rest (§4.1). */
data class AccelRecord(
    override val timestampNs: Long,
    val x: Float, val y: Float, val z: Float
) : SensorRecord

/**
 * Angular rate in device coordinates, rad/s, **without** drift compensation, plus the HAL's
 * estimated drift — `TYPE_GYROSCOPE_UNCALIBRATED`'s `values[0..2]` and `values[3..5]`
 * respectively (§4.2). Both are stored; which one a replay trusts is its decision.
 */
data class GyroRecord(
    override val timestampNs: Long,
    val x: Float, val y: Float, val z: Float,
    val driftX: Float = 0f, val driftY: Float = 0f, val driftZ: Float = 0f
) : SensorRecord

/**
 * Magnetic field in device coordinates, µT, **without** hard-iron correction, plus the HAL's
 * estimated bias — `TYPE_MAGNETIC_FIELD_UNCALIBRATED`'s two halves (§4.3).
 *
 * Storing both is what keeps a recording useful across the M2 boundary: today a replay may
 * subtract the HAL estimate, after M2 it subtracts our own ellipsoid fit, and the same
 * recording answers both questions.
 */
data class MagRecord(
    override val timestampNs: Long,
    val x: Float, val y: Float, val z: Float,
    val biasX: Float = 0f, val biasY: Float = 0f, val biasZ: Float = 0f
) : SensorRecord

/** A GNSS fix, timestamped on the sensor clock so M4 can align it with IMU propagation. */
data class FixRecord(
    override val timestampNs: Long,
    val latitude: Double, val longitude: Double,
    val altitude: Double? = null,
    val speedMps: Float? = null,
    val courseDeg: Float? = null,
    val horizontalAccuracyM: Float? = null,
    val speedAccuracyMps: Float? = null,
    val courseAccuracyDeg: Float? = null
) : SensorRecord

object RecordingFormat {

    const val VERSION = 1
    private const val ABSENT = "-"

    fun header(deviceDescription: String, wallClockMs: Long, bootTimeNs: Long): String =
        "# signalk-pose-provider recording v$VERSION\n" +
            "# device=$deviceDescription\n" +
            "# wallClockMs=$wallClockMs bootTimeNs=$bootTimeNs\n" +
            "# timestamps are monotonic ns; wallClockMs is for correlation only, never for dt\n"

    /** One record as a line, without the trailing newline. */
    fun format(record: SensorRecord): String = when (record) {
        is AccelRecord -> "A ${record.timestampNs} ${record.x} ${record.y} ${record.z}"
        is GyroRecord -> "G ${record.timestampNs} ${record.x} ${record.y} ${record.z} " +
            "${record.driftX} ${record.driftY} ${record.driftZ}"
        is MagRecord -> "M ${record.timestampNs} ${record.x} ${record.y} ${record.z} " +
            "${record.biasX} ${record.biasY} ${record.biasZ}"
        is FixRecord -> "F ${record.timestampNs} ${record.latitude} ${record.longitude} " +
            "${opt(record.altitude)} ${opt(record.speedMps)} ${opt(record.courseDeg)} " +
            "${opt(record.horizontalAccuracyM)} ${opt(record.speedAccuracyMps)} " +
            opt(record.courseAccuracyDeg)
    }

    /**
     * Parse one line. Returns null for comments, blanks, and malformed lines.
     *
     * Malformed lines are skipped rather than thrown on: a recording is a field artefact
     * that may have been truncated by a battery pull mid-write, and losing the last partial
     * line should not cost you the sail.
     */
    fun parse(line: String): SensorRecord? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
        val f = trimmed.split(' ')
        return try {
            when (f[0]) {
                "A" -> if (f.size < 5) null else
                    AccelRecord(f[1].toLong(), f[2].toFloat(), f[3].toFloat(), f[4].toFloat())
                "G" -> if (f.size < 5) null else GyroRecord(
                    f[1].toLong(), f[2].toFloat(), f[3].toFloat(), f[4].toFloat(),
                    f.getOrNull(5)?.toFloat() ?: 0f,
                    f.getOrNull(6)?.toFloat() ?: 0f,
                    f.getOrNull(7)?.toFloat() ?: 0f
                )
                "M" -> if (f.size < 5) null else MagRecord(
                    f[1].toLong(), f[2].toFloat(), f[3].toFloat(), f[4].toFloat(),
                    f.getOrNull(5)?.toFloat() ?: 0f,
                    f.getOrNull(6)?.toFloat() ?: 0f,
                    f.getOrNull(7)?.toFloat() ?: 0f
                )
                "F" -> if (f.size < 4) null else FixRecord(
                    f[1].toLong(), f[2].toDouble(), f[3].toDouble(),
                    optD(f.getOrNull(4)), optF(f.getOrNull(5)), optF(f.getOrNull(6)),
                    optF(f.getOrNull(7)), optF(f.getOrNull(8)), optF(f.getOrNull(9))
                )
                else -> null
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    fun parseAll(lines: Sequence<String>): Sequence<SensorRecord> = lines.mapNotNull(::parse)

    private fun opt(v: Any?): String = v?.toString() ?: ABSENT
    private fun optF(s: String?): Float? = if (s == null || s == ABSENT) null else s.toFloatOrNull()
    private fun optD(s: String?): Double? = if (s == null || s == ABSENT) null else s.toDoubleOrNull()
}
