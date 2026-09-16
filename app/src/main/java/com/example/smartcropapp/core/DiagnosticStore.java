package com.example.smartcropapp.core;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class DiagnosticStore {

    private static final String RELATIVE_PATH =
            Environment.DIRECTORY_DOWNLOADS
                    + "/SmartReframe/diagnostics";

    private DiagnosticStore() {
    }

    public static Uri writeText(
            Context context,
            String fileName,
            String content) throws Exception {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw new IllegalStateException(
                    "DiagnosticStore requires Android 10+");
        }

        ContentResolver resolver = context.getContentResolver();

        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
        values.put(
                MediaStore.Downloads.RELATIVE_PATH,
                RELATIVE_PATH);

        Uri uri = resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values);

        if (uri == null) {
            throw new IllegalStateException(
                    "Failed to create diagnostic file: " + fileName);
        }

        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) {
                throw new IllegalStateException(
                        "Failed to open diagnostic file: " + fileName);
            }

            out.write(content.getBytes(StandardCharsets.UTF_8));
        }

        return uri;
    }
}
