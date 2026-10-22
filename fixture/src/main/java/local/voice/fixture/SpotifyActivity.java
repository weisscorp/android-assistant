package local.voice.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

/** Synthetic launcher only; no Spotify account, network or audio playback. */
public class SpotifyActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView label = new TextView(this);
        label.setText("Spotify launcher fixture");
        label.setTextSize(24);
        setContentView(label);
    }
}
