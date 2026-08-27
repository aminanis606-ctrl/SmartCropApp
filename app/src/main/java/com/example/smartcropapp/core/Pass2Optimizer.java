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
    private static final int SMOOTH_WINDOW = 3;

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

            Log.i(TAG, "Shot " + shot.optInt("shotId")
                    + " samples=" + samples.length()
                    + " startMs=" + shot.optLong("startMs"));

            float[] rawX = new float[samples.length()];
            float[] rawY = new float[samples.length()];
            float[] rawSize = new float[samples.length()];
            long[] times = new long[samples.length()];
            boolean hasEverHadFace = false;
            float lastX = 0.5f, lastY = 0.4f, lastSize = 0.3f;

            for (int i = 0; i < samples.length(); i++) {
                JSONObject sample = samples.getJSONObject(i);
                times[i] = sample.optLong("t", 0);
                JSONArray faces = sample.getJSONArray("faces");

                if (faces.length() == 0) {
                    rawX[i] = lastX; rawY[i] = lastY; rawSize[i] = lastSize;
                } else {
                    JSONObject best = faces.getJSONObject(0);
                    float bestSize = (float) best.optDouble("size", 0);
                    for (int f = 1; f < faces.length(); f++) {
                        JSONObject cand = faces.getJSONObject(f);
                        float candSize = (float) cand.optDouble("size", 0);
                        if (candSize > bestSize) { best = cand; bestSize = candSize; }
                    }
                    rawX[i] = (float) best.optDouble("x", lastX);
                    rawY[i] = (float) best.optDouble("y", lastY);
                    rawSize[i] = (float) best.optDouble("size", lastSize);
                    lastX = rawX[i]; lastY = rawY[i]; lastSize = rawSize[i];
                    hasEverHadFace = true;
                }

                Log.i(TAG, "t=" + times[i]
                        + " faces=" + faces.length()
                        + " targetX=" + rawX[i]
                        + " targetSize=" + rawSize[i]);
            }

            if (!hasEverHadFace) {
                for (int i = 0; i < rawX.length; i++) { rawX[i] = 0.5f; rawY[i] = 0.4f; rawSize[i] = 0.3f; }
                Log.w(TAG, "Shot " + shot.optInt("shotId") + ": tidak ada wajah sama sekali, fallback center.");
            }

            for (int i = 0; i < rawX.length; i++) {
                float sumX = 0, sumY = 0, sumSize = 0, weightSum = 0;
                int lo = Math.max(0, i - SMOOTH_WINDOW);
                int hi = Math.min(rawX.length - 1, i + SMOOTH_WINDOW);
                for (int j = lo; j <= hi; j++) {
                    float weight = 1f / (1 + Math.abs(i - j));
                    sumX += rawX[j] * weight;
                    sumY += rawY[j] * weight;
                    sumSize += rawSize[j] * weight;
                    weightSum += weight;
                }
                JSONObject faceObj = new JSONObject();
                faceObj.put("t", times[i]);
                faceObj.put("x", sumX / weightSum);
                faceObj.put("y", sumY / weightSum);
                faceObj.put("size", sumSize / weightSum);
                finalFaces.put(faceObj);
            }
        }

        JSONObject outputRoot = new JSONObject();
        outputRoot.put("faces", finalFaces);

        try (FileOutputStream fos = new FileOutputStream(trajectoryFile)) {
            fos.write(outputRoot.toString().getBytes());
        }

        Log.i(TAG, "Pass 2 selesai, " + shots.length() + " shot diproses -> " + trajectoryFile.getAbsolutePath());
    }
}
