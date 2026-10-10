package local.voice

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class BoundedJournalFileTest {
    private fun withFile(test: (java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("journal-test").toFile()
        try { test(java.io.File(directory, "journal.jsonl")) } finally { directory.deleteRecursively() }
    }
    @Test fun rotationUsesUtf8BytesKeepsWholeRecentRecordsAndSurvivesReopening() = withFile { file ->
        val records = (0 until 250).map { "{\"n\":$it,\"text\":\"Привет 🎧, говорит человек\"}" }
        var store = BoundedJournalFile(file, 1024)
        records.forEachIndexed { index, record ->
            store.append(record)
            assertTrue(file.length() <= 1024)
            val lines = file.readLines()
            assertEquals(record, lines.last())
            assertEquals(records.subList(index + 1 - lines.size, index + 1), lines)
            assertFalse(file.readText().contains('\uFFFD'))
            if (index % 13 == 0) store = BoundedJournalFile(file, 1024)
        }
        val output = ByteArrayOutputStream()
        assertEquals(file.length(), store.copyTo(output))
        assertArrayEquals(file.readBytes(), output.toByteArray())
        assertFalse(java.io.File(file.path + ".tmp").exists())
    }
    @Test fun recoveryDiscardsOnlyIncompleteTrailingRecord() = withFile { file ->
        val complete = "{\"text\":\"Полная запись 🎧\"}\n"
        file.writeBytes(complete.toByteArray() + byteArrayOf(123, 34, -48))
        val store = BoundedJournalFile(file, 512)
        assertEquals(complete, file.readText())
        store.append("{\"next\":true}")
        assertEquals(2, file.readLines().size)
    }
    @Test fun startupEnforcesLimitAndHugeRecordCannotDestroyPreviousData() = withFile { file ->
        file.writeText((0 until 100).joinToString("\n", postfix = "\n") { "{\"n\":$it}" })
        val store = BoundedJournalFile(file, 128)
        assertTrue(file.length() <= 128)
        assertEquals("{\"n\":99}", file.readLines().last())
        val before = file.readBytes()
        try { store.append("x".repeat(129)); fail("Expected size rejection") } catch (_: IllegalArgumentException) { }
        assertArrayEquals(before, file.readBytes())
        store.clear(); assertEquals(0, file.length())
        store.append("{\"reset\":true}"); assertEquals("{\"reset\":true}\n", file.readText())
    }
}
