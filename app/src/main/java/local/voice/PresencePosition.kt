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

/** A single foreground fix for distance, no background permission or location service. */
internal object PresencePosition {
    fun get(context: Context, allowed: () -> Boolean, done: (GeoFix?) -> Unit) {
        if (!allowed()) return
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) { done(null); return }
        val manager = context.getSystemService(LocationManager::class.java)
        fun fresh(loc: Location) = SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos in 0..60_000_000_000L && loc.hasAccuracy() && loc.accuracy <= 1000
        fun fix(loc: Location) = GeoFix(loc.latitude, loc.longitude,
            System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1000000, loc.accuracy.toDouble())
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        val cached = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.filter(::fresh).minByOrNull { it.accuracy }
        if (cached != null) { done(fix(cached)); return }
        val signals = providers.map { CancellationSignal() }
        val main = Handler(Looper.getMainLooper()); var finished = false
        fun finish(loc: Location?) { if (finished) return; finished = true; signals.forEach(CancellationSignal::cancel); if (allowed()) done(loc?.let(::fix)) }
        providers.forEachIndexed { i, provider -> runCatching { manager.getCurrentLocation(provider, signals[i], context.mainExecutor) { loc -> if (loc != null && fresh(loc)) finish(loc) } } }
        val until = SystemClock.elapsedRealtime() + 5000
        val poll = object : Runnable {
            override fun run() {
                if (finished) return
                if (!allowed()) { finished = true; signals.forEach(CancellationSignal::cancel) }
                else if (SystemClock.elapsedRealtime() >= until || providers.isEmpty()) finish(null)
                else main.postDelayed(this, 100)
            }
        }; main.post(poll)
    }
}
