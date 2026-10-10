package local.voice

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.SystemClock
import android.os.Bundle
import android.telecom.TelecomManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeviceTools(private val context: Context) {
    data class Reply(val text: String, val afterSpeech: (() -> String?)? = null, val handoff: Boolean = false, val verify: (((String) -> Unit) -> Unit)? = null, val mediaHandoff: (((Boolean, String) -> Unit) -> Unit)? = null, val openedPackage: String? = null, val appSelection: Boolean = false, val beforeOpening: (() -> String?)? = null, val actionPending: Boolean = false, val actionAccepted: Boolean = false, internal val observation: ToolObservation? = null)
    private data class AppChoice(val pkg: String, val label: String, val version: Long, val installed: Long, val updated: Long)
    private data class Pending(val until: Long, val action: (() -> Reply)? = null, val contacts: List<ContactChoices.Entry>? = null, val requiresUnlock: Boolean = true, val apps: List<AppChoice>? = null)
    private var pending: Pending? = null
    private var generation = 0L
    private val clocks = ClockTools(context)
    private fun locked() = context.getSystemService(KeyguardManager::class.java).isDeviceLocked
    fun clear() { generation++; pending = null; clocks.clear() }
    fun clockAnswer(command: VoiceCommand, text: String): Reply? {
        val result = clocks.answer(command, text)
        if (result != null) InteractionJournal.current?.event("tool_result", InteractionJournal.data("name" to "clock_answer", "command" to InteractionJournal.command(command), "result" to InteractionJournal.reply(result)))
        return result
    }
    internal fun openTelegramSource(link: String, packages: Set<String>): Reply {
        if (locked()) return Reply("Для чтения геопозиции Telegram разблокируйте телефон.")
        if (!TelegramPresence.isTelegramLink(link)) return Reply("Ссылка Telegram не поддерживается.")
        val pkg = packages.firstOrNull { runCatching { context.packageManager.getPackageInfo(it, 0); true }.getOrDefault(false) }
            ?: return Reply("Установите официальный Telegram.")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg)
        val result = launch(intent, "Открываю сохранённую геопозицию в Telegram.")
        return result.copy(openedPackage = pkg, beforeOpening = result.afterSpeech)
    }
    fun offerContacts(name: String, result: ContactBook.Result): Reply {
        clear()
        result.error?.let { return Reply(it) }
        val choices = result.entries
        if (choices.isEmpty()) return Reply("Не нашёл контакт $name. Назовите имя как в адресной книге или номер по цифрам.")
        if (choices.size == 1) return call(choices.single().number, choices.single().name)
        if (choices.size > 5) return Reply("Нашёл слишком много номеров. Назовите имя и фамилию точнее.")
        pending = Pending(SystemClock.elapsedRealtime() + 60_000, contacts = choices, requiresUnlock = false)
        return Reply("Какой номер выбрать? " + choices.mapIndexed { index, entry ->
            "${index + 1}: ${entry.name}, ${entry.kind}, заканчивается на ${entry.number.takeLast(4).toCharArray().joinToString(" ")}."
        }.joinToString(" ") + " Скажите первый, второй или отмена.")
    }
    fun execute(command: VoiceCommand, expectedPackage: String? = null): Reply? {
        val call = InteractionJournal.current?.begin("tool", InteractionJournal.command(command, expectedPackage))
        return try { executeInternal(command, expectedPackage).also { call?.result(InteractionJournal.reply(it)) } }
        catch (error: Exception) { call?.error(error); throw error }
    }
    private fun executeInternal(command: VoiceCommand, expectedPackage: String?): Reply? {
        if(command == VoiceCommand.Confirm || command == VoiceCommand.Cancel) clocks.answer(command,"")?.let { return it }
        if (expectedPackage != null && (locked() || PhoneAccessibility.connected?.foregroundPackage() != expectedPackage)) {
            clear(); return Reply("Приложение на экране изменилось. Продолжение команды отменено.")
        }
        if (command != VoiceCommand.Confirm && command !is VoiceCommand.Choose) generation++
        val waiting = pending
        pending = null
        if (command == VoiceCommand.Confirm) {
            if (waiting == null || SystemClock.elapsedRealtime() > waiting.until) return Reply("Нет действия для подтверждения. Повторите команду.")
            if (waiting.requiresUnlock && locked()) return Reply("Для этого действия нужно разблокировать телефон.")
            return waiting.action?.invoke() ?: Reply(if (waiting.apps != null) "Сначала выберите приложение: первый, второй или отмена."
                else "Сначала выберите номер: первый, второй или отмена.", appSelection = waiting.apps != null).also { pending = waiting }
        }
        if (command is VoiceCommand.Choose) {
            if (waiting == null || SystemClock.elapsedRealtime() > waiting.until) return Reply("Нет списка для выбора. Повторите команду.")
            if (waiting.apps != null) {
                if (locked()) return Reply("Для открытия приложения разблокируйте телефон.")
                val selected = waiting.apps.getOrNull(command.index)
                    ?: return Reply("Такого варианта нет. Назовите номер приложения из списка или отмена.", appSelection = true).also { pending = waiting }
                return launchChoice(selected)
            }
            val contact = waiting.contacts?.getOrNull(command.index)
                ?: return Reply("Такого варианта нет. Назовите номер варианта из списка или отмена.").also { pending = waiting }
            return call(contact.number, contact.name)
        }
        if (command == VoiceCommand.Cancel) return Reply("Отменено.")
        return when (command) {
            VoiceCommand.Help -> Reply("Скажите: таймер на десять минут, сколько осталось на таймере, запусти секундомер, останови секундомер, будильник завтра в восемь тридцать, напомни завтра в десять купить хлеб, отмени все таймеры, какая погода, погода завтра, какой ветер в windy.app, который час, заряд, открой и название приложения, прочитай уведомления, прочитай последнее уведомление, что сейчас играет, пауза музыки, продолжи музыку, следующий трек, предыдущий трек, музыку громче, музыку тише, прочитай экран, нажми и точное название кнопки, напиши и текст для поля ввода, прокрути вниз, позвони и имя контакта или номер по цифрам, маршрут и адрес, сервер и ваш запрос, статус сервера, отмени задачу сервера, проверь обновления, статус обновления. Для завершения скажите стоп.")
            VoiceCommand.Time -> Reply("Сейчас ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())}.")
            is VoiceCommand.Clock -> clocks.offer(command.request,command.proposed)
            VoiceCommand.Battery -> Reply("Заряд батареи ${context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)} процентов.")
            is VoiceCommand.Media -> PhoneMedia.execute(context, command.action)
            VoiceCommand.Screen -> Reply(if (locked()) "Телефон заблокирован. Экран недоступен." else PhoneAccessibility.connected?.readScreen() ?: "Включите доступ к экрану в настройках помощника.")
            is VoiceCommand.Scroll -> {
                val accepted = !locked() && PhoneAccessibility.connected?.scroll(command.forward) == true
                Reply(if (accepted) "Команда прокрутки принята приложением." else "Не удалось прокрутить экран.", actionAccepted = accepted)
            }
            is VoiceCommand.Open -> openApp(command.app)
            is VoiceCommand.Tap -> tap(command.label)
            is VoiceCommand.Write -> write(command.text)
            is VoiceCommand.Call -> call(command.number)
            is VoiceCommand.Navigate -> if (locked()) Reply("Для открытия навигации разблокируйте телефон.") else launch(
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(command.destination.take(300))}")), "Открываю карты для ${command.destination.take(150)}."
            )
            VoiceCommand.Stop -> Reply("Разговор завершён.")
            else -> null
        }
    }
    private fun openApp(name: String): Reply {
        if (locked()) return Reply("Для открытия приложения разблокируйте телефон.")
        val apps = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val spoken = AppNames.canonical(name)
        if (spoken.isBlank()) return Reply("Назовите приложение.")
        val exact = apps.filter { AppNames.canonical(it.loadLabel(context.packageManager).toString()) == spoken }
        val candidates = (if (exact.isNotEmpty()) exact else apps.filter { AppNames.canonical(it.loadLabel(context.packageManager).toString()).contains(spoken) }).distinctBy { it.activityInfo.packageName }
        if (candidates.isEmpty()) return Reply("Не нашёл приложение $name.")
        if (candidates.size > 5) return Reply("Нашёл слишком много приложений. Назовите приложение точнее.")
        if (candidates.size > 1) {
            val choices = candidates.mapNotNull { app -> runCatching {
                val info = context.packageManager.getPackageInfo(app.activityInfo.packageName, 0)
                AppChoice(app.activityInfo.packageName, app.loadLabel(context.packageManager).toString(), info.longVersionCode, info.firstInstallTime, info.lastUpdateTime)
            }.getOrNull() }.sortedWith(compareBy<AppChoice> { AppNames.canonical(it.label) }.thenBy { it.pkg })
            if (choices.size != candidates.size) return Reply("Список приложений изменился. Повторите команду открытия.")
            pending = Pending(SystemClock.elapsedRealtime() + 60_000, apps = choices)
            return Reply("Какое приложение открыть? " + choices.mapIndexed { index, app -> "${index + 1}: ${app.label}." }.joinToString(" ") +
                " Скажите первый, второй или отмена.", appSelection = true)
        }
        val app = candidates.single()
        val intent = context.packageManager.getLaunchIntentForPackage(app.activityInfo.packageName) ?: return Reply("Приложение нельзя открыть.")
        val reply = launch(intent, "Открываю ${app.loadLabel(context.packageManager)}.")
        return if (reply.afterSpeech != null) reply.copy(openedPackage = app.activityInfo.packageName) else reply
    }
    private fun appUnchanged(app: AppChoice): Boolean = runCatching {
            val info = context.packageManager.getPackageInfo(app.pkg, 0)
            info.longVersionCode == app.version && info.firstInstallTime == app.installed && info.lastUpdateTime == app.updated &&
                context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .any { it.activityInfo.packageName == app.pkg && it.loadLabel(context.packageManager).toString() == app.label }
        }.getOrDefault(false)
    private fun launchChoice(app: AppChoice): Reply {
        val changed = "Список приложений изменился. Повторите команду открытия."
        if (!appUnchanged(app)) return Reply(changed)
        val intent = context.packageManager.getLaunchIntentForPackage(app.pkg) ?: return Reply("Приложение нельзя открыть.")
        val reply = launch(intent, "Открываю ${app.label}.")
        return if (reply.afterSpeech != null) reply.copy(openedPackage = app.pkg,
            beforeOpening = { if (appUnchanged(app)) null else changed },
            afterSpeech = { if (!appUnchanged(app)) changed else reply.afterSpeech.invoke() }) else reply
    }
    private fun tap(label: String): Reply {
        if (locked()) return Reply("Телефон заблокирован.")
        val service = PhoneAccessibility.connected ?: return Reply("Включите доступ к экрану в настройках помощника.")
        val target = service.target(label) ?: return Reply("Не нашёл единственную кнопку с точным названием $label. Скажите прочитай экран.")
        pending = Pending(SystemClock.elapsedRealtime() + 30_000, action = {
            val success = !locked() && PhoneAccessibility.connected?.tap(target) == true
            Reply(if (success) "Команда нажатия принята приложением." else "Экран изменился, нажатие отменено.", actionAccepted = success)
        })
        return Reply("Нажать кнопку $label? Скажите подтверждаю или отмена.", actionPending = true)
    }
    private fun write(text: String): Reply {
        if (locked()) return Reply("Для ввода текста разблокируйте телефон.")
        if (text.isBlank() || text.length > 1000) return Reply("Текст должен содержать от одного до тысячи символов.")
        val service = PhoneAccessibility.connected ?: return Reply("Включите доступ к экрану в настройках помощника.")
        val target = runCatching { service.fieldTarget() }.getOrNull() ?: return Reply("Не нашёл однозначное поле ввода. Откройте нужное поле или скажите прочитай экран.")
        val offeredGeneration = generation
        pending = Pending(SystemClock.elapsedRealtime() + 30_000, action = {
            Reply("Проверяю ввод текста…", verify = { done ->
                if (locked()) done("Телефон заблокировался. Ввод отменён.")
                else runCatching { PhoneAccessibility.connected?.write(target, text, { generation == offeredGeneration && !locked() }, done) ?: done("Доступ к экрану отключился. Ввод отменён.") }
                    .onFailure { done("Не удалось выполнить ввод текста. Доступ к экрану мог измениться.") }
            })
        })
        return Reply("Заменить текст в поле ${target.label} на: $text? Скажите подтверждаю или отмена.", actionPending = true)
    }
    private fun call(value: String, name: String? = null): Reply {
        val number = VoiceCommands.phoneNumber(value) ?: return Reply("Назовите номер отдельными цифрами или точное имя контакта.")
        if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return Reply("Сначала разрешите звонки в настройках помощника.")
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return Reply("Системная телефония недоступна.")
        pending = Pending(SystemClock.elapsedRealtime() + 30_000, requiresUnlock = false, action = {
            Reply("Передаю звонок системе. Завершаю разговор с помощником.", afterSpeech = {
                runCatching { InteractionJournal.action("phone_call", InteractionJournal.data("number" to number)) { telecom.placeCall(Uri.fromParts("tel", number, null), Bundle()) }; null }
                    .getOrElse { "Система не разрешила звонок. Проверьте SIM-карту и разрешение на звонки." }
            }, handoff = true)
        })
        return Reply("Позвонить ${name?.let { "контакту $it " } ?: ""}на номер ${number.toCharArray().joinToString(" ")}? Скажите подтверждаю или отмена.")
    }
    private fun launch(intent: Intent, text: String, handoff: Boolean = false): Reply {
        if (intent.resolveActivity(context.packageManager) == null) return Reply("Нет приложения для этого действия.")
        return Reply(text, afterSpeech = {
            if (locked()) "Телефон заблокировался. Действие отменено."
            else runCatching { InteractionJournal.action("launch_intent", InteractionJournal.data("action" to intent.action, "package" to intent.`package`, "data" to intent.dataString)) { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }; null }
                .getOrElse { "Android не разрешил открыть приложение. Действие не выполнено." }
        }, handoff = handoff)
    }
}
