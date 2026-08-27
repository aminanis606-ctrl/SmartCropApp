package com.example.smartcropapp.core;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;

public class FileUtils {

    /**
     * Menyalin file hasil render ke folder Download publik agar nampak di Galeri/File Manager.
     */
    public static File saveVideoToPublicDownload(Context context, File sourceFile, String displayName) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SmartReframe");

            Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri itemUri = context.getContentResolver().insert(collection, values);

            if (itemUri != null) {
                try (OutputStream out = context.getContentResolver().openOutputStream(itemUri);
                     FileInputStream in = new FileInputStream(sourceFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    out.flush();
                }
                // Kembalikan file tiruan atau referensi sukses
                return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SmartReframe/" + displayName);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
