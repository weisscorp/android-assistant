package local.voice

import org.junit.Assert.*
import org.junit.Test

class NowPlayingTest {
    @Test fun musicQuestionsOfferReaderToLlmWithoutExecutingItInParser() {
        for (question in listOf("что щас играет за песня", "Что сейчас играет?", "какая песня играет",
            "что за музыка сейчас играет", "какой трек сейчас играет у меня", "Как называется эта песня?",
            "кто поёт эту песню", "кто поёт", "кто исполняет", "назови текущий трек", "название текущей песни", "что я сейчас слушаю")) {
            assertTrue(question, VoiceCommands.parse(question) is VoiceCommand.Chat)
            assertTrue(question, ModelIntent.needsTools(question))
            val reader = ToolInstructions.search(question).single { it.name == "media" }
            assertEquals(question, "info", reader.argument)
            assertTrue(reader.instruction, reader.instruction.contains("read current player"))
            assertFalse("Device facts must be read before answering", ToolInstructions.search(question).any { it.name == "answer" })
        }
    }

    @Test fun musicKnowledgeSearchAndDictatedTextAreNotCurrentTrackReads() {
        for (question in listOf("что сейчас играет роль в экономике", "какая песня играет в фильме Интерстеллар",
            "найди песню которая сейчас играет", "что сейчас играет и поставь на паузу", "не говори что сейчас играет")) {
            assertFalse(question, NowPlayingRequest.matches(question))
        }
        assertEquals(VoiceCommand.Write("что сейчас играет"), VoiceCommands.parse("напиши что сейчас играет"))
    }

    @Test fun replyUsesTrackMetadataAndDoesNotInventMissingFields() {
        assertEquals("Сейчас играет «Тестовый трек» — Исполнитель.",
            NowPlayingReply.text("Тестовый трек", "Исполнитель", NowPlayingReply.State.PLAYING))
        assertEquals("На паузе «Тестовый трек».",
            NowPlayingReply.text("Тестовый трек", null, NowPlayingReply.State.PAUSED))
        assertEquals("Проигрыватель не передал название и исполнителя текущего трека.",
            NowPlayingReply.text(" ", null, NowPlayingReply.State.PLAYING))
        assertTrue(NowPlayingReply.text(null, "Исполнитель", NowPlayingReply.State.LOADING).contains("не передал"))
        assertTrue(NowPlayingReply.text("Трек", null, NowPlayingReply.State.STOPPED).contains("остановлено"))
    }

    @Test fun metadataIsBoundedAndCleanedForSpeech() {
        val result = NowPlayingReply.text("  A\nB\u0000C  ", "Z".repeat(1000), NowPlayingReply.State.PLAYING)
        assertTrue(result, result.contains("«A B C»"))
        assertFalse(result.contains('\n'))
        assertFalse(result.contains('\u0000'))
        assertTrue(result.length < 220)
    }
}
