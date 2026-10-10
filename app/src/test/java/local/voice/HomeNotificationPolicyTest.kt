package local.voice

import org.junit.Assert.*
import org.junit.Test

class HomeNotificationPolicyTest {
    @Test fun importantEventsCanPopupButUpdatesCannot() {
        assertTrue(HomeNotificationPolicy.popup("message", "important", true))
        assertFalse(HomeNotificationPolicy.popup("message", "normal", true))
        assertFalse(HomeNotificationPolicy.popup("update", "important", true))
        assertFalse(HomeNotificationPolicy.popup("message", "important", false))
    }
}
