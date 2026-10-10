package local.voice

import org.junit.Assert.*
import org.junit.Test

class HeadsetAudioEndTest {
    @Test fun endingAnAlreadyConnectedHelmetDoesNotNeedAnotherAssistantIntent() {
        val events = HeadsetAudioEnd("helmet")
        assertTrue(events.update("helmet", 10, 12))
        assertFalse("Duplicate disconnect stopped a later session", events.update("helmet", 10, 12))
    }
    @Test fun otherHeadsetsCannotStopTheSelectedConversation() {
        val events = HeadsetAudioEnd("helmet")
        assertFalse(events.update("earbuds", 12, 11))
        assertFalse(events.update("earbuds", 10, 12))
        assertFalse(events.update(null, 10, 12))
        assertTrue(events.update("HELMET", 10, 12))
    }
    @Test fun initialDisconnectedOrConnectingStatesAreNotASecondButtonPress() {
        val events = HeadsetAudioEnd("helmet")
        assertFalse(events.update("helmet", 10, -1))
        assertFalse(events.update("helmet", 11, 10))
        assertFalse(events.update("helmet", 10, 11))
        assertFalse(events.update("helmet", 12, 11))
        assertTrue(events.update("helmet", 10, 11))
    }
    @Test fun missingIdentityNeverStopsAnUnrelatedRoute() {
        assertFalse(HeadsetAudioEnd("").update("helmet", 10, 12))
    }
}
