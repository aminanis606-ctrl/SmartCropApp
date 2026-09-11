package com.example.smartcropapp;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.media.MediaMetadataRetriever;
import android.provider.Settings;
import android.provider.OpenableColumns;
import android.database.Cursor;
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
import com.example.smartcropapp.smartreframe.SmartReframeOrchestrator;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

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

    private String getVideoId(Uri uri) {
        if (uri == null) return "unknown";

        try (Cursor c = getContentResolver().query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {

            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                java.util.regex.Matcher m =
                        java.util.regex.Pattern.compile("(\\d+)").matcher(name);

                if (m.find()) return m.group(1);
            }
        } catch (Exception e) {
            Log.w(TAG, "Gagal mengambil video ID", e);
        }

        return "unknown";
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
                SmartReframeOrchestrator orchestrator =
                        new SmartReframeOrchestrator(
                                this,
                                selectedVideoUri,
                                getVideoId(selectedVideoUri));

                // PHASE 1 / PASS 1
                runOnUiThread(() ->
                        statusText.setText("Pass 1: Analisis Video..."));

                File internalAnalysis =
                        new File(getFilesDir(), "analysis.json");

                orchestrator.runPass1(internalAnalysis);

                // OUTPUT PLANNING
                File outputDir =
                        new File(
                                Environment.getExternalStoragePublicDirectory(
                                        Environment.DIRECTORY_MOVIES),
                                "IkhlasApp");

                if (!outputDir.exists()) {
                    outputDir.mkdirs();
                }

                File outputVideo =
                        createUniqueOutputFile(
                                selectedVideoUri,
                                internalAnalysis,
                                outputDir);

                // PHASE 2 / PASS 2 + PASS 3
                runOnUiThread(() ->
                        statusText.setText("Pass 2: Optimasi Gerakan..."));

                File internalTrajectory =
                        new File(getFilesDir(), "trajectory.json");

                runOnUiThread(() ->
                        statusText.setText("Pass 3: Rendering Video..."));

                String diagnosticBase =
                        outputVideo.getName();

                int dot = diagnosticBase.lastIndexOf('.');

                if (dot > 0) {
                    diagnosticBase =
                            diagnosticBase.substring(0, dot);
                }

                orchestrator.runPass2AndPass3(
                        internalTrajectory,
                        outputVideo);

                try {
                    FileUtils.copyFileToPublicDownloads(
                            this,
                            "analysis.json",
                            diagnosticBase + "_analysis.json");

                    FileUtils.copyFileToPublicDownloads(
                            this,
                            "trajectory.json",
                            diagnosticBase + "_trajectory.json");
                } catch (Exception e) {
                    Log.w(
                            TAG,
                            "Gagal copy diagnostic (diabaikan): "
                                    + e.getMessage());
                }

                runOnUiThread(() -> {
                    statusText.setText(
                            "SELESAI! Video tersimpan di Movies/IkhlasApp/");

                    Toast.makeText(
                            MainActivity.this,
                            "Proses berhasil!",
                            Toast.LENGTH_LONG).show();

                    isProcessing = false;
                });

            } catch (Exception e) {
                Log.e(TAG, "Pipeline Fatal Error", e);

                runOnUiThread(() -> {
                    statusText.setText("Error: " + e.getMessage());

                    Toast.makeText(
                            MainActivity.this,
                            "Gagal: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();

                    isProcessing = false;
                });
            }
        }).start();
    }

    private File createUniqueOutputFile(
            Uri sourceUri,
            File analysisFile,
            File outputDir) throws Exception {

        MediaMetadataRetriever mmr =
                new MediaMetadataRetriever();

        try {
            mmr.setDataSource(this, sourceUri);

            String durationStr =
                    mmr.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_DURATION);

            long durationMs =
                    durationStr == null
                            ? 0L
                            : Long.parseLong(durationStr);

            long totalSeconds =
                    Math.max(0L, durationMs / 1000L);

            long minutes = totalSeconds / 60L;
            long seconds = totalSeconds % 60L;

            String duration =
                    String.format(
                            Locale.US,
                            "%02d:%02d",
                            minutes,
                            seconds);

            org.json.JSONObject root =
                    new org.json.JSONObject(
                            readTextFile(analysisFile));

            org.json.JSONArray shots =
                    root.optJSONArray("shots");

            int shotCount =
                    shots == null ? 0 : shots.length();

            int splitCount = 0;

            if (shots != null) {
                for (int i = 0; i < shots.length(); i++) {
                    if ("split".equals(
                            shots.getJSONObject(i)
                                    .optString("layout"))) {
                        splitCount++;
                    }
                }
            }

            int singleCount =
                    Math.max(0, shotCount - splitCount);

            String sourceName =
                    "video";

            String uriName =
                    sourceUri.getLastPathSegment();

            if (uriName != null && !uriName.isEmpty()) {
                int slash = uriName.lastIndexOf('/');
                if (slash >= 0) {
                    uriName = uriName.substring(slash + 1);
                }

                int dot = uriName.lastIndexOf('.');
                if (dot > 0) {
                    uriName = uriName.substring(0, dot);
                }

                if (!uriName.isEmpty()) {
                    sourceName = uriName;
                }
            }

            sourceName =
                    sourceName.replaceAll(
                            "[^A-Za-z0-9_-]",
                            "_");

            String base =
                    String.format(
                            Locale.US,
                            "%s_SHOT%d_SPLIT%d_SINGLE%d_%s",
                            getVideoId(sourceUri),
                            shotCount,
                            splitCount,
                            singleCount,
                            duration);

            File result =
                    new File(
                            outputDir,
                            base + ".mp4");

            int index = 1;

            while (result.exists()) {
                result =
                        new File(
                                outputDir,
                                String.format(
                                        Locale.US,
                                        "%s_%03d.mp4",
                                        base,
                                        index++));
            }

            return result;

        } finally {
            mmr.release();
        }
    }

    private String readTextFile(File file) throws Exception {
        try (InputStream in =
                     new java.io.FileInputStream(file)) {

            java.io.ByteArrayOutputStream out =
                    new java.io.ByteArrayOutputStream();

            byte[] buffer = new byte[4096];
            int n;

            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }

            return out.toString("UTF-8");
        }
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
            File baseDir = new File(
                    Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS),
                    "IkhlasApp");

            File configDir = new File(baseDir, "config");
            if (!configDir.exists()) configDir.mkdirs();

            File manualSplitFile =
                    new File(configDir, "manual_split.txt");

            AssetManager assetManager = getAssets();
            InputStream in =
                    assetManager.open("manual_split.txt");

            OutputStream out =
                    new FileOutputStream(manualSplitFile, false);

            byte[] buffer = new byte[1024];
            int read;

            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }

            in.close();
            out.close();

        } catch (Exception e) {
            Log.e(TAG, "Init storage error", e);
        }
    }
}
