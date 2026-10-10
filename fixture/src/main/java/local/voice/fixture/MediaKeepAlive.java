package local.voice.fixture;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/** Keeps the synthetic player eligible for background focus on target35+. No audio/network. */
public class MediaKeepAlive extends Service {
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("media-fixture", "Тест медиасервиса", NotificationManager.IMPORTANCE_LOW));
        startForeground(500, new Notification.Builder(this, "media-fixture").setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Синтетический проигрыватель").setOngoing(true).build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
