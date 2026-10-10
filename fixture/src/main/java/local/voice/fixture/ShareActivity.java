package local.voice.fixture;

import android.app.Activity;
import android.content.Intent;
import android.content.ClipData;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;

/** A real cross-UID Android share with a temporary read grant. */
public class ShareActivity extends Activity {
    public void onCreate(Bundle state) {
        super.onCreate(state);
        Button share=new Button(this); share.setText("Поделиться тестовым изображением"); setContentView(share);
        share.setOnClickListener(view -> {
            Uri uri=Uri.parse("content://local.voice.fixture.shared/sample.png");
            Intent intent=new Intent(Intent.ACTION_SEND).setType("image/png").setClassName("local.voice","local.voice.SourcesActivity")
                .putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("Synthetic image",uri));
            startActivity(intent); finish();
        });
    }
}
