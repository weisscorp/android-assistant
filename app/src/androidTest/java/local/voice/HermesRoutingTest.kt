package local.voice

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.media.AudioManager
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

@RunWith(AndroidJUnit4::class)
class HermesRoutingTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun fixture(block: () -> Unit) {
        val prefs = context.getSharedPreferences("settings", 0)
        val original = prefs.all.toMap()
        val defaultTls = HttpsURLConnection.getDefaultSSLSocketFactory()
        try {
            val endpoint = InstrumentationRegistry.getArguments().getString("backend_endpoint") ?: error("Use verify_emulator.py")
            val certificate = File(context.filesDir, "test-server-ca.pem").inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("fixture", certificate) }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            HttpsURLConnection.setDefaultSSLSocketFactory(SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory)
            BackendClient.save(context, endpoint, "helmet-test-token")
            prefs.edit().putString("brain_mode", "AUTO").commit()
            block()
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(defaultTls)
            val restore = prefs.edit().clear()
            original.forEach { (key, value) -> when (value) {
                is String -> restore.putString(key, value); is Boolean -> restore.putBoolean(key, value)
                is Int -> restore.putInt(key, value); is Long -> restore.putLong(key, value); is Float -> restore.putFloat(key, value)
                is Set<*> -> restore.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            assertTrue(restore.commit())
        }
    }
    @Test fun httpsConversationReceivesBoundedHistoryAndExecutesTypedPhoneTool() = fixture {
        val session = UUID.randomUUID().toString()
        val result = HermesConversation.ask(context, session, "Привет $(буквально)", listOf("Еду в Батуми" to "Понял"), BackendClient.Request())
        assertEquals("Hermes: Привет $(буквально); history=1", result.reply)
        assertNull(result.action)
        val proposed = HermesConversation.ask(context, session, "tool-time", emptyList(), BackendClient.Request())
        assertEquals(VoiceCommand.Time, proposed.action)
        val body = HermesConversation.body(session, UUID.randomUUID().toString(), "test", List(10) { "у".repeat(800) to "а".repeat(1500) }, JSONArray())
        assertEquals(4, body.getJSONArray("history").length())
        assertEquals(600, body.getJSONArray("history").getJSONObject(0).getString("user").length)
        assertEquals(1000, body.getJSONArray("history").getJSONObject(0).getString("assistant").length)
    }
    @Test fun autoHandsOffContextOnlyOnceAndDirectLocalCommandStillWorks() = fixture {
        val replies = LinkedBlockingQueue<String>()
        var conversation: VoiceConversation? = null
        val stats = LocalBrain.lastStats
        fun request(text: String) { instrumentation.runOnMainSync { conversation!!.respond(text) } }
        try {
            instrumentation.runOnMainSync { conversation = VoiceConversation(context, {}, {}, textOnly = true, replyObserver = { replies.offer(it) }).also { it.start() } }
            request("Как выбрать маршрут для дальней поездки?")
            assertEquals("Подключаю Hermes. Передаю контекст разговора.", replies.poll(3, TimeUnit.SECONDS))
            assertTrue(replies.poll(8, TimeUnit.SECONDS)!!.endsWith("history=0"))
            request("Продолжи рассуждение о поездке")
            assertTrue(replies.poll(8, TimeUnit.SECONDS)!!.endsWith("history=1"))
            request("который час")
            assertTrue(replies.poll(2, TimeUnit.SECONDS)!!.startsWith("Сейчас"))
            assertEquals(stats, LocalBrain.lastStats)
            assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
            assertEquals(0L, conversation!!.speechFrames)
        } finally { instrumentation.runOnMainSync { conversation?.close() } }
    }
    @Test fun newerTurnDropsLateServerReply() = fixture {
        val replies = LinkedBlockingQueue<String>()
        var conversation: VoiceConversation? = null
        try {
            instrumentation.runOnMainSync { conversation = VoiceConversation(context, {}, {}, textOnly = true, replyObserver = { replies.offer(it) }).also { it.start(); it.respond("slow") } }
            assertTrue(replies.poll(3, TimeUnit.SECONDS)!!.contains("Подключаю Hermes"))
            Thread.sleep(150)
            instrumentation.runOnMainSync { conversation!!.respond("заряд") }
            assertTrue(replies.poll(3, TimeUnit.SECONDS)!!.startsWith("Заряд батареи"))
            assertNull("Cancelled server response reached the user", replies.poll(2500, TimeUnit.MILLISECONDS))
        } finally { instrumentation.runOnMainSync { conversation?.close() } }
    }
    @Test fun localKnowledgeDoesNotBreakRemoteProtocolOrExposePrivateBindings() = fixture {
        val store = SavedSources(context)
        val source = SavedSource(name = "Приватный " + UUID.randomUUID().toString().take(8), kind = SourceKind.NOTE,
            value = "PRIVATE_SOURCE_TEST_ONLY", instructions = "Только локальные данные")
        try {
            store.save(source)
            assertTrue(CapabilityCatalog.entries(context).single { it.name == "saved_sources" }.ready)
            val capabilities = CapabilityCatalog.json(context)
            assertFalse(capabilities.toString().contains("saved_sources"))
            assertFalse(capabilities.toString().contains(source.name))
            assertFalse(capabilities.toString().contains(source.value))
            val result = HermesConversation.ask(context, UUID.randomUUID().toString(), "Проверка каталога", emptyList(), BackendClient.Request())
            assertEquals("Hermes: Проверка каталога; history=0", result.reply)
            assertNull(result.action)
        } finally { store.delete(source.id) }
    }
    @Test fun malformedAndUnrequestedRemoteActionsCannotBeExecuted() {
        fun action(tool: String, args: JSONObject) = JSONObject().put("tool", tool).put("arguments", args)
        for (tool in listOf("shell", "confirm", "install", "screenshot")) assertTrue(runCatching { RemotePhoneTools.decode(action(tool, JSONObject())) }.isFailure)
        assertTrue(runCatching { RemotePhoneTools.decode(action("open", JSONObject().put("app", "Telegram").put("url", "https://example.org"))) }.isFailure)
        assertTrue(runCatching { RemotePhoneTools.decode(action("music", JSONObject().put("artist", "Metallica").put("play", "true"))) }.isFailure)
        assertTrue(runCatching { RemotePhoneTools.decode(action("weather", JSONObject().put("place", "Батуми").put("day", 0.5))) }.isFailure)
        assertFalse(RemotePhoneTools.requested(VoiceCommand.Call("123456"), "Расскажи о звёздах"))
        assertFalse(RemotePhoneTools.requested(VoiceCommand.Music(MusicRequest("Metallica", true)), "Объясни квантовую физику"))
        assertTrue(RemotePhoneTools.requested(VoiceCommand.Telegram(TelegramRequest("жене", TelegramRequest.Operation.DRAFT, "Привет")), "напиши жене привет"))
    }
    @Test fun telegramIdentityPersistsWithoutRewritingDictationAndNeedsConfirmation() {
        val memory = PeopleMemory(context)
        val alias = "герместест"
        val original = context.getSharedPreferences("people", 0).getString(alias, null)
        val replies = LinkedBlockingQueue<String>()
        var conversation: VoiceConversation? = null
        try {
            memory.forget(alias)
            instrumentation.runOnMainSync { conversation = VoiceConversation(context, {}, {}, textOnly = true, replyObserver = { replies.offer(it) }).also { it.start(); it.respond("запомни телеграм герместест это @fixture_user") } }
            assertTrue(replies.poll(2, TimeUnit.SECONDS)!!.contains("подтверждаю"))
            assertNull(memory.get(alias))
            instrumentation.runOnMainSync { conversation!!.respond("отмена") }
            replies.poll(2, TimeUnit.SECONDS)
            assertNull(memory.get(alias))
            instrumentation.runOnMainSync { conversation!!.respond("запомни телеграм герместест это @fixture_user"); conversation!!.respond("подтверждаю") }
            assertEquals("@fixture_user", PeopleMemory(context).get(alias)!!.telegram)
            val request = (VoiceCommands.parse("напиши герместест сообщение свяжи с гермесом") as VoiceCommand.Telegram).request
            assertEquals("свяжи с гермесом", request.text)
            assertEquals(TelegramRequest.Operation.DRAFT, request.operation)
        } finally {
            instrumentation.runOnMainSync { conversation?.close() }
            val edit = context.getSharedPreferences("people", 0).edit()
            if (original == null) edit.remove(alias) else edit.putString(alias, original)
            edit.commit()
        }
    }
    @Test fun contactAliasSurvivesRenameAndFailsClosedAfterDeletion() {
        assertTrue("Synthetic contacts are only inserted into the owned emulator", android.os.Build.MODEL.contains("SDK", true))
        val name = "Герместест Мария"
        val alias = "герместест контакт"
        val memory = PeopleMemory(context)
        val original = context.getSharedPreferences("people", 0).getString(alias, null)
        val book = ContactBook(context)
        var rawId: Long? = null
        fun find(query: String): ContactBook.Result {
            val done = CountDownLatch(1); var result: ContactBook.Result? = null
            book.find(query) { result = it; done.countDown() }
            assertTrue(done.await(5, TimeUnit.SECONDS)); return result!!
        }
        try {
            instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.WRITE_CONTACTS)
            try {
                val batch = arrayListOf(
                    ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI).withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build(),
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValue(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE).withValue(StructuredName.DISPLAY_NAME, name).build(),
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValue(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE).withValue(Phone.NUMBER, "55501999").withValue(Phone.TYPE, Phone.TYPE_MOBILE).build())
                rawId = ContentUris.parseId(context.contentResolver.applyBatch(ContactsContract.AUTHORITY, batch)[0].uri!!)
            } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
            val contact = find(name).entries.single()
            memory.contact(alias, contact)
            assertEquals(contact.number, find(alias).entries.single().number)
            instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.WRITE_CONTACTS)
            try {
                context.contentResolver.update(ContactsContract.Data.CONTENT_URI, android.content.ContentValues().apply { put(StructuredName.DISPLAY_NAME, "Герместест Другое имя") },
                    "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?", arrayOf(rawId.toString(), StructuredName.CONTENT_ITEM_TYPE))
            } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
            assertEquals(contact.number, find(alias).entries.single().number)
            instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.WRITE_CONTACTS)
            try { context.contentResolver.delete(ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawId!!), null, null); rawId = null }
            finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
            assertTrue(find(alias).entries.isEmpty())
            assertNotNull(find(alias).error)
        } finally {
            book.close()
            if (rawId != null) {
                instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.WRITE_CONTACTS)
                try { context.contentResolver.delete(ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawId!!), null, null) }
                finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
            }
            val edit = context.getSharedPreferences("people", 0).edit()
            if (original == null) edit.remove(alias) else edit.putString(alias, original)
            edit.commit()
        }
    }
}
