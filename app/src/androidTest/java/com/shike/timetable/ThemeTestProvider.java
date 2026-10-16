package com.shike.timetable;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** 测试APK独立进程不依赖目标应用的Kotlin运行库。仅提供公开的测试素材。 */
public class ThemeTestProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "application/zip"; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!mode.equals("r") && !mode.equals("w") && !mode.equals("wt")) throw new IllegalArgumentException();
        File file = file(uri);
        file.getParentFile().mkdirs();
        int flags = mode.equals("r") ? ParcelFileDescriptor.MODE_READ_ONLY :
            ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE;
        return ParcelFileDescriptor.open(file, flags);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File file = file(uri);
        MatrixCursor cursor = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[] {file.getName(), file.length()});
        return cursor;
    }
    private File file(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("[a-z0-9_-]+\\.zip")) throw new IllegalArgumentException();
        return new File(getContext().getCacheDir(), name);
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { return file(uri).delete() ? 1 : 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
