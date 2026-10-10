package local.voice

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService

/** No notification cache, persistence, automatic announcements, or server forwarding. */
class PhoneNotifications : NotificationListenerService() {
    override fun onListenerConnected() { super.onListenerConnected(); connected = this }
    override fun onListenerDisconnected() { if (connected === this) connected = null; super.onListenerDisconnected() }
    override fun onDestroy() { if (connected === this) connected = null; super.onDestroy() }

    companion object {
        @Volatile private var connected: PhoneNotifications? = null
        fun component(context: Context) = ComponentName(context, PhoneNotifications::class.java)
        fun hasAccess(context: Context): Boolean = context.getSystemService(NotificationManager::class.java)
            .isNotificationListenerAccessGranted(component(context))
        fun read(context: Context, latest: Boolean) = speech(context, latest).text
        internal fun speech(context: Context, latest: Boolean): NotificationSpeech.Result {
            if (!hasAccess(context)) return NotificationSpeech.Result("Сначала разрешите доступ к уведомлениям в настройках приложения.")
            val listener = connected ?: run {
                runCatching { requestRebind(component(context)) }
                return NotificationSpeech.Result("Доступ разрешён, но служба уведомлений ещё подключается. Повторите команду через несколько секунд.")
            }
            val notifications = runCatching { listener.activeNotifications }.getOrNull()
                ?: return NotificationSpeech.Result("Android не предоставил уведомления. Проверьте разрешение и повторите команду.")
            val items = notifications.mapNotNull { sbn -> runCatching {
                val n = sbn.notification
                val extras = n.extras
                val lastMessage = extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.let {
                    Notification.MessagingStyle.Message.getMessagesFromBundleArray(it).maxByOrNull { message -> message.timestamp }
                }
                val title = lastMessage?.senderPerson?.name ?: extras.getCharSequence(Notification.EXTRA_TITLE)
                val body = lastMessage?.text ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                    ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.lastOrNull()
                val app = runCatching { context.packageManager.getApplicationInfo(sbn.packageName, 0)
                    .let { context.packageManager.getApplicationLabel(it).toString() } }.getOrDefault(sbn.packageName)
                NotificationSpeech.Item(sbn.packageName, app, title?.toString().orEmpty(), body?.toString().orEmpty(),
                    n.visibility, n.flags, sbn.postTime)
            }.getOrNull() }
            return NotificationSpeech.prepare(items, context.packageName,
                context.getSystemService(KeyguardManager::class.java).isDeviceLocked,
                context.getSharedPreferences("settings", 0).getBoolean("notifications_when_locked", false), latest)
        }
    }
}

internal object NotificationSpeech {
    data class Item(val pkg: String, val app: String, val title: String, val text: String,
                    val visibility: Int, val flags: Int, val time: Long)
    private fun clean(text: String, limit: Int) = text.replace(Regex("[\\p{Cc}\\p{Cf}]+"), " ")
        .replace(Regex("\\s+"), " ").trim().take(limit)
    data class Result(val text: String, val containsPrivate: Boolean = false, val containsNotifications: Boolean = false)
    fun summarize(items: List<Item>, ownPackage: String, locked: Boolean, allowPrivate: Boolean, latest: Boolean) =
        prepare(items, ownPackage, locked, allowPrivate, latest).text
    fun prepare(items: List<Item>, ownPackage: String, locked: Boolean, allowPrivate: Boolean, latest: Boolean): Result {
        val ordinary = items.filter { it.pkg != ownPackage && it.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT) == 0 }
        val visible = ordinary.filter { it.visibility != Notification.VISIBILITY_SECRET &&
            (!locked || allowPrivate || it.visibility == Notification.VISIBILITY_PUBLIC) }
        val lines = visible.sortedByDescending { it.time }.mapNotNull {
            val title = clean(it.title, 80); val text = clean(it.text, 260)
            if (title.isEmpty() && text.isEmpty()) null else clean(it.app, 50) + ": " +
                listOf(title, text).filter(String::isNotEmpty).distinct().joinToString(". ") to (it.visibility != Notification.VISIBILITY_PUBLIC)
        }
        if (lines.isEmpty()) return Result(if (locked && !allowPrivate && ordinary.any { it.visibility == Notification.VISIBILITY_PRIVATE })
            "Личные уведомления скрыты при блокировке. Разрешите их чтение в настройках помощника или разблокируйте телефон."
        else "Доступных для чтения уведомлений нет.")
        val limit = if (latest) 1 else 3
        val selected = lines.take(limit)
        val text = selected.mapIndexed { index, line -> "${index + 1}. ${line.first}." }.joinToString(" ") +
            if (!latest && lines.size > limit) " Есть ещё уведомления. Читаю только три последних." else ""
        return Result(text, selected.any { it.second }, true)
    }
}
