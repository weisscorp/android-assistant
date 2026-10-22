package local.voice.fixture;

import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.media.MediaMetadata;
import android.os.Bundle;
import android.service.media.MediaBrowserService;
import java.util.Collections;

/** Silent disposable player for locked background resumption tests. */
public class BrowserPlayerService extends MediaBrowserService {
    private MediaSession session;
    private void update(String artist) {
        startForegroundService(new android.content.Intent(this, BrowserPlayerService.class));
        android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
        manager.createNotificationChannel(new android.app.NotificationChannel("browser-fixture", "Silent browser fixture", android.app.NotificationManager.IMPORTANCE_LOW));
        startForeground(77, new android.app.Notification.Builder(this, "browser-fixture")
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Silent browser fixture").build());
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_ARTIST, artist).build());
        session.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1)
            .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PLAY_FROM_SEARCH).build());
    }
    @Override public void onCreate() {
        super.onCreate();
        session = new MediaSession(this, "cold-silent-fixture");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { update("Fixture Artist"); }
            @Override public void onPlayFromSearch(String query, Bundle extras) { update(query); }
        });
        session.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0, 0)
            .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PLAY_FROM_SEARCH).build());
        session.setActive(true); setSessionToken(session.getSessionToken());
    }
    @Override public BrowserRoot onGetRoot(String pkg, int uid, Bundle extras) { return new BrowserRoot("fixture", null); }
    @Override public void onLoadChildren(String id, Result<java.util.List<MediaBrowser.MediaItem>> result) { result.sendResult(Collections.emptyList()); }
    @Override public void onDestroy() { session.release(); super.onDestroy(); }
}
