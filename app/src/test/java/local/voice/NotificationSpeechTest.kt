package local.voice
import android.app.Notification
import org.junit.Assert.*
import org.junit.Test

class NotificationSpeechTest {
    private fun item(text: String, visibility: Int = Notification.VISIBILITY_PRIVATE, flags: Int = 0, time: Long = 1, pkg: String = "external") =
        NotificationSpeech.Item(pkg, "Тест", "Отправитель", text, visibility, flags, time)
    @Test fun lockPolicySecretOngoingAndOwnNotificationsAreFiltered() {
        val items = listOf(item("PRIVATE_CONTENT"), item("PUBLIC_CONTENT", Notification.VISIBILITY_PUBLIC), item("секретное", Notification.VISIBILITY_SECRET),
            item("сервис", flags = Notification.FLAG_ONGOING_EVENT), item("сводка", flags = Notification.FLAG_GROUP_SUMMARY), item("собственное", pkg = "own"))
        val locked = NotificationSpeech.summarize(items, "own", true, false, false)
        assertTrue(locked.contains("PUBLIC_CONTENT")); assertFalse(locked.contains("PRIVATE_CONTENT"))
        for (result in listOf(NotificationSpeech.summarize(items, "own", false, false, false), NotificationSpeech.summarize(items, "own", true, true, false))) {
            assertTrue(result.contains("PRIVATE_CONTENT"))
            for (hidden in listOf("секретное", "сервис", "сводка", "собственное")) assertFalse(result.contains(hidden))
        }
    }
    @Test fun sensitivityDescribesOnlyTheSelectedSpokenContent() {
        val items = listOf(item("private", time = 1), item("public", Notification.VISIBILITY_PUBLIC, time = 2))
        val latest = NotificationSpeech.prepare(items, "own", false, false, true)
        assertTrue(latest.containsNotifications); assertFalse(latest.containsPrivate)
        assertTrue(NotificationSpeech.prepare(items, "own", false, false, false).containsPrivate)
        assertFalse(NotificationSpeech.prepare(items, "own", true, false, false).containsPrivate)
        val hidden = NotificationSpeech.prepare(listOf(item("secret", Notification.VISIBILITY_SECRET)), "own", false, true, false)
        assertFalse(hidden.containsNotifications); assertFalse(hidden.containsPrivate)
    }
    @Test fun readingIsBoundedSortedAndControlCharactersAreRemoved() {
        val items = (1..6).map { item("сообщение $it\n\u0000" + "x".repeat(500), time = it.toLong()) }
        val all = NotificationSpeech.summarize(items, "own", false, false, false)
        assertTrue(all.indexOf("сообщение 6") < all.indexOf("сообщение 5"))
        assertFalse(all.contains("сообщение 3")); assertTrue(all.length < 1200)
        assertFalse(all.contains('\u0000')); assertFalse(all.contains('\n'))
        val last = NotificationSpeech.summarize(items, "own", false, false, true)
        assertTrue(last.contains("сообщение 6")); assertFalse(last.contains("сообщение 5"))
        assertEquals(VoiceCommand.Notifications(false), VoiceCommands.parse("прочитай уведомления"))
        assertEquals(VoiceCommand.Notifications(true), VoiceCommands.parse("Последнее уведомление"))
    }
}
