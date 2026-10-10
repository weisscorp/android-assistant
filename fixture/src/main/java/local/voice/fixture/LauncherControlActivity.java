package local.voice.fixture;

import android.app.Activity;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.Bundle;

/** Disposable app changes its own launcher; no privileged shell permission needed. */
public class LauncherControlActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String mode = getIntent().getStringExtra("mode");
        if ("telegram".equals(mode) || "restore_telegram".equals(mode)) {
            int field = "telegram".equals(mode) ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED : getIntent().getIntExtra("field_state", 0);
            int telegram = "telegram".equals(mode) ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : getIntent().getIntExtra("telegram_state", 0);
            if (field < 0 || field > 2 || telegram < 0 || telegram > 2) { finish(); return; }
            getPackageManager().setComponentEnabledSetting(new ComponentName(this, FieldActivity.class), field, PackageManager.DONT_KILL_APP);
            getPackageManager().setComponentEnabledSetting(new ComponentName(this, "local.voice.fixture.TelegramLauncher"), telegram, PackageManager.DONT_KILL_APP);
            finish(); return;
        }
        int enabled;
        if ("enable".equals(mode)) enabled = PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        else if ("disable".equals(mode)) enabled = PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        else if ("default".equals(mode)) enabled = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        else { finish(); return; }
        getPackageManager().setComponentEnabledSetting(new ComponentName(this, FieldActivity.class), enabled, PackageManager.DONT_KILL_APP);
        finish();
    }
}
