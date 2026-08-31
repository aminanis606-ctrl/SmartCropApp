package com.example.smartcropapp;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.smartcropapp.core.Pass1Extractor;
import com.example.smartcropapp.core.Pass2Optimizer;
import com.example.smartcropapp.core.Pass3Renderer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "SmartCropApp";
    private static final int REQUEST_CODE_STORAGE = 101;
    
    private File configDir;
    private File manualSplitFile;
    private File diagnosticsDir;

    private TextView statusText;
    private Uri selectedVideoUri;
    private ActivityResultLauncher<PickVisualMediaRequest> pickMedia;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initializeStorageStructure();
        setupUI();
        setupPermission();
        setupMediaPicker();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkStoragePermission()) {
            statusText.setText("Izin diberikan. Siap memproses.");
        }
    }

    private void setupUI() {
        statusText = findViewById(R.id.statusText);
        Button btnSelect = findViewById(R.id.btnPass1);
        Button btnProcess = findViewById(R.id.btnPass2);
        Button btnDummy = findViewById(R.id.btnPass3);
        
        btnSelect.setText("Pilih Video");
        btnProcess.setText("Proses Otomatis");
        btnDummy.setVisibility(android.view.View.GONE);

        btnSelect.setOnClickListener(v -> {
            if (!checkStoragePermission()) {
                requestStoragePermission();
                return;
            }
            PickVisualMediaRequest request = new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly.INSTANCE)
                    .build();
            pickMedia.launch(request);
        });
        btnProcess.setOnClickListener(v -> startAutoPipeline());
    }

    private void setupMediaPicker() {
        pickMedia = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri != null) {
                selectedVideoUri = uri;
                statusText.setText("Video dipilih: " + uri.getLastPathSegment());
            } else {
                Toast.makeText(this, "Tidak ada video yang dipilih", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupPermission() {
        if (checkStoragePermission()) {
            statusText.setText("Siap. Silakan pilih video.");
        } else {
            requestStoragePermission();
        }
    }

    private void initializeStorageStructure() {
        try {
            File baseDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SmartReframe");
            configDir = new File(baseDir, "config");
            if (!configDir.exists()) configDir.mkdirs();
            manualSplitFile = new File(configDir, "manual_split.txt");

            diagnosticsDir = new File(baseDir, "diagnostics");
            if (!diagnosticsDir.exists()) diagnosticsDir.mkdirs();

            if (!manualSplitFile.exists() || manualSplitFile.length() == 0) {
                restoreManualSplitFromAssets();
            }
        } catch (Exception e) {
            Log.e(TAG, "Gagal inisialisasi storage", e);
        }
    }

    private void restoreManualSplitFromAssets() {
        try {
            AssetManager assetManager = getAssets();
            InputStream in = assetManager.open("manual_split.txt");
            OutputStream out = new FileOutputStream(manualSplitFile);
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            in.close();
            out.flush();
            out.close();
        } catch (Exception e) {
            Log.e(TAG, "Gagal memulihkan manual_split.txt", e);
        }
    }

    // Fungsi Copy Manual yang Stabil
    private void copyFile(File src, File dst) throws Exception {
        if (!src.exists()) {
            throw new Exception("File sumber tidak ditemukan: " + src.getAbsolutePath());
        }
        InputStream in = new FileInputStream(src);
        OutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[1024];
        int len;
        while ((len = in.read(buf)) > 0) {
            out.write(buf, 0, len);
        }
        in.close();
        out.close();
        Log.i(TAG, "Berhasil menyalin " + src.getName() + " ke " + dst.getAbsolutePath());
    }

    private boolean checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            int write = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
            int read = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE);
            return write == PackageManager.PERMISSION_GRANTED && read == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse(String.format("package:%s", getPackageName())));
                startActivity(intent);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE},
                REQUEST_CODE_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_STORAGE && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            statusText.setText("Izin diberikan.");
        }
    }

    private void startAutoPipeline() {
        if (selectedVideoUri == null) {
            Toast.makeText(this, "Pilih video terlebih dahulu!", Toast.LENGTH_SHORT).show();
            return;
        }

        statusText.setText("Memulai Pipeline...");
        new Thread(() -> {
            try {
                // --- PASS 1 ---
                runOnUiThread(() -> statusText.setText("Pass 1: Analisis Wajah..."));
                File internalAnalysis = new File(getFilesDir(), "analysis.json");
                
                // Pastikan folder internal ada
                if (!getFilesDir().exists()) getFilesDir().mkdirs();

                Pass1Extractor.extract(this, selectedVideoUri, internalAnalysis);
                
                if (!internalAnalysis.exists()) {
                    throw new Exception("Pass1Extractor gagal membuat file analysis.json di internal storage.");
                }

                File dest1 = new File(diagnosticsDir, "analysis_latest.json");
                copyFile(internalAnalysis, dest1);

                // --- PASS 2 ---
                runOnUiThread(() -> statusText.setText("Pass 2: Optimasi Trajectory..."));
                File internalTrajectory = new File(getFilesDir(), "trajectory.json");
                Pass2Optimizer.optimize(internalAnalysis, internalTrajectory);
                
                File dest2 = new File(diagnosticsDir, "trajectory_latest.json");
                copyFile(internalTrajectory, dest2);

                // --- PASS 3 ---
                runOnUiThread(() -> statusText.setText("Pass 3: Render Video..."));
                File outputVideo = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "SmartReframe/output_final.mp4");
                if (!outputVideo.getParentFile().exists()) outputVideo.getParentFile().mkdirs();
                
                Pass3Renderer.render(this, selectedVideoUri, internalTrajectory, outputVideo);

                runOnUiThread(() -> {
                    statusText.setText("SELESAI!");
                    Toast.makeText(MainActivity.this, "Video disimpan di Movies/SmartReframe/", Toast.LENGTH_LONG).show();
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusText.setText("Error: " + e.getMessage());
                    Log.e(TAG, "Pipeline Error", e);
                });
            }
        }).start();
    }
}
