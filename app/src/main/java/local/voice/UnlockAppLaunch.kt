package local.voice

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.util.UUID

/** One process-local, expiring request. Consumed before execution; never persisted. */
internal class UnlockActionQueue(private val now: () -> Long) {
    private data class Request(val token: String, val until: Long, val action: () -> String?)
    private var request: Request? = null
    @Synchronized fun stage(until: Long, action: () -> String?): String {
        check(until > now()) { "Запрос открытия истёк. Повторите команду." }
        return UUID.randomUUID().toString().also { request = Request(it, until, action) }
    }
    @Synchronized fun remaining(token: String): Long {
        val current = request?.takeIf { it.token == token } ?: return 0
        val left = current.until - now()
        if (left <= 0) request = null
        return maxOf(0, left)
    }
    @Synchronized fun cancel(token: String) { if (request?.token == token) request = null }
    fun run(token: String, allowed: () -> Boolean): String? {
        val action = synchronized(this) {
            if (remaining(token) == 0L) return "Запрос открытия истёк или отменён. Повторите команду."
            if (!allowed()) return "Телефон заблокирован. Приложение пока не открыто."
            if (remaining(token) == 0L) return "Запрос открытия истёк. Повторите команду."
            request!!.action.also { request = null }
        }
        return action()
    }
}

internal object UnlockAppLaunch {
    private val queue = UnlockActionQueue(SystemClock::elapsedRealtime)
    fun locked(context: Context): Boolean = context.getSystemService(KeyguardManager::class.java).let {
        it.isDeviceLocked || it.isKeyguardLocked
    }
    fun start(context: Context, until: Long, action: () -> String?): String? {
        val token = runCatching { queue.stage(until, action) }.getOrElse { return "Запрос открытия истёк. Повторите команду." }
        return runCatching {
            context.startActivity(Intent(context, UnlockAppActivity::class.java)
                .putExtra("request_token", token).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            null
        }.getOrElse { queue.cancel(token); "Android не разрешил показать разблокировку. Откройте ассистент и повторите команду." }
    }
    fun remaining(token: String) = queue.remaining(token)
    fun cancel(token: String) = queue.cancel(token)
    fun complete(context: Context, token: String): String? = queue.run(token) { !locked(context) }
}
