package local.voice

/** Updates stay quiet; only important messages can request a heads-up display. */
internal object HomeNotificationPolicy {
    fun popup(kind: String, priority: String, enabled: Boolean): Boolean = enabled && kind == "message" && priority == "important"
}
