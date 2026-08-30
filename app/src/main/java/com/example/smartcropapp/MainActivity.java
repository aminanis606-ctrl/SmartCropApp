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
import java.io.InputStream;

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
        if (requestCode != PICK_VIDEO_REQUEST || resultCode != RESULT_OK || data == null) return;
        
        Uri videoUri = data.getData();
        if (videoUri == null) return;

        File analysisFile = new File(getFilesDir(), "analysis.json");
        File trajectoryFile = new File(getFilesDir(), "trajectory.json");
        File outputVideoFile = new File(getFilesDir(), "output_final.mp4");

        new Thread(() -> {
            try {
                runOnUiThread(() -> tvStatus.setText("PASS 1: Extracting shots..."));
                Pass1Extractor.extract(getApplicationContext(), videoUri, analysisFile);
                
                if (!analysisFile.exists()) throw new Exception("analysis.json not created!");
                runOnUiThread(() -> tvStatus.setText("PASS 1 DONE. Size: " + analysisFile.length()));

                runOnUiThread(() -> tvStatus.setText("PASS 2: Optimizing layout..."));
                Pass2Optimizer.optimize(analysisFile, trajectoryFile);
                
                if (!trajectoryFile.exists()) throw new Exception("trajectory.json not created!");
                runOnUiThread(() -> tvStatus.setText("PASS 2 DONE. Size: " + trajectoryFile.length()));

                exportDiagnostics(analysisFile, trajectoryFile);
                runOnUiThread(() -> tvStatus.setText("DIAGNOSTICS EXPORTED."));

                runOnUiThread(() -> tvStatus.setText("PASS 3: Rendering..."));
                Pass3Renderer.render(this, videoUri, trajectoryFile, outputVideoFile);
                
                if (!outputVideoFile.exists()) throw new Exception("output_final.mp4 not created!");
                
                File exportedVideo = exportVideoToMovies(outputVideoFile);
                runOnUiThread(() -> tvStatus.setText("SELESAI!\n" + exportedVideo.getAbsolutePath()));

            } catch (Exception e) {
                Log.e(TAG, "PROCESS FAILED", e);
                String trace = Log.getStackTraceString(e);
                if (trace.length() > 300) trace = trace.substring(0, 300) + "...";
                runOnUiThread(() -> tvStatus.setText("ERROR:\n" + trace));
            }
        }).start();
    }

    private void exportDiagnostics(File analysisFile, File trajectoryFile) {
        try {
            exportJsonToMediaStore(analysisFile, "analysis.json");
            exportJsonToMediaStore(trajectoryFile, "trajectory.json");
            Log.i(TAG, "DIAGNOSTIC EXPORTED");
        } catch (Exception e) {
            Log.e(TAG, "EXPORT FAILED", e);
            throw new RuntimeException("Export failed: " + e.getMessage());
        }
    }

    private File exportVideoToMovies(File sourceFile) throws Exception {
        if (sourceFile == null || !sourceFile.exists()) throw new Exception("Source video missing");
        String displayName = "output_final_" + System.currentTimeMillis() + ".mp4";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SmartReframe");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        
        Uri uri = getContentResolver().insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        if (uri == null) throw new Exception("MediaStore insert failed");

        try (InputStream in = new FileInputStream(sourceFile);
             OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new Exception("OutputStream null");
            byte[] buffer = new byte[1024 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        }
        
        ContentValues ready = new ContentValues();
        ready.put(MediaStore.Video.Media.IS_PENDING, 0);
        getContentResolver().update(uri, ready, null, null);
        
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "SmartReframe/" + displayName);
    }

    private void exportJsonToMediaStore(File sourceFile, String displayName) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Files.FileColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.Files.FileColumns.MIME_TYPE, "application/json");
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Files.FileColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SmartReframe");
            values.put(MediaStore.Files.FileColumns.IS_PENDING, 1);
        }
        
        Uri uri = getContentResolver().insert(MediaStore.Files.getContentUri("external"), values);
        if (uri == null) throw new Exception("MediaStore insert JSON failed");

        try (InputStream in = new FileInputStream(sourceFile);
             OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new Exception("OutputStream null for JSON");
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) out.write(buffer, 0, len);
        }
        
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.Files.FileColumns.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);
        }
    }
}
