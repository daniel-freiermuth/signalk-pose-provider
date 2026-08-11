package com.signalk.companion.replay

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A file-backed recording: where it lands, what it is called, and its live status.
 *
 * [RecordingWriter] deliberately knows only about a [java.io.Writer]; this is the other half
 * — the Android-side decisions it refused to make. Split that way because the formatting and
 * budget logic is worth testing on the JVM, and none of what is here can be.
 *
 * **Location:** `getExternalFilesDir("recordings")`, not `filesDir`. Both are app-private and
 * need no permission, but the external one is reachable over USB and from a file manager,
 * which is how a recording actually gets off the boat and onto a machine that can replay it.
 * A recording nobody can extract is not a regression dataset.
 *
 * **Threading:** this class *is* synchronised, unlike [RecordingWriter], because a recording
 * genuinely has two producers — raw sensor records arrive on the sensor thread at ~600 Hz,
 * and GNSS fixes arrive on whichever looper delivers them. Two unsynchronised writers into
 * one `Writer` interleave half-lines and race the byte counter, and a corrupt recording is
 * discovered long after the sail that produced it. The lock is uncontended almost always and
 * guards a buffered append, so the cost does not show at these rates.
 */
class RecordingSession(private val context: Context) {

    /** What the UI needs to show, and what a bug report needs to quote. */
    data class Status(
        val isRecording: Boolean = false,
        val fileName: String? = null,
        val recordCount: Long = 0L,
        val bytesWritten: Long = 0L,
        /** True once the byte budget stopped the recording short. */
        val isTruncated: Boolean = false,
        val error: String? = null
    )

    companion object {
        private const val TAG = "RecordingSession"
        private const val DIRECTORY = "recordings"
        const val FILE_EXTENSION = ".skpose"

        /** Buffer well above one line so the 600 Hz write path rarely touches the disk. */
        private const val BUFFER_BYTES = 64 * 1024
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile private var writer: RecordingWriter? = null
    private var file: File? = null

    val isRecording: Boolean get() = writer != null

    /**
     * Open a new recording. Returns the file, or null if it could not be created — a failure
     * to record must not take down the sensor pipeline that was about to feed it.
     *
     * The name carries wall-clock local time purely so a human can find the right sail in a
     * directory listing. Nothing in the format uses it for timing; every timestamp inside is
     * the monotonic sensor clock (frame-conventions.md §7), and the header records the
     * correspondence between the two.
     */
    @Synchronized
    fun start(deviceDescription: String = defaultDeviceDescription()): File? {
        if (isRecording) {
            Log.w(TAG, "Recording already in progress: ${file?.name}")
            return file
        }

        return try {
            val directory = File(
                context.getExternalFilesDir(null) ?: context.filesDir, DIRECTORY
            )
            if (!directory.exists() && !directory.mkdirs()) {
                throw java.io.IOException("could not create ${directory.absolutePath}")
            }

            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val target = File(directory, "$stamp$FILE_EXTENSION")

            val recordingWriter = RecordingWriter(
                BufferedWriter(FileWriter(target), BUFFER_BYTES)
            )
            recordingWriter.writeHeader(
                deviceDescription = deviceDescription,
                wallClockMs = System.currentTimeMillis(),
                bootTimeNs = SystemClock.elapsedRealtimeNanos()
            )

            writer = recordingWriter
            file = target
            _status.value = Status(isRecording = true, fileName = target.name)
            Log.i(TAG, "Recording to ${target.absolutePath}")
            target
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            writer = null
            file = null
            _status.value = Status(error = e.message ?: e.javaClass.simpleName)
            null
        }
    }

    /**
     * Append one record. Called on the sensor thread; cheap and non-throwing by design.
     *
     * Status is refreshed on a coarse interval rather than per record: publishing a
     * [StateFlow] update 600 times a second would wake Compose on every sensor sample, which
     * is the same main-thread contention [com.signalk.companion.service.RawSensorSource]
     * moved the callbacks off the main looper to avoid.
     */
    @Synchronized
    fun write(record: SensorRecord) {
        val active = writer ?: return
        active.write(record)
        if (active.recordCount % STATUS_EVERY == 0L) publishStatus(active)
    }

    /** Close the recording and return the file, or null if none was open. */
    @Synchronized
    fun stop(): File? {
        val active = writer ?: return null
        val target = file
        writer = null
        try {
            active.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close recording cleanly - the tail may be missing", e)
        }
        _status.value = Status(
            isRecording = false,
            fileName = target?.name,
            recordCount = active.recordCount,
            bytesWritten = active.bytesWritten,
            isTruncated = active.isTruncated
        )
        Log.i(TAG, "Recording stopped: ${active.recordCount} records, ${active.bytesWritten} bytes")
        file = null
        return target
    }

    /** Existing recordings, newest first. */
    fun list(): List<File> {
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, DIRECTORY)
        return directory.listFiles { f -> f.name.endsWith(FILE_EXTENSION) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    private fun publishStatus(active: RecordingWriter) {
        _status.value = Status(
            isRecording = true,
            fileName = file?.name,
            recordCount = active.recordCount,
            bytesWritten = active.bytesWritten,
            isTruncated = active.isTruncated
        )
    }

    private fun defaultDeviceDescription(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} android-${Build.VERSION.SDK_INT}"
}

/** Roughly twice a second at full rate — often enough for a progress readout, rare enough not to matter. */
private const val STATUS_EVERY = 256L
