package dev.herdr.android.data;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;

/** Platform-only read-only fixture: the standalone test APK process has no app Kotlin runtime. */
public final class SyntheticChatAttachmentProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "screenshot.png".equals(uri.getLastPathSegment()) || "invalid.png".equals(uri.getLastPathSegment()) ? "image/png" : "text/plain"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) row[i] = OpenableColumns.DISPLAY_NAME.equals(columns[i]) ? uri.getLastPathSegment() : 0L;
        cursor.addRow(row);
        return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("This fixture is read-only");
        final byte[] bytes = data(uri);
        try {
            final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            new Thread(() -> {
                try (ParcelFileDescriptor.AutoCloseOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) { out.write(bytes); }
                catch (java.io.IOException ignored) { /* A canceled bounded reader can close its pipe. */ }
            }).start();
            return pipe[0];
        } catch (java.io.IOException failure) { throw new FileNotFoundException("Synthetic pipe could not open"); }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    private byte[] data(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if ("invalid.png".equals(name)) return "This is not an image".getBytes(StandardCharsets.UTF_8);
        if ("note.txt".equals(name)) return "Synthetic reviewed file.\nNo real user data.\n".getBytes(StandardCharsets.UTF_8);
        if (!"screenshot.png".equals(name)) throw new FileNotFoundException("Unknown synthetic fixture");
        Bitmap bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.rgb(30, 90, 150));
        for (int y = 8; y <= 30; y++) for (int x = 8; x <= 55; x++) bitmap.setPixel(x, y, Color.rgb(230, 190, 40));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); return out.toByteArray(); }
        finally { bitmap.recycle(); }
    }
}
