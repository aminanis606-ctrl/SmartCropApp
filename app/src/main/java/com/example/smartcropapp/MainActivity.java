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
import com.example.smartcropapp.utils.FileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "SmartCropApp";
    private static final int REQUEST_CODE_STORAGE = 101;
    
    private File configDir;
    private File manualSplitFile;
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
        if (checkStoragePermission()) statusText.setText("Izin diberikan. Siap memproses.");
    }

    private void setupUI() {
        statusText = findViewById(R.id.statusText);
        Button btnSelect = findViewById(R.id.btnPass1);
        Button btnProcess = findViewById(R.id.btnPass2);
        
        btnSelect.setText("Pilih Video");
        btnProcess.setText("Proses Otomatis");

        btnSelect.setOnClickListener(v -> {
            if (!checkStoragePermission()) {
                requestStoragePermission();
                return;
            }
            pickMedia.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly.INSTANCE)
                    .build());
        });
        btnProcess.setOnClickListener(v -> startAutoPipeline());
    }

    private void setupMediaPicker() {
        pickMedia = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri != null) {
                selectedVideoUri = uri;
                statusText.setText("Video dipilih: " + uri.getLastPathSegment());
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

            if (!manualSplitFile.exists() || manualSplitFile.length() == 0) {
                AssetManager assetManager = getAssets();
                InputStream in = assetManager.open("manual_split.txt");
                OutputStream out = new FileOutputStream(manualSplitFile);
                byte[] buffer = new byte[1024];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                in.close(); out.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Gagal inisialisasi storage", e);
        }
    }

    private boolean checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
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
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_CODE_STORAGE);
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
                runOnUiThread(() -> statusText.setText("Pass 1: Analisis Wajah..."));
                File internalAnalysis = new File(getFilesDir(), "analysis.json");
                Pass1Extractor.extract(this, selectedVideoUri, internalAnalysis);
                
                // Gunakan FileUtils yang robust
                if (!FileUtils.copyFileToPublicDownloads(this, "analysis.json")) {
                    throw new Exception("Gagal menyalin analysis.json ke folder publik.");
                }

                runOnUiThread(() -> statusText.setText("Pass 2: Optimasi Trajectory..."));
                File internalTrajectory = new File(getFilesDir(), "trajectory.json");
                Pass2Optimizer.optimize(internalAnalysis, internalTrajectory);
                
                if (!FileUtils.copyFileToPublicDownloads(this, "trajectory.json")) {
                    throw new Exception("Gagal menyalin trajectory.json ke folder publik.");
                }

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
