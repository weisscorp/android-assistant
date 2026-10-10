package local.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Contacts
import android.content.ContentUris
import java.util.concurrent.Executors

/** Uses the AOSP contacts provider; no Google account or network is involved. */
class ContactBook(private val context: Context) {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    @Volatile private var query: CancellationSignal? = null
    data class Result(val entries: List<ContactChoices.Entry> = emptyList(), val error: String? = null)
    fun find(name: String, aliases: Boolean = true, done: (Result) -> Unit) {
        val trace = InteractionJournal.current?.begin("contacts", InteractionJournal.data("name" to name, "aliases" to aliases))
        worker.execute {
            if (closed) return@execute
            val signal = CancellationSignal(); query = signal
            if (closed) signal.cancel()
            val result = runCatching {
                if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
                    return@runCatching Result(error = "Разрешите чтение контактов в настройках помощника. Пока можно назвать номер по цифрам.")
                val person = if (aliases) PeopleMemory(context).get(name) else null
                val lookup = person?.lookup?.takeIf { it.isNotBlank() }
                val resolved = lookup?.let { Contacts.lookupContact(context.contentResolver, Uri.withAppendedPath(Contacts.CONTENT_LOOKUP_URI, it)) }
                if (lookup != null && resolved == null) return@runCatching Result(error = "Сохранённый контакт удалён или изменился. Назначьте связь $name заново.")
                val uri = if (lookup == null) Uri.withAppendedPath(Phone.CONTENT_FILTER_URI, Uri.encode(name.take(120))) else Phone.CONTENT_URI
                val entries = mutableListOf<ContactChoices.Entry>()
                context.contentResolver.query(uri, arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL, Phone.LOOKUP_KEY),
                    if (lookup == null) null else "${Phone.CONTACT_ID} = ?", resolved?.let { arrayOf(ContentUris.parseId(it).toString()) }, null, signal)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        if (entries.size >= 100) return@runCatching Result(error = "Слишком много контактов. Назовите имя и фамилию.")
                        val kind = Phone.getTypeLabel(context.resources, cursor.getInt(3), cursor.getString(4)).toString()
                        entries.add(ContactChoices.Entry(cursor.getLong(0), cursor.getString(1) ?: "Без имени", cursor.getString(2) ?: "", kind, cursor.getString(5).orEmpty()))
                    }
                } ?: return@runCatching Result(error = "На этой прошивке недоступна адресная книга.")
                if (lookup != null) Result(entries.mapNotNull { entry -> VoiceCommands.phoneNumber(entry.number)?.let { entry.copy(number = it) } }.distinctBy { it.id to it.number },
                    if (entries.isEmpty()) "Сохранённый контакт удалён или изменился. Назначьте связь $name заново." else null)
                else Result(ContactChoices.matches(name, entries))
            }.getOrElse { Result(error = "Не удалось прочитать контакты. Назовите номер или повторите запрос.") }
            trace?.result(InteractionJournal.data("entries" to result.entries.map { InteractionJournal.data("id" to it.id, "name" to it.name, "number" to it.number, "kind" to it.kind, "lookup" to it.lookup) }, "error" to result.error, "cancelled" to signal.isCanceled))
            query = null
            main.post { if (!closed) done(result) }
        }
    }
    fun cancel() { query?.cancel() }
    fun close() { closed = true; cancel(); worker.shutdownNow() }
}
