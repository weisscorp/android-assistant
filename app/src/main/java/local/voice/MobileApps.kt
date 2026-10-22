package local.voice

import android.app.KeyguardManager
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore

/** Public app modules, with a voice chooser and no screen-coordinate guesses. */
class MobileApps(private val context: Context) {
    data class Navigator(val pkg: String, val label: String)
    companion object {
        private const val SPOTIFY = "com.spotify.music"
        fun spotifyInstalled(context: Context) = runCatching { context.packageManager.getPackageInfo(SPOTIFY, 0).applicationInfo?.enabled == true }.getOrDefault(false)
        fun navigators(context: Context): List<Navigator> = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=destination")), 0).filter {
                it.activityInfo.packageName in setOf("com.google.android.apps.maps", "ru.yandex.yandexmaps", "ru.dublgis.dgismobile", "net.osmand", "net.osmand.plus", "app.organicmaps", "com.mapswithme.maps.pro") ||
                    Regex("карты|maps|navigator|навигатор|2гис|2gis", RegexOption.IGNORE_CASE).containsMatchIn(it.loadLabel(context.packageManager).toString())
            }.map { Navigator(it.activityInfo.packageName, it.loadLabel(context.packageManager).toString()) }.distinctBy { it.pkg }.sortedBy { it.label }
        fun preferred(context: Context) = context.getSharedPreferences("settings", 0).getString("navigator", "").orEmpty()
        fun prefer(context: Context, pkg: String) { require(pkg.isBlank() || navigators(context).any { it.pkg == pkg }); context.getSharedPreferences("settings", 0).edit().putString("navigator", pkg).apply() }
    }
    private data class Waiting(val until: Long, val answer: (VoiceCommand) -> DeviceTools.Reply?)
    private var waiting: Waiting? = null
    fun clear() { waiting = null }
    private fun locked() = context.getSystemService(KeyguardManager::class.java).isDeviceLocked
    fun answer(command: VoiceCommand): DeviceTools.Reply? {
        val pending = waiting ?: return null
        waiting = null
        if (SystemClock.elapsedRealtime() > pending.until) return DeviceTools.Reply("Выбор истёк. Повторите запрос.")
        if (command == VoiceCommand.Cancel) return DeviceTools.Reply("Отменено.")
        return pending.answer(command)
    }
    private fun wait(answer: (VoiceCommand) -> DeviceTools.Reply?) { waiting = Waiting(SystemClock.elapsedRealtime() + 60_000, answer) }
    fun navigate(request: NavigationRequest): DeviceTools.Reply {
        val trace = InteractionJournal.current?.begin("tool", InteractionJournal.data("name" to "navigate", "request" to request.toString()))
        return navigateInternal(request).also { trace?.result(InteractionJournal.reply(it)) }
    }
    private fun navigateInternal(request: NavigationRequest): DeviceTools.Reply {
        clear()
        if (locked()) return DeviceTools.Reply("Для открытия навигатора разблокируйте телефон.")
        val choices = navigators(context)
        if (choices.isEmpty()) return DeviceTools.Reply("Не найдено установленного навигатора.")
        val preferred = choices.singleOrNull { it.pkg == preferred(context) }
        if (preferred != null) return mode(request, preferred)
        if (choices.size > 5) return DeviceTools.Reply("Выберите предпочитаемый навигатор в настройках помощника.")
        fun offer(): DeviceTools.Reply {
            wait { command ->
                if (command !is VoiceCommand.Choose) null else choices.getOrNull(command.index)?.let { mode(request, it) } ?: offer()
            }
            return DeviceTools.Reply("В каком навигаторе искать ${request.destination}? " + choices.mapIndexed { i, app -> "${i + 1}: ${app.label}." }.joinToString(" ") + " Скажите первый, второй или отмена.")
        }
        return offer()
    }
    private fun mode(request: NavigationRequest, navigator: Navigator): DeviceTools.Reply {
        request.mode?.let { return confirm(request, navigator) }
        fun offer(): DeviceTools.Reply {
            wait { command ->
                val mode = when (command) {
                    is VoiceCommand.Choose -> NavigationRequest.Mode.entries.getOrNull(command.index)
                    is VoiceCommand.Chat -> NavigationRequest.mode(command.text)
                    else -> null
                }
                if (mode == null) if (command is VoiceCommand.Chat || command is VoiceCommand.Choose) offer() else null
                else confirm(request.copy(mode = mode), navigator)
            }
            return DeviceTools.Reply("Как добираться? Первый: на машине. Второй: пешком. Третий: общественным транспортом.")
        }
        return offer()
    }
    private fun confirm(request: NavigationRequest, navigator: Navigator): DeviceTools.Reply {
        val fullRoute = navigator.pkg == "com.google.android.apps.maps"
        wait { command -> if (command != VoiceCommand.Confirm) null else {
            if (!navigators(context).contains(navigator) || locked()) DeviceTools.Reply("Навигатор изменился или телефон заблокирован. Действие отменено.")
            else {
                val uri = if (fullRoute) Uri.parse("https://www.google.com/maps/dir/").buildUpon().appendQueryParameter("api", "1")
                    .appendQueryParameter("destination", request.destination).appendQueryParameter("travelmode", when (request.mode) {
                        NavigationRequest.Mode.WALK -> "walking"; NavigationRequest.Mode.TRANSIT -> "transit"; else -> "driving"
                    }).build() else Uri.parse("geo:0,0?q=${Uri.encode(request.destination)}")
                launch(Intent(Intent.ACTION_VIEW, uri).setPackage(navigator.pkg),
                    if (fullRoute) "Передаю ${navigator.label} запрос маршрута ${request.mode!!.label} до ${request.destination}."
                    else "Открываю поиск ${request.destination} в ${navigator.label}. Способ ${request.mode!!.label} нужно выбрать в навигаторе. Можно затем сказать прочитай экран.")
            }
        } }
        return DeviceTools.Reply(if (fullRoute) "Открыть ${navigator.label}, маршрут ${request.mode!!.label} до ${request.destination}? Скажите подтверждаю или отмена."
            else "Открыть ${navigator.label} для ${request.destination}? Этот модуль пока передаёт поиск адреса; способ ${request.mode!!.label} выбирается в самом навигаторе. Скажите подтверждаю или отмена.")
    }
    fun music(request: MusicRequest): DeviceTools.Reply {
        val trace = InteractionJournal.current?.begin("tool", InteractionJournal.data("name" to "music", "request" to request.toString()))
        return musicInternal(request).also { trace?.result(InteractionJournal.reply(it)) }
    }
    private fun musicInternal(request: MusicRequest): DeviceTools.Reply {
        clear()
        if (UnlockAppLaunch.locked(context) && request.play) return PackagePlayback.request(context, SPOTIFY, "Spotify", request.artist)
        if (locked()) return DeviceTools.Reply("Для просмотра поиска исполнителя разблокируйте телефон.")
        if (!spotifyInstalled(context)) return DeviceTools.Reply("Spotify не установлен.")
        val search = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${Uri.encode(request.artist)}")).setPackage(SPOTIFY)
        val play = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(SPOTIFY)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
            .putExtra(MediaStore.EXTRA_MEDIA_ARTIST, request.artist).putExtra(SearchManager.QUERY, request.artist)
        if (!request.play) return launch(search, "Передаю Spotify поиск исполнителя ${request.artist}.")
        if (play.resolveActivity(context.packageManager) == null || !PhoneNotifications.hasAccess(context))
            return launch(search, "Открываю поиск ${request.artist} в Spotify. Автоматическое воспроизведение пока недоступно: нужен поддерживаемый вход проигрывателя и доступ к уведомлениям.")
        return DeviceTools.Reply("Передаю Spotify запрос исполнителя ${request.artist}. Завершаю голосовой канал, чтобы включить музыку.", mediaHandoff = { done ->
            if (locked() || CallGuard.inCall(context) == true || !PhoneNotifications.hasAccess(context)) done(false, "Телефон или разрешение изменились. Запуск музыки отменён.")
            else if (runCatching { InteractionJournal.action("launch_music", InteractionJournal.data("artist" to request.artist)) { context.startActivity(play.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }.isFailure) done(false, "Spotify не принял запрос воспроизведения.")
            else {
                val until = SystemClock.elapsedRealtime() + 8000
                val main = Handler(Looper.getMainLooper())
                fun poll() {
                    val played = runCatching { context.getSystemService(MediaSessionManager::class.java).getActiveSessions(PhoneNotifications.component(context))
                        .any { controller -> controller.packageName == SPOTIFY && controller.playbackState?.state == PlaybackState.STATE_PLAYING &&
                            controller.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.equals(request.artist, ignoreCase = true) == true }
                    }.getOrDefault(false)
                    if (played) done(true, "Spotify подтвердил воспроизведение ${request.artist}.")
                    else if (SystemClock.elapsedRealtime() >= until) done(false, "Запрос передан Spotify, но воспроизведение ${request.artist} не подтверждено. Можно проверить поиск в приложении.")
                    else main.postDelayed({ poll() }, 150)
                }
                poll()
            }
        })
    }
    private fun launch(intent: Intent, text: String): DeviceTools.Reply {
        if (intent.resolveActivity(context.packageManager) == null) return DeviceTools.Reply("Приложение не поддерживает этот вход.")
        return DeviceTools.Reply(text, afterSpeech = {
            if (locked()) "Телефон заблокирован. Открытие отменено."
            else runCatching { InteractionJournal.action("launch_intent", InteractionJournal.data("action" to intent.action, "package" to intent.`package`, "data" to intent.dataString)) { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; null }.getOrElse { "Android не разрешил открыть приложение." }
        })
    }
}
