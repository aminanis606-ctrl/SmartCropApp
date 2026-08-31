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
        
        if (!src.exists() || src.length() == 0) {
            Log.e(TAG, "Source file missing or empty: " + src.getAbsolutePath());
            return false;
        }

        // Cek Izin: Coba MANAGE_EXTERNAL_STORAGE dulu, fallback ke state eksternal biasa
        boolean hasPermission = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            hasPermission = Environment.isExternalStorageManager();
            if (!hasPermission) {
                Log.w(TAG, "MANAGE_EXTERNAL_STORAGE not granted. Trying legacy write access.");
                // Fallback check for older permission style if possible
                File test = new File(Environment.getExternalStorageDirectory(), "test_write.tmp");
                try {
                    test.createNewFile();
                    test.delete();
                    hasPermission = true;
                } catch (IOException e) {
                    Log.e(TAG, "Legacy write test failed. Permission denied.");
                }
            }
        } else {
            hasPermission = Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState());
        }

        if (!hasPermission) {
            Log.e(TAG, "PERMISSION DENIED: Cannot write to external storage.");
            return false;
        }

        File destDir = new File(Environment.getExternalStorageDirectory(), "Download/SmartReframe/diagnostics");
        if (!destDir.exists()) destDir.mkdirs();

        File destFile = new File(destDir, sourceFileName);
        File tmpFile = new File(destDir, sourceFileName + ".tmp");

        try {
            if (destFile.exists()) destFile.delete();
            if (tmpFile.exists()) tmpFile.delete();

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

            if (!tmpFile.renameTo(destFile)) {
                // Fallback manual copy
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
            }
        } catch (IOException e) {
            Log.e(TAG, "IO Error during copy", e);
        } finally {
            if (tmpFile.exists()) tmpFile.delete();
        }
        return false;
    }
}
