package local.voice.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import android.view.WindowInsets;

/** Disposable UI contract fixture. Never connects to Telegram or sends network messages. */
public class TelegramActivity extends Activity {
    LinearLayout layout;
    String mode;
    String receiptBody;
    String page = "home";
    String activeUser;
    int backCount;
    java.util.Map<String, String> drafts = new java.util.HashMap<>();
    private TextView typed(String kind, String text, String description) {
        TextView view = new TextView(this) {
            @Override public CharSequence getAccessibilityClassName() { return "android.view.View"; }
        };
        view.setText(text); view.setContentDescription(description); view.setPadding(12, 12, 12, 12);
        return view;
    }
    private Button button(String label) { Button b = new Button(this); b.setText(label); layout.addView(b); return b; }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); mode = getIntent().getStringExtra("mode"); receiptBody = getIntent().getStringExtra("receipt_body"); layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        // API35+ draws edge to edge. Keep the simulated chat title visible below system bars.
        layout.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        setContentView(layout);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::backPage);
        if ("nested".equals(getIntent().getStringExtra("initial_screen"))) blocked(); else home();
    }
    private void counter() {
        TextView count = new TextView(this); count.setText("Переходов назад: " + backCount); layout.addView(count);
    }
    private void home() {
        page = "home"; layout.removeAllViews();
        button("Поиск").setOnClickListener(v -> search());
        counter();
    }
    private void blocked() {
        page = "blocked"; layout.removeAllViews();
        TextView title = new TextView(this); title.setText("Раздел без поиска"); layout.addView(title); counter();
    }
    private void backPage() {
        backCount++;
        if (page.equals("blocked")) blocked();
        else if (page.equals("chat")) search();
        else if (page.equals("search")) home();
        else if (page.equals("location")) chat(activeUser);
        else finish();
    }
    private void search() {
        page = "search";
        layout.removeAllViews(); EditText query = new EditText(this); query.setHint("Поиск"); layout.addView(query); query.requestFocus();
        LinearLayout people = new LinearLayout(this); people.setOrientation(LinearLayout.VERTICAL); layout.addView(people);
        query.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                people.removeAllViews();
                for (String user : new String[]{"@masha_one", "@masha_two"}) {
                    String detail = "duplicate".equals(mode) ? "Маша, контакт" : "Маша, " + user;
                    TextView row = typed("UserCell", "Маша", detail); row.setOnClickListener(v -> chat(user)); people.addView(row);
                }
            }
            public void afterTextChanged(Editable s) {}
        });
        counter();
    }
    private void chat(String user) {
        page = "chat"; activeUser = user;
        layout.removeAllViews(); TextView title = new TextView(this); title.setText("Маша"); layout.addView(title);
        TextView identity = new TextView(this); identity.setText(user); layout.addView(identity);
        layout.addView(typed("ChatMessageCell", "Привет", "Привет\nПолучено сегодня в 12:00"));
        if ("transfer_link".equals(mode)) layout.addView(typed("ChatMessageCell", "Ссылка рейса", "Ссылка рейса https://location.example.org/live?id=123&key=TESTONLY\nПолучено сегодня в 12:00"));
        if ("location".equals(mode)) {
            Button location=button("Трансляция геопозиции");
            location.setOnClickListener(v -> {
                page = "location";
                layout.removeAllViews(); TextView header=new TextView(this); header.setText("Трансляция геопозиции"); layout.addView(header);
                TextView row=new TextView(this); row.setText("Маша\nобновлена только что - 540 м отсюда"); layout.addView(row);
            });
        }
        TextView oldPending = typed("ChatMessageCell", receiptBody, receiptBody + "\nSending");
        if ("stale_status".equals(mode)) layout.addView(oldPending);
        TextView count = new TextView(this); count.setText("Нажатий отправки: 0"); layout.addView(count); int[] clicks = {0};
        EditText message = new EditText(this); message.setHint("Сообщение"); message.setText(drafts.getOrDefault(user, "empty_composer".equals(mode) ? "" : "Черновик")); layout.addView(message); message.requestFocus();
        message.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { drafts.put(user, s.toString()); }
            public void afterTextChanged(Editable s) {}
        });
        Button send = button("Отправить"); send.setOnClickListener(v -> {
            count.setText("Нажатий отправки: " + (++clicks[0]));
            String body = message.getText().toString(); message.setText("");
            String footer = "Отправлено сегодня в 12:01";
            if ("send_error".equals(mode)) footer = "Sending error";
            if ("send_pending".equals(mode)) footer = "Sending";
            if ("incoming_echo".equals(mode)) footer = "Received today at 12:01";
            if ("pending_then_sent".equals(mode)) footer = "Sending";
            if ("stale_status".equals(mode)) { oldPending.setContentDescription(receiptBody + "\nSent today at 12:01"); return; }
            if (!"no_receipt".equals(mode)) {
                TextView outgoing = typed("ChatMessageCell", body, body + "\n" + footer); layout.addView(outgoing);
                if ("pending_then_sent".equals(mode)) outgoing.postDelayed(() -> outgoing.setContentDescription(body + "\nSent today at 12:01"), 750);
            }
        });
        if ("empty_composer".equals(mode)) {
            send.setText("Записать голосовое сообщение");
            message.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                public void onTextChanged(CharSequence s, int start, int before, int count) { send.setText(s.length() == 0 ? "Записать голосовое сообщение" : "Отправить"); }
                public void afterTextChanged(Editable s) {}
            });
        }
        button("Другой адресат").setOnClickListener(v -> title.setText("Олег"));
        counter();
    }
}
