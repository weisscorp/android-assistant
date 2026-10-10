package local.voice

import org.junit.Assert.*
import org.junit.Test

class PresenceFactsTest {
    private val now = 1_790_000_000_000L
    @Test fun acceptsPointLinksButNotMapViewportOrNamedZeroCoordinates() {
        assertEquals(GeoFix(41.65,41.64), PresenceFacts.pointFromLink("geo:41.65,41.64"))
        assertEquals(GeoFix(41.65,41.64), PresenceFacts.pointFromLink("https://maps.google.com/?q=41.65,41.64"))
        assertEquals(GeoFix(41.65,41.64), PresenceFacts.pointFromLink("https://www.openstreetmap.org/?mlat=41.65&mlon=41.64"))
        assertNull(PresenceFacts.pointFromLink("https://www.google.com/maps/@41.65,41.64,12z"))
        assertNull(PresenceFacts.pointFromLink("https://www.openstreetmap.org/#map=14/41.65/41.64"))
        assertNull(PresenceFacts.pointFromLink("geo:0,0?q=Аэропорт"))
        assertNull(PresenceFacts.pointFromLink("geo:91,181"))
    }
    @Test fun sharedCaptionKeepsOneLinkAndMultipleLinksRequireSelection() {
        assertEquals("https://t.me/test_chat/22", PresenceFacts.sharedReference("Чат трансфера\nhttps://t.me/test_chat/22"))
        val two="https://example.org/one https://example.org/two"
        assertEquals(two, PresenceFacts.sharedReference(two))
    }
    @Test fun movementRequiresTimeAndDisplacementAboveGpsNoise() {
        val first=GeoFix(41.65,41.64,now-30_000,5.0)
        val second=GeoFix(41.65,41.642,now,5.0)
        assertEquals(90.0, PresenceFacts.movement(first,second,now)!!,1.0)
        assertNull(PresenceFacts.movement(first,second.copy(updatedAt=now-300_000),now))
        assertNull(PresenceFacts.movement(first,first.copy(updatedAt=now,longitude=41.64001),now))
        assertNull(PresenceFacts.movement(first,second.copy(updatedAt=now+1000),now))
        assertNull(PresenceFacts.movement(null,second.copy(heading=90.0),now))
        assertEquals(90.0,PresenceFacts.movement(null,second.copy(heading=90.0,speedMps=5.0),now)!!,0.0)
    }
    @Test fun speechUsesLandmarkAndDistanceWithoutCoordinatesOrInventedArrivalTime() {
        val current=GeoFix(41.65,41.642,now,5.0)
        val speech=PresenceFacts.speech("Курьер",PresenceEvidence(current,place="На улице Примерной",live=true),now,
            GeoFix(41.65,41.648,now,10.0),GeoFix(41.65,41.64,now-30_000,5.0),listOf("аэропорта" to GeoFix(41.65,41.66)))
        assertTrue(speech,speech.contains("сторону аэропорта"))
        assertTrue(speech,speech.contains("по прямой примерно 500 метров"))
        assertFalse(speech,speech.contains("41.65"))
        assertFalse(speech,speech.contains("прибудет"))
    }
    @Test fun stalePointAndSavedImageNeverDescribeCurrentMovement() {
        val old=GeoFix(41.65,41.64,now-600_000,5.0,90.0,5.0)
        val speech=PresenceFacts.speech("Трансфер",PresenceEvidence(old,live=true),now,GeoFix(41.65,41.65,now,5.0))
        assertTrue(speech,speech.contains("10 минут назад"))
        assertTrue(speech,speech.contains("от сохранённой точки"))
        assertFalse(speech,speech.contains("Движется"))
        val snapshot=PresenceFacts.speech("Друг",PresenceEvidence(distance=540.0,ageText="обновлена только что",snapshot=true),now)
        assertTrue(snapshot.contains("Сохранённый снимок")); assertTrue(snapshot.contains("На снимке"))
        assertFalse(snapshot.contains("Движется"))
    }
    @Test fun locationGrammarIsAnchoredAndDictatedMessagesStayOpaque() {
        assertEquals("курьер",PresenceFacts.query("Где сейчас курьер?"))
        assertNull(PresenceFacts.query("напиши жене сообщение где сейчас курьер"))
        assertEquals(VoiceCommand.ReadSource("курьер"),VoiceCommands.parse("прочитай источник курьер"))
        assertEquals(VoiceCommand.ReadSource("трансфер группы"),VoiceCommands.parse("Где трансфер группы?"))
        assertTrue(VoiceCommands.parse("где купить велосипед") is VoiceCommand.Chat)
        assertTrue(VoiceCommands.parse("напиши жене сообщение где курьер") is VoiceCommand.Telegram)
    }
    @Test fun distanceHandlesAntimeridianAndBadNumbersAreRejected() {
        assertEquals(222.4,PresenceFacts.distance(GeoFix(0.0,179.999),GeoFix(0.0,-179.999)),1.0)
        assertFalse(GeoFix(Double.NaN,0.0).valid()); assertFalse(GeoFix(0.0,0.0,accuracy=-1.0).valid())
        assertFalse(GeoFix(0.0,0.0,speedMps=Double.POSITIVE_INFINITY).valid())
    }
    @Test fun readOnlySourcesAcceptGroupsWhileMessageRecipientMatchingStaysNarrow() {
        assertTrue(TelegramUiText.sourceChat("Трансфер, группа, 20 участников","Трансфер"))
        assertFalse(TelegramUiText.person("Трансфер, группа, 20 участников","Трансфер"))
        assertFalse(TelegramUiText.sourceChat("Другой чат, группа","Трансфер"))
        assertFalse(PresenceFacts.forSpeech("ссылка https://example.org/trip?t=SECRET 41.6500,41.6400").contains("SECRET"))
        assertFalse(PresenceFacts.forSpeech("точка 41.6500,41.6400").contains("41.6500"))
    }
}
