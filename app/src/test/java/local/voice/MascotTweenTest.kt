package local.voice

import local.voice.MascotTween.Channel
import local.voice.MascotTween.Ease
import local.voice.MascotTween.Key
import org.junit.Assert.*
import org.junit.Test

class MascotTweenTest {
    @Test fun slowFrameCarriesTimeAcrossSeveralKeysAndFinishes() {
        val motion = MascotTween()
        motion.animate(Channel.LIFT, Key(10f, 100f, Ease.LINEAR), Key(0f, 100f, Ease.LINEAR))
        motion.advance(150f)
        assertEquals(5f, motion[Channel.LIFT], .001f)
        motion.advance(100f)
        assertEquals(0f, motion[Channel.LIFT], .001f)
        assertFalse(motion.running)
    }
    @Test fun replacingTrackKeepsCurrentPositionAndDoesNotResurrectOldPose() {
        val motion = MascotTween()
        motion.animate(Channel.TILT, Key(20f, 200f, Ease.LINEAR))
        motion.advance(100f)
        motion.animate(Channel.TILT, Key(-10f, 100f, Ease.LINEAR))
        assertEquals(10f, motion[Channel.TILT], .001f)
        motion.advance(50f)
        assertEquals(0f, motion[Channel.TILT], .001f)
        motion.advance(500f)
        assertEquals(-10f, motion[Channel.TILT], .001f)
    }
    @Test fun reducedMotionSettlesAllChannelsAndZeroDurationDoesNotDivideByZero() {
        val motion = MascotTween()
        motion.animate(Channel.EYES, Key(0f, 0f), Key(.6f, 0f))
        motion.advance(0f)
        assertEquals(.6f, motion[Channel.EYES], .001f)
        motion.animate(Channel.SCALE_X, Key(1.1f, 100f), Key(1f, 100f))
        motion.animate(Channel.TILT, Key(7f, 150f))
        motion.settle()
        assertEquals(7f, motion[Channel.TILT], .001f)
        assertEquals(1f, motion[Channel.SCALE_X], .001f)
        assertFalse(motion.running)
        motion.reset()
        assertEquals(0f, motion[Channel.TILT], .001f)
    }
}
