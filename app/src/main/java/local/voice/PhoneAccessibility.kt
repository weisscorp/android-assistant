package local.voice

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.security.MessageDigest
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class PhoneAccessibility : AccessibilityService() {
    companion object { var connected: PhoneAccessibility? = null; private set }
    override fun onServiceConnected() { connected = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { if (connected === this) connected = null; super.onDestroy() }
    private fun root(): AccessibilityNodeInfo? {
        if (getSystemService(KeyguardManager::class.java).isDeviceLocked) return null
        // Confirmation must bind to current UI, not a cached node from a prior window.
        clearCache()
        val currentWindows = windows
        // The expanded shade/quick settings are system windows, not application
        // windows. A focused active SystemUI surface is controllable too; never
        // select a passive navigation bar or keyboard as the current screen.
        val system = currentWindows.filter { it.type == AccessibilityWindowInfo.TYPE_SYSTEM && it.isActive && it.isFocused }
            .mapNotNull { it.root?.takeIf { node -> node.packageName?.toString() == "com.android.systemui" } }
        val applications = currentWindows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root?.takeIf { node -> node.packageName?.toString() != packageName } }
        return system.singleOrNull() ?: applications.firstOrNull { it.window?.isActive == true }
            ?: applications.singleOrNull()
    }

    data class ForegroundSnapshot(val app: String, val window: Int, val content: String)
    fun foregroundSnapshot(): ForegroundSnapshot? {
        val node = root()?.takeIf { it.window?.isActive == true } ?: return null
        val all = mutableListOf<AccessibilityNodeInfo>().also { visit(node, it) }
        val geometry = all.filter { it.isVisibleToUser && !it.isPassword }.joinToString("\n") {
            val bounds = Rect().also(it::getBoundsInScreen).flattenToString()
            "${it.viewIdResourceName}:$bounds:${it.isFocused}:${it.isEnabled}"
        }
        return ForegroundSnapshot(node.packageName.toString(), node.windowId, digest(screenStamp(all, includeEditable = true) + geometry))
    }
    fun foregroundPackage(): String? = root()?.takeIf { it.window?.isActive == true }?.packageName?.toString()

    /** Immutable observations; no live nodes survive a spoken confirmation. */
    data class Element(val app: String, val window: Int, val id: String?, val kind: String,
        val bounds: String, val text: String, val description: String, val hint: String,
        val editable: Boolean, val clickable: Boolean) {
        val label: String get() = description.ifBlank { text }
    }
    private fun element(node: AccessibilityNodeInfo) = Element(node.packageName.toString(), node.windowId,
        node.viewIdResourceName, node.className?.toString().orEmpty(), Rect().also(node::getBoundsInScreen).flattenToString(),
        node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty(), node.hintText?.toString().orEmpty(),
        editable(node), node.isEnabled && (node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }))
    fun elements(): List<Element> = nodes().filter { it.isVisibleToUser && !it.isPassword }.map(::element)
    internal fun textTree(expectedPackage: String): ScreenTextNode? {
        val node = root()?.takeIf { it.window?.isActive == true && it.packageName?.toString() == expectedPackage } ?: return null
        var count = 0
        fun copy(n: AccessibilityNodeInfo, depth: Int): ScreenTextNode? {
            if (depth > 20 || count++ >= 300 || !n.isVisibleToUser || n.isPassword || n.isEditable) return null
            return ScreenTextNode(n.text?.toString().orEmpty(), n.contentDescription?.toString().orEmpty(),
                n.viewIdResourceName.orEmpty(), n.isEnabled && n.isClickable,
                (0 until n.childCount).mapNotNull { i -> n.getChild(i)?.let { copy(it, depth + 1) } },
                Rect().also(n::getBoundsInScreen).flattenToString())
        }
        return copy(node, 0)
    }
    internal fun goBack(expected: ForegroundSnapshot): Boolean = InteractionJournal.action("screen_back", InteractionJournal.data("frame" to expected.toString())) {
        foregroundSnapshot() == expected && performGlobalAction(GLOBAL_ACTION_BACK)
    }
    internal fun screenshot(expected: ForegroundSnapshot, done: (android.graphics.Bitmap?, String?) -> Unit) {
        if (foregroundSnapshot() != expected) { done(null, "Экран изменился."); return }
        val area = Rect().also { root()?.window?.getBoundsInScreen(it) }
        if (area.isEmpty) { done(null, "Окно приложения недоступно."); return }
        try { takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                try {
                    val now = foregroundSnapshot()
                    val currentArea = Rect().also { root()?.window?.getBoundsInScreen(it) }
                    if (now?.app != expected.app || now.window != expected.window || currentArea != area) {
                        done(null, "Приложение изменилось во время снимка."); return
                    }
                    val hardware = android.graphics.Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    val copy = hardware?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    hardware?.recycle()
                    if (copy == null || !area.intersect(0, 0, copy.width, copy.height)) {
                        copy?.recycle(); done(null, "Не удалось получить изображение окна."); return
                    }
                    val image = android.graphics.Bitmap.createBitmap(copy, area.left, area.top, area.width(), area.height())
                    if (image !== copy) copy.recycle()
                    done(image, null)
                } catch (_: Exception) { done(null, "Не удалось прочитать снимок экрана.") }
                finally { buffer.close() }
            }
            override fun onFailure(errorCode: Int) { done(null, "Android не разрешил снимок этого экрана (код $errorCode). Защищённые окна прочитать нельзя.") }
        }) } catch (_: Exception) { done(null, "Android не разрешил снимок этого экрана.") }
    }
    fun tapElement(expected: Element, frame: ForegroundSnapshot): Boolean = InteractionJournal.action("screen_tap_element", InteractionJournal.data("target" to expected.toString(), "frame" to frame.toString())) { tapElementInternal(expected, frame) }
    private fun tapElementInternal(expected: Element, frame: ForegroundSnapshot): Boolean {
        if (foregroundSnapshot() != frame) return false
        var node = nodes().filter { it.isVisibleToUser && !it.isPassword && element(it) == expected }.singleOrNull() ?: return false
        repeat(4) {
            if (node.isEnabled && (node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node = node.parent ?: return false
        }
        return false
    }

    private fun visit(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>, depth: Int = 0) {
        if (depth > 20 || result.size >= 300) return
        result.add(node)
        for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, result, depth + 1) }
    }
    private fun nodes(): List<AccessibilityNodeInfo> = root()?.let { node -> mutableListOf<AccessibilityNodeInfo>().also { visit(node, it) } } ?: emptyList()
    fun readScreen(): String {
        val text = nodes().filter { it.isVisibleToUser && !it.isPassword }.mapNotNull {
            (it.text ?: it.contentDescription)?.toString()?.trim()?.takeIf(String::isNotEmpty)
        }.distinct().take(35).joinToString(". ").take(2000)
        return text.ifBlank { "Не могу прочитать экран. Телефон может быть заблокирован или приложение не предоставляет элементы." }
    }
    fun target(label: String): Target? {
        if (label.isBlank() || label.length > 120) return null
        val all = nodes()
        val candidates = all.filter { it.isVisibleToUser && !it.isPassword && (it.text?.toString()?.equals(label, true) == true || it.contentDescription?.toString()?.equals(label, true) == true) }
        if (candidates.size != 1) return null
        var clickable = candidates.single()
        repeat(4) {
            if (clickable.isClickable && clickable.isEnabled) return Target(clickable.packageName.toString(), label, clickable.viewIdResourceName, clickable.windowId,
                Rect().also(clickable::getBoundsInScreen).flattenToString(), screenStamp(all, includeEditable = true))
            clickable = clickable.parent ?: return null
        }
        return null
    }
    data class Target(val app: String, val label: String, val viewId: String?, val window: Int, val bounds: String, val screen: String)
    fun tap(expected: Target): Boolean = InteractionJournal.action("screen_tap", InteractionJournal.data("target" to expected.toString())) { tapInternal(expected) }
    private fun tapInternal(expected: Target): Boolean {
        val fresh = target(expected.label) ?: return false
        if (fresh != expected) return false
        var node = nodes().singleOrNull { it.isVisibleToUser && !it.isPassword && (it.text?.toString()?.equals(expected.label, true) == true || it.contentDescription?.toString()?.equals(expected.label, true) == true) } ?: return false
        repeat(4) {
            if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node = node.parent ?: return false
        }
        return false
    }
    data class FieldTarget(val app: String, val window: Int, val viewId: String?, val bounds: String,
        val hint: String, val kind: String, val original: String, val screen: String, val label: String)
    private fun digest(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun editable(node: AccessibilityNodeInfo) = node.isVisibleToUser && node.isEnabled && node.isEditable && !node.isPassword &&
        node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
    private fun screenStamp(all: List<AccessibilityNodeInfo>, includeEditable: Boolean = false, excluded: AccessibilityNodeInfo? = null) = digest(all.filter { it != excluded && it.isVisibleToUser && !it.isPassword && (includeEditable || !it.isEditable) }
        .joinToString("\n") { "${it.viewIdResourceName}:${it.className}:${it.hintText}:${it.isEditable}:${it.isEnabled}:${it.text}:${it.contentDescription}" })
    private fun identity(node: AccessibilityNodeInfo, all: List<AccessibilityNodeInfo>): FieldTarget {
        val bounds = Rect().also(node::getBoundsInScreen).flattenToString()
        val hint = (node.hintText ?: node.contentDescription)?.toString().orEmpty()
        return FieldTarget(node.packageName.toString(), node.windowId, node.viewIdResourceName, bounds,
            hint, node.className?.toString().orEmpty(), digest(node.text?.toString().orEmpty()), screenStamp(all, includeEditable = true, excluded = node), hint.take(100).ifBlank { "поле ввода" })
    }
    /** Prefer the input-focused editable field; otherwise require one unique field. */
    fun fieldTarget(): FieldTarget? {
        val all = nodes()
        val fields = all.filter(::editable)
        val selected = fields.filter { it.isFocused }.singleOrNull() ?: fields.singleOrNull() ?: return null
        return identity(selected, all)
    }
    // Used only after matching the action node's source/window identity by equals().
    // A resource ID is not necessary to verify a field that reflows after writing.
    private fun sameField(expected: FieldTarget, actual: FieldTarget) = expected.copy(original = actual.original, bounds = actual.bounds) == actual
    fun write(expected: FieldTarget, text: String, allowed: () -> Boolean = { true }, done: (String) -> Unit) {
        writeInternal(expected, text, allowed, "strict", done)
    }
    /** Search results may change after entering a query; message writing stays strict. */
    internal fun writeSearch(expected: FieldTarget, text: String, allowed: () -> Boolean, done: (String) -> Unit) {
        if (expected.hint.trim().lowercase() !in setOf("поиск", "search")) { done("Поле поиска изменилось."); return }
        writeInternal(expected, text, allowed, "search", done)
    }
    /** Caller must keep the selected chat recipient verified. Composer controls may change after typing. */
    internal fun writeChat(expected: FieldTarget, text: String, allowed: () -> Boolean, done: (String) -> Unit) {
        if (expected.hint.trim().lowercase() !in setOf("сообщение", "message")) { done("Поле сообщения изменилось."); return }
        writeInternal(expected, text, allowed, "chat", done)
    }
    private fun writeInternal(expected: FieldTarget, text: String, allowed: () -> Boolean, mode: String, originalDone: (String) -> Unit) {
        val trace = InteractionJournal.current?.begin("action", InteractionJournal.data("name" to "screen_write", "text" to text, "mode" to mode, "target" to expected.toString()))
        val done: (String) -> Unit = { result -> trace?.result(InteractionJournal.data("text" to result)); originalDone(result) }
        if (text.isBlank() || text.length > 1000) { done("Текст должен содержать от одного до тысячи символов."); return }
        val handler = Handler(Looper.getMainLooper())
        val until = android.os.SystemClock.elapsedRealtime() + 700
        var consecutive = 0
        val poll = object : Runnable {
            override fun run() {
                if (connected !== this@PhoneAccessibility || !allowed()) { done("Ввод отменён."); return }
                val all = nodes()
                val node = all.filter(::editable).singleOrNull { identity(it, all) == expected }
                consecutive = if (node != null) consecutive + 1 else 0
                // Require the complete original identity twice; never substitute a changed field or recipient.
                if (node == null || consecutive < 2) {
                    if (android.os.SystemClock.elapsedRealtime() >= until) done("Поле или экран изменились. Ввод отменён.")
                    else handler.postDelayed(this, 100)
                    return
                }
                val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
                val otherFields = screenStamp(all.filter(::editable), includeEditable = true, excluded = node)
                if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                    done("Приложение отклонило ввод текста."); return
                }
                // Action acknowledgement is followed by actual text readback, never an invented success.
                handler.postDelayed({
                    val verified = runCatching {
                        val fresh = nodes()
                        val changed = fresh.filter(::editable).singleOrNull {
                            val actual = identity(it, fresh)
                            val contextChangedSafely = mode == "search" || (mode == "chat" &&
                                otherFields == screenStamp(fresh.filter(::editable), includeEditable = true, excluded = it))
                            it == node && sameField(if (contextChangedSafely) expected.copy(screen = actual.screen) else expected, actual)
                        }
                        allowed() && connected === this@PhoneAccessibility && changed?.refresh() == true && changed.text?.toString() == text
                    }.getOrDefault(false)
                    done(if (verified) "Текст записан в поле. Отправка не выполнялась."
                        else "Не удалось подтвердить текст в поле. Проверьте результат перед отправкой.")
                }, 350)
            }
        }
        poll.run()
    }
    fun scroll(forward: Boolean): Boolean = InteractionJournal.action("screen_scroll", InteractionJournal.data("forward" to forward)) {
        nodes().firstOrNull { it.isVisibleToUser && it.isScrollable }?.performAction(
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        ) ?: false
    }
}
