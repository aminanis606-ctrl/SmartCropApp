package com.example.smartcropapp.utils;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
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
        File downloadsRoot = Environment.getExternalStorageDirectory();
        File destDir = new File(downloadsRoot, "Download/SmartReframe/diagnostics");

        // 1) Check source availability
        if (!src.exists()) {
            Log.e(TAG, "Source file does not exist: " + src.getAbsolutePath());
            return false;
        }
        if (!src.isFile()) {
            Log.e(TAG, "Source is not a file: " + src.getAbsolutePath());
            return false;
        }
        long srcLen = src.length();
        if (srcLen == 0L) {
            Log.w(TAG, "Source file is empty (length=0): " + src.getAbsolutePath());
            return false;
        }

        // 2) Check external manage permission (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Log.e(TAG, "App is not granted MANAGE_EXTERNAL_STORAGE.");
                return false;
            }
        } else {
            String state = Environment.getExternalStorageState();
            if (!Environment.MEDIA_MOUNTED.equals(state)) {
                Log.e(TAG, "External storage not mounted or not writable: state=" + state);
                return false;
            }
        }

        // 3) Ensure destination directory exists
        if (!destDir.exists()) {
            boolean made = destDir.mkdirs();
            if (!made && !destDir.exists()) {
                Log.e(TAG, "Failed to create destination directory: " + destDir.getAbsolutePath());
                return false;
            }
        }

        // 4) Copy using buffered streams into a temp file
        File destFile = new File(destDir, sourceFileName);
        File tmpFile = new File(destDir, sourceFileName + ".tmp-" + System.currentTimeMillis());
        final int BUF_SIZE = 8 * 1024;

        try (FileInputStream fis = new FileInputStream(src);
             BufferedInputStream bis = new BufferedInputStream(fis, BUF_SIZE);
             FileOutputStream fos = new FileOutputStream(tmpFile);
             BufferedOutputStream bos = new BufferedOutputStream(fos, BUF_SIZE)) {

            byte[] buffer = new byte[BUF_SIZE];
            int read;
            while ((read = bis.read(buffer)) != -1) {
                bos.write(buffer, 0, read);
            }
            bos.flush();
            fos.getFD().sync();

        } catch (IOException ioe) {
            Log.e(TAG, "IOException during copy: " + ioe.getMessage(), ioe);
            if (tmpFile.exists()) tmpFile.delete();
            return false;
        }

        // 5) Move temp -> final
        if (tmpFile.exists()) {
            if (destFile.exists()) destFile.delete();
            
            boolean renamed = tmpFile.renameTo(destFile);
            if (!renamed) {
                // Fallback stream-copy
                try (FileInputStream fis2 = new FileInputStream(tmpFile);
                     BufferedInputStream bis2 = new BufferedInputStream(fis2, BUF_SIZE);
                     FileOutputStream fos2 = new FileOutputStream(destFile);
                     BufferedOutputStream bos2 = new BufferedOutputStream(fos2, BUF_SIZE)) {
                    
                    byte[] buffer = new byte[BUF_SIZE];
                    int r;
                    while ((r = bis2.read(buffer)) != -1) {
                        bos2.write(buffer, 0, r);
                    }
                    bos2.flush();
                    fos2.getFD().sync();
                } catch (IOException fallbackEx) {
                    Log.e(TAG, "Fallback copy failed", fallbackEx);
                    return false;
                } finally {
                    if (tmpFile.exists()) tmpFile.delete();
                }
            }
            
            if (!destFile.exists() || destFile.length() == 0L) {
                Log.e(TAG, "Final destination file missing or empty after move.");
                return false;
            }
            Log.i(TAG, "Copy succeeded: " + destFile.getAbsolutePath());
            return true;
        }
        return false;
    }
}
