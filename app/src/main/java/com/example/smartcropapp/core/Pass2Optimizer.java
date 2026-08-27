package com.example.smartcropapp.core;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class Pass2Optimizer {
    private static final String TAG = "Pass2Optimizer";
    
    // Batas tengah kosong yang harus dihindari saat ada dua wajah
    private static final float CENTER_MIN = 0.40f;
    private static final float CENTER_MAX = 0.60f;
    
    // Hysteresis: berapa sample berturut-turut dibutuhkan untuk pindah target (Kiri <-> Kanan)
    private static final int HYSTERESIS_THRESHOLD = 3; 

    public static void optimize(File analysisFile, File trajectoryFile) throws Exception {
        String content;
        try (FileInputStream fis = new FileInputStream(analysisFile)) {
            byte[] data = new byte[(int) analysisFile.length()];
            int read = fis.read(data);
            content = new String(data, 0, read, StandardCharsets.UTF_8);
        }

        JSONObject root = new JSONObject(content);
        JSONArray shots = root.getJSONArray("shots");

        JSONArray finalFaces = new JSONArray();

        for (int s = 0; s < shots.length(); s++) {
            JSONObject shot = shots.getJSONObject(s);
            JSONArray samples = shot.getJSONArray("samples");

            Log.i(TAG, "Processing Shot " + shot.optInt("shotId")
                    + " samples=" + samples.length()
                    + " startMs=" + shot.optLong("startMs"));

            int n = samples.length();
            if (n == 0) continue;

            float[] lockedX = new float[n];
            float[] lockedY = new float[n];
            float[] lockedSize = new float[n];
            long[] times = new long[n];

            float lastX = 0.3f; 
            float lastY = 0.4f;
            float lastSize = 0.3f;
            boolean hasEverHadFace = false;

            String currentSide = "LEFT"; 
            String pendingSide = "LEFT";
            int sideCounter = 0;

            // --- TAHAP 1: DISCRETE TARGET SELECTION & LOCKING ---
            for (int i = 0; i < n; i++) {
                JSONObject sample = samples.getJSONObject(i);
                times[i] = sample.optLong("t", 0);
                JSONArray faces = sample.getJSONArray("faces");

                if (faces.length() == 0) {
                    lockedX[i] = lastX;
                    lockedY[i] = lastY;
                    lockedSize[i] = lastSize;
                } else if (faces.length() == 1) {
                    JSONObject f0 = faces.getJSONObject(0);
                    float x = (float) f0.optDouble("x", lastX);
                    float y = (float) f0.optDouble("y", lastY);
                    float size = (float) f0.optDouble("size", lastSize);

                    lockedX[i] = x;
                    lockedY[i] = y;
                    lockedSize[i] = size;

                    lastX = x; lastY = y; lastSize = size;
                    currentSide = (x < 0.5f) ? "LEFT" : "RIGHT";
                    pendingSide = currentSide;
                    sideCounter = 0;
                    hasEverHadFace = true;
                } else {
                    JSONObject leftFace = null;
                    JSONObject rightFace = null;
                    float minX = 2f, maxX = -1f;

                    for (int f = 0; f < faces.length(); f++) {
                        JSONObject cand = faces.getJSONObject(f);
                        float cx = (float) cand.optDouble("x", 0.5f);
                        if (cx < minX) { minX = cx; leftFace = cand; }
                        if (cx > maxX) { maxX = cx; rightFace = cand; }
                    }

                    String targetSide = currentSide;
                    JSONObject chosenFace = ("RIGHT".equals(currentSide)) ? rightFace : leftFace;

                    if (leftFace != null && rightFace != null) {
                        float lx = (float) leftFace.optDouble("x", 0.3f);
                        float rx = (float) rightFace.optDouble("x", 0.7f);
                        
                        String detectedSide = (Math.abs(lx - lastX) < Math.abs(rx - lastX)) ? "LEFT" : "RIGHT";
                        
                        if (!detectedSide.equals(pendingSide)) {
                            pendingSide = detectedSide;
                            sideCounter = 1;
                        } else {
                            sideCounter++;
                        }

                        if (sideCounter >= HYSTERESIS_THRESHOLD) {
                            currentSide = pendingSide;
                        }
                        
                        targetSide = currentSide;
                        chosenFace = ("RIGHT".equals(targetSide)) ? rightFace : leftFace;
                    } else if (leftFace != null) {
                        chosenFace = leftFace;
                    } else if (rightFace != null) {
                        chosenFace = rightFace;
                    }

                    float x = (float) chosenFace.optDouble("x", lastX);
                    float y = (float) chosenFace.optDouble("y", lastY);
                    float size = (float) chosenFace.optDouble("size", lastSize);

                    if (x >= CENTER_MIN && x <= CENTER_MAX) {
                        x = ("LEFT".equals(targetSide)) ? 0.30f : 0.70f;
                    }

                    lockedX[i] = x;
                    lockedY[i] = y;
                    lockedSize[i] = size;

                    lastX = x; lastY = y; lastSize = size;
                    hasEverHadFace = true;
                }
            }

            if (!hasEverHadFace) {
                for (int i = 0; i < n; i++) {
                    lockedX[i] = 0.5f; lockedY[i] = 0.4f; lockedSize[i] = 0.3f;
                }
                Log.w(TAG, "Shot " + shot.optInt("shotId") + ": tidak ada wajah sama sekali, fallback center.");
            }

            // --- TAHAP 2: CAMERA MOTION SMOOTHING (EMA pada Target Ter-lock) ---
            float smoothX = lockedX[0];
            float smoothY = lockedY[0];
            float smoothSize = lockedSize[0];
            float alpha = 0.4f; 

            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    smoothX = smoothX + alpha * (lockedX[i] - smoothX);
                    smoothY = smoothY + alpha * (lockedY[i] - smoothY);
                    smoothSize = smoothSize + alpha * (lockedSize[i] - smoothSize);
                }

                JSONObject faceObj = new JSONObject();
                faceObj.put("t", times[i]);
                faceObj.put("x", (double) smoothX);
                faceObj.put("y", (double) smoothY);
                faceObj.put("size", (double) smoothSize);
                finalFaces.put(faceObj);
            }
        }

        JSONObject outputRoot = new JSONObject();
        outputRoot.put("faces", finalFaces);

        try (FileOutputStream fos = new FileOutputStream(trajectoryFile)) {
            fos.write(outputRoot.toString().getBytes());
        }

        Log.i(TAG, "Pass 2 V3 (Target Lock + Hysteresis + Anti-Center) selesai -> " + trajectoryFile.getAbsolutePath());
    }
}
