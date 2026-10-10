package local.voice.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;

/** External, disposable UI fixture. Never included in the assistant APK. */
public class FieldActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String mode = getIntent().getStringExtra("mode");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(30, 90, 30, 30);
        box.setFocusableInTouchMode(true);
        TextView heading = new TextView(this); heading.setText("Адресат: Тестовый контакт"); box.addView(heading);
        EditText message = new EditText(this) {
            @Override public boolean performAccessibilityAction(int action, Bundle args) {
                if ("reject".equals(mode) && action == AccessibilityNodeInfo.ACTION_SET_TEXT) return true;
                if ("replace".equals(mode) && action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                    EditText replacement = new EditText(FieldActivity.this);
                    replacement.setId(R.id.message); replacement.setHint("Сообщение"); replacement.setShowSoftInputOnFocus(false);
                    replacement.setText(args.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE));
                    int index = box.indexOfChild(this); box.removeView(this); box.addView(replacement, index); replacement.requestFocus();
                    return true;
                }
                return super.performAccessibilityAction(action, args);
            }
        };
        message.setId(R.id.message); message.setHint("Сообщение"); message.setText("Черновик"); message.setShowSoftInputOnFocus(false);
        if ("noid".equals(mode)) message.setId(android.view.View.NO_ID);
        if ("password".equals(mode)) message.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(message);
        final EditText[] toField = new EditText[1];
        if ("multiple".equals(mode) || "focused".equals(mode) || "editable_recipient".equals(mode)) {
            EditText other = new EditText(this); other.setId(R.id.other); other.setHint("Другое поле"); other.setShowSoftInputOnFocus(false);
            if ("editable_recipient".equals(mode)) { other.setHint("Кому"); other.setText("Тестовый контакт"); toField[0] = other; }
            box.addView(other);
        }
        Button change = new Button(this); change.setText("Изменить черновик"); change.setOnClickListener(v -> message.setText("Изменено другим пользователем")); box.addView(change);
        Button recipient = new Button(this); recipient.setText("Другой адресат"); recipient.setOnClickListener(v -> { if (toField[0] != null) toField[0].setText("Другой контакт"); else heading.setText("Адресат: Другой контакт"); }); box.addView(recipient);
        setContentView(box);
        if ("multiple".equals(mode)) box.requestFocus(); else message.requestFocus();
    }
}
