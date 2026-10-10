package local.voice

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class UpdateDownloadTest {
    /** Virtual zero bytes: exercise large streams without a gigabyte file or array. */
    private class Stream(var remaining: Long, val afterRead: () -> Unit = {}) : InputStream() {
        override fun read(): Int = if (remaining > 0) { remaining--; 0 } else -1
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining == 0L) return -1
            val count = minOf(length.toLong(), remaining).toInt()
            remaining -= count
            afterRead()
            return count
        }
    }
    private class Sink : OutputStream() {
        var bytes = 0L
        override fun write(value: Int) { bytes++ }
        override fun write(buffer: ByteArray, offset: Int, length: Int) { bytes += length }
    }
    @Test fun speechBundledApkLargerThanOldLimitCanDownload() {
        val size = 477L * 1024 * 1024
        val output = Sink()
        assertEquals(size, UpdateDownload.copy(Stream(size), output))
        assertEquals(size, output.bytes)
    }
    @Test fun exactLimitIsAccepted() {
        val output = Sink()
        assertEquals(1024L * 1024 * 1024, UpdateDownload.copy(Stream(1024L * 1024 * 1024), output))
        assertEquals(1024L * 1024 * 1024, output.bytes)
    }
    @Test fun byteBeyondLimitIsNeverWritten() {
        val output = Sink()
        val error = runCatching { UpdateDownload.copy(Stream(1024L * 1024 * 1024 + 1), output) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals("APK слишком большой.", error?.message)
        assertEquals(1024L * 1024 * 1024, output.bytes)
    }
    @Test fun cancellationDuringTransferStopsFurtherReading() {
        var cancelled = false
        val input = Stream(477L * 1024 * 1024) { cancelled = true }
        val output = Sink()
        val error = runCatching {
            UpdateDownload.copy(input, output) { if (cancelled) throw CancellationException("cancelled") }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertTrue(output.bytes in 1 until 477L * 1024 * 1024)
        assertEquals(477L * 1024 * 1024 - output.bytes, input.remaining)
    }
}
