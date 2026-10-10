package local.voice.fixture;

import android.app.Activity;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.AudioManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.os.Handler;
import android.os.Looper;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.widget.TextView;

/** Synthetic transport target; does not play sound or access a network. */
public class MediaActivity extends Activity {
    private static MediaSession session, other;
    private static long index;
    private static AudioManager audio;
    private static AudioFocusRequest focus;
    private static boolean resumeAfterFocus;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (audio != null && focus != null) { audio.abandonAudioFocusRequest(focus); focus = null; }
        resumeAfterFocus = false;
        if (session != null) { session.release(); session = null; }
        if (other != null) { other.release(); other = null; }
        String mode = getIntent().getStringExtra("mode");
        if ("clear".equals(mode)) stopService(new Intent(this, MediaKeepAlive.class));
        if (!"clear".equals(mode)) {
            index = 1;
            session = new MediaSession(this, "helmet-media-fixture");
            final boolean withFocus = "focus".equals(mode);
            final boolean ignore = ("ignore".equals(mode) || "paused-ignore".equals(mode));
            final long actions = "unsupported".equals(mode) ? PlaybackState.ACTION_PLAY :
                PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
            if (withFocus) {
                startForegroundService(new Intent(this, MediaKeepAlive.class));
                audio = getApplicationContext().getSystemService(AudioManager.class);
                final MediaSession owner = session;
                focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener(change -> {
                        if (session != owner) return;
                        if (change == AudioManager.AUDIOFOCUS_GAIN && resumeAfterFocus) update(PlaybackState.STATE_PLAYING, actions);
                        else if (change < 0) {
                            resumeAfterFocus = change != AudioManager.AUDIOFOCUS_LOSS && session.getController().getPlaybackState().getState() == PlaybackState.STATE_PLAYING;
                            update(PlaybackState.STATE_PAUSED, actions);
                        }
                    }, new Handler(Looper.getMainLooper())).build();
            }
            session.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() {
                    Bundle report = new Bundle(); report.putInt("playAudioMode", getSystemService(AudioManager.class).getMode());
                    session.setExtras(report);
                    if (!ignore) {
                        resumeAfterFocus = false;
                        if (!withFocus || audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) update(PlaybackState.STATE_PLAYING, actions);
                    }
                }
                @Override public void onPause() { if (!ignore) { resumeAfterFocus = false; update(PlaybackState.STATE_PAUSED, actions); } }
                @Override public void onSkipToNext() { if (!ignore) { index++; update(PlaybackState.STATE_PLAYING, actions); } }
                @Override public void onSkipToPrevious() { if (!ignore) { index--; update(PlaybackState.STATE_PLAYING, actions); } }
            });
            update(!"paused-ignore".equals(mode) && (!withFocus || audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, actions);
            session.setActive(true);
            if ("multiple".equals(mode)) {
                other = new MediaSession(this, "helmet-media-other");
                other.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1).setActions(actions).build());
                other.setActive(true);
            }
        }
        TextView text = new TextView(this); text.setText("Синтетический проигрыватель: " + mode); setContentView(text); finish();
    }
    private static void update(int state, long actions) {
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "fixture-" + index)
            .putString(MediaMetadata.METADATA_KEY_TITLE, "Тестовый трек " + index).build());
        session.setPlaybackState(new PlaybackState.Builder().setState(state, 0, state == PlaybackState.STATE_PLAYING ? 1 : 0)
            .setActions(actions).setActiveQueueItemId(index).build());
    }
}
