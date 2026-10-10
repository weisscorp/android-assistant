package local.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.util.concurrent.Executors
import local.voice.SessionDiagnostics.Phase

/** One owner of microphone, audio focus, inference and network for a user-invoked session. */
class VoiceConversation internal constructor(
    context: Context,
    private val status: (String) -> Unit,
    private val finished: () -> Unit,
    private val requireHeadset: Boolean = false,
    telegramPackages: Set<String> = setOf("org.telegram.messenger", "org.telegram.messenger.web"),
    private val speechObserver: ((String) -> Unit)? = null,
    private val phoneMicrophone: Boolean = !requireHeadset && runCatching { AudioRoute.headsets(context).isEmpty() }.getOrDefault(true),
    private val microphoneLevel: ((Int, Boolean) -> Unit)? = null,
    private val recognized: ((String) -> Unit)? = null,
    private val releaseScreen: () -> Unit = {},
    private val transcript: ((String, Boolean) -> Unit)? = null,
    private val textOnly: Boolean = false,
    private val replyObserver: ((String) -> Unit)? = null,
    private val weatherSource: WeatherDataSource = OpenMeteo
) {
    companion object {
        private var activeConversation: VoiceConversation? = null
        internal val activePhase: Phase? get() = activeConversation?.diagnostics?.phase
        internal val activeSpeechLevel: Int get() = activeConversation?.speechLevel ?: 0
        fun finishActiveUtterance() { activeConversation?.finishUtterance() }
        fun stopActive(): Boolean {
            val active = activeConversation ?: return false
            active.stop()
            return true
        }
    }
    private var weatherNavigation: WindyNavigation? = null
    private var weatherQuery: PublicWeatherQuery? = null
    private var presenceQuery: PresenceQuery? = null
    private var telegramPresence: TelegramPresence? = null
    private val context = context.applicationContext
    // Internal composition seam; Android intents and model requests cannot change it.
    private val telegramPackages = telegramPackages.toSet()
    private val main = Handler(Looper.getMainLooper())
    private var journal: InteractionJournal.Scope? = null
    private var recognizedCall: String? = null
    private val tools = DeviceTools(this.context)
    private val contacts = ContactBook(this.context)
    private val people = PeopleMemory(this.context)
    private val mobileApps = MobileApps(this.context)
    private var peoplePending: Pair<Long, (VoiceCommand) -> Unit>? = null
    private var brainMode = runCatching { AssistantRequests.Mode.valueOf(context.getSharedPreferences("settings", 0).getString("brain_mode", "AUTO")!!) }.getOrDefault(AssistantRequests.Mode.AUTO)
    private val hermesSession = java.util.UUID.randomUUID().toString()
    private var serverAnnounced = false
    private var localRequest: String? = null
    private val callGuard = CallGuard(this.context) {
        status("Разговор завершён: входящий или активный звонок."); end("phone_call")
    }
    private val diagnostics = SessionDiagnostics(this.context, requireHeadset)
    private val network = Executors.newSingleThreadExecutor()
    private var request: BackendClient.Request? = null
    private data class ScreenTaskState(val request: String, val until: Long,
        var remaining: String = request, var pkg: String? = null, var steps: Int = 0, var waiting: String? = null,
        var waitUntil: Long = 0, var offered: VoiceCommand? = null, var replans: Int = 0,
        val receipts: MutableList<String> = mutableListOf(), val seen: MutableSet<String> = mutableSetOf())
    private var screenTask: ScreenTaskState? = null
    private var telegramFlow: TelegramFlow? = null
    private var pendingTelegram: Pair<TelegramRequest, Long>? = null
    private var pendingOpenChoice: Pair<VoiceCommand.OpenThen, Long>? = null
    private var pendingUpdate: Pair<File, Long>? = null
    private var route: AudioRoute? = null
    private var audioLease: Int? = null
    private val playback = PlaybackReference()
    private val visualAudio = ShortArray(160)
    private val speechLevel: Int get() {
        playback.frame(System.nanoTime(), visualAudio)
        return kotlin.math.sqrt(visualAudio.sumOf { it.toDouble() * it } / visualAudio.size).toInt()
    }
    private fun createSpeech() = OfflineSpeech(this.context, playback, persistent = true, phoneMicrophone = phoneMicrophone, readyCueExpected = true,
        level = { raw, clean, silenced -> diagnostics.capture(offlineSpeech.captureSource, offlineSpeech.inputDeviceType,
            offlineSpeech.rawInputSamples, offlineSpeech.nonzeroInputSamples); diagnostics.audio(raw, clean, silenced); microphoneLevel?.invoke(raw, silenced) }, fatal = ::recordingFailed)
    internal fun recordingFailed(error: String) {
        journal?.event("recording_error", InteractionJournal.data("error" to error))
        if (!closed) {
            status(error)
            if (error == OfflineSpeech.NO_HEADSET_AUDIO && ready) {
                offlineSpeech.close()
                say(error, monitorInput = false) { end("headset_no_input") }
            } else end("recording_error")
        }
    }
    private var offlineSpeech: OfflineSpeech = createSpeech()
    private val localVoice = LocalVoice(this.context, playback, phoneSpeaker = phoneMicrophone)
    private var closed = false
    internal val ownsActiveConversation: Boolean get() = activeConversation === this
    private var stopping = false
    private var started = false
    private var turn = 0
    private var speechId = 0
    private var ready = false
    private var replyDeadline: Runnable? = null
    private var speechGuard: Runnable? = null
    internal val speechOutputDeviceType: Int? get() = localVoice.outputDeviceType
    internal val speechFrames: Long get() = localVoice.playingFrames
    private var workDeadline: Runnable? = null
    private var initDeadline: Runnable? = null
    private var startDeadline: Runnable? = null
    private var retries = 0
    private val history = mutableListOf<Pair<String, String>>()

    fun start() {
        if (closed || started) return
        started = true
        activeConversation?.takeIf { it !== this }?.close("replaced")
        activeConversation = this
        journal = InteractionJournal.start(context, textOnly, requireHeadset)
        diagnostics.phase(Phase.STARTING)
        if (textOnly) { ready = true; listen(); return }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status("Откройте приложение и разрешите микрофон."); end("permission"); return
        }
        callGuard.start()
        if (closed) return
        status("Запускаю голосовой сервис…")
        startDeadline = Runnable { status("Голосовой сервис не запустился. Повторите вызов."); end("foreground_timeout") }
            .also { main.postDelayed(it, 10_000) }
        audioLease = AudioForeground.acquire(context, { stop() }) { error ->
            if (closed) return@acquire
            startDeadline?.let(main::removeCallbacks); startDeadline = null
            if (error != null) { status(error); end("foreground_error") } else startAudio()
        }
    }
    private fun startAudio() {
        diagnostics.phase(Phase.ROUTING)
        status("Подключаю голосовой канал…")
        route = AudioRoute(context, phoneOnly = phoneMicrophone, onHeadsetEnd = ::headsetEndedAudio) {
            status("Разговор остановлен: голосовой канал отключился или занят."); end("audio_lost")
        }
        route!!.start(requireHeadset) { error ->
            if (closed) return@start
            if (error != null) { status(error); end("route_error"); return@start }
            initializeModels()
        }
    }
    internal fun headsetEndedAudio() {
        if (closed) return
        status("Разговор остановлен гарнитурой.")
        // The headset has already closed SCO, so do not play a cue or retain capture
        // waiting for a reply. Release inference, playback and the foreground lease.
        end("headset_ended_audio")
    }
    private fun initializeModels() {
        diagnostics.phase(Phase.LOADING)
        status("Загружаю распознавание…")
        initDeadline = Runnable {
            if (!closed) { status("Не удалось запустить распознавание."); end("model_timeout") }
        }.also { main.postDelayed(it, 30_000) }
        offlineSpeech.prepare { error ->
            if (closed) return@prepare
            initDeadline?.let(main::removeCallbacks); initDeadline = null
            if (error != null) { status(error); end("model_error") }
        }
        // Capture first. OfflineSpeech buffers PCM if a cold decoder is still loading.
        ready = true
        listen()
    }
    private var backgroundModelsStarted = false
    private fun startBackgroundModels() {
        if (closed || backgroundModelsStarted) return
        backgroundModelsStarted = true
        LocalBrain.preload(context)
        localVoice.initialize { error ->
            if (!closed && error != null) { status(error); end("model_error") }
        }
    }
    private fun readyCue() {
        offlineSpeech.muteReadyCue()
        ReadyCue.play(context, phoneMicrophone)
    }
    private class EndCue(var next: () -> Unit, var completed: Boolean = false)
    private var endCue: EndCue? = null
    private fun finishedCue(next: () -> Unit = {}) {
        endCue?.let { cue ->
            if (cue.completed) next() else cue.next = next
            return
        }
        val cue = EndCue(next).also { endCue = it }
        diagnostics.phase(Phase.THINKING)
        status("Обрабатываю запись…")
        offlineSpeech.muteReadyCue(500)
        ReadyCue.play(context, phoneMicrophone, descending = true) { error ->
            // Leave the Bluetooth transport tail clear before starting speech or barge-in.
            main.postDelayed({
                if (!closed && endCue === cue) {
                    cue.completed = true
                    val afterPlayback = cue.next; cue.next = {}
                    afterPlayback()
                }
            }, if (error == null) 200 else 0)
        }
    }
    private fun listen() {
        if (closed || stopping) return
        if (textOnly) { diagnostics.phase(Phase.LISTENING); status("Готов к сообщению"); return }
        cancelRecognition()
        endCue = null
        val attempt = turn
        val recognition = journal?.begin("recognition", InteractionJournal.data("headset_required" to requireHeadset))
        status("Готовлю локальное распознавание…")
        offlineSpeech.listen(requireHeadset, {
            if (!closed && attempt == turn) {
                if (it == "Слушаю…") diagnostics.phase(Phase.LISTENING)
                status(it)
                if (it == "Слушаю…") { recognition?.event("recording", InteractionJournal.data("capture_source" to offlineSpeech.captureSource, "device_type" to offlineSpeech.inputDeviceType)); readyCue(); startBackgroundModels() }
                else transcript?.invoke(it, false)
            }
        }) { text, error ->
            if (closed || attempt != turn) return@listen
            cancelRecognition()
            val generation = turn
            recognition?.result(InteractionJournal.data("text" to text, "error" to error, "capture_source" to offlineSpeech.captureSource, "device_type" to offlineSpeech.inputDeviceType, "input_samples" to offlineSpeech.rawInputSamples, "nonzero_samples" to offlineSpeech.nonzeroInputSamples))
            recognizedCall = recognition?.id
            if (!text.isNullOrBlank()) { recognized?.invoke(text); transcript?.invoke(text, true) }
            val processResult = {
                if (!closed && generation == turn) when {
                    error != null -> say(error) { end("recording_error") }
                    text.isNullOrBlank() -> if (retries++ == 0) say("Не расслышал. Повторите.") else { status("Речь не распознана. Проверьте уровень микрофона и повторите."); end("no_speech") }
                    else -> { retries = 0; respond(text) }
                }
            }
            if (error == null) finishedCue(processResult) else processResult()
        }
    }
    internal val listening: Boolean get() = !closed && diagnostics.phase == Phase.LISTENING
    fun finishUtterance() {
        if (!closed && !textOnly && diagnostics.phase == Phase.LISTENING) {
            journal?.event("listening_stop", InteractionJournal.data("reason" to "button"))
            offlineSpeech.finishUtterance()
            finishedCue()
        }
    }
    fun respond(text: String, expectedPackage: String? = null) {
        if (closed || stopping || !ready) return
        journal = journal?.nextTurn()
        journal?.event("user_request", InteractionJournal.data("text" to text, "input" to if (textOnly) "text" else "speech", "expected_package" to expectedPackage, "recognition_id" to recognizedCall))
        recognizedCall = null
        clearSpeechGuard(); cancelRecognition(); cancelWork(); localVoice.stop(); speechId++
        replyDeadline?.let(main::removeCallbacks); replyDeadline = null
        status(text)
        localRequest = null
        val sourceAlias = PresenceFacts.query(text)
        val command = if (sourceAlias != null && SavedSources(context).matching(sourceAlias).isNotEmpty())
            VoiceCommand.ReadSource(sourceAlias) else VoiceCommands.parse(text)
        journal?.event("request_route", InteractionJournal.command(command, expectedPackage))
        tools.clockAnswer(command,text)?.let { deliver(it); return }
        val personChoice = peoplePending
        peoplePending = null
        if (personChoice != null && (command == VoiceCommand.Confirm || command == VoiceCommand.Cancel || command is VoiceCommand.Choose)) {
            if (SystemClock.elapsedRealtime() > personChoice.first) say("Выбор связи истёк. Повторите команду.")
            else personChoice.second(command)
            return
        }
        mobileApps.answer(command)?.let { deliver(it); return }
        val apiWeather = weatherQuery
        if (apiWeather != null && apiWeather.answer(command)) return
        apiWeather?.cancel(); weatherQuery = null
        val weather = weatherNavigation
        if (weather != null && weather.answer(command)) return
        weather?.cancel(); weatherNavigation = null
        val telegram = telegramFlow
        if (telegram != null && telegram.answer(command, text)) return
        telegram?.cancel(); telegramFlow = null
        val telegramOpening = pendingTelegram
        pendingTelegram = null
        if (telegramOpening != null && (command == VoiceCommand.Confirm || command is VoiceCommand.Choose)) {
            if (SystemClock.elapsedRealtime() > telegramOpening.second) { tools.clear(); say("Выбор Telegram истёк."); return }
            val reply = tools.execute(command) ?: return
            if (reply.appSelection) { pendingTelegram = telegramOpening; deliver(reply) }
            else continueTelegram(telegramOpening.first, reply)
            return
        }
        val task = screenTask
        if (task != null && (command == VoiceCommand.Confirm || command is VoiceCommand.Choose)) {
            if (SystemClock.elapsedRealtime() > task.until || SystemClock.elapsedRealtime() > task.waitUntil || task.waiting == null) {
                stopScreenTask("Подтверждение задачи истекло. Повторите задачу."); return
            }
            if (task.waiting == "app") handleScreenReply(task, tools.execute(command), task.offered!!)
            else if (command == VoiceCommand.Confirm) handleScreenReply(task, tools.execute(command, task.pkg), task.offered!!)
            else say("Скажите подтверждаю или отмена.")
            return
        }
        if (task != null) { screenTask = null; tools.clear() }
        if (command is VoiceCommand.ScreenTask) {
            pendingOpenChoice = null; pendingUpdate = null; tools.clear()
            if (PhoneAccessibility.connected == null || !LocalBrain.model(context).exists()) {
                say("Для задачи по экрану включите доступ к экрану и импортируйте локальную модель."); return
            }
            val state = ScreenTaskState(command.request, SystemClock.elapsedRealtime() + 300_000)
            screenTask = state; nextScreenStep(state); return
        }
        val waitingOpen = pendingOpenChoice
        pendingOpenChoice = null
        if (waitingOpen != null && (command is VoiceCommand.Choose || command == VoiceCommand.Confirm)) {
            if (SystemClock.elapsedRealtime() > waitingOpen.second) { tools.clear(); say("Выбор приложения истёк. Повторите команду."); return }
            val reply = tools.execute(command) ?: return
            if (reply.appSelection) { pendingOpenChoice = waitingOpen; deliver(reply) }
            else if (reply.openedPackage != null) continueOpen(waitingOpen.first, reply)
            else deliver(reply)
            return
        }
        val waitingUpdate = pendingUpdate
        pendingUpdate = null
        if (command == VoiceCommand.Stop) { tools.clear(); say("До связи.") { end("voice_stop") }; return }
        if (command == VoiceCommand.Confirm && waitingUpdate != null) {
            tools.clear()
            if (SystemClock.elapsedRealtime() > waitingUpdate.second) { say("Подтверждение обновления истекло. Скажите проверь обновления."); return }
            if (!context.packageManager.canRequestPackageInstalls()) { say("Откройте приложение и один раз разрешите установку обновлений."); return }
            say("Передаю обновление установщику. Разговор завершится. Помощник будет доступен после установки.") {
                end("update_handoff")
                Updates.enqueueInstall(context, waitingUpdate.first)
            }
        } else if (command == VoiceCommand.UpdateStatus) {
            tools.clear(); say(Updates.message(context))
        } else if (command == VoiceCommand.UpdateCheck) {
            tools.clear()
            diagnostics.phase(Phase.SERVER)
            status("Проверяю и скачиваю обновление…")
            val generation = turn
            val activeRequest = BackendClient.Request().also { request = it }
            deadline(generation, 180_000)
            monitorStop()
            network.execute {
                val result = runCatching { Updates.download(context, activeRequest) }
                main.post {
                    if (!closed && generation == turn) {
                        clearWorkDeadline(); request = null
                        result.onSuccess { file ->
                            if (file == null) say("Более новой версии на сервере нет.")
                            else {
                                pendingUpdate = file to (SystemClock.elapsedRealtime() + 30_000)
                                say("Скачана и проверена новая версия. Установка завершит разговор. Скажите подтверждаю или отмена.")
                            }
                        }.onFailure { say(it.message ?: "Не удалось проверить обновление.") }
                    }
                }
            }
        } else if (command is VoiceCommand.BrainMode) {
            tools.clear(); mobileApps.clear()
            brainMode = command.mode; serverAnnounced = false
            say(when (brainMode) {
                AssistantRequests.Mode.LOCAL -> "Работаю на телефоне. Сервер автоматически не подключаю."
                AssistantRequests.Mode.AUTO -> "Автоматический режим. Команды телефона выполняю локально, сложные вопросы передаю настроенному Hermes."
                AssistantRequests.Mode.HERMES -> if (BackendClient.endpoint(context).isBlank()) "Режим Hermes выбран, но домашний сервер ещё не настроен. Команды телефона доступны. Для разговора без сервера скажите локальный режим."
                    else "Для вопросов выбран Hermes. Распознавание и озвучка остаются на телефоне. Связь проверю при следующем запросе."
            })
        } else if (command == VoiceCommand.Capabilities || command == VoiceCommand.Help) {
            tools.clear(); say(CapabilityCatalog.message(context).take(3000))
        } else if (command == VoiceCommand.People) {
            tools.clear(); say(people.message())
        } else if (command == VoiceCommand.Sources) {
            val items = SavedSources(context).list()
            say(if (items.isEmpty()) "Знаний пока нет. Добавьте чат, страницу, место или картинку через Поделиться в Ассистент либо в Параметрах, Поведение и память, Знания и источники."
                else "Сохранены: ${items.joinToString("; ") { it.name }}. Спросите где и сохранённое название.")
        } else if (command is VoiceCommand.ReadSource) {
            readSource(command.alias, question = text)
        } else if (command is VoiceCommand.RememberPerson) {
            rememberPerson(command)
        } else if (command is VoiceCommand.FindContact) {
            tools.clear(); diagnostics.phase(Phase.CONTACTS); status("Ищу в адресной книге…")
            val generation = turn
            deadline(generation, 10_000); monitorStop()
            contacts.find(command.name) { result ->
                if (!closed && generation == turn) {
                    clearWorkDeadline()
                    say(result.error ?: if (result.entries.isEmpty()) "Не нашёл контакт ${command.name}." else
                        result.entries.take(5).joinToString(" ") { "${it.name}, ${it.kind}, номер заканчивается на ${it.number.takeLast(4).toCharArray().joinToString(" ")}." })
                }
            }
        } else if (command is VoiceCommand.RememberTelegram) {
            tools.clear()
            confirmPerson("Запомнить: ${PersonNames.key(command.alias)} в Telegram — ${command.target}?") {
                people.telegram(command.alias, command.target)
            }
        } else if (command is VoiceCommand.ForgetPerson) {
            tools.clear()
            confirmPerson("Забыть связь ${PersonNames.key(command.alias)} и её имя Telegram?") { people.forget(command.alias) }
        } else if (command is VoiceCommand.Telegram) {
            openTelegram(command.request)
        } else if (command is VoiceCommand.Route) {
            releaseScreen(); tools.clear(); deliver(mobileApps.navigate(command.request))
        } else if (command is VoiceCommand.Navigate) {
            releaseScreen(); tools.clear(); deliver(mobileApps.navigate(NavigationRequest(command.destination)))
        } else if (command is VoiceCommand.Music) {
            releaseScreen(); tools.clear()
            // The large model can canonicalize a spoken artist name with its configured web tools.
            if (brainMode != AssistantRequests.Mode.LOCAL && BackendClient.endpoint(context).isNotBlank() && command.request.artist.any { it in 'А'..'я' || it == 'ё' || it == 'Ё' })
                askHermes(text)
            else deliver(mobileApps.music(command.request))
        } else if (command is VoiceCommand.OpenThen) {
            openThen(command)
        } else if (command is VoiceCommand.Weather) {
            readWeather(command.request)
        } else if (command is VoiceCommand.HomeMessages) {
            tools.clear()
            val inbox = HomeInbox(context)
            val device = HomeLink.device(context)
            val reading = inbox.reading(command.latest)
            say(reading.first, display = "Сообщения от Hermes", allowed = { inbox.allowed() && device == HomeLink.device(context) },
                onDenied = { say("Чтение личных сообщений остановлено. Проверьте блокировку и подключение.") },
                then = { inbox.markSpoken(device, reading.second); listen() })
        } else if (command is VoiceCommand.Notifications) {
            readNotifications(command)
        } else if (command is VoiceCommand.Server || command == VoiceCommand.ServerStatus || command == VoiceCommand.ServerCancel) {
            submitServer(command)
        } else if (command is VoiceCommand.Chat) {
            tools.clear()
            val knowledge = SavedSources(context).relevant(command.text)
            val location = LocationQuestion.subject(command.text, people.list().flatMap { listOf(it.alias, it.name) })
            if (location != null && knowledge.isEmpty()) { say(LocationQuestion.MISSING); return }
            if (location != null && knowledge.size > 1) { say(LocationQuestion.ambiguous(knowledge.map { it.name })); return }
            if (knowledge.isEmpty() && BrainRouting.target(command.text, brainMode, BackendClient.endpoint(context).isNotBlank()) == BrainRouting.Target.HERMES) {
                askHermes(command.text); return
            }
            if (!LocalBrain.model(context).exists()) {
                if (brainMode != AssistantRequests.Mode.LOCAL && BackendClient.endpoint(context).isNotBlank()) { askHermes(command.text); return }
                say("Для свободного разговора импортируйте локальную модель. Скажите помощь для списка голосовых команд."); return
            }
            diagnostics.phase(Phase.THINKING)
            status("Думаю локально…")
            val generation = turn
            localRequest = command.text
            if (ModelIntent.needsTools(command.text) || knowledge.isNotEmpty()) {
                deadline(generation, 45_000)
                monitorStop()
                plan(command.text, generation)
            } else {
                deadline(generation, 30_000)
                monitorStop()
                LocalBrain.answerWithMemory(context, history.toList(), command.text) { answer ->
                    if (!closed && generation == turn) {
                        clearWorkDeadline()
                        localRequest = null
                        if (BrainRouting.failed(answer, LocalBrain.lastFailure) && brainMode != AssistantRequests.Mode.LOCAL && BackendClient.endpoint(context).isNotBlank()) {
                            askHermes(command.text); return@answerWithMemory
                        }
                        history.add(command.text to answer)
                        if (history.size > 4) history.removeAt(0)
                        say(answer)
                    }
                }
            }
        } else execute(command, expectedPackage) { reply -> if (reply != null) deliver(reply) else say("Не смог выполнить команду. Скажите помощь.") }
    }
    private fun confirmPerson(message: String, save: () -> Unit) {
        peoplePending = (SystemClock.elapsedRealtime() + 60_000) to { command ->
            if (command == VoiceCommand.Confirm) {
                val result = runCatching(save)
                journal?.event("action_receipt", InteractionJournal.data("name" to "save_person", "success" to result.isSuccess, "error" to result.exceptionOrNull()?.message))
                say(if (result.isSuccess) "Связь сохранена." else "Не удалось сохранить связь.")
            } else say("Связь не изменена.")
        }
        say("$message Скажите подтверждаю или отмена.")
    }
    private fun rememberPerson(command: VoiceCommand.RememberPerson) {
        tools.clear(); diagnostics.phase(Phase.CONTACTS); status("Ищу контакт для связи…")
        val generation = turn
        deadline(generation, 10_000); monitorStop()
        contacts.find(command.name, aliases = false) { result ->
            if (closed || generation != turn) return@find
            clearWorkDeadline()
            result.error?.let { say(it); return@find }
            val choices = result.entries.distinctBy { it.lookup }.filter { it.lookup.isNotBlank() }
            fun save(entry: ContactChoices.Entry) = confirmPerson("Связать ${PersonNames.key(command.alias)} с контактом ${entry.name}?") { people.contact(command.alias, entry) }
            if (choices.size == 1) save(choices.single())
            else if (choices.isEmpty()) say("Не нашёл контакт ${command.name}. Назовите имя как в адресной книге.")
            else if (choices.size > 5) say("Слишком много контактов. Назовите имя и фамилию точнее.")
            else {
                fun offer() {
                    peoplePending = (SystemClock.elapsedRealtime() + 60_000) to { next ->
                        if (next is VoiceCommand.Choose) choices.getOrNull(next.index)?.let(::save) ?: offer()
                        else say("Связь не изменена.")
                    }
                    say("Кого запомнить? " + choices.mapIndexed { i, entry -> "${i + 1}: ${entry.name}." }.joinToString(" ") + " Скажите первый, второй или отмена.")
                }
                offer()
            }
        }
    }
    private fun openTelegram(original: TelegramRequest) {
        releaseScreen(); tools.clear()
        val person = if (original.operation == TelegramRequest.Operation.LOCATE) null else people.get(original.recipient)
        // An address-book name is not proof of a Telegram identity. Keep the Telegram chooser.
        val target = person?.telegram?.takeIf { it.isNotBlank() } ?: person?.name?.takeIf { it.isNotBlank() } ?: original.recipient
        val request = original.copy(recipient = target)
        val reply = tools.execute(VoiceCommand.Open("Telegram")) ?: return
        if (reply.appSelection) {
            pendingTelegram = request to (SystemClock.elapsedRealtime() + 60_000); deliver(reply)
        } else continueTelegram(request, reply)
    }
    private fun askHermes(text: String) {
        journal?.event("request_route", InteractionJournal.data("name" to "hermes", "text" to text))
        tools.clear(); localRequest = null
        if (BackendClient.endpoint(context).isBlank()) { say("Домашний Hermes ещё не настроен. Команды телефона доступны. Скажите локальный режим для местной модели."); return }
        fun send() {
            if (closed) return
            diagnostics.phase(Phase.SERVER); status("Hermes · жду ответ сервера…")
            val generation = turn
            val recent = history.toList()
            val activeRequest = BackendClient.Request().also { request = it }
            deadline(generation, 50_000); monitorStop()
            network.execute {
                val result = runCatching { HermesConversation.ask(context, hermesSession, text, recent, activeRequest) }
                main.post {
                    if (!closed && generation == turn) {
                        clearWorkDeadline(); request = null
                        val response = result.getOrElse { HermesConversation.Result("Не удалось связаться с Hermes. Запрос не повторялся автоматически. Команды телефона доступны.") }
                        history.add(text to if (response.action == null) response.reply else "Hermes предложил действие телефона; выполнение ещё не подтверждено.")
                        if (history.size > 4) history.removeAt(0)
                        val proposed = response.action
                        if (proposed != null && !RemotePhoneTools.requested(proposed, text)) {
                            say("Hermes предложил действие, которого нет в вашем запросе. Уточните, что выполнить на телефоне.")
                            return@post
                        }
                        when (val action = proposed) {
                            null -> say(response.reply)
                            is VoiceCommand.Telegram -> openTelegram(action.request)
                            is VoiceCommand.Weather -> readWeather(action.request)
                            is VoiceCommand.Route -> { releaseScreen(); deliver(mobileApps.navigate(action.request)) }
                            is VoiceCommand.Music -> { releaseScreen(); deliver(mobileApps.music(action.request)) }
                            else -> execute(action) { reply -> deliver(reply ?: DeviceTools.Reply("Инструмент телефона недоступен.")) }
                        }
                    }
                }
            }
        }
        if (!serverAnnounced) {
            serverAnnounced = true
            say("Подключаю Hermes. Передаю контекст разговора.", then = ::send)
        } else send()
    }
    private fun continueTelegram(request: TelegramRequest, reply: DeviceTools.Reply) {
        val pkg = reply.openedPackage
        if (pkg !in telegramPackages) {
            tools.clear(); say(if (pkg == null) reply.text else "Этот клиент Telegram пока не поддерживается для поиска и сообщений."); return
        }
        if (PhoneAccessibility.connected == null) { say("Включите доступ к экрану для работы с Telegram."); return }
        continueOpen(VoiceCommand.OpenThen("Telegram", ""), reply) {
            val flow = TelegramFlow(request, pkg!!, { text, allowed, then ->
                say(text, display = "Telegram", allowed = allowed,
                    onDenied = { telegramFlow?.cancel(); telegramFlow = null; say("Экран Telegram изменился. Чтение остановлено.") }) {
                    if (then == null) listen() else { then(); if (telegramFlow == null || telegramFlow?.awaitingFollowUp == true) listen() }
                }
            }, { monitorStop() }, { telegramFlow = null }, if (request.operation == TelegramRequest.Operation.LOCATE) {
                { SavedSources(context).get(request.text)?.let { readTelegramSource(it, pkg) } ?: say("Источник удалён. Добавьте его заново.") }
            } else null)
            telegramFlow = flow; flow.start()
        }
    }
    private fun openThen(command: VoiceCommand.OpenThen) {
        releaseScreen()
        tools.clear()
        if (PhoneAccessibility.connected == null) {
            say("Для продолжения команды в приложении включите доступ к экрану в настройках помощника."); return
        }
        val reply = tools.execute(VoiceCommand.Open(command.app)) ?: return
        if (reply.appSelection) {
            pendingOpenChoice = command to (SystemClock.elapsedRealtime() + 60_000)
            deliver(reply); return
        }
        continueOpen(command, reply)
    }
    private fun continueOpen(command: VoiceCommand.OpenThen, reply: DeviceTools.Reply, continued: (() -> Unit)? = null) {
        val expected = reply.openedPackage
        if (expected == null || reply.afterSpeech == null) { deliver(reply); return }
        say(reply.text) {
            val failure = reply.beforeOpening?.invoke() ?: if (PhoneAccessibility.connected?.foregroundPackage() == expected) null else reply.afterSpeech.invoke()
            journal?.event("action_receipt", InteractionJournal.data("name" to "open_app", "package" to expected, "error" to failure))
            if (failure != null) { screenTask = null; tools.clear(); say(failure); return@say }
            val generation = turn
            diagnostics.phase(Phase.ACTION)
            status("Жду экран приложения…")
            val openedAt = SystemClock.elapsedRealtime()
            val until = openedAt + 5000
            var previous: PhoneAccessibility.ForegroundSnapshot? = null
            var stableSince = openedAt
            deadline(generation, 6000)
            monitorStop()
            val poll = object : Runnable {
                override fun run() {
                    if (closed || turn != generation) return
                    val snapshot = PhoneAccessibility.connected?.foregroundSnapshot()
                    val now = SystemClock.elapsedRealtime()
                    if (snapshot != previous) { previous = snapshot; stableSince = now }
                    if (snapshot?.app == expected && now - stableSince >= 400 && now - openedAt >= 400) {
                        clearWorkDeadline()
                        if (continued != null) continued() else respond(command.next, expected)
                    } else if (now >= until) {
                        clearWorkDeadline()
                        screenTask = null; tools.clear()
                        say("Не удалось проверить экран открытого приложения. Продолжение команды отменено.")
                    } else main.postDelayed(this, 100)
                }
            }
            main.post(poll)
        }
    }
    private fun stopScreenTask(message: String) {
        screenTask = null; tools.clear(); clearWorkDeadline(); say(message)
    }
    private fun nextScreenStep(task: ScreenTaskState) {
        if (closed || screenTask !== task) return
        if (task.steps >= 8 || SystemClock.elapsedRealtime() > task.until) {
            stopScreenTask("Достигнут предел задачи. Продолжение остановлено."); return
        }
        releaseScreen()
        val service = PhoneAccessibility.connected
        val snapshot = service?.foregroundSnapshot()
        if (task.pkg != null && (snapshot == null || task.pkg != snapshot.app)) {
            stopScreenTask("Экран недоступен или приложение изменилось. Задача остановлена."); return
        }
        val observation = if (snapshot == null) "Приложение ещё не открыто." else service?.readScreen().orEmpty()
        diagnostics.phase(Phase.THINKING); status("Выбираю следующий шаг по экрану…")
        val generation = turn
        deadline(generation, 95_000); monitorStop()
        LocalBrain.planScreenTask(context, task.request, observation, task.receipts, task.pkg == null, task.remaining) { proposal ->
            if (closed || generation != turn || screenTask !== task) return@planScreenTask
            clearWorkDeadline()
            val action = proposal.command
            // Completion/error proposals also depend on the observed frame. Reobserve them
            // only in that same app; explicit Open still follows application selection.
            if (action !is VoiceCommand.Open && task.pkg == null && snapshot != null)
                task.pkg = snapshot.app
            val currentFrame = PhoneAccessibility.connected?.foregroundSnapshot()
            if (currentFrame != snapshot) {
                if (ScreenReplan.allowed(task.pkg, snapshot?.app, currentFrame?.app, task.replans)) {
                    task.replans++
                    tools.clear(); task.waiting = null; task.offered = null
                    say("Экран изменился. Проверю его заново перед действием.") { waitScreenTask(task) }
                } else stopScreenTask("Экран изменился во время выбора шага. Задача остановлена.")
                return@planScreenTask
            }
            proposal.answer?.let { stopScreenTask(it); return@planScreenTask }
            if (action == null) {
                screenTask = null; tools.clear()
                val expected = task.pkg ?: snapshot?.app
                if (expected == null) { stopScreenTask("Приложение не открыто. Задача остановлена."); return@planScreenTask }
                say("Работу по экрану закончил. Текущий экран: ${PhoneAccessibility.connected?.readScreen().orEmpty()}",
                    display = "Работа по экрану закончена.",
                    allowed = { PhoneAccessibility.connected?.foregroundPackage() == expected },
                    onDenied = { say("Экран недоступен. Чтение остановлено.") })
                return@planScreenTask
            }
            if (action is VoiceCommand.Open && task.pkg != null) {
                stopScreenTask("Повторное открытие приложения остановлено. Уточните задачу."); return@planScreenTask
            }
            if (!task.seen.add("$action:$snapshot")) {
                stopScreenTask("Следующий шаг повторяется без изменения экрана. Задача остановлена."); return@planScreenTask
            }
            task.steps++
            if (action !is VoiceCommand.Open && task.pkg == null) {
                if (snapshot == null) { stopScreenTask("Нет экрана приложения для действия."); return@planScreenTask }
                task.pkg = snapshot.app
            }
            handleScreenReply(task, tools.execute(action, if (action is VoiceCommand.Open) null else task.pkg), action)
        }
    }
    private fun handleScreenReply(task: ScreenTaskState, reply: DeviceTools.Reply?, action: VoiceCommand) {
        if (closed || screenTask !== task) return
        if (reply == null) { stopScreenTask("Шаг недоступен. Задача остановлена."); return }
        if (reply.appSelection || reply.actionPending) {
            task.waiting = if (reply.appSelection) "app" else "action"
            task.offered = action
            task.waitUntil = SystemClock.elapsedRealtime() + if (reply.appSelection) 60_000 else 30_000
            deliver(reply); return
        }
        task.waiting = null
        if (reply.openedPackage != null && action is VoiceCommand.Open) {
            task.pkg = reply.openedPackage
            continueOpen(VoiceCommand.OpenThen(action.app, ""), reply) {
                task.receipts.add("Открыто приложение ${action.app}")
                task.remaining = ScreenTaskProgress.remaining(task.remaining, action)
                nextScreenStep(task)
            }
            return
        }
        fun completed(receipt: String) {
            journal?.event("action_receipt", InteractionJournal.data("command" to InteractionJournal.command(action), "text" to receipt))
            task.remaining = ScreenTaskProgress.remaining(task.remaining, action)
            task.receipts.add(when (action) {
                is VoiceCommand.Tap -> "Нажата кнопка ${action.label}: $receipt"
                is VoiceCommand.Write -> "Записан текст ${action.text}: $receipt"
                else -> "$action: $receipt"
            })
            say(receipt) { waitScreenTask(task) }
        }
        if (reply.verify != null) {
            val generation = turn
            deadline(generation, 5000); monitorStop()
            reply.verify.invoke { result ->
                if (!closed && generation == turn && screenTask === task) {
                    clearWorkDeadline()
                    // This receipt is generated by our own readback verifier, never by app text or the model.
                    if (result == "Текст записан в поле. Отправка не выполнялась.") completed(result)
                    else stopScreenTask(result)
                }
            }
        } else if (reply.actionAccepted) completed(reply.text)
        else stopScreenTask(reply.text)
    }
    private fun waitScreenTask(task: ScreenTaskState) {
        val generation = turn
        val until = SystemClock.elapsedRealtime() + 5000
        var previous: PhoneAccessibility.ForegroundSnapshot? = null
        var stableSince = SystemClock.elapsedRealtime()
        deadline(generation, 6000); monitorStop()
        val poll = object : Runnable {
            override fun run() {
                if (closed || generation != turn || screenTask !== task) return
                val snapshot = PhoneAccessibility.connected?.foregroundSnapshot()
                val now = SystemClock.elapsedRealtime()
                if (snapshot != previous) { previous = snapshot; stableSince = now }
                if (snapshot == null || snapshot.app != task.pkg) stopScreenTask("Приложение изменилось. Задача остановлена.")
                else if (now - stableSince >= 400) { clearWorkDeadline(); nextScreenStep(task) }
                else if (now >= until) stopScreenTask("Экран не стабилизировался. Задача остановлена.")
                else main.postDelayed(this, 100)
            }
        }
        main.post(poll)
    }
    private fun submitServer(command: VoiceCommand) {
        tools.clear()
        diagnostics.phase(Phase.SERVER)
        status("Запрос домашнему серверу…")
        val generation = turn
        val activeRequest = BackendClient.Request().also { request = it }
        deadline(generation, 45_000)
        monitorStop()
        network.execute {
            val answer = ServerJobs.perform(context, command, activeRequest)
            main.post {
                if (!closed && generation == turn) { clearWorkDeadline(); request = null; say(answer) }
            }
        }
    }
    private fun readNotifications(command: VoiceCommand.Notifications) {
        journal?.event("tool_request", InteractionJournal.command(command))
            tools.clear()
            val generation = turn
            deadline(generation, 10_000)
            monitorStop()
            network.execute {
                val result = runCatching { PhoneNotifications.speech(context, command.latest) }
                    .getOrDefault(NotificationSpeech.Result("Не удалось прочитать уведомления."))
                main.post { if (!closed && generation == turn) {
                    clearWorkDeadline()
                    val allowed: (() -> Boolean)? = if (!result.containsNotifications) null else {
                        { PhoneNotifications.hasAccess(context) && (!result.containsPrivate ||
                            context.getSharedPreferences("settings", 0).getBoolean("notifications_when_locked", false) ||
                            !context.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked) }
                    }
                    say(result.text, display = "Уведомления", allowed = allowed, onDenied = {
                        say(if (!PhoneNotifications.hasAccess(context)) "Доступ к уведомлениям отключён. Чтение остановлено."
                            else "Телефон заблокирован. Чтение личных уведомлений остановлено.")
                    })
                } }
            }
    }
    private fun readWeather(request: WeatherRequest) {
        journal?.event("tool_request", InteractionJournal.data("name" to "weather", "request" to request.toString()))
        tools.clear()
        if (!request.fromWindy) {
            diagnostics.phase(Phase.ACTION); status("Получаю погоду из Open-Meteo…")
            lateinit var query: PublicWeatherQuery
            query = PublicWeatherQuery(context, request, { !closed && weatherQuery === query },
                { message -> say(message) }, { answer ->
                    if (!closed && weatherQuery === query) { weatherQuery = null; say(answer) }
                }, weatherSource)
            weatherQuery = query
            monitorStop(); query.start()
            return
        }
        releaseScreen()
        if (request.day == null) { say("Пока могу прочитать дневную карточку windy.app на сегодня, завтра или послезавтра. Для почасового прогноза откройте таблицу и скажите прочитай экран."); return }
        if (PhoneAccessibility.connected == null) { say("Включите доступ к экрану в настройках помощника."); return }
        diagnostics.phase(Phase.ACTION); status("Определяю место и читаю windy.app…")
        lateinit var navigation: WindyNavigation
        navigation = WindyWeather.navigation(context, request, { !closed && weatherNavigation === navigation },
            { message -> say(message) }, { answer ->
                if (!closed && weatherNavigation === navigation) { weatherNavigation = null; say(answer) }
            })
        weatherNavigation = navigation
        monitorStop(); navigation.start()
    }
    private fun readSource(alias: String, question: String = "", summarize: Boolean = false) {
        journal?.event("tool_request", InteractionJournal.data("name" to "read_source", "alias" to alias, "question" to question, "summarize" to summarize))
        tools.clear()
        val matches = SavedSources(context).matching(alias)
        if (matches.isEmpty() && SavedSources(context).relevant(question.ifBlank { alias }).isNotEmpty() && LocalBrain.model(context).exists()) {
            val request = question.ifBlank { "Прочитай источник $alias" }
            localRequest = request; diagnostics.phase(Phase.THINKING); status("Выбираю знание…")
            deadline(turn, 45_000); monitorStop(); plan(request, turn); return
        }
        if (matches.size != 1) { say("Такой источник не подключён однозначно. Добавьте его в Параметрах, Поведение и память, Знания и источники."); return }
        val source = matches.single()
        diagnostics.phase(Phase.ACTION); status("Читаю ${source.name}…")
        if (source.kind != SourceKind.TELEGRAM) {
            lateinit var query: PresenceQuery
            query = PresenceQuery(context, source, { !closed && presenceQuery === query }, if (summarize) question else "") { answer -> presenceQuery = null; clearWorkDeadline(); say(answer) }
            presenceQuery = query; deadline(turn, 45_000); monitorStop(); query.start(); return
        }
        releaseScreen()
        if (PhoneAccessibility.connected == null) { say("Включите доступ к экрану и разблокируйте телефон для Telegram."); return }
        if (TelegramPresence.isTelegramLink(source.value)) {
            val reply = tools.openTelegramSource(source.value, telegramPackages)
            if (reply.afterSpeech == null || reply.openedPackage == null) { deliver(reply); return }
            continueOpen(VoiceCommand.OpenThen("Telegram", ""), reply) { readTelegramSource(source, reply.openedPackage) }
        } else openTelegram(TelegramRequest(source.value, TelegramRequest.Operation.LOCATE, source.id))
    }
    private fun readTelegramSource(source: SavedSource, pkg: String) {
        lateinit var reader: TelegramPresence
        reader = TelegramPresence(context, source, pkg, { !closed && telegramPresence === reader }, { evidence, error ->
            if (closed || telegramPresence !== reader) return@TelegramPresence
            telegramPresence = null; reader.cancel()
            if (evidence == null) { say(error ?: "Не удалось прочитать положение."); return@TelegramPresence }
            lateinit var query: PresenceQuery
            query = PresenceQuery(context, source, { !closed && presenceQuery === query }) { answer -> presenceQuery = null; say(answer) }
            presenceQuery = query; query.describe(evidence)
        }, { url ->
            if (!closed && telegramPresence === reader) {
                telegramPresence=null; reader.cancel()
                lateinit var query: PresenceQuery
                query=PresenceQuery(context,source.copy(kind=SourceKind.WEB,value=url, readerRecipe=SavedSources(context).list().firstOrNull { it.kind == SourceKind.WEB && it.value == url }?.readerRecipe.orEmpty()),{ !closed && presenceQuery === query }) { answer -> presenceQuery=null; say(answer) }
                presenceQuery=query; status("Читаю ссылку из выбранного чата…"); query.start()
            }
        })
        telegramPresence = reader; monitorStop(); reader.start()
    }
    private fun execute(command: VoiceCommand, expectedPackage: String? = null, done: (DeviceTools.Reply?) -> Unit) {
        val call = journal?.begin("dispatch", InteractionJournal.command(command, expectedPackage))
        executeInternal(command, expectedPackage) { reply -> call?.result(InteractionJournal.reply(reply)); done(reply) }
    }
    private fun executeInternal(command: VoiceCommand, expectedPackage: String?, done: (DeviceTools.Reply?) -> Unit) {
        if (command is VoiceCommand.Navigate) { releaseScreen(); done(mobileApps.navigate(NavigationRequest(command.destination))); return }
        if (command is VoiceCommand.Open || command is VoiceCommand.Write || command is VoiceCommand.Tap ||
            command is VoiceCommand.Scroll || command == VoiceCommand.Screen || command == VoiceCommand.Confirm ||
            command is VoiceCommand.Choose) releaseScreen()
        if (command == VoiceCommand.Screen) {
            val frame = PhoneAccessibility.connected?.foregroundSnapshot()
            if (frame == null) { done(DeviceTools.Reply("Для чтения экрана разблокируйте телефон и включите доступ к экрану.")); return }
            val generation = turn
            diagnostics.phase(Phase.ACTION); status("Читаю текст и изображение экрана…")
            deadline(generation, 25_000); monitorStop()
            AppScreenReader.read(context, frame, { !closed && generation == turn }) { result, error ->
                if (!closed && generation == turn) { clearWorkDeadline(); done(DeviceTools.Reply(result?.speech() ?: error ?: "Экран не прочитан.")) }
            }
        } else if (command is VoiceCommand.Media && command.action == PhoneMedia.Action.PLAY) {
            done(PhoneMedia.execute(context, command.action, deferPlay = true))
        } else if (command is VoiceCommand.Call && VoiceCommands.phoneNumber(command.number) == null) {
            tools.clear()
            if (!command.number.matches(Regex("[\\p{L} .'-]{1,120}"))) { done(DeviceTools.Reply("Назовите номер отдельными цифрами или имя контакта.")); return }
            diagnostics.phase(Phase.CONTACTS)
            status("Ищу контакт…")
            val generation = turn
            deadline(generation, 10_000)
            monitorStop()
            contacts.find(command.number) { result ->
                if (!closed && generation == turn) { clearWorkDeadline(); done(tools.offerContacts(command.number, result)) }
            }
        } else done(tools.execute(command, expectedPackage))
    }
    private fun plan(text: String, generation: Int, observation: String? = null) {
        LocalBrain.plan(context, history.toList(), text, observation) { proposal ->
            if (closed || generation != turn) return@plan
            val action = proposal.command
            if (observation == null && action == null && (ModelIntent.needsTools(text) || BrainRouting.failed(proposal.answer.orEmpty(), LocalBrain.lastFailure)) &&
                brainMode != AssistantRequests.Mode.LOCAL && BackendClient.endpoint(context).isNotBlank()) {
                clearWorkDeadline(); askHermes(text); return@plan
            }
            if (action is VoiceCommand.ReadSource) {
                clearWorkDeadline(); localRequest = null
                readSource(action.alias, text, summarize = !Regex("^\\s*прочитай\\s+источник\\s+", RegexOption.IGNORE_CASE).containsMatchIn(text)); return@plan
            }
            if (action is VoiceCommand.Weather) {
                clearWorkDeadline(); readWeather(action.request); return@plan
            }
            if (action is VoiceCommand.OpenThen) {
                clearWorkDeadline()
                openThen(action)
                return@plan
            }
            if (action is VoiceCommand.Server || action == VoiceCommand.ServerStatus || action == VoiceCommand.ServerCancel) {
                clearWorkDeadline()
                localRequest = null
                if (action is VoiceCommand.Server && !action.request.startsWith("разработай обновление помощника ")) {
                    if (brainMode != AssistantRequests.Mode.LOCAL) askHermes(text)
                    else say("Эта задача выходит за возможности локальных модулей. Скажите свяжи с гермесом или выберите другую команду.")
                } else submitServer(action)
                return@plan
            }
            if (action is VoiceCommand.Notifications) {
                clearWorkDeadline()
                readNotifications(action)
                return@plan
            }
            if (action is VoiceCommand.Media && action.action == PhoneMedia.Action.INFO) {
                diagnostics.tool("media", "info", "requested_by_model")
                diagnostics.phase(Phase.ACTION); status("Читаю данные проигрывателя…")
                execute(action) { result ->
                    if (closed || generation != turn) return@execute
                    val data = result?.observation
                    if (data == null) { clearWorkDeadline(); localRequest = null; say("Инструмент не вернул данные проигрывателя."); return@execute }
                    diagnostics.tool(data.tool, data.operation, "returned_" + data.status)
                    diagnostics.phase(Phase.THINKING); status("Готовлю ответ по данным проигрывателя…")
                    clearWorkDeadline()
                    // A response timeout keeps the successfully read facts instead of discarding them.
                    workDeadline = Runnable {
                        if (!closed && generation == turn) {
                            cancelWork(); localRequest = null
                            diagnostics.tool(data.tool, data.operation, "answer_timeout")
                            say(data.fallback)
                        }
                    }.also { main.postDelayed(it, 30_000) }
                    LocalBrain.answerTool(context, text, data) { answer ->
                        if (closed || generation != turn) return@answerTool
                        clearWorkDeadline(); localRequest = null
                        diagnostics.tool(data.tool, data.operation, "answered_after_result")
                        history.add(text to answer)
                        if (history.size > 4) history.removeAt(0)
                        say(answer)
                    }
                }
                return@plan
            }
            if (action == VoiceCommand.Screen && observation == null) {
                releaseScreen()
                execute(action) { result ->
                    if (!closed && generation == turn) plan(text, generation, result?.text ?: "Экран недоступен.")
                }
                return@plan
            }
            clearWorkDeadline()
            localRequest = null
            fun reply(result: DeviceTools.Reply?) {
                val answer = result?.text ?: proposal.answer ?: "Не смог выполнить запрос."
                history.add(text to answer)
                if (history.size > 4) history.removeAt(0)
                deliver(result ?: DeviceTools.Reply(answer))
            }
            if (action == null) reply(null) else execute(action, done = ::reply)
        }
    }
    private fun deliver(reply: DeviceTools.Reply) {
        journal?.event("tool_reply", InteractionJournal.reply(reply))
        reply.mediaHandoff?.let { handoff ->
            say(reply.text) {
                cancelRecognition(); cancelWork(); tools.clear()
                val generation = turn
                val headsetKey = route?.selectionKey
                offlineSpeech.close()
                route?.close(); route = null
                status("Передаю музыку проигрывателю…")
                val trace = journal
                handoff { success, result ->
                    trace?.event("action_receipt", InteractionJournal.data("name" to "media_handoff", "success" to success, "text" to result))
                    if (!closed && generation == turn) {
                        status(result)
                        if (success) end("media_handoff")
                        else restoreVoiceAfterMediaFailure(result, generation, headsetKey)
                    }
                }
            }
            return
        }
        reply.verify?.let { verify ->
            diagnostics.phase(Phase.ACTION)
            status(reply.text)
            val generation = turn
            deadline(generation, 5000)
            monitorStop()
            val trace = journal
            verify { result ->
                trace?.event("action_receipt", InteractionJournal.data("name" to "verify", "text" to result))
                if (!closed && generation == turn) { clearWorkDeadline(); say(result) }
            }
            return
        }
        say(reply.text) {
            val failure = reply.afterSpeech?.invoke()
            if (reply.afterSpeech != null) journal?.event("action_receipt", InteractionJournal.data("name" to "after_speech", "error" to failure))
            if (failure != null) say(failure)
            else if (reply.handoff) end("call_handoff")
            else listen()
        }
    }
    private fun restoreVoiceAfterMediaFailure(message: String, generation: Int, headsetKey: String?) {
        if (CallGuard.inCall(context) == true) { status("Идёт звонок. Голосовой режим не включаю."); end("phone_call"); return }
        ready = false
        initDeadline = Runnable { if (!closed && generation == turn && !ready) {
            status("Не удалось восстановить голосовой режим."); end("model_timeout")
        } }.also { main.postDelayed(it, 30_000) }
        route = AudioRoute(context, requiredKey = headsetKey, phoneOnly = phoneMicrophone,
            onHeadsetEnd = ::headsetEndedAudio) { status("Голосовой канал отключился."); end("audio_lost") }
        route!!.start(requireHeadset) { error ->
            if (closed || generation != turn) return@start
            if (error != null) { status(error); end("route_error"); return@start }
            offlineSpeech = createSpeech()
            offlineSpeech.prepare { failure ->
                if (!closed && generation == turn) {
                    if (failure != null) { status(failure); end("model_error") }
                    else { ready = true; initDeadline?.let(main::removeCallbacks); initDeadline = null; say(message) }
                }
            }
        }
    }
    private fun clearSpeechGuard() { speechGuard?.let(main::removeCallbacks); speechGuard = null }
    private fun say(text: String, display: String? = null, allowed: (() -> Boolean)? = null,
                    onDenied: (() -> Unit)? = null, monitorInput: Boolean = true, then: () -> Unit = { listen() }) {
        if (closed) return
        clearSpeechGuard()
        if (allowed != null && !runCatching(allowed).getOrDefault(false)) { journal?.event("response_blocked", InteractionJournal.data("reason" to "permission_or_screen_changed")); onDenied?.invoke(); return }
        cancelRecognition()
        diagnostics.phase(Phase.SPEAKING)
        val answer = SpokenReply.answer(text)
        val trace = journal
        trace?.event("assistant_response", InteractionJournal.data("text" to answer, "original_text" to text, "display" to display, "text_only" to textOnly))
        status(display?.takeIf(SpokenReply::hasContent) ?: answer)
        if (!ready) { end("voice_unavailable"); return }
        replyObserver?.invoke(answer)
        if (textOnly) { then(); return }
        val spoken = SpokenReply.text(answer)
        val id = ++speechId
        replyDeadline?.let(main::removeCallbacks)
        replyDeadline = Runnable { status("Озвучка не завершилась. Повторите вызов."); end("tts_timeout") }.also { main.postDelayed(it, SpokenReply.timeoutMillis(spoken)) }
        fun deny() {
            if (closed || id != speechId) return
            clearSpeechGuard(); replyDeadline?.let(main::removeCallbacks); replyDeadline = null
            speechId++; localVoice.stop(); playback.clear()
            onDenied?.invoke() ?: end("speech_policy")
        }
        if (allowed != null) {
            speechGuard = object : Runnable {
                override fun run() {
                    if (closed || id != speechId) return
                    if (!runCatching(allowed).getOrDefault(false)) deny() else main.postDelayed(this, 100)
                }
            }.also { main.postDelayed(it, 100) }
        }
        val startOutput = output@{
            if (allowed != null && !runCatching(allowed).getOrDefault(false)) { deny(); return@output }
            if (!closed && id == speechId) {
                trace?.event("speech_start", InteractionJournal.data("text" to spoken))
                speechObserver?.invoke(spoken)
                localVoice.speak(spoken) { error ->
                    trace?.event("speech_end", InteractionJournal.data("error" to error, "interrupted" to (closed || id != speechId)))
                    if (closed || id != speechId) return@speak
                    clearSpeechGuard(); replyDeadline?.let(main::removeCallbacks); replyDeadline = null
                    if (error != null) { status(error); end("tts_error") } else then()
                }
            }
        }
        if (monitorInput) monitorStop(startOutput) else startOutput()
    }
    private fun monitorStop(ready: () -> Unit = {}) {
        if (textOnly) { ready(); return }
        offlineSpeech.interrupt(requireHeadset, ready) {
            if (!closed) { status("Разговор остановлен голосом."); end("voice_interrupt") }
        }
    }
    private fun deadline(generation: Int, milliseconds: Long) {
        clearWorkDeadline()
        workDeadline = Runnable {
            if (!closed && generation == turn) {
                val retry = localRequest
                localRequest = null
                journal?.event("request_timeout", InteractionJournal.data("timeout_ms" to milliseconds, "server_fallback" to (retry != null && brainMode != AssistantRequests.Mode.LOCAL && BackendClient.endpoint(context).isNotBlank())))
                turn++; cancelWork(); tools.clear(); screenTask = null
                if (retry != null && brainMode != AssistantRequests.Mode.LOCAL && BackendClient.endpoint(context).isNotBlank()) askHermes(retry)
                else say("Запрос занял слишком много времени. Повторите или назовите другую команду.")
            }
        }.also { main.postDelayed(it, milliseconds) }
    }
    private fun clearWorkDeadline() { workDeadline?.let(main::removeCallbacks); workDeadline = null }
    private fun cancelWork() {
        clearWorkDeadline(); request?.cancel(); request = null; contacts.cancel(); LocalBrain.cancel()
        telegramPresence?.cancel(); telegramPresence = null; presenceQuery?.cancel(); presenceQuery = null
    }
    private fun cancelRecognition() { turn++; offlineSpeech.cancel() }
    fun stop() {
        if (closed || stopping) return
        if (!textOnly && (listening || endCue?.completed == false)) {
            stopping = true
            // Stop capture now; retain the selected output only through the end signal.
            cancelRecognition(); cancelWork(); localVoice.stop(); speechId++
            offlineSpeech.close()
            finishedCue { end("button_stop") }
            diagnostics.phase(Phase.STOPPED)
            status("Разговор остановлен.")
        } else { status("Разговор остановлен."); end("button_stop") }
    }
    private fun end(reason: String) { if (closed) return; close(reason); finished() }
    fun close(reason: String = "cancelled") {
        if (closed) return
        journal?.let { InteractionJournal.end(it, reason) }
        closed = true
        endCue = null
        if (activeConversation === this) activeConversation = null
        callGuard.close(); clearSpeechGuard()
        diagnostics.capture(offlineSpeech.captureSource, offlineSpeech.inputDeviceType,
            offlineSpeech.rawInputSamples, offlineSpeech.nonzeroInputSamples)
        diagnostics.finish(reason)
        speechId++; cancelRecognition(); cancelWork()
        offlineSpeech.close(); contacts.close(); tools.clear()
        startDeadline?.let(main::removeCallbacks); initDeadline?.let(main::removeCallbacks); replyDeadline?.let(main::removeCallbacks)
        localVoice.close(); playback.clear(); LocalBrain.dropContext()
        route?.close(); route = null
        audioLease?.let { AudioForeground.release(context, it) }; audioLease = null
        telegramFlow?.cancel(); telegramFlow = null; pendingTelegram = null
        weatherNavigation?.cancel(); weatherNavigation = null
        weatherQuery?.cancel(); weatherQuery = null
        network.shutdownNow(); history.clear(); screenTask = null; pendingUpdate = null; pendingOpenChoice = null
        peoplePending = null; mobileApps.clear()
    }
}
