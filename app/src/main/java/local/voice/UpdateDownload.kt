package local.voice

import java.io.InputStream
import java.io.OutputStream

/** Stream bundled speech models in the APK without retaining the download in RAM. */
internal object UpdateDownload {
    const val MAX_APK_BYTES = 1024L * 1024 * 1024

    fun copy(input: InputStream, output: OutputStream, ensureActive: () -> Unit = {}): Long {
        val buffer = ByteArray(64 * 1024)
        var size = 0L
        while (true) {
            ensureActive()
            val count = input.read(buffer)
            if (count < 0) return size
            check(size + count <= MAX_APK_BYTES) { "APK слишком большой." }
            output.write(buffer, 0, count)
            size += count
        }
    }
}
