package local.voice

import android.content.Intent
import android.media.AudioManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Navigation must retain the old controls without opening any voice session. */
@RunWith(AndroidJUnit4::class)
class SettingsMenuTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun texts(activity: SettingsActivity) = views(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
    private fun current(): SettingsActivity? {
        var activity: SettingsActivity? = null
        instrumentation.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<SettingsActivity>().singleOrNull()
        }
        return activity
    }
    private fun waitFor(description: String, predicate: () -> Boolean) {
        val until = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < until) {
            if (predicate()) return
            Thread.sleep(30)
        }
        fail(description)
    }
    private fun click(activity: SettingsActivity, text: String) {
        var target: View? = null
        instrumentation.runOnMainSync {
            target = views(activity.window.decorView).single { it.isClickable &&
                ((it as? TextView)?.text?.toString() == text || it.contentDescription?.toString()?.startsWith("$text.") == true) }
            target!!.requestRectangleOnScreen(android.graphics.Rect(0, 0, target!!.width, target!!.height), true)
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { assertTrue(target!!.performClick()) }
        instrumentation.waitForIdleSync()
    }
    private fun launch(): SettingsActivity {
        // Establish accessibility before opening a modal; include its separate window.
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        return instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
    }
    private fun assertNoAudio() {
        assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
        assertNull(VoiceConversation.activePhase)
    }
    @Test fun everySectionHasItsOwnControlsAndBackReturnsToMenuWithoutAudio() {
        val home = launch()
        try {
            instrumentation.runOnMainSync {
                assertFalse(texts(home).contains("Разрешить микрофон и Bluetooth"))
                assertFalse(texts(home).contains("Проверить голос здесь"))
                assertFalse(texts(home).contains("Сохранить подключение"))
            }
            val expected = linkedMapOf(
                "Доступы" to "Разрешить микрофон и Bluetooth",
                "Модели" to "Каталог моделей и benchmark",
                "Аудиоустройства" to "Выбрать гарнитуру",
                "Оформление" to "Показать пример без звука",
                "Проверки" to "Проверить звук гарнитуры",
                "Поведение и память" to "Предпочитаемый навигатор",
                "Hermes и обновления" to "Сохранить подключение")
            expected.forEach { (title, control) ->
                click(home, title)
                waitFor("Section $title did not open") { current()?.let { it !== home && it.intent.getStringExtra(SettingsActivity.EXTRA_SECTION) != null } == true }
                val screen = current()!!
                instrumentation.runOnMainSync {
                    assertTrue("Missing $control in $title", texts(screen).contains(control))
                    assertEquals(1, texts(screen).count { it == control })
                    if (title != "Проверки") assertFalse("Voice tests leaked into $title", texts(screen).contains("Проверить голос здесь"))
                }
                assertNoAudio()
                if (title == "Поведение и память") {
                    instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
                } else instrumentation.runOnMainSync { screen.findViewById<Button>(R.id.settings_back).performClick() }
                waitFor("Back did not restore the settings menu") { current() === home }
            }
            assertNoAudio()
        } finally { instrumentation.runOnMainSync { home.finish() } }
    }
    @Test fun changingSpeechModelSurvivesRecreationAndMenuNavigation() = checkSpeechChoice(SpeechEngine.VOSK)
    @Test fun gigaAmIsThirdChoiceAndSurvivesRecreation() = checkSpeechChoice(SpeechEngine.GIGAAM)
    private fun checkSpeechChoice(target: SpeechEngine) {
        val prefs = context.getSharedPreferences("settings", 0)
        val hadPreference = prefs.contains("tone_asr")
        val previous = prefs.getBoolean("tone_asr", false)
        val previousEngine = prefs.getString("stt_engine", null)
        prefs.edit().remove("stt_engine").putBoolean("tone_asr", true).commit()
        val home = launch()
        var screen: SettingsActivity? = null
        try {
            click(home, "Модели")
            waitFor("Models screen did not open") { current()?.intent?.getStringExtra(SettingsActivity.EXTRA_SECTION) == SettingsSection.MODELS.name }
            screen = current()!!
            click(screen!!, "Распознавание: T-one")
            waitFor("Speech model dialog did not accept selection") {
                instrumentation.uiAutomation.windows.any { window -> window.root?.let { root ->
                    try {
                        val items = root.findAccessibilityNodeInfosByText("${target.label} · ${target.description}")
                        try {
                            items.singleOrNull()?.let { item ->
                                // The native choice row is handled by its ListView and
                                // exposes no ACTION_CLICK. Tap its measured screen bounds.
                                val bounds = android.graphics.Rect(); item.getBoundsInScreen(bounds)
                                if (bounds.isEmpty) false else {
                                    val time = android.os.SystemClock.uptimeMillis()
                                    val down = android.view.MotionEvent.obtain(time, time, android.view.MotionEvent.ACTION_DOWN,
                                        bounds.exactCenterX(), bounds.exactCenterY(), 0)
                                    val up = android.view.MotionEvent.obtain(time, time + 20, android.view.MotionEvent.ACTION_UP,
                                        bounds.exactCenterX(), bounds.exactCenterY(), 0)
                                    try { instrumentation.uiAutomation.injectInputEvent(down, true) && instrumentation.uiAutomation.injectInputEvent(up, true) }
                                    finally { down.recycle(); up.recycle() }
                                }
                            } == true
                        }
                        finally { items.forEach { it.recycle() } }
                    } finally { root.recycle() }
                } == true }
            }
            waitFor("Speech model was not saved") { SpeechEngines.selected(context) == target }
            val before = screen!!
            instrumentation.runOnMainSync { before.recreate() }
            waitFor("Models screen did not restore after recreation") { current()?.let { it !== before && it.intent.getStringExtra(SettingsActivity.EXTRA_SECTION) == SettingsSection.MODELS.name } == true }
            screen = current()!!
            instrumentation.runOnMainSync { assertTrue(texts(screen!!).contains("Распознавание: ${target.label}")); screen!!.findViewById<Button>(R.id.settings_back).performClick() }
            waitFor("Recreated page did not return to menu") { current() === home }
            screen = null
            click(home, "Модели")
            waitFor("Models screen did not reopen") { current()?.intent?.getStringExtra(SettingsActivity.EXTRA_SECTION) == SettingsSection.MODELS.name }
            screen = current()!!
            instrumentation.runOnMainSync { assertTrue(texts(screen!!).contains("Распознавание: ${target.label}")) }
            assertNoAudio()
        } finally {
            instrumentation.runOnMainSync { screen?.finish(); home.finish() }
            prefs.edit().apply { if (previousEngine == null) remove("stt_engine") else putString("stt_engine", previousEngine) }.commit()
            if (hadPreference) prefs.edit().putBoolean("tone_asr", previous).commit() else prefs.edit().remove("tone_asr").commit()
        }
    }
}
