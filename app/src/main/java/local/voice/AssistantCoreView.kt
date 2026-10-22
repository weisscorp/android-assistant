package local.voice

import android.animation.ValueAnimator
import android.content.Context
import android.content.SharedPreferences
import android.database.ContentObserver
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import local.voice.MascotTween.Channel
import local.voice.MascotTween.Ease
import local.voice.MascotTween.Key
import kotlin.math.*

/** Shared native mascot for the home screen and Android's voice-assistant session. */
internal class AssistantCoreView(context: Context) : View(context) {
    enum class Mode { IDLE, LOADING, LISTENING, THINKING, SPEAKING, WORKING, ERROR }
    private val motion = MascotTween()
    private val reaction = MascotTween()
    private val gaze = MascotTween()
    private val scene = AssistantMascotScene()
    private val preferences = MascotColors.preferences(context)
    var colors = MascotColors.read(context)
        private set
    private val appearanceChanges = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        colors = MascotColors.read(context)
        invalidate()
    }
    private var visible = false
    private var framePending = false
    private var lastFrame = 0L
    private var time = 0f
    private var blinkAt = 3.2f
    private var glanceAt = 7.4f
    private var microphone = 0f
    private var energy = 0f
    private var unit = 0f
    private var centerX = 0f
    private var centerY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var pointer = -1
    private var screenPointer = -1
    private val screenLocation = IntArray(2)
    private val visibleBounds = Rect()
    private var downX = 0f
    private var downY = 0f
    private var dragged = false
    private var tapCount = 0
    private val staticReset = Runnable { reaction.reset(); invalidate() }
    var speechLevel: (() -> Int)? = null
    var mode = Mode.IDLE
        set(value) {
            if (field == value) return
            field = value
            pose()
            invalidate()
        }
    private val frame = Runnable {
        framePending = false
        if (canAnimate()) invalidate()
    }
    private val animationSettings = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            if (!ValueAnimator.areAnimatorsEnabled()) {
                // A blink may have been interrupted halfway; restore the actual state's open eyes.
                motion.settle()
                motion.set(Channel.EYES, eyes())
                reaction.reset()
                gaze.settle()
                removeCallbacks(staticReset)
                energy = 0f
            }
            lastFrame = 0L
            cancelFrame()
            invalidate()
            schedule()
        }
    }
    init {
        isClickable = true
        isFocusable = true
        isSoundEffectsEnabled = false
        contentDescription = "Маскот. Нажмите, чтобы поиграть; можно потянуть за пальцем"
        // Playing never requests audio, speech, network or permissions, including on the lock screen.
        pose()
        motion.settle()
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = Button::class.java.name
    }
    override fun performClick(): Boolean {
        super.performClick()
        removeCallbacks(staticReset)
        tapCount++
        if (!ValueAnimator.areAnimatorsEnabled()) {
            // Reduced motion still acknowledges the tap with a static smile and wink.
            reaction.reset()
            reaction.set(Channel.JOY, 1f)
            reaction.set(Channel.WINK, 1f)
            postDelayed(staticReset, 600)
        } else {
            val tilt = when (tapCount % 3) { 0 -> -12f; 1 -> 10f; else -> -8f }
            reaction.animate(Channel.SCALE_X, Key(.88f, 95f, Ease.OUT), Key(1.08f, 170f, Ease.BACK), Key(1f, 190f, Ease.OUT))
            reaction.animate(Channel.SCALE_Y, Key(1.18f, 95f, Ease.OUT), Key(.93f, 170f, Ease.BACK), Key(1f, 190f, Ease.OUT))
            reaction.animate(Channel.LIFT, Key(18f, 150f, Ease.OUT), Key(0f, 280f, Ease.BACK))
            reaction.animate(Channel.TILT, Key(tilt, 120f, Ease.OUT), Key(-tilt * .45f, 140f), Key(0f, 180f, Ease.OUT))
            reaction.animate(Channel.WOBBLE, Key(9f, 120f), Key(-4f, 140f), Key(0f, 180f))
            reaction.animate(Channel.JOY, Key(1f, 70f, Ease.OUT), Key(1f, 300f), Key(0f, 220f))
            reaction.animate(Channel.WINK, Key(if (tapCount % 3 == 2) 1f else 0f, 100f), Key(0f, 290f))
            returnGaze()
        }
        glanceAt = time + 7.4f
        invalidate(); schedule()
        return true
    }
    private fun returnGaze() {
        for (channel in arrayOf(Channel.OFFSET_X, Channel.OFFSET_Y)) {
            reaction.animate(channel, Key(0f, 320f, Ease.BACK))
        }
        if (screenPointer < 0) releaseGaze()
    }
    private fun finishTouch(cancelled: Boolean) {
        pointer = -1; isPressed = false
        if (screenPointer < 0) releaseGaze()
        parent?.requestDisallowInterceptTouchEvent(false)
        if (!cancelled && !dragged) { performClick(); return }
        if (ValueAnimator.areAnimatorsEnabled()) {
            for (channel in Channel.entries) {
                reaction.animate(channel, Key(channel.initial, 340f, Ease.BACK))
            }
        } else reaction.reset()
        glanceAt = time + 7.4f
        invalidate(); schedule()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || unit <= 0f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Only the character owns the gesture; empty preview space still scrolls normally.
                val minTarget = 24f * resources.displayMetrics.density
                if (abs(event.x - centerX) > max(92f * unit, minTarget) ||
                    abs(event.y - centerY + 8f * unit) > max(96f * unit, minTarget)) return false
                removeCallbacks(staticReset)
                pointer = event.getPointerId(0); downX = event.x; downY = event.y; dragged = false
                isPressed = true
                parent?.requestDisallowInterceptTouchEvent(true)
                reaction.set(Channel.JOY, .7f)
                reaction.set(Channel.WINK, 0f)
                if (ValueAnimator.areAnimatorsEnabled()) {
                    reaction.animate(Channel.SCALE_X, Key(1.13f, 65f, Ease.OUT))
                    reaction.animate(Channel.SCALE_Y, Key(.87f, 65f, Ease.OUT))
                    reaction.animate(Channel.LIFT, Key(0f, 65f, Ease.OUT))
                }
                trackFinger(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointer)
                if (index < 0) { finishTouch(true); return true }
                val x = event.getX(index); val y = event.getY(index)
                if (hypot(x - downX, y - downY) > touchSlop) dragged = true
                trackFinger(x, y)
                if (dragged && ValueAnimator.areAnimatorsEnabled()) {
                    val dx = ((x - downX) / unit).coerceIn(-24f, 24f)
                    val dy = ((y - downY) / unit).coerceIn(-18f, 18f)
                    reaction.set(Channel.OFFSET_X, dx)
                    reaction.set(Channel.OFFSET_Y, dy)
                    reaction.set(Channel.TILT, dx * .4f)
                    reaction.set(Channel.SCALE_X, 1.05f + abs(dx) / 300f + dy / 350f)
                    reaction.set(Channel.SCALE_Y, .97f - dy / 180f)
                    reaction.set(Channel.WOBBLE, dx * .2f)
                }
            }
            MotionEvent.ACTION_UP -> { if (pointer < 0) return false; finishTouch(false) }
            MotionEvent.ACTION_CANCEL -> { if (pointer < 0) return false; finishTouch(true) }
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == pointer) finishTouch(true)
            else -> if (pointer < 0) return false
        }
        invalidate(); schedule()
        return true
    }
    private fun trackFinger(x: Float, y: Float) {
        gaze.set(Channel.LOOK_X, ((x - centerX) / (unit * 8f)).coerceIn(-10f, 10f))
        gaze.set(Channel.LOOK_Y, ((y - centerY) / (unit * 10f)).coerceIn(-8f, 8f))
    }
    /** Observe the window's original events without consuming or redirecting its gestures. */
    fun observeScreenTouch(event: MotionEvent) {
        if (!isEnabled || unit <= 0f || !isShown || !getGlobalVisibleRect(visibleBounds)) {
            screenPointer = -1
            gaze.reset()
            return
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> screenPointer = event.getPointerId(0)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                screenPointer = -1
                releaseGaze()
                return
            }
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == screenPointer) {
                val next = (0 until event.pointerCount).firstOrNull { it != event.actionIndex }
                screenPointer = next?.let { event.getPointerId(it) } ?: -1
            }
        }
        val index = event.findPointerIndex(screenPointer)
        if (index < 0) return
        getLocationOnScreen(screenLocation)
        trackFinger(event.getRawX(index) - screenLocation[0], event.getRawY(index) - screenLocation[1])
        glanceAt = time + 7.4f
        // Sample fast touch streams in the existing 30 fps loop, not with a redraw per event.
        if (!canAnimate()) invalidate()
        schedule()
    }
    private fun releaseGaze() {
        if (ValueAnimator.areAnimatorsEnabled()) {
            gaze.animate(Channel.LOOK_X, Key(0f, 260f, Ease.OUT))
            gaze.animate(Channel.LOOK_Y, Key(0f, 260f, Ease.OUT))
        } else gaze.reset()
        glanceAt = time + 7.4f
        invalidate(); schedule()
    }
    private fun clearTouch() {
        pointer = -1; screenPointer = -1; isPressed = false
        parent?.requestDisallowInterceptTouchEvent(false)
        removeCallbacks(staticReset)
        reaction.reset()
        gaze.reset()
    }
    private fun eyes() = when (mode) {
        Mode.LISTENING -> 1.08f
        Mode.THINKING -> .82f
        Mode.IDLE -> .95f
        else -> 1f
    }
    private fun pose() {
        fun target(channel: Channel, value: Float) = motion.animate(channel, Key(value, 240f, Ease.OUT))
        target(Channel.SCALE_X, when (mode) { Mode.LISTENING -> .94f; Mode.ERROR -> 1.06f; else -> 1f })
        target(Channel.SCALE_Y, when (mode) { Mode.LISTENING -> 1.06f; Mode.ERROR -> .94f; else -> 1f })
        target(Channel.LIFT, when (mode) { Mode.LISTENING -> 10f; Mode.THINKING -> 8f; Mode.ERROR -> 0f; else -> 4f })
        target(Channel.TILT, when (mode) { Mode.THINKING -> -5f; Mode.ERROR -> 5f; else -> 0f })
        target(Channel.WOBBLE, when (mode) { Mode.THINKING -> 4f; Mode.ERROR -> -4f; else -> 0f })
        target(Channel.EYES, eyes())
        blinkAt = time + 3.2f
        if (!ValueAnimator.areAnimatorsEnabled()) motion.settle()
    }
    fun microphoneLevel(rms: Int, silenced: Boolean) {
        microphone = if (silenced) 0f else level(rms)
        // The frame loop samples this value at 30 fps. Audio callbacks must not drive redraws.
    }
    private fun level(rms: Int) = (ln(1f + rms.coerceAtLeast(0) / 100f) / 4f).coerceIn(0f, 1f)
    private fun canAnimate() = visible && isAttachedToWindow && isShown && ValueAnimator.areAnimatorsEnabled()
    private fun schedule() {
        if (!framePending && canAnimate()) {
            framePending = true
            postDelayed(frame, if (mode == Mode.IDLE && !reaction.running && !gaze.running && pointer < 0 && screenPointer < 0) 66L else 33L)
        }
    }
    private fun cancelFrame() { removeCallbacks(frame); framePending = false }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, animationSettings)
        colors = MascotColors.read(context)
        preferences.registerOnSharedPreferenceChangeListener(appearanceChanges)
        lastFrame = 0L
        schedule()
    }
    override fun onDetachedFromWindow() {
        cancelFrame()
        clearTouch()
        context.contentResolver.unregisterContentObserver(animationSettings)
        preferences.unregisterOnSharedPreferenceChangeListener(appearanceChanges)
        lastFrame = 0L
        super.onDetachedFromWindow()
    }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        visible = isVisible
        lastFrame = 0L
        cancelFrame()
        if (!isVisible) clearTouch()
        if (isVisible) { invalidate(); schedule() }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Keep the jelly silhouette, shadow and progress motes inside the compact layout.
        unit = min(w / 300f, h / 260f)
        centerX = w / 2f; centerY = h / 2f - 12f * unit
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (unit <= 0f) return
        val animated = canAnimate()
        if (animated) {
            val now = SystemClock.uptimeMillis()
            val dt = if (lastFrame == 0L) 0f else (now - lastFrame).coerceAtMost(100L) / 1000f
            lastFrame = now
            time += dt
            motion.advance(dt * 1000f)
            reaction.advance(dt * 1000f)
            gaze.advance(dt * 1000f)
            if (time >= glanceAt && mode == Mode.IDLE && pointer < 0 && screenPointer < 0 && !gaze.running && !reaction.running) {
                val side = if ((time / 7f).toInt() % 2 == 0) 1f else -1f
                reaction.animate(Channel.LOOK_X, Key(side * 7f, 240f, Ease.OUT), Key(side * 7f, 600f), Key(0f, 300f))
                reaction.animate(Channel.TILT, Key(side * 4f, 240f, Ease.OUT), Key(0f, 700f))
                glanceAt = time + 8.6f
            }
            if (time >= blinkAt) {
                motion.animate(Channel.EYES, Key(.03f, 75f, Ease.OUT), Key(eyes(), 125f, Ease.OUT))
                blinkAt = time + 3.7f + if (mode == Mode.THINKING) .8f else 0f
            }
            val target = when (mode) {
                Mode.LISTENING -> microphone
                Mode.SPEAKING -> level(speechLevel?.invoke() ?: 0)
                else -> 0f
            }
            energy += (target - energy) * (1f - exp(-dt * 13f))
        } else if (!ValueAnimator.areAnimatorsEnabled()) {
            motion.settle()
            motion.set(Channel.EYES, eyes())
            energy = 0f
        }
        canvas.save()
        canvas.translate(centerX, centerY)
        canvas.scale(unit, unit)
        val following = pointer >= 0 || screenPointer >= 0 || gaze.running
        scene.draw(canvas, mode, motion, reaction, colors, time, energy, animated, isPressed, hasFocus(),
            if (following) gaze[Channel.LOOK_X] else null, if (following) gaze[Channel.LOOK_Y] else null)
        canvas.restore()
        schedule()
    }
}

/** A solid black backdrop shared by the home screen and voice overlay. */
internal class AssistantBackdrop : android.graphics.drawable.ColorDrawable(Color.BLACK)
