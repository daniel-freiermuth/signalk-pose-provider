package com.signalk.companion.replay

import java.io.Closeable
import java.io.Writer

/**
 * Writes [SensorRecord]s in the [RecordingFormat] line format.
 *
 * Deliberately takes a [Writer] rather than a file: it makes the whole class testable
 * against a `StringWriter`, and it keeps the decision about *where* a recording lands —
 * app-private storage, a share target, a debug dump — out of the formatting logic.
 *
 * **Throughput is not trivial.** At 200 Hz across three sensors this emits roughly 600
 * lines/second, about 35 kB/s, so a six-hour passage produces on the order of 750 MB. That
 * is affordable on a modern phone but not something to leave running by accident, which is
 * why recording is an explicit user action with a byte budget rather than a background
 * default. [maxBytes] stops a forgotten recording from filling the device: writing stops at
 * the limit and [isTruncated] reports it, instead of failing the sail with an IO error
 * halfway through.
 *
 * Not thread-safe. Feed it from the sensor thread that produced the records.
 */
class RecordingWriter(
    private val sink: Writer,
    private val maxBytes: Long = DEFAULT_MAX_BYTES
) : Closeable {

    companion object {
        /** ~1 GB: several hours at full rate, and well inside typical free space. */
        const val DEFAULT_MAX_BYTES = 1_000_000_000L

        /**
         * Flush every N records rather than every record. A per-record flush at 600 Hz is
         * a syscall storm; batching bounds the loss on an abrupt kill to a fraction of a
         * second of data, which the format already tolerates (a truncated final line is
         * skipped on parse).
         */
        const val FLUSH_EVERY = 200
    }

    var recordCount: Long = 0L
        private set

    var bytesWritten: Long = 0L
        private set

    /** True once [maxBytes] was reached and records started being dropped. */
    var isTruncated: Boolean = false
        private set

    private var sinceFlush = 0
    private var closed = false

    /** Write the format header. Call once, before any records. */
    fun writeHeader(deviceDescription: String, wallClockMs: Long, bootTimeNs: Long) {
        val header = RecordingFormat.header(deviceDescription, wallClockMs, bootTimeNs)
        sink.write(header)
        bytesWritten += header.length
    }

    /**
     * Append one record. Silently becomes a no-op once the byte budget is exhausted or the
     * writer is closed — a recording that stops early is still a usable recording, and
     * throwing here would take down the sensor callback that called it.
     */
    fun write(record: SensorRecord) {
        if (closed || isTruncated) return

        val line = RecordingFormat.format(record)
        if (bytesWritten + line.length + 1 > maxBytes) {
            isTruncated = true
            sink.write("# truncated: byte budget of $maxBytes reached\n")
            sink.flush()
            return
        }

        sink.write(line)
        sink.write("\n")
        bytesWritten += line.length + 1
        recordCount++

        if (++sinceFlush >= FLUSH_EVERY) {
            sink.flush()
            sinceFlush = 0
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // Flush before closing so the tail of the recording is not lost; a failure here is
        // worth surfacing, unlike a failure mid-recording.
        sink.flush()
        sink.close()
    }
}
