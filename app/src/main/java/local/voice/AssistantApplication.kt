package local.voice

import android.app.Activity
import android.app.Application
import android.os.Bundle

/** Presence means an open assistant screen or conversation, never inferred GPS position. */
class AssistantApplication : Application(), Application.ActivityLifecycleCallbacks {
    companion object { @Volatile internal var foreground = false; private set }
    private var resumed = 0
    override fun onCreate() {
        super.onCreate(); registerActivityLifecycleCallbacks(this)
        OtaClient.consumeBootstrap(this)
        runCatching { AutoUpdates.schedule(this) }
    }
    override fun onActivityResumed(activity: Activity) {
        resumed++; foreground = true
        // A development administrator can provision while an assistant service
        // already keeps the process alive; an ordinary screen open consumes it.
        OtaClient.consumeBootstrap(this)
    }
    override fun onActivityPaused(activity: Activity) {
        resumed = maxOf(0, resumed - 1); foreground = resumed > 0
        if (!foreground) runCatching { AutoUpdates.schedule(this, immediate = true) }
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
