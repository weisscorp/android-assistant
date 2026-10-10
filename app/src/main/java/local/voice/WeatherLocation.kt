package local.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Locale

/** Uses Android's providers directly; no Google SDK and no ongoing tracking. */
internal object WeatherLocation {
    data class Point(val latitude: Double, val longitude: Double) {
        val query: String get() = String.format(Locale.US, "%.5f, %.5f", latitude, longitude)
    }
    fun get(context: Context, allowed: () -> Boolean, done: (Point?, String?) -> Unit) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            done(null, "Разрешите местоположение для погоды в настройках помощника."); return
        }
        val manager = context.getSystemService(LocationManager::class.java)
        if (!manager.isLocationEnabled) { done(null, "Включите местоположение на телефоне для прогноза рядом с вами."); return }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        fun fresh(location: Location) = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos in 0..120_000_000_000L && location.hasAccuracy() && location.accuracy <= 1000f
        val last = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.filter(::fresh).maxByOrNull { it.elapsedRealtimeNanos }
        if (last != null) { done(Point(last.latitude, last.longitude), null); return }
        val main = Handler(Looper.getMainLooper())
        val signals = providers.map { CancellationSignal() }
        var complete = false
        fun finish(location: Location?, error: String?) {
            if (complete) return
            complete = true; signals.forEach { it.cancel() }
            if (allowed()) done(location?.let { Point(it.latitude, it.longitude) }, error)
        }
        providers.forEachIndexed { i, provider -> runCatching {
            manager.getCurrentLocation(provider, signals[i], context.mainExecutor) { location ->
                if (location != null && fresh(location)) finish(location, null)
            }
        } }
        val until = SystemClock.elapsedRealtime() + 15_000
        val poll = object : Runnable {
            override fun run() {
                if (complete) return
                if (!allowed()) { complete = true; signals.forEach { it.cancel() }; return }
                if (SystemClock.elapsedRealtime() >= until) finish(null, "Не удалось получить свежее местоположение. Повторите запрос или назовите город.")
                else main.postDelayed(this, 150)
            }
        }
        main.post(poll)
    }
}
