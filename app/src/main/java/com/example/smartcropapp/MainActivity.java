package com.example.smartcropapp;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.smartcropapp.core.Pass1Analyzer;
import com.example.smartcropapp.core.Pass2Optimizer;
import com.example.smartcropapp.core.Pass3Renderer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Comparator;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "SmartCropApp";
    private static final int REQUEST_CODE_STORAGE = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeStorageStructure();

        TextView statusText = findViewById(R.id.statusText);
        Button btnPass1 = findViewById(R.id.btnPass1);
        Button btnPass2 = findViewById(R.id.btnPass2);
        Button btnPass3 = findViewById(R.id.btnPass3);

        if (checkStoragePermission()) {
            statusText.setText("Izin penyimpanan diberikan. Siap memproses.");
        } else {
            requestStoragePermission();
        }

        btnPass1.setOnClickListener(v -> runPass1(statusText));
        btnPass2.setOnClickListener(v -> runPass2(statusText));
        btnPass3.setOnClickListener(v -> runPass3(statusText));
    }

    private void initializeStorageStructure() {
        try {
            File baseDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SmartReframe");
            File diagnosticsDir = new File(baseDir, "diagnostics");

            if (!baseDir.exists()) baseDir.mkdirs();
            if (!diagnosticsDir.exists()) diagnosticsDir.mkdirs();

            // SELF-HEALING: Salin manual_split.txt dari assets jika belum ada atau kosong
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
                Log.d(TAG, "manual_split.txt berhasil disinkronisasi dari assets.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Gagal inisialisasi struktur storage", e);
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
            Toast.makeText(this, "Izin diberikan!", Toast.LENGTH_SHORT).show();
        }
    }

    private void runPass1(TextView statusText) {
        statusText.setText("Menjalankan Pass 1: Analisis Wajah...");
        new Thread(() -> {
            try {
                Pass1Analyzer analyzer = new Pass1Analyzer(this);
                File analysisFile = analyzer.analyze();
                
                File diagnosticsDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SmartReframe/diagnostics");
                File dest = new File(diagnosticsDir, "analysis_" + System.currentTimeMillis() + ".json");
                java.nio.file.Files.copy(analysisFile.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                
                runOnUiThread(() -> statusText.setText("Pass 1 Selesai. Hasil disimpan di diagnostics."));
            } catch (Exception e) {
                runOnUiThread(() -> statusText.setText("Error Pass 1: " + e.getMessage()));
                Log.e(TAG, "Pass 1 Error", e);
            }
        }).start();
    }

    private void runPass2(TextView statusText) {
        statusText.setText("Menjalankan Pass 2: Optimasi Trajectory...");
        new Thread(() -> {
            try {
                // Cari file analysis terbaru
                File diagnosticsDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SmartReframe/diagnostics");
                File[] files = diagnosticsDir.listFiles((dir, name) -> name.startsWith("analysis_") && name.endsWith(".json"));
                if (files == null || files.length == 0) throw new Exception("File analysis tidak ditemukan!");
                
                Arrays.sort(files, Comparator.comparingLong(File::lastModified));
                File latestAnalysis = files[files.length - 1];

                Pass2Optimizer optimizer = new Pass2Optimizer(this);
                File trajectoryFile = optimizer.optimize(latestAnalysis);

                File dest = new File(diagnosticsDir, "trajectory_" + System.currentTimeMillis() + ".json");
                java.nio.file.Files.copy(trajectoryFile.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                runOnUiThread(() -> statusText.setText("Pass 2 Selesai. Cek layout di file trajectory."));
            } catch (Exception e) {
                runOnUiThread(() -> statusText.setText("Error Pass 2: " + e.getMessage()));
                Log.e(TAG, "Pass 2 Error", e);
            }
        }).start();
    }

    private void runPass3(TextView statusText) {
        statusText.setText("Menjalankan Pass 3: Rendering Video...");
        new Thread(() -> {
            try {
                File diagnosticsDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SmartReframe/diagnostics");
                File[] files = diagnosticsDir.listFiles((dir, name) -> name.startsWith("trajectory_") && name.endsWith(".json"));
                if (files == null || files.length == 0) throw new Exception("File trajectory tidak ditemukan!");

                Arrays.sort(files, Comparator.comparingLong(File::lastModified));
                File latestTrajectory = files[files.length - 1];

                // Contoh: Menggunakan video dummy atau video terakhir yang dipilih
                // Anda mungkin perlu menambahkan logic pemilihan video di sini
                Uri sourceUri = null; // Ganti dengan logic pemilihan video Anda
                
                Pass3Renderer renderer = new Pass3Renderer(this);
                // renderer.render(sourceUri, latestTrajectory, outputFile); 

                runOnUiThread(() -> statusText.setText("Pass 3 Selesai. Cek folder Movies/SmartReframe."));
            } catch (Exception e) {
                runOnUiThread(() -> statusText.setText("Error Pass 3: " + e.getMessage()));
                Log.e(TAG, "Pass 3 Error", e);
            }
        }).start();
    }
}
