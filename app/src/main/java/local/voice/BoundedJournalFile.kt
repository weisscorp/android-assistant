package local.voice

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Called by one writer. Retains complete UTF-8 JSONL records, never more than [limit] bytes. */
internal class BoundedJournalFile(val file: File, val limit: Long = 50_000_000L) {
    init {
        require(limit >= 128)
        file.parentFile?.mkdirs()
        File(file.path + ".tmp").delete()
        repairTail()
        if (file.length() > limit) retain(limit)
    }

    fun append(json: String) {
        require('\n' !in json && '\r' !in json) { "Expected a single JSONL record" }
        val bytes = (json + "\n").toByteArray(Charsets.UTF_8)
        require(bytes.size <= limit) { "Record exceeds journal capacity" }
        if (file.length() + bytes.size > limit) {
            // Evict in batches to avoid rewriting 50 MB after every subsequent token/event.
            retain(minOf(limit - bytes.size, limit * 3 / 4))
        }
        val before = file.length()
        try {
            FileOutputStream(file, true).use { it.write(bytes); it.fd.sync() }
        } catch (error: Exception) {
            // A failed append must not turn the next record into invalid JSON.
            runCatching { RandomAccessFile(file, "rw").use { it.setLength(before) } }
            throw error
        }
    }

    fun copyTo(output: OutputStream): Long = if (file.exists()) file.inputStream().use { it.copyTo(output) } else 0L
    fun clear() { if (file.exists()) RandomAccessFile(file, "rw").use { it.setLength(0); it.fd.sync() } }

    private fun repairTail() {
        if (!file.exists()) return
        RandomAccessFile(file, "rw").use { input ->
            var end = input.length()
            val buffer = ByteArray(8192)
            while (end > 0) {
                val start = maxOf(0L, end - buffer.size)
                input.seek(start)
                val count = (end - start).toInt()
                input.readFully(buffer, 0, count)
                for (index in count - 1 downTo 0) if (buffer[index] == 10.toByte()) {
                    input.setLength(start + index + 1)
                    return
                }
                end = start
            }
            input.setLength(0)
        }
    }

    private fun retain(bytes: Long) {
        if (!file.exists()) return
        val temporary = File(file.path + ".tmp")
        try {
            RandomAccessFile(file, "r").use { input ->
                var start = maxOf(0L, input.length() - bytes)
                if (start > 0) {
                    input.seek(start - 1)
                    if (input.read() != 10) {
                        input.seek(start)
                        while (input.filePointer < input.length() && input.read() != 10) { /* skip partial record */ }
                        start = input.filePointer
                    }
                }
                input.seek(start)
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}
