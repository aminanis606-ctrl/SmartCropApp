package com.example.smartcropapp;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.example.smartcropapp.core.Pass1Extractor;
import com.example.smartcropapp.core.Pass2Optimizer;
import com.example.smartcropapp.core.Pass3Renderer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PICK_VIDEO_REQUEST = 101;
    private static final int PERMISSION_REQUEST_CODE = 202;
    private TextView tvStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnSelectVideo = findViewById(R.id.btnSelectVideo);
        tvStatus = findViewById(R.id.tvStatus);

        btnSelectVideo.setOnClickListener(v -> checkPermissionAndPick());
    }

    private String getRequiredPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return Manifest.permission.READ_MEDIA_VIDEO;
        } else {
            return Manifest.permission.READ_EXTERNAL_STORAGE;
        }
    }

    private void checkPermissionAndPick() {
        String permission = getRequiredPermission();
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            openVideoPicker();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{permission}, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openVideoPicker();
            } else {
                tvStatus.setText("Izin akses video ditolak.");
            }
        }
    }

    private void openVideoPicker() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("video/*");
        startActivityForResult(Intent.createChooser(intent, "Pilih Video"), PICK_VIDEO_REQUEST);
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            @Nullable Intent data) {

        super.onActivityResult(
                requestCode,
                resultCode,
                data);

        if (requestCode != PICK_VIDEO_REQUEST ||
                resultCode != RESULT_OK ||
                data == null) {
            return;
        }

        Uri videoUri =
                data.getData();

        if (videoUri == null) {
            return;
        }

        tvStatus.setText(
                "PASS 1: Mendeteksi shot...");

        File analysisFile =
                new File(
                        getFilesDir(),
                        "analysis.json");

        File trajectoryFile =
                new File(
                        getFilesDir(),
                        "trajectory.json");

        File outputVideoFile =
                new File(
                        getFilesDir(),
                        "output_final.mp4");

        new Thread(() -> {

            try {

                /*
                 * PASS 1
                 *
                 * Hanya mencari batas shot.
                 * Layout otomatis sudah dimatikan.
                 */
                Pass1Extractor.extract(
                        getApplicationContext(),
                        videoUri,
                        analysisFile);

                runOnUiThread(() ->
                        showManualLayoutEditor(
                                analysisFile,
                                trajectoryFile,
                                outputVideoFile,
                                videoUri));

            } catch (Exception e) {

                Log.e(
                        TAG,
                        "PASS 1 Gagal",
                        e);

                String trace =
                        Log.getStackTraceString(e);

                if (trace.length() > 500) {
                    trace =
                            trace.substring(0, 500)
                                    + "...";
                }

                String finalTrace = trace;

                runOnUiThread(() ->
                        tvStatus.setText(
                                "GAGAL PASS 1:\n"
                                        + finalTrace));
            }

        }).start();
    }


    /*
     * ============================================================
     * MANUAL SHOT LAYOUT EDITOR
     * ============================================================
     *
     * Semua shot dimulai sebagai SINGLE.
     *
     * User dapat memilih SPLIT secara manual
     * hanya pada shot yang benar-benar membutuhkan split.
     *
     * Setelah TERAPKAN:
     *
     * analysis.json
     *      ↓
     * Pass2Optimizer
     *      ↓
     * trajectory.json
     *      ↓
     * Pass3Renderer
     */
    /*
     * ============================================================
     * AUTO REFRAFRAME FLOW
     * ============================================================
     *
     * Manual Layout Shot dihapus dari jalur normal.
     *
     * Pass 1 -> analysis.json
     * Pass 2 -> AUTO SINGLE/SPLIT -> trajectory.json
     * Pass 3 -> render
     */
    private void showManualLayoutEditor(
            File analysisFile,
            File trajectoryFile,
            File outputVideoFile,
            Uri videoUri) {

        tvStatus.setText("PASS 2: Auto Layout + Optimasi...");

        new Thread(() -> {

            try {

                /*
                 * Pass2Optimizer sekarang menentukan layout
                 * secara otomatis per-shot.
                 */
                Pass2Optimizer.optimize(
                        analysisFile,
                        trajectoryFile);

                runOnUiThread(() ->
                        tvStatus.setText(
                                "PASS 3: Auto Render..."));

                /*
                 * Gunakan pipeline renderer yang sudah ada.
                 *
                 * Signature Pass3Renderer dipertahankan dari
                 * pipeline aplikasi saat ini.
                 */
                Pass3Renderer.render(
                        this,
                        videoUri,
                        trajectoryFile,
                        outputVideoFile);

                        File exportedVideo =
                                exportVideoToMovies(
                                        outputVideoFile);

                runOnUiThread(() -> {

                    tvStatus.setText(
                            "SELESAI: Auto Reframe\n" +
                            exportedVideo.getAbsolutePath());

                    Log.i(
                            TAG,
                            "AUTO REFRAFRAME DONE: " +
                            exportedVideo.getAbsolutePath());
                });

            } catch (Exception e) {

                Log.e(
                        TAG,
                        "AUTO REFRAFRAME FAILED",
                        e);

                String trace =
                        Log.getStackTraceString(e);

                if (trace.length() > 1000) {
                    trace =
                            trace.substring(0, 1000)
                                    + "...";
                }

                String finalTrace = trace;

                runOnUiThread(() ->
                        tvStatus.setText(
                                "GAGAL AUTO REFRAFRAME:\n" +
                                finalTrace));
            }

        }).start();
    }

    private void exportDiagnostics(File analysisFile, File trajectoryFile) {
        try {
            exportJsonToMediaStore(analysisFile, "analysis.json");
            exportJsonToMediaStore(trajectoryFile, "trajectory.json");

            Log.i(TAG, "DIAGNOSTIC EXPORTED TO Movies/SmartReframe");

        } catch (Exception e) {
            Log.e(TAG, "DIAGNOSTIC EXPORT FAILED", e);
        }
    }

    private File exportVideoToMovies(
            File sourceFile) throws Exception {

        if (sourceFile == null || !sourceFile.exists()) {
            throw new Exception(
                    "File hasil render tidak ditemukan: " +
                    sourceFile);
        }

        String displayName =
                "output_final_" +
                System.currentTimeMillis() +
                ".mp4";

        android.content.ContentValues values =
                new android.content.ContentValues();

        values.put(
                MediaStore.Video.Media.DISPLAY_NAME,
                displayName);

        values.put(
                MediaStore.Video.Media.MIME_TYPE,
                "video/mp4");

        values.put(
                MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES +
                        "/SmartReframe");

        values.put(
                MediaStore.Video.Media.IS_PENDING,
                1);

        android.content.ContentResolver resolver =
                getContentResolver();

        android.net.Uri collection =
                MediaStore.Video.Media.getContentUri(
                        MediaStore.VOLUME_EXTERNAL_PRIMARY);

        android.net.Uri uri =
                resolver.insert(
                        collection,
                        values);

        if (uri == null) {
            throw new Exception(
                    "MediaStore gagal membuat output video.");
        }

        boolean success = false;

        try {
            try (
                    java.io.InputStream in =
                            new java.io.FileInputStream(sourceFile);

                    java.io.OutputStream out =
                            resolver.openOutputStream(uri)
            ) {
                if (out == null) {
                    throw new Exception(
                            "MediaStore gagal membuka output stream.");
                }

                byte[] buffer = new byte[1024 * 1024];
                int count;

                while ((count = in.read(buffer)) != -1) {
                    out.write(buffer, 0, count);
                }

                out.flush();
            }

            android.content.ContentValues ready =
                    new android.content.ContentValues();

            ready.put(
                    MediaStore.Video.Media.IS_PENDING,
                    0);

            resolver.update(
                    uri,
                    ready,
                    null,
                    null);

            success = true;

            Log.i(
                    TAG,
                    "VIDEO EXPORTED TO Movies/SmartReframe: " +
                    displayName);

            return new File(
                    Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_MOVIES),
                    "SmartReframe/" + displayName);

        } finally {
            if (!success) {
                resolver.delete(uri, null, null);
            }
        }
    }

    private void exportJsonToMediaStore(File sourceFile, String displayName)
            throws Exception {

        android.content.ContentValues values =
                new android.content.ContentValues();

        values.put(
                android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME,
                displayName);

        values.put(
                android.provider.MediaStore.Files.FileColumns.MIME_TYPE,
                "application/json");

        if (android.os.Build.VERSION.SDK_INT >= 29) {
            values.put(
                    android.provider.MediaStore.Files.FileColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_MOVIES
                            + "/SmartReframe");
            values.put(
                    android.provider.MediaStore.Files.FileColumns.IS_PENDING,
                    1);
        }

        android.net.Uri uri =
                getContentResolver().insert(
                        android.provider.MediaStore.Files.getContentUri("external"),
                        values);

        if (uri == null) {
            throw new Exception("MediaStore gagal membuat file: " + displayName);
        }

        try (
                java.io.InputStream in =
                        new java.io.FileInputStream(sourceFile);
                java.io.OutputStream out =
                        getContentResolver().openOutputStream(uri)
        ) {
            if (out == null) {
                throw new Exception("OutputStream null: " + displayName);
            }

            byte[] buffer = new byte[8192];
            int len;

            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }

            out.flush();

        } finally {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues done =
                        new android.content.ContentValues();

                done.put(
                        android.provider.MediaStore.Files.FileColumns.IS_PENDING,
                        0);

                getContentResolver().update(uri, done, null, null);
            }
        }
    }

}
