package local.voice

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Run explicitly on the owned AOSP emulator with READ_CONTACTS and CALL_PHONE granted.
 * Temporary synthetic contacts are inserted under shell identity, and deleted in finally.
 * No afterSpeech call action is ever invoked. */
@RunWith(AndroidJUnit4::class)
class ContactProviderTest {
    @Test fun testAospContactsSelectionAndOneShotCallConfirmation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.READ_CONTACTS))
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.CALL_PHONE))
        val rawIds = mutableListOf<Long>()
        val book = ContactBook(context)
        try {
            instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.WRITE_CONTACTS)
            try {
                for ((name, number) in listOf("Шлемтест Первый" to "55511111", "Шлемтест Второй" to "55522222")) {
                    val batch = arrayListOf(
                        ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build(),
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                            .withValue(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE).withValue(StructuredName.DISPLAY_NAME, name).build(),
                        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                            .withValue(ContactsContract.Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE).withValue(Phone.NUMBER, number).withValue(Phone.TYPE, Phone.TYPE_MOBILE).build()
                    )
                    val result = context.contentResolver.applyBatch(ContactsContract.AUTHORITY, batch)
                    rawIds.add(ContentUris.parseId(result[0].uri!!))
                }
            } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
            val latch = CountDownLatch(1)
            var found: ContactBook.Result? = null
            book.find("Шлемтест") { found = it; latch.countDown() }
            assertTrue(latch.await(10, TimeUnit.SECONDS))
            assertNull(found!!.error)
            assertEquals(2, found!!.entries.size)
            instrumentation.runOnMainSync {
                val tools = DeviceTools(context)
                assertTrue(tools.offerContacts("Шлемтест", found!!).text.contains("Какой номер"))
                val ambiguous = tools.execute(VoiceCommand.Confirm)!!
                assertNull("Confirm must not bypass choosing a contact", ambiguous.afterSpeech)
                val choice = tools.execute(VoiceCommand.Choose(0))!!
                assertTrue(choice.text.contains(found!!.entries.first().name))
                assertTrue(choice.text.contains("подтверждаю"))
                assertNull("Choosing must not place a call", choice.afterSpeech)
                val confirmed = tools.execute(VoiceCommand.Confirm)!!
                assertNotNull(confirmed.afterSpeech)
                assertTrue(confirmed.handoff)
                assertNull("Confirmation must be consumed once", tools.execute(VoiceCommand.Confirm)!!.afterSpeech)
                // Deliberately do not invoke the call closure.
            }
        } finally {
            book.close()
            instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.WRITE_CONTACTS)
            try {
                rawIds.forEach { id -> context.contentResolver.delete(ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, id), null, null) }
            } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
        }
    }
}
