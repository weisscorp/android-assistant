package local.voice

/** A saved headset is preferred; strict callers can forbid fallback when it is unavailable. */
object HeadsetSelection {
    data class Device(val id: Int, val key: String, val bluetooth: Boolean)
    fun choose(devices: List<Device>, saved: String = "", current: Int? = null, allowFallback: Boolean = false): Device? {
        if (saved.isNotBlank()) {
            val selected = devices.filter { it.key == saved }.singleOrNull()
            if (selected != null || !allowFallback) return selected
        }
        return devices.firstOrNull { it.id == current }
            ?: devices.firstOrNull { it.bluetooth }
            ?: devices.firstOrNull()
    }
}
