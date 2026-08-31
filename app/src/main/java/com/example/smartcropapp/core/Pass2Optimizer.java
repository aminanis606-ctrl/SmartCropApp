package com.example.smartcropapp.core;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

public class Pass2Optimizer {
    private static final String TAG = "Pass2Optimizer";
    private static final float CENTER_MIN = 0.25f;
    private static final float CENTER_MAX = 0.75f;

    private static class FrameData {
        int shotId;
        int frameIndex;
        float faceX;
        float faceY;
        float faceWidth;
        float faceHeight;
    }

    private static class CropRegion {
        int shotId; // Ditambahkan untuk keperluan serialisasi
        int frameIndex;
        float x;
        float y;
        float width;
        float height;
    }

    private static class ShotConfig {
        boolean isSplit;
        float topX;
        float bottomX;

        ShotConfig() {
            this.isSplit = false;
            this.topX = 0.25f;
            this.bottomX = 0.75f;
        }
    }

    public static void optimize(File analysisFile, File trajectoryFile) throws Exception {
        // 1. Baca file analisis dari Pass 1
        String content = java.nio.file.Files.readString(analysisFile.toPath());
        JSONObject analysis = new JSONObject(content);
        JSONArray shotsArray = analysis.getJSONArray("shots");
        
        List<FrameData> frames = new ArrayList<>();

        // Parse data frame per frame dari JSON analisis
        for (int i = 0; i < shotsArray.length(); i++) {
            JSONObject shot = shotsArray.getJSONObject(i);
            int shotId = shot.getInt("shotId");
            JSONArray track = shot.getJSONArray("track");
            
            for (int j = 0; j < track.length(); j++) {
                JSONObject point = track.getJSONObject(j);
                FrameData fd = new FrameData();
                fd.shotId = shotId;
                fd.frameIndex = point.getInt("t");
                fd.faceX = (float) point.getDouble("x");
                fd.faceY = (float) point.getDouble("y");
                fd.faceWidth = (float) point.getDouble("size");
                fd.faceHeight = (float) point.getDouble("size");
                frames.add(fd);
            }
        }

        // 2. Proses optimasi per shot
        List<CropRegion> result = new ArrayList<>();
        int totalFrames = frames.size();
        
        if (totalFrames == 0) {
            throw new Exception("Tidak ada data frame untuk dioptimasi");
        }

        // Kelompokkan frame berdasarkan shotId
        int i = 0;
        while (i < totalFrames) {
            int shotId = frames.get(i).shotId;
            int startIdx = i;
            while (i < totalFrames && frames.get(i).shotId == shotId) {
                i++;
            }
            int endIdx = i - 1;

            processShot(frames, startIdx, endIdx, shotId, result);
        }

        // 3. Tulis hasil ke trajectory.json
        writeTrajectoryJSON(result, trajectoryFile);
    }

    private static void writeTrajectoryJSON(List<CropRegion> regions, File outputFile) throws Exception {
        JSONObject root = new JSONObject();
        JSONArray shotsJson = new JSONArray();

        // Kelompokkan region berdasarkan shotId
        int i = 0;
        while (i < regions.size()) {
            int currentShotId = regions.get(i).shotId;
            JSONObject shotObj = new JSONObject();
            shotObj.put("shotId", currentShotId);
            
            // Tentukan layout berdasarkan konfigurasi
            ShotConfig config = getShotConfig(currentShotId);
            shotObj.put("layout", config.isSplit ? "split" : "single");
            
            JSONArray trackJson = new JSONArray();
            while (i < regions.size() && regions.get(i).shotId == currentShotId) {
                CropRegion r = regions.get(i);
                JSONObject point = new JSONObject();
                point.put("t", r.frameIndex);
                point.put("x", r.x);
                point.put("y", r.y);
                point.put("size", Math.max(r.width, r.height)); // Simplifikasi size
                trackJson.put(point);
                i++;
            }
            shotObj.put("track", trackJson);
            shotsJson.put(shotObj);
        }

        root.put("shots", shotsJson);

        try (FileWriter writer = new FileWriter(outputFile)) {
            writer.write(root.toString(2)); // Indentasi 2 spasi agar mudah dibaca
        }
        Log.d(TAG, "Trajectory written to: " + outputFile.getAbsolutePath());
    }

    private static void processShot(List<FrameData> frames, int startFrame, int endFrame, int shotId, List<CropRegion> result) {
        ShotConfig config = getShotConfig(shotId);
        
        if (config.isSplit) {
            // Layout split: dua panel vertikal (atas & bawah)
            for (int i = startFrame; i <= endFrame; i++) {
                FrameData fd = frames.get(i);
                
                // Panel Atas
                CropRegion topRegion = new CropRegion();
                topRegion.shotId = shotId;
                topRegion.frameIndex = i;
                topRegion.x = config.topX; 
                topRegion.y = 0.25f; 
                topRegion.width = 0.5f;
                topRegion.height = 0.5f;
                result.add(topRegion);

                // Panel Bawah
                CropRegion bottomRegion = new CropRegion();
                bottomRegion.shotId = shotId;
                bottomRegion.frameIndex = i;
                bottomRegion.x = config.bottomX;
                bottomRegion.y = 0.75f; 
                bottomRegion.width = 0.5f;
                bottomRegion.height = 0.5f;
                result.add(bottomRegion);
            }
        } else {
            // Layout single: satu panel penuh mengikuti subjek
            for (int i = startFrame; i <= endFrame; i++) {
                FrameData fd = frames.get(i);
                CropRegion region = new CropRegion();
                region.shotId = shotId;
                region.frameIndex = i;
                region.x = fd.faceX;
                region.y = fd.faceY;
                region.width = fd.faceWidth;
                region.height = fd.faceHeight;
                result.add(region);
            }
        }
    }

    private static ShotConfig getShotConfig(int shotId) {
        ShotConfig config = new ShotConfig();
        try {
            File baseDir = new File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "SmartReframe");
            File configFile = new File(baseDir, "manual_split.txt");
            
            if (!configFile.exists()) return config;

            BufferedReader reader = new BufferedReader(new FileReader(configFile));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                
                String[] parts = line.split(",");
                int idInFile = Integer.parseInt(parts[0].trim());
                
                if (idInFile == shotId) {
                    config.isSplit = true;
                    if (parts.length >= 3) {
                        config.topX = Float.parseFloat(parts[1].trim());
                        config.bottomX = Float.parseFloat(parts[2].trim());
                    }
                    reader.close();
                    return config;
                }
            }
            reader.close();
        } catch (Exception e) {
            Log.e(TAG, "Gagal membaca manual_split.txt", e);
        }
        return config;
    }
}
