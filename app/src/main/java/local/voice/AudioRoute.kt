package local.voice

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

class AudioRoute(private val context: Context, private val requiredKey: String? = null, private val phoneOnly: Boolean = false,
                 private val onHeadsetEnd: (() -> Unit)? = null, private val onLoss: () -> Unit) {
    companion object {
        private val types = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET)
        fun headsets(context: Context) = context.getSystemService(AudioManager::class.java).availableCommunicationDevices.filter { it.type in types }
        fun key(device: AudioDeviceInfo) = "${device.type}:${device.address.ifBlank { device.productName.toString() }}"
    }
    var selectionKey: String? = null
        private set
    private val manager = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var focus: AudioFocusRequest? = null
    private var closed = false
    private var modeOwned = false
    private var deviceOwned = false
    private var listener: AudioManager.OnCommunicationDeviceChangedListener? = null
    private var headsetReceiver: BroadcastReceiver? = null
    private var deadline: Runnable? = null
    fun start(requireHeadset: Boolean, done: (String?) -> Unit) {
        try {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(if (phoneOnly) AudioAttributes.USAGE_ASSISTANT else AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener({ change -> if (change < 0) main.post { if (!closed) onLoss() } }, main).build()
            if (manager.requestAudioFocus(focus!!) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { done("Звуковой канал занят. Повторите после звонка."); return }
            if (phoneOnly) { done(null); return }
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            modeOwned = true
            val devices = headsets(context)
            val saved = requiredKey ?: context.getSharedPreferences("settings", 0).getString("headset_key", "").orEmpty()
            val choice = HeadsetSelection.choose(devices.map { device ->
                HeadsetSelection.Device(device.id, key(device), device.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET))
            }, saved, manager.communicationDevice?.id, allowFallback = !requireHeadset && requiredKey == null)
            val headset = if (phoneOnly) manager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                else devices.firstOrNull { it.id == choice?.id }
            if (headset == null) {
                done(if (phoneOnly) "Динамик телефона недоступен." else if (!requireHeadset && requiredKey == null) null else if (saved.isNotBlank()) "Выбранная гарнитура недоступна. Подключите её или измените выбор в приложении." else "Гарнитура с микрофоном не подключена.")
                return
            }
            selectionKey = if (phoneOnly) null else key(headset)
            if (headset.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                val ended = HeadsetAudioEnd(headset.address)
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (closed || intent.action != BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED) return
                        val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                        val previous = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)
                        if (ended.update(device?.address, state, previous)) (onHeadsetEnd ?: onLoss).invoke()
                    }
                }
                // Bluetooth broadcasts come from a privileged app, not necessarily the
                // system UID. This action is platform-protected; additionally require
                // the sender's Bluetooth permission and the exact selected device.
                context.registerReceiver(receiver, IntentFilter(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED),
                    android.Manifest.permission.BLUETOOTH_CONNECT, main, Context.RECEIVER_EXPORTED)
                headsetReceiver = receiver
            }
            var completed = false
            fun complete(error: String?) {
                if (closed || completed) return
                completed = true
                if (error != null) { listener?.let(manager::removeOnCommunicationDeviceChangedListener); listener = null }
                deadline?.let(main::removeCallbacks); deadline = null
                done(error)
            }
            listener = AudioManager.OnCommunicationDeviceChangedListener { device ->
                if (!closed) {
                    if (!completed && device?.id == headset.id) complete(null)
                    else if (completed && device?.id != headset.id) onLoss()
                }
            }
                .also { manager.addOnCommunicationDeviceChangedListener(context.mainExecutor, it) }
            fun selectRoute() {
                if (closed) return
                try {
                    if (!manager.setCommunicationDevice(headset)) { complete("Не удалось подключить микрофон гарнитуры."); return }
                    deviceOwned = true
                    if (manager.communicationDevice?.id == headset.id) complete(null)
                    if (!completed) {
                        val until = android.os.SystemClock.elapsedRealtime() + 6_000
                        // Some ROMs change the route without delivering the listener callback.
                        deadline = object : Runnable {
                            override fun run() {
                                if (closed || completed) return
                                if (manager.communicationDevice?.id == headset.id) complete(null)
                                else if (android.os.SystemClock.elapsedRealtime() >= until) complete("Гарнитура не включила голосовой канал. Повторите вызов.")
                                else main.postDelayed(this, 100)
                            }
                        }.also { main.postDelayed(it, 100) }
                    }
                } catch (_: Exception) { complete("Не удалось включить голосовой канал. Повторите вызов.") }
            }
            selectRoute()
        } catch (_: SecurityException) { done("Разрешите доступ к Bluetooth в настройках помощника.") }
        catch (_: Exception) { done("Не удалось включить голосовой канал. Повторите вызов.") }
    }
    fun close() {
        if (closed) return
        closed = true
        headsetReceiver?.let { runCatching { context.unregisterReceiver(it) } }; headsetReceiver = null
        runCatching { listener?.let(manager::removeOnCommunicationDeviceChangedListener) }
        deadline?.let(main::removeCallbacks)
        if (deviceOwned) runCatching { manager.clearCommunicationDevice() }
        // MODE_NORMAL removes this process's request; Telecom/other owners retain theirs.
        if (modeOwned) runCatching { manager.mode = AudioManager.MODE_NORMAL }
        focus?.let { owned -> runCatching { manager.abandonAudioFocusRequest(owned) } }
    }
}
