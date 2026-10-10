package local.voice

import org.junit.Assert.*
import org.junit.Test

class ScreenReplanTest {
    @Test fun samePinnedAppMayBeObservedAgainOnlyTwice() {
        assertTrue(ScreenReplan.allowed("chat", "chat", "chat", 0))
        assertTrue(ScreenReplan.allowed("chat", "chat", "chat", 1))
        assertFalse(ScreenReplan.allowed("chat", "chat", "chat", 2))
        assertFalse(ScreenReplan.allowed("chat", "chat", "chat", -1))
    }

    @Test fun missingLockedOrDifferentAppCannotAuthorizeAReplan() {
        assertFalse(ScreenReplan.allowed(null, "chat", "chat", 0))
        assertFalse(ScreenReplan.allowed("chat", null, "chat", 0))
        assertFalse(ScreenReplan.allowed("chat", "chat", null, 0))
        assertFalse(ScreenReplan.allowed("chat", "other", "chat", 0))
        assertFalse(ScreenReplan.allowed("chat", "chat", "other", 0))
    }
}
