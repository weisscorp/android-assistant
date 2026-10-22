package local.voice

import org.junit.Assert.*
import org.junit.Test

class SpeechPauseTest {
    @Test fun backgroundAfterSpeechEndsWithinOneSecondAndShortPausesDoNotCutSpeech() {
        val pause = SpeechPause()
        repeat(20) { assertFalse(pause.accept(.9f)) }
        assertTrue(pause.heardSpeech)
        repeat(20) { assertFalse(pause.accept(.05f)) } // 640ms thinking pause.
        repeat(10) { assertFalse(pause.accept(.8f)) }
        repeat(24) { assertFalse(pause.accept(.1f)) }
        assertTrue(pause.accept(.1f))
    }
    @Test fun lowConfidenceNoiseNeverExtendsTheInitialFiveSecondWait() {
        val pause = SpeechPause()
        repeat(156) { assertFalse(pause.accept(.2f)) }
        assertTrue(pause.accept(.2f)); assertFalse(pause.heardSpeech)
    }
    @Test fun quietSpeechCanContinueWithHysteresisAndClicksAreInsufficient() {
        val pause = SpeechPause()
        assertFalse(pause.accept(.8f)); assertFalse(pause.accept(.01f))
        assertFalse(pause.heardSpeech)
        repeat(3) { assertFalse(pause.accept(.55f)) }
        repeat(80) { assertFalse(pause.accept(.4f)) }
        assertTrue(pause.heardSpeech)
    }
}
