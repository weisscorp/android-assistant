package local.voice

/** Reobserve only before execution, in the already pinned app, with a task-wide budget. */
internal object ScreenReplan {
    fun allowed(pinned: String?, observed: String?, current: String?, attempts: Int): Boolean =
        pinned != null && observed == pinned && current == pinned && attempts in 0..1
}
