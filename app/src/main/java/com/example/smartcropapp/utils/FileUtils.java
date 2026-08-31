package com.example.smartcropapp.utils;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public final class FileUtils {
    private static final String TAG = "FileUtils";

    public static boolean copyFileToPublicDownloads(Context context, String sourceFileName) {
        File src = new File(context.getFilesDir(), sourceFileName);
        
        // Cek sumber terlebih dahulu
        if (!src.exists() || src.length() == 0) {
            Log.e(TAG, "Source file missing or empty: " + src.getAbsolutePath());
            return false;
        }

        // Cek Izin
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Log.e(TAG, "PERMISSION DENIED: MANAGE_EXTERNAL_STORAGE is not granted.");
                return false;
            }
        }

        // Coba beberapa variasi path tujuan untuk kompatibilitas perangkat
        File[] destDirs = {
            new File(Environment.getExternalStorageDirectory(), "Download/SmartReframe/diagnostics"),
            new File("/storage/emulated/0/Download/SmartReframe/diagnostics")
        };

        File destDir = null;
        for (File dir : destDirs) {
            if (dir.exists() || dir.mkdirs()) {
                destDir = dir;
                break;
            }
        }

        if (destDir == null || !destDir.canWrite()) {
            Log.e(TAG, "Failed to find or create a writable destination directory.");
            return false;
        }

        File destFile = new File(destDir, sourceFileName);
        File tmpFile = new File(destDir, sourceFileName + ".tmp");

        try {
            // Hapus file lama jika ada
            if (destFile.exists()) destFile.delete();
            if (tmpFile.exists()) tmpFile.delete();

            // Proses Copy
            try (FileInputStream fis = new FileInputStream(src);
                 BufferedInputStream bis = new BufferedInputStream(fis);
                 FileOutputStream fos = new FileOutputStream(tmpFile);
                 BufferedOutputStream bos = new BufferedOutputStream(fos)) {

                byte[] buffer = new byte[8192];
                int len;
                while ((len = bis.read(buffer)) != -1) {
                    bos.write(buffer, 0, len);
                }
                bos.flush();
                fos.getFD().sync();
            }

            // Rename atomic
            if (!tmpFile.renameTo(destFile)) {
                // Fallback manual copy jika rename gagal (cross-device link error)
                try (FileInputStream fis = new FileInputStream(tmpFile);
                     FileOutputStream fos = new FileOutputStream(destFile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = fis.read(buffer)) != -1) {
                        fos.write(buffer, 0, len);
                    }
                }
                tmpFile.delete();
            }

            if (destFile.exists() && destFile.length() > 0) {
                Log.i(TAG, "Successfully copied: " + destFile.getAbsolutePath());
                return true;
            } else {
                Log.e(TAG, "Destination file is empty or missing after copy.");
                return false;
            }

        } catch (IOException e) {
            Log.e(TAG, "IO Error during copy", e);
            if (tmpFile.exists()) tmpFile.delete();
            return false;
        }
    }
}
