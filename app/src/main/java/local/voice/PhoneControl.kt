package local.voice

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlin.math.roundToInt

/** Public local Android APIs only. No shell, network, guessed taps or microphone capture. */
internal class PhoneControl(private val context: Context) {
    private fun locked() = context.getSystemService(KeyguardManager::class.java).isDeviceLocked
    fun execute(request: PhoneRequest): DeviceTools.Reply {
        if (locked() && request.action == PhoneAction.LOCK) return DeviceTools.Reply("Экран уже заблокирован.")
        if (locked() && request.action !in setOf(PhoneAction.VOLUME, PhoneAction.VOLUME_UP, PhoneAction.VOLUME_DOWN,
                PhoneAction.BRIGHTNESS, PhoneAction.TORCH_ON, PhoneAction.TORCH_OFF))
            return DeviceTools.Reply("Для управления экраном разблокируйте телефон.")
        return when (request.action) {
            PhoneAction.VOLUME_UP, PhoneAction.VOLUME_DOWN -> {
                val audio = context.getSystemService(AudioManager::class.java)
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val current = if (max > 0) audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max else 0
                volume((current + if (request.action == PhoneAction.VOLUME_UP) 10 else -10).coerceIn(0, 100))
            }
            PhoneAction.VOLUME -> volume(request.percent)
            PhoneAction.BRIGHTNESS -> brightness(request.percent)
            PhoneAction.TORCH_ON, PhoneAction.TORCH_OFF -> torch(request.action == PhoneAction.TORCH_ON)
            PhoneAction.SETTINGS -> settings(request.setting ?: PhoneSetting.GENERAL)
            else -> global(request.action)
        }
    }
    private fun global(action: PhoneAction): DeviceTools.Reply {
        val code = when (action) {
            PhoneAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            PhoneAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            PhoneAction.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            PhoneAction.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            PhoneAction.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            PhoneAction.DISMISS_SHADE -> AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
            PhoneAction.LOCK -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            PhoneAction.SCREENSHOT -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            PhoneAction.POWER_MENU -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            else -> return DeviceTools.Reply("Это действие недоступно.")
        }
        val service = PhoneAccessibility.connected ?: return DeviceTools.Reply("Для системных команд включите доступ к экрану в настройках помощника.")
        if (service.systemActions.none { it.id == code }) return DeviceTools.Reply("Android сейчас не предоставляет это системное действие.")
        val text = when (action) {
            PhoneAction.BACK -> "Назад."
            PhoneAction.HOME -> "Перехожу на главный экран."
            PhoneAction.RECENTS -> "Открываю последние приложения."
            PhoneAction.NOTIFICATIONS -> "Открываю уведомления."
            PhoneAction.QUICK_SETTINGS -> "Открываю быстрые настройки."
            PhoneAction.DISMISS_SHADE -> "Закрываю шторку."
            PhoneAction.LOCK -> "Блокирую телефон."
            PhoneAction.SCREENSHOT -> "Запрашиваю снимок экрана."
            PhoneAction.POWER_MENU -> "Открываю меню питания."
            else -> "Выполняю."
        }
        return DeviceTools.Reply(text, releaseScreen = true, afterSpeech = {
            val current = PhoneAccessibility.connected
            if (locked() || current !== service) "Экран заблокирован или доступ отключён. Действие отменено."
            else if (!InteractionJournal.action("phone_global", InteractionJournal.data("action" to action.name)) { service.performGlobalAction(code) }) "Android не принял системное действие."
            else null // Accepted by Android; not a claim of a saved screenshot or completed transition.
        })
    }
    private fun volume(percent: Int?): DeviceTools.Reply {
        if (percent == null || percent !in 0..100) return DeviceTools.Reply(PhoneCommands.prompt(VoiceSlot.VOLUME))
        val audio = context.getSystemService(AudioManager::class.java)
        if (audio.isVolumeFixed) return DeviceTools.Reply("На этом устройстве Android запрещает менять громкость программно.")
        return runCatching {
            val min = audio.getStreamMinVolume(AudioManager.STREAM_MUSIC)
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, min + ((max - min) * percent / 100.0).roundToInt(), 0)
            val actual = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val result = if (max > min) ((actual - min) * 100.0 / (max - min)).roundToInt() else 0
            DeviceTools.Reply("Громкость музыки: $result процентов.")
        }.getOrElse { DeviceTools.Reply("Android не разрешил изменить громкость.") }
    }
    private fun brightness(percent: Int?): DeviceTools.Reply {
        if (percent == null || percent !in 1..100) return DeviceTools.Reply(PhoneCommands.prompt(VoiceSlot.BRIGHTNESS))
        if (!Settings.System.canWrite(context)) return DeviceTools.Reply("Для изменения яркости разрешите изменение системных настроек в разделе Доступы помощника.")
        return runCatching {
            val resolver = context.contentResolver
            val before = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
            val target = (percent * 255.0 / 100).roundToInt().coerceIn(1, 255)
            if (!Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL))
                return@runCatching DeviceTools.Reply("Android не разрешил отключить автоматическую яркость.")
            if (!Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, target)) {
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, before)
                return@runCatching DeviceTools.Reply("Яркость не изменена.")
            }
            val actual = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            DeviceTools.Reply(if (actual == target) "Яркость: $percent процентов. Автоматическая яркость отключена." else "Android не подтвердил новое значение яркости.")
        }.getOrElse { DeviceTools.Reply("Не удалось изменить яркость.") }
    }
    private fun torch(enabled: Boolean): DeviceTools.Reply {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            return DeviceTools.Reply("Для фонарика разрешите доступ к камере в разделе Доступы помощника.")
        val cameras = context.getSystemService(CameraManager::class.java)
        val id = runCatching { cameras.cameraIdList.firstOrNull { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } }.getOrNull()
            ?: return DeviceTools.Reply("Фонарик на этом устройстве недоступен.")
        return DeviceTools.Reply(if (enabled) "Включаю фонарик." else "Выключаю фонарик.", verify = { done ->
            val handler = Handler(Looper.getMainLooper()); var finished = false; var requested = false
            lateinit var callback: CameraManager.TorchCallback
            lateinit var timeout: Runnable
            fun finish(text: String) {
                if (finished) return
                finished = true; handler.removeCallbacks(timeout); cameras.unregisterTorchCallback(callback); done(text)
            }
            callback = object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, on: Boolean) {
                    if (requested && cameraId == id && on == enabled) finish(if (on) "Фонарик включён." else "Фонарик выключен.")
                }
                override fun onTorchModeUnavailable(cameraId: String) { if (requested && cameraId == id) finish("Фонарик сейчас занят другим приложением.") }
            }
            timeout = Runnable { finish("Android не подтвердил переключение фонарика.") }
            try {
                cameras.registerTorchCallback(callback, handler)
                requested = true
                cameras.setTorchMode(id, enabled)
                handler.postDelayed(timeout, 2000)
            } catch (_: Exception) { finish("Android не разрешил переключить фонарик.") }
        })
    }
    private fun settings(setting: PhoneSetting): DeviceTools.Reply {
        val action = when (setting) {
            PhoneSetting.GENERAL -> Settings.ACTION_SETTINGS
            PhoneSetting.WIFI -> Settings.ACTION_WIFI_SETTINGS
            PhoneSetting.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
            PhoneSetting.INTERNET -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            PhoneSetting.LOCATION -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            PhoneSetting.DISPLAY -> Settings.ACTION_DISPLAY_SETTINGS
            PhoneSetting.SOUND -> Settings.ACTION_SOUND_SETTINGS
            PhoneSetting.APPS -> Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS
            PhoneSetting.ACCESSIBILITY -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            PhoneSetting.BATTERY -> Settings.ACTION_BATTERY_SAVER_SETTINGS
        }
        return DeviceTools.Reply("Открываю системные настройки.", releaseScreen = true, afterSpeech = {
            if (locked()) "Сначала разблокируйте телефон."
            else runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); null }
                .getOrElse { "Этот раздел настроек недоступен в прошивке." }
        })
    }
}
