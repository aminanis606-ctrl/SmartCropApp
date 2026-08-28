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
    private static final float CENTER_MIN = 0.40f;
    private static final float CENTER_MAX = 0.60f;
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
        JSONArray outputShots = new JSONArray();

        for (int s = 0; s < shots.length(); s++) {
            JSONObject shot = shots.getJSONObject(s);
            String layout = shot.optString("layout", "single");
            int shotId = shot.optInt("shotId", s);
            long startMs = shot.optLong("startMs", 0);

            JSONObject outShot = new JSONObject();
            outShot.put("shotId", shotId);
            outShot.put("startMs", startMs);
            outShot.put("layout", layout);

            if ("split".equals(layout)) {
                // Mode split: crop tetap kiri/kanan, tanpa tracking dinamis
                outShot.put("topX", 0.25f);
                outShot.put("topY", 0.5f);
                outShot.put("bottomX", 0.75f);
                outShot.put("bottomY", 0.5f);
                Log.i(TAG, "Shot " + shotId + " (split): panel tetap kiri=0.25 kanan=0.75");
            } else {
                JSONArray samples = shot.getJSONArray("samples");
                outShot.put("track", processSingleShot(shotId, samples));
            }

            outputShots.put(outShot);
        }

        JSONObject outputRoot = new JSONObject();
        outputRoot.put("shots", outputShots);

        try (FileOutputStream fos = new FileOutputStream(trajectoryFile)) {
            fos.write(outputRoot.toString().getBytes());
        }

        Log.i(TAG, "Pass 2 selesai -> " + trajectoryFile.getAbsolutePath());
    }

    private static JSONArray processSingleShot(int shotId, JSONArray samples) throws Exception {
        int n = samples.length();
        JSONArray track = new JSONArray();
        if (n == 0) return track;

        float[] lockedX = new float[n];
        float[] lockedY = new float[n];
        float[] lockedSize = new float[n];
        long[] times = new long[n];

        float lastX = 0.3f, lastY = 0.4f, lastSize = 0.3f;
        boolean hasEverHadFace = false;
        String currentSide = "LEFT";
        String pendingSide = "LEFT";
        int sideCounter = 0;

        for (int i = 0; i < n; i++) {
            JSONObject sample = samples.getJSONObject(i);
            times[i] = sample.optLong("t", 0);
            JSONArray faces = sample.getJSONArray("faces");

            if (faces.length() == 0) {
                lockedX[i] = lastX; lockedY[i] = lastY; lockedSize[i] = lastSize;
            } else if (faces.length() == 1) {
                JSONObject f0 = faces.getJSONObject(0);
                float x = (float) f0.optDouble("x", lastX);
                float y = (float) f0.optDouble("y", lastY);
                float size = (float) f0.optDouble("size", lastSize);
                lockedX[i] = x; lockedY[i] = y; lockedSize[i] = size;
                lastX = x; lastY = y; lastSize = size;
                currentSide = (x < 0.5f) ? "LEFT" : "RIGHT";
                pendingSide = currentSide;
                sideCounter = 0;
                hasEverHadFace = true;
            } else {
                JSONObject leftFace = null, rightFace = null;
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

                    if (!detectedSide.equals(pendingSide)) { pendingSide = detectedSide; sideCounter = 1; }
                    else { sideCounter++; }

                    if (sideCounter >= HYSTERESIS_THRESHOLD) currentSide = pendingSide;

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

                lockedX[i] = x; lockedY[i] = y; lockedSize[i] = size;
                lastX = x; lastY = y; lastSize = size;
                hasEverHadFace = true;
            }
        }

        if (!hasEverHadFace) {
            for (int i = 0; i < n; i++) { lockedX[i] = 0.5f; lockedY[i] = 0.4f; lockedSize[i] = 0.3f; }
            Log.w(TAG, "Shot " + shotId + ": tidak ada wajah, fallback center.");
        }

        float smoothX = lockedX[0], smoothY = lockedY[0], smoothSize = lockedSize[0];
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
            track.put(faceObj);
        }

        return track;
    }
}
