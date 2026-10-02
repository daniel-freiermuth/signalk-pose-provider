package com.signalk.companion.replay

import android.content.Context
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.io.IOException
import java.io.Writer
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class RecordingSessionTest {

    @TempDir
    lateinit var tempDir: File

    private fun contextWithFilesDir(dir: File): Context {
        val context = mock(Context::class.java)
        `when`(context.getExternalFilesDir(null)).thenReturn(dir)
        return context
    }

    private fun sample(t: Long) = AccelRecord(t, 0.1f, -0.2f, 9.81f)

    /** A sink that can be told to fail, standing in for a full disk or a dropped USB-MTP session. */
    private class FlakySink : Writer() {
        var failWrites = false
        var failClose = false
        var closed = false
        var failedWriteAttempts = 0

        override fun write(cbuf: CharArray, off: Int, len: Int) {
            if (failWrites) {
                failedWriteAttempts++
                throw IOException("No space left on device")
            }
            // Successful writes are discarded; these tests assert on status, not content.
        }

        override fun flush() = Unit

        override fun close() {
            closed = true
            if (failClose) throw IOException("close failed too")
        }
    }

    private fun sessionWith(sink: Writer, maxBytes: Long = RecordingWriter.DEFAULT_MAX_BYTES) =
        RecordingSession(contextWithFilesDir(tempDir), openSink = { sink }, maxBytes = maxBytes)

    private fun recordingFiles(): List<File> =
        File(tempDir, "recordings").listFiles()?.toList().orEmpty()

    @Test
    fun `start creates one recording file and reports it as recording`() {
        val session = RecordingSession(contextWithFilesDir(tempDir))

        val file = session.start("test-device")

        assertNotNull(file)
        assertTrue(file!!.exists())
        assertEquals(File(tempDir, "recordings"), file.parentFile)
        assertTrue(file.name.endsWith(RecordingSession.FILE_EXTENSION))
        assertTrue(session.isRecording)
        assertEquals(RecordingSession.Status(isRecording = true, fileName = file.name), session.status.value)
        assertEquals(file, session.stop())
    }

    @Test
    fun `start while already recording returns the same file and opens no second one`() {
        val session = RecordingSession(contextWithFilesDir(tempDir))

        val first = session.start("test-device")
        val second = session.start("test-device")

        assertEquals(first, second)
        assertEquals(listOf(first), recordingFiles())
        assertEquals(first, session.stop())
    }

    @Test
    fun `start returns null and reports the error when the recordings directory cannot be created`() {
        // A regular file where a parent directory should be makes mkdirs fail.
        val blocker = File(tempDir, "not-a-directory").apply { writeText("x") }
        val session = RecordingSession(contextWithFilesDir(File(blocker, "files")))

        val file = session.start("test-device")

        assertNull(file)
        assertFalse(session.isRecording)
        val status = session.status.value
        assertFalse(status.isRecording)
        assertTrue(status.error!!.contains("could not create"), status.error)
        // The failed start must leave the session usable as "not recording".
        session.write(sample(1L))
        assertNull(session.stop())
    }

    @Test
    fun `stop returns the file with final counts and a second stop returns null`() {
        val session = RecordingSession(contextWithFilesDir(tempDir))
        val file = session.start("test-device")!!
        val records = (1L..10L).map(::sample)

        records.forEach(session::write)
        val stopped = session.stop()

        assertEquals(file, stopped)
        assertFalse(session.isRecording)
        val status = session.status.value
        assertFalse(status.isRecording)
        assertEquals(file.name, status.fileName)
        assertEquals(10L, status.recordCount)
        assertEquals(file.length(), status.bytesWritten)
        assertFalse(status.isTruncated)
        assertNull(status.error)
        assertEquals(records, RecordingFormat.parseAll(file.readLines().asSequence()).toList())

        assertNull(session.stop())
        assertEquals(status, session.status.value)
    }

    @Test
    fun `a write failure stops the recording, closes the writer and reports the error without throwing`() {
        val sink = FlakySink()
        val session = sessionWith(sink)
        assertNotNull(session.start("test-device"))
        session.write(sample(1L))

        sink.failWrites = true
        session.write(sample(2L))

        assertFalse(session.isRecording)
        assertTrue(sink.closed)
        assertEquals(
            RecordingSession.Status(error = "No space left on device"),
            session.status.value
        )
        assertNull(session.stop())

        // Later sensor callbacks must not keep feeding the dead writer.
        val attempts = sink.failedWriteAttempts
        session.write(sample(3L))
        assertEquals(attempts, sink.failedWriteAttempts)
    }

    @Test
    fun `a close failure during write-failure cleanup is swallowed and the write error is still reported`() {
        val sink = FlakySink()
        val session = sessionWith(sink)
        assertNotNull(session.start("test-device"))

        sink.failWrites = true
        sink.failClose = true
        session.write(sample(1L))

        assertFalse(session.isRecording)
        assertEquals("No space left on device", session.status.value.error)
    }

    @Test
    fun `status is published on every 256th record and not in between`() {
        val session = sessionWith(FlakySink())
        assertNotNull(session.start("test-device"))

        (1L..255L).forEach { session.write(sample(it)) }
        assertEquals(0L, session.status.value.recordCount)

        session.write(sample(256L))
        val published = session.status.value
        assertEquals(256L, published.recordCount)
        assertTrue(published.isRecording)
        assertTrue(published.bytesWritten > 0L)

        session.write(sample(257L))
        assertEquals(published, session.status.value)

        (258L..512L).forEach { session.write(sample(it)) }
        assertEquals(512L, session.status.value.recordCount)
    }

    @Test
    fun `status reports truncation the moment the byte budget is hit, off the publish cadence`() {
        val header = RecordingFormat.header("test-device", System.currentTimeMillis(), 0L)
        // Room for the header and a handful of records - far fewer than the 256-record cadence.
        val session = sessionWith(FlakySink(), maxBytes = header.length + 400L)
        assertNotNull(session.start("test-device"))

        var writes = 0
        while (!session.status.value.isTruncated && writes < 255) {
            session.write(sample(1_000_000L + writes))
            writes++
        }

        val status = session.status.value
        assertTrue(status.isTruncated, "truncation not published within $writes writes")
        assertTrue(status.isRecording)
        assertTrue(status.recordCount in 1L until 255L, "recordCount=${status.recordCount}")
        assertNull(status.error)
    }

    @Test
    fun `concurrent writers from two threads produce only whole, parseable lines`() {
        val session = RecordingSession(contextWithFilesDir(tempDir))
        val file = session.start("test-device")!!
        val perThread = 20_000L
        val go = CountDownLatch(1)

        val producers = listOf(0L, perThread).map { offset ->
            thread {
                go.await()
                for (i in 1L..perThread) session.write(sample(offset + i))
            }
        }
        go.countDown()
        producers.forEach { it.join() }
        assertEquals(file, session.stop())

        val recordLines = file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(2 * perThread, recordLines.size.toLong())
        val parsed = recordLines.map { line ->
            RecordingFormat.parse(line) ?: throw AssertionError("unparseable line: $line")
        }
        assertEquals((1L..2 * perThread).toSet(), parsed.map { it.timestampNs }.toSet())
        assertEquals(2 * perThread, session.status.value.recordCount)
    }
}
