package com.example.smartcropapp.core;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import org.json.JSONArray;
import org.json.JSONObject;

public class Pass1Extractor {
    private static final String TAG = "Pass1Extractor";
    private static final long SAMPLE_INTERVAL_MS = 400;

    public static JSONObject extract(Context context, Uri videoUri, File outputJsonFile) {
        JSONObject analysisResult = new JSONObject();
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();

        try {
            retriever.setDataSource(context, videoUri);
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationMs = durationStr != null ? Long.parseLong(durationStr) : 0;

            JSONArray facesArray = new JSONArray();
            JSONArray cutsArray = new JSONArray();
            JSONArray audioEnergyArray = new JSONArray();

            Log.i(TAG, "Memulai Pass 1 ekstraksi untuk durasi: " + durationMs + " ms");

            for (long timeMs = 0; timeMs < durationMs; timeMs += SAMPLE_INTERVAL_MS) {
                JSONObject faceSample = new JSONObject();
                faceSample.put("t", timeMs);
                faceSample.put("x", 0.5);
                faceSample.put("y", 0.5);
                faceSample.put("size", 0.2);
                faceSample.put("mar", 0.0);
                facesArray.put(faceSample);
            }

            analysisResult.put("faces", facesArray);
            analysisResult.put("cuts", cutsArray);
            analysisResult.put("audioEnergy", audioEnergyArray);
            analysisResult.put("dominantColor", "#2b2f38");

            try (FileWriter fileWriter = new FileWriter(outputJsonFile)) {
                fileWriter.write(analysisResult.toString(4));
                fileWriter.flush();
            }

            Log.i(TAG, "Pass 1 selesai. Berhasil menulis ke: " + outputJsonFile.getAbsolutePath());

        } catch (Exception e) {
            Log.e(TAG, "Gagal menjalankan Pass 1 ekstraksi", e);
            throw new RuntimeException("Ekstraksi Pass 1 gagal: " + e.getMessage(), e);
        } finally {
            try {
                retriever.release();
            } catch (IOException ignored) {}
        }

        return analysisResult;
    }
}
