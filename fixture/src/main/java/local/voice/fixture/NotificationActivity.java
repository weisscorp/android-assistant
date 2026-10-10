package local.voice.fixture;
import android.app.*;
import android.os.Bundle;
/** Disposable synthetic notifications in another app; never contacts anyone. */
public class NotificationActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if ("clear".equals(getIntent().getStringExtra("mode"))) { manager.cancelAll(); finish(); return; }
        manager.createNotificationChannel(new NotificationChannel("fixture", "Тест уведомлений", NotificationManager.IMPORTANCE_DEFAULT));
        String mode = getIntent().getStringExtra("mode");
        if ("private-long".equals(mode) || "public-long".equals(mode)) {
            manager.cancelAll();
            post(manager, 106, "Это синтетическое сообщение для проверки голосового чтения. ".repeat(8),
                "private-long".equals(mode) ? Notification.VISIBILITY_PRIVATE : Notification.VISIBILITY_PUBLIC, false);
            finish(); return;
        }
        post(manager, 101, "PRIVATE_FIXTURE", Notification.VISIBILITY_PRIVATE, false);
        post(manager, 102, "PUBLIC_FIXTURE", Notification.VISIBILITY_PUBLIC, false);
        post(manager, 103, "SECRET_FIXTURE", Notification.VISIBILITY_SECRET, false);
        post(manager, 104, "ONGOING_FIXTURE", Notification.VISIBILITY_PUBLIC, true);
        Person sender = new Person.Builder().setName("Синтетический отправитель").build();
        Notification.MessagingStyle style = new Notification.MessagingStyle(new Person.Builder().setName("Тест").build())
            .addMessage("OLD_MESSAGE_FIXTURE", 1, sender).addMessage("LATEST_MESSAGE_FIXTURE", 2, sender);
        manager.notify(105, new Notification.Builder(this, "fixture").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setStyle(style).build());
        finish();
    }
    private void post(NotificationManager manager, int id, String text, int visibility, boolean ongoing) {
        manager.notify(id, new Notification.Builder(this, "fixture").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Тестовый заголовок").setContentText(text).setVisibility(visibility).setOngoing(ongoing).build());
    }
}
