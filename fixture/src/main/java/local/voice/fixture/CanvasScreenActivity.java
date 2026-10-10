package local.voice.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** Deliberately draws text without accessible nodes, like a weather chart. */
public class CanvasScreenActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(new View(this) {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                canvas.drawColor(Color.rgb(28, 48, 64));
                paint.setColor(Color.WHITE); paint.setTextSize(42 * getResources().getDisplayMetrics().density);
                canvas.drawText("Погода", 24, 120, paint);
                paint.setTextSize(28 * getResources().getDisplayMetrics().density);
                canvas.drawText("Температура 17 C", 24, 240, paint);
                canvas.drawText("Влажность 82 %", 24, 350, paint);
                canvas.drawText("Rain 40 %", 24, 460, paint);
            }
        });
    }
}
