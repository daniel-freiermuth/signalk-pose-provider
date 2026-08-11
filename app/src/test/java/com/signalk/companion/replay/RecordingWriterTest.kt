package com.signalk.companion.replay

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.StringWriter

class RecordingWriterTest {

    private fun sample(t: Long) = AccelRecord(t, 0.1f, -0.2f, 9.81f)

    @Test
    fun `written records parse back identically`() {
        val out = StringWriter()
        val records = listOf(
            AccelRecord(1_000L, 0.1f, -0.2f, 9.81f),
            GyroRecord(2_000L, 0.01f, 0.02f, -0.03f, 1e-4f, 0f, 0f),
            MagRecord(3_000L, 10f, -20f, -40f, 1f, 2f, 3f),
            FixRecord(4_000L, 59.3293, 18.0686, speedMps = 2.5f)
        )
        RecordingWriter(out).use { w ->
            w.writeHeader("test-device", 1L, 2L)
            records.forEach(w::write)
        }
        assertEquals(records, RecordingFormat.parseAll(out.toString().lineSequence()).toList())
    }

    @Test
    fun `header is written and skipped on parse`() {
        val out = StringWriter()
        RecordingWriter(out).use { it.writeHeader("Pixel 8", 1700000000000L, 42L) }
        val text = out.toString()
        assertTrue(text.contains("Pixel 8"), "header should carry the device description")
        assertTrue(text.contains("monotonic"), "header should state the time base")
        assertTrue(RecordingFormat.parseAll(text.lineSequence()).toList().isEmpty())
    }

    @Test
    fun `counts records and bytes`() {
        val out = StringWriter()
        val w = RecordingWriter(out)
        repeat(10) { w.write(sample(it.toLong())) }
        w.close()
        assertEquals(10L, w.recordCount)
        assertEquals(out.toString().length.toLong(), w.bytesWritten)
    }

    @Test
    fun `stops at the byte budget instead of failing`() {
        // A forgotten recording must not fill the device, and must not take down the sensor
        // callback that feeds it — it stops, says so, and stays parseable.
        val out = StringWriter()
        val w = RecordingWriter(out, maxBytes = 200)
        repeat(1000) { w.write(sample(it.toLong())) }
        w.close()

        assertTrue(w.isTruncated, "the budget must be reported, not silently exceeded")
        assertTrue(w.bytesWritten <= 200, "budget exceeded: ${w.bytesWritten}")
        assertTrue(w.recordCount in 1..999, "some records should have been written")
        assertTrue(out.toString().contains("# truncated"), "truncation should be visible in the file")
        // Everything written before the cut is still valid.
        val parsed = RecordingFormat.parseAll(out.toString().lineSequence()).toList()
        assertEquals(w.recordCount, parsed.size.toLong())
    }

    @Test
    fun `writing after close is a no-op rather than a crash`() {
        val out = StringWriter()
        val w = RecordingWriter(out)
        w.write(sample(1L))
        w.close()
        w.write(sample(2L)) // late sensor callback after stop — must not throw
        assertEquals(1L, w.recordCount)
    }

    @Test
    fun `close is idempotent`() {
        val w = RecordingWriter(StringWriter())
        w.close()
        w.close()
    }

    @Test
    fun `a truncated final line does not invalidate the recording`() {
        // Simulates a battery pull mid-write: the last line is cut off.
        val out = StringWriter()
        RecordingWriter(out).use { w -> repeat(5) { w.write(sample(it.toLong())) } }
        val mangled = out.toString().dropLast(12) // chop into the final record
        val parsed = RecordingFormat.parseAll(mangled.lineSequence()).toList()
        assertTrue(parsed.size >= 4, "only the damaged tail should be lost, got ${parsed.size}")
    }
}
