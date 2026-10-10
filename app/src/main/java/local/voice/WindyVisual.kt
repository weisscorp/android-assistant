package local.voice

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Uses the common screen module; this adapter never assigns an unlabeled number to a metric. */
internal object WindyVisual {
    internal fun openCard(ui: PhoneAccessibility, frame: PhoneAccessibility.ForegroundSnapshot): Boolean {
        val root = ui.textTree(frame.app) ?: return false
        val card = WindyForecast.card(root) ?: return false
        val element = ui.elements().singleOrNull { it.clickable && it.bounds == card.bounds } ?: return false
        return ui.tapElement(element, frame)
    }
    fun read(context: Context, request: WeatherRequest, active: () -> Boolean, done: (String) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val ui = PhoneAccessibility.connected
        val frame = ui?.foregroundSnapshot()
        if (frame?.app != WindyForecast.PACKAGE || !openCard(ui!!, frame)) {
            done("Не удалось открыть таблицу для чтения снимка."); return
        }
        val until = SystemClock.elapsedRealtime() + 35_000
        val facts = mutableListOf<ScreenFacts.Fact>()
        var captures = 0
        fun finish() {
            val selected = ScreenFacts.relevant(facts.distinct(), request)
            val unambiguous = selected.groupBy { it.field }.values.mapNotNull { it.singleOrNull() }
            if (unambiguous.isEmpty()) done("По снимкам графика не удалось однозначно связать нужные числа с подписями и временем. Значения не подставляю. Команда прочитай экран озвучит распознанный текст.")
            else done("На снимке видны строки: " + unambiguous.joinToString("; ") { "${it.caption}: ${it.value}" } + ". Время этих значений не подтверждено, поэтому это не ответ о погоде прямо сейчас.")
        }
        lateinit var capture: () -> Unit
        capture = {
            if (active()) {
                val current = PhoneAccessibility.connected?.foregroundSnapshot()
                if (current?.app != WindyForecast.PACKAGE || SystemClock.elapsedRealtime() >= until) finish()
                else AppScreenReader.read(context, current, active) { result, _ ->
                    if (active()) {
                        if (result != null) facts += ScreenFacts.read(result.words)
                        captures++
                        if (captures >= 3 || ScreenFacts.relevant(facts, request).isNotEmpty()) finish()
                        else if (PhoneAccessibility.connected?.foregroundPackage() == WindyForecast.PACKAGE && PhoneAccessibility.connected?.scroll(true) == true)
                            main.postDelayed({ capture() }, 700)
                        else finish()
                    }
                }
            }
        }
        val readyUntil = SystemClock.elapsedRealtime() + 15_000
        val wait = object : Runnable {
            override fun run() {
                if (!active()) return
                val service = PhoneAccessibility.connected
                val current = service?.foregroundSnapshot()
                val elements = service?.elements().orEmpty()
                val detail = elements.any { it.id in setOf("${WindyForecast.PACKAGE}:id/item_share", "${WindyForecast.PACKAGE}:id/item_favorite_and_share") }
                val units = elements.any { Regex("^(м/с|m/s|км/ч|km/h|уз|kts)$").matches(it.text.trim()) }
                if (current?.app != WindyForecast.PACKAGE) done("Приложение изменилось. Чтение графика остановлено.")
                else if (detail && units) main.postDelayed({ capture() }, 700)
                else if (SystemClock.elapsedRealtime() >= readyUntil) done("windy.app не показал загруженную таблицу подробностей. Значения не получены.")
                else main.postDelayed(this, 200)
            }
        }
        main.postDelayed(wait, 300)
    }
}
