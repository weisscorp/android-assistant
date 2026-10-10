package local.voice.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.time.LocalDate;

public class WeatherActivity extends Activity {
    String selected = "Wrong City";
    boolean menuMode, nestedMode;
    int stage = 0;
    LinearLayout root, results;
    TextView text(String value, LinearLayout parent) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(20); t.setPadding(12, 6, 12, 6); parent.addView(t); return t;
    }
    LinearLayout root(int number) {
        stage = number;
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(12, 18, 12, 18); setContentView(root); return root;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        android.content.SharedPreferences prefs = getSharedPreferences("weather", 0);
        if (getIntent().getBooleanExtra("reset", false)) prefs.edit().putString("place", "Wrong City")
            .putBoolean("menu", getIntent().getBooleanExtra("menu", false))
            .putBoolean("nested", getIntent().getBooleanExtra("nested", false)).commit();
        selected = prefs.getString("place", "Wrong City");
        menuMode = prefs.getBoolean("menu", false); nestedMode = prefs.getBoolean("nested", false);
        home();
    }
    void home() {
        root(0);
        if (menuMode) {
            TextView button = text("Меню", root); button.setContentDescription("Настройки");
            button.setOnClickListener(v -> { root(3); text("Настройки", root).setOnClickListener(w -> settings()); });
        } else text("Настройки", root).setOnClickListener(v -> settings());
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setClickable(true); root.addView(card);
        card.setOnClickListener(v -> { root(5); text("Модели погоды", root); });
        text(selected, card);
        String[] names = {"пн", "вт", "ср", "чт", "пт", "сб", "вс"};
        for (int i=0;i<3;i++) {
            LocalDate date = LocalDate.now().plusDays(i);
            text(names[date.getDayOfWeek().getValue()-1] + ", " + String.format("%02d", date.getDayOfMonth()), card)
                .setOnClickListener(v -> { root(6); text("Нажата надпись дня, а не карточка", root); });
            text((17+i) + "°", card);
        }
    }
    void settings() {
        settings(1);
    }
    void settings(int stageNumber) {
        root(stageNumber);
        if (stageNumber == 4) text("Выбранное место сохранено", root);
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); root.addView(row);
        text("Местоположение", row); text(selected, row); row.setOnClickListener(v -> search());
        text("Отправить деньги", root); // Must never be acted on by the weather adapter.
    }
    void search() {
        root(2);
        EditText field = new EditText(this); field.setHint("Поиск"); field.setId(R.id.search_src_text); root.addView(field);
        results = new LinearLayout(this); results.setOrientation(LinearLayout.VERTICAL); root.addView(results);
        field.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            public void onTextChanged(CharSequence s,int start,int before,int count) {
                results.removeAllViews();
                if (s.toString().startsWith("москв")) { result("Москва"); result("Москва аэропорт"); }
                else if (s.toString().startsWith("берлин")) { result("Берлин Центральный"); result("Берлин Западный"); }
                else result("Another Place");
            }
            public void afterTextChanged(Editable s) {}
        });
    }
    void result(String value) {
        TextView title = text(value, results); title.setId(R.id.searchLocationTitle);
        title.setOnClickListener(v -> { selected=value; getSharedPreferences("weather", 0).edit().putString("place", value).commit(); settings(nestedMode ? 4 : 1); });
    }
    @Override public void onBackPressed() { if (stage==2 || stage==4) settings(); else if(stage==1 || stage==3) home(); else super.onBackPressed(); }
}
