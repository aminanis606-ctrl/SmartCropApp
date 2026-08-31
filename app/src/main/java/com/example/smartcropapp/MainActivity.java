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
    private static final String TAG = "IkhlasApp";
    
    private TextView statusText;
    private Uri selectedVideoUri;
    private ActivityResultLauncher<PickVisualMediaRequest> pickMedia;
    private boolean isProcessing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        initializeStorageStructure();
        setupUI();
        setupMediaPicker();
        checkPermissions();
    }

    private void setupUI() {
        statusText = findViewById(R.id.statusText);
        Button btnStart = findViewById(R.id.btnStart);
        
        btnStart.setText("MULAI PROSES");
        btnStart.setOnClickListener(v -> {
            if (isProcessing) return;
            
            if (!checkStoragePermission()) {
                requestStoragePermission();
                return;
            }
            
            pickMedia.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.VideoOnly.INSTANCE)
                    .build());
        });
    }

    private void setupMediaPicker() {
        pickMedia = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri != null) {
                selectedVideoUri = uri;
                startAutoPipeline();
            } else {
                statusText.setText("Pemilihan video dibatalkan.");
            }
        });
    }

    private void startAutoPipeline() {
        if (selectedVideoUri == null) {
            Toast.makeText(this, "Pilih video terlebih dahulu!", Toast.LENGTH_SHORT).show();
            return;
        }

        isProcessing = true;
        statusText.setText("Memulai Pipeline Otomatis...");
        
        new Thread(() -> {
            try {
                // PASS 1
                runOnUiThread(() -> statusText.setText("Pass 1: Analisis Video..."));
                File internalAnalysis = new File(getFilesDir(), "analysis.json");
                Pass1Extractor.extract(this, selectedVideoUri, internalAnalysis);
                
                // COPY DIAGNOSTIC (SILENT FAILURE - TIDAK BOLEH MENGHENTIKAN PIPELINE)
                try {
                    FileUtils.copyFileToPublicDownloads(this, "analysis.json");
                } catch (Exception e) {
                    Log.w(TAG, "Gagal copy analysis.json (diabaikan): " + e.getMessage());
                }

                // PASS 2
                runOnUiThread(() -> statusText.setText("Pass 2: Optimasi Gerakan..."));
                File internalTrajectory = new File(getFilesDir(), "trajectory.json");
                Pass2Optimizer.optimize(internalAnalysis, internalTrajectory);
                
                // COPY DIAGNOSTIC (SILENT FAILURE)
                try {
                    FileUtils.copyFileToPublicDownloads(this, "trajectory.json");
                } catch (Exception e) {
                    Log.w(TAG, "Gagal copy trajectory.json (diabaikan): " + e.getMessage());
                }

                // PASS 3
                runOnUiThread(() -> statusText.setText("Pass 3: Rendering Video..."));
                File outputVideo = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "IkhlasApp/output_final.mp4");
                if (!outputVideo.getParentFile().exists()) outputVideo.getParentFile().mkdirs();
                Pass3Renderer.render(this, selectedVideoUri, internalTrajectory, outputVideo);

                runOnUiThread(() -> {
                    statusText.setText("SELESAI! Video tersimpan di Movies/IkhlasApp/");
                    Toast.makeText(MainActivity.this, "Proses berhasil!", Toast.LENGTH_LONG).show();
                    isProcessing = false;
                });

            } catch (Exception e) {
                Log.e(TAG, "Pipeline Fatal Error", e);
                runOnUiThread(() -> {
                    statusText.setText("Error: " + e.getMessage());
                    Toast.makeText(MainActivity.this, "Gagal: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    isProcessing = false;
                });
            }
        }).start();
    }

    private void checkPermissions() {
        if (!checkStoragePermission()) {
            requestStoragePermission();
        } else {
            statusText.setText("Siap. Klik MULAI PROSES.");
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
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 101);
        }
    }

    private void initializeStorageStructure() {
        try {
            File baseDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "IkhlasApp");
            File configDir = new File(baseDir, "config");
            if (!configDir.exists()) configDir.mkdirs();
            
            File manualSplitFile = new File(configDir, "manual_split.txt");
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
            Log.e(TAG, "Init storage error", e);
        }
    }
}
