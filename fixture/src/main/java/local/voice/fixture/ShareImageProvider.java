package local.voice.fixture;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Synthetic image owned by the disposable emulator fixture, not a user's screenshot. */
public class ShareImageProvider extends ContentProvider {
    public boolean onCreate() { return true; }
    public String getType(Uri uri) { return "image/png"; }
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        if (!"/sample.png".equals(uri.getPath()) || !"r".equals(mode)) throw new java.io.FileNotFoundException();
        File file=new File(getContext().getCacheDir(),"synthetic-share.png");
        if (!file.isFile()) {
            Bitmap image=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888); image.eraseColor(0xff123440);
            try (FileOutputStream stream=new FileOutputStream(file)) { image.compress(Bitmap.CompressFormat.PNG,100,stream); }
            catch (IOException ex) { throw new java.io.FileNotFoundException(); } finally { image.recycle(); }
        }
        return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
    }
    public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order) { return null; }
    public Uri insert(Uri uri,ContentValues values) { throw new UnsupportedOperationException(); }
    public int update(Uri uri,ContentValues values,String selection,String[] args) { throw new UnsupportedOperationException(); }
    public int delete(Uri uri,String selection,String[] args) { throw new UnsupportedOperationException(); }
}
