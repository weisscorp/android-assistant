package local.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore

/** Public playback APIs only. Never opens an Activity or dismisses keyguard. */
internal object PackagePlayback {
    const val SPOTIFY = "com.spotify.music"
    fun request(context: Context, pkg: String, label: String, artist: String? = null): DeviceTools.Reply {
        if (!PhoneNotifications.hasAccess(context)) return DeviceTools.Reply(
            "Для управления музыкой разрешите доступ к уведомлениям в настройках помощника.")
        fun identity() = runCatching { context.packageManager.getPackageInfo(pkg, 0).let {
            if (it.applicationInfo?.enabled != true) null else Triple(it.longVersionCode, it.firstInstallTime, it.lastUpdateTime)
        } }.getOrNull()
        val expected = identity() ?: return DeviceTools.Reply("$label не установлен или отключён.")
        val offeredUntil = SystemClock.elapsedRealtime() + 60_000
        return DeviceTools.Reply("Передаю $label команду воспроизведения без разблокировки.", mediaHandoff = { done ->
            val main = Handler(Looper.getMainLooper())
            var browser: MediaBrowser? = null
            var finished = false
            val until = SystemClock.elapsedRealtime() + 8000
            fun valid() = SystemClock.elapsedRealtime() <= offeredUntil && identity() == expected &&
                PhoneNotifications.hasAccess(context) && CallGuard.inCall(context) != true
            fun finish(ok: Boolean, text: String) {
                if (finished) return
                finished = true
                browser?.disconnect()
                done(ok, text)
            }
            fun fail() = finish(false, "$label не подтвердил воспроизведение. Возможно, сначала нужно выбрать музыку в приложении.")
            fun play(controller: MediaController) {
                if (finished) return
                if (!valid() || controller.packageName != pkg) { finish(false, "Приложение или разрешение изменились. Запуск музыки отменён."); return }
                val action = if (artist == null) PlaybackState.ACTION_PLAY else PlaybackState.ACTION_PLAY_FROM_SEARCH
                if ((controller.playbackState?.actions ?: 0L) and action == 0L) { fail(); return }
                val sent = runCatching {
                    InteractionJournal.action("package_play", InteractionJournal.data("player" to pkg, "artist" to artist)) {
                    if (artist == null) controller.transportControls.play()
                    else controller.transportControls.playFromSearch(artist, Bundle().apply {
                        putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
                        putString(MediaStore.EXTRA_MEDIA_ARTIST, artist)
                    })
                    }
                }.isSuccess
                if (!sent) { fail(); return }
                fun poll() {
                    if (finished) return
                    if (!valid()) { finish(false, "Приложение или разрешение изменились. Запуск музыки отменён."); return }
                    val played = runCatching { controller.playbackState?.state == PlaybackState.STATE_PLAYING &&
                        (artist == null || controller.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.equals(artist, true) == true)
                    }.getOrDefault(false)
                    if (played) finish(true, "$label подтвердил воспроизведение${artist?.let { " $it" }.orEmpty()}.")
                    else if (SystemClock.elapsedRealtime() >= until) fail()
                    else main.postDelayed({ poll() }, 100)
                }
                poll()
            }
            main.post {
                if (!valid()) { finish(false, "Приложение или разрешение изменились. Запуск музыки отменён."); return@post }
                val sessions = runCatching { context.getSystemService(MediaSessionManager::class.java)
                    .getActiveSessions(PhoneNotifications.component(context)).filter { it.packageName == pkg } }.getOrNull()
                when {
                    sessions == null -> fail()
                    sessions.size > 1 -> finish(false, "У $label несколько проигрывателей. Не могу однозначно выбрать музыку.")
                    sessions.size == 1 -> play(sessions.single())
                    else -> {
                        // Cold resumption through the player's exported browser; never start its UI behind a PIN.
                        val services = context.packageManager.queryIntentServices(Intent("android.media.browse.MediaBrowserService").setPackage(pkg), 0)
                            .map { it.serviceInfo }.filter { it.exported && it.enabled && it.packageName == pkg && it.permission.isNullOrBlank() }
                        if (services.size != 1) { fail(); return@post }
                        val service = services.single()
                        browser = MediaBrowser(context, ComponentName(pkg, service.name), object : MediaBrowser.ConnectionCallback() {
                            override fun onConnected() {
                                if (!finished) runCatching { play(MediaController(context, browser!!.sessionToken)) }.onFailure { fail() }
                            }
                            override fun onConnectionFailed() = fail()
                            override fun onConnectionSuspended() = fail()
                        }, null)
                        runCatching { browser!!.connect() }.onFailure { fail() }
                    }
                }
            }
            main.postDelayed({ if (!finished) fail() }, 8000)
        })
    }
}
