package com.example.smartcropapp;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.smartcropapp.core.Pass1Extractor;
import com.example.smartcropapp.core.Pass2Optimizer;
import com.example.smartcropapp.core.Pass3Renderer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "SmartCropApp";
    private static final int REQUEST_CODE_STORAGE = 101;
    
    private TextView statusText;
    private Uri selectedVideoUri;
    private ActivityResultLauncher<String> pickMedia;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeStorageStructure();
        setupUI();
        setupPermission();
        setupMediaPicker();
    }

    private void setupUI() {
        statusText = findViewById(R.id.statusText);
        Button btnSelect = findViewById(R.id.btnPass1);
        Button btnProcess = findViewById(R.id.btnPass2);
        Button btnDummy = findViewById(R.id.btnPass3);
        
        btnSelect.setText("Pilih Video");
        btnProcess.setText("Proses Otomatis");
        btnDummy.setVisibility(android.view.View.GONE);

        btnSelect.setOnClickListener(v -> pickMedia.launch("video/*"));
        btnProcess.setOnClickListener(v -> startAutoPipeline());
    }

    private void setupMediaPicker() {
        pickMedia = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri != null) {
                selectedVideoUri = uri;
                statusText.setText("Video dipilih: " + uri.getLastPathSegment());
                Log.d(TAG, "Selected URI: " + uri);
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
            File diagnosticsDir = new File(baseDir, "diagnostics");

            if (!baseDir.exists()) baseDir.mkdirs();
            if (!diagnosticsDir.exists()) diagnosticsDir.mkdirs();

            File externalConfig = new File(baseDir, "manual_split.txt");
            if (!externalConfig.exists() || externalConfig.length() == 0) {
                AssetManager assetManager = getAssets();
                InputStream in = assetManager.open("manual_split.txt");
                OutputStream out = new FileOutputStream(externalConfig);
                byte[] buffer = new byte[1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                in.close();
                out.flush();
                out.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Gagal inisialisasi storage", e);
        }
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
            Toast.makeText(this, "Berikan izin 'Akses ke Semua File' di pengaturan.", Toast.LENGTH_LONG).show();
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
            statusText.setText("Izin diberikan. Siap memproses.");
        }
    }

    private void startAutoPipeline() {
        if (selectedVideoUri == null) {
            Toast.makeText(this, "Pilih video terlebih dahulu!", Toast.LENGTH_SHORT).show();
            return;
        }

        statusText.setText("Memulai Pipeline Otomatis...");
        new Thread(() -> {
            try {
                File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File diagnosticsDir = new File(downloadDir, "SmartReframe/diagnostics");
                
                // --- PASS 1 ---
                runOnUiThread(() -> statusText.setText("Pass 1: Menganalisis Wajah..."));
                File outputAnalysis = new File(getFilesDir(), "analysis.json");
                Pass1Extractor.extract(this, selectedVideoUri, outputAnalysis);
                File dest1 = new File(diagnosticsDir, "analysis_latest.json");
                java.nio.file.Files.copy(outputAnalysis.toPath(), dest1.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                // --- PASS 2 ---
                runOnUiThread(() -> statusText.setText("Pass 2: Mengoptimalkan Trajectory..."));
                File trajectoryOutput = new File(getFilesDir(), "trajectory.json");
                Pass2Optimizer.optimize(outputAnalysis, trajectoryOutput);
                File dest2 = new File(diagnosticsDir, "trajectory_latest.json");
                java.nio.file.Files.copy(trajectoryOutput.toPath(), dest2.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                // --- PASS 3 ---
                runOnUiThread(() -> statusText.setText("Pass 3: Merender Video Akhir..."));
                File outputVideo = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "SmartReframe/output_final.mp4");
                if (!outputVideo.getParentFile().exists()) outputVideo.getParentFile().mkdirs();
                
                Pass3Renderer.render(this, selectedVideoUri, trajectoryOutput, outputVideo);

                runOnUiThread(() -> {
                    statusText.setText("SELESAI! Video disimpan di Movies/SmartReframe/");
                    Toast.makeText(MainActivity.this, "Proses Selesai!", Toast.LENGTH_LONG).show();
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
