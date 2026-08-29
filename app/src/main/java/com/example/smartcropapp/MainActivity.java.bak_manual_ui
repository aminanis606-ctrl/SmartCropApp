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
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_VIDEO_REQUEST && resultCode == RESULT_OK && data != null) {
            Uri videoUri = data.getData();
            if (videoUri != null) {
                tvStatus.setText("Menjalankan Pipeline End-to-End...");
                
                File analysisFile = new File(getFilesDir(), "analysis.json");
                File trajectoryFile = new File(getFilesDir(), "trajectory.json");
                File outputVideoFile = new File(getFilesDir(), "output_final.mp4");

                new Thread(() -> {
                    try {
                        Pass1Extractor.extract(getApplicationContext(), videoUri, analysisFile);
                        Pass2Optimizer.optimize(analysisFile, trajectoryFile);
                        exportDiagnostics(analysisFile, trajectoryFile);
                        Pass3Renderer.render(getApplicationContext(), videoUri, trajectoryFile, outputVideoFile);

                        String exportError = exportToGallery(outputVideoFile, analysisFile);
                        long finalSize = outputVideoFile.exists() ? outputVideoFile.length() : 0;
                        
                        runOnUiThread(() -> {
                            String msg = (exportError == null)
                                    ? "RENDER + EXPORT SUKSES!\nCek folder Movies/SmartReframe\n"
                                    : "Render OK, GAGAL export:\n" + (exportError.length() > 400 ? exportError.substring(0, 400) + "..." : exportError) + "\n";
                            tvStatus.setText(msg + "Ukuran: " + finalSize + " bytes");
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "Pipeline Gagal", e);
                        String fullTrace = Log.getStackTraceString(e);
                        if (fullTrace.length() > 400) {
                            fullTrace = fullTrace.substring(0, 400) + "...";
                        }
                        String finalTrace = fullTrace;
                        runOnUiThread(() -> tvStatus.setText("GAGAL:\n" + finalTrace));
                    }
                }).start();
            }
        }
    }

    private String exportToGallery(File sourceFile, File analysisFile) {
        try {
            String layoutLabel = "SINGLE";
            int shotCount = 0;
            int splitCount = 0;
            int singleCount = 0;

            try {
                String json = new String(
                        java.nio.file.Files.readAllBytes(analysisFile.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);

                org.json.JSONObject root =
                        new org.json.JSONObject(json);

                org.json.JSONArray shots =
                        root.optJSONArray("shots");

                if (shots != null) {
                    shotCount = shots.length();

                    for (int i = 0; i < shots.length(); i++) {
                        org.json.JSONObject shot = shots.optJSONObject(i);

                        if (shot == null) continue;

                        String layout =
                                shot.optString("layout", "single");

                        if ("split".equalsIgnoreCase(layout)) {
                            splitCount++;
                        } else {
                            singleCount++;
                        }
                    }
                }

                if (splitCount > 0) {
                    layoutLabel =
                            "SHOTS" + shotCount +
                            "_SPLIT" + splitCount +
                            "_SINGLE" + singleCount;
                } else {
                    layoutLabel =
                            "SHOTS" + shotCount +
                            "_SINGLE" + singleCount;
                }

            } catch (Exception diagnosticError) {
                Log.w(
                        TAG,
                        "Gagal membaca shot diagnostic",
                        diagnosticError);
            }

            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, layoutLabel + "_" + System.currentTimeMillis() + ".mp4");
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            // Diubah ke DIRECTORY_MOVIES karena Android melarang DIRECTORY_DOWNLOADS untuk Video.Media
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SmartReframe");

            Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri itemUri = MainActivity.this.getContentResolver().insert(collection, values);
            if (itemUri == null) return "insert() mengembalikan null";

            try (OutputStream out = MainActivity.this.getContentResolver().openOutputStream(itemUri);
                 FileInputStream in = new FileInputStream(sourceFile)) {
                if (out == null) return "openOutputStream() null untuk uri: " + itemUri;
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            }
            return null; // Sukses tanpa error
        } catch (Exception e) {
            Log.e(TAG, "Gagal ekspor ke MediaStore", e);
            return Log.getStackTraceString(e);
        }
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
