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
                        // 1. Pass 1
                        Pass1Extractor.extract(getApplicationContext(), videoUri, analysisFile);
                        
                        // 2. Pass 2
                        Pass2Optimizer.optimize(analysisFile, trajectoryFile);

                        // 3. Pass 3 (Real OpenGL Crop Rendering)
                        Pass3Renderer.render(getApplicationContext(), videoUri, trajectoryFile, outputVideoFile);

                        // 4. Salin ke MediaStore Public dengan pelaporan status jujur
                        boolean exported = exportToGallery(outputVideoFile);

                        long finalSize = outputVideoFile.exists() ? outputVideoFile.length() : 0;
                        runOnUiThread(() -> {
                            tvStatus.setText((exported ? "RENDER + EXPORT SUKSES!\n" : "Render OK, TAPI GAGAL export ke galeri.\n")
                                    + "Ukuran: " + finalSize + " bytes\nPath: " + outputVideoFile.getAbsolutePath());
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "Pipeline Gagal", e);
                        String fullTrace = Log.getStackTraceString(e);
                        // Perbaikan: gunakan .length() bukan .length
                        if (fullTrace.length() > 500) {
                            fullTrace = fullTrace.substring(0, 500) + "...";
                        }
                        String finalTrace = fullTrace;
                        runOnUiThread(() -> tvStatus.setText("GAGAL:\n" + finalTrace));
                    }
                }).start();
            }
        }
    }

    private boolean exportToGallery(File sourceFile) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, "SmartReframe_" + System.currentTimeMillis() + ".mp4");
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SmartReframe");

            Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri itemUri = getContentResolver().insert(collection, values);
            if (itemUri == null) throw new RuntimeException("MediaStore insert() mengembalikan null");

            try (OutputStream out = getContentResolver().openOutputStream(itemUri);
                 FileInputStream in = new FileInputStream(sourceFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Gagal ekspor ke MediaStore", e);
            return false;
        }
    }
}
