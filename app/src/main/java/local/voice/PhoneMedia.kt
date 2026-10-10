package local.voice

import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Read current track or control an existing session; never launches or guesses between players. */
object PhoneMedia {
    enum class Action { PLAY, PAUSE, NEXT, PREVIOUS, LOUDER, QUIETER, INFO }
    fun execute(context: Context, action: Action, deferPlay: Boolean = false): DeviceTools.Reply {
        val trace = InteractionJournal.current?.begin("tool", InteractionJournal.data("name" to "media", "action" to action.name, "defer_play" to deferPlay))
        return try { executeInternal(context, action, deferPlay).also { trace?.result(InteractionJournal.reply(it)) } }
        catch (error: Exception) { trace?.error(error); throw error }
    }
    private fun executeInternal(context: Context, action: Action, deferPlay: Boolean): DeviceTools.Reply {
        fun result(text: String, status: String): DeviceTools.Reply = DeviceTools.Reply(text,
            observation = if (action == Action.INFO) ToolObservation("media", "info", status, emptyMap(), text) else null)
        if (action == Action.LOUDER || action == Action.QUIETER) return volume(context, action)
        if (!PhoneNotifications.hasAccess(context)) return result(if (action == Action.INFO)
            "Чтобы узнать текущую песню, разрешите доступ к уведомлениям в настройках помощника."
            else "Для управления музыкой разрешите доступ к уведомлениям в настройках помощника.", "permission_required")
        val sessions = runCatching { context.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(PhoneNotifications.component(context)).filter { it.packageName != context.packageName }.map { it to it.playbackState } }.getOrNull()
            ?: return result("Android не предоставил доступ к проигрывателям. Проверьте разрешение.", "unavailable")
        val playing = sessions.filter { it.second?.state in setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING) }
        val candidates = if (playing.isNotEmpty()) playing else sessions.filter { it.second?.state in setOf(PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED) }
        if (candidates.isEmpty()) return result("Активного проигрывателя нет. Сначала запустите музыку в приложении.", "no_player")
        if (candidates.size != 1) return result("Найдено несколько проигрывателей. Не могу однозначно выбрать музыку.", "ambiguous")
        val controller = candidates.single().first
        val state = candidates.single().second
        if (action == Action.INFO) return runCatching {
            val metadata = controller.metadata
            val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            val playback = when (state?.state) {
                PlaybackState.STATE_PLAYING -> NowPlayingReply.State.PLAYING
                PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> NowPlayingReply.State.LOADING
                PlaybackState.STATE_PAUSED -> NowPlayingReply.State.PAUSED
                else -> NowPlayingReply.State.STOPPED
            }
            val fields = linkedMapOf("player" to controller.packageName, "state" to playback.name.lowercase())
            NowPlayingReply.value(title)?.let { fields["title"] = it }
            NowPlayingReply.value(artist)?.let { fields["artist"] = it }
            val text = NowPlayingReply.text(title, artist, playback)
            DeviceTools.Reply(text, observation = ToolObservation("media", "info",
                if ("title" in fields || "artist" in fields) "ok" else "missing_metadata", fields, text))
        }.getOrElse { result("Не удалось прочитать текущий трек из проигрывателя. Повторите вопрос.", "error") }
        val required = when (action) {
            Action.PLAY -> PlaybackState.ACTION_PLAY
            Action.PAUSE -> PlaybackState.ACTION_PAUSE
            Action.NEXT -> PlaybackState.ACTION_SKIP_TO_NEXT
            Action.PREVIOUS -> PlaybackState.ACTION_SKIP_TO_PREVIOUS
            else -> error("Volume handled separately")
        }
        if ((state?.actions ?: 0) and required == 0L) return DeviceTools.Reply("Проигрыватель не поддерживает эту команду.")
        if (deferPlay && action == Action.PLAY) return DeviceTools.Reply(
            "Завершаю разговор и передаю музыку проигрывателю.", mediaHandoff = { done ->
                val valid = runCatching {
                    if (CallGuard.inCall(context) == true) false else
                    if (!PhoneNotifications.hasAccess(context)) false else {
                        val current = context.getSystemService(MediaSessionManager::class.java).getActiveSessions(PhoneNotifications.component(context))
                            .filter { it.packageName != context.packageName }.map { it to it.playbackState }
                        val active = current.filter { it.second?.state in setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING) }
                        val chosen = if (active.isNotEmpty()) active else current.filter { it.second?.state in setOf(PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED) }
                        chosen.singleOrNull()?.let { it.first.sessionToken == controller.sessionToken &&
                            (it.second?.actions ?: 0) and PlaybackState.ACTION_PLAY != 0L } == true
                    }
                }.getOrDefault(false)
                if (!valid) done(false, "Проигрыватель или разрешение изменились. Продолжение музыки отменено.")
                else if (runCatching { InteractionJournal.action("media_play", InteractionJournal.data("player" to controller.packageName)) { controller.transportControls.play() } }.isFailure) done(false, "Не удалось передать команду проигрывателю.")
                else {
                    val main = Handler(Looper.getMainLooper())
                    val until = SystemClock.elapsedRealtime() + 3000
                    fun poll() {
                        val played = runCatching { controller.playbackState?.state == PlaybackState.STATE_PLAYING }.getOrDefault(false)
                        if (played) done(true, "Проигрыватель включил музыку.")
                        else if (SystemClock.elapsedRealtime() >= until) done(false, "Проигрыватель не подтвердил продолжение музыки. Повторите команду.")
                        else main.postDelayed({ poll() }, 100)
                    }
                    poll()
                }
            })
        val before = track(controller)
        val sent = runCatching {
            when (action) {
                Action.PLAY -> InteractionJournal.action("media_play", InteractionJournal.data("player" to controller.packageName)) { controller.transportControls.play() }
                Action.PAUSE -> InteractionJournal.action("media_pause", InteractionJournal.data("player" to controller.packageName)) { controller.transportControls.pause() }
                Action.NEXT -> InteractionJournal.action("media_skipToNext", InteractionJournal.data("player" to controller.packageName)) { controller.transportControls.skipToNext() }
                Action.PREVIOUS -> InteractionJournal.action("media_skipToPrevious", InteractionJournal.data("player" to controller.packageName)) { controller.transportControls.skipToPrevious() }
                else -> Unit
            }
        }.isSuccess
        if (!sent) return DeviceTools.Reply("Не удалось передать команду проигрывателю.")
        return DeviceTools.Reply("Проверяю проигрыватель…", verify = { done ->
            val main = Handler(Looper.getMainLooper())
            val until = SystemClock.elapsedRealtime() + 3000
            fun poll() {
                val matched = runCatching {
                    when (action) {
                        Action.PLAY -> controller.playbackState?.state == PlaybackState.STATE_PLAYING
                        Action.PAUSE -> controller.playbackState?.state in setOf(PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED)
                        else -> {
                            val after = track(controller)
                            after.indices.any { index -> after[index] != null && before[index] != null &&
                                after[index] != before[index] && (index != 0 || after[index] != android.media.session.MediaSession.QueueItem.UNKNOWN_ID) }
                        }
                    }
                }.getOrDefault(false)
                if (matched) done(when (action) {
                    Action.PLAY -> "Проигрыватель включил музыку."
                    Action.PAUSE -> "Музыка на паузе."
                    Action.NEXT -> "Проигрыватель сменил трек."
                    else -> "Проигрыватель сменил трек."
                }) else if (SystemClock.elapsedRealtime() >= until) done("Команда передана, но проигрыватель не подтвердил результат.")
                else main.postDelayed({ poll() }, 100)
            }
            poll()
        })
    }
    private fun track(controller: MediaController): List<Any?> = listOf(
        controller.playbackState?.activeQueueItemId,
        controller.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID),
        controller.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE),
        controller.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST))
    private fun volume(context: Context, action: Action): DeviceTools.Reply {
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio.isVolumeFixed) return DeviceTools.Reply("На этом устройстве громкость фиксирована.")
        return runCatching {
            val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (action == Action.LOUDER) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
            val after = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            DeviceTools.Reply(if (after == before) "Громкость музыки не изменилась. Возможно, достигнут предел."
                else "Громкость музыки ${if (max > 0) after * 100 / max else 0} процентов.")
        }.getOrElse { DeviceTools.Reply("Не удалось изменить громкость музыки.") }
    }
}
