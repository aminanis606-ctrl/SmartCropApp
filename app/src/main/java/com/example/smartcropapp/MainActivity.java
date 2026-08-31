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

        // Inisialisasi Struktur Folder & Self-Healing Config
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
                // Logika Pass 1 Anda di sini
                // ...
                File analysisFile = new File(getFilesDir(), "analysis.json");
                exportJsonToMediaStore(analysisFile, "analysis_" + System.currentTimeMillis() + ".json");
                runOnUiThread(() -> statusText.setText("Pass 1 Selesai."));
            } catch (Exception e) {
                runOnUiThread(() -> statusText.setText("Error Pass 1: " + e.getMessage()));
            }
        }).start();
    }

    private void runPass2(TextView statusText) {
        statusText.setText("Menjalankan Pass 2: Optimasi Trajectory...");
        new Thread(() -> {
            try {
                // Logika Pass 2 Anda di sini
                // ...
                File trajectoryFile = new File(getFilesDir(), "trajectory.json");
                exportJsonToMediaStore(trajectoryFile, "trajectory_" + System.currentTimeMillis() + ".json");
                runOnUiThread(() -> statusText.setText("Pass 2 Selesai."));
            } catch (Exception e) {
                runOnUiThread(() -> statusText.setText("Error Pass 2: " + e.getMessage()));
            }
        }).start();
    }

    private void runPass3(TextView statusText) {
        statusText.setText("Menjalankan Pass 3: Rendering Video...");
        new Thread(() -> {
            try {
                // Cari trajectory terbaru dari folder diagnostics
                File downloadDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SmartReframe/diagnostics");
                File trajectoryFile = null;
                
                if (downloadDir.exists()) {
                    File[] files = downloadDir.listFiles((dir, name) -> name.startsWith("trajectory_") && name.endsWith(".json"));
                    if (files != null && files.length > 0) {
                        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
                        trajectoryFile = files[files.length - 1];
                    }
                }

                if (trajectoryFile == null) throw new Exception("File trajectory tidak ditemukan di folder diagnostics!");

                // Logika rendering Pass 3
                // Pass3Renderer.render(this, sourceUri, trajectoryFile, outputFile);
                
                runOnUiThread(() -> statusText.setText("Pass 3 Selesai. Cek folder Movies/SmartReframe."));
            } catch (Exception e) {
                runOnUiThread(() -> statusText.setText("Error Pass 3: " + e.getMessage()));
            }
        }).start();
    }

    private void exportJsonToMediaStore(File sourceFile, String displayName) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Files.FileColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.Files.FileColumns.MIME_TYPE, "application/json");
        values.put(MediaStore.Files.FileColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SmartReframe/diagnostics");
        
        Uri uri = getContentResolver().insert(MediaStore.Files.getContentUri("external"), values);
        if (uri == null) throw new Exception("Gagal membuat entry MediaStore");
        
        try (OutputStream os = getContentResolver().openOutputStream(uri);
             InputStream is = new java.io.FileInputStream(sourceFile)) {
            
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                os.write(buffer, 0, len);
            }
        } catch (Exception e) {
            getContentResolver().delete(uri, null, null);
            throw e;
        }
    }
}
