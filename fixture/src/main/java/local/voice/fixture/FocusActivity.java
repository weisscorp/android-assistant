package local.voice.fixture;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Bundle;

/** Transient competing audio owner. No call is made and no microphone is opened. */
public class FocusActivity extends Activity {
    private static AudioManager audio;
    private static AudioFocusRequest focus;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (audio != null && focus != null) { audio.abandonAudioFocusRequest(focus); focus = null; }
        if (!"release".equals(getIntent().getStringExtra("mode"))) {
            audio = getApplicationContext().getSystemService(AudioManager.class);
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setOnAudioFocusChangeListener(change -> {}).build();
            audio.requestAudioFocus(focus);
        }
        finish();
    }
}
