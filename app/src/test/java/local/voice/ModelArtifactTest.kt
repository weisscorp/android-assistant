package local.voice

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class ModelArtifactTest {
    private val data = "GGUFtest model bytes".toByteArray()
    private val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private fun copy(bytes: ByteArray = data, size: Long = data.size.toLong(), sha: String = hash, active: () -> Boolean = { true }): ByteArray {
        val output = ByteArrayOutputStream()
        ModelArtifact.copyVerified(ByteArrayInputStream(bytes), output, size, sha, active) {}
        return output.toByteArray()
    }
    @Test fun verifiedModelIsCopied() { assertArrayEquals(data, copy()) }
    @Test fun corruptedModelIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { copy(bytes = data.copyOf().apply { this[5] = 0 }) }
    }
    @Test fun partialAndOversizedFilesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { copy(bytes = data.copyOf(data.size - 1)) }
        assertThrows(IllegalArgumentException::class.java) { copy(bytes = data + byteArrayOf(0)) }
    }
    @Test fun htmlInsteadOfModelIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { copy(bytes = "<html>bad gateway</html>".toByteArray()) }
    }
    @Test fun cancellationPreventsAcceptance() {
        assertThrows(IllegalStateException::class.java) { copy(active = { false }) }
    }
}
