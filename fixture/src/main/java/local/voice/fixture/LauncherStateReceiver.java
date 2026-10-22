package local.voice.fixture;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/** Disposable fixture changes its own launcher without stealing the credential UI. */
public class LauncherStateReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        int state = intent.getIntExtra("state", -1);
        if (state < 0 || state > 2) return;
        context.getPackageManager().setComponentEnabledSetting(
            new ComponentName(context, FieldActivity.class), state, PackageManager.DONT_KILL_APP);
    }
}
