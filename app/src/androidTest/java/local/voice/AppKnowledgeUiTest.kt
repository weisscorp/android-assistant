package local.voice

import android.content.Intent
import android.media.AudioManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class AppKnowledgeUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    @Test fun savedNicknameIsVisibleInKnowledgeAndLaunchesThroughTheConversationWithoutLlm() {
        val catalog = AppKnowledge(context)
        catalog.ensureReady()
        val pkg = "local.voice.fixture"
        assertNotNull("Install the synthetic fixture first", catalog.get(pkg))
        catalog.saveNames(pkg, "Моя библиотека", listOf("читалка"))
        val activity = instrumentation.startActivitySync(Intent(context, AppKnowledgeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as AppKnowledgeActivity
        var conversation: VoiceConversation? = null
        try {
            val until = System.currentTimeMillis() + 5000
            var visible = false
            while (System.currentTimeMillis() < until && !visible) {
                instrumentation.runOnMainSync { visible = views(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("Моя библиотека") } }
                Thread.sleep(50)
            }
            assertTrue("Knowledge screen did not show the saved Russian name", visible)
            instrumentation.runOnMainSync {
                views(activity.window.decorView).filterIsInstance<EditText>().single().setText("читалку")
                assertEquals(1, views(activity.window.decorView).filterIsInstance<Button>().count { it.text == "Названия и прозвища" })
                views(activity.window.decorView).filterIsInstance<Button>().single { it.text == "Названия и прозвища" }.performClick()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val dialog = AppKnowledgeActivity::class.java.getDeclaredField("editorDialog").apply { isAccessible = true }.get(activity) as android.app.AlertDialog
                val fields = views(dialog.window!!.decorView).filterIsInstance<EditText>()
                fields[0].setText("Моя библиотека")
                fields[1].setText("читалка\nчитатель для книг")
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            }
            assertEquals("Editor did not persist the nickname", pkg, AppKnowledge(context).matching("читатель для книг").single().pkg)
            val replies = LinkedBlockingQueue<String>()
            val stats = LocalBrain.lastStats
            instrumentation.runOnMainSync {
                conversation = VoiceConversation(context, {}, {}, textOnly = true, replyObserver = { replies.offer(it) })
                    .also { it.start(); it.respond("открой читалку") }
            }
            assertEquals("Открываю мою библиотеку.", replies.poll(2, TimeUnit.SECONDS))
            val launchUntil = System.currentTimeMillis() + 5000
            var opened = false
            while (System.currentTimeMillis() < launchUntil && !opened) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                opened = root?.let { try { it.packageName == pkg } finally { it.recycle() } } ?: false
                Thread.sleep(50)
            }
            assertTrue("Nickname did not launch the bound app", opened)
            assertSame(stats, LocalBrain.lastStats)
            assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
        } finally {
            instrumentation.runOnMainSync { conversation?.close(); activity.finish() }
            catalog.resetNames(pkg)
        }
    }
}
